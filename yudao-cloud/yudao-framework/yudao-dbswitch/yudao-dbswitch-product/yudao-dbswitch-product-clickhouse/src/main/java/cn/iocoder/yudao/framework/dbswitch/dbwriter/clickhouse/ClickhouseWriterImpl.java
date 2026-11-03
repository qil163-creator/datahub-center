package cn.iocoder.yudao.framework.dbswitch.dbwriter.clickhouse;

import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.AbstractDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.product.clickhouse.ClickhouseSql;
import cn.iocoder.yudao.framework.dbswitch.product.clickhouse.ClickhouseWriteExecution;
import cn.iocoder.yudao.framework.dbswitch.product.clickhouse.ClickhouseValueConversions;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;

/**
 * ClickHouse 原生同步 INSERT；不模拟 JDBC 事务。
 *
 * @author jrl
 */
public class ClickhouseWriterImpl extends AbstractDatabaseWriter {

    private java.util.Map<String, ClickhouseValueConversions.Column> nativeColumns = java.util.Map.of();

    /**
     * 初始化 {@code ClickhouseWriterImpl}及其父类状态。
     */
    public ClickhouseWriterImpl(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "ClickHouse";
    }

    /**
     * 写入准备：执行一条 WHERE 1=2 的探测查询（不取数据）， 从结果集元数据中缓存目标表“字段名 -> JDBC 类型”映射，供 write 阶段绑定参数类型使用； 查询失败或表无字段时抛出运行时异常。
     */
    @Override
    public void prepareWrite(String schema, String table, List<String> fields) {
        String sql = selectTableMetaDataSqlString(schema, table, fields);
        var types = new HashMap<String, Integer>();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
            try (var rows = statement.executeQuery(sql)) {
                var metadata = rows.getMetaData();
                nativeColumns = ClickhouseValueConversions.columns(metadata);
                for (int column = 1; column <= metadata.getColumnCount(); column++) {
                    types.put(metadata.getColumnName(column), metadata.getColumnType(column));
                }
            }
        } catch (Exception failure) {
            throw new IllegalStateException("ClickHouse target metadata preflight failed");
        }
        if (types.isEmpty()) {
            throw new IllegalStateException("ClickHouse target has no writable columns");
        }
        schemaName = schema;
        tableName = table;
        columnType = types;
    }

    /**
     * 生成读取表元数据的 SQL。
     */
    @Override
    protected String selectTableMetaDataSqlString(String schema, String table, List<String> fields) {
        return "SELECT " + (fields == null || fields.isEmpty() ? "*" : ClickhouseSql.columns(fields))
                + " FROM " + ClickhouseSql.table(schema, table) + " WHERE 1=0";
    }

    /**
     * 生成目标表的 INSERT 语句。
     */
    @Override
    protected String insertSql(List<String> fields) {
        return ClickhouseSql.insert(schemaName, tableName, fields);
    }

    /**
     * 批量数据写入。
     */
    @Override
    public long write(List<String> fields, List<Object[]> records) {
        if (records.isEmpty()) {
            return 0;
        }
        if (columnType == null || !columnType.keySet().containsAll(fields)) {
            throw new IllegalArgumentException("ClickHouse writer must be prepared for every selected column");
        }
        String sql = insertSql(fields);
        batchLimits.requireStatementParameters(fields.size());
        var rows = BatchValueConverter.convert(records, fields.size(), batchLimits,
                (value, column) -> ClickhouseValueConversions.convert(value, nativeColumns.get(fields.get(column))));
        return ClickhouseWriteExecution.insert(dataSource, sql, rows, runtimeOptions.queryTimeoutSeconds());
    }
}
