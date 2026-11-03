package cn.iocoder.yudao.framework.dbswitch.dbsynch.sqlite;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSqliteImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.AbstractDatabaseSynchronize;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.sqlite.Sqlite3WriterImpl;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/** SQLite 差异 DML 与元数据读取器共享精确的物理标识符规则。 */
public class Sqlite3DatabaseSyncImpl extends AbstractDatabaseSynchronize {

    private final DatabaseSqliteImpl dialect = new DatabaseSqliteImpl();

    /**
     * 初始化 {@code Sqlite3DatabaseSyncImpl}及其父类状态。
     */
    public Sqlite3DatabaseSyncImpl(DataSource source) {
        super(source);
    }

    /**
     * 在 SQLite 共享写入锁内按 JDBC 批次限制执行同步操作。
     */
    @Override
    protected void batchOperator(List<Object[]> rows, String sql, int[] types) {
        synchronized (Sqlite3WriterImpl.GLOBAL_WRITE_LOCK) {
            batchLimits.requireStatementParameters(types.length);
            cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor.execute(dataSource, context -> {
                cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor.executeBatch(context, sql, rows,
                        types, runtimeOptions.queryTimeoutSeconds());
                return null;
            }, null, cn.iocoder.yudao.framework.dbswitch.product.sqlite3.SqliteNativeTables::configureWrite);
        }
    }

    /**
     * 获取查询列元信息的SQL语句。
     */
    @Override
    public String getColumnMetaDataSql(String schema, String table) {
        return "SELECT * FROM " + dialect.getQuotedSchemaTableCombination(schema, table) + " WHERE 1=0";
    }

    /**
     * 生成使用参数占位符的 INSERT 语句。
     */
    @Override
    public String getInsertPrepareStatementSql(String schema, String table, List<String> fields) {
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("SQLite insert columns are required");
        }
        return "INSERT INTO " + dialect.getQuotedSchemaTableCombination(schema, table) + " ("
                + fields.stream().map(dialect::quoteIdentifier).collect(Collectors.joining(",")) + ") VALUES ("
                + String.join(",", Collections.nCopies(fields.size(), "?")) + ")";
    }

    /**
     * 生成Update操作的SQL语句。
     */
    @Override
    public String getUpdatePrepareStatementSql(String schema, String table, List<String> fields, List<String> keys) {
        var changed = fields.stream().filter(field -> !keys.contains(field)).toList();
        String where = bound(keys, " AND ");
        String assignment = changed.isEmpty() ? dialect.quoteIdentifier(
                keys.getFirst()) + "=" + dialect.quoteIdentifier(keys.getFirst()) : bound(changed, ",");
        return "UPDATE " + dialect.getQuotedSchemaTableCombination(schema,
                table) + " SET " + assignment + " WHERE " + where;
    }

    /**
     * 生成Delete操作的SQL语句。
     */
    @Override
    public String getDeletePrepareStatementSql(String schema, String table, List<String> keys) {
        return "DELETE FROM " + dialect.getQuotedSchemaTableCombination(schema, table) + " WHERE " + bound(keys,
                " AND ");
    }

    /**
     * 返回当前边界。
     */
    private String bound(List<String> fields, String separator) {
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("SQLite matching columns are required");
        }
        return fields.stream().map(field -> dialect.quoteIdentifier(field) + "=?")
                .collect(Collectors.joining(separator));
    }
}
