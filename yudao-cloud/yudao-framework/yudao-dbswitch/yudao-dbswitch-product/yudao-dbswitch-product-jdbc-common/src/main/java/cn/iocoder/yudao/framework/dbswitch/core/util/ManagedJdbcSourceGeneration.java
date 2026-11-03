package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;

import java.sql.*;
import java.util.*;

/**
 * 两个旧版加锁 JDBC 配置使用的显式管理员代际协议。
 * 运行时绝不安装或修复对象。恢复、重载、TRUNCATE 和克隆需要隔离，
 * 排空所有消费方，并在恢复数据库之外保留新固定信息。恢复的标记
 * 本身无法检测恢复。代际 UUID 由调用方提供，使旋转可对账。
 */
public final class ManagedJdbcSourceGeneration {

    private static final int MAX_HISTORY = 10_000;

    public interface Backend {

        /**
         * 返回当前操作使用的数据库产品。
         */
        String product();

        /**
         * 核对连接实际产品与当前实现的产品要求。
         */
        void verifyProduct(Connection connection) throws SQLException;

        /**
         * 生成模式与表组成的限定关系名。
         */
        String relation(String namespace, String table);

        /**
         * 返回当前存在性。
         */
        boolean exists(Connection connection, String namespace, String table, int timeout) throws SQLException;

        /** 验证精确物理列、普通表及唯一主键；拒绝触发器或默认值。 */
        void validate(Connection connection, String namespace, String table, boolean head,
                int timeout) throws SQLException;

        /**
         * 获取当前操作所需的数据库或资源锁。
         */
        void lock(Connection connection, String relation, int timeout) throws SQLException;

        /** Some servers replace the preceding LOCK TABLES set; acquire the whole set at once. */
        default void lockAll(Connection connection, String head, String history, int timeout) throws SQLException {
            lock(connection, head, timeout);
            lock(connection, history, timeout);
        }

        /** Called only after a confirmed commit/rollback and restored autoCommit, never UNKNOWN. */
        default void unlock(Connection connection, int timeout) throws SQLException {
        }
    }

    private record Head(SourceGenerationPin pin, String state) {

    }

    @FunctionalInterface
    private interface Work<T> {

        /**
         * 在当前代次管理连接上调用数据库工作回调。
         */
        T run() throws SQLException;
    }

    private final Backend backend;

    /**
     * 校验构造参数并初始化 {@code ManagedJdbcSourceGeneration}。
     */
    public ManagedJdbcSourceGeneration(Backend backend) {
        this.backend = Objects.requireNonNull(backend);
    }

    /**
     * 返回当前标记。
     */
    public String marker(String namespace) {
        return name(namespace, "m");
    }

    /**
     * 返回当前历史记录。
     */
    public String history(String namespace) {
        return name(namespace, "h");
    }

    /**
     * 返回名称。
     */
    private String name(String namespace, String suffix) {
        SourceGenerationPin.validateSchema(namespace);
        return "DSG_" + DigestUtil.sha256Hex("LOCKED_GENERATION_V1\0" + backend.product() + '\0' + namespace)
                .substring(0, 16).toUpperCase(Locale.ROOT) + "_" + suffix.toUpperCase(Locale.ROOT);
    }

    /**
     * 生成当前对象的限定引用名。
     */
    private String ref(String namespace, boolean head) {
        return backend.relation(namespace, head ? marker(namespace) : history(namespace));
    }

    /**
     * 初始化当前组件的运行资源和内部状态。
     */
    public SourceGenerationPin initialize(Connection connection, String namespace, int timeout) throws SQLException {
        SourceGenerationPin.validateSchema(namespace);
        idle(connection, timeout);
        boolean head = backend.exists(connection, namespace, marker(namespace), timeout);
        boolean history = backend.exists(connection, namespace, history(namespace), timeout);
        if (head != history) {
            throw new SQLException("Incomplete generation installation requires explicit repair");
        }
        if (!head) {
            execute(connection, "CREATE TABLE " + ref(namespace, true)
                            + " (SINGLETON_ID INTEGER NOT NULL PRIMARY KEY,PROTOCOL_VERSION INTEGER NOT NULL,INSTANCE_ID VARCHAR(36) NOT NULL,GENERATION VARCHAR(36) NOT NULL,STATE VARCHAR(6) NOT NULL)",
                    timeout);
            execute(connection, "CREATE TABLE " + ref(namespace, false)
                            + " (GENERATION VARCHAR(36) NOT NULL PRIMARY KEY,INSTANCE_ID VARCHAR(36) NOT NULL,PREVIOUS_GENERATION VARCHAR(36) NULL)",
                    timeout);
        }
        return transaction(connection, namespace, timeout, () -> {
            Head current = head(connection, namespace, timeout);
            if (current != null) {
                audit(connection, current.pin(), timeout);
                return current.pin();
            }
            if (!all(connection, namespace, timeout).isEmpty()) {
                throw new SQLException("Generation history exists without its head");
            }
            var pin = new SourceGenerationPin(namespace, UUID.randomUUID().toString(), UUID.randomUUID().toString());
            insertHistory(connection, pin, null, timeout);
            update(connection, "INSERT INTO " + ref(namespace, true) + " VALUES(1,1,?,?,'ACTIVE')", timeout,
                    pin.instanceId(), pin.generation());
            return pin;
        });
    }

    /**
     * 校验当前资源身份与代次，阻止使用已经失效的读写状态。
     */
    public void fence(Connection connection, SourceGenerationPin pin, int timeout) throws SQLException {
        idle(connection, timeout);
        transaction(connection, pin.schema(), timeout, () -> {
            Head current = head(connection, pin.schema(), timeout);
            require(current, pin, false);
            audit(connection, pin, timeout);
            if ("ACTIVE".equals(current.state())) {
                update(connection, "UPDATE " + ref(pin.schema(), true)
                                + " SET STATE='FENCED' WHERE SINGLETON_ID=1 AND INSTANCE_ID=? AND GENERATION=? AND STATE='ACTIVE'",
                        timeout, pin.instanceId(), pin.generation());
            }
            return null;
        });
    }

    /**
     * 校验旧代次并切换当前资源的代次标记。
     */
    public SourceGenerationPin rotate(Connection connection, SourceGenerationPin previous, String nextUuid,
            int timeout) throws SQLException {
        var next = new SourceGenerationPin(previous.schema(), previous.instanceId(), nextUuid);
        if (next.equals(previous)) {
            throw new SQLException("Rotation requires a fresh generation");
        }
        idle(connection, timeout);
        return transaction(connection, previous.schema(), timeout, () -> {
            Head current = head(connection, previous.schema(), timeout);
            if (current == null) {
                throw new SQLException("Generation head is missing");
            }
            audit(connection, current.pin(), timeout);
            var used = all(connection, previous.schema(), timeout);
            if (current.pin().equals(next) && "ACTIVE".equals(current.state()) && Objects.equals(used.get(nextUuid),
                    previous.generation())) {
                return next;
            }
            require(current, previous, false);
            if (!"FENCED".equals(current.state()) || used.containsKey(nextUuid) || used.size() >= MAX_HISTORY) {
                throw new SQLException("Generation must be fenced and the next UUID unused");
            }
            insertHistory(connection, next, previous.generation(), timeout);
            update(connection, "UPDATE " + ref(previous.schema(), true)
                            + " SET GENERATION=?,STATE='ACTIVE' WHERE SINGLETON_ID=1 AND INSTANCE_ID=? AND GENERATION=? AND STATE='FENCED'",
                    timeout, nextUuid, previous.instanceId(), previous.generation());
            return next;
        });
    }

    /**
     * 重新读取当前对象事实并校验其与预期一致。
     */
    public void verifyCurrent(Connection connection, SourceGenerationPin pin, int timeout) throws SQLException {
        if (pin == null) {
            throw new SQLException("A retained deployment generation pin is required");
        }
        backend.verifyProduct(connection);
        shapes(connection, pin.schema(), timeout);
        require(head(connection, pin.schema(), timeout), pin, true);
        audit(connection, pin, timeout);
        require(head(connection, pin.schema(), timeout), pin, true);
    }

    /** 此命名空间的所有消费方排空后，由管理员清理。 */
    public void drop(Connection connection, String namespace, int timeout) throws SQLException {
        idle(connection, timeout);
        shapes(connection, namespace, timeout);
        execute(connection, "DROP TABLE " + ref(namespace, false), timeout);
        execute(connection, "DROP TABLE " + ref(namespace, true), timeout);
    }

    /**
     * 返回当前首部。
     */
    private Head head(Connection c, String namespace, int timeout) throws SQLException {
        try (var p = c.prepareStatement(
                "SELECT SINGLETON_ID,PROTOCOL_VERSION,INSTANCE_ID,GENERATION,STATE FROM " + ref(namespace, true))) {
            p.setQueryTimeout(timeout);
            p.setMaxRows(2);
            try (var r = p.executeQuery()) {
                if (!r.next()) {
                    return null;
                }
                if (r.getInt(1) != 1 || r.getInt(2) != 1) {
                    throw new SQLException("Generation protocol differs");
                }
                var pin = new SourceGenerationPin(namespace, r.getString(3), r.getString(4));
                String state = r.getString(5);
                if (!Set.of("ACTIVE", "FENCED").contains(state) || r.next()) {
                    throw new SQLException("Generation head is ambiguous");
                }
                return new Head(pin, state);
            }
        }
    }

    /**
     * 检查当前状态、固定代次、活跃是否满足当前操作的约定。
     */
    private static void require(Head current, SourceGenerationPin pin, boolean active) throws SQLException {
        if (current == null || !pin.equals(current.pin()) || active && !"ACTIVE".equals(current.state())) {
            throw new SQLException("Source generation changed or is fenced");
        }
    }

    /**
     * 根据规则及当前数据生成质量审计结果。
     */
    private void audit(Connection c, SourceGenerationPin pin, int timeout) throws SQLException {
        try (var p = c.prepareStatement(
                "SELECT INSTANCE_ID,PREVIOUS_GENERATION FROM " + ref(pin.schema(), false) + " WHERE GENERATION=?")) {
            p.setQueryTimeout(timeout);
            p.setString(1, pin.generation());
            try (var r = p.executeQuery()) {
                if (!r.next() || !pin.instanceId().equals(r.getString(1))) {
                    throw new SQLException("Generation receipt is missing");
                }
                String old = r.getString(2);
                if (old != null) {
                    new SourceGenerationPin(pin.schema(), pin.instanceId(), old);
                }
                if (pin.generation().equals(old) || r.next()) {
                    throw new SQLException("Generation receipt is ambiguous");
                }
            }
        }
    }

    /**
     * 返回当前全部。
     */
    private Map<String, String> all(Connection c, String namespace, int timeout) throws SQLException {
        var values = new LinkedHashMap<String, String>();
        try (var p = c.prepareStatement(
                "SELECT GENERATION,INSTANCE_ID,PREVIOUS_GENERATION FROM " + ref(namespace, false))) {
            p.setQueryTimeout(timeout);
            p.setMaxRows(MAX_HISTORY + 1);
            try (var r = p.executeQuery()) {
                while (r.next()) {
                    var pin = new SourceGenerationPin(namespace, r.getString(2), r.getString(1));
                    String old = r.getString(3);
                    if (old != null) {
                        new SourceGenerationPin(namespace, pin.instanceId(), old);
                    }
                    if (values.size() >= MAX_HISTORY || values.containsKey(pin.generation())) {
                        throw new SQLException("Generation history exceeds its bound");
                    }
                    values.put(pin.generation(), old);
                }
            }
        }
        return values;
    }

    /**
     * 插入历史记录。
     */
    private void insertHistory(Connection c, SourceGenerationPin pin, String previous,
            int timeout) throws SQLException {
        update(c, "INSERT INTO " + ref(pin.schema(), false) + " VALUES(?,?,?)", timeout, pin.generation(),
                pin.instanceId(), previous);
    }

    /**
     * 读取当前字段集合对应的结构定义。
     */
    private void shapes(Connection c, String namespace, int timeout) throws SQLException {
        backend.validate(c, namespace, marker(namespace), true, timeout);
        backend.validate(c, namespace, history(namespace), false, timeout);
    }

    /**
     * 校验当前连接处于允许开始操作的空闲状态。
     */
    private void idle(Connection c, int timeout) throws SQLException {
        backend.verifyProduct(c);
        if (!c.getAutoCommit() || timeout < 1 || timeout > 120) {
            throw new SQLException("Administrator requires an idle owned connection and bounded timeout");
        }
    }

    /**
     * 返回事务。
     */
    private <T> T transaction(Connection c, String namespace, int timeout, Work<T> work) throws SQLException {
        c.setAutoCommit(false);
        boolean commitAttempted = false, reusable = false;
        try {
            backend.lockAll(c, ref(namespace, true), ref(namespace, false), timeout);
            shapes(c, namespace, timeout);
            T value = work.run();
            commitAttempted = true;
            c.commit();
            reusable = true;
            return value;
        } catch (Throwable failure) {
            if (!commitAttempted) {
                try {
                    c.rollback();
                    reusable = true;
                } catch (Throwable cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            if (failure instanceof Error fatal) {
                throw fatal;
            }
            throw new SQLException(
                    commitAttempted ? "Generation commit outcome is UNKNOWN; reconcile the requested UUID" : "Generation operation failed before commit");
        } finally {
            if (reusable) {
                try {
                    c.setAutoCommit(true);
                    backend.unlock(c, timeout);
                } catch (Throwable cleanup) {
                    terminate(c);
                    if (cleanup instanceof Error fatal) {
                        throw fatal;
                    }
                    throw new SQLException(
                            "Generation connection cleanup outcome is UNKNOWN; reconcile the requested UUID");
                }
            } else {
                terminate(c);
            }
        }
    }

    /**
     * 尽力中止并关闭 JDBC 连接，忽略清理过程异常。
     */
    private static void terminate(Connection c) {
        try {
            c.abort(Runnable::run);
        } catch (Throwable ignored) {
        } finally {
            try {
                c.close();
            } catch (Throwable ignoredAgain) {
            }
        }
    }

    /**
     * 在当前数据库连接上执行给定 SQL 语句。
     */
    private static void execute(Connection c, String sql, int timeout) throws SQLException {
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            s.execute(sql);
        }
    }

    /**
     * 按当前键和预期状态更新持久化记录。
     */
    private static void update(Connection c, String sql, int timeout, String... values) throws SQLException {
        try (var p = c.prepareStatement(sql)) {
            p.setQueryTimeout(timeout);
            for (int i = 0; i < values.length; i++) {
                p.setString(i + 1, values[i]);
            }
            if (p.executeUpdate() != 1) {
                throw new SQLException("Generation compare-and-set did not affect exactly one row");
            }
        }
    }
}
