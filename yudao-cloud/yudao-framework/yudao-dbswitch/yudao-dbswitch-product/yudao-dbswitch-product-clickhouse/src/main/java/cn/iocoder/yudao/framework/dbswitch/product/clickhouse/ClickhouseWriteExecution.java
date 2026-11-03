package cn.iocoder.yudao.framework.dbswitch.product.clickhouse;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

/** ClickHouse 管理独立请求，而非 JDBC 事务。绝不重试已尝试发送的请求。 */
public final class ClickhouseWriteExecution {

    /**
     * 初始化 {@code ClickhouseWriteExecution}。
     */
    private ClickhouseWriteExecution() {
    }

    @FunctionalInterface
    public interface Work {

        /**
         * 在当前 ClickHouse 连接及写入尝试上下文中调用工作回调。
         */
        long run(Connection connection, Attempt attempt) throws Exception;
    }

    public static final class Attempt {

        private boolean sent;

        /**
         * 记录当前批次进入发送阶段。
         */
        public void sending() {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("ClickHouse write interrupted");
            }
            sent = true;
        }
    }

    /**
     * 使用独立连接调用 ClickHouse 写入回调，并跟踪写入尝试及未知结果。
     */
    public static long execute(DataSource dataSource, Work work) {
        Attempt attempt = new Attempt();
        try (Connection connection = dataSource.getConnection()) {
            // 绝不为虚构事务调用 get/setAutoCommit、commit 或 rollback。
            return work.run(connection, attempt);
        } catch (Exception failure) {
            if (attempt.sent) {
                throw new UnconfirmedWriteException();
            }
            throw new IllegalStateException("ClickHouse write preflight failed before sending data");
        }
    }

    /**
     * 按当前定义插入一条或一批数据。
     */
    public static long insert(DataSource dataSource, String sql, List<Object[]> rows, int timeout) {
        if (rows.isEmpty()) {
            return 0;
        }
        if (timeout < 1) {
            throw new IllegalArgumentException("A positive query timeout is required");
        }
        return execute(dataSource, (connection, attempt) -> insert(connection, attempt, sql, rows, timeout));
    }

    /**
     * 按当前定义插入一条或一批数据。
     */
    public static long insert(Connection connection, Attempt attempt, String sql, List<Object[]> rows,
            int timeout) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeout);
            // 在 executeBatch 可能发送任何数据前绑定每一行。
            for (Object[] row : rows) {
                bind(statement, row);
                statement.addBatch();
            }
            attempt.sending();
            int[] counts = statement.executeBatch();
            if (counts == null || counts.length != rows.size()) {
                throw new IllegalStateException("Incomplete INSERT response");
            }
            long confirmed = 0;
            for (int count : counts) {
                // SUCCESS_NO_INFO 无法证明此 API 的已确认行数。
                if (count != 1) {
                    throw new IllegalStateException("INSERT row count is not confirmed");
                }
                confirmed += count;
            }
            return confirmed;
        }
    }

    /**
     * 将当前值绑定到预编译语句参数。
     */
    public static void bind(PreparedStatement statement, Object[] values) throws Exception {
        for (int index = 0; index < values.length; index++) {
            statement.setObject(index + 1, values[index]);
        }
    }
}
