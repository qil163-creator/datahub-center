package cn.iocoder.yudao.framework.dbswitch.product.sqlserver;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import cn.iocoder.yudao.framework.dbswitch.core.schema.*;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.SqlServerTimeValues;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;

import java.math.*;
import java.nio.charset.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** 原生 SQL Server 16 重放值域，绝不推断 SQL Server 2000 能力。 */
public final class SqlServerCursorMetadata {

    /**
     * 初始化 {@code SqlServerCursorMetadata}。
     */
    private SqlServerCursorMetadata() {
    }

    public record Shape(TableSchemaMetadata schema, String physical, String fingerprint,
                        Map<String, Integer> codePages) {

        /**
         * 初始化 {@code Shape}。
         */
        public Shape {
            codePages = Map.copyOf(codePages);
        }

        /**
         * 读取或组装当前操作所使用的列定义。
         */
        public Map<String, ColumnDescription> columns() {
            var values = new LinkedHashMap<String, ColumnDescription>();
            schema.columns().forEach(c -> values.put(c.getFieldName(), c));
            return Collections.unmodifiableMap(values);
        }
    }

    /**
     * 按SQL Server方言转义并引用标识符。
     */
    public static String quote(String name) {
        return RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.SQLSERVER, name);
    }

    /**
     * 生成模式与表组成的限定关系名。
     */
    public static String relation(String schema, String table) {
        return quote(schema) + '.' + quote(table);
    }

    /**
     * 返回字段集合。
     */
    public static String fields(List<String> names) {
        return String.join(",", names.stream().map(SqlServerCursorMetadata::quote).toList());
    }

    /**
     * 返回当前操作使用的数据库产品。
     */
    public static void product(Connection c) throws SQLException {
        if (!"Microsoft SQL Server".equals(c.getMetaData().getDatabaseProductName()) || c.getMetaData()
                .getDatabaseMajorVersion() != 16) {
            throw new SQLException("SQL Server 2022 native replay connection required");
        }
    }

    /**
     * 按当前游标位置读取受限数量的数据行。
     */
    public static Shape read(Connection c, String schema, String table, int timeout) throws SQLException {
        product(c);
        String physical = physical(c, schema, table, timeout);
        var metadata = NativeSchemaReader.read(c, ProductTypeEnum.SQLSERVER, schema, table, Math.min(timeout, 120));
        // 约束可能原子拒绝批次，触发器、行级安全和数据掩码会改变重放契约。
        if (metadata.additionalObjects().state() != MetadataValue.State.ABSENT
                && (metadata.additionalObjects().state() != MetadataValue.State.VALUE || metadata.additionalObjects()
                .value().stream()
                .anyMatch(v -> !Set.of("CHECK", "FOREIGN_KEY", "INDEX", "PRIMARY_KEY_STORAGE_OR_DIRECTION",
                        "CUSTOM_STATISTICS").contains(v)))) {
            throw new SQLException("SQL Server replay table has unverified side effects or storage");
        }
        Map<String, Integer> codePages = new LinkedHashMap<>();
        for (var column : metadata.columns()) {
            if (column.getMetadataEvidence().generatedExpression().state() != MetadataValue.State.ABSENT) {
                throw new SQLException("SQL Server generated or special columns require an explicit replay plan");
            }
            nativeType(column);
            var collation = column.getMetadataEvidence().collation();
            if (collation.state() == MetadataValue.State.VALUE) {
                try (var p = c.prepareStatement("SELECT CONVERT(int,COLLATIONPROPERTY(?, 'CodePage'))")) {
                    p.setQueryTimeout(timeout);
                    p.setString(1, collation.value());
                    try (var r = p.executeQuery()) {
                        if (!r.next()) {
                            throw new SQLException("SQL Server text code page is unavailable");
                        }
                        int page = r.getInt(1);
                        if (r.wasNull() || r.next()) {
                            throw new SQLException("SQL Server text code page is ambiguous");
                        }
                        codePages.put(column.getFieldName(), page);
                    }
                }
            }
        }
        if (!physical.equals(physical(c, schema, table, timeout))) {
            throw new SQLException("SQL Server table changed during replay observation");
        }
        return new Shape(metadata, physical, SchemaFingerprint.of(metadata), codePages);
    }

    /** 对象代次补充外部保留的代次固定标识，但绝不能替代它。 */
    private static String physical(Connection c, String schema, String table, int timeout) throws SQLException {
        try (var p = c.prepareStatement(
                "SELECT CONVERT(varchar(36),d.database_guid),t.object_id,CONVERT(varchar(40),t.create_date,126) "
                        + "FROM sys.tables t JOIN sys.schemas s ON s.schema_id=t.schema_id JOIN sys.database_recovery_status d ON d.database_id=DB_ID() WHERE s.name=? AND t.name=?")) {
            p.setQueryTimeout(timeout);
            p.setString(1, schema);
            p.setString(2, table);
            try (var r = p.executeQuery()) {
                if (!r.next()) {
                    throw new SQLException("SQL Server native table incarnation is unavailable");
                }
                String guid = r.getString(1), created = r.getString(3);
                long object = r.getLong(2);
                if (guid == null || created == null || object <= 0 || r.next()) {
                    throw new SQLException("SQL Server native table incarnation is ambiguous");
                }
                return DigestUtil.sha256Hex(guid + '\0' + object + '\0' + created);
            }
        }
    }

    /**
     * 为当前操作获取目标对象的锁。
     */
    public static void lockTarget(Connection c, String schema, String table, int timeout) throws SQLException {
        if (c.getAutoCommit()) {
            throw new SQLException("SQL Server replay lock requires an owned transaction");
        }
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            try (var r = s.executeQuery(
                    "SELECT TOP(1) 1 FROM " + relation(schema, table) + " WITH(TABLOCKX,HOLDLOCK)")) {
                while (r.next()) {
                }
            }
        }
    }

    /**
     * 返回键集合。
     */
    public static CursorKeyContract keys(Shape shape, List<String> keys) throws SQLException {
        if (!shape.schema().primaryKeys().equals(keys)) {
            throw new SQLException("SQL Server replay requires the exact ordered primary key");
        }
        var columns = shape.columns();
        var domains = new ArrayList<CursorKeyContract.Domain>();
        StringBuilder identity = new StringBuilder("MSSQL_CURSOR_KEYS_V1");
        for (String key : keys) {
            var c = columns.get(key);
            if (c == null || c.isNullable()) {
                throw new SQLException("SQL Server cursor key is nullable or absent");
            }
            requireOrdered(c);
            // 键按 UTC 瞬间判断相等；偏移变化时 MERGE 会匹配该键，但绝不会改写它。
            if (nativeType(c).startsWith("datetimeoffset")) {
                throw new SQLException("SQL Server datetimeoffset keys cannot preserve their offset on replay");
            }
            var numeric = numeric(c);
            domains.add(numeric == null ? CursorKeyContract.Domain.nativeOnly() : numeric);
            identity.append('\0').append(nativeType(c)).append(':').append(c.getMetadataEvidence().collation())
                    .append(':').append(shape.codePages().get(key));
        }
        return new CursorKeyContract(ProductTypeEnum.SQLSERVER, DigestUtil.sha256Hex(identity.toString()), domains);
    }

    /** 所有比较均由 SQL Server 执行，包括 GUID 字节序；Java 仅持久保存精确值。 */
    public static void requireOrdered(ColumnDescription c) throws SQLException {
        if (c.isNullable() || !nativeType(c).matches(
                "tinyint|smallint|int|bigint|(?:decimal|numeric)\\([1-9][0-9]?,0?([0-9]+)\\)|money|smallmoney|date|time\\([0-7]\\)"
                        + "|datetime2\\([0-7]\\)|datetime|smalldatetime|datetimeoffset\\([0-7]\\)|uniqueidentifier|n?(?:var)?char\\([1-9][0-9]*\\)")) {
            throw new SQLException("SQL Server cursor ordering type is not verified");
        }
    }

    /**
     * 根据原生列信息确定数据库类型声明。
     */
    public static String nativeType(ColumnDescription c) throws SQLException {
        String t = c.getMetadataEvidence().nativeType().value();
        if (t == null || !t.matches(
                "bit|tinyint|smallint|int|bigint|(?:decimal|numeric)\\([1-9][0-9]?,[0-9]+\\)|money|smallmoney|uniqueidentifier|date|time\\([0-7]\\)|datetime2\\([0-7]\\)|datetimeoffset\\([0-7]\\)|datetime|smalldatetime|n?(?:var)?char\\((?:max|[1-9][0-9]*)\\)|(?:var)?binary\\((?:max|[1-9][0-9]*)\\)")) {
            throw new SQLException("SQL Server column has no exact replay codec");
        }
        return t;
    }

    /** 持久化游标类型：GUID 为规范文本，datetimeoffset 为精确的 OffsetDateTime。 */
    public static int jdbcType(ColumnDescription c) throws SQLException {
        String type = nativeType(c);
        if (type.startsWith("time(")) {
            return Types.TIME;
        }
        if (type.startsWith("datetime2") || type.equals("datetime") || type.equals("smalldatetime")) {
            return Types.TIMESTAMP;
        }
        if (type.startsWith("datetimeoffset")) {
            return Types.TIMESTAMP_WITH_TIMEZONE;
        }
        if (type.equals("money") || type.equals("smallmoney")) {
            return Types.DECIMAL;
        }
        if (type.startsWith("nchar") || type.startsWith("nvarchar")) {
            return Types.NVARCHAR;
        }
        if (type.startsWith("char") || type.startsWith("varchar") || type.equals("uniqueidentifier")) {
            return Types.VARCHAR;
        }
        return c.getFieldType();
    }

    /**
     * 按当前值域约定解析或转换数值。
     */
    public static CursorKeyContract.Domain numeric(ColumnDescription c) throws SQLException {
        return switch (nativeType(c).split("\\(")[0]) {
            case "tinyint" -> CursorKeyContract.Domain.integer(8, true);
            case "smallint" -> CursorKeyContract.Domain.integer(16, false);
            case "int" -> CursorKeyContract.Domain.integer(32, false);
            case "bigint" -> CursorKeyContract.Domain.integer(64, false);
            case "decimal", "numeric" ->
                    CursorKeyContract.Domain.decimal(c.getMetadataEvidence().numericPrecision().value(),
                            c.getMetadataEvidence().numericScale().value(), false);
            // 固定四位标度及 64 位、32 位缩放整数范围，不使用 decimal(19,4) 或 (10,4) 边界。
            case "money" -> new CursorKeyContract.Domain(
                    new CursorKeyContract.Numeric(new BigDecimal("-922337203685477.5808"),
                            new BigDecimal("922337203685477.5807"), 4));
            case "smallmoney" -> new CursorKeyContract.Domain(
                    new CursorKeyContract.Numeric(new BigDecimal("-214748.3648"), new BigDecimal("214748.3647"), 4));
            default -> null;
        };
    }

    /**
     * 根据当前类型及元数据证据确定允许的值域。
     */
    public static SqlServerWriteColumns.Column domain(ColumnDescription c, Integer page) throws SQLException {
        return SqlServerWriteColumns.of(c, page);
    }

    /**
     * 比较实际回读定义与当前计划定义是否完全一致。
     */
    public static Object exact(Object value, ColumnDescription c, Integer page) throws SQLException {
        return domain(c, page).exact(value, true);
    }

    /**
     * 按当前游标位置读取受限数量的数据行。
     */
    public static Object read(ResultSet r, int index, ColumnDescription c, long maxCellBytes) throws SQLException {
        String type = nativeType(c);
        int jdbc = jdbcType(c);
        if (jdbc == Types.TIME) {
            return r.getObject(index, LocalTime.class);
        }
        if (jdbc == Types.TIMESTAMP) {
            return r.getObject(index, LocalDateTime.class);
        }
        if (type.equals("date")) {
            return r.getObject(index, LocalDate.class);
        }
        if (jdbc == Types.TIMESTAMP_WITH_TIMEZONE) {
            var value = r.getObject(index, OffsetDateTime.class);
            return value == null ? null : SqlServerTimeValues.requireUnambiguous(value);
        }
        if (type.equals("uniqueidentifier")) {
            String text = r.getString(index);
            return text == null ? null : UUID.fromString(text);
        }
        if (jdbc == Types.DECIMAL || jdbc == Types.NUMERIC) {
            return r.getBigDecimal(index);
        }
        if (type.endsWith("char(max)")) {
            return SqlServerTimeValues.boundedText(r, index, maxCellBytes);
        }
        if (type.equals("varbinary(max)")) {
            return SqlServerTimeValues.boundedBytes(r, index, maxCellBytes);
        }
        return JdbcValueConversions.normalize(r.getObject(index), maxCellBytes);
    }
}
