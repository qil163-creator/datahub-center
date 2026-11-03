package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.product.sqlserver.SqlServerCursorMetadata;

import java.sql.*;
import java.util.*;

/**
 * 针对已有专用 SQL Server 模式的显式管理员协议，运行时仅读取。
 * 恢复、TRUNCATE、重载或克隆前，须停止并等待消费者结束、实施隔离并使用新的
 * 外部固定标识；将此标记与数据一起恢复，无法检测到该次恢复。
 * DDL 和 CAS 在同一本地事务内执行；显式指定下一个 UUID，使轮换重试可对账。
 */
public final class SqlServerSourceGeneration {

    public static final String MARKER = "dbswitch_source_generation", HISTORY = "dbswitch_source_generation_history";

    private static final int MAX_HISTORY = 10_000;

    /**
     * 初始化 {@code SqlServerSourceGeneration}。
     */
    private SqlServerSourceGeneration() {
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
     * 初始化当前组件的运行资源和内部状态。
     */
    public static SourceGenerationPin initialize(Connection c, String schema, int timeout) throws SQLException {
        SourceGenerationPin.validateSchema(schema);
        idle(c, timeout);
        return transaction(c, () -> {
            try (var p = c.prepareStatement(
                    "DECLARE @r int; EXEC @r=sys.sp_getapplock @Resource=?,@LockMode='Exclusive',@LockOwner='Transaction',@LockTimeout=10000; SELECT @r")) {
                p.setQueryTimeout(timeout);
                p.setString(1, "dbswitch-source-generation:" + schema);
                try (var r = p.executeQuery()) {
                    if (!r.next() || r.getInt(1) < 0) {
                        throw new SQLException("Generation installation lock unavailable");
                    }
                }
            }
            try (var p = c.prepareStatement("SELECT 1 FROM sys.schemas WHERE name=?")) {
                p.setQueryTimeout(timeout);
                p.setString(1, schema);
                try (var r = p.executeQuery()) {
                    if (!r.next()) {
                        throw new SQLException("Dedicated generation schema must already exist");
                    }
                }
            }
            boolean marker = exists(c, schema, MARKER, timeout), history = exists(c, schema, HISTORY, timeout);
            if (marker != history) {
                throw new SQLException("Incomplete generation installation requires explicit repair");
            }
            if (!marker) {
                execute(c, "CREATE TABLE " + ref(schema,
                                MARKER) + " (singleton_id tinyint NOT NULL PRIMARY KEY CHECK(singleton_id=1),protocol_version tinyint NOT NULL CHECK(protocol_version=1),instance_id char(36) COLLATE Latin1_General_100_BIN2 NOT NULL,generation char(36) COLLATE Latin1_General_100_BIN2 NOT NULL,state varchar(8) COLLATE Latin1_General_100_BIN2 NOT NULL CHECK(state IN('ACTIVE','FENCED')))",
                        timeout);
                execute(c, "CREATE TABLE " + ref(schema,
                                HISTORY) + " (generation char(36) COLLATE Latin1_General_100_BIN2 NOT NULL PRIMARY KEY,instance_id char(36) COLLATE Latin1_General_100_BIN2 NOT NULL,previous_generation char(36) COLLATE Latin1_General_100_BIN2 NULL)",
                        timeout);
            }
            shapes(c, schema, timeout);
            var head = head(c, schema, true, timeout);
            if (head != null) {
                audit(c, head.pin(), timeout);
                return head.pin();
            }
            if (!history(c, schema, timeout).isEmpty()) {
                throw new SQLException("Generation head missing but history exists");
            }
            var pin = new SourceGenerationPin(schema, UUID.randomUUID().toString(), UUID.randomUUID().toString());
            insertHistory(c, pin, null, timeout);
            update(c, "INSERT " + ref(schema, MARKER) + " VALUES(1,1,?,?,'ACTIVE')", timeout, pin.instanceId(),
                    pin.generation());
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
                                MARKER) + " SET state='FENCED' WHERE singleton_id=1 AND instance_id=? AND generation=? AND state='ACTIVE'",
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
            var previous = history(c, old.schema(), timeout);
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
                            MARKER) + " SET generation=?,state='ACTIVE' WHERE singleton_id=1 AND instance_id=? AND generation=? AND state='FENCED'",
                    timeout, nextUuid, old.instanceId(), old.generation());
            return next;
        });
    }

    /** READCOMMITTEDLOCK 有意读取当前隔离标记，即使位于数据 SNAPSHOT 内也如此。 */
    public static void verifyCurrent(Connection c, SourceGenerationPin pin, int timeout) throws SQLException {
        if (pin == null) {
            throw new SQLException("SQL Server deployed generation pin is required");
        }
        SqlServerCursorMetadata.product(c);
        shapes(c, pin.schema(), timeout);
        require(head(c, pin.schema(), false, timeout), pin, true);
        audit(c, pin, timeout);
        require(head(c, pin.schema(), false, timeout), pin, true);
    }

    /**
     * 返回当前首部。
     */
    private static Head head(Connection c, String schema, boolean lock, int timeout) throws SQLException {
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            s.setMaxRows(2);
            try (var r = s.executeQuery(
                    "SELECT singleton_id,protocol_version,instance_id,generation,state FROM " + ref(schema,
                            MARKER) + (lock ? " WITH(UPDLOCK,HOLDLOCK)" : " WITH(READCOMMITTEDLOCK)"))) {
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
            throw new SQLException("SQL Server generation changed or is fenced");
        }
    }

    /**
     * 根据规则及当前数据生成质量审计结果。
     */
    private static void audit(Connection c, SourceGenerationPin pin, int timeout) throws SQLException {
        try (var p = c.prepareStatement("SELECT instance_id,previous_generation FROM " + ref(pin.schema(),
                HISTORY) + " WITH(READCOMMITTEDLOCK) WHERE generation=?")) {
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
     * 返回当前历史记录。
     */
    private static Map<String, String> history(Connection c, String schema, int timeout) throws SQLException {
        var values = new LinkedHashMap<String, String>();
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            s.setMaxRows(MAX_HISTORY + 1);
            try (var r = s.executeQuery("SELECT generation,instance_id,previous_generation FROM " + ref(schema,
                    HISTORY) + " WITH(UPDLOCK,HOLDLOCK)")) {
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
        update(c, "INSERT " + ref(pin.schema(), HISTORY) + " VALUES(?,?,?)", timeout, pin.generation(),
                pin.instanceId(), previous);
    }

    /**
     * 返回当前存在性。
     */
    private static boolean exists(Connection c, String schema, String table, int timeout) throws SQLException {
        try (var p = c.prepareStatement("SELECT OBJECT_ID(?, 'U')")) {
            p.setQueryTimeout(timeout);
            p.setString(1, ref(schema, table));
            try (var r = p.executeQuery()) {
                if (!r.next()) {
                    throw new SQLException("Generation object observation unavailable");
                }
                return r.getObject(1) != null;
            }
        }
    }

    /**
     * 读取当前字段集合对应的结构定义。
     */
    private static void shapes(Connection c, String schema, int timeout) throws SQLException {
        shape(c, schema, MARKER,
                List.of("singleton_id:48:1:false", "protocol_version:48:1:false", "instance_id:175:36:false",
                        "generation:175:36:false", "state:167:8:false"), timeout);
        shape(c, schema, HISTORY,
                List.of("generation:175:36:false", "instance_id:175:36:false", "previous_generation:175:36:true"),
                timeout);
    }

    /**
     * 读取或构建当前关系及字段的结构信息。
     */
    private static void shape(Connection c, String schema, String table, List<String> expected,
            int timeout) throws SQLException {
        String relation = ref(schema, table);
        var columns = new ArrayList<String>();
        try (var p = c.prepareStatement(
                "SELECT c.name,c.system_type_id,c.max_length,c.is_nullable,c.is_identity,c.is_computed,c.collation_name,c.user_type_id FROM sys.columns c WHERE c.object_id=OBJECT_ID(?, 'U') ORDER BY c.column_id")) {
            p.setQueryTimeout(timeout);
            p.setString(1, relation);
            try (var r = p.executeQuery()) {
                while (r.next()) {
                    if (columns.size() >= 8 || r.getBoolean(5) || r.getBoolean(6) || r.getInt(2) != r.getInt(
                            8) || r.getInt(2) != 48 && !"Latin1_General_100_BIN2".equals(r.getString(7))) {
                        throw new SQLException("Generation marker column shape differs");
                    }
                    columns.add(r.getString(1) + ":" + r.getInt(2) + ":" + r.getInt(3) + ":" + r.getBoolean(4));
                }
            }
        }
        if (!columns.equals(expected)) {
            throw new SQLException("Generation installation shape differs");
        }
        try (var p = c.prepareStatement(
                "SELECT HAS_PERMS_BY_NAME(?, 'OBJECT','VIEW DEFINITION'),(SELECT COUNT(*) FROM sys.triggers WHERE parent_id=OBJECT_ID(?)),(SELECT COUNT(*) FROM sys.security_predicates WHERE target_object_id=OBJECT_ID(?)),(SELECT COUNT(*) FROM sys.indexes WHERE object_id=OBJECT_ID(?) AND is_primary_key=1 AND is_disabled=0 AND has_filter=0)")) {
            p.setQueryTimeout(timeout);
            for (int i = 1; i <= 4; i++) {
                p.setString(i, relation);
            }
            try (var r = p.executeQuery()) {
                if (!r.next() || r.getInt(1) != 1 || r.getInt(2) != 0 || r.getInt(3) != 0 || r.getInt(4) != 1) {
                    throw new SQLException("Generation installation metadata is not fully visible or has side effects");
                }
            }
        }
        try (var p = c.prepareStatement(
                "SELECT c.name FROM sys.indexes i JOIN sys.index_columns ic ON ic.object_id=i.object_id AND ic.index_id=i.index_id JOIN sys.columns c ON c.object_id=ic.object_id AND c.column_id=ic.column_id WHERE i.object_id=OBJECT_ID(?) AND i.is_primary_key=1 AND ic.key_ordinal>0 ORDER BY ic.key_ordinal")) {
            p.setQueryTimeout(timeout);
            p.setString(1, relation);
            try (var r = p.executeQuery()) {
                String key = table.equals(MARKER) ? "singleton_id" : "generation";
                if (!r.next() || !key.equals(r.getString(1)) || r.next()) {
                    throw new SQLException("Generation installation primary key differs");
                }
            }
        }
    }

    /**
     * 生成当前对象的限定引用名。
     */
    private static String ref(String schema, String table) {
        SourceGenerationPin.validateSchema(schema);
        return SqlServerCursorMetadata.relation(schema, table);
    }

    /**
     * 校验当前连接处于允许开始操作的空闲状态。
     */
    private static void idle(Connection c, int timeout) throws SQLException {
        SqlServerCursorMetadata.product(c);
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
            // JDBC 通过 IMPLICIT_TRANSACTIONS 禁用自动提交，sp_getapplock 自身不会开启事务。
            try (var s = c.createStatement()) {
                s.setQueryTimeout(15);
                s.execute("IF @@TRANCOUNT=0 BEGIN TRANSACTION");
            }
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
