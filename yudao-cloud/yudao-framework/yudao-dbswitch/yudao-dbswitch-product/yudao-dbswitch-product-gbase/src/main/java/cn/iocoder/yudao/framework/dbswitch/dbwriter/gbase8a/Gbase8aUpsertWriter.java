package cn.iocoder.yudao.framework.dbswitch.dbwriter.gbase8a;

import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;
import cn.iocoder.yudao.framework.dbswitch.core.util.Gbase8aCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.util.Gbase8aSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.*;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * GBase 8.6 EXPRESS 不强制唯一键。先校验连接本地的源，再在
 * 目标表排他锁下执行单次原生 MERGE。不回退到逐行目标操作。
 * 临时暂存自动提交；只有目标 MERGE 进入共享事务执行器。
 */
public final class Gbase8aUpsertWriter extends AbstractNativeUpsertWriter {

    private Gbase8aCursorMetadata.Shape shape;

    private CursorKeyContract contract;

    private SourceGenerationPin targetGeneration;

    /**
     * 初始化 {@code Gbase8aUpsertWriter}及其父类状态。
     */
    public Gbase8aUpsertWriter(DataSource source) {
        super(source);
    }

    /**
     * 为游标写入路径配置目标关系代次信息。
     */
    @Override
    public void configureCursorTargetGeneration(SourceGenerationPin pin) {
        if (pin == null) {
            throw new IllegalArgumentException("GBase cursor target requires a managed deployment generation");
        }
        if (fields != null) {
            throw new IllegalStateException("Target generation must be configured before preparation");
        }
        Gbase8aSourceGeneration.references(pin); // 同时拒绝旧外部 v8 源 pin 被当成受管目标 pin。
        targetGeneration = pin;
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "GBase";
    }

    /**
     * 按当前数据库方言转义并引用标识符。
     */
    @Override
    protected String quote(String name) {
        return Gbase8aCursorMetadata.quote(name);
    }

    /**
     * 按目标方言生成按键更新或插入的 SQL。
     */
    @Override
    protected String buildUpsertSql() {
        return "";
    }

    /**
     * 核对连接实际产品与当前实现的产品要求。
     */
    @Override
    protected void verifyProduct(Connection c) throws SQLException {
        Gbase8aCursorMetadata.verifyProduct(c, runtimeOptions.queryTimeoutSeconds());
        if (!"8.6.2.43-R7-free.110605".equals(c.getMetaData().getDatabaseProductVersion())) {
            throw new SQLException("GBase MERGE requires its verified native server profile");
        }
    }

    /**
     * 补充当前产品特有的模式身份信息。
     */
    @Override
    protected String additionalSchemaIdentity(Connection c) throws SQLException {
        if (targetGeneration != null) {
            if (!schemaName.equals(targetGeneration.schema()) || Gbase8aSourceGeneration.references(targetGeneration)
                    .contains(target())) {
                throw new SQLException("GBase target generation does not protect the target data relation");
            }
            Gbase8aSourceGeneration.verifyCurrent(c, targetGeneration, runtimeOptions.queryTimeoutSeconds());
        }
        // SHOW CREATE TABLE 在原厂 LOCK TABLES 内被拒绝；直接读取可在锁内重验的目录事实。
        // TABLE_ID 识别 DROP/CREATE，不将它或随 DML 变化的 SCN 冒充 TRUNCATE 世代。
        String identity;
        var columns = Gbase8aCursorMetadata.columns(c, schemaName, tableName, runtimeOptions.queryTimeoutSeconds());
        try (var p = c.prepareStatement(
                "SELECT ENGINE,TABLE_TYPE,TABLE_COMMENT,CREATE_TIME,TABLE_ID,TABLE_COLLATION,OWNER_UID "
                        + "FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
            p.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
            p.setString(1, schemaName);
            p.setString(2, tableName);
            try (var r = p.executeQuery()) {
                if (!r.next() || !"EXPRESS".equalsIgnoreCase(r.getString(1)) || !"BASE TABLE".equals(r.getString(2))) {
                    throw new SQLException("GBase MERGE requires one EXPRESS base table");
                }
                String tableId = r.getString(5);
                if (tableId == null || tableId.isBlank()) {
                    throw new SQLException("GBase physical table identity is unavailable");
                }
                identity = "GBASE8A_MERGE_V1\0" + tableId + '\0' + r.getString(6) + '\0' + r.getString(7);
                shape = new Gbase8aCursorMetadata.Shape(schemaName, tableName, r.getString(1), r.getString(2),
                        r.getString(3), r.getString(4), identity, c.getMetaData().getDatabaseProductVersion(), tableId,
                        columns);
                if (r.next()) {
                    throw new SQLException("GBase physical table identity is ambiguous");
                }
            }
        }
        // 原厂 CREATE_TIME 在普通写入/表锁路径上也会变化，不能作为结构或恢复代际。
        return identity + '\0' + shape.version() + '\0' + shape.engine() + '\0' + shape.tableType()
                + '\0' + shape.tableComment() + '\0' + columns.values().toString()
                + '\0' + (targetGeneration == null ? "standalone" : targetGeneration.fingerprint());
    }

    /**
     * 检查指定键列是否满足当前写入路径要求的唯一键条件。
     */
    @Override
    protected void verifyUniqueKey(Connection c) throws SQLException {
        if (uniqueKeys.size() > 32 || fields.size() > 1024 || !shape.columns().keySet().equals(new HashSet<>(fields))) {
            throw new SQLException("GBase MERGE requires a complete scalar projection and a bounded key");
        }
        for (String field : fields) {
            var column = shape.column(field);
            nativeType(column);
            if (column.extra() != null && !column.extra().isBlank()) {
                throw new SQLException("Generated GBase columns cannot be replayed");
            }
        }
        for (String key : uniqueKeys) {
            if (shape.column(key).nullable() || !Gbase8aCursorMetadata.supportedKeyType(shape.column(key))) {
                throw new SQLException("GBase MERGE keys must have verified non-null domains");
            }
        }
        try (var p = c.prepareStatement(
                "SELECT 1 FROM information_schema.TRIGGERS WHERE EVENT_OBJECT_SCHEMA=? AND EVENT_OBJECT_TABLE=? LIMIT 1")) {
            p.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
            p.setString(1, schemaName);
            p.setString(2, tableName);
            try (var r = p.executeQuery()) {
                if (r.next()) {
                    throw new SQLException("GBase MERGE does not replay trigger side effects");
                }
            }
        }
        // 在获取排他锁后重复检查：绝不将目录 PRIMARY KEY 语法视为证明。
        Gbase8aCursorMetadata.requireUniqueKeys(c, target(), uniqueKeys, runtimeOptions.queryTimeoutSeconds());
        contract = shape.sourceContract(uniqueKeys);
    }

    /**
     * 返回游标读写使用的结构化键契约。
     */
    @Override
    public CursorKeyContract keyContract() {
        targetSchemaFingerprint();
        return contract;
    }

    /**
     * 精确有序键的类型与排序规则契约，是无损类型化游标重放的必要条件。
     */
    @Override
    public String keyContractFingerprint() {
        return keyContract().nativeFingerprint();
    }

    /**
     * 写入已完成预处理的数据行。
     */
    @Override
    protected BatchWriteResult writePreparedRows(List<Object[]> rows) {
        var exact = new ArrayList<Object[]>(rows.size());
        try {
            for (Object[] row : rows) {
                var values = new Object[fields.size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = exactValue(row[i], shape.column(fields.get(i)));
                }
                exact.add(values);
            }
            batchLimits.requireStatementParameters(fields.size() + 1);
        } catch (RuntimeException failure) {
            throw new BatchRejectedException("GBase MERGE input is outside the exact native scalar domain");
        }
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String temporary = "dbswitch_merge_" + suffix;
        String ordinal = "dbswitch_row_" + suffix;
        String stage = quote(schemaName) + "." + quote(temporary);
        Connection connection = null;
        boolean created = false, locked = false, transactionEntered = false, ownsSession = false;
        var targetAttempted = new java.util.concurrent.atomic.AtomicBoolean();
        var schemaChanged = new java.util.concurrent.atomic.AtomicBoolean();
        String phase = "target metadata";
        try {
            connection = dataSource.getConnection();
            if (!connection.getAutoCommit()) {
                throw new SQLException("Caller transaction is not supported");
            }
            ownsSession = true;
            inspect(connection, false);
            phase = "temporary source creation";
            var definitions = new ArrayList<String>();
            for (String field : fields) {
                definitions.add(quote(field) + " " + nativeType(shape.column(field)));
            }
            definitions.add(quote(ordinal) + " INTEGER NOT NULL");
            execute(connection, "CREATE TEMPORARY TABLE " + stage + "(" + String.join(",", definitions) + ")");
            created = true;
            phase = "temporary source binding";
            String values = String.join(",", Collections.nCopies(fields.size() + 1, "?"));
            try (var p = connection.prepareStatement("INSERT INTO " + stage + " VALUES(" + values + ")")) {
                p.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
                for (int row = 0; row < exact.size(); row++) {
                    for (int i = 0; i < fields.size(); i++) {
                        Object value = exact.get(row)[i];
                        if (value == null) {
                            p.setNull(i + 1, Types.NULL);
                        } else {
                            Gbase8aCursorMetadata.bind(p, i + 1, shape.column(fields.get(i)), value);
                        }
                    }
                    p.setInt(fields.size() + 1, row);
                    p.addBatch();
                }
                int[] results = p.executeBatch();
                if (results.length != exact.size()) {
                    throw new SQLException("Temporary batch acknowledgement is incomplete");
                }
                for (int result : results) {
                    if (result < 0 && result != Statement.SUCCESS_NO_INFO) {
                        throw new SQLException("Temporary batch was not acknowledged");
                    }
                }
            }
            // 观测实际原生值；GBase 可能静默缩窄整数或字符值。
            phase = "temporary value verification";
            verifyStage(connection, stage, ordinal, exact);
            phase = "temporary key verification";
            Gbase8aCursorMetadata.requireUniqueKeys(connection, stage, uniqueKeys,
                    runtimeOptions.queryTimeoutSeconds());
            phase = "target lock";
            execute(connection, "LOCK TABLES " + target() + " WRITE," + stage + " READ"
                    + (targetGeneration == null ? "" : "," + Gbase8aSourceGeneration.markerReadLocks(
                    targetGeneration)));
            locked = true;
            Connection owned = connection;
            // 此公开 Spring 适配器仅抑制内部逻辑关闭。产品持有
            // 外层连接和临时资源；提交仅由现有执行器负责。
            var transactionSource = new SingleConnectionDataSource(owned, true);
            transactionEntered = true;
            phase = "locked target preflight";
            JdbcBatchExecutor.execute(transactionSource, context -> {
                try {
                    inspect(context.connection(), true);
                } catch (SQLException rejected) {
                    schemaChanged.set("UPSERT target schema changed".equals(rejected.getMessage()));
                    throw rejected;
                }
                try (var statement = context.connection().createStatement()) {
                    statement.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
                    targetAttempted.set(true);
                    context.markWriteAttempted();
                    statement.executeUpdate(merge(stage, temporary));
                }
                return null;
            });
            execute(connection, "UNLOCK TABLES");
            locked = false;
            execute(connection, "DROP TEMPORARY TABLE " + stage);
            created = false;
            connection.close();
            connection = null;
            return new BatchWriteResult(rows.size(), null, CommitAcknowledgement.JDBC_COMMIT_RETURNED);
        } catch (Throwable failure) {
            if (failure instanceof Error fatal) {
                if (targetAttempted.get()) {
                    fields = null;
                    fatal.addSuppressed(new UnconfirmedWriteException());
                }
                throw fatal;
            }
            if (failure instanceof UnconfirmedWriteException || targetAttempted.get()) {
                fields = null;
                throw new UnconfirmedWriteException();
            }
            throw new BatchRejectedException("GBase MERGE rejected before modifying the target during " + phase
                    + (schemaChanged.get() ? " (target fingerprint changed)" : ""));
        } finally {
            if (connection != null) {
                // 目标事务结果未确定后，绝不执行清理 SQL。abort/evict 改为终止
                // 连接本地表和锁；不重试，也不恢复自动提交。
                try {
                    if (ownsSession && !transactionEntered && connection.getAutoCommit()) {
                        if (locked) {
                            execute(connection, "UNLOCK TABLES");
                        }
                        if (created) {
                            execute(connection, "DROP TEMPORARY TABLE " + stage);
                        }
                    }
                } catch (Throwable ignored) {
                }
                if (ownsSession) {
                    try {
                        connection.abort(Runnable::run);
                    } catch (Throwable ignored) {
                    }
                    if (dataSource instanceof HikariDataSource pool) {
                        try {
                            pool.evictConnection(connection);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                try {
                    connection.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 根据唯一键生成并执行目标表与暂存表的合并操作。
     */
    private String merge(String stage, String temporary) {
        String targetName = quote(tableName), sourceName = quote(temporary);
        String on = String.join(" AND ",
                uniqueKeys.stream().map(key -> targetName + "." + quote(key) + "=" + sourceName + "." + quote(key))
                        .toList());
        String updates = updateFields().isEmpty() ? "" : " WHEN MATCHED THEN UPDATE SET "
                + String.join(",", updateFields().stream()
                .map(field -> targetName + "." + quote(field) + "=" + sourceName + "." + quote(field)).toList());
        return "MERGE INTO " + target() + " USING " + stage + " ON " + on + updates
                + " WHEN NOT MATCHED THEN INSERT(" + quoted(fields) + ") VALUES("
                + String.join(",", fields.stream().map(field -> sourceName + "." + quote(field)).toList()) + ")";
    }

    /**
     * 校验阶段。
     */
    private void verifyStage(Connection c, String stage, String ordinal, List<Object[]> expected) throws SQLException {
        try (var s = c.createStatement()) {
            s.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
            try (var result = s.executeQuery(
                    "SELECT " + quoted(fields) + " FROM " + stage + " ORDER BY " + quote(ordinal))) {
                for (Object[] row : expected) {
                    if (!result.next()) {
                        throw new SQLException("Temporary row count differs");
                    }
                    for (int i = 0; i < fields.size(); i++) {
                        var column = shape.column(fields.get(i));
                        Object observed = exactValue(Gbase8aCursorMetadata.readValue(result, i + 1, column), column);
                        if (!Objects.equals(row[i], observed)) {
                            throw new SQLException("Temporary value did not round-trip exactly");
                        }
                    }
                }
                if (result.next()) {
                    throw new SQLException("Temporary row count differs");
                }
            }
        }
    }

    /**
     * 在当前数据库连接上执行给定 SQL 语句。
     */
    private void execute(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement()) {
            s.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
            s.execute(sql);
        }
    }

    /**
     * 根据原生列信息确定数据库类型声明。
     */
    private static String nativeType(Gbase8aCursorMetadata.Column column) throws SQLException {
        String type = column.dataType().toLowerCase(Locale.ROOT);
        if ((!Gbase8aCursorMetadata.supportedKeyType(column) && !"time".equals(type))
                || !column.nativeType().matches("[a-zA-Z]+(?:\\([0-9]+(?:,[0-9]+)?\\))?(?: unsigned)?")) {
            throw new SQLException("GBase MERGE scalar encoding is not verified");
        }
        String nativeType = column.nativeType();
        if ("varchar".equals(type)) {
            if (column.charset() == null || !Set.of("utf8", "utf8mb4").contains(column.charset())
                    || column.collation() == null || !column.collation().matches("[a-zA-Z0-9_]+")) {
                throw new SQLException("GBase character encoding is not verified");
            }
            nativeType += " CHARACTER SET " + column.charset() + " COLLATE " + column.collation();
        }
        return nativeType;
    }

    /**
     * 按当前产品的精确值域读取或转换字段值。
     */
    private static Object exactValue(Object value, Gbase8aCursorMetadata.Column column) {
        if (value == null) {
            if (!column.nullable()) {
                throw new IllegalArgumentException("Non-null column requires a value");
            }
            return null;
        }
        String type = column.dataType().toLowerCase(Locale.ROOT);
        if (Set.of("tinyint", "smallint", "mediumint", "int", "bigint", "decimal").contains(type)) {
            if (value instanceof Float || value instanceof Double) {
                throw new IllegalArgumentException("Approximate value cannot be an exact number");
            }
            var domain = Gbase8aCursorMetadata.domain(column).numeric();
            if (domain == null) {
                throw new IllegalArgumentException("Numeric domain is unavailable");
            }
            BigDecimal decimal = JdbcValueConversions.toDecimal(value)
                    .setScale(domain.scale(), RoundingMode.UNNECESSARY);
            if (decimal.compareTo(domain.minimum()) < 0 || decimal.compareTo(domain.maximum()) > 0) {
                throw new IllegalArgumentException("Numeric value exceeds its native domain");
            }
            return decimal;
        }
        if ("varchar".equals(type)) {
            String text = JdbcValueConversions.toText(value);
            try {
                StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                        .encode(CharBuffer.wrap(text));
            } catch (java.nio.charset.CharacterCodingException invalid) {
                throw new IllegalArgumentException("Text is not valid Unicode");
            }
            return text;
        }
        if ("date".equals(type)) {
            LocalDate date = JdbcValueConversions.toDate(value);
            if (date.getYear() < 1 || date.getYear() > 9999) {
                throw new IllegalArgumentException("Date exceeds native range");
            }
            return date;
        }
        if ("datetime".equals(type)) {
            LocalDateTime time = JdbcValueConversions.toDateTime(value);
            if (time.getYear() < 1 || time.getYear() > 9999 || time.getNano() % 1000 != 0) {
                throw new IllegalArgumentException("Timestamp exceeds native range or precision");
            }
            return time;
        }
        if ("time".equals(type)) {
            LocalTime time = JdbcValueConversions.toTime(value);
            if (time.getNano() != 0) {
                throw new IllegalArgumentException("TIME subsecond binding is not verified");
            }
            return time;
        }
        throw new IllegalArgumentException("Native scalar is not verified");
    }
}
