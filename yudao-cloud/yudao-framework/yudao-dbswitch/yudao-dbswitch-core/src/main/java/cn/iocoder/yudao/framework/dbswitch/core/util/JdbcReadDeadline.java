package cn.iocoder.yudao.framework.dbswitch.core.util;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTimeoutException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 独占元数据连接的总读取期限；旧接口不启用，显式调用方必须使用支持网络超时的驱动。 */
public final class JdbcReadDeadline implements AutoCloseable {

    private static final ScheduledThreadPoolExecutor TIMER;

    static {
        TIMER = new ScheduledThreadPoolExecutor(2, task -> {
            return Thread.ofPlatform().name("dbswitch-metadata-deadline").daemon()
                    .inheritInheritableThreadLocals(false).unstarted(task);
        });
        TIMER.setRemoveOnCancelPolicy(true);
        TIMER.setKeepAliveTime(30, TimeUnit.SECONDS);
        TIMER.allowCoreThreadTimeOut(true);
    }

    private final AtomicBoolean expired = new AtomicBoolean();

    private final long deadlineNanos;

    private final ScheduledFuture<?> timeout;

    /**
     * 校验构造参数并初始化 {@code JdbcReadDeadline}。
     */
    public JdbcReadDeadline(Connection connection, int timeoutMillis) throws SQLException {
        if (timeoutMillis < 1 || timeoutMillis > 120_000) {
            throw new IllegalArgumentException("元数据总读取期限必须在一至十二万毫秒之间");
        }
        deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        // 驱动不支持时直接拒绝，不降级为无界阻塞读取。连接由调用方独占并在操作后关闭。
        try {
            connection.setNetworkTimeout(Runnable::run, timeoutMillis);
        } catch (AbstractMethodError incompatibleDriver) {
            // JDBC 4.0 二进制库可在 Java 25 链接，但可能缺少 JDBC 4.1 方法。
            // 保留与驱动显式拒绝时相同的受检拒绝行为。
            throw new SQLFeatureNotSupportedException(
                    "Metadata deadlines require JDBC network timeout support", "0A000", incompatibleDriver);
        }
        timeout = TIMER.schedule(() -> {
            expired.set(true);
            try {
                connection.abort(Runnable::run);
            } catch (SQLException | AbstractMethodError ignored) {
                // 驱动的网络超时仍生效，业务层通过 check 检查总期限并关闭连接。
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 检查当前状态是否满足当前操作的约定。
     */
    public void check() throws SQLTimeoutException {
        if (expired.get() || System.nanoTime() - deadlineNanos >= 0) {
            throw new SQLTimeoutException("元数据读取超过总期限");
        }
    }

    /**
     * 取消当前读取期限对应的超时任务。
     */
    @Override
    public void close() {
        timeout.cancel(false);
    }
}
