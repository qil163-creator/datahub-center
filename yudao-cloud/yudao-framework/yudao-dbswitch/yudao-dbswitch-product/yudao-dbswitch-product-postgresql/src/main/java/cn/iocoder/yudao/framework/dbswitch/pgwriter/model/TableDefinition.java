package cn.iocoder.yudao.framework.dbswitch.pgwriter.model;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.util.PostgreSqlUtils;

/**
 * 表定义模型,持有 schema 与表名,并可生成带引号的全限定表名,用于拼装 COPY 命令。
 */
public class TableDefinition {

    private final String schema;

    private final String tableName;

    /**
     * 使用给定参数初始化 {@code TableDefinition}。
     */
    public TableDefinition(String tableName) {
        this("", tableName);
    }

    /**
     * 初始化 {@code TableDefinition}。
     */
    public TableDefinition(String schema, String tableName) {
        this.schema = schema;
        this.tableName = tableName;
    }

    /**
     * 返回模式。
     */
    public String getSchema() {
        return schema;
    }

    /**
     * 返回表名。
     */
    public String getTableName() {
        return tableName;
    }

    /**
     * 返回包含模式的完整限定表名。
     */
    public String GetFullyQualifiedTableName(boolean usePostgresQuoting) {
        return PostgreSqlUtils.getFullyQualifiedTableName(schema, tableName, usePostgresQuoting);
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return String.format("TableDefinition (Schema = {%1$s}, TableName = {%2$s})",
                schema, tableName);
    }
}
