package cn.iocoder.yudao.framework.dbswitch.product.opengauss;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalColumn;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalType;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalType.*;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalTypeMapper;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.NativeColumnType;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeMappingResult;

import java.sql.Types;
import java.util.regex.Pattern;

/**
 * openGauss 7.0.0-RC3 构建 01b7e318、server_version 9.2.4、sql_compatibility A，驱动为
 * org.opengauss.Driver（JAR 7.0.0-RC3-og）；每种接受的形态均已从该服务器读取验证。
 * A 模式将 {@code date} 改写为 {@code timestamp(0) without time zone}，并将 {@code ''} 保存为 NULL。
 * CHAR 和 VARCHAR 长度按字节计算（4 字节 UTF-8 字符需要 varchar(4)，varchar(3) 会拒绝）。
 * 下方谓词为 OPENGAUSS_CATALOG_V1 子句；因本次未修改 MetadataProvenance，故保留在此处。
 * 尚未探测 PG 兼容模式，因此不能在该模式复用这些事实。
 */
public final class OpengaussLogicalTypeMapper implements LogicalTypeMapper {

    public static final String BANNER = "(openGauss 7.0.0-RC3 build 01b7e318) compiled at 2026-03-25 18:12:24 commit 0 last mr 9114";

    public static final String SERVER_VERSION = "9.2.4";

    public static final String METHOD = "OPENGAUSS_CATALOG_V1";

    /** 已验证端点可存储的完整年份范围：timestamp 为公元前 4713-01-01 至 9999-12-31；timestamptz 仅确认 294277-01-01。 */
    private static final int MIN_YEAR = -4712, MAX_LOCAL_YEAR = 9999, MAX_INSTANT_YEAR = 294277;

    /** 错误说明：varchar 和 char 的长度不能超过 10485760；已成功创建 varchar(10485760) 与 char(10485760)。 */
    private static final long MAX_CHAR_OCTETS = 10485760L;

    /** text、bytea、json 和 uuid 的 JDBC 精度，不代表已测得的字段上限。 */
    private static final int DRIVER_UNBOUNDED = Integer.MAX_VALUE;

    /** 已探测的 UTF-8 字符 U+1F600 占 4 字节，char_length 为 1。 */
    private static final int UTF8_OCTETS_PER_CHAR = 4;

    private static final Pattern NUMERIC = Pattern.compile("numeric\\(([0-9]{1,4}),([0-9]{1,4})\\)");

    private static final Pattern CHARS = Pattern.compile("(character varying|character)\\(([0-9]{1,8})\\)");

    private static final Pattern TIME = Pattern.compile("time(?:\\(([0-6])\\))? without time zone");

    private static final Pattern TIMESTAMP = Pattern.compile("timestamp(?:\\(([0-6])\\))? (with|without) time zone");

    /**
     * 初始化 {@code OpengaussLogicalTypeMapper}。
     */
    public OpengaussLogicalTypeMapper() {
    }

    /**
     * 返回当前操作使用的数据库产品。
     */
    @Override
    public ProductTypeEnum product() {
        return ProductTypeEnum.OPENGAUSS;
    }

    /**
     * 将数据库原生列类型映射为逻辑类型。
     */
    @Override
    public TypeMappingResult<LogicalType> toLogical(ColumnDescription column) {
        ColumnMetadataEvidence facts = column.getMetadataEvidence();
        if (!verified(facts.provenance())) {
            return TypeMappingResult.unsupported("SOURCE_PROVENANCE_UNVERIFIED",
                    "OPENGAUSS_CATALOG_V1 for the probed 7.0.0-RC3 A-mode server required");
        }
        if (facts.charset().state() != MetadataValue.State.ABSENT) {
            return inconsistent("charset");
        }
        if (facts.collation().state() == MetadataValue.State.VALUE) {
            return TypeMappingResult.unsupported("COLLATION_UNVERIFIED", facts.collation().value());
        }
        if (facts.collation().state() != MetadataValue.State.ABSENT) {
            return incomplete("collation");
        }
        String type = facts.nativeType().value();
        if (type.startsWith("timestamp")) {
            return timestamp(column, facts, type);
        }
        var time = TIME.matcher(type);
        if (time.matches()) {
            return time(column, facts, time.group(1));
        }
        return switch (type) {
            case "smallint" -> integer(column, facts, "int2", Types.SMALLINT, 5, 16, "java.lang.Integer");
            case "integer" -> integer(column, facts, "int4", Types.INTEGER, 10, 32, "java.lang.Integer");
            case "bigint" -> integer(column, facts, "int8", Types.BIGINT, 19, 64, "java.lang.Long");
            case "numeric" -> unboundedNumeric(column, facts);
            case "real" -> floating(column, facts, true);
            case "double precision" -> floating(column, facts, false);
            case "boolean" -> bool(column, facts);
            case "text" -> text(column, facts);
            case "bytea" -> bytea(column, facts);
            case "json" -> json(column, facts);
            case "uuid" -> uuid(column, facts);
            default -> sized(column, facts, type);
        };
    }

    /**
     * 为类型声明补充所需长度或精度信息。
     */
    private TypeMappingResult<LogicalType> sized(ColumnDescription column, ColumnMetadataEvidence facts, String type) {
        var numeric = NUMERIC.matcher(type);
        if (numeric.matches()) {
            return decimal(column, facts, Integer.parseInt(numeric.group(1)), Integer.parseInt(numeric.group(2)));
        }
        var chars = CHARS.matcher(type);
        if (chars.matches()) {
            return character(column, facts, chars.group(1).equals("character"), Long.parseLong(chars.group(2)));
        }
        return TypeMappingResult.unsupported("NATIVE_TYPE_UNRECOGNIZED", type);
    }

    /**
     * 根据原生列证据推导整数的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> integer(ColumnDescription column, ColumnMetadataEvidence facts,
            String typeName,
            int jdbc, int driverPrecision, int catalogPrecision, String className) {
        if (!driver(column, jdbc, typeName, driverPrecision, 0, true, className) || !noTextLength(facts)) {
            return inconsistent(typeName);
        }
        if (facts.numericPrecision().state() != MetadataValue.State.VALUE || facts.numericScale()
                .state() != MetadataValue.State.VALUE) {
            return incomplete("numeric precision/scale");
        }
        if (facts.numericPrecision().value() != catalogPrecision || facts.numericScale().value() != 0) {
            return inconsistent(typeName);
        }
        int bits = switch (catalogPrecision) {
            case 16 -> 16;
            case 32 -> 32;
            default -> 64;
        };
        return exact(new IntegerType(bits, false));
    }

    /**
     * 核对驱动和目录事实后映射无固定精度的数值类型。
     */
    private TypeMappingResult<LogicalType> unboundedNumeric(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (!driver(column, Types.DECIMAL, "numeric", 0, 0, true, "java.math.BigDecimal") || !noTextLength(facts)) {
            return inconsistent("numeric");
        }
        if (facts.numericPrecision().state() != MetadataValue.State.ABSENT || facts.numericScale()
                .state() != MetadataValue.State.ABSENT) {
            return inconsistent("numeric");
        }
        return nan(new DecimalType(null, null, false, true));
    }

    /**
     * 根据原生列证据推导精确小数的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> decimal(ColumnDescription column, ColumnMetadataEvidence facts,
            int precision, int scale) {
        if (precision < 1 || precision > 1000 || scale > precision) {
            return inconsistent("numeric");
        }
        if (!driver(column, Types.DECIMAL, "numeric", precision, scale, true, "java.math.BigDecimal") || !noTextLength(
                facts)) {
            return inconsistent("numeric");
        }
        if (facts.numericPrecision().state() != MetadataValue.State.VALUE || facts.numericScale()
                .state() != MetadataValue.State.VALUE) {
            return incomplete("numeric precision/scale");
        }
        if (facts.numericPrecision().value() != precision || facts.numericScale().value() != scale) {
            return inconsistent("numeric");
        }
        return nan(new DecimalType(precision, scale, false, true));
    }

    /**
     * 根据原生列证据推导浮点数的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> floating(ColumnDescription column, ColumnMetadataEvidence facts,
            boolean single) {
        String typeName = single ? "float4" : "float8";
        int jdbcPrecision = single ? 8 : 17;
        if (!driver(column, single ? Types.REAL : Types.DOUBLE, typeName, jdbcPrecision, jdbcPrecision, true,
                single ? "java.lang.Float" : "java.lang.Double") || !noTextLength(facts)) {
            return inconsistent(typeName);
        }
        if (facts.numericPrecision().state() != MetadataValue.State.VALUE || facts.numericScale()
                .state() != MetadataValue.State.ABSENT) {
            return inconsistent(typeName);
        }
        if (facts.numericPrecision().value() != (single ? 24 : 53)) {
            return inconsistent(typeName);
        }
        // -0.0 与 0 相等且显示为 0；NaN、Infinity 和 -Infinity 可经文本及 Float、Double 往返转换。
        return exact(new FloatType(single ? 32 : 64, false, false, true));
    }

    /**
     * 根据原生列证据推导布尔值的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> bool(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (!driver(column, Types.BOOLEAN, "bool", 1, 0, false, "java.lang.Boolean") || !noTextLength(facts)) {
            return inconsistent("bool");
        }
        if (facts.numericPrecision().state() != MetadataValue.State.ABSENT || facts.numericScale()
                .state() != MetadataValue.State.ABSENT) {
            return inconsistent("bool");
        }
        return exact(new BooleanType());
    }

    /**
     * 根据原生列证据推导字符的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> character(ColumnDescription column, ColumnMetadataEvidence facts,
            boolean fixed, long length) {
        if (length < 1 || length > MAX_CHAR_OCTETS) {
            return inconsistent(fixed ? "bpchar" : "varchar");
        }
        String typeName = fixed ? "bpchar" : "varchar";
        int jdbc = fixed ? Types.CHAR : Types.VARCHAR;
        if (!driver(column, jdbc, typeName, (int) length, 0, false, "java.lang.String")) {
            return inconsistent(typeName);
        }
        if (facts.characterLength().state() != MetadataValue.State.VALUE || facts.characterLength().value() != length) {
            return inconsistent(typeName);
        }
        if (facts.numericPrecision().state() != MetadataValue.State.ABSENT) {
            return inconsistent(typeName);
        }
        // 声明长度按字节计算；仅验证了 character(n) 忽略尾部空格的相等性，列级
        // 排序规则缺失（数据库 lc_collate 为 en_US.utf8），因此使用默认比较规则，而非 C。
        return emptyString(new CharacterType(fixed ? StorageKind.FIXED : StorageKind.VARYING, length, LengthUnit.OCTETS,
                null, "UNICODE", Comparison.DETERMINISTIC_DEFAULT, null, false));
    }

    /**
     * 根据原生列证据推导文本的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> text(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (!driver(column, Types.VARCHAR, "text", DRIVER_UNBOUNDED, 0, false, "java.lang.String")) {
            return inconsistent("text");
        }
        if (facts.characterLength().state() != MetadataValue.State.ABSENT) {
            return inconsistent("text");
        }
        return emptyString(new CharacterType(StorageKind.LARGE, null, LengthUnit.CHARACTERS, (long) DRIVER_UNBOUNDED,
                "UNICODE", Comparison.DETERMINISTIC_DEFAULT, null, false));
    }

    /**
     * 核对驱动和目录事实后映射 PostgreSQL 家族 bytea 二进制类型。
     */
    private TypeMappingResult<LogicalType> bytea(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (!driver(column, Types.BINARY, "bytea", DRIVER_UNBOUNDED, 0, false, "[B") || !noTextLength(facts)) {
            return inconsistent("bytea");
        }
        return exact(new BinaryType(StorageKind.LARGE, null, (long) DRIVER_UNBOUNDED));
    }

    /**
     * 根据原生列证据推导JSON 值的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> json(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (!driver(column, Types.VARCHAR, "json", DRIVER_UNBOUNDED, 0, false, "java.lang.String") || !noTextLength(
                facts)) {
            return inconsistent("json");
        }
        // 存储文本保留空格和 30 位整数；getObject 返回类型为 String。
        return exact(new JsonType(JsonRepresentation.TEXT, JsonNumbers.ARBITRARY_DECIMAL));
    }

    /**
     * 根据原生列证据推导UUID 值的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> uuid(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (!driver(column, Types.OTHER, "uuid", DRIVER_UNBOUNDED, 0, false, "java.util.UUID") || !noTextLength(
                facts)) {
            return inconsistent("uuid");
        }
        return exact(new UuidType());
    }

    /**
     * 根据原生列证据推导时间的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> time(ColumnDescription column, ColumnMetadataEvidence facts,
            String digitsText) {
        int digits = digitsText == null ? 6 : Integer.parseInt(digitsText);
        int precision = 8 + (digits == 0 ? 0 : digits + 1);
        if (!driver(column, Types.TIME, "time", precision, digits, false, "java.sql.Time") || !noTextLength(facts)) {
            return inconsistent("time");
        }
        // 服务器文本和 extract(hour) 保留 24；getString 返回 24:00:00，getObject 或 java.sql.Time 返回 00:00:00。
        var result = exact((LogicalType) new TimeType(digits, false, TimeRange.TIME_OF_DAY_INCLUDING_24_00))
                .loss("TIME_24_00_WRAPPED",
                        "getObject returns 00:00:00 for a stored 24:00:00; getString still returns 24:00:00");
        return digits > 3 ? result.loss("TIME_FRACTION_TRUNCATED",
                "getString keeps microseconds; java.sql.Time keeps milliseconds (12:34:56.123456 -> millis .123)") : result;
    }

    /**
     * 根据原生列证据推导时间戳的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> timestamp(ColumnDescription column, ColumnMetadataEvidence facts,
            String type) {
        var temporal = TIMESTAMP.matcher(type);
        if (!temporal.matches()) {
            return TypeMappingResult.unsupported("NATIVE_TYPE_UNRECOGNIZED", type);
        }
        int digits = temporal.group(1) == null ? 6 : Integer.parseInt(temporal.group(1));
        boolean zone = temporal.group(2).equals("with");
        if (zone && digits == 0) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", "timestamptz(0) JDBC precision was not probed");
        }
        int extra = digits == 0 ? 0 : digits + 1;
        int precision = (zone ? 28 : 22) + extra;
        String typeName = zone ? "timestamptz" : "timestamp";
        if (!driver(column, Types.TIMESTAMP, typeName, precision, digits, false, "java.sql.Timestamp") || !noTextLength(
                facts)) {
            return inconsistent(typeName);
        }
        int maxYear = zone ? MAX_INSTANT_YEAR : MAX_LOCAL_YEAR;
        return exact(
                new TimestampType(digits, zone ? TimeZoneSemantics.INSTANT : TimeZoneSemantics.LOCAL, MIN_YEAR, maxYear,
                        true));
    }

    /**
     * 将逻辑列类型映射为当前产品的原生类型。
     */
    @Override
    public TypeMappingResult<NativeColumnType> toNative(LogicalColumn column) {
        LogicalType type = column.type();
        if (type instanceof IntegerType integer) {
            return integer(integer);
        }
        if (type instanceof DecimalType decimal) {
            return decimal(decimal);
        }
        if (type instanceof FloatType value) {
            return floating(value);
        }
        if (type instanceof BooleanType) {
            return TypeMappingResult.exact(NativeColumnType.of("boolean"));
        }
        if (type instanceof CharacterType text) {
            return character(text, column);
        }
        if (type instanceof BinaryType binary) {
            return binary(binary);
        }
        if (type instanceof DateType date) {
            return date(date);
        }
        if (type instanceof TimeType time) {
            return time(time);
        }
        if (type instanceof TimestampType timestamp) {
            return timestamp(timestamp);
        }
        if (type instanceof UuidType) {
            return TypeMappingResult.exact(NativeColumnType.of("uuid"));
        }
        if (type instanceof JsonType) {
            return TypeMappingResult.exact(NativeColumnType.of("json"));
        }
        return TypeMappingResult.unsupported("TYPE_UNMAPPED", type.canonical() + " has no verified openGauss target");
    }

    /**
     * 根据原生列证据推导整数的逻辑类型及值域。
     */
    private static TypeMappingResult<NativeColumnType> integer(IntegerType integer) {
        if (integer.unsigned() || (integer.bits() != 16 && integer.bits() != 32 && integer.bits() != 64)) {
            return TypeMappingResult.unsupported("INTEGER_WIDTH_UNMAPPED",
                    "only signed smallint/integer/bigint were probed");
        }
        String name = switch (integer.bits()) {
            case 16 -> "smallint";
            case 32 -> "integer";
            default -> "bigint";
        };
        return TypeMappingResult.exact(NativeColumnType.of(name));
    }

    /**
     * 根据原生列证据推导精确小数的逻辑类型及值域。
     */
    private static TypeMappingResult<NativeColumnType> decimal(DecimalType decimal) {
        if (decimal.nonNegative()) {
            return TypeMappingResult.unsupported("NON_NEGATIVE_UNPROBED", "no checked non-negative numeric was probed");
        }
        if (!decimal.bounded()) {
            return nanTarget(NativeColumnType.of("numeric"));
        }
        if (decimal.precision() > 1000 || decimal.scale() < 0 || decimal.scale() > decimal.precision()) {
            return TypeMappingResult.unsupported("NUMERIC_PRECISION_EXCEEDS_TARGET",
                    "numeric precision must be 1..1000");
        }
        return nanTarget(NativeColumnType.of("numeric(" + decimal.precision() + "," + decimal.scale() + ")"));
    }

    /**
     * 根据原生列证据推导浮点数的逻辑类型及值域。
     */
    private static TypeMappingResult<NativeColumnType> floating(FloatType value) {
        if (value.nonNegative()) {
            return TypeMappingResult.unsupported("NON_NEGATIVE_UNPROBED", "no checked non-negative float was probed");
        }
        var result = TypeMappingResult.exact(NativeColumnType.of(value.bits() == 32 ? "real" : "double precision"));
        return value.negativeZero() ? result.loss("NEGATIVE_ZERO_NOT_PRESERVED",
                "-0.0 is stored as 0 on float4 and float8") : result;
    }

    /**
     * 根据原生列证据推导字符的逻辑类型及值域。
     */
    private static TypeMappingResult<NativeColumnType> character(CharacterType text, LogicalColumn column) {
        if (column.key()) {
            return TypeMappingResult.unsupported("TEXT_KEY_EQUALITY_UNPROVEN",
                    "empty string becomes NULL under sql_compatibility A");
        }
        if (text.nulAllowed()) {
            return TypeMappingResult.unsupported("NUL_NOT_PRESERVED", "chr(0) is stored as NULL");
        }
        if (text.comparison() == Comparison.NAMED) {
            return TypeMappingResult.unsupported("COLLATION_UNAVAILABLE", text.collation());
        }
        if (!text.repertoire().equals("UNICODE") && !text.repertoire().equals("UTF8") && !text.repertoire()
                .equals("utf8")) {
            return TypeMappingResult.unsupported("CHARSET_UNVERIFIED", text.repertoire());
        }
        if (text.kind() == StorageKind.LARGE) {
            var large = emptyTarget(NativeColumnType.of("text"), text);
            return text.comparison() == Comparison.BINARY_PAD_SPACE
                    ? large.loss("PAD_SPACE_EQUALITY_CHANGED", "text does not ignore trailing spaces") : large;
        }
        if (text.unit() != LengthUnit.CHARACTERS && text.unit() != LengthUnit.OCTETS) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", text.canonical());
        }
        long bytes = text.unit() == LengthUnit.OCTETS ? text.length()
                : text.length() > MAX_CHAR_OCTETS / UTF8_OCTETS_PER_CHAR ? Long.MAX_VALUE
                : text.length() * UTF8_OCTETS_PER_CHAR;
        if (bytes > MAX_CHAR_OCTETS) {
            return text.kind() == StorageKind.VARYING
                    ? emptyTarget(NativeColumnType.of("text"), text).loss("LENGTH_NOT_ENFORCED",
                    "varchar/char stop at 10485760 octets")
                    : TypeMappingResult.unsupported("LENGTH_EXCEEDS_TARGET", text.canonical());
        }
        String sql = (text.kind() == StorageKind.FIXED ? "character(" : "character varying(") + bytes + ")";
        var result = emptyTarget(NativeColumnType.of(sql), text);
        boolean fixed = text.kind() == StorageKind.FIXED;
        // character(n) 比较时忽略尾部空格，character varying 和 text 则不忽略。
        if (text.comparison() == Comparison.BINARY_NO_PAD && fixed) {
            result = result.loss("PAD_SPACE_EQUALITY_CHANGED", "character(n) ignores trailing spaces");
        } else if (text.comparison() == Comparison.BINARY_PAD_SPACE && !fixed) {
            result = result.loss("PAD_SPACE_EQUALITY_CHANGED", "character varying/text do not ignore trailing spaces");
        }
        return result;
    }

    /**
     * 根据原生列证据推导二进制的逻辑类型及值域。
     */
    private static TypeMappingResult<NativeColumnType> binary(BinaryType binary) {
        if (binary.kind() != StorageKind.LARGE) {
            return TypeMappingResult.unsupported("BINARY_LENGTH_UNPROBED", "only unbounded bytea was probed");
        }
        return TypeMappingResult.exact(NativeColumnType.of("bytea"));
    }

    /**
     * 根据原生列证据推导日期的逻辑类型及值域。
     */
    private static TypeMappingResult<NativeColumnType> date(DateType date) {
        var result = TypeMappingResult.exact(NativeColumnType.of("date"));
        // A 模式的 date 存储为 timestamp(0) without time zone；接受 9999-12-31，不接受 10000-01-01 和 294276。
        return date.minYear() < MIN_YEAR || date.maxYear() > MAX_LOCAL_YEAR
                ? result.loss("DATE_RANGE_NOT_REPRESENTABLE", "A-mode date/timestamp years are -4712..9999") : result;
    }

    /**
     * 根据原生列证据推导时间的逻辑类型及值域。
     */
    private static TypeMappingResult<NativeColumnType> time(TimeType time) {
        if (time.withTimeZone()) {
            return TypeMappingResult.unsupported("TIME_ZONE_SEMANTICS_UNMAPPED", time.canonical());
        }
        if (time.range() == TimeRange.SIGNED_DURATION_838_HOURS) {
            return TypeMappingResult.unsupported("TIME_RANGE_UNMAPPED", "durations outside a day were not probed");
        }
        int digits = Math.min(time.fractionalDigits(), 6);
        var result = TypeMappingResult.exact(NativeColumnType.of("time(" + digits + ") without time zone"));
        if (time.fractionalDigits() > 6) {
            result = result.loss("TIME_FRACTION_TRUNCATED", "time keeps microseconds");
        }
        // 可存储 24:00:00 和 24:00:00.000000，拒绝 24:00:00.100000。
        return time.range() == TimeRange.TIME_OF_DAY_INCLUDING_24_00 && time.fractionalDigits() > 0
                ? result.loss("TIME_24_00_FRACTION_REJECTED",
                "24:00 is accepted only when the fractional seconds are zero") : result;
    }

    /**
     * 根据原生列证据推导时间戳的逻辑类型及值域。
     */
    private static TypeMappingResult<NativeColumnType> timestamp(TimestampType timestamp) {
        if (timestamp.zone() == TimeZoneSemantics.OFFSET) {
            return TypeMappingResult.unsupported("TIME_ZONE_SEMANTICS_UNMAPPED",
                    "no offset-preserving timestamp was probed");
        }
        int digits = Math.min(timestamp.fractionalDigits(), 6);
        boolean instant = timestamp.zone() == TimeZoneSemantics.INSTANT;
        var result = TypeMappingResult.exact(
                NativeColumnType.of("timestamp(" + digits + ") " + (instant ? "with" : "without") + " time zone"));
        if (timestamp.fractionalDigits() > 6) {
            result = result.loss("TIME_FRACTION_TRUNCATED", "timestamps keep microseconds");
        }
        int maxYear = instant ? MAX_INSTANT_YEAR : MAX_LOCAL_YEAR;
        // timestamptz 接受 294277-01-01 但拒绝 294277-12-31，因此上界年份仅有部分日期可用。
        return timestamp.minYear() < MIN_YEAR || timestamp.maxYear() > maxYear
                ? result.loss("DATE_RANGE_NOT_REPRESENTABLE", instant
                ? "timestamptz accepts 294277-01-01 and rejects 294277-12-31; local timestamp stops at 9999"
                : "timestamp without time zone accepts -4712..9999 and rejects year 10000") : result;
    }

    /**
     * 检查元数据来源与已验证的 openGauss 产品、版本和读取方法一致。
     */
    static boolean verified(MetadataProvenance source) {
        return source.product() == ProductTypeEnum.OPENGAUSS && BANNER.equals(source.serverProduct())
                && SERVER_VERSION.equals(source.serverVersion()) && METHOD.equals(source.method());
    }

    /**
     * 解析当前连接或产品使用的驱动配置。
     */
    private static boolean driver(ColumnDescription column, int jdbc, String typeName, int precision, int scale,
            boolean signed, String className) {
        return column.getFieldType() == jdbc && typeName.equals(column.getFieldTypeName())
                && column.getPrecisionSize() == precision && column.getScaleSize() == scale
                && column.isSigned() == signed && className.equals(column.getFiledTypeClassName());
    }

    /**
     * 检查当前目录事实明确未声明字符长度。
     */
    private static boolean noTextLength(ColumnMetadataEvidence facts) {
        return facts.characterLength().state() == MetadataValue.State.ABSENT;
    }

    /**
     * 比较实际回读定义与当前计划定义是否完全一致。
     */
    private static TypeMappingResult<LogicalType> exact(LogicalType type) {
        return TypeMappingResult.exact(type);
    }

    /**
     * 返回当前NaN。
     */
    private static TypeMappingResult<LogicalType> nan(DecimalType type) {
        return exact(type).note("NUMERIC_NAN_READS_AS_DOUBLE",
                "numeric NaN is stored and prints as NaN; getObject returns java.lang.Double and metadata class stays BigDecimal");
    }

    /**
     * 处理当前产品对空字符串的特定值域语义。
     */
    private static TypeMappingResult<LogicalType> emptyString(CharacterType type) {
        return exact(type).loss("EMPTY_STRING_BECOMES_NULL",
                "sql_compatibility A stores '' as NULL in char, varchar and text");
    }

    /**
     * 记录 numeric NaN 在当前驱动中按 Double 回读的映射说明。
     */
    private static TypeMappingResult<NativeColumnType> nanTarget(NativeColumnType type) {
        return TypeMappingResult.exact(type).note("NUMERIC_NAN_READS_AS_DOUBLE",
                "numeric NaN is stored; getObject returns java.lang.Double");
    }

    /**
     * 记录目标数据库将空字符串保存为 NULL 的值域损失。
     */
    private static TypeMappingResult<NativeColumnType> emptyTarget(NativeColumnType type, CharacterType text) {
        var result = TypeMappingResult.exact(type).loss("EMPTY_STRING_BECOMES_NULL",
                "sql_compatibility A stores '' as NULL in char, varchar and text");
        if (text.comparison() == Comparison.DETERMINISTIC_DEFAULT) {
            result = result.note("ORDERING_MAY_DIFFER", "database lc_collate is en_US.utf8");
        }
        return result;
    }

    /**
     * 返回当前不一致。
     */
    private static TypeMappingResult<LogicalType> inconsistent(String type) {
        return TypeMappingResult.unsupported("DRIVER_METADATA_INCONSISTENT",
                "catalog " + type + " disagrees with driver metadata");
    }

    /**
     * 返回元数据证据不完整时的类型映射结果。
     */
    private static TypeMappingResult<LogicalType> incomplete(String property) {
        return TypeMappingResult.unsupported("INCOMPLETE_METADATA",
                property + " was not observed; UNKNOWN cannot be EXACT");
    }
}
