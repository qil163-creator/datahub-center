package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.product.db2.Db2CursorMetadata;
import cn.hutool.crypto.digest.DigestUtil;

import java.sql.*;
import java.util.*;

/**
 * DB2 LUW 12.1.5.0 的显式管理员协议：标记表位于固定记录的模式中，名称
 * 由该模式确定性生成，运行时仅读取。恢复、TRUNCATE、重载及克隆操作
 * 要求消费者已停止且完全退出，并设置隔离标记及新的外部保留固定记录。若将此
 * 标记与数据一并恢复，则无法检测此次恢复。最新记录更新与 CAS 在同一本地
 * 事务内完成；显式提供下一个 UUID，使轮换重试能够对账。
 */
public final class Db2SourceGeneration {

    private static final int MAX_HISTORY = 10_000;

    /**
     * 初始化 {@code Db2SourceGeneration}。
     */
    private Db2SourceGeneration() {
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

    /**
     * 返回当前标记。
     */
    public static String marker(String schema) {
        return "DSG_" + DigestUtil.sha256Hex("DB2_SOURCE_GENERATION_V1\0" + schema).substring(0, 16)
                .toUpperCase() + "_M";
    }

    /**
     * 返回当前历史记录。
     */
    public static String history(String schema) {
        return "DSG_" + DigestUtil.sha256Hex("DB2_SOURCE_GENERATION_V1\0" + schema).substring(0, 16)
                .toUpperCase() + "_H";
    }

    /**
     * 生成当前对象的限定引用名。
     */
    private static String ref(String schema, String table) {
        SourceGenerationPin.validateSchema(schema);
        return Db2CursorMetadata.relation(schema, table);
    }

    /**
     * 初始化当前组件的运行资源和内部状态。
     */
    public static SourceGenerationPin initialize(Connection c, String schema, int timeout) throws SQLException {
        SourceGenerationPin.validateSchema(schema);
        idle(c, timeout);
        boolean marker = exists(c, schema, marker(schema), timeout), history = exists(c, schema, history(schema),
                timeout);
        if (marker != history) {
            throw new SQLException("Incomplete generation installation requires explicit repair");
        }
        if (!marker) {
            execute(c, "CREATE TABLE " + ref(schema,
                            marker(schema)) + " (SINGLETON_ID SMALLINT NOT NULL PRIMARY KEY CHECK(SINGLETON_ID=1),PROTOCOL_VERSION SMALLINT NOT NULL CHECK(PROTOCOL_VERSION=1),INSTANCE_ID VARCHAR(36) NOT NULL,GENERATION VARCHAR(36) NOT NULL,STATE VARCHAR(6) NOT NULL CHECK(STATE IN('ACTIVE','FENCED')))",
                    timeout);
            execute(c, "CREATE TABLE " + ref(schema,
                            history(schema)) + " (GENERATION VARCHAR(36) NOT NULL PRIMARY KEY,INSTANCE_ID VARCHAR(36) NOT NULL,PREVIOUS_GENERATION VARCHAR(36))",
                    timeout);
        }
        return transaction(c, () -> {
            shapes(c, schema, timeout);
            var head = head(c, schema, true, timeout);
            if (head != null) {
                audit(c, head.pin(), timeout);
                return head.pin();
            }
            if (!all(c, schema, timeout).isEmpty()) {
                throw new SQLException("Generation head missing but history exists");
            }
            var pin = new SourceGenerationPin(schema, UUID.randomUUID().toString(), UUID.randomUUID().toString());
            insertHistory(c, pin, null, timeout);
            update(c, "INSERT INTO " + ref(schema, marker(schema)) + " VALUES(1,1,?,?,'ACTIVE')", timeout,
                    pin.instanceId(), pin.generation());
            return pin;
        });
    }

    /**
     * 校验当前资源身份与代次，阻止使用已经失效的读写状态。
     */
    public static void fence(Connection c, SourceGenerationPin pin, int timeout) throws SQLException {
        idle(c, timeout);
        transaction(c, () -> {
            shapes(c, pin.schema(), timeout);
            var h = head(c, pin.schema(), true, timeout);
            require(h, pin, false);
            audit(c, pin, timeout);
            if (h.state().equals("ACTIVE")) {
                update(c, "UPDATE " + ref(pin.schema(),
                                marker(pin.schema())) + " SET STATE='FENCED' WHERE SINGLETON_ID=1 AND INSTANCE_ID=? AND GENERATION=? AND STATE='ACTIVE'",
                        timeout, pin.instanceId(), pin.generation());
            }
            return null;
        });
    }

    /**
     * 校验旧代次并切换当前资源的代次标记。
     */
    public static SourceGenerationPin rotate(Connection c, SourceGenerationPin old, String nextUuid,
            int timeout) throws SQLException {
        var next = new SourceGenerationPin(old.schema(), old.instanceId(), nextUuid);
        if (next.equals(old)) {
            throw new SQLException("A fresh generation is required");
        }
        idle(c, timeout);
        return transaction(c, () -> {
            shapes(c, old.schema(), timeout);
            var h = head(c, old.schema(), true, timeout);
            if (h == null) {
                throw new SQLException("Generation head unavailable");
            }
            audit(c, h.pin(), timeout);
            var previous = all(c, old.schema(), timeout);
            if (h.pin().equals(next) && h.state().equals("ACTIVE") && Objects.equals(previous.get(nextUuid),
                    old.generation())) {
                return next;
            }
            require(h, old, false);
            if (!h.state().equals("FENCED") || previous.containsKey(nextUuid) || previous.size() >= MAX_HISTORY) {
                throw new SQLException("Generation must be fenced and the next UUID unused");
            }
            insertHistory(c, next, old.generation(), timeout);
            update(c, "UPDATE " + ref(old.schema(),
                            marker(old.schema())) + " SET GENERATION=?,STATE='ACTIVE' WHERE SINGLETON_ID=1 AND INSTANCE_ID=? AND GENERATION=? AND STATE='FENCED'",
                    timeout, nextUuid, old.instanceId(), old.generation());
            return next;
        });
    }

    /** 读取当前已提交状态的标记；游标通过专用 CS 连接校验它。 */
    public static void verifyCurrent(Connection c, SourceGenerationPin pin, int timeout) throws SQLException {
        if (pin == null) {
            throw new SQLException("DB2 deployed generation pin is required");
        }
        Db2CursorMetadata.product(c);
        shapes(c, pin.schema(), timeout);
        require(head(c, pin.schema(), false, timeout), pin, true);
        audit(c, pin, timeout);
        require(head(c, pin.schema(), false, timeout), pin, true);
    }

    /**
     * 返回当前首部。
     */
    private static Head head(Connection c, String schema, boolean lock, int timeout) throws SQLException {
        try (var p = c.prepareStatement(
                "SELECT SINGLETON_ID,PROTOCOL_VERSION,INSTANCE_ID,GENERATION,STATE FROM " + ref(schema,
                        marker(schema)))) {
            p.setQueryTimeout(timeout);
            p.setMaxRows(2);
            try (var r = p.executeQuery()) {
                if (!r.next()) {
                    return null;
                }
                if (r.getInt(1) != 1 || r.getInt(2) != 1) {
                    throw new SQLException("Generation head protocol differs");
                }
                var pin = new SourceGenerationPin(schema, r.getString(3), r.getString(4));
                String state = r.getString(5);
                if (!Set.of("ACTIVE", "FENCED").contains(state) || r.next()) {
                    throw new SQLException("Generation head is ambiguous");
                }
                return new Head(pin, state);
            }
        }
    }

    /**
     * 检查首部、固定代次、活跃是否满足当前操作的约定。
     */
    private static void require(Head head, SourceGenerationPin pin, boolean active) throws SQLException {
        if (head == null || !pin.equals(head.pin()) || active && !head.state().equals("ACTIVE")) {
            throw new SQLException("DB2 generation changed or is fenced");
        }
    }

    /**
     * 根据规则及当前数据生成质量审计结果。
     */
    private static void audit(Connection c, SourceGenerationPin pin, int timeout) throws SQLException {
        try (var p = c.prepareStatement("SELECT INSTANCE_ID,PREVIOUS_GENERATION FROM " + ref(pin.schema(),
                history(pin.schema())) + " WHERE GENERATION=?")) {
            p.setQueryTimeout(timeout);
            p.setString(1, pin.generation());
            try (var r = p.executeQuery()) {
                if (!r.next() || !pin.instanceId().equals(r.getString(1))) {
                    throw new SQLException("Generation receipt unavailable");
                }
                String old = r.getString(2);
                if (old != null) {
                    new SourceGenerationPin(pin.schema(), pin.instanceId(), old);
                }
                if (pin.generation().equals(old) || r.next()) {
                    throw new SQLException("Generation receipt ambiguous");
                }
            }
        }
    }

    /**
     * 返回当前全部。
     */
    private static Map<String, String> all(Connection c, String schema, int timeout) throws SQLException {
        var values = new LinkedHashMap<String, String>();
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            s.setMaxRows(MAX_HISTORY + 1);
            try (var r = s.executeQuery(
                    "SELECT GENERATION,INSTANCE_ID,PREVIOUS_GENERATION FROM " + ref(schema, history(schema)))) {
                while (r.next()) {
                    var pin = new SourceGenerationPin(schema, r.getString(2), r.getString(1));
                    String old = r.getString(3);
                    if (old != null) {
                        new SourceGenerationPin(schema, pin.instanceId(), old);
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
    private static void insertHistory(Connection c, SourceGenerationPin pin, String previous,
            int timeout) throws SQLException {
        update(c, "INSERT INTO " + ref(pin.schema(), history(pin.schema())) + " VALUES(?,?,?)", timeout,
                pin.generation(), pin.instanceId(), previous);
    }

    /**
     * 返回当前存在性。
     */
    private static boolean exists(Connection c, String schema, String table, int timeout) throws SQLException {
        try (var p = c.prepareStatement(
                "SELECT TABNAME FROM SYSCAT.TABLES WHERE TABSCHEMA=? AND TABNAME=? AND TYPE='T'")) {
            p.setQueryTimeout(timeout);
            p.setString(1, schema);
            p.setString(2, table);
            try (var r = p.executeQuery()) {
                if (!r.next()) {
                    return false;
                }
                if (r.next()) {
                    throw new SQLException("Generation object observation is ambiguous");
                }
                return true;
            }
        }
    }

    /**
     * 读取当前字段集合对应的结构定义。
     */
    private static void shapes(Connection c, String schema, int timeout) throws SQLException {
        shape(c, schema, marker(schema), List.of("SINGLETON_ID:SMALLINT:2:false", "PROTOCOL_VERSION:SMALLINT:2:false",
                "INSTANCE_ID:VARCHAR:36:false", "GENERATION:VARCHAR:36:false", "STATE:VARCHAR:6:false"), timeout);
        shape(c, schema, history(schema), List.of("GENERATION:VARCHAR:36:false", "INSTANCE_ID:VARCHAR:36:false",
                "PREVIOUS_GENERATION:VARCHAR:36:true"), timeout);
    }

    /**
     * 读取或构建当前关系及字段的结构信息。
     */
    private static void shape(Connection c, String schema, String table, List<String> expected,
            int timeout) throws SQLException {
        var columns = new ArrayList<String>();
        try (var p = c.prepareStatement(
                "SELECT COLNAME,TYPENAME,LENGTH,NULLS FROM SYSCAT.COLUMNS WHERE TABSCHEMA=? AND TABNAME=? ORDER BY COLNO")) {
            p.setQueryTimeout(timeout);
            p.setString(1, schema);
            p.setString(2, table);
            try (var r = p.executeQuery()) {
                while (r.next()) {
                    if (columns.size() >= 5) {
                        throw new SQLException("Generation marker column shape differs");
                    }
                    columns.add(r.getString(1) + ":" + r.getString(2) + ":" + r.getInt(3) + ":" + "Y".equals(
                            r.getString(4)));
                }
            }
        }
        if (!columns.equals(expected)) {
            throw new SQLException("Generation installation shape differs");
        }
        String constraint = null;
        try (var p = c.prepareStatement(
                "SELECT CONSTNAME FROM SYSCAT.TABCONST WHERE TABSCHEMA=? AND TABNAME=? AND TYPE='P'")) {
            p.setQueryTimeout(timeout);
            p.setString(1, schema);
            p.setString(2, table);
            try (var r = p.executeQuery()) {
                while (r.next()) {
                    if (constraint != null) {
                        throw new SQLException("Generation installation has multiple primary keys");
                    }
                    constraint = r.getString(1);
                }
            }
        }
        if (constraint == null) {
            throw new SQLException("Generation installation primary key differs");
        }
    }

    /** 管理员清理安装记录，要求所有消费者均不再引用该安装。 */
    public static void drop(Connection c, String schema, int timeout) throws SQLException {
        idle(c, timeout);
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            s.execute("DROP TABLE " + ref(schema, history(schema)));
        } catch (SQLException missing) {
        }
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            s.execute("DROP TABLE " + ref(schema, marker(schema)));
        }
    }

    /**
     * 校验当前连接处于允许开始操作的空闲状态。
     */
    private static void idle(Connection c, int timeout) throws SQLException {
        Db2CursorMetadata.product(c);
        if (!c.getAutoCommit() || timeout < 1 || timeout > 120) {
            throw new SQLException("Administrator requires an idle owned connection and bounded timeout");
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

    /**
     * 返回事务。
     */
    private static <T> T transaction(Connection c, Work<T> work) throws SQLException {
        c.setAutoCommit(false);
        try {
            T value = work.run();
            c.commit();
            return value;
        } catch (SQLException | RuntimeException failure) {
            try {
                c.rollback();
            } catch (SQLException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        } finally {
            c.setAutoCommit(true);
        }
    }
}
