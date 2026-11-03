package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSqliteImpl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.AbstractDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.sqlite.Sqlite3WriterImpl;

import javax.sql.DataSource;
import java.util.List;
import java.util.stream.Collectors;

/** SQLite 原生标识符及事务管理的破坏性操作。 */
public class SqliteDatabaseOperator extends AbstractDatabaseOperator {

    private final DatabaseSqliteImpl dialect = new DatabaseSqliteImpl();

    /**
     * 初始化 {@code SqliteDatabaseOperator}及其父类状态。
     */
    public SqliteDatabaseOperator(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 返回当前实现对应的数据库产品类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.SQLITE3;
    }

    /**
     * 原生索引及集合结构支持独立于关系型 SQL DDL。
     */
    @Override
    public boolean supportsNativeTableDefinition() {
        return true;
    }

    /**
     * 读取数据库原生表定义及其元数据证据。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition describeNativeTable(String schema,
            String table) {
        return cn.iocoder.yudao.framework.dbswitch.product.sqlite3.SqliteNativeTables.describe(dataSource, schema,
                table, runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 为当前产品准备原生目标对象及写入计划。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTargetPlan prepareNativeTarget(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition definition, String schema,
            String table, java.util.Map<String, String> mapping, boolean replace) {
        return cn.iocoder.yudao.framework.dbswitch.product.sqlite3.SqliteNativeTables.plan(dataSource, definition,
                schema, table, mapping, replace, runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 返回原生搬运路径使用的列定义。
     */
    @Override
    public List<cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription> nativeMigrationColumns(String schema,
            String table) {
        return cn.iocoder.yudao.framework.dbswitch.product.sqlite3.SqliteNativeTables.writableColumns(dataSource,
                schema, table, runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 创建并打开按键推进的读取游标。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession openCursor(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest request) {
        return SqliteCursorReader.open(dataSource, request, runtimeOptions, fetchSize);
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql, List<String> fields, List<String> orders) {
        return selectTableData("SELECT " + columns(fields) + " FROM (" + sql + ") t" + order(orders), fetchSize);
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql, List<String> fields) {
        return queryTableData(sql, fields, List.of());
    }

    /**
     * 生成查询指定字段的select查询SQL语句。
     */
    @Override
    public String getSelectTableSql(String schema, String table, List<String> fields) {
        return "SELECT " + columns(fields) + " FROM " + dialect.getQuotedSchemaTableCombination(schema, table);
    }

    /**
     * 获取指定schema下表的按主键有序的结果集。
     */
    @Override
    public StatementResultSet queryTableData(String schema, String table, List<String> fields, List<String> orders) {
        return selectTableData(getSelectTableSql(schema, table, fields) + order(orders), fetchSize);
    }

    /**
     * 获取指定schema下表的结果集。
     */
    @Override
    public StatementResultSet queryTableData(String schema, String table, List<String> fields) {
        return queryTableData(schema, table, fields, List.of());
    }

    /**
     * 读取或组装当前操作所使用的列定义。
     */
    private String columns(List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("SQLite projection is required");
        }
        return fields.stream().map(dialect::quoteIdentifier).collect(Collectors.joining(","));
    }

    /**
     * 返回顺序。
     */
    private String order(List<String> fields) {
        return fields == null || fields.isEmpty() ? "" : " ORDER BY " + columns(fields);
    }

    /**
     * 清除指定表的所有数据。
     */
    @Override
    public void truncateTableData(String schema, String table) {
        // 保留 AUTOINCREMENT 的高水位；清空附加表绝不能
        // 修改 main.sqlite_sequence 或重新分配已消耗的自增标识。
        mutate("DELETE FROM " + dialect.getQuotedSchemaTableCombination(schema, table));
    }

    /**
     * 删除指定物理表。
     */
    @Override
    public void dropTable(String schema, String table) {
        mutate("DROP TABLE " + dialect.getQuotedSchemaTableCombination(schema, table));
    }

    /**
     * 按当前变更定义修改目标对象或数据。
     */
    private void mutate(String sql) {
        synchronized (Sqlite3WriterImpl.GLOBAL_WRITE_LOCK) {
            JdbcBatchExecutor.execute(dataSource, context -> {
                try (var statement = context.connection().createStatement()) {
                    statement.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
                    context.markWriteAttempted();
                    statement.executeUpdate(sql);
                    return null;
                }
            }, null, cn.iocoder.yudao.framework.dbswitch.product.sqlite3.SqliteNativeTables::configureWrite);
        }
    }
}
