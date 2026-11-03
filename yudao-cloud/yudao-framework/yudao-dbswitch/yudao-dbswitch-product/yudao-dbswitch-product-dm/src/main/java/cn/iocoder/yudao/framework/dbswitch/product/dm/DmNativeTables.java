package cn.iocoder.yudao.framework.dbswitch.product.dm;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.util.DmDriverCompatibility;
import cn.iocoder.yudao.framework.dbswitch.core.util.DmRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.core.util.DmSqlNames;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.NonTransactionalWriteExecution;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.*;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 同产品的普通 DM8 行表；SQL 根据有数量上限的强类型目录凭据生成，绝不使用 GET_DDL 文本。 */
public final class DmNativeTables {

    private static final String FORMAT = "DM8_NATIVE_ROW_V3";

    private static final int MAX_COLUMNS = 1024, MAX_DOCUMENT = 1_048_576;

    /** 仅描述定义：IDENT_CURRENT 无法区分从未使用的种子与已发放的种子。 */
    private record Generator(long seed, long increment) {

        /**
         * 初始化 {@code Generator}。
         */
        Generator {
            if (increment == 0 || seed == Long.MAX_VALUE) {
                throw invalid();
            }
        }

        /**
         * 根据当前DM方言生成 SQL 语句或片段。
         */
        String sql() {
            return " IDENTITY(" + seed + "," + increment + ")";
        }
    }

    private record Column(String name, String kind, Integer precision, Integer scale, int length, String semantics,
                          boolean nullable, String defaultValue, boolean defaultOnNull, String comment,
                          Generator generator) {

        /**
         * 初始化 {@code Column}。
         */
        Column {
            DmNativeIndexes.name(name);
            type(kind, precision, scale, length, semantics);
            defaultValue = defaultSql(kind, defaultValue);
            bounded(comment);
            if (defaultOnNull && (defaultValue == null || nullable)) {
                throw invalid();
            }
            if (generator != null) {
                if (nullable || defaultValue != null || defaultOnNull || !Set.of("INT", "INTEGER", "BIGINT", "DECIMAL",
                        "NUMERIC").contains(kind)
                        || Set.of("DECIMAL", "NUMERIC").contains(kind) && !Integer.valueOf(0).equals(scale)) {
                    throw invalid();
                }
                var limit = kind.equals("INT") || kind.equals("INTEGER") ? java.math.BigInteger.ONE.shiftLeft(
                        31) : kind.equals("BIGINT") ? java.math.BigInteger.ONE.shiftLeft(
                        63) : java.math.BigInteger.TEN.pow(precision);
                var seed = java.math.BigInteger.valueOf(generator.seed());
                var minimum = Set.of("DECIMAL", "NUMERIC").contains(kind) ? limit.negate()
                        .add(java.math.BigInteger.ONE) : limit.negate();
                if (seed.compareTo(minimum) < 0 || seed.compareTo(limit) >= 0) {
                    throw invalid();
                }
            }
        }

        /**
         * 根据当前DM方言生成 SQL 语句或片段。
         */
        String sql() {
            return DmSqlNames.quote(name) + " " + type(kind, precision, scale, length,
                    semantics) + (generator == null ? "" : generator.sql()) + (defaultValue == null ? "" : " DEFAULT " + (defaultOnNull ? "ON NULL " : "") + defaultValue) + (nullable ? "" : " NOT NULL");
        }

        /**
         * 将当前对象或数据行按既定映射转换为目标形式。
         */
        Column mapped(String mapped) {
            return new Column(mapped, kind, precision, scale, length, semantics, nullable, defaultValue, defaultOnNull,
                    comment, generator);
        }

        /**
         * 读取或构建当前关系及字段的结构信息。
         */
        List<Object> shape() {
            return Arrays.asList(name, type(kind, precision, scale, length, semantics), nullable, defaultValue,
                    defaultOnNull, comment, generator);
        }
    }

    private record Definition(String identity, String characterProfile, List<Column> columns, String comment,
                              DmNativeIndexes.Catalog indexes, DmNativeChecks.Catalog checks) {

        /**
         * 校验构造参数并初始化 {@code Definition}。
         */
        Definition {
            columns = List.copyOf(columns);
            Objects.requireNonNull(indexes);
            Objects.requireNonNull(checks);
            bounded(comment);
            if (identity == null || !identity.matches(
                    "[0-9a-f]{64}") || characterProfile == null || !characterProfile.matches(
                    "[0-9]{1,2}:[01]") || columns.isEmpty() || columns.size() > MAX_COLUMNS
                    || columns.stream().map(Column::name).distinct().count() != columns.size() || columns.stream()
                    .filter(c -> c.generator() != null).count() > 1) {
                throw invalid();
            }
            Set<String> fields = new HashSet<>(columns.stream().map(Column::name).toList());
            if (indexes.entries().stream().flatMap(i -> i.keys().stream()).anyMatch(k -> !fields.contains(k.column()))
                    || columns.stream().anyMatch(c -> c.nullable() && indexes.keys().contains(c.name()))
                    || checks.entries().stream().anyMatch(e -> !fields.containsAll(e.columns()))) {
                throw invalid();
            }
        }

        /**
         * 将当前对象或数据行按既定映射转换为目标形式。
         */
        Definition mapped(Map<String, String> mapping) {
            if (columns.stream().anyMatch(c -> c.generator() != null && !mapping.containsKey(c.name()))) {
                throw invalid();
            }
            if (mapping.isEmpty() || !columns.stream().map(Column::name).toList().containsAll(mapping.keySet())) {
                throw invalid();
            }
            return new Definition(identity, characterProfile,
                    columns.stream().filter(c -> mapping.containsKey(c.name()))
                            .map(c -> c.mapped(mapping.get(c.name()))).toList(), comment, indexes.mapped(mapping),
                    checks.mapped(mapping));
        }

        /**
         * 读取或构建当前关系及字段的结构信息。
         */
        List<Object> shape() {
            return Arrays.asList(characterProfile, columns.stream().map(Column::shape).toList(), comment,
                    indexes.shape(), checks.shape());
        }
    }

    /**
     * 初始化 {@code DmNativeTables}。
     */
    private DmNativeTables() {
    }

    /** 原生 DDL、游标读取及重放安全写入共享的有数量上限的目录凭据。 */
    public record ReadColumn(String name, String kind, Integer precision, Integer scale, int length, String semantics,
                             boolean nullable) {

    }

    public record ReadShape(String identity, String fingerprint, List<ReadColumn> columns, List<String> primaryKeys) {

        /**
         * 初始化 {@code ReadShape}。
         */
        public ReadShape {
            columns = List.copyOf(columns);
            primaryKeys = List.copyOf(primaryKeys);
        }
    }

    /**
     * 读取重放结构。
     */
    public static ReadShape readReplayShape(Connection connection, String schema, String table,
            int timeout) throws SQLException {
        Definition value = read(connection, schema, table, timeout);
        if (value == null || !value.characterProfile().startsWith("1:") || value.columns().stream()
                .anyMatch(c -> c.generator() != null || c.defaultOnNull())) {
            throw new SQLException("DM replay requires an ordinary table without generated or default-on-null values");
        }
        return new ReadShape(value.identity(), DigestUtil.sha256Hex(JSONUtil.toJsonStr(value.shape())),
                value.columns().stream()
                        .map(c -> new ReadColumn(c.name(), c.kind(), c.precision(), c.scale(), c.length(),
                                c.semantics(), c.nullable())).toList(), value.indexes().keys());
    }

    /**
     * 根据当前输入构建对象或结构的描述信息。
     */
    public static NativeTableDefinition describe(DataSource source, String schema, String table, int timeout) {
        try (var c = source.getConnection()) {
            Definition first = read(c, schema, table, timeout), second = read(c, schema, table, timeout);
            if (first == null || !first.equals(second)) {
                throw new SQLException("DM source changed during native capture");
            }
            return new NativeTableDefinition(ProductTypeEnum.DM, FORMAT, encode(first),
                    first.columns().stream().map(Column::name).toList());
        } catch (Exception failure) {
            throw new IllegalStateException("DM native source structure could not be captured");
        }
    }

    /**
     * 返回计划。
     */
    public static NativeTargetPlan plan(DataSource target, NativeTableDefinition evidence, String schema, String table,
            Map<String, String> mapping, boolean replace, int timeout) {
        DmNativeIndexes.name(schema);
        DmNativeIndexes.name(table);
        if (evidence.product() != ProductTypeEnum.DM || !FORMAT.equals(evidence.format())) {
            throw invalid();
        }
        Definition source = decode(evidence.document()), desired = source.mapped(Map.copyOf(mapping)), observed;
        if (!evidence.fields().equals(source.columns().stream().map(Column::name).toList())) {
            throw invalid();
        }
        try (var c = target.getConnection()) {
            if (!source.characterProfile().equals(characterProfile(c, timeout))) {
                throw new SQLException("DM character comparison profile differs");
            }
            observed = read(c, schema, table, timeout);
            if (observed != null && source.identity().equals(observed.identity())) {
                throw new SQLException("DM target is its physical source");
            }
            if (observed != null && !replace && !observed.shape().equals(desired.shape())) {
                throw new SQLException("DM target structure differs from source");
            }
            if (observed != null && replace) {
                requireNoExternalDependents(c, schema, table, timeout);
            }
            if (observed == null || replace) {
                DmNativeIndexes.assertNamesAvailable(c, desired.indexes(), schema, table, observed != null, timeout);
                DmNativeChecks.assertNamesAvailable(c, desired.checks(), schema, table, observed != null, timeout);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("DM native target preflight failed");
        }
        String create = createSql(schema, table, desired), digest = DigestUtil.sha256Hex(
                evidence.document() + '\0' + create + '\0' + replace);
        ddlBudget(create);
        DmNativeIndexes.statements(desired.indexes(), schema, table).forEach(DmNativeTables::ddlBudget);
        if (desired.comment() != null) {
            ddlBudget("COMMENT ON TABLE " + DmSqlNames.relation(schema, table) + " IS " + literal(desired.comment()));
        }
        for (var column : desired.columns()) {
            if (column.comment() != null) {
                ddlBudget("COMMENT ON COLUMN " + DmSqlNames.relation(schema, table) + '.' + DmSqlNames.quote(
                        column.name()) + " IS " + literal(column.comment()));
            }
        }
        return new NativeTargetPlan() {
            private final AtomicBoolean used = new AtomicBoolean();

            private volatile String identity = observed == null ? null : observed.identity();

            /**
             * 返回摘要。
             */
            @Override
            public String digest() {
                return digest;
            }

            /**
             * 在数据搬运前检查源端与目标端的兼容条件。
             */
            @Override
            public void preflightDataTransfer() {
                if (source.columns().stream().anyMatch(c -> c.generator() != null)) {
                    throw new IllegalStateException(
                            "DM identity data transfer requires a verified exact generator-position protocol");
                }
            }

            /**
             * 说明当前目标计划是否会替换已有对象。
             */
            @Override
            public boolean replacesExisting() {
                return replace && observed != null;
            }

            /**
             * 说明当前 DDL 路径是否要求调用方独占目标结构变更。
             */
            @Override
            public boolean requiresExclusiveDdlOwnership() {
                return replacesExisting();
            }

            /**
             * 返回当前操作恢复时需要关联的资源信息。
             */
            @Override
            public List<NativeRecoveryResource> recoveryResources() {
                return List.of(new NativeRecoveryResource(ProductTypeEnum.DM, schema, table,
                        NativeRecoveryResource.Role.TARGET, identity));
            }

            /**
             * 在当前数据库连接上执行给定 SQL 语句。
             */
            @Override
            public void execute() {
                if (!used.compareAndSet(false, true)) {
                    throw new IllegalStateException("DM native plan is single use");
                }
                NonTransactionalWriteExecution.execute(target, (c, attempt) -> {
                    if (!c.getAutoCommit()) {
                        throw new SQLException("DM native DDL cannot take over a caller transaction");
                    }
                    if (!source.characterProfile().equals(characterProfile(c, timeout))) {
                        throw new SQLException("DM character comparison profile changed");
                    }
                    Definition current = read(c, schema, table, timeout);
                    if (!Objects.equals(observed, current)) {
                        throw new SQLException("DM target changed after preflight");
                    }
                    if (current == null || replace) {
                        DmNativeIndexes.assertNamesAvailable(c, desired.indexes(), schema, table, current != null,
                                timeout);
                        DmNativeChecks.assertNamesAvailable(c, desired.checks(), schema, table, current != null,
                                timeout);
                    }
                    if (current != null && replace) {
                        requireNoExternalDependents(c, schema, table, timeout);
                        executeDdl(c, attempt, "DROP TABLE " + DmSqlNames.relation(schema, table), timeout);
                        identity = null;
                    }
                    if (current == null || replace) {
                        executeDdl(c, attempt, create, timeout);
                        identity = DmRelationIdentity.physicalFingerprint(c, schema, table, timeout);
                        for (String sql : DmNativeIndexes.statements(desired.indexes(), schema, table)) {
                            executeDdl(c, attempt, sql, timeout);
                            identity = DmRelationIdentity.physicalFingerprint(c, schema, table, timeout);
                        }
                        if (desired.comment() != null) {
                            executeDdl(c, attempt,
                                    "COMMENT ON TABLE " + DmSqlNames.relation(schema, table) + " IS " + literal(
                                            desired.comment()), timeout);
                        }
                        for (var column : desired.columns()) {
                            if (column.comment() != null) {
                                executeDdl(c, attempt, "COMMENT ON COLUMN " + DmSqlNames.relation(schema,
                                        table) + '.' + DmSqlNames.quote(column.name()) + " IS " + literal(
                                        column.comment()), timeout);
                            }
                        }
                        var created = read(c, schema, table, timeout);
                        if (created == null || !created.shape().equals(desired.shape())) {
                            throw new SQLException("DM native structure readback differs from the plan");
                        }
                        identity = created.identity();
                    }
                    return 0;
                });
            }
        };
    }

    /**
     * 读取DM原生目录并构建表结构元数据。
     */
    private static Definition read(Connection c, String schema, String table, int timeout) throws SQLException {
        DmDriverCompatibility.require(c);
        DmSqlNames.relation(schema, table);
        String comment;
        try (var p = DmNativeIndexes.prepare(c,
                "SELECT t.temporary,t.partitioned,t.iot_type,t.cluster_name,t.duration,m.comments FROM ALL_TABLES t "
                        + "LEFT JOIN ALL_TAB_COMMENTS m ON m.owner=t.owner AND m.table_name=t.table_name WHERE t.owner=? AND t.table_name=?",
                timeout, schema, table); var r = p.executeQuery()) {
            if (!r.next()) {
                // 对象不存在不能将已被非表对象占用的命名空间转为 CREATE 计划。
                try (var occupied = DmNativeIndexes.prepare(c,
                        "SELECT 1 FROM SYS.SYSOBJECTS o JOIN SYS.SYSOBJECTS s ON s.id=o.schid WHERE s.name=? AND o.name=?",
                        timeout, schema, table); var rr = occupied.executeQuery()) {
                    if (rr.next()) {
                        throw unsupported();
                    }
                }
                return null;
            }
            if (!"N".equals(r.getString(1)) || !"NO".equals(r.getString(2)) || !"IOT".equals(
                    r.getString(3)) || r.getString(4) != null || r.getString(5) != null) {
                throw unsupported();
            }
            comment = r.getString(6);
            if (r.next()) {
                throw unsupported();
            }
        }
        String before = DmRelationIdentity.physicalFingerprint(c, schema, table, timeout);
        try (var p = DmNativeIndexes.prepare(c, "SELECT 1 FROM ALL_TRIGGERS WHERE table_owner=? AND table_name=?",
                timeout, schema, table); var r = p.executeQuery()) {
            if (r.next()) {
                throw unsupported();
            }
        }
        // DM 通过 DBMS_RLS 安装策略目录；Oracle 同名视图不存在，不代表没有策略。
        try (var p = DmNativeIndexes.prepare(c, "SELECT SF_CHECK_RLS_SYS() FROM DUAL",
                timeout); var r = p.executeQuery()) {
            if (!r.next() || r.getInt(1) != 0 || r.wasNull() || r.next()) {
                throw new SQLException("DM installed RLS requires a verified policy dependency plan");
            }
        }
        List<Column> columns = new ArrayList<>();
        String query = "SELECT c.column_name,c.data_type,c.data_type_owner,c.data_precision,c.data_scale,c.data_length,c.char_length,c.char_used,c.nullable,c.hidden_column,c.virtual_column,"
                + "sc.info2,sc.info1,m.comments,c.data_default,COALESCE(ci.info1,0),o.info6 FROM ALL_TAB_COLS c JOIN SYS.SYSOBJECTS o ON o.name=c.table_name AND o.type$='SCHOBJ' AND o.subtype$='UTAB' "
                + "JOIN SYS.SYSOBJECTS s ON s.id=o.schid AND s.name=c.owner JOIN SYS.SYSCOLUMNS sc ON sc.id=o.id AND sc.name=c.column_name "
                + "LEFT JOIN SYS.SYSCOLINFOS ci ON ci.id=sc.id AND ci.colid=sc.colid "
                + "LEFT JOIN ALL_COL_COMMENTS m ON m.schema_name=c.owner AND m.table_name=c.table_name AND m.column_name=c.column_name "
                + "WHERE c.owner=? AND c.table_name=? ORDER BY c.column_id";
        try (var p = DmNativeIndexes.prepare(c, query, timeout, schema, table); var r = p.executeQuery()) {
            while (r.next()) {
                if (columns.size() >= MAX_COLUMNS || r.getString(3) != null || !Set.of("Y", "N")
                        .contains(r.getString(9)) || !"NO".equals(r.getString(10)) || !"NO".equals(r.getString(11))
                        || (r.getInt(12) & ~1) != 0 || r.wasNull() || r.getInt(13) != 0 || r.wasNull()) {
                    throw unsupported();
                }
                String kind = r.getString(2), semantics = r.getString(8);
                int length = Set.of("CHAR", "VARCHAR", "VARCHAR2", "NCHAR", "NVARCHAR", "NVARCHAR2")
                        .contains(kind) && "C".equals(semantics) ? r.getInt(7) : r.getInt(6);
                String remarks = r.getString(14), defaultValue = r.getString(15);
                int flags = r.getInt(16);
                // DM 将 DEFAULT ON NULL 及隐式 NOT NULL 存在 SYSCOLINFOS，而非 ALL_TAB_COLS。
                if ((flags & ~48) != 0) {
                    throw unsupported();
                }
                columns.add(new Column(r.getString(1), kind, integer(r, 4), integer(r, 5), length, semantics,
                        "Y".equals(r.getString(9)), defaultValue, (flags & 16) != 0, remarks,
                        (r.getInt(12) & 1) == 0 ? null : generator(c, schema, table, r.getBytes(17), timeout)));
            }
        }
        var fields = new HashSet<>(columns.stream().map(Column::name).toList());
        var checks = DmNativeChecks.read(c, schema, table, fields, timeout);
        var indexes = DmNativeIndexes.read(c, schema, table, fields, checks, timeout);
        if (!before.equals(DmRelationIdentity.physicalFingerprint(c, schema, table, timeout))) {
            throw new SQLException("DM structure changed during capture");
        }
        return new Definition(before, characterProfile(c, timeout), columns, comment, indexes, checks);
    }

    /**
     * 返回当前生成器。
     */
    private static Generator generator(Connection c, String schema, String table, byte[] catalog,
            int timeout) throws SQLException {
        // 官方 SYSOBJECTS INFO6 定义：第 1..8 字节为种子，9..16 为增量，25..26 为标识类型。
        if (catalog == null || catalog.length < 26) {
            throw unsupported();
        }
        var bytes = java.nio.ByteBuffer.wrap(catalog).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        if (bytes.getShort(24) != 1) {
            throw unsupported(); // AUTO_INCREMENT 具有独立且依赖会话的语义。
        }
        var value = new Generator(bytes.getLong(0), bytes.getLong(8));
        // DM 内置函数接受物理名称，而非 SQL 引号包围的名称。任一名称中的点号
        // 对该辅助 API 都有歧义；精确的模式与对象目录连接仍是权威依据。
        if (schema.indexOf('.') >= 0 || table.indexOf('.') >= 0) {
            return value;
        }
        String relation = schema + "." + table;
        try (var p = DmNativeIndexes.prepare(c, "SELECT IDENT_SEED(?),IDENT_INCR(?) FROM DUAL", timeout, relation,
                relation); var r = p.executeQuery()) {
            if (!r.next() || r.getBigDecimal(1) == null || r.getBigDecimal(2) == null
                    || r.getBigDecimal(1).toBigIntegerExact().longValueExact() != value.seed()
                    || r.getBigDecimal(2).toBigIntegerExact().longValueExact() != value.increment() || r.next()) {
                throw unsupported();
            }
        }
        return value;
    }

    /**
     * 解析恢复材料中的标识列起点和增量定义。
     */
    private static Generator decodeGenerator(JSONObject value) {
        if (value == null) {
            return null;
        }
        if (!(value.get("seed") instanceof String seed) || !(value.get("increment") instanceof String step)
                || !seed.matches("-?(0|[1-9][0-9]{0,18})") || !step.matches("-?(0|[1-9][0-9]{0,18})")) {
            throw invalid();
        }
        return new Generator(Long.parseLong(seed), Long.parseLong(step));
    }

    /**
     * 读取数据库字符集、大小写及兼容模式配置。
     */
    private static String characterProfile(Connection c, int timeout) throws SQLException {
        try (var p = DmNativeIndexes.prepare(c,
                "SELECT SF_GET_UNICODE_FLAG(),SF_GET_CASE_SENSITIVE_FLAG(),(SELECT VALUE FROM V$PARAMETER WHERE NAME='COMPATIBLE_MODE') FROM DUAL",
                timeout); var r = p.executeQuery()) {
            if (!r.next() || !"0".equals(r.getString(3))) {
                throw new SQLException("DM native literal and type profile requires native compatibility mode");
            }
            String value = r.getInt(1) + ":" + r.getInt(2);
            if (r.next()) {
                throw unsupported();
            }
            return value;
        }
    }

    /**
     * 检查目标对象不存在当前路径无法接管的外部依赖。
     */
    private static void requireNoExternalDependents(Connection c, String schema, String table,
            int timeout) throws SQLException {
        String object = "SELECT o.id FROM SYS.SYSOBJECTS o JOIN SYS.SYSOBJECTS s ON s.id=o.schid WHERE s.name=? AND o.name=? AND o.type$='SCHOBJ' AND o.subtype$='UTAB'";
        // 自动提交的 DROP 后，视图或过程可能仍处于无效状态；外部所有权不等于允许丢弃它。
        try (var p = DmNativeIndexes.prepare(c,
                "SELECT 1 FROM SYS.SYSDEPENDENCIES d WHERE d.refed_id IN (" + object + ") AND d.type$ NOT IN ('INDEX')",
                timeout, schema, table); var r = p.executeQuery()) {
            if (r.next()) {
                throw new SQLException("DM target has external dependencies");
            }
        }
        try (var p = DmNativeIndexes.prepare(c,
                "SELECT 1 FROM SYS.SYSCONS fk JOIN SYS.SYSOBJECTS ix ON ix.id=fk.findexid WHERE fk.type$='F' AND ix.pid IN (" + object + ")",
                timeout, schema, table); var r = p.executeQuery()) {
            if (r.next()) {
                throw new SQLException("DM target has incoming foreign keys");
            }
        }
    }

    /**
     * 返回类型。
     */
    private static String type(String kind, Integer precision, Integer scale, int length, String semantics) {
        if (kind == null) {
            throw invalid();
        }
        return switch (kind) {
            case "INT", "INTEGER", "BIGINT", "SMALLINT", "TINYINT", "BIT", "BOOLEAN", "REAL", "DOUBLE",
                 "DOUBLE PRECISION", "FLOAT", "DATE", "BLOB", "CLOB", "TEXT", "IMAGE" -> kind;
            case "DECIMAL", "NUMERIC", "NUMBER" -> {
                if (precision == null || precision < 1 || precision > 38 || scale == null || scale < 0 || scale > precision) {
                    throw invalid();
                }
                yield kind + "(" + precision + "," + scale + ")";
            }
            case "TIMESTAMP", "DATETIME", "TIME" -> {
                if (scale == null || scale < 0 || scale > (kind.equals("TIME") ? 6 : 9)) {
                    throw invalid();
                }
                yield kind + "(" + scale + ")";
            }
            case "VARCHAR", "VARCHAR2", "CHAR", "NVARCHAR", "NVARCHAR2", "NCHAR" -> {
                if (length < 1 || length > 32767 || !Set.of("B", "C").contains(semantics)) {
                    throw invalid();
                }
                yield kind + "(" + length + (semantics.equals("C") ? " CHAR" : " BYTE") + ")";
            }
            case "BINARY", "VARBINARY", "RAW" -> {
                if (length < 1 || length > 32767) {
                    throw invalid();
                }
                yield kind + "(" + length + ")";
            }
            default -> throw new IllegalArgumentException("DM native type requires a verified renderer");
        };
    }

    /**
     * 规范化列默认值 SQL，并将显式 NULL 视为无默认值。
     */
    private static String defaultSql(String kind, String sql) {
        if (sql == null) {
            return null;
        }
        String value = sql.trim();
        if (value.equalsIgnoreCase("NULL")) {
            return null;
        }
        if (value.length() > 8192) {
            throw invalid();
        }
        if (Set.of("INT", "INTEGER", "BIGINT", "SMALLINT", "TINYINT", "BIT", "BOOLEAN", "REAL", "DOUBLE",
                "DOUBLE PRECISION", "FLOAT", "DECIMAL", "NUMERIC", "NUMBER").contains(kind)
                && value.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]{1,3})?")) {
            var number = new BigDecimal(value);
            if (Math.abs(number.scale()) > 1024 || number.precision() > 1024) {
                throw invalid();
            }
            return number.stripTrailingZeros().toPlainString();
        }
        if (Set.of("CHAR", "VARCHAR", "VARCHAR2", "NCHAR", "NVARCHAR", "NVARCHAR2", "TEXT", "CLOB")
                .contains(kind) && value.startsWith("'")) {
            StringBuilder decoded = new StringBuilder();
            for (int i = 1; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (ch == '\'') {
                    if (i + 1 < value.length() && value.charAt(i + 1) == '\'') {
                        decoded.append('\'');
                        i++;
                    } else if (i == value.length() - 1) {
                        return literal(decoded.toString());
                    } else {
                        throw invalid();
                    }
                } else {
                    decoded.append(ch);
                }
            }
        }
        throw new IllegalArgumentException("DM dependent or nonliteral default requires a verified dependency plan");
    }

    /**
     * 生成原生对象的创建 SQL。
     */
    private static String createSql(String schema, String table, Definition definition) {
        var parts = new ArrayList<>(definition.columns().stream().map(Column::sql).toList());
        parts.addAll(DmNativeIndexes.tableConstraints(definition.indexes(), schema, table));
        parts.addAll(DmNativeChecks.tableConstraints(definition.checks(), schema, table));
        return "CREATE TABLE " + DmSqlNames.relation(schema, table) + "(" + String.join(",", parts) + ")";
    }

    /**
     * 将当前数据编码为字符串。
     */
    private static String encode(Definition d) {
        var root = new JSONObject().set("identity", d.identity()).set("characterProfile", d.characterProfile())
                .set("comment", d.comment()).set("indexes", DmNativeIndexes.encode(d.indexes()))
                .set("checks", DmNativeChecks.encode(d.checks()));
        root.set("columns", d.columns().stream()
                .map(c -> new JSONObject().set("name", c.name()).set("kind", c.kind()).set("precision", c.precision())
                        .set("scale", c.scale()).set("length", c.length())
                        .set("semantics", c.semantics()).set("nullable", c.nullable()).set("default", c.defaultValue())
                        .set("defaultOnNull", c.defaultOnNull()).set("comment", c.comment()).set("generator",
                                c.generator() == null ? null : new JSONObject().set("seed",
                                                Long.toString(c.generator().seed()))
                                        .set("increment", Long.toString(c.generator().increment())))).toList());
        String json = root.toString();
        if (json.length() > MAX_DOCUMENT) {
            throw invalid();
        }
        return json;
    }

    /**
     * 从编码数据恢复定义。
     */
    private static Definition decode(String json) {
        if (json == null || json.length() > MAX_DOCUMENT) {
            throw invalid();
        }
        var root = JSONUtil.parseObj(json);
        var array = root.getJSONArray("columns");
        if (array == null || array.size() > MAX_COLUMNS) {
            throw invalid();
        }
        var columns = new ArrayList<Column>();
        for (Object object : array) {
            var c = (JSONObject) object;
            if (!(c.get("nullable") instanceof Boolean) || !(c.get("defaultOnNull") instanceof Boolean)) {
                throw invalid();
            }
            columns.add(new Column(c.getStr("name"), c.getStr("kind"), c.getInt("precision"), c.getInt("scale"),
                    c.getInt("length"), c.getStr("semantics"), c.getBool("nullable"), c.getStr("default"),
                    c.getBool("defaultOnNull"), c.getStr("comment"), decodeGenerator(c.getJSONObject("generator"))));
        }
        return new Definition(root.getStr("identity"), root.getStr("characterProfile"), columns, root.getStr("comment"),
                DmNativeIndexes.decode(root.getJSONObject("indexes")),
                DmNativeChecks.decode(root.getJSONObject("checks")));
    }

    /**
     * 在当前连接上执行给定的 DDL 语句。
     */
    private static void executeDdl(Connection c, NonTransactionalWriteExecution.Attempt attempt, String sql,
            int timeout) throws SQLException {
        ddlBudget(sql);
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            attempt.sending();
            s.execute(sql);
        }
    }

    /**
     * 检查 DDL 的 UTF-8 字节数未超过文档上限。
     */
    private static void ddlBudget(String sql) {
        if (sql.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_DOCUMENT) {
            throw new IllegalArgumentException("DM DDL byte budget exceeded");
        }
    }

    /**
     * 从 JDBC 结果集读取并转换整数。
     */
    private static Integer integer(ResultSet r, int index) throws SQLException {
        int value = r.getInt(index);
        return r.wasNull() ? null : value;
    }

    /**
     * 校验当前输入未超过约定的大小或数量上限。
     */
    private static void bounded(String text) {
        if (text != null && (text.length() > 4000 || text.indexOf('\0') >= 0)) {
            throw invalid();
        }
    }

    /**
     * 返回当前字面量。
     */
    private static String literal(String text) {
        bounded(text);
        return '\'' + text.replace("'", "''") + '\'';
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid DM native structure evidence");
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLException unsupported() {
        return new SQLException("DM table requires a verified native structure strategy");
    }
}
