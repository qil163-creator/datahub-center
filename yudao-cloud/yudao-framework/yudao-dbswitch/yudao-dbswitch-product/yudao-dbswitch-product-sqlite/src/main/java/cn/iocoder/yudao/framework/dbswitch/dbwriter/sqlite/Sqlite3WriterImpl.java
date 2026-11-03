package cn.iocoder.yudao.framework.dbswitch.dbwriter.sqlite;


import cn.iocoder.yudao.framework.dbswitch.dbwriter.AbstractDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;

import javax.sql.DataSource;
import java.util.List;

/**
 * SQLite数据库写入实现类
 *
 * @author jrl
 */
public class Sqlite3WriterImpl extends AbstractDatabaseWriter implements IDatabaseWriter {

    private final cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSqliteImpl dialect =
            new cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSqliteImpl();

    /**
     * SQLite 全局写锁（对齐 dbswitch 2.0.2 的 supportConcurrentWrite 设计）：
     * SQLite 为库级锁，多表并发写同一数据库文件会产生 SQLITE_BUSY 锁冲突，
     * 所有写入（含增量同步，见 Sqlite3DatabaseSyncImpl）在进程内串行执行
     */
    public static final Object GLOBAL_WRITE_LOCK = new Object();

    /**
     * 初始化 {@code Sqlite3WriterImpl}及其父类状态。
     */
    public Sqlite3WriterImpl(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "SQLite";
    }

    /**
     * 生成读取表元数据的 SQL。
     */
    @Override
    protected String selectTableMetaDataSqlString(String schema, String table, List<String> fields) {
        return "SELECT " + (fields == null || fields.isEmpty() ? "*" : columns(fields)) + " FROM "
                + dialect.getQuotedSchemaTableCombination(schema, table) + " WHERE 1=0";
    }

    /**
     * 生成目标表的 INSERT 语句。
     */
    @Override
    protected String insertSql(List<String> fields) {
        return "INSERT INTO " + dialect.getQuotedSchemaTableCombination(schemaName, tableName) + " (" + columns(
                fields) + ") VALUES ("
                + String.join(",", java.util.Collections.nCopies(fields.size(), "?")) + ")";
    }

    /**
     * 读取或组装当前操作所使用的列定义。
     */
    private String columns(List<String> fields) {
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("SQLite write columns are required");
        }
        return fields.stream().map(dialect::quoteIdentifier).collect(java.util.stream.Collectors.joining(","));
    }

  /*@Override
  protected TransactionDefinition getTransactionDefinition() {
    DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
    definition.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    return definition;
  }*/


    /**
     * 按已准备的 JDBC 类型和目标列配置写入当前数据批次。
     */
    @Override
    protected void batchWrite(List<String> fieldNames, List<Object[]> recordValues,
            String sqlInsert, int[] argTypes) {
        synchronized (GLOBAL_WRITE_LOCK) {
            batchLimits.requireStatementParameters(argTypes.length);
            cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor.execute(dataSource, context -> {
                cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor.executeBatch(context, sqlInsert,
                        recordValues, argTypes, runtimeOptions.queryTimeoutSeconds());
                return null;
            }, null, cn.iocoder.yudao.framework.dbswitch.product.sqlite3.SqliteNativeTables::configureWrite);
        }
    }
}
