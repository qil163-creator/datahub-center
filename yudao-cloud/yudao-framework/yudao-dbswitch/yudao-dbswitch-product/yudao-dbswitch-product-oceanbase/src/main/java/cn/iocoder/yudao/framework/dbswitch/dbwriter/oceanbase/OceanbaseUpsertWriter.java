package cn.iocoder.yudao.framework.dbswitch.dbwriter.oceanbase;

import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.core.util.MysqlCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.util.OceanbaseCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.AbstractNativeUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.BatchWriteResult;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.product.oceanbase.OceanbaseExactValues;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

/**
 * 在已验证 OceanBase MySQL 租户上执行 INSERT ... ON DUPLICATE KEY UPDATE。语句没有冲突
 * 目标，所以要求恰好一个完整、非空、与显式键相等的唯一索引。受影响行
 * 数（此驱动下插入为 1、改变为 2、未改变为 1）不是行依据；仅确认已提交
 * 批次。在写入连接上，DML 前后均观测模式、tablet 及目录；后一次检查时，
 * 未提交的 DML 已阻止 TRUNCATE。构造过程绝不打开连接。
 */
public final class OceanbaseUpsertWriter extends AbstractNativeUpsertWriter {

    private OceanbaseCursorMetadata.Shape shape;

    private int[] bindTypes;

    private boolean instants;

    private String cursorKeyFingerprint;

    /**
     * 初始化 {@code OceanbaseUpsertWriter}及其父类状态。
     */
    public OceanbaseUpsertWriter(DataSource source) {
        super(source);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "OceanBase";
    }

    /**
     * 按OceanBase方言转义并引用标识符。
     */
    @Override
    protected String quote(String name) {
        return OceanbaseCursorMetadata.quote(name);
    }

    /**
     * 核对连接实际产品与当前实现的产品要求。
     */
    @Override
    protected void verifyProduct(Connection connection) throws SQLException {
        OceanbaseCursorMetadata.requireSession(connection, runtimeOptions.queryTimeoutSeconds(), true);
    }

    /**
     * 补充当前产品特有的模式身份信息。
     */
    @Override
    protected String additionalSchemaIdentity(Connection connection) throws SQLException {
        shape = OceanbaseCursorMetadata.inspect(connection, schemaName, tableName,
                runtimeOptions.queryTimeoutSeconds());
        return shape.identity();
    }

    /**
     * 检查指定键列是否满足当前写入路径要求的唯一键条件。
     */
    @Override
    protected void verifyUniqueKey(Connection connection) throws SQLException {
        var indexes = shape.uniqueIndexes();
        if (indexes.size() != 1 || !shape.unsafeUniqueIndexes().isEmpty()
                || indexes.values().iterator().next().size() != uniqueKeys.size()
                || !Set.copyOf(indexes.values().iterator().next()).equals(Set.copyOf(uniqueKeys))) {
            throw new SQLException(
                    "OceanBase UPSERT requires exactly one complete non-null unique index matching the explicit keys");
        }
        int[] types = new int[fields.size()];
        boolean utc = false;
        for (int i = 0; i < types.length; i++) {
            var column = shape.columns().get(fields.get(i));
            if (column == null) {
                throw new SQLException("OceanBase UPSERT target column is missing");
            }
            types[i] = OceanbaseExactValues.bindType(column);
            utc |= OceanbaseExactValues.kind(column) == OceanbaseExactValues.Kind.TIMESTAMP;
        }
        // 绝对时刻在临时 UTC 会话中绑定为 UTC 文本；未修改的民用时钟默认值会随时区改变。
        if (utc) {
            for (var column : shape.columns().values()) {
                if (!fields.contains(column.name()) && column.usesSessionClock()) {
                    throw new SQLException("OceanBase target has civil clock defaults outside the written fields");
                }
            }
        }
        bindTypes = types;
        instants = utc;
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
            if (!connection.getAutoCommit()) {
                throw new SQLException("Caller transaction is not supported");
            }
            inspect(connection, false);
            MysqlCursorMetadata.requireReplayWithoutTriggers(connection, schemaName, tableName,
                    runtimeOptions.queryTimeoutSeconds());
            var contract = OceanbaseCursorMetadata.keyContract(shape, uniqueKeys);
            cursorKeyFingerprint = contract.nativeFingerprint();
            return contract;
        } catch (SQLException failure) {
            throw new IllegalArgumentException("Typed cursor target key contract is unsupported");
        }
    }

    /**
     * 写入已完成预处理的数据行。
     */
    @Override
    protected BatchWriteResult writePreparedRows(List<Object[]> rows) {
        var columns = shape.columns();
        var names = fields;
        int timeout = runtimeOptions.queryTimeoutSeconds();
        // 借用连接前证明整批精确性：严格模式仍可能舍入或填充。
        List<Object[]> exact = BatchValueConverter.convert(rows, names.size(), batchLimits,
                (value, index) -> OceanbaseExactValues.write(columns.get(names.get(index)), value));
        JdbcBatchExecutor.execute(dataSource, context -> {
            var connection = context.connection();
            inspect(connection, true);
            if (cursorKeyFingerprint != null) {
                MysqlCursorMetadata.requireReplayWithoutTriggers(connection, schemaName, tableName, timeout);
                if (!cursorKeyFingerprint.equals(
                        OceanbaseCursorMetadata.keyContract(shape, uniqueKeys).nativeFingerprint())) {
                    throw new SQLException("Cursor target key comparison changed");
                }
            }
            String previousZone = instants ? timeZone(connection, timeout) : null;
            try {
                // TIMESTAMP 是绝对时刻：不得按另一会话时区重新解释其精确 UTC 文本。
                if (instants) {
                    timeZone(connection, "+00:00", timeout);
                }
                JdbcBatchExecutor.executeBatch(context, upsertSql, exact, bindTypes, timeout);
                // 普通读取不会锁定 OceanBase 表，未提交 DML 会锁定。因此，此处检测
                // 在锁定前插入的 TRUNCATE 或重建，并禁止提交事务。
                inspect(connection, true);
            } finally {
                // 恢复失败传递到 JdbcBatchExecutor 的回滚及丢弃逻辑，绝不复用连接池或报告成功。
                if (instants) {
                    timeZone(connection, previousZone, timeout);
                }
            }
            return null;
        });
        // 仅在 commit 返回且自有连接成功清理后发布。
        return new BatchWriteResult(rows.size(), null, CommitAcknowledgement.JDBC_COMMIT_RETURNED);
    }

    /**
     * 解析当前配置或类型声明中的时区。
     */
    private static String timeZone(Connection connection, int timeout) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (var result = statement.executeQuery("SELECT @@session.time_zone")) {
                String zone;
                if (!result.next() || (zone = result.getString(1)) == null || result.next()) {
                    throw new SQLException("OceanBase target time zone is unavailable");
                }
                return zone;
            }
        }
    }

    /**
     * 解析当前配置或类型声明中的时区。
     */
    private static void timeZone(Connection connection, String zone, int timeout) throws SQLException {
        try (var statement = connection.prepareStatement("SET SESSION time_zone=?")) {
            statement.setQueryTimeout(timeout);
            statement.setString(1, zone);
            statement.execute();
        }
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
