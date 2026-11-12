package cn.iocoder.yudao.module.dataforge.service.lineage;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.LineageColumnSource;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.LineageTable;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.LineageTransform;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.QueryLineageResult;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.SqlLineageAnalyzer;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.SqlLineageRequest;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.SqlLineageStatus;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessTaskDetailDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.lineage.LineageMarkerMapper;
import cn.iocoder.yudao.module.dataforge.service.access.AccessTypeMappingPlan;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfig;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfigCodec;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageNodeRefDTO;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageReportReqDTO;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageSourceTypeEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据同步血缘：以运行事实为准。
 * <p>
 * 取当前发布修订下最近 {@value #RECENT_RUNS} 次已结束运行中同步成功的表级明细，得到实际的源表 → 目标表；
 * 字段级取明细记录的类型映射计划（同步按列名复制，源列与目标列同名）。源端为自定义 SQL 时，
 * 用分析器解析 SQL 得到真实上游表与字段来源。修订号为最近一次已结束运行的编号，每次运行结束后重新上报，
 * 内容不变时 governance 返回 NOOP。实时同步或 API 接入未绑定平台源数据源时不解析。
 */
@Component
public class DataAccessLineageCollector implements LineageSourceCollector {

    /** 合并的最近运行次数：避免单次运行个别表失败时血缘短暂缺失。 */
    static final int RECENT_RUNS = 5;
    private static final int SOURCE_TYPE_SQL = 2;

    @Resource
    private LineageMarkerMapper markerMapper;
    @Resource
    private DataAccessMapper accessMapper;
    @Resource
    private LineageDatasourceResolver datasources;
    @Resource
    private LineageCatalogProvider catalogs;

    /**
     * 返回来源类型。
     */
    @Override
    public String sourceType() {
        return LineageSourceTypeEnum.DATA_ACCESS.name();
    }

    /**
     * 返回解析器版本。
     */
    @Override
    public int analyzerVersion() {
        return LineageReportVersions.DATA_ACCESS;
    }

    /**
     * 有界扫描待上报的数据同步任务。
     */
    @Override
    public List<LineageMarkerRow> pending(int limit) {
        List<LineageMarkerRow> rows = new ArrayList<>(markerMapper.selectDataAccessPending(analyzerVersion(), limit));
        if (rows.size() < limit) {
            rows.addAll(markerMapper.selectDataAccessRemoved(limit - rows.size()));
        }
        return rows;
    }

    /**
     * 按运行事实构造上报内容。
     */
    @Override
    public LineageReportReqDTO build(LineageMarkerRow row) {
        long revision = row.getSourceRevision() == null ? 0L : row.getSourceRevision();
        DataAccessDO access = row.isRemovedSource() ? null : accessMapper.selectById(row.getId());
        if (access == null) {
            return LineageReportBuilder.removed(sourceType(), row.getId(), row.getSourceName(), revision,
                    analyzerVersion());
        }
        LineageReportBuilder builder = new LineageReportBuilder(sourceType(), access.getId(), access.getTaskName(),
                revision, analyzerVersion());
        if (access.getSourceDatabaseId() == null || access.getTargetDatabaseId() == null) {
            return builder.status(SqlLineageStatus.UNSUPPORTED).issue("DATASOURCE_UNBOUND",
                    "实时同步或 API 接入未绑定平台源/目标数据源，暂不解析血缘", null).build();
        }
        List<DataAccessTaskDetailDO> details = markerMapper.selectRecentSuccessfulDetails(access.getId(),
                access.getPublishedRevision(), RECENT_RUNS);
        if (details.isEmpty()) {
            return builder.issue("NO_SUCCESSFUL_RUN", "最近运行没有同步成功的表，暂无血缘", null).build();
        }
        Long sourceId = access.getSourceDatabaseId();
        Long targetId = access.getTargetDatabaseId();
        Map<String, LineageNodeRefDTO> targets = new LinkedHashMap<>();
        for (DataAccessTaskDetailDO detail : details) {
            if (StrUtil.isBlank(detail.getTargetTableName())) {
                continue;
            }
            LineageNodeRefDTO target = LineageNodeRefDTO.table(targetId, StrUtil.emptyToNull(detail.getTargetSchemaName()),
                    detail.getTargetTableName());
            targets.putIfAbsent(target.getSchemaName() + "\u0000" + target.getTableName(), target);
            if (Integer.valueOf(SOURCE_TYPE_SQL).equals(access.getSourceType())
                    || StrUtil.isBlank(detail.getSourceTableName())) {
                continue;
            }
            LineageNodeRefDTO source = LineageNodeRefDTO.table(sourceId, StrUtil.emptyToNull(detail.getSourceSchemaName()),
                    detail.getSourceTableName());
            builder.tableEdge(source, target, LineageReportBuilder.runtimeOrigin(), null);
            addTypeMappingColumns(detail, source, target, builder);
        }
        if (Integer.valueOf(SOURCE_TYPE_SQL).equals(access.getSourceType())) {
            addSqlSources(access, sourceId, new ArrayList<>(targets.values()), builder);
        }
        return builder.build();
    }

    /**
     * 上报后按旧标记 CAS 更新。
     */
    @Override
    public boolean mark(LineageMarkerRow row, String newKey) {
        return markerMapper.updateDataAccessKey(row.getId(), row.getLineageReportKey(), newKey) == 1;
    }

    /**
     * 明细的类型映射计划：每列源与目标同名，记为 IDENTITY。
     */
    private static void addTypeMappingColumns(DataAccessTaskDetailDO detail, LineageNodeRefDTO source,
                                              LineageNodeRefDTO target, LineageReportBuilder builder) {
        if (StrUtil.isBlank(detail.getTypeMappingPlan())) {
            builder.degrade("TYPE_MAPPING_MISSING", "部分同步明细没有字段映射记录，只保留表级血缘");
            return;
        }
        AccessTypeMappingPlan plan;
        try {
            plan = JsonUtils.parseObject(detail.getTypeMappingPlan(), AccessTypeMappingPlan.class);
        } catch (RuntimeException unreadable) {
            builder.degrade("TYPE_MAPPING_INVALID", "部分同步明细的字段映射记录无法读取，只保留表级血缘");
            return;
        }
        if (plan == null) {
            builder.degrade("TYPE_MAPPING_MISSING", "部分同步明细没有字段映射记录，只保留表级血缘");
            return;
        }
        if (plan.truncated()) {
            builder.degrade("TYPE_MAPPING_TRUNCATED", "部分同步明细的字段映射记录被截断，字段级血缘不完整");
        }
        for (AccessTypeMappingPlan.Column column : plan.columns()) {
            if (StrUtil.isNotBlank(column.column())) {
                builder.columnEdge(source, column.column(), target, column.column(), LineageTransform.IDENTITY,
                        false, false, null);
            }
        }
    }

    /**
     * 自定义 SQL 源：解析发布配置中的源 SQL，真实上游表连到本次运行的目标表，输出字段按同名写入目标。
     */
    private void addSqlSources(DataAccessDO access, Long sourceId, List<LineageNodeRefDTO> targets,
                               LineageReportBuilder builder) {
        AccessTaskConfig config;
        try {
            config = AccessTaskConfigCodec.read(StrUtil.blankToDefault(access.getPublishedConfigJson(), "{}"));
        } catch (RuntimeException invalid) {
            builder.degrade("CONFIG_INVALID", "发布配置无法读取，自定义 SQL 源的上游未解析");
            return;
        }
        var datasource = datasources.resolve(sourceId);
        if (datasource == null || datasource.product() == null || !SqlLineageAnalyzer.supports(datasource.product())) {
            builder.degrade("SQL_SOURCE_UNSUPPORTED", "源数据源产品暂不支持解析自定义 SQL 的血缘");
            return;
        }
        for (AccessTaskConfig.Source source : config.getSource()) {
            if (StrUtil.isBlank(source.getSourceSql())) {
                continue;
            }
            SqlLineageRequest request = SqlLineageRequest.of(datasource.product(), datasource.defaultSchema(),
                    source.getSourceSql());
            QueryLineageResult result = SqlLineageAnalyzer.analyzeQuery(request);
            if (!result.readTables().isEmpty()) {
                result = SqlLineageAnalyzer.analyzeQuery(request.withCatalog(
                        catalogs.prefetch(sourceId, result.readTables()).catalog()));
            }
            builder.status(result.status() == SqlLineageStatus.FAILED ? SqlLineageStatus.PARTIAL : result.status());
            result.issues().forEach(issue -> builder.issue(issue.code(), issue.message(), null));
            for (LineageNodeRefDTO target : targets) {
                for (LineageTable read : result.readTables()) {
                    builder.tableEdge(LineageReportBuilder.table(sourceId, read), target,
                            LineageReportBuilder.parsedOrigin(), null);
                }
                for (QueryLineageResult.Column column : result.columns()) {
                    if (column.name() == null || column.wildcard()) {
                        continue;
                    }
                    for (LineageColumnSource upstream : column.sources()) {
                        builder.columnEdge(LineageReportBuilder.table(sourceId, upstream.table()), upstream.column(),
                                target, column.name(), upstream.transform(), upstream.ambiguous(), upstream.masking(),
                                null);
                    }
                }
                for (LineageColumnSource indirect : result.indirectSources()) {
                    builder.columnEdge(LineageReportBuilder.table(sourceId, indirect.table()), indirect.column(),
                            target, "*", indirect.transform(), indirect.ambiguous(), indirect.masking(), null);
                }
            }
        }
    }
}
