package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.AbstractNativeUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.BatchWriteResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** PostgreSQL 原生 ON CONFLICT，有意不支持部分索引键、表达式键、可空键和可延迟键。 */
public final class PostgresUpsertWriter extends AbstractNativeUpsertWriter {

    private String cursorKeyContract;

    /**
     * 初始化 {@code PostgresUpsertWriter}及其父类状态。
     */
    public PostgresUpsertWriter(DataSource source) {
        super(source);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "PostgreSQL";
    }

    /**
     * 根据列属性确定参数绑定使用的类型。
     */
    @Override
    protected int bindingType(int jdbcType, String typeName) {
        return "timestamptz".equals(typeName) ? java.sql.Types.TIMESTAMP_WITH_TIMEZONE : jdbcType;
    }

    /**
     * 按PostgreSQL方言转义并引用标识符。
     */
    @Override
    protected String quote(String name) {
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    /**
     * 精确有序键的类型与排序规则契约，是无损类型化游标重放的必要条件。
     */
    @Override
    public String keyContractFingerprint() {
        return keyContract().nativeFingerprint();
    }

    /**
     * 返回游标读写使用的结构化键契约。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract keyContract() {
        targetSchemaFingerprint();
        try (var connection = dataSource.getConnection()) {
            var contract = cn.iocoder.yudao.framework.dbswitch.core.util.PostgresCursorKeyContract.describe(connection,
                    target(), uniqueKeys);
            cursorKeyContract = contract.nativeFingerprint();
            return contract;
        } catch (SQLException failure) {
            throw new IllegalArgumentException("Typed cursor target key contract is unsupported");
        }
    }

    /**
     * 补充当前产品特有的模式身份信息。
     */
    @Override
    protected String additionalSchemaIdentity(Connection connection) throws SQLException {
        String identity = cn.iocoder.yudao.framework.dbswitch.core.util.PostgresRelationIdentity.storageFingerprint(
                connection, target(), false);
        if (identity == null) {
            throw new SQLException("UPSERT target relation is unsupported");
        }
        return identity;
    }

    /**
     * 核对连接实际产品与当前实现的产品要求。
     */
    @Override
    protected void verifyProduct(Connection connection) throws SQLException {
        if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())
                || connection.getMetaData().getDatabaseMajorVersion() < 12) {
            throw new SQLException("Native PostgreSQL UPSERT requires PostgreSQL 12 or later");
        }
    }

    /**
     * 为当前操作获取目标对象的锁。
     */
    @Override
    protected void lockTarget(Connection connection, boolean writing) throws SQLException {
        if (!writing) {
            return;
        }
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
            statement.execute("LOCK TABLE " + target() + " IN ROW EXCLUSIVE MODE");
        }
    }

    /**
     * 检查指定键列是否满足当前写入路径要求的唯一键条件。
     */
    @Override
    protected void verifyUniqueKey(Connection connection) throws SQLException {
        PostgresUpsertSql.verifyUniqueKey(connection, schemaName, tableName, uniqueKeys, runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 按目标方言生成按键更新或插入的 SQL。
     */
    @Override
    protected String buildUpsertSql() {
        String base = "INSERT INTO " + target() + " (" + quoted(fields) + ") VALUES (" + placeholders()
                + ") ON CONFLICT (" + quoted(uniqueKeys) + ") ";
        List<String> updates = updateFields();
        return base + (updates.isEmpty() ? "DO NOTHING" : "DO UPDATE SET "
                + String.join(",", updates.stream().map(name -> quote(name) + "=EXCLUDED." + quote(name)).toList()));
    }

    /**
     * 写入已完成预处理的数据行。
     */
    @Override
    protected BatchWriteResult writePreparedRows(List<Object[]> rows) {
        return JdbcBatchExecutor.execute(dataSource, context -> {
            int[] types = inspect(context.connection(), true);
            // TIME 和 TIMETZ 目标存储微秒；pgjdbc 会隐式舍入不足微秒的纳秒，而毫秒精度的
            // java.sql.Time 无法证明源端微秒精度，因此两者均在第一条数据语句前拒绝。
            for (int i = 0; i < types.length; i++) {
                if (types[i] == java.sql.Types.TIME) {
                    for (Object[] row : rows) {
                        exactTime(row[i]);
                    }
                }
            }
            if (cursorKeyContract != null && !cursorKeyContract.equals(
                    cn.iocoder.yudao.framework.dbswitch.core.util.PostgresCursorKeyContract.fingerprint(
                            context.connection(), target(), uniqueKeys))) {
                throw new SQLException("Cursor target key comparison changed before write");
            }
            var witness = PostgresCommitWitness.begin(context.connection(), schemaName, tableName);
            JdbcBatchExecutor.executeBatch(context, upsertSql, rows, types);
            if (witness != null) {
                witness.verifyTransaction(context.connection());
            }
            return new Pending(rows.size(), witness);
        }, (connection, pending) -> {
            if (pending.witness != null) {
                pending.witness.verifyCommitted(connection);
            }
        }).result();
    }

    /**
     * 校验 TIME 值使用受支持的 Java 类型及微秒精度。
     */
    private static void exactTime(Object value) throws SQLException {
        if (value != null && !(value instanceof java.time.LocalTime time && time.getNano() % 1000 == 0)) {
            throw new SQLException("PostgreSQL TIME target requires a microsecond-exact LocalTime");
        }
    }

    private record Pending(long rows, PostgresCommitWitness witness) {

        /**
         * 返回结果。
         */
        BatchWriteResult result() {
            return new BatchWriteResult(rows, witness == null ? null : witness.receipt());
        }
    }
}
