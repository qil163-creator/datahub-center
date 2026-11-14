package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.dbswitch.core.model.BasicTableMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable.FillTableColumnDO;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import cn.iocoder.yudao.module.governance.api.security.SafetySecurityApi;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyDynamicMaskReqDTO;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyDynamicMaskRespDTO;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyStorageMaskReqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.FILL_TABLE_DATABASE_NOT_EXISTS;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.FILL_TABLE_REF_SOURCE_INVALID;

/**
 * 在线填报-数据表下拉取值：发布模板时从来源表读取去重后的取值（及级联上级取值），固化进模板版本。
 *
 * <p>只读连接、查询超时、行数上限；表名与列名先经目标库元数据核对，再由 DB Switch 按产品规则加引号，
 * 不拼接任何来自请求的原始标识符。填报人只看到固化后的取值，不直接访问来源表。</p>
 *
 * <p>脱敏：固化的取值会原样展示给全部填报人，并作为单元格值写入提交与合并后的物理表，没有确定的查看者，
 * 因此按“持久化前脱敏”口径（不看场景开关与白名单豁免）交给 governance 判定。取值列或级联上级列命中敏感分类、
 * 被标记敏感时拒绝发布，而不是固化掩码（掩码写进单元格会污染填报数据）。governance 不可用时同样拒绝（失败关闭）。
 * 判定在来源库连接关闭之后进行，发布流程本身不在事务中。</p>
 *
 * @author Li
 */
@Slf4j
@Component
public class FillReferenceOptionLoader {

    /** 单个字段固化的可选项上限 */
    public static final int MAX_OPTIONS = 5000;

    private static final int QUERY_TIMEOUT_SECONDS = 15;

    /** 脱敏判定的输出列名 */
    static final String MASK_VALUE = "value";
    static final String MASK_PARENT = "parent";

    @Resource
    private AccessDatabaseResolver databaseResolver;

    @Resource
    private DbSwitchRuntime dbSwitchRuntime;

    @Resource
    private SafetySecurityApi safetySecurityApi;

    /**
     * 固化结果。
     *
     * @param values 全部可选项（保持来源顺序、去重）
     * @param byParent 级联：上级取值 → 可选项；未配置级联时为空
     */
    public record Options(List<String> values, Map<String, List<String>> byParent) {
    }

    /**
     * 读取数据表下拉的可选项。
     *
     * @param column 字段（refDatabaseId、refTableName、refValueColumn 必填；级联时含 refParentColumn）
     * @return 可选项
     */
    public Options load(FillTableColumnDO column) {
        String label = column.getCnName();
        if (StrUtil.hasBlank(column.getRefTableName(), column.getRefValueColumn())) {
            throw exception(FILL_TABLE_REF_SOURCE_INVALID, label, "请选择来源表和取值列");
        }
        boolean cascade = StrUtil.isNotBlank(column.getRefParentField());
        if (cascade && StrUtil.isBlank(column.getRefParentColumn())) {
            throw exception(FILL_TABLE_REF_SOURCE_INVALID, label, "级联字段需要指定来源表中的上级取值列");
        }
        AccessDatabaseResolver.ConnectionInfo conn =
                databaseResolver.resolve(column.getRefDatabaseId(), FILL_TABLE_DATABASE_NOT_EXISTS);
        String table = column.getRefTableName().trim();
        String valueColumn = column.getRefValueColumn().trim();
        String parentColumn = cascade ? column.getRefParentColumn().trim() : null;
        // 1. 表与列必须真实存在（按目标库返回的精确名称匹配）
        BasicTableMetadata metadata;
        try {
            metadata = dbSwitchRuntime.jdbcMetadata(conn.getProductType()).queryTableColumnsAndKeys(
                    conn.getJdbcUrl(), conn.getUsername(), conn.getPassword(), conn.getSchema(), table);
        } catch (RuntimeException ex) {
            throw exception(FILL_TABLE_REF_SOURCE_INVALID, label, "来源表 " + table + " 不存在或无法读取");
        }
        Set<String> columns = new LinkedHashSet<>();
        metadata.columns().stream().map(ColumnDescription::getFieldName).forEach(columns::add);
        if (!columns.contains(valueColumn) || (cascade && !columns.contains(parentColumn))) {
            throw exception(FILL_TABLE_REF_SOURCE_INVALID, label, "来源表 " + table + " 中没有指定的列");
        }
        // 2. 只读查询去重取值
        String select = RestrictedSelectGuard.quotePhysicalIdentifier(conn.getProductType(), valueColumn)
                + (cascade ? ", " + RestrictedSelectGuard.quotePhysicalIdentifier(conn.getProductType(), parentColumn)
                : "");
        String from = StrUtil.isBlank(conn.getSchema())
                ? RestrictedSelectGuard.quotePhysicalIdentifier(conn.getProductType(), table)
                : RestrictedSelectGuard.qualifiedTableName(conn.getProductType(), conn.getSchema(), table);
        String sql = "SELECT DISTINCT " + select + " FROM " + from;
        Set<String> values = new LinkedHashSet<>();
        Map<String, Set<String>> byParent = new LinkedHashMap<>();
        int rows = 0;
        try (Connection connection = dbSwitchRuntime.openConnection(conn.getProductType(), null,
                conn.getJdbcUrl(), conn.getUsername(), conn.getPassword())) {
            try {
                connection.setReadOnly(true);
            } catch (Exception ignored) {
                // 部分驱动不支持只读模式；语句本身只有 SELECT
            }
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                statement.setMaxRows(MAX_OPTIONS * 4 + 1);
                try (ResultSet resultSet = statement.executeQuery(sql)) {
                    while (resultSet.next()) {
                        if (++rows > MAX_OPTIONS * 4) {
                            throw exception(FILL_TABLE_REF_SOURCE_INVALID, label, "来源表取值过多");
                        }
                        String value = StrUtil.trimToNull(resultSet.getString(1));
                        if (value == null) {
                            continue;
                        }
                        values.add(value);
                        if (cascade) {
                            String parent = StrUtil.trimToNull(resultSet.getString(2));
                            if (parent != null) {
                                byParent.computeIfAbsent(parent, key -> new LinkedHashSet<>()).add(value);
                            }
                        }
                        if (values.size() > MAX_OPTIONS) {
                            throw exception(FILL_TABLE_REF_SOURCE_INVALID, label,
                                    "可选项超过 " + MAX_OPTIONS + " 个，请缩小来源表或改用手工录入");
                        }
                    }
                }
            }
        } catch (cn.iocoder.yudao.framework.common.exception.ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("[load][字段({}) 读取来源表({}) 失败：{}]", column.getFieldCode(), table, ex.getMessage());
            throw exception(FILL_TABLE_REF_SOURCE_INVALID, label, "读取来源表失败");
        }
        Map<String, List<String>> parents = new LinkedHashMap<>();
        byParent.forEach((parent, children) -> parents.put(parent, new ArrayList<>(children)));
        Options options = new Options(new ArrayList<>(values), parents);
        // 3. 连接已关闭：敏感数据不能固化为下拉取值
        checkNotSensitive(column, column.getRefDatabaseId(), conn.getSchema(), table, valueColumn, parentColumn,
                options);
        return options;
    }

    /**
     * 按持久化前脱敏口径判定取值列（及级联上级列）是否敏感；敏感或无法判定时拒绝。
     */
    void checkNotSensitive(FillTableColumnDO column, Long databaseId, String schema, String table,
                           String valueColumn, String parentColumn, Options options) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (parentColumn == null) {
            options.values().forEach(value -> rows.add(Map.of(MASK_VALUE, value)));
        } else {
            Set<String> children = new LinkedHashSet<>();
            options.byParent().forEach((parent, values) -> values.forEach(value -> {
                children.add(value);
                rows.add(Map.of(MASK_VALUE, value, MASK_PARENT, parent));
            }));
            // 没有上级取值的项也要参与判定
            options.values().stream().filter(value -> !children.contains(value))
                    .forEach(value -> rows.add(Map.of(MASK_VALUE, value)));
        }
        if (rows.isEmpty()) {
            return;
        }
        List<SafetyDynamicMaskReqDTO.Column> columns = new ArrayList<>();
        columns.add(maskColumn(MASK_VALUE, databaseId, schema, table, valueColumn));
        if (parentColumn != null) {
            columns.add(maskColumn(MASK_PARENT, databaseId, schema, table, parentColumn));
        }
        String label = column.getCnName();
        for (int from = 0; from < rows.size(); from += SafetyDynamicMaskReqDTO.MAX_ROWS) {
            SafetyStorageMaskReqDTO req = new SafetyStorageMaskReqDTO();
            req.setColumns(columns);
            req.setRows(rows.subList(from, Math.min(rows.size(), from + SafetyDynamicMaskReqDTO.MAX_ROWS)));
            SafetyDynamicMaskRespDTO resp;
            try {
                resp = safetySecurityApi.maskForStorage(req).getCheckedData();
            } catch (RuntimeException unavailable) {
                log.warn("[checkNotSensitive][字段({}) 敏感判定不可用，失败关闭 failureType={}]",
                        column.getFieldCode(), unavailable.getClass().getSimpleName());
                throw exception(FILL_TABLE_REF_SOURCE_INVALID, label,
                        "无法确认来源列是否为敏感数据（数据安全服务暂不可用），请稍后重试发布");
            }
            if (resp == null) {
                throw exception(FILL_TABLE_REF_SOURCE_INVALID, label,
                        "无法确认来源列是否为敏感数据，请稍后重试发布");
            }
            List<SafetyDynamicMaskRespDTO.MaskedColumn> masked = resp.getMaskedColumns();
            if (masked != null && !masked.isEmpty()) {
                SafetyDynamicMaskRespDTO.MaskedColumn first = masked.getFirst();
                String sourceColumn = MASK_PARENT.equals(first.getOutputName()) ? parentColumn : valueColumn;
                throw exception(FILL_TABLE_REF_SOURCE_INVALID, label, describeSensitive(table, sourceColumn, first));
            }
        }
    }

    /** 拒绝发布的说明：哪一列、为什么敏感、怎么处理 */
    static String describeSensitive(String table, String sourceColumn, SafetyDynamicMaskRespDTO.MaskedColumn masked) {
        String why;
        if (masked.getCategoryNames() != null && !masked.getCategoryNames().isEmpty()) {
            why = "命中敏感分类【" + String.join("、", masked.getCategoryNames()) + "】";
        } else if (SafetyDynamicMaskRespDTO.REASON_UNCLASSIFIED.equals(masked.getReason())) {
            why = "已被标记为敏感字段";
        } else {
            why = "属于敏感数据";
        }
        return "来源列 " + table + "." + sourceColumn + " " + why
                + "，下拉取值会原样展示给全部填报人并写入填报数据，不能作为下拉来源；请改用不含敏感数据的维表或手工录入";
    }

    private static SafetyDynamicMaskReqDTO.Column maskColumn(String outputName, Long databaseId, String schema,
                                                             String table, String column) {
        SafetyDynamicMaskReqDTO.Column item = new SafetyDynamicMaskReqDTO.Column();
        item.setOutputName(outputName);
        item.setSources(List.of(SafetyDynamicMaskReqDTO.Source.of(databaseId, StrUtil.emptyToNull(schema),
                table, column)));
        return item;
    }

}
