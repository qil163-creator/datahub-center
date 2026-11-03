package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 原生 MariaDB 11.4 的可选源端代次协议；DBA 必须创建专用模式，
 * 并将写入及 DDL 权限限制为管理主体。运行时需要两张表的 SELECT 权限及
 * 可见元数据。标记缺失表示不支持；权限错误保留 JDBC
 * 分类；损坏、过期或被隔离的标记均以普通 SQLException 失败。
 *
 * <p>本类不关闭或共享连接；所有入口都要求自动提交且不存在
 * 显式事务。运行时仅读取，调用方在操作前后均须
 * 校验。管理员调用持有自己的短事务，并在成功提交或
 * 回滚后恢复自动提交。禁用自动提交或回滚失败时，调用方必须
 * 丢弃连接；本类既不恢复自动提交（可能提交残余
 * 工作），也不关闭调用方连接。清理失败作为被抑制异常附在主错误上。
 * 提交确认失败意味着结果未知；在可用的管理连接上重试同一预期固定记录及新 UUID，
 * 对照持久化历史回执进行对账。初始化
 * DDL 可能部分成功，仅空且精确匹配的协议表可恢复。已有数据的
 * 标记若缺少历史或历史损坏，绝不自动修复。
 *
 * <p>此协议不检测恢复或克隆；完整恢复要求消费者停止、克隆隔离，
 * 以及外部固定记录轮换。不得删除历史，其主键防止 UUID 重用或 ABA。
 * 协议使用期间必须排除模式及触发器管理操作。每条语句采用
 * 五秒 JDBC 超时；历史审计最多允许 10,000 个代次，超出上限即拒绝。
 * 运行时仅读取当前历史回执。启用此能力之前，必须取得原生数据库集成凭据，
 * 包括已部署驱动的超时行为。
 */
public final class MariaDbSourceGeneration {

    public static final String MARKER_TABLE = "dbswitch_source_generation";

    public static final String HISTORY_TABLE = "dbswitch_source_generation_history";

    private static final int TIMEOUT_SECONDS = 5;

    private static final int MAX_HISTORY = 10_000;

    private static final String ACTIVE = "ACTIVE";

    private static final String FENCED = "FENCED";

    private static final String OWNER = "dbswitch.source-generation.v1.";

    private static final String TABLE_FILTER = " WHERE BINARY TABLE_SCHEMA = BINARY ? AND BINARY TABLE_NAME = BINARY ?";

    private static final List<Column> MARKER_COLUMNS = List.of(
            new Column("singleton_id", "tinyint unsigned", false, null),
            new Column("schema_version", "smallint unsigned", false, null),
            new Column("instance_id", "char(36)", false, "ascii_bin"),
            new Column("generation", "char(36)", false, "ascii_bin"),
            new Column("state", "varchar(6)", false, "ascii_bin"));

    private static final List<Column> HISTORY_COLUMNS = List.of(
            new Column("generation", "char(36)", false, "ascii_bin"),
            new Column("prev_generation", "char(36)", true, "ascii_bin"),
            new Column("instance_id", "char(36)", false, "ascii_bin"));

    private static final Map<String, String> MARKER_CHECKS = Map.of(
            "sg_singleton", "singleton_id=1", "sg_version", "schema_version=1",
            "sg_state", "statein('ACTIVE','FENCED')");

    /**
     * 初始化 {@code MariaDbSourceGeneration}。
     */
    private MariaDbSourceGeneration() {
    }

    /** 绝不创建模式、替换对象或重新激活已有隔离标记。 */
    public static SourceGenerationPin initialize(Connection admin, String existingDedicatedSchema) throws SQLException {
        SourceGenerationPin.validateSchema(existingDedicatedSchema);
        requireConnection(admin);
        String schema = existingDedicatedSchema;
        requireSchema(admin, schema);
        boolean markerPresent = inspectTable(admin, schema, MARKER_TABLE);
        boolean historyPresent = inspectTable(admin, schema, HISTORY_TABLE);
        // 执行任何 DDL 之前先检查两个现有对象；其中一张表已有数据不属于部分 DDL。
        if (markerPresent && !historyPresent && readMarker(admin, schema, false) != null
                || historyPresent && !markerPresent && !readHistory(admin, schema).isEmpty()) {
            throw invalid("Incomplete populated source generation installation");
        }
        if (!markerPresent) {
            createTable(admin, schema, MARKER_TABLE);
        }
        if (!historyPresent) {
            createTable(admin, schema, HISTORY_TABLE);
        }
        requireTables(admin, schema);
        return inTransaction(admin, () -> {
            Marker marker = readMarker(admin, schema, true);
            Map<String, History> history = readHistory(admin, schema);
            if (marker != null) {
                verifyHistory(marker.pin(), history);
                return marker.pin();
            }
            if (!history.isEmpty()) {
                throw invalid("History exists without its source generation marker");
            }
            SourceGenerationPin pin = new SourceGenerationPin(schema, UUID.randomUUID().toString(),
                    UUID.randomUUID().toString());
            insertHistory(admin, pin, null);
            updateExactlyOne(admin, "INSERT INTO " + qualified(schema, MARKER_TABLE)
                            + " (singleton_id, schema_version, instance_id, generation, state) VALUES (1, 1, ?, ?, ?)",
                    pin.instanceId(), pin.generation(), ACTIVE);
            return pin;
        });
    }

    /** CAS ACTIVE → FENCED；重复同一隔离固定记录不进行修改，过期固定记录则失败。 */
    public static void fence(Connection admin, SourceGenerationPin expected) throws SQLException {
        Objects.requireNonNull(expected, "expected");
        requireConnection(admin);
        requireTables(admin, expected.schema());
        inTransaction(admin, () -> {
            Marker marker = requireMarker(admin, expected.schema(), true);
            requirePin(marker, expected);
            verifyHistory(marker.pin(), readHistory(admin, expected.schema()));
            if (ACTIVE.equals(marker.state())) {
                updateExactlyOne(admin, "UPDATE " + qualified(expected.schema(), MARKER_TABLE)
                                + " SET state = ? WHERE singleton_id = 1 AND schema_version = 1"
                                + " AND instance_id = ? AND generation = ? AND state = ?",
                        FENCED, expected.instanceId(), expected.generation(), ACTIVE);
            }
            return null;
        });
    }

    /**
     * CAS FENCED(expected) → ACTIVE(newGeneration)，保留 instanceId。仅当该次精确轮换仍是活跃最新记录，
     * 且历史回执匹配时，重试才返回同一固定记录。
     * 后续隔离或轮换会使旧请求过期，即使旧回执仍存在。
     */
    public static SourceGenerationPin rotate(Connection admin, SourceGenerationPin expected, String newGeneration)
            throws SQLException {
        Objects.requireNonNull(expected, "expected");
        SourceGenerationPin next = new SourceGenerationPin(expected.schema(), expected.instanceId(), newGeneration);
        if (expected.generation().equals(newGeneration)) {
            throw invalid("A rotation must use a fresh generation");
        }
        requireConnection(admin);
        requireTables(admin, expected.schema());
        return inTransaction(admin, () -> {
            Marker marker = requireMarker(admin, expected.schema(), true);
            Map<String, History> history = readHistory(admin, expected.schema());
            verifyHistory(marker.pin(), history);
            History receipt = history.get(newGeneration);
            if (marker.pin().equals(next) && ACTIVE.equals(marker.state()) && receipt != null
                    && expected.generation().equals(receipt.previous())
                    && expected.instanceId().equals(receipt.instance())) {
                return next;
            }
            requirePin(marker, expected);
            if (!FENCED.equals(marker.state())) {
                throw invalid("Source generation must be fenced before rotation");
            }
            if (receipt != null) {
                throw invalid("A source generation UUID cannot be reused");
            }
            if (history.size() >= MAX_HISTORY) {
                throw invalid("Source generation history audit bound reached");
            }
            // generation 上的原生主键是并发 UUID 重用及 ABA 的最终保护。
            insertHistory(admin, next, expected.generation());
            updateExactlyOne(admin, "UPDATE " + qualified(expected.schema(), MARKER_TABLE)
                            + " SET generation = ?, state = ? WHERE singleton_id = 1 AND schema_version = 1"
                            + " AND instance_id = ? AND generation = ? AND state = ?",
                    newGeneration, ACTIVE, expected.instanceId(), expected.generation(), FENCED);
            return next;
        });
    }

    /** 只读校验；绝不调用管理员入口或改变连接状态。 */
    public static void readVerified(Connection runtime, SourceGenerationPin expected) throws SQLException {
        Objects.requireNonNull(expected, "expected");
        requireConnection(runtime);
        if (!inspectTable(runtime, expected.schema(), MARKER_TABLE)) {
            throw unsupported("Source generation marker is not installed or not visible");
        }
        Marker marker = requireMarker(runtime, expected.schema(), false);
        requirePin(marker, expected);
        if (!ACTIVE.equals(marker.state())) {
            throw invalid("Source generation is fenced");
        }
        if (!inspectTable(runtime, expected.schema(), HISTORY_TABLE)) {
            throw invalid("Source generation history is missing");
        }
        try (PreparedStatement statement = prepare(runtime, "SELECT generation, prev_generation, instance_id FROM "
                        + qualified(expected.schema(), HISTORY_TABLE) + " WHERE generation = ? LIMIT 2", 2,
                expected.generation()); ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                throw invalid("Current source generation has no history receipt");
            }
            History receipt = historyRow(expected.schema(), rows);
            if (!expected.generation().equals(receipt.generation())
                    || !expected.instanceId().equals(receipt.instance()) || rows.next()) {
                throw invalid("Current source generation history is inconsistent");
            }
        }
        // 检查元数据或历史期间的隔离、轮换，不得被视为成功读取旧固定记录。
        Marker after = requireMarker(runtime, expected.schema(), false);
        requirePin(after, expected);
        if (!ACTIVE.equals(after.state())) {
            throw invalid("Source generation is fenced");
        }
    }

    /**
     * 校验并取得数据库连接。
     */
    private static void requireConnection(Connection connection) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        if (!connection.getAutoCommit()) {
            throw new SQLException("Source generation requires autocommit", "25000");
        }
        var metadata = connection.getMetaData();
        if (!"MariaDB".equals(metadata.getDatabaseProductName()) || metadata.getDatabaseMajorVersion() != 11
                || metadata.getDatabaseMinorVersion() != 4) {
            throw unsupported("Only native MariaDB 11.4 source generation is supported");
        }
        try (PreparedStatement statement = prepare(connection,
                "SELECT VERSION(), @@version_comment, @@autocommit, @@in_transaction, @@check_constraint_checks", 2);
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                throw invalid("MariaDB session cannot be verified");
            }
            String version = rows.getString(1);
            String comment = rows.getString(2);
            if (version == null || !version.matches("11\\.4\\.[0-9]+-MariaDB(?:[-+].*)?") || comment == null
                    || comment.toLowerCase(Locale.ROOT).matches(".*(tidb|oceanbase|starrocks|doris|percona).*")) {
                throw unsupported("Native MariaDB 11.4 server identity cannot be verified");
            }
            if (!"1".equals(rows.getString(3)) || !"0".equals(rows.getString(4))) {
                throw new SQLException("Source generation requires no active transaction", "25000");
            }
            if (!"1".equals(rows.getString(5)) || rows.next()) {
                throw invalid("MariaDB constraint enforcement cannot be verified");
            }
        }
    }

    /**
     * 校验并取得模式。
     */
    private static void requireSchema(Connection connection, String schema) throws SQLException {
        try (PreparedStatement statement = prepare(connection, "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA"
                + " WHERE BINARY SCHEMA_NAME = BINARY ? LIMIT 2", 2, schema);
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next() || !schema.equals(rows.getString(1)) || rows.next()) {
                throw invalid("The DBA-created dedicated schema is absent or not visible with this spelling");
            }
        }
    }

    /**
     * 检查当前操作涉及的表定义及元数据已完整提供。
     */
    private static void requireTables(Connection connection, String schema) throws SQLException {
        if (!inspectTable(connection, schema, MARKER_TABLE) || !inspectTable(connection, schema, HISTORY_TABLE)) {
            throw invalid("Source generation installation is incomplete");
        }
    }

    /** SHOW 解析会话内遮蔽；仅 INFORMATION_SCHEMA 仍可能描述永久表。 */
    private static boolean inspectTable(Connection connection, String schema, String table) throws SQLException {
        try (PreparedStatement statement = prepare(connection, "SHOW CREATE TABLE " + qualified(schema, table), 2);
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                throw invalid("Source generation table definition is unavailable");
            }
            String ddl = rows.getString(2);
            if (ddl == null || !ddl.stripLeading().toUpperCase(Locale.ROOT).startsWith("CREATE TABLE ")
                    || rows.next()) {
                throw invalid("Source generation requires a permanent table without temporary shadowing");
            }
            // 对只有 SELECT 权限的 MariaDB 主体，TABLE_CONSTRAINTS 连 PRIMARY/CHECK 记录也会隐藏。
            // SELECT 权限仍可执行 SHOW CREATE，并查看外键定义。
            if (ddl.toUpperCase(Locale.ROOT).matches("(?s).*\\bFOREIGN\\s+KEY\\b.*")
                    || ddl.toUpperCase(Locale.ROOT).contains("WITH SYSTEM VERSIONING")) {
                throw invalid("Source generation tables must not have foreign keys or system versioning");
            }
        } catch (SQLException ex) {
            if (ex.getErrorCode() == 1146 && "42S02".equals(ex.getSQLState())) {
                return false;
            }
            throw ex;
        }
        try (PreparedStatement statement = prepare(connection,
                "SELECT TABLE_TYPE, ENGINE, TABLE_COMMENT, CREATE_OPTIONS FROM information_schema.TABLES"
                        + TABLE_FILTER + " LIMIT 2", 2, schema, table); ResultSet rows = statement.executeQuery()) {
            if (!rows.next() || !"BASE TABLE".equals(rows.getString(1))
                    || !"InnoDB".equalsIgnoreCase(rows.getString(2))
                    || !(OWNER + table).equals(rows.getString(3))
                    || rows.getString(4) == null || !rows.getString(4).isBlank() || rows.next()) {
                throw invalid("Source generation object is not an owned ordinary InnoDB base table");
            }
        }
        verifyColumns(connection, schema, table);
        verifyConstraints(connection, schema, table);
        try (PreparedStatement statement = prepare(connection,
                "SELECT TRIGGER_NAME FROM information_schema.TRIGGERS WHERE BINARY EVENT_OBJECT_SCHEMA = BINARY ?"
                        + " AND BINARY EVENT_OBJECT_TABLE = BINARY ? LIMIT 1", 1, schema, table);
             ResultSet rows = statement.executeQuery()) {
            if (rows.next()) {
                throw invalid("Source generation tables must not have triggers");
            }
        }
        return true;
    }

    /**
     * 校验列集合。
     */
    private static void verifyColumns(Connection connection, String schema, String table) throws SQLException {
        List<Column> expected = MARKER_TABLE.equals(table) ? MARKER_COLUMNS : HISTORY_COLUMNS;
        List<Column> actual = new ArrayList<>();
        try (PreparedStatement statement = prepare(connection,
                "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLLATION_NAME, COLUMN_DEFAULT, EXTRA, IS_GENERATED"
                        + " FROM information_schema.COLUMNS" + TABLE_FILTER + " ORDER BY ORDINAL_POSITION LIMIT 9",
                9, schema, table); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String type = rows.getString(2);
                String defaultValue = rows.getString(5);
                if (type == null || defaultValue != null && !"NULL".equals(defaultValue)
                        || !"".equals(rows.getString(6)) || !"NEVER".equals(rows.getString(7))) {
                    throw invalid("Source generation column behavior is incompatible");
                }
                actual.add(new Column(rows.getString(1), type.replaceAll("(tinyint|smallint)\\([0-9]+\\)", "$1"),
                        "YES".equals(rows.getString(3)), rows.getString(4)));
            }
        }
        if (!expected.equals(actual)) {
            throw invalid("Source generation table columns are incompatible");
        }
    }

    /**
     * 校验约束集合。
     */
    private static void verifyConstraints(Connection connection, String schema, String table) throws SQLException {
        boolean marker = MARKER_TABLE.equals(table);
        Map<String, String> expectedChecks = marker ? MARKER_CHECKS : Map.of();
        // 这两个原生目录向只有 SELECT 权限的用户暴露 CHECK 定义及实际唯一索引。
        // 结果为空或不完整仍无法通过精确比较，不允许按权限差异绕过。
        Map<String, String> checks = new HashMap<>();
        try (PreparedStatement statement = prepare(connection,
                "SELECT CONSTRAINT_NAME, CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS"
                        + " WHERE BINARY CONSTRAINT_SCHEMA = BINARY ? AND BINARY TABLE_NAME = BINARY ? LIMIT 9",
                9, schema, table); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String clause = rows.getString(2);
                // 仅接受本组件 DDL 输出的精确简单表达式，绝不泛化 SQL 等价关系。
                if (clause == null) {
                    throw invalid("Source generation check expression is absent");
                }
                String normalized = clause.replace("`", "").replaceAll("\\s+", "");
                checks.put(rows.getString(1), normalized);
            }
        }
        if (!checks.equals(expectedChecks)) {
            throw invalid("Source generation check expressions are incompatible");
        }
        Map<String, String> keys = new HashMap<>();
        try (PreparedStatement statement = prepare(connection,
                "SELECT INDEX_NAME, COLUMN_NAME, NON_UNIQUE, SEQ_IN_INDEX, SUB_PART, INDEX_TYPE, IGNORED"
                        + " FROM information_schema.STATISTICS" + TABLE_FILTER + " LIMIT 9", 9, schema, table);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                if (rows.getInt(3) != 0 || rows.getInt(4) != 1 || rows.getString(5) != null
                        || !"BTREE".equals(rows.getString(6)) || !"NO".equals(rows.getString(7))
                        || keys.put(rows.getString(1), rows.getString(2)) != null) {
                    throw invalid("Source generation requires native full-column unique keys");
                }
            }
        }
        Map<String, String> expectedKeys = marker ? Map.of("PRIMARY", "singleton_id")
                : Map.of("PRIMARY", "generation", "sg_previous", "prev_generation");
        if (!keys.equals(expectedKeys)) {
            throw invalid("Source generation unique keys are incompatible");
        }
    }

    /**
     * 创建表。
     */
    private static void createTable(Connection connection, String schema, String table) throws SQLException {
        String columns = MARKER_TABLE.equals(table)
                ? "singleton_id TINYINT UNSIGNED NOT NULL, schema_version SMALLINT UNSIGNED NOT NULL,"
                + " instance_id CHAR(36) NOT NULL, generation CHAR(36) NOT NULL, state VARCHAR(6) NOT NULL,"
                + " PRIMARY KEY (singleton_id), CONSTRAINT sg_singleton CHECK (singleton_id = 1),"
                + " CONSTRAINT sg_version CHECK (schema_version = 1),"
                + " CONSTRAINT sg_state CHECK (state IN ('ACTIVE', 'FENCED'))"
                : "generation CHAR(36) NOT NULL, prev_generation CHAR(36) NULL, instance_id CHAR(36) NOT NULL,"
                + " PRIMARY KEY (generation), UNIQUE KEY sg_previous (prev_generation)";
        try (PreparedStatement statement = prepare(connection, "CREATE TABLE " + qualified(schema, table)
                + " (" + columns + ") ENGINE=InnoDB DEFAULT CHARSET=ascii COLLATE=ascii_bin COMMENT='"
                + OWNER + table + "'", 1)) {
            statement.executeUpdate();
        } catch (SQLException ex) {
            // 另一初始化调用可能率先完成 CREATE；只检查它，绝不替换或修改。
            if (ex.getErrorCode() != 1050 || !"42S01".equals(ex.getSQLState())) {
                throw ex;
            }
        }
        if (!inspectTable(connection, schema, table)) {
            throw invalid("Source generation table creation is incomplete");
        }
    }

    /**
     * 校验并取得标记。
     */
    private static Marker requireMarker(Connection connection, String schema, boolean lock) throws SQLException {
        Marker marker = readMarker(connection, schema, lock);
        if (marker == null) {
            throw invalid("Source generation marker is empty");
        }
        return marker;
    }

    /**
     * 读取当前资源的代次或状态标记。
     */
    private static Marker readMarker(Connection connection, String schema, boolean lock) throws SQLException {
        try (PreparedStatement statement = prepare(connection,
                "SELECT singleton_id, schema_version, instance_id, generation, state FROM "
                        + qualified(schema, MARKER_TABLE) + " LIMIT 2" + (lock ? " FOR UPDATE WAIT 5" : ""), 2);
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                return null;
            }
            if (rows.getInt(1) != 1 || rows.getInt(2) != 1) {
                throw invalid("Source generation singleton/version is invalid");
            }
            SourceGenerationPin pin = storedPin(schema, rows.getString(3), rows.getString(4));
            String state = rows.getString(5);
            if ((!ACTIVE.equals(state) && !FENCED.equals(state)) || rows.next()) {
                throw invalid("Source generation state/singleton is invalid");
            }
            return new Marker(pin, state);
        }
    }

    /**
     * 读取历史记录。
     */
    private static Map<String, History> readHistory(Connection connection, String schema) throws SQLException {
        Map<String, History> history = new HashMap<>();
        try (PreparedStatement statement = prepare(connection,
                "SELECT generation, prev_generation, instance_id FROM " + qualified(schema, HISTORY_TABLE)
                        + " LIMIT " + (MAX_HISTORY + 1), MAX_HISTORY + 1); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                History entry = historyRow(schema, rows);
                if (history.put(entry.generation(), entry) != null || history.size() > MAX_HISTORY) {
                    throw invalid("Source generation history is ambiguous or exceeds its audit bound");
                }
            }
        }
        return history;
    }

    /**
     * 根据当前输入构建并返回 {@code History}。
     */
    private static History historyRow(String schema, ResultSet rows) throws SQLException {
        String generation = rows.getString(1);
        String previous = rows.getString(2);
        String instance = rows.getString(3);
        storedPin(schema, instance, generation);
        if (previous != null) {
            storedPin(schema, instance, previous);
        }
        if (generation.equals(previous)) {
            throw invalid("Source generation history contains a self-reference");
        }
        return new History(generation, previous, instance);
    }

    /**
     * 校验历史记录。
     */
    private static void verifyHistory(SourceGenerationPin pin, Map<String, History> history) throws SQLException {
        Set<String> visited = new HashSet<>();
        String generation = pin.generation();
        while (generation != null) {
            History entry = history.get(generation);
            if (entry == null || !pin.instanceId().equals(entry.instance()) || !visited.add(generation)) {
                throw invalid("Source generation history is missing, cyclic or belongs to another instance");
            }
            generation = entry.previous();
        }
        if (visited.size() != history.size()) {
            throw invalid("Source generation history has orphaned or branched entries");
        }
    }

    /**
     * 根据当前输入构建并返回 {@code SourceGenerationPin}。
     */
    private static SourceGenerationPin storedPin(String schema, String instance,
            String generation) throws SQLException {
        try {
            return new SourceGenerationPin(schema, instance, generation);
        } catch (IllegalArgumentException ex) {
            throw invalid("Source generation contains an invalid identity");
        }
    }

    /**
     * 校验并取得固定代次。
     */
    private static void requirePin(Marker marker, SourceGenerationPin expected) throws SQLException {
        if (!expected.equals(marker.pin())) {
            throw invalid("Source generation pin is stale");
        }
    }

    /**
     * 插入历史记录。
     */
    private static void insertHistory(Connection connection, SourceGenerationPin pin,
            String previous) throws SQLException {
        updateExactlyOne(connection, "INSERT INTO " + qualified(pin.schema(), HISTORY_TABLE)
                        + " (generation, prev_generation, instance_id) VALUES (?, ?, ?)",
                pin.generation(), previous, pin.instanceId());
    }

    /**
     * 执行条件更新并要求恰好更新一条记录。
     */
    private static void updateExactlyOne(Connection connection, String sql, String... values) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, 1, values)) {
            if (statement.executeUpdate() != 1) {
                throw invalid("Source generation compare-and-set did not affect one row");
            }
        }
    }

    /**
     * 创建带查询超时和参数绑定的目录查询语句。
     */
    private static PreparedStatement prepare(Connection connection, String sql, int maxRows, String... values)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setQueryTimeout(TIMEOUT_SECONDS);
            statement.setMaxRows(maxRows);
            for (int i = 0; i < values.length; i++) {
                statement.setString(i + 1, values[i]);
            }
            return statement;
        } catch (SQLException | RuntimeException | Error ex) {
            try {
                statement.close();
            } catch (SQLException close) {
                ex.addSuppressed(close);
            }
            throw ex;
        }
    }

    /**
     * setAutoCommit(true) 会隐式提交可能仍在进行的失败事务。
     */
    private static <T> T inTransaction(Connection connection, SqlWork<T> work) throws SQLException {
        Throwable failure = null;
        boolean transactionStarted = false;
        boolean safeToRestore = false;
        try {
            connection.setAutoCommit(false);
            transactionStarted = true;
            T result = work.run();
            connection.commit();
            safeToRestore = true;
            return result;
        } catch (SQLException | RuntimeException | Error ex) {
            failure = ex;
            if (transactionStarted) {
                try {
                    connection.rollback();
                    safeToRestore = true;
                } catch (SQLException | RuntimeException | Error rollback) {
                    ex.addSuppressed(rollback);
                    // setAutoCommit(true) 会隐式提交可能仍在进行的失败事务。
                    // 保留此连接不可用于普通工作的状态；其所有者必须丢弃它。
                }
            }
            throw ex;
        } finally {
            if (safeToRestore) {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException | RuntimeException | Error restore) {
                    if (failure != null) {
                        failure.addSuppressed(restore);
                    } else {
                        throw restore;
                    }
                }
            }
        }
    }

    /**
     * 返回当前数据库对象经过引用处理的限定名称。
     */
    private static String qualified(String schema, String table) {
        // 仅经过校验的固定记录模式与私有固定表名可传入此方法。
        return "`" + schema + "`.`" + table + "`";
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLException invalid(String message) {
        return new SQLException(message, "55000");
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String message) {
        return new SQLFeatureNotSupportedException(message, "0A000");
    }

    @FunctionalInterface
    private interface SqlWork<T> {

        /**
         * 在当前代次管理连接上调用数据库工作回调。
         */
        T run() throws SQLException;
    }

    private record Column(String name, String type, boolean nullable, String collation) {

    }

    private record Marker(SourceGenerationPin pin, String state) {

    }

    private record History(String generation, String previous, String instance) {

    }
}
