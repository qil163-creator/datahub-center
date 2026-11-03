package cn.iocoder.yudao.framework.dbswitch.dbwriter.mysql;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.AbstractNativeUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.core.util.MysqlCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.BatchWriteResult;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.product.mysql.MysqlCompatibleProfile;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** MySQL 没有冲突目标语法；若接受竞争唯一索引，可能更新错误的数据行。 */
public final class MySqlUpsertWriter extends AbstractNativeUpsertWriter {

    private final MysqlCompatibleProfile profile;

    private String cursorKeyFingerprint;

    /**
     * 使用给定参数初始化 {@code MySqlUpsertWriter}。
     */
    public MySqlUpsertWriter(DataSource source) {
        this(source, MysqlCompatibleProfile.MYSQL);
    }

    /**
     * 校验构造参数并初始化 {@code MySqlUpsertWriter}及其父类状态。
     */
    public MySqlUpsertWriter(DataSource source, MysqlCompatibleProfile profile) {
        super(source);
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return profile.productName();
    }

    /**
     * 按MySQL方言转义并引用标识符。
     */
    @Override
    protected String quote(String name) {
        return '`' + name.replace("`", "``") + '`';
    }

    /**
     * 核对连接实际产品与当前实现的产品要求。
     */
    @Override
    protected void verifyProduct(Connection connection) throws SQLException {
        MysqlCursorMetadata.verifyProduct(connection, profile, runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 补充当前产品特有的模式身份信息。
     */
    @Override
    protected String additionalSchemaIdentity(Connection connection) throws SQLException {
        return MysqlCursorMetadata.physicalIdentity(connection, profile, schemaName, tableName,
                runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 为当前操作获取目标对象的锁。
     */
    @Override
    protected void lockTarget(Connection connection, boolean writing) throws SQLException {
        if (!writing) {
            return;
        }
        profile.beforeUpsertLock(connection, runtimeOptions.queryTimeoutSeconds());
        // InnoDB 将此关系的元数据锁持有到提交，覆盖所有目录复检与 DML。
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
            try (var ignored = statement.executeQuery(
                    "SELECT " + quoted(uniqueKeys) + " FROM " + target() + " WHERE 1=0")) {
            }
        }
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
    public CursorKeyContract keyContract() {
        targetSchemaFingerprint();
        try (var connection = dataSource.getConnection()) {
            MysqlCursorMetadata.requireReplayWithoutTriggers(connection, schemaName, tableName,
                    runtimeOptions.queryTimeoutSeconds());
            var contract = MysqlCursorMetadata.keyContract(connection, profile, schemaName, tableName, uniqueKeys,
                    runtimeOptions.queryTimeoutSeconds());
            cursorKeyFingerprint = contract.nativeFingerprint();
            return contract;
        } catch (SQLException failure) {
            throw new IllegalArgumentException("Typed cursor target key contract is unsupported");
        }
    }

    /**
     * 检查指定键列是否满足当前写入路径要求的唯一键条件。
     */
    @Override
    protected void verifyUniqueKey(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT ENGINE,TABLE_TYPE FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
            statement.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
            statement.setString(1, schemaName);
            statement.setString(2, tableName);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !"InnoDB".equalsIgnoreCase(rows.getString(1))
                        || !"BASE TABLE".equals(rows.getString(2)) || rows.next()) {
                    throw new SQLException("UPSERT requires an InnoDB base table");
                }
            }
        }
        Map<String, Set<String>> indexes = new HashMap<>();
        Set<String> unsafe = new HashSet<>();
        String sql = "SELECT s.INDEX_NAME,s.COLUMN_NAME,s.SUB_PART," + profile.statisticsExpressionSql() + ",c.IS_NULLABLE,s.INDEX_TYPE "
                + "FROM information_schema.STATISTICS s LEFT JOIN information_schema.COLUMNS c "
                + "ON c.TABLE_SCHEMA=s.TABLE_SCHEMA AND c.TABLE_NAME=s.TABLE_NAME AND c.COLUMN_NAME=s.COLUMN_NAME "
                + "WHERE s.TABLE_SCHEMA=? AND s.TABLE_NAME=? AND s.NON_UNIQUE=0 ORDER BY s.INDEX_NAME,s.SEQ_IN_INDEX";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
            statement.setString(1, schemaName);
            statement.setString(2, tableName);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String index = rows.getString(1), column = rows.getString(2);
                    if (column == null || rows.getObject(3) != null || rows.getString(4) != null
                            || !"NO".equals(rows.getString(5)) || !"BTREE".equals(rows.getString(6))) {
                        unsafe.add(index);
                    }
                    indexes.computeIfAbsent(index, ignored -> new HashSet<>()).add(column);
                }
            }
        }
        if (indexes.size() != 1 || !unsafe.isEmpty() || !indexes.values().iterator().next()
                .equals(Set.copyOf(uniqueKeys))) {
            throw new SQLException(
                    "MySQL UPSERT requires exactly one complete non-null unique index matching the explicit keys");
        }
    }

    /**
     * 写入已完成预处理的数据行。
     */
    @Override
    protected BatchWriteResult writePreparedRows(java.util.List<Object[]> rows) {
        JdbcBatchExecutor.execute(dataSource, context -> {
            int[] types = inspect(context.connection(), true);
            if (cursorKeyFingerprint != null) {
                MysqlCursorMetadata.requireReplayWithoutTriggers(context.connection(), schemaName, tableName,
                        runtimeOptions.queryTimeoutSeconds());
                if (!cursorKeyFingerprint.equals(
                        MysqlCursorMetadata.keyContract(context.connection(), profile, schemaName, tableName,
                                uniqueKeys, runtimeOptions.queryTimeoutSeconds()).nativeFingerprint())) {
                    throw new SQLException("Cursor target key comparison changed");
                }
            }
            profile.executeUpsertBatch(context, upsertSql, rows, types, runtimeOptions.queryTimeoutSeconds());
            return null;
        });
        // 仅在提交返回且自有连接成功清理后发布。
        return new BatchWriteResult(rows.size(), null, CommitAcknowledgement.JDBC_COMMIT_RETURNED);
    }

    /**
     * 按目标方言生成按键更新或插入的 SQL。
     */
    @Override
    protected String buildUpsertSql() {
        var updates = updateFields();
        String assignments = updates.isEmpty() ? quote(uniqueKeys.getFirst()) + "=" + quote(uniqueKeys.getFirst())
                : String.join(",", updates.stream().map(name -> quote(name) + "=VALUES(" + quote(name) + ")").toList());
        return "INSERT INTO " + target() + " (" + quoted(fields) + ") VALUES (" + placeholders()
                + ") ON DUPLICATE KEY UPDATE " + assignments;
    }
}
