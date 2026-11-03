package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;

import java.sql.*;
import java.util.*;

/**
 * 原生 TiDB 由外部管理员负责的代次协议，独立于平台迁移。
 * 安装在现有专用模式中；运行时仅需 SELECT 权限及可见的原生元数据。
 * 仅管理员可修改标记、历史及其 DDL。恢复或轮换之前，停止并等待全部消费者退出，
 * 同时隔离克隆，再激活外部保留的新固定记录。
 * 与数据库一并恢复的标记无法检测此次恢复；历史防止现存数据库中的 UUID
 * 重用，但不防止整库回退。每条语句均有执行上限。
 *
 * 初始化 DDL 不具备事务性；仅空且精确匹配的部分安装可
 * 恢复。轮换提供显式 UUID，以便提交确认未知后重试。
 * 运行时绝不初始化、修复、隔离或轮换安装。快照校验
 * 明确独立于当前状态校验，其自身不能证明并发隔离状态。
 */
public final class TidbSourceGeneration {

    public static final String MARKER_TABLE = "dbswitch_source_generation";

    public static final String HISTORY_TABLE = "dbswitch_source_generation_history";

    private static final String OWNER = "dbswitch.tidb-source-generation.v1.";

    private static final int MAX_HISTORY = 10_000;

    /**
     * 初始化 {@code TidbSourceGeneration}。
     */
    private TidbSourceGeneration() {
    }

    /**
     * 初始化当前组件的运行资源和内部状态。
     */
    public static SourceGenerationPin initialize(Connection admin, String schema, int timeout) throws SQLException {
        SourceGenerationPin.validateSchema(schema);
        idle(admin, timeout);
        if (!schema.equals(schema.toLowerCase(Locale.ROOT))) {
            throw new SQLException("TiDB generation schema must use its native lowercase spelling");
        }
        try (var p = prepare(admin, "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=?", timeout,
                schema); var r = p.executeQuery()) {
            if (!r.next() || !schema.equals(r.getString(1)) || r.next()) {
                throw new SQLException("Dedicated generation schema is absent");
            }
        }
        boolean marker = exists(admin, schema, MARKER_TABLE, timeout), history = exists(admin, schema, HISTORY_TABLE,
                timeout);
        if (marker) {
            shape(admin, schema, MARKER_TABLE, timeout);
        }
        if (history) {
            shape(admin, schema, HISTORY_TABLE, timeout);
        }
        if (marker && !history && readMarker(admin, schema, false, timeout) != null
                || history && !marker && !history(admin, schema, timeout).isEmpty()) {
            throw new SQLException("Populated partial generation installation");
        }
        if (!marker) {
            create(admin, schema, MARKER_TABLE, timeout);
        }
        if (!history) {
            create(admin, schema, HISTORY_TABLE, timeout);
        }
        shapes(admin, schema, timeout);
        return transaction(admin, timeout, () -> {
            Marker current = readMarker(admin, schema, true, timeout);
            var log = history(admin, schema, timeout);
            if (current != null) {
                audit(current.pin(), log);
                return current.pin();
            }
            if (!log.isEmpty()) {
                throw new SQLException("Generation history exists without its head");
            }
            var pin = new SourceGenerationPin(schema, UUID.randomUUID().toString(), UUID.randomUUID().toString());
            insertHistory(admin, pin, null, timeout);
            update(admin, "INSERT INTO " + ref(schema, MARKER_TABLE) + " VALUES(1,1,?,?,'ACTIVE')", timeout,
                    pin.instanceId(), pin.generation());
            return pin;
        });
    }

    /**
     * 校验当前资源身份与代次，阻止使用已经失效的读写状态。
     */
    public static void fence(Connection admin, SourceGenerationPin expected, int timeout) throws SQLException {
        Objects.requireNonNull(expected);
        idle(admin, timeout);
        shapes(admin, expected.schema(), timeout);
        transaction(admin, timeout, () -> {
            Marker current = readMarker(admin, expected.schema(), true, timeout);
            require(current, expected, false);
            audit(expected, history(admin, expected.schema(), timeout));
            if (current.state().equals("ACTIVE")) {
                update(admin, "UPDATE " + ref(expected.schema(), MARKER_TABLE)
                                + " SET state='FENCED' WHERE singleton_id=1 AND instance_id=? AND generation=? AND state='ACTIVE'",
                        timeout, expected.instanceId(), expected.generation());
            }
            return null;
        });
    }

    /**
     * 校验旧代次并切换当前资源的代次标记。
     */
    public static SourceGenerationPin rotate(Connection admin, SourceGenerationPin expected, String generation,
            int timeout) throws SQLException {
        var next = new SourceGenerationPin(expected.schema(), expected.instanceId(), generation);
        if (next.equals(expected)) {
            throw new SQLException("Rotation requires a fresh generation UUID");
        }
        idle(admin, timeout);
        shapes(admin, expected.schema(), timeout);
        return transaction(admin, timeout, () -> {
            Marker current = readMarker(admin, expected.schema(), true, timeout);
            if (current == null) {
                throw new SQLException("Generation head missing");
            }
            var log = history(admin, expected.schema(), timeout);
            audit(current.pin(), log);
            History receipt = log.get(generation);
            if (current.pin().equals(next) && current.state().equals("ACTIVE") && receipt != null
                    && Objects.equals(receipt.previous(), expected.generation()) && receipt.instance()
                    .equals(expected.instanceId())) {
                return next;
            }
            require(current, expected, false);
            if (!current.state().equals("FENCED") || receipt != null || log.size() >= MAX_HISTORY) {
                throw new SQLException("Generation must be fenced and rotation UUID unused");
            }
            insertHistory(admin, next, expected.generation(), timeout);
            update(admin, "UPDATE " + ref(expected.schema(),
                            MARKER_TABLE) + " SET generation=?,state='ACTIVE' WHERE singleton_id=1 AND instance_id=? AND generation=? AND state='FENCED'",
                    timeout, generation, expected.instanceId(), expected.generation());
            return next;
        });
    }

    /** 读取当前主节点，不改变连接状态，也不获取行锁。 */
    public static String verifyCurrent(Connection connection, SourceGenerationPin pin,
            int timeout) throws SQLException {
        idle(connection, timeout);
        return verify(connection, pin, timeout);
    }

    /** 仅在与游标固定上界及数据页相同的自有 TiDB TSO 内调用。 */
    public static String verifySnapshot(Connection connection, SourceGenerationPin pin, String tso,
            int timeout) throws SQLException {
        TidbRelationIdentity.requireOwnedSnapshot(connection, tso, timeout);
        return verify(connection, pin, timeout);
    }

    /**
     * 检查数据库连接、固定代次、超时设置是否满足当前操作的约定。
     */
    private static String verify(Connection connection, SourceGenerationPin pin, int timeout) throws SQLException {
        if (pin == null) {
            throw new SQLException("Deployed TiDB generation pin is required");
        }
        shapes(connection, pin.schema(), timeout);
        String markerIdentity = TidbRelationIdentity.cursorFingerprint(connection, pin.schema(), MARKER_TABLE, timeout);
        String historyIdentity = TidbRelationIdentity.cursorFingerprint(connection, pin.schema(), HISTORY_TABLE,
                timeout);
        require(readMarker(connection, pin.schema(), false, timeout), pin, true);
        try (var p = prepare(connection, "SELECT generation,prev_generation,instance_id FROM " + ref(pin.schema(),
                HISTORY_TABLE) + " WHERE generation=? LIMIT 2", timeout, pin.generation()); var r = p.executeQuery()) {
            if (!r.next() || !pin.generation().equals(r.getString(1)) || !pin.instanceId().equals(r.getString(3))) {
                throw new SQLException("Current generation receipt missing");
            }
            String previous = r.getString(2);
            if (previous != null) {
                new SourceGenerationPin(pin.schema(), pin.instanceId(), previous);
            }
            if (pin.generation().equals(previous) || r.next()) {
                throw new SQLException("Current generation receipt corrupt");
            }
        }
        require(readMarker(connection, pin.schema(), false, timeout), pin, true);
        return cn.hutool.crypto.digest.DigestUtil.sha256Hex(
                "TIDB_DEPLOYED_GENERATION_V1\0" + pin.fingerprint() + "\0" + markerIdentity + "\0" + historyIdentity);
    }

    /**
     * 校验当前连接处于允许开始操作的空闲状态。
     */
    private static void idle(Connection connection, int timeout) throws SQLException {
        if (timeout < 1) {
            throw new IllegalArgumentException("Generation timeout must be positive");
        }
        TidbRelationIdentity.requireIdleSession(connection, timeout);
        try (var p = prepare(connection, "SELECT @@tidb_txn_mode", timeout); var r = p.executeQuery()) {
            if (!r.next() || !"pessimistic".equals(r.getString(1)) || r.next()) {
                throw new SQLException("Generation requires native pessimistic transactions");
            }
        }
    }

    /**
     * 返回当前存在性。
     */
    private static boolean exists(Connection c, String schema, String table, int timeout) throws SQLException {
        try (var p = prepare(c, "SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?",
                timeout, schema, table); var r = p.executeQuery()) {
            return r.next();
        }
    }

    /**
     * 读取当前字段集合对应的结构定义。
     */
    private static void shapes(Connection c, String schema, int timeout) throws SQLException {
        shape(c, schema, MARKER_TABLE, timeout);
        shape(c, schema, HISTORY_TABLE, timeout);
    }

    /**
     * 读取或构建当前关系及字段的结构信息。
     */
    private static void shape(Connection c, String schema, String table, int timeout) throws SQLException {
        TidbRelationIdentity.cursorFingerprint(c, schema, table, timeout); // 拒绝临时表遮蔽、视图及非原生标识。
        try (var p = prepare(c,
                "SELECT TABLE_COMMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?", timeout,
                schema, table); var r = p.executeQuery()) {
            if (!r.next() || !(OWNER + table).equals(r.getString(1)) || r.next()) {
                throw new SQLException("Generation object ownership changed");
            }
        }
        List<String> actual = new ArrayList<>();
        try (var p = prepare(c,
                "SELECT COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLLATION_NAME,COLUMN_DEFAULT,EXTRA,GENERATION_EXPRESSION FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION LIMIT 9",
                timeout, schema, table); var r = p.executeQuery()) {
            while (r.next()) {
                if (r.getObject(5) != null || !r.getString(6).isEmpty() || !r.getString(7).isEmpty()) {
                    throw new SQLException("Generation column behavior changed");
                }
                actual.add(r.getString(1) + ":" + r.getString(2)
                        .replaceAll("(tinyint|smallint)\\([0-9]+\\)", "$1") + ":" + r.getString(3) + ":" + r.getString(
                        4));
            }
        }
        var expected = table.equals(MARKER_TABLE) ? List.of("singleton_id:tinyint unsigned:NO:null",
                "schema_version:smallint unsigned:NO:null", "instance_id:char(36):NO:ascii_bin",
                "generation:char(36):NO:ascii_bin", "state:varchar(6):NO:ascii_bin")
                : List.of("generation:char(36):NO:ascii_bin", "prev_generation:char(36):YES:ascii_bin",
                "instance_id:char(36):NO:ascii_bin");
        if (!actual.equals(expected)) {
            throw new SQLException("Generation column shape changed");
        }
        Map<String, String> keys = new HashMap<>();
        try (var p = prepare(c,
                "SELECT INDEX_NAME,COLUMN_NAME,NON_UNIQUE,SEQ_IN_INDEX,SUB_PART,EXPRESSION,INDEX_TYPE FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? LIMIT 9",
                timeout, schema, table); var r = p.executeQuery()) {
            while (r.next()) {
                if (r.getInt(3) != 0 || r.getInt(4) != 1 || r.getObject(5) != null || r.getString(
                        6) != null || !"BTREE".equals(r.getString(7)) || keys.put(r.getString(1),
                        r.getString(2)) != null) {
                    throw new SQLException("Generation unique key changed");
                }
            }
        }
        if (!keys.equals(
                table.equals(MARKER_TABLE) ? Map.of("PRIMARY", "singleton_id") : Map.of("PRIMARY", "generation",
                        "sg_previous", "prev_generation"))) {
            throw new SQLException("Generation unique key set changed");
        }
    }

    /**
     * 根据给定参数创建 {@code void} 实例。
     */
    private static void create(Connection c, String schema, String table, int timeout) throws SQLException {
        String columns = table.equals(
                MARKER_TABLE) ? "singleton_id TINYINT UNSIGNED NOT NULL PRIMARY KEY,schema_version SMALLINT UNSIGNED NOT NULL,instance_id CHAR(36) NOT NULL,generation CHAR(36) NOT NULL,state VARCHAR(6) NOT NULL"
                : "generation CHAR(36) NOT NULL PRIMARY KEY,prev_generation CHAR(36) NULL,instance_id CHAR(36) NOT NULL,UNIQUE KEY sg_previous(prev_generation)";
        try (var p = prepare(c, "CREATE TABLE " + ref(schema,
                        table) + "(" + columns + ") ENGINE=InnoDB DEFAULT CHARSET=ascii COLLATE=ascii_bin COMMENT='" + OWNER + table + "'",
                timeout)) {
            p.execute();
        } catch (SQLException conflict) {
            if (conflict.getErrorCode() != 1050) {
                throw conflict;
            }
        }
        shape(c, schema, table, timeout);
    }

    /**
     * 读取当前资源的代次或状态标记。
     */
    private static Marker readMarker(Connection c, String schema, boolean lock, int timeout) throws SQLException {
        try (var p = prepare(c, "SELECT singleton_id,schema_version,instance_id,generation,state FROM " + ref(schema,
                MARKER_TABLE) + " LIMIT 2" + (lock ? " FOR UPDATE NOWAIT" : ""), timeout); var r = p.executeQuery()) {
            if (!r.next()) {
                return null;
            }
            if (r.getInt(1) != 1 || r.getInt(2) != 1) {
                throw new SQLException("Generation header invalid");
            }
            var marker = new Marker(new SourceGenerationPin(schema, r.getString(3), r.getString(4)), r.getString(5));
            if (!Set.of("ACTIVE", "FENCED").contains(marker.state()) || r.next()) {
                throw new SQLException("Generation head ambiguous");
            }
            return marker;
        }
    }

    /**
     * 返回当前历史记录。
     */
    private static Map<String, History> history(Connection c, String schema, int timeout) throws SQLException {
        Map<String, History> result = new HashMap<>();
        try (var p = prepare(c, "SELECT generation,prev_generation,instance_id FROM " + ref(schema,
                HISTORY_TABLE) + " LIMIT " + (MAX_HISTORY + 1), timeout); var r = p.executeQuery()) {
            while (r.next()) {
                var pin = new SourceGenerationPin(schema, r.getString(3), r.getString(1));
                String prev = r.getString(2);
                if (prev != null) {
                    new SourceGenerationPin(schema, pin.instanceId(), prev);
                }
                if (result.put(pin.generation(),
                        new History(prev, pin.instanceId())) != null || result.size() > MAX_HISTORY) {
                    throw new SQLException("Generation history bound/collision");
                }
            }
        }
        return result;
    }

    /**
     * 根据规则及当前数据生成质量审计结果。
     */
    private static void audit(SourceGenerationPin head, Map<String, History> log) throws SQLException {
        Set<String> visited = new HashSet<>();
        String generation = head.generation();
        while (generation != null) {
            History entry = log.get(generation);
            if (entry == null || !entry.instance().equals(head.instanceId()) || !visited.add(generation)) {
                throw new SQLException("Generation history chain corrupt");
            }
            generation = entry.previous();
        }
        if (visited.size() != log.size()) {
            throw new SQLException("Generation history has unrelated branches");
        }
    }

    /**
     * 检查标记、固定代次、活跃是否满足当前操作的约定。
     */
    private static void require(Marker marker, SourceGenerationPin pin, boolean active) throws SQLException {
        if (marker == null || !pin.equals(marker.pin()) || active && !marker.state().equals("ACTIVE")) {
            throw new SQLException("Source generation is stale or fenced");
        }
    }

    /**
     * 插入历史记录。
     */
    private static void insertHistory(Connection c, SourceGenerationPin pin, String previous,
            int timeout) throws SQLException {
        update(c, "INSERT INTO " + ref(pin.schema(), HISTORY_TABLE) + " VALUES(?,?,?)", timeout, pin.generation(),
                previous, pin.instanceId());
    }

    /**
     * 按当前键和预期状态更新持久化记录。
     */
    private static void update(Connection c, String sql, int timeout, String... args) throws SQLException {
        try (var p = prepare(c, sql, timeout, args)) {
            if (p.executeUpdate() != 1) {
                throw new SQLException("Generation CAS lost ownership");
            }
        }
    }

    /**
     * 创建带查询超时和参数绑定的目录查询语句。
     */
    private static PreparedStatement prepare(Connection c, String sql, int timeout,
            String... args) throws SQLException {
        var p = c.prepareStatement(sql);
        try {
            p.setQueryTimeout(timeout);
            for (int i = 0; i < args.length; i++) {
                p.setString(i + 1, args[i]);
            }
            return p;
        } catch (SQLException failure) {
            p.close();
            throw failure;
        }
    }

    /**
     * 生成当前对象的限定引用名。
     */
    private static String ref(String schema, String table) {
        SourceGenerationPin.validateSchema(schema);
        return MysqlCursorMetadata.reference(schema, table);
    }

    @FunctionalInterface
    private interface Work<T> {

        /**
         * 在当前代次管理连接上调用数据库工作回调。
         */
        T run() throws SQLException;
    }

    /**
     * 返回事务。
     */
    private static <T> T transaction(Connection c, int timeout, Work<T> work) throws SQLException {
        c.setAutoCommit(false);
        boolean safe = false;
        try {
            TidbRelationIdentity.beginOwnedSnapshot(c, timeout);
            T value = work.run();
            c.commit();
            safe = true;
            return value;
        } catch (SQLException | RuntimeException failure) {
            try {
                c.rollback();
                safe = true;
            } catch (SQLException rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        } finally {
            if (safe) {
                c.setAutoCommit(true);
            }
        }
    }

    private record Marker(SourceGenerationPin pin, String state) {

    }

    private record History(String previous, String instance) {

    }
}
