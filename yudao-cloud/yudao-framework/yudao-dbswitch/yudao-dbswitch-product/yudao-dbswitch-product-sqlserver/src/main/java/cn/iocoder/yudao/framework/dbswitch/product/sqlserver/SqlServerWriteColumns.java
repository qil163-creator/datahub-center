package cn.iocoder.yudao.framework.dbswitch.product.sqlserver;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.SqlServerTimeValues;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.time.*;
import java.util.*;

/**
 * FULL、DIFF 和原生 MERGE 写入共享的目标列事实及精确 SQL Server 16 值域。
 * 借用连接前验证值，否则 SQL Server 会舍入货币、小数或时间的
 * 标度、补齐 binary(n)、替换无法映射的 varchar 字符，或在批次已发送后才失败，结果为 UNKNOWN。
 * 绝不写入服务器生成列（计算列、rowversion、GENERATED ALWAYS）及 sql_variant。
 */
public final class SqlServerWriteColumns {

    private static final BigDecimal MONEY_MIN = new BigDecimal("-922337203685477.5808"), MONEY_MAX = new BigDecimal(
            "922337203685477.5807");

    private static final BigDecimal SMALLMONEY_MIN = new BigDecimal("-214748.3648"), SMALLMONEY_MAX = new BigDecimal(
            "214748.3647");

    private static final Set<String> CLR = Set.of("hierarchyid", "geometry", "geography");

    /** base 为系统类型名；长度按字符（n 类型）或字节计算，-1 表示 MAX。 */
    public record Column(String name, String base, int length, int precision, int scale, Integer codePage,
                         boolean nullable,
                         boolean computed, boolean rowversion, boolean generatedAlways, boolean clr) {

        /**
         * 判断当前列值是否由服务端自动生成。
         */
        public boolean serverGenerated() {
            return computed || rowversion || generatedAlways;
        }

        /**
         * 读取或生成当前产品使用的 SQL 变体。
         */
        public boolean sqlVariant() {
            return base.equals("sql_variant");
        }

        /** 与原生目录读取器使用相同规范拼写，用于原生 MERGE 的 CAST(? AS type)。 */
        public String nativeType() {
            return switch (base) {
                case "decimal", "numeric" -> base + '(' + precision + ',' + scale + ')';
                case "char", "varchar", "binary", "varbinary", "nchar", "nvarchar" ->
                        base + '(' + (length == -1 ? "max" : Integer.toString(length)) + ')';
                case "datetime2", "time", "datetimeoffset" -> base + '(' + scale + ')';
                case "float" -> "float(" + precision + ')';
                default -> base;
            };
        }

        /** JDBC 参数类型；CLR 序列化值以 varbinary 发送，由服务器精确转换。 */
        public int bindingType(int metadataType) {
            return clr ? Types.VARBINARY : base.equals(
                    "datetimeoffset") ? SqlServerTimeValues.DATETIMEOFFSET : metadataType;
        }

        /** 返回精确值域内的值或拒绝；宽松模式仅对非文本标量转文本保留历史驱动转换。 */
        public Object exact(Object value, boolean strict) throws SQLException {
            if (value == null) {
                if (!nullable) {
                    throw new SQLException("SQL Server required value is null");
                }
                return null;
            }
            if (serverGenerated() || sqlVariant()) {
                throw new SQLException("SQL Server server-generated or sql_variant column is not writable");
            }
            switch (base) {
                case "tinyint":
                    return integer(value, 0, 255).shortValueExact();
                case "smallint":
                    return integer(value, Short.MIN_VALUE, Short.MAX_VALUE).shortValueExact();
                case "int":
                    return integer(value, Integer.MIN_VALUE, Integer.MAX_VALUE).intValueExact();
                case "bigint":
                    return integer(value, Long.MIN_VALUE, Long.MAX_VALUE).longValueExact();
                case "bit":
                    if (value instanceof Boolean) {
                        return value;
                    }
                    var bit = integer(value, 0, 1);
                    return bit.signum() != 0;
                case "decimal", "numeric": {
                    var number = decimal(value, scale);
                    if (number.abs().compareTo(BigDecimal.TEN.pow(precision - scale)) >= 0) {
                        throw new SQLException("SQL Server decimal input is out of range");
                    }
                    return number;
                }
                case "money":
                    return range(decimal(value, 4), MONEY_MIN, MONEY_MAX);
                case "smallmoney":
                    return range(decimal(value, 4), SMALLMONEY_MIN, SMALLMONEY_MAX);
                case "date": {
                    LocalDate date = value instanceof LocalDate v ? v : value instanceof java.sql.Date v ? v.toLocalDate() : null;
                    if (date == null && civil(value) instanceof LocalDateTime time && time.toLocalTime()
                            .equals(LocalTime.MIDNIGHT)) {
                        date = time.toLocalDate();
                    }
                    if (date == null || date.getYear() < 1 || date.getYear() > 9999) {
                        throw new SQLException("Exact date input required");
                    }
                    return date;
                }
                case "time": {
                    if (!(value instanceof LocalTime) && !(value instanceof java.sql.Time)) {
                        throw new SQLException("Exact time input required");
                    }
                    return fraction(JdbcValueConversions.toTime(value), JdbcValueConversions.toTime(value).getNano());
                }
                case "datetime2", "datetime", "smalldatetime": {
                    LocalDateTime time = civil(value);
                    if (time == null && value instanceof LocalDate date) {
                        time = date.atStartOfDay();
                    }
                    if (time == null || time.getYear() < 1 || time.getYear() > 9999) {
                        throw new SQLException("Exact civil timestamp input required");
                    }
                    if (base.equals("datetime2")) {
                        return fraction(time, time.getNano());
                    }
                    legacy(time);
                    return time;
                }
                case "datetimeoffset": {
                    OffsetDateTime time = offset(value);
                    if (time == null || time.getYear() < 1 || time.getYear() > 9999 || time.withOffsetSameInstant(
                            ZoneOffset.UTC).getYear() < 1
                            || time.withOffsetSameInstant(ZoneOffset.UTC).getYear() > 9999) {
                        throw new SQLException("Exact datetimeoffset input with an explicit offset required");
                    }
                    if (time.getOffset().getTotalSeconds() % 60 != 0 || Math.abs(
                            time.getOffset().getTotalSeconds()) > 14 * 3600) {
                        throw new SQLException("SQL Server datetimeoffset offset would be lost");
                    }
                    return fraction(time, time.getNano());
                }
                case "uniqueidentifier": {
                    // 原始 16 字节输入会按 SQL Server 的混合字节序 GUID 布局重新解释。
                    String text = value instanceof UUID v ? v.toString() : value instanceof String v ? v : null;
                    if (text == null || !text.matches(
                            "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")) {
                        throw new SQLException("Canonical uniqueidentifier input required");
                    }
                    return text.toLowerCase(Locale.ROOT);
                }
                case "char", "varchar", "text", "nchar", "nvarchar", "ntext", "sysname", "xml": {
                    if (value instanceof UUID id) {
                        value = id.toString();
                    }
                    if (value instanceof Character character) {
                        value = character.toString();
                    }
                    if (!(value instanceof String text)) {
                        if (strict || base.equals("xml") || value instanceof byte[]) {
                            throw new SQLException("Materialized text input required");
                        }
                        return value;
                    }
                    boolean unicode = base.startsWith("n") || base.equals("xml") || base.equals(
                            "sysname"), ascii = true, surrogate = false;
                    for (int i = 0; i < text.length() && (ascii || !surrogate); i++) {
                        char c = text.charAt(i);
                        ascii &= c < 0x80;
                        surrogate |= Character.isSurrogate(c);
                    }
                    try {
                        // 未配对代理字符不能往返；varchar 字符必须能由代码页精确表示，不能依赖近似替换。
                        if (surrogate) {
                            StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                                    .encode(CharBuffer.wrap(text));
                        }
                        long actual = unicode || ascii ? text.length() : charset(codePage).newEncoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
                                .remaining();
                        if (!unicode && codePage == null) {
                            throw new SQLException("SQL Server code page is unavailable");
                        }
                        if (length >= 0 && actual > length) {
                            throw new SQLException("SQL Server text input exceeds its domain");
                        }
                        return text;
                    } catch (CharacterCodingException failure) {
                        throw new SQLException("SQL Server text input cannot be encoded exactly");
                    }
                }
                case "binary", "varbinary", "image": {
                    if (!(value instanceof byte[] bytes)) {
                        throw new SQLException("Exact binary input required");
                    }
                    // binary(n) 会为较短值补零字节。
                    if (length >= 0 && (bytes.length > length || base.equals("binary") && bytes.length != length)) {
                        throw new SQLException("SQL Server binary input does not match its domain");
                    }
                    return bytes.clone();
                }
                default:
                    if (clr) {
                        if (value instanceof byte[] bytes) {
                            return bytes.clone();
                        }
                        throw new SQLException("SQL Server CLR values require their native serialization");
                    }
                    if (strict) {
                        throw new SQLException("SQL Server value has no exact replay codec");
                    }
                    return value;
            }
        }

        /**
         * 返回当前小数部分。
         */
        private <T> T fraction(T value, int nanos) throws SQLException {
            if (scale < 0 || scale > 7 || nanos % (int) Math.pow(10, 9 - scale) != 0) {
                throw new SQLException("SQL Server temporal precision would be lost");
            }
            return value;
        }

        /** datetime 存储 1/300 秒刻度（.xx0、.xx3、.xx7 毫秒），smalldatetime 存储整分钟，其他输入会被舍入。 */
        private void legacy(LocalDateTime time) throws SQLException {
            if (base.equals("datetime") && (time.isBefore(LocalDateTime.of(1753, 1, 1, 0, 0)) || time.isAfter(
                    LocalDateTime.of(9999, 12, 31, 23, 59, 59, 997_000_000))
                    || time.getNano() % 1_000_000 != 0 || !Set.of(0, 3, 7).contains(time.getNano() / 1_000_000 % 10))) {
                throw new SQLException("SQL Server datetime would round this value");
            }
            if (base.equals("smalldatetime") && (time.isBefore(LocalDateTime.of(1900, 1, 1, 0, 0)) || time.isAfter(
                    LocalDateTime.of(2079, 6, 6, 23, 59))
                    || time.getSecond() != 0 || time.getNano() != 0)) {
                throw new SQLException("SQL Server smalldatetime would round this value");
            }
        }
    }

    private final Map<String, Column> columns;

    /**
     * 初始化 {@code SqlServerWriteColumns}。
     */
    private SqlServerWriteColumns(Map<String, Column> columns) {
        this.columns = Collections.unmodifiableMap(columns);
    }

    /** 已验证 SQL Server 16 之外的服务器返回 null，保留历史上未经认证的驱动转换。 */
    public static SqlServerWriteColumns read(DataSource dataSource, String schema, String table, int timeout) {
        try (Connection connection = dataSource.getConnection()) {
            var metadata = connection.getMetaData();
            if (!"Microsoft SQL Server".equals(
                    metadata.getDatabaseProductName()) || metadata.getDatabaseMajorVersion() != 16) {
                return null;
            }
            return read(connection, schema, table, timeout);
        } catch (SQLException failure) {
            throw new IllegalStateException("SQL Server target column contract is unavailable");
        }
    }

    /**
     * 读取 SQL Server 目标列的写入属性和精确值域。
     */
    public static SqlServerWriteColumns read(Connection connection, String schema, String table,
            int timeout) throws SQLException {
        SqlServerCursorMetadata.product(connection);
        Map<String, Column> columns = new LinkedHashMap<>();
        try (var statement = connection.prepareStatement(
                "SELECT c.name,CASE WHEN t.is_assembly_type=1 THEN t.name ELSE TYPE_NAME(c.system_type_id) END,"
                        + "c.max_length,c.precision,c.scale,c.is_nullable,c.is_computed,c.system_type_id,c.generated_always_type,t.is_assembly_type,"
                        + "CONVERT(int,COLLATIONPROPERTY(c.collation_name,'CodePage')) FROM sys.columns c JOIN sys.types t ON t.user_type_id=c.user_type_id "
                        + "WHERE c.object_id=OBJECT_ID(?, 'U') ORDER BY c.column_id")) {
            statement.setQueryTimeout(Math.max(1, timeout));
            statement.setString(1, RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.SQLSERVER, schema, table));
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (columns.size() >= 4096) {
                        throw new SQLException("SQL Server column limit exceeded");
                    }
                    String name = rows.getString(1), base = Objects.requireNonNullElse(rows.getString(2), "")
                            .toLowerCase(Locale.ROOT);
                    int bytes = rows.getInt(3), systemType = rows.getInt(8);
                    Integer page = rows.getInt(11);
                    if (rows.wasNull()) {
                        page = null;
                    }
                    int length = base.matches("n?(?:var)?char|(?:var)?binary") ? bytes == -1 ? -1 : base.startsWith(
                            "n") ? bytes / 2 : bytes : -1;
                    boolean clr = rows.getBoolean(10) || CLR.contains(base);
                    columns.put(name,
                            new Column(name, base, length, rows.getInt(4), rows.getInt(5), page, rows.getBoolean(6),
                                    rows.getBoolean(7), systemType == 189, rows.getInt(9) != 0, clr));
                }
            }
        }
        if (columns.isEmpty()) {
            throw new SQLException("SQL Server target table is not visible");
        }
        return new SqlServerWriteColumns(columns);
    }

    /** 重放列的原生目录证据，代码页由调用方解析。 */
    public static Column of(ColumnDescription column, Integer codePage) throws SQLException {
        String type = SqlServerCursorMetadata.nativeType(column);
        var evidence = column.getMetadataEvidence();
        String base = type.contains("(") ? type.substring(0, type.indexOf('(')) : type;
        int length = -1;
        if (base.matches("n?(?:var)?char|(?:var)?binary") && !type.endsWith("(max)")) {
            length = Integer.parseInt(type.substring(base.length() + 1, type.length() - 1));
        }
        Integer precision = evidence.numericPrecision().value(), scale = evidence.numericScale().value();
        if (precision == null || scale == null) {
            throw new SQLException("SQL Server numeric evidence is unavailable");
        }
        return new Column(column.getFieldName(), base, length, precision, scale, codePage, column.isNullable(), false,
                false, false, false);
    }

    /** 任何目标语句前提前拒绝，否则发送后驱动失败会产生 UNKNOWN 结果。 */
    public void requireWritable(List<String> fields) {
        for (String field : fields) {
            var column = columns.get(field);
            if (column == null) {
                continue; // 物理名称解析和缺失列仍属于调用方的准备阶段失败。
            }
            if (column.serverGenerated()) {
                throw new IllegalArgumentException(
                        "SQL Server computed, rowversion and GENERATED ALWAYS columns are server-generated;"
                                + " exclude them from the column mapping before writing");
            }
            if (column.sqlVariant()) {
                throw new IllegalArgumentException("SQL Server sql_variant target columns have no exact write codec");
            }
        }
    }

    /**
     * 读取或组装当前操作所使用的列定义。
     */
    public List<Column> columns(List<String> fields) {
        List<Column> result = new ArrayList<>(fields.size());
        for (String field : fields) {
            var column = columns.get(field);
            if (column == null) {
                throw new IllegalArgumentException("SQL Server target column is not visible");
            }
            result.add(column);
        }
        return List.copyOf(result);
    }

    /** 在调用线程完成整批精确转换，时间文本参数的绑定不依赖语言设置。 */
    public static List<Object[]> exactRows(List<Object[]> rows, List<Column> order, boolean strict) {
        List<Object[]> copy = new ArrayList<>(rows.size());
        for (int row = 0; row < rows.size(); row++) {
            Object[] values = rows.get(row);
            if (values == null || values.length != order.size()) {
                throw new BatchRejectedException("SQL Server batch column count mismatch");
            }
            Object[] exact = new Object[values.length];
            for (int i = 0; i < values.length; i++) {
                try {
                    exact[i] = SqlServerTimeValues.bindable(order.get(i).exact(values[i], strict), order.get(i).base());
                } catch (SQLException | RuntimeException failure) {
                    throw new BatchRejectedException(
                            "SQL Server input cannot be represented exactly at row " + (row + 1) + ", column " + (i + 1));
                }
            }
            copy.add(exact);
        }
        return List.copyOf(copy);
    }

    /**
     * 返回当前参数集合对应的绑定类型。
     */
    public static int[] bindingTypes(int[] metadataTypes, List<Column> order) {
        if (metadataTypes.length != order.size()) {
            throw new IllegalArgumentException("SQL Server parameter types are incomplete");
        }
        int[] types = metadataTypes.clone();
        for (int i = 0; i < types.length; i++) {
            types[i] = order.get(i).bindingType(types[i]);
        }
        return types;
    }

    /**
     * 按当前值域约定解析或转换整数。
     */
    private static BigInteger integer(Object value, long minimum, long maximum) throws SQLException {
        BigInteger number;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            number = BigInteger.valueOf(((Number) value).longValue());
        } else if (value instanceof BigInteger v) {
            number = v;
        } else if (value instanceof BigDecimal v) {
            try {
                number = v.toBigIntegerExact();
            } catch (ArithmeticException fractional) {
                throw new SQLException("SQL Server integer input is fractional");
            }
        } else {
            throw new SQLException("Exact integer input required");
        }
        if (number.compareTo(BigInteger.valueOf(minimum)) < 0 || number.compareTo(BigInteger.valueOf(maximum)) > 0) {
            throw new SQLException("SQL Server integer input is out of range");
        }
        return number;
    }

    /** 不使用 float 或 double 路径，二进制浮点数不是精确十进制源值。 */
    private static BigDecimal decimal(Object value, int scale) throws SQLException {
        BigDecimal number;
        if (value instanceof BigDecimal v) {
            number = v;
        } else if (value instanceof BigInteger v) {
            number = new BigDecimal(v);
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            number = BigDecimal.valueOf(((Number) value).longValue());
        } else {
            throw new SQLException("Exact decimal input required");
        }
        try {
            return number.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException failure) {
            throw new SQLException("SQL Server decimal scale would be lost");
        }
    }

    /**
     * 返回当前范围。
     */
    private static BigDecimal range(BigDecimal value, BigDecimal minimum, BigDecimal maximum) throws SQLException {
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new SQLException("SQL Server money input is out of range");
        }
        return value;
    }

    /**
     * 按当前值域约定解析或转换本地日期时间。
     */
    private static LocalDateTime civil(Object value) {
        return value instanceof LocalDateTime v ? v : value instanceof java.sql.Timestamp v ? v.toLocalDateTime() : null;
    }

    /** java.sql.Timestamp 不含偏移证据，以猜测时区保存会改变瞬间或偏移。 */
    private static OffsetDateTime offset(Object value) throws SQLException {
        if (value instanceof OffsetDateTime v) {
            return v;
        }
        if (value instanceof ZonedDateTime v) {
            return v.toOffsetDateTime();
        }
        if (value instanceof Instant v) {
            return v.atOffset(ZoneOffset.UTC);
        }
        if (value != null && value.getClass().getName().equals("microsoft.sql.DateTimeOffset")) {
            try {
                return SqlServerTimeValues.requireUnambiguous(
                        (OffsetDateTime) value.getClass().getMethod("getOffsetDateTime").invoke(value));
            } catch (ReflectiveOperationException | ClassCastException failure) {
                throw new SQLException("SQL Server datetimeoffset input is unreadable");
            }
        }
        return null;
    }

    /**
     * 返回字符集。
     */
    private static Charset charset(Integer page) throws SQLException {
        if (page == null) {
            throw new SQLException("SQL Server code page is unavailable");
        }
        try {
            return page == 65001 ? StandardCharsets.UTF_8 : Charset.forName(
                    page == 932 ? "windows-31j" : page == 936 ? "GBK" : page == 949 ? "MS949" : page == 950 ? "Big5" : "windows-" + page);
        } catch (IllegalArgumentException failure) {
            throw new SQLException("SQL Server code page has no verified encoder");
        }
    }
}
