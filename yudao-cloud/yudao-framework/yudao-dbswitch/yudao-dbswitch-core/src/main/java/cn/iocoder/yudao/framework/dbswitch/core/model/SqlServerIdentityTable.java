package cn.iocoder.yudao.framework.dbswitch.core.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** 原生表实例及所有观测到的标识生成列，不包含凭据和端点字符串。 */
public record SqlServerIdentityTable(String databaseGuid, long objectId, Instant created,
                                     Map<String, SqlServerIdentityMetadata> columns) {

    /**
     * 校验构造参数并初始化 {@code SqlServerIdentityTable}。
     */
    public SqlServerIdentityTable {
        Objects.requireNonNull(created);
        columns = Map.copyOf(columns);
        if (databaseGuid == null || !databaseGuid.matches("[0-9a-fA-F-]{36}") || objectId <= 0 || columns.size() > 1) {
            throw new IllegalArgumentException("Invalid SQL Server physical identity observation");
        }
    }

    /**
     * 检查两个表引用是否指向同一物理表。
     */
    public boolean samePhysicalTable(SqlServerIdentityTable other) {
        return other != null && databaseGuid.equalsIgnoreCase(
                other.databaseGuid) && objectId == other.objectId && created.equals(other.created);
    }

    /**
     * 比较两份表结构定义是否一致。
     */
    public boolean sameDefinition(SqlServerIdentityTable other) {
        return other != null && columns.keySet().equals(other.columns.keySet())
                && columns.entrySet().stream()
                .allMatch(entry -> entry.getValue().sameDefinition(other.columns.get(entry.getKey())));
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "SqlServerIdentityTable[columns=" + columns.size() + "]";
    }
}
