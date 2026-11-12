package cn.iocoder.yudao.module.dataforge.service.lineage;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.LineageTable;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.LineageTableEdge;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.SqlLineageAnalyzer;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.SqlLineageRequest;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.SqlLineageResult;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.SqlLineageStatus;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.DevTaskLineagePreviewRespVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskVersionDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevTaskVersionMapper;
import cn.iocoder.yudao.module.dataforge.enums.dev.DevTaskTypeEnum;
import cn.iocoder.yudao.module.dataforge.model.dev.DevExecutionConfig;
import cn.iocoder.yudao.module.dataforge.service.dev.DevTaskDefinition;
import cn.iocoder.yudao.module.dataforge.service.dev.DevTaskSnapshot;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageNodeRefDTO;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageReportReqDTO;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageSourceTypeEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 开发任务血缘：解析当前保存版本的 SQL，并合并手工维护的表血缘。
 * <p>
 * SQL 任务按绑定数据源的产品方言解析；Spark SQL 任务按 Hive 方言解析；Flink、Shell、JAR、Python
 * 与存储过程任务暂不解析，只上报手工血缘并标记为 UNSUPPORTED。表结构来自 governance 已采集的元数据，
 * 不连接源库。
 */
@Component
public class DevTaskLineageCollector implements LineageSourceCollector {

    @Resource
    private DevTaskMapper taskMapper;
    @Resource
    private DevTaskVersionMapper versionMapper;
    @Resource
    private LineageDatasourceResolver datasources;
    @Resource
    private LineageCatalogProvider catalogs;

    /** 一次分析的结果：上报内容与读写表（供预览展示）。 */
    public record Analysis(LineageReportReqDTO report, List<LineageTable> readTables, List<LineageTable> writeTables,
                           Long datasourceId, int statementCount) {
    }

    /**
     * 返回来源类型。
     */
    @Override
    public String sourceType() {
        return LineageSourceTypeEnum.DEV_TASK.name();
    }

    /**
     * 返回解析器版本。
     */
    @Override
    public int analyzerVersion() {
        return LineageReportVersions.DEV_TASK;
    }

    /**
     * 有界扫描待上报的开发任务。
     */
    @Override
    public List<LineageMarkerRow> pending(int limit) {
        List<LineageMarkerRow> rows = new ArrayList<>(taskMapper.selectLineagePending(analyzerVersion(), limit));
        if (rows.size() < limit) {
            rows.addAll(taskMapper.selectLineageRemoved(limit - rows.size()));
        }
        return rows;
    }

    /**
     * 按当前保存版本的冻结快照构造上报内容。
     */
    @Override
    public LineageReportReqDTO build(LineageMarkerRow row) {
        long revision = row.getSourceRevision() == null ? 0L : row.getSourceRevision();
        if (row.isRemovedSource()) {
            return LineageReportBuilder.removed(sourceType(), row.getId(), name(row), revision, analyzerVersion());
        }
        DevTaskVersionDO version = versionMapper.selectById(row.getSourceRevision());
        if (version == null || StrUtil.isBlank(version.getSnapshotJson())) {
            return new LineageReportBuilder(sourceType(), row.getId(), name(row), revision, analyzerVersion())
                    .status(SqlLineageStatus.FAILED)
                    .issue("VERSION_MISSING", "当前保存版本不存在，无法解析血缘", null).build();
        }
        DevTaskDO task = DevTaskSnapshot.read(version.getSnapshotJson()).getTask();
        if (StrUtil.isNotBlank(version.getContent())) {
            task.setContent(version.getContent());
        }
        task.setId(row.getId());
        return analyze(task, revision).report();
    }

    /**
     * 上报后按旧标记 CAS 更新。
     */
    @Override
    public boolean mark(LineageMarkerRow row, String newKey) {
        return taskMapper.updateLineageReportKey(row.getId(), row.getLineageReportKey(), newKey) == 1;
    }

    /**
     * 分析任务定义；预览与上报共用。
     *
     * @param revision 修订号；预览时为当前版本编号，草稿为 0
     */
    public Analysis analyze(DevTaskDO task, long revision) {
        LineageReportBuilder builder = new LineageReportBuilder(sourceType(), task.getId(), task.getName(), revision,
                analyzerVersion());
        DevExecutionConfig config;
        try {
            config = DevTaskDefinition.readExecutionConfig(task.getExecutionConfigJson());
        } catch (ServiceException invalid) {
            builder.status(SqlLineageStatus.FAILED).issue("CONFIG_INVALID", "执行配置不合法，无法解析血缘", null);
            return new Analysis(builder.build(), List.of(), List.of(), task.getDatabaseId(), 0);
        }
        addManualLineage(config, builder);

        DevTaskTypeEnum type = DevTaskTypeEnum.fromType(StrUtil.blankToDefault(task.getTaskType(), DevTaskDO.TYPE_SQL));
        if (type != DevTaskTypeEnum.SQL && type != DevTaskTypeEnum.SPARK_SQL) {
            builder.status(SqlLineageStatus.UNSUPPORTED).issue("TASK_TYPE_UNSUPPORTED",
                    "任务类型 " + task.getTaskType() + " 暂不支持自动血缘解析，仅记录手工维护的血缘", null);
            return new Analysis(builder.build(), List.of(), List.of(), task.getDatabaseId(), 0);
        }
        if (task.getDatabaseId() == null) {
            builder.status(SqlLineageStatus.UNSUPPORTED).issue("DATASOURCE_UNBOUND",
                    "任务未绑定平台数据源，无法定位血缘中的表", null);
            return new Analysis(builder.build(), List.of(), List.of(), null, 0);
        }
        LineageDatasourceResolver.Datasource database = datasources.resolve(task.getDatabaseId());
        if (database == null) {
            builder.status(SqlLineageStatus.FAILED).issue("DATASOURCE_MISSING", "绑定的数据源不存在", null);
            return new Analysis(builder.build(), List.of(), List.of(), task.getDatabaseId(), 0);
        }
        if (database.product() == null) {
            builder.status(SqlLineageStatus.UNSUPPORTED).issue("PRODUCT_UNSUPPORTED", "数据源产品无法识别", null);
            return new Analysis(builder.build(), List.of(), List.of(), database.id(), 0);
        }
        // Spark SQL 的语法与 Hive 相同；表命名空间仍按绑定数据源解析。
        ProductTypeEnum dialect = type == DevTaskTypeEnum.SPARK_SQL ? ProductTypeEnum.HIVE : database.product();
        if (!SqlLineageAnalyzer.supports(dialect)) {
            builder.status(SqlLineageStatus.UNSUPPORTED).issue("PRODUCT_UNSUPPORTED",
                    "数据库产品 " + dialect.name() + " 尚未进入自动血缘支持范围", null);
            return new Analysis(builder.build(), List.of(), List.of(), database.id(), 0);
        }
        SqlLineageRequest request = SqlLineageRequest.of(dialect, database.defaultSchema(),
                        StrUtil.nullToEmpty(task.getContent()))
                .withParameters(parameters(config))
                .withStatementSeparator(config.getStatementSeparator());
        SqlLineageResult first = SqlLineageAnalyzer.analyze(request);
        SqlLineageResult result = first;
        Set<LineageTable> tables = tables(first);
        if (!tables.isEmpty()) {
            LineageCatalogProvider.Prefetched prefetched = catalogs.prefetch(database.id(), tables);
            if (prefetched.failed()) {
                builder.degrade("METADATA_UNAVAILABLE", "读取已采集元数据失败，字段级血缘可能不完整");
            }
            result = SqlLineageAnalyzer.analyze(request.withCatalog(prefetched.catalog()));
        }
        builder.addSql(result, database.id(), LineageReportBuilder.parsedOrigin());
        return new Analysis(builder.build(), result.readTables(), result.writeTables(), database.id(),
                result.statementCount());
    }

    /**
     * 预览当前保存内容的自动血缘（不上报）。
     */
    public DevTaskLineagePreviewRespVO preview(DevTaskDO task) {
        Analysis analysis = analyze(task, task.getCurrentVersionId() == null ? 0L : task.getCurrentVersionId());
        LineageReportReqDTO report = analysis.report();
        DevTaskLineagePreviewRespVO vo = new DevTaskLineagePreviewRespVO();
        vo.setStatus(report.getStatus());
        vo.setStatementCount(analysis.statementCount());
        vo.setAnalyzerVersion(report.getAnalyzerVersion());
        analysis.readTables().forEach(table -> vo.getReadTables().add(ref(analysis.datasourceId(), table)));
        analysis.writeTables().forEach(table -> vo.getWriteTables().add(ref(analysis.datasourceId(), table)));
        report.getTableEdges().forEach(edge -> {
            DevTaskLineagePreviewRespVO.TableEdge item = new DevTaskLineagePreviewRespVO.TableEdge();
            item.setUpstream(ref(edge.getUpstream()));
            item.setDownstream(ref(edge.getDownstream()));
            item.setOrigin(edge.getOrigin());
            vo.getTableEdges().add(item);
        });
        vo.setColumnEdgeTotal(report.getColumnEdges().size());
        report.getColumnEdges().stream().limit(DevTaskLineagePreviewRespVO.MAX_COLUMN_EDGES).forEach(edge -> {
            DevTaskLineagePreviewRespVO.ColumnEdge item = new DevTaskLineagePreviewRespVO.ColumnEdge();
            item.setUpstream(ref(edge.getUpstream()));
            item.setUpstreamColumn(edge.getUpstreamColumn());
            item.setDownstream(ref(edge.getDownstream()));
            item.setDownstreamColumn(edge.getDownstreamColumn());
            item.setTransformType(edge.getTransformType());
            item.setTransformSubtype(edge.getTransformSubtype());
            item.setAmbiguous(edge.getAmbiguous());
            item.setMasking(edge.getMasking());
            vo.getColumnEdges().add(item);
        });
        report.getIssues().forEach(issue -> {
            DevTaskLineagePreviewRespVO.Issue item = new DevTaskLineagePreviewRespVO.Issue();
            item.setCode(issue.getCode());
            item.setMessage(issue.getMessage());
            item.setStatementIndex(issue.getStatementIndex());
            vo.getIssues().add(item);
        });
        return vo;
    }

    /**
     * 表转预览引用。
     */
    private static DevTaskLineagePreviewRespVO.TableRef ref(Long datasourceId, LineageTable table) {
        DevTaskLineagePreviewRespVO.TableRef ref = new DevTaskLineagePreviewRespVO.TableRef();
        ref.setDatasourceId(datasourceId);
        ref.setSchemaName(table.schema());
        ref.setTableName(table.name());
        return ref;
    }

    /**
     * 节点转预览引用。
     */
    private static DevTaskLineagePreviewRespVO.TableRef ref(LineageNodeRefDTO node) {
        DevTaskLineagePreviewRespVO.TableRef ref = new DevTaskLineagePreviewRespVO.TableRef();
        ref.setDatasourceId(node.getDatasourceId());
        ref.setSchemaName(node.getSchemaName());
        ref.setTableName(node.getTableName());
        return ref;
    }

    /**
     * 手工维护的上游 × 下游组合为表级边。
     */
    private static void addManualLineage(DevExecutionConfig config, LineageReportBuilder builder) {
        DevExecutionConfig.TableLineage lineage = config.getTableLineage();
        if (lineage == null) {
            return;
        }
        for (DevExecutionConfig.TableReference upstream : lineage.getUpstream()) {
            for (DevExecutionConfig.TableReference downstream : lineage.getDownstream()) {
                builder.tableEdge(node(upstream), node(downstream), LineageReportBuilder.manualOrigin(), null);
            }
        }
    }

    /**
     * 手工表引用转节点。
     */
    private static LineageNodeRefDTO node(DevExecutionConfig.TableReference reference) {
        return LineageNodeRefDTO.table(reference.getDatabaseId(), StrUtil.emptyToNull(reference.getSchemaName()),
                reference.getTableName());
    }

    /**
     * 固定值参数。
     */
    private static Map<String, String> parameters(DevExecutionConfig config) {
        Map<String, String> parameters = new LinkedHashMap<>();
        for (DevExecutionConfig.LocalParam param : config.getLocalParams()) {
            parameters.put(param.getProp(), param.getValue());
        }
        return parameters;
    }

    /**
     * 首轮分析涉及的持久表。
     */
    private static Set<LineageTable> tables(SqlLineageResult result) {
        Set<LineageTable> tables = new LinkedHashSet<>(result.readTables());
        tables.addAll(result.writeTables());
        for (LineageTableEdge edge : result.tableEdges()) {
            tables.add(edge.upstream());
            tables.add(edge.downstream());
        }
        return tables;
    }

    /**
     * 来源名称。
     */
    private static String name(LineageMarkerRow row) {
        return StrUtil.blankToDefault(row.getSourceName(), "开发任务#" + row.getId());
    }
}
