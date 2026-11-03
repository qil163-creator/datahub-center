package cn.iocoder.yudao.module.governance.service.metadata.core;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object.MetaColumnRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object.MetaSnapshotCompareRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object.MetaTableRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableSnapshotDO;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.META_SNAPSHOT_INVALID;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.META_SNAPSHOT_TABLE_MISMATCH;

/** 元数据结构快照的展示投影，不读取实时对象或通过字段名补造历史身份。 */
public final class MetaSnapshotViews {

    /**
     * 限制元数据快照视图工具类的直接实例化。
     */
    private MetaSnapshotViews() {
    }

    /**
     * 解析元数据快照视图持久化快照的正文。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> body(MetaTableSnapshotDO snapshot) {
        try {
            Map<String, Object> body = JsonUtils.parseObject(snapshot.getSnapshot(), Map.class);
            if (body == null || !(body.get("table") instanceof Map) || !(body.get("columns") instanceof List)) {
                throw exception(META_SNAPSHOT_INVALID);
            }
            return body;
        } catch (RuntimeException invalid) {
            throw exception(META_SNAPSHOT_INVALID);
        }
    }

    /**
     * 读取元数据快照视图关联表信息。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> table(Map<String, Object> body) {
        return (Map<String, Object>) body.get("table");
    }

    /**
     * 读取元数据快照视图的字段定义。
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> columns(Map<String, Object> body) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object node : (List<?>) body.get("columns")) {
            if (!(node instanceof Map<?, ?> column) || !(column.get("columnName") instanceof String name)
                    || name.isBlank()) {
                throw exception(META_SNAPSHOT_INVALID);
            }
            result.add((Map<String, Object>) column);
        }
        return result;
    }

    /**
     * 应用表结构。
     */
    public static void applyTableStructure(MetaTableRespVO target, Map<String, Object> source) {
        target.setTableName(string(source.get("tableName")));
        target.setTableType(string(source.get("tableType")));
        target.setSourceComment(string(source.get("comment")));
        target.setPrimaryKeys(JsonUtils.toJsonString(source.get("primaryKeys")));
        target.setColumnCount(integer(source.get("columnCount")));
        target.setEngine(string(source.get("engine")));
        target.setIndexes(json(source.get("indexes")));
        target.setPartitionInfo(json(source.get("partitionInfo")));
    }

    /**
     * 应用字段结构。
     */
    public static void applyColumnStructure(MetaColumnRespVO target, Map<String, Object> source) {
        target.setId(number(source.get("id")));
        target.setResourceUrn(string(source.get("resourceUrn")));
        target.setColumnName(string(source.get("columnName")));
        target.setOrdinalPosition(integer(source.get("ordinal")));
        target.setSourceComment(string(source.get("comment")));
        target.setDataType(string(source.get("dataType")));
        target.setDataLength(number(source.get("length")));
        target.setDataPrecision(integer(source.get("precision")));
        target.setDataScale(integer(source.get("scale")));
        target.setNullableFlag(integer(source.get("nullable")));
        target.setDefaultValue(string(source.get("defaultValue")));
        target.setPkFlag(integer(source.get("pk")));
        target.setAutoIncrementFlag(integer(source.get("autoIncrement")));
        target.setRevision(null);
        target.setReadOnly(true);
    }

    /**
     * 比对两个元数据快照并汇总表与字段差异。
     */
    public static MetaSnapshotCompareRespVO compare(MetaTableSnapshotDO before, MetaTableSnapshotDO after) {
        if (!Objects.equals(before.getTableId(), after.getTableId())) {
            throw exception(META_SNAPSHOT_TABLE_MISMATCH);
        }
        Map<String, Object> from = body(before);
        Map<String, Object> to = body(after);
        var result = new MetaSnapshotCompareRespVO();
        result.setTableId(before.getTableId());
        result.setFromSnapshotId(before.getId());
        result.setToSnapshotId(after.getId());
        result.setFromVersion(before.getStructVersion());
        result.setToVersion(after.getStructVersion());
        result.setTableChanges(changes(table(from), table(to)));
        // 可信离线导入明确声明源系统的字段名比较语义；平台采集仍按稳定字段身份比较。
        boolean importedNameIdentity = importedNameIdentity(from) && importedNameIdentity(to);
        Map<String, Map<String, Object>> oldColumns = index(columns(from), importedNameIdentity);
        Map<String, Map<String, Object>> newColumns = index(columns(to), importedNameIdentity);
        var keys = new LinkedHashSet<>(oldColumns.keySet());
        keys.addAll(newColumns.keySet());
        List<MetaSnapshotCompareRespVO.ColumnChange> rows = new ArrayList<>();
        for (String key : keys) {
            var oldColumn = oldColumns.get(key);
            var newColumn = newColumns.get(key);
            var row = new MetaSnapshotCompareRespVO.ColumnChange();
            row.setColumnName(string((newColumn == null ? oldColumn : newColumn).get("columnName")));
            row.setBefore(oldColumn);
            row.setAfter(newColumn);
            row.setChanges(
                    changes(oldColumn, newColumn, importedNameIdentity ? Set.of("id", "resourceUrn") : Set.of()));
            if (oldColumn == null) {
                row.setChangeType("ADDED");
                result.setAddedCount(result.getAddedCount() + 1);
            } else if (newColumn == null) {
                row.setChangeType("REMOVED");
                result.setRemovedCount(result.getRemovedCount() + 1);
            } else if (!row.getChanges().isEmpty()) {
                row.setChangeType("MODIFIED");
                result.setModifiedCount(result.getModifiedCount() + 1);
            } else {
                row.setChangeType("UNCHANGED");
            }
            rows.add(row);
        }
        result.setColumnChanges(rows);
        return result;
    }

    /**
     * 判断迁入快照是否应使用名称建立字段对应关系。
     */
    private static boolean importedNameIdentity(Map<String, Object> body) {
        return "qdata".equals(body.get("source")) && "COLUMN_NAME".equals(body.get("comparisonIdentity"));
    }

    /**
     * 按字段标识建立快照字段索引。
     */
    private static Map<String, Map<String, Object>> index(List<Map<String, Object>> columns, boolean nameIdentity) {
        Map<String, Map<String, Object>> index = new LinkedHashMap<>();
        for (var column : columns) {
            String key = nameIdentity || column.get("id") == null ? "name:" + column.get("columnName")
                    : "id:" + number(column.get("id"));
            if (index.putIfAbsent(key, column) != null) {
                throw exception(META_SNAPSHOT_INVALID);
            }
        }
        return index;
    }

    /**
     * 比较属性值并追加有差异的变更明细。
     */
    private static List<MetaSnapshotCompareRespVO.PropertyChange> changes(Map<String, Object> from,
            Map<String, Object> to) {
        return changes(from, to, Set.of());
    }

    /**
     * 比较属性值并追加有差异的变更明细。
     */
    private static List<MetaSnapshotCompareRespVO.PropertyChange> changes(Map<String, Object> from,
            Map<String, Object> to, Set<String> ignored) {
        Map<String, Object> left = from == null ? Map.of() : from;
        Map<String, Object> right = to == null ? Map.of() : to;
        var keys = new LinkedHashSet<>(left.keySet());
        keys.addAll(right.keySet());
        var result = new ArrayList<MetaSnapshotCompareRespVO.PropertyChange>();
        for (String key : keys) {
            if (ignored.contains(key)) {
                continue;
            }
            if (Objects.equals(left.get(key), right.get(key))) {
                continue;
            }
            var change = new MetaSnapshotCompareRespVO.PropertyChange();
            change.setProperty(key);
            change.setBefore(left.get(key));
            change.setAfter(right.get(key));
            result.add(change);
        }
        return result;
    }

    /**
     * 读取并转换元数据快照视图中的数值。
     */
    public static Long number(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value)).longValueExact();
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw exception(META_SNAPSHOT_INVALID);
        }
    }

    /**
     * 解析元数据快照视图中的整数值。
     */
    private static Integer integer(Object value) {
        Long number = number(value);
        if (number == null) {
            return null;
        }
        try {
            return Math.toIntExact(number);
        } catch (ArithmeticException invalid) {
            throw exception(META_SNAPSHOT_INVALID);
        }
    }

    /**
     * 解析元数据快照视图中的 JSON 内容。
     */
    private static String json(Object value) {
        return value == null ? null : JsonUtils.toJsonString(value);
    }

    /**
     * 从快照属性映射中读取字符串值。
     */
    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
