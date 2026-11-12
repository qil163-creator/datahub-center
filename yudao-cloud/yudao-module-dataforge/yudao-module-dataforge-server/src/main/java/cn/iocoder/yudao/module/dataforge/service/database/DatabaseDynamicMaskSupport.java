package cn.iocoder.yudao.module.dataforge.service.database;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.LineageColumnSource;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.LineageTable;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.QueryLineageResult;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.SqlLineageAnalyzer;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.SqlLineageRequest;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.SqlLineageStatus;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
import cn.iocoder.yudao.module.governance.api.security.SafetySecurityApi;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyDynamicMaskReqDTO;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyDynamicMaskRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * 数据库预览与只读查询结果的动态脱敏接入：确定每个输出列的物理来源，交给 governance 按分类、白名单与场景处理。
 * <p>
 * 失败关闭：governance 不可用时不返回结果；查询输出列无法追溯时，以查询涉及的表作为候选，由 governance 判定是否打码。
 * 不在数据库事务或连接持有期间调用（调用方在关闭连接后再处理结果）。
 */
@Slf4j
@Component
public class DatabaseDynamicMaskSupport {

    public static final String SCENE_DATABASE_PREVIEW = "DATABASE_PREVIEW";
    public static final String SCENE_DATA_QUERY = "DATA_QUERY";
    /** 仅供 governance 敏感识别采样的内部场景，不做脱敏。 */
    public static final String SCENE_SENSITIVE_SCAN = "SENSITIVE_SCAN";
    private static final int MAX_SOURCES = 50;
    private static final int MAX_CANDIDATES = 20;

    @Resource
    private SafetySecurityApi safetySecurityApi;

    /**
     * 表预览：每个输出列就是该表的同名物理列。
     */
    public DatabasePreviewRespDTO maskTable(Long databaseId, String schemaName, String tableName, String scene,
                                            DatabasePreviewRespDTO resp, ErrorCode failure) {
        if (SCENE_SENSITIVE_SCAN.equals(scene)) {
            // 敏感识别内部采样：分类判定需要原值，结果不返回给用户。
            resp.setMaskedColumns(new ArrayList<>());
            return resp;
        }
        List<SafetyDynamicMaskReqDTO.Column> columns = new ArrayList<>();
        for (DatabasePreviewRespDTO.Column column : nullSafe(resp.getColumns())) {
            SafetyDynamicMaskReqDTO.Column item = new SafetyDynamicMaskReqDTO.Column();
            item.setOutputName(column.getName());
            item.setSources(List.of(SafetyDynamicMaskReqDTO.Source.of(databaseId, StrUtil.emptyToNull(schemaName),
                    tableName, StrUtil.blankToDefault(column.getLabel(), column.getName()))));
            columns.add(item);
        }
        return apply(StrUtil.blankToDefault(scene, SCENE_DATABASE_PREVIEW), qualified(schemaName, tableName), columns,
                resp, failure);
    }

    /**
     * 只读查询：用 SQL 血缘分析把输出列追溯到物理列。
     *
     * @param expectedSchema 调用方声明的表命名空间（可空），分析不可用时作为候选表
     * @param expectedTable  调用方声明的表（可空）
     */
    public DatabasePreviewRespDTO maskQuery(Long databaseId, ProductTypeEnum product, String defaultSchema, String sql,
                                            String expectedSchema, String expectedTable, String scene,
                                            DatabasePreviewRespDTO resp, ErrorCode failure) {
        QueryLineageResult analysis = analyze(product, defaultSchema, sql);
        Map<String, QueryLineageResult.Column> named = new LinkedHashMap<>();
        Set<String> duplicated = new LinkedHashSet<>();
        List<LineageTable> wildcards = new ArrayList<>();
        Set<LineageTable> candidates = new LinkedHashSet<>();
        boolean positional = false;
        if (analysis != null) {
            for (QueryLineageResult.Column column : analysis.columns()) {
                if (column.wildcard()) {
                    column.sources().forEach(source -> wildcards.add(source.table()));
                } else if (column.name() != null && named.putIfAbsent(key(column.name()), column) != null) {
                    duplicated.add(key(column.name()));
                }
            }
            candidates.addAll(analysis.readTables());
            // 没有未展开的 * 且列数一致时，分析结果与结果集按位置一一对应（同名列、未命名表达式也能准确对齐）。
            positional = wildcards.isEmpty() && analysis.columns().size() == nullSafe(resp.getColumns()).size();
        }
        if (StrUtil.isNotBlank(expectedTable)) {
            candidates.add(new LineageTable(StrUtil.blankToDefault(expectedSchema, defaultSchema), expectedTable));
        }
        List<LineageTable> distinctWildcards = wildcards.stream().distinct().toList();
        List<SafetyDynamicMaskReqDTO.Column> columns = new ArrayList<>();
        List<DatabasePreviewRespDTO.Column> resultColumns = nullSafe(resp.getColumns());
        for (int index = 0; index < resultColumns.size(); index++) {
            DatabasePreviewRespDTO.Column result = resultColumns.get(index);
            SafetyDynamicMaskReqDTO.Column item = new SafetyDynamicMaskReqDTO.Column();
            item.setOutputName(result.getName());
            String label = key(StrUtil.blankToDefault(result.getLabel(), result.getName()));
            QueryLineageResult.Column traced = positional ? analysis.columns().get(index)
                    : duplicated.contains(label) ? null : named.get(label);
            if (traced != null) {
                List<SafetyDynamicMaskReqDTO.Source> sources = new ArrayList<>();
                // 经过计数、哈希等不暴露原值的变换后，该来源不再需要脱敏。
                for (LineageColumnSource source : traced.sources()) {
                    if (!source.masking() && sources.size() < MAX_SOURCES) {
                        sources.add(source(databaseId, source.table(), source.column()));
                    }
                }
                // 归属不唯一或仍是未展开的 *，都不能当作确定来源。
                boolean uncertain = traced.sources().stream()
                        .anyMatch(source -> source.ambiguous() || "*".equals(source.column()));
                if (uncertain) {
                    untraceable(item, databaseId, candidates);
                } else {
                    item.setSources(sources);
                }
            } else if (distinctWildcards.size() == 1) {
                item.setSources(List.of(source(databaseId, distinctWildcards.getFirst(),
                        StrUtil.blankToDefault(result.getLabel(), result.getName()))));
            } else {
                untraceable(item, databaseId, candidates);
            }
            columns.add(item);
        }
        String resource = StrUtil.isNotBlank(expectedTable) ? qualified(expectedSchema, expectedTable)
                : candidates.stream().map(LineageTable::displayName).findFirst().orElse("SQL 查询");
        return apply(StrUtil.blankToDefault(scene, SCENE_DATA_QUERY), resource, columns, resp, failure);
    }

    // ==================== 内部 ====================

    /**
     * 调用 governance 并写回结果；不可用时失败关闭。
     */
    private DatabasePreviewRespDTO apply(String scene, String resourceName, List<SafetyDynamicMaskReqDTO.Column> columns,
                                         DatabasePreviewRespDTO resp, ErrorCode failure) {
        Outcome outcome = maskColumns(scene, SecurityFrameworkUtils.getLoginUserId(), resourceName, columns,
                resp.getRows(), failure);
        resp.setRows(outcome.rows());
        resp.setMaskedColumns(outcome.maskedColumns());
        return resp;
    }

    /**
     * 动态脱敏结果。
     */
    public record Outcome(List<Map<String, Object>> rows, List<DatabasePreviewRespDTO.MaskedColumn> maskedColumns) {
    }

    /**
     * 通用入口：按列来源调用 governance 动态脱敏。没有行或没有列时不调用；governance 不可用或结果不完整时
     * 抛出 {@code failure}，绝不返回原值。调用方须在数据库连接关闭后、事务外调用。
     *
     * @param userId  查看结果的用户；为空时不享受任何豁免
     * @param columns 每个结果列的物理来源（见 SafetyDynamicMaskReqDTO.Column）
     */
    public Outcome maskColumns(String scene, Long userId, String resourceName,
                               List<SafetyDynamicMaskReqDTO.Column> columns, List<Map<String, Object>> rows,
                               ErrorCode failure) {
        if (rows == null || rows.isEmpty() || columns.isEmpty()) {
            return new Outcome(rows == null ? new ArrayList<>() : rows, new ArrayList<>());
        }
        SafetyDynamicMaskReqDTO req = new SafetyDynamicMaskReqDTO();
        req.setUserId(userId);
        req.setScene(scene);
        req.setResourceName(StrUtil.maxLength(resourceName, 250));
        req.setColumns(columns);
        req.setRows(rows);
        SafetyDynamicMaskRespDTO masked;
        try {
            masked = safetySecurityApi.maskRows(req).getCheckedData();
        } catch (RuntimeException unavailable) {
            // 只记录异常类型；不能因脱敏服务不可用而返回原值。
            log.warn("[maskColumns][动态脱敏不可用，失败关闭 scene={} failureType={}]", scene,
                    unavailable.getClass().getSimpleName());
            throw exception(failure, "动态脱敏服务暂不可用，为保护敏感数据暂不返回结果");
        }
        if (masked == null || masked.getRows() == null || masked.getRows().size() != rows.size()) {
            throw exception(failure, "动态脱敏结果不完整，为保护敏感数据暂不返回结果");
        }
        List<DatabasePreviewRespDTO.MaskedColumn> maskedColumns = new ArrayList<>();
        for (SafetyDynamicMaskRespDTO.MaskedColumn column : nullSafe(masked.getMaskedColumns())) {
            maskedColumns.add(new DatabasePreviewRespDTO.MaskedColumn(column.getOutputName(), column.getReason(),
                    column.getCategoryNames()));
        }
        return new Outcome(masked.getRows(), maskedColumns);
    }

    /**
     * 标记为无法追溯，附上候选表。
     */
    private static void untraceable(SafetyDynamicMaskReqDTO.Column item, Long databaseId, Set<LineageTable> candidates) {
        item.setTraceable(false);
        item.setSources(List.of());
        item.setCandidateTables(candidates.stream().limit(MAX_CANDIDATES)
                .map(table -> source(databaseId, table, null)).toList());
    }

    /**
     * 分析查询；产品不在支持矩阵或分析失败时返回 null。
     */
    private static QueryLineageResult analyze(ProductTypeEnum product, String defaultSchema, String sql) {
        if (product == null || StrUtil.isBlank(sql) || !SqlLineageAnalyzer.supports(product)) {
            return null;
        }
        try {
            QueryLineageResult result = SqlLineageAnalyzer.analyzeQuery(SqlLineageRequest.of(product,
                    StrUtil.emptyToNull(defaultSchema), sql));
            return result.status() == SqlLineageStatus.FAILED || result.status() == SqlLineageStatus.UNSUPPORTED
                    ? null : result;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /**
     * 物理来源。
     */
    private static SafetyDynamicMaskReqDTO.Source source(Long databaseId, LineageTable table, String column) {
        return SafetyDynamicMaskReqDTO.Source.of(databaseId, table.schema(), table.name(), column);
    }

    /**
     * 限定名。
     */
    private static String qualified(String schema, String table) {
        return StrUtil.isBlank(schema) ? StrUtil.nullToEmpty(table) : schema + "." + table;
    }

    /**
     * 大小写不敏感的列键。
     */
    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    /**
     * 空列表兜底。
     */
    private static <T> List<T> nullSafe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
