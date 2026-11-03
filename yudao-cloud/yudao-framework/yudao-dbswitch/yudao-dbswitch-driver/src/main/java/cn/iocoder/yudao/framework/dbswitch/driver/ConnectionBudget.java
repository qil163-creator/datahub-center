package cn.iocoder.yudao.framework.dbswitch.driver;

import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.Objects;

/** 上下文级物理连接上限。正在创建和隔离中的连接仍占用许可。 */
public final class ConnectionBudget {

    public enum Role {METADATA, WORKLOAD}

    public record Snapshot(int limit, int metadataReserved, int metadata, int workload,
                           int creating, int active, int quarantined, int waiting) {

    }

    private final int limit, reserved;

    private int metadata, workload, creating, active, quarantined, waiting;

    /**
     * 校验构造参数并初始化 {@code ConnectionBudget}。
     */
    public ConnectionBudget(int limit, int metadataReserved) {
        if (limit < 2 || metadataReserved < 1 || metadataReserved >= limit) {
            throw new IllegalArgumentException("Invalid JDBC connection budget");
        }
        this.limit = limit;
        this.reserved = metadataReserved;
    }

    /**
     * 创建当前组件的默认配置。
     */
    public static ConnectionBudget defaults() {
        return new ConnectionBudget(32, 4);
    }

    /**
     * 根据当前配置获取可用资源并登记其使用配额。
     */
    public synchronized Permit acquire(Role role,
            Duration timeout) throws SQLTransientConnectionException, SQLTimeoutException {
        Objects.requireNonNull(role);
        Objects.requireNonNull(timeout);
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Connection wait budget must be within five minutes");
        }
        if (waiting >= 256) {
            throw new SQLTransientConnectionException("JDBC connection budget wait queue is full");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        waiting++;
        try {
            while (metadata + workload >= limit || role == Role.WORKLOAD && workload >= limit - reserved) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new SQLTimeoutException("JDBC physical connection budget exceeded");
                }
                try {
                    wait(Math.max(1, Math.min(1000, left / 1_000_000)));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new SQLTransientConnectionException("JDBC connection budget wait interrupted");
                }
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new SQLTransientConnectionException("JDBC connection acquisition interrupted");
            }
            if (role == Role.METADATA) {
                metadata++;
            } else {
                workload++;
            }
            creating++;
            return new Permit(this, role);
        } finally {
            waiting--;
        }
    }

    /**
     * 返回快照。
     */
    public synchronized Snapshot snapshot() {
        return new Snapshot(limit, reserved, metadata, workload, creating, active, quarantined, waiting);
    }

    public static final class Permit implements AutoCloseable {

        private final ConnectionBudget owner;

        private final Role role;

        private int state; // 0 创建中，1 活跃，2 隔离中，3 已释放

        /**
         * 初始化 {@code Permit}。
         */
        private Permit(ConnectionBudget owner, Role role) {
            this.owner = owner;
            this.role = role;
        }

        /**
         * 登记当前连接已建立并更新连接配额计数。
         */
        public void connected() {
            synchronized (owner) {
                if (state == 0) {
                    owner.creating--;
                    owner.active++;
                    state = 1;
                }
            }
        }

        /**
         * 将尚未安全退出的运行资源放入隔离状态。
         */
        public void quarantine() {
            synchronized (owner) {
                if (state >= 2) {
                    return;
                }
                if (state == 0) {
                    owner.creating--;
                } else {
                    owner.active--;
                }
                owner.quarantined++;
                state = 2;
            }
        }

        /** 仅物理所有者可在确认退出，或创建失败且没有连接后调用。 */
        @Override
        public void close() {
            synchronized (owner) {
                if (state == 3) {
                    return;
                }
                if (state == 0) {
                    owner.creating--;
                } else if (state == 1) {
                    owner.active--;
                } else {
                    owner.quarantined--;
                }
                if (role == Role.METADATA) {
                    owner.metadata--;
                } else {
                    owner.workload--;
                }
                state = 3;
                owner.notifyAll();
            }
        }
    }
}
