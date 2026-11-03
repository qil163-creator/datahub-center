package cn.iocoder.yudao.framework.dbswitch.dbcommon.database;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;

import javax.sql.DataSource;
import java.sql.Connection;

/** 不支持 JDBC 事务控制的产品采用请求级确认。不重试，也不回滚。 */
public final class NonTransactionalWriteExecution {

    /**
     * 初始化 {@code NonTransactionalWriteExecution}。
     */
    private NonTransactionalWriteExecution() {
    }

    @FunctionalInterface
    public interface Work {

        /**
         * 在当前连接和非事务写入尝试上下文中调用工作回调。
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
                throw new IllegalStateException("Native JDBC write interrupted");
            }
            sent = true;
        }
    }

    /**
     * 在独立连接上调用非事务写入回调，并记录已尝试及结果未知状态。
     */
    public static long execute(DataSource dataSource, Work work) {
        var attempt = new Attempt();
        try (Connection connection = dataSource.getConnection()) {
            return work.run(connection, attempt);
        } catch (Exception failure) {
            if (attempt.sent) {
                throw new UnconfirmedWriteException();
            }
            throw new IllegalStateException("Native JDBC write preflight failed before sending data");
        }
    }
}
