package cn.iocoder.yudao.framework.dbswitch.product.oceanbase;

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
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 通过 Connector/J 2.4.12 观测的 OceanBase CE 4.3.5.6 MySQL 模式事实。
 * 来源依据是固定元组（产品、服务器产品 {@value #SERVER_PRODUCT}、VERSION()
 * {@value #SERVER_VERSION}、方法 {@value #CATALOG_METHOD}）。映射器自行检查该元组。
 * {@code MetadataProvenance#nativeProduct} 接受相同元组。只有完成此固定验证，才允许 JDBC 主版本 5
 * 绕过 MySQL 8 目录版本门禁。绝不接受 Oracle 模式。
 * <p>整数目录 precision 等于显示宽度及驱动 precision。{@code tinyint(1)} 被读取
 * 为 {@code java.lang.Boolean}。FLOAT 驱动 scale 为 31，目录 scale 缺失；DOUBLE 目录
 * precision 为 22，驱动 precision 为 23。Datetime 小数位数取自 {@code datetime(6)}，
 * 因为驱动报告 scale 0。
 */
public final class OceanbaseLogicalTypeMapper implements LogicalTypeMapper {

    static final String SERVER_PRODUCT = "OceanBase";

    static final String SERVER_VERSION = "5.7.25-OceanBase_CE-v4.3.5.6";

    static final String CATALOG_METHOD = "OCEANBASE_CATALOG_V1";

    /** 实际创建的最大 char / varchar / binary / varbinary 长度。 */
    private static final int MAX_PROBED_CHAR = 10, MAX_PROBED_VARCHAR = 64, MAX_PROBED_BINARY = 4, MAX_PROBED_VARBINARY = 16;

    private static final int MAX_DECIMAL_PRECISION = 65, MAX_PROBED_DECIMAL_SCALE = 30;

    private static final long TEXT_OCTETS = 65535L, JSON_OCTETS = 536870911L;

    private static final Pattern INTEGER = Pattern.compile(
            "(tinyint|smallint|mediumint|int|bigint)\\(([0-9]{1,3})\\)( unsigned)?");

    private static final Pattern DECIMAL = Pattern.compile("decimal\\(([0-9]{1,2}),([0-9]{1,2})\\)");

    private static final Pattern SIZED = Pattern.compile("(char|varchar|binary|varbinary)\\(([0-9]{1,6})\\)");

    private static final Pattern TEMPORAL = Pattern.compile("(datetime|timestamp|time)(?:\\(([0-6])\\))?");

    /**
     * 初始化 {@code OceanbaseLogicalTypeMapper}。
     */
    public OceanbaseLogicalTypeMapper() {
    }

    /**
     * 返回当前操作使用的数据库产品。
     */
    @Override
    public ProductTypeEnum product() {
        return ProductTypeEnum.OCEANBASE;
    }

    /**
     * 将数据库原生列类型映射为逻辑类型。
     */
    @Override
    public TypeMappingResult<LogicalType> toLogical(ColumnDescription column) {
        ColumnMetadataEvidence facts = column.getMetadataEvidence();
        if (!pinned(facts.provenance())) {
            return TypeMappingResult.unsupported("SOURCE_PROVENANCE_UNVERIFIED",
                    "OCEANBASE_CATALOG_V1 MySQL-mode " + SERVER_VERSION + " required");
        }
        if (facts.nativeType().state() != MetadataValue.State.VALUE) {
            return incomplete("native type");
        }
        String type = facts.nativeType().value().trim().toLowerCase(Locale.ROOT);
        if (type.endsWith(" zerofill")) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", "zerofill was not created");
        }
        if (type.equals("tinyint(1)")) {
            return bool(column, facts);
        }
        var integer = INTEGER.matcher(type);
        if (integer.matches()) {
            return integer(column, facts, integer.group(1), Integer.parseInt(integer.group(2)),
                    integer.group(3) != null, type);
        }
        var decimal = DECIMAL.matcher(type);
        if (decimal.matches()) {
            return decimal(column, facts, Integer.parseInt(decimal.group(1)), Integer.parseInt(decimal.group(2)), type);
        }
        if (type.equals("float") || type.equals("double")) {
            return floating(column, facts, type.equals("float"), type);
        }
        if (type.startsWith("float(") || type.startsWith("double(")) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", "precision-qualified floating point was not created");
        }
        var sized = SIZED.matcher(type);
        if (sized.matches()) {
            long length = Long.parseLong(sized.group(2));
            return switch (sized.group(1)) {
                case "char" -> character(column, facts, StorageKind.FIXED, length, type);
                case "varchar" -> character(column, facts, StorageKind.VARYING, length, type);
                case "binary" -> binary(column, facts, StorageKind.FIXED, length, type);
                default -> binary(column, facts, StorageKind.VARYING, length, type);
            };
        }
        if (type.equals("text")) {
            return text(column, facts);
        }
        if (type.equals("blob")) {
            return blob(column, facts);
        }
        if (type.equals("date")) {
            return date(column, facts);
        }
        if (type.equals("json")) {
            return json(column, facts);
        }
        var temporal = TEMPORAL.matcher(type);
        if (temporal.matches()) {
            return temporal(column, facts, temporal.group(1), temporal.group(2), type);
        }
        return TypeMappingResult.unsupported("NATIVE_TYPE_UNRECOGNIZED", type);
    }

    /**
     * 根据原生列证据推导布尔值的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> bool(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (!jdbc(column, Types.BIT, "TINYINT", "java.lang.Boolean", 1, 0) || !column.isSigned() || !number(facts, 1,
                0) || !noCharacterFacts(facts)) {
            return inconsistent("tinyint(1)");
        }
        // 值 5 和 1 均回读为 Boolean.TRUE。未读到存储的整数：CAST 缺少 FROM。
        return exact(new BooleanType()).loss("TINYINT1_NONZERO_READ_AS_TRUE",
                "Connector/J 2.4.12 reads tinyint(1) and boolean as Boolean; 5 and 1 are indistinguishable");
    }

    /**
     * 根据原生列证据推导整数的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> integer(ColumnDescription column, ColumnMetadataEvidence facts, String base,
            int width, boolean unsigned, String type) {
        int expectedWidth = switch (base) {
            case "tinyint" -> unsigned ? 3 : 4;
            case "smallint" -> unsigned ? 5 : 6;
            case "mediumint" -> unsigned ? 8 : 9;
            case "int" -> unsigned ? 10 : 11;
            default -> 20;
        };
        if (width != expectedWidth) {
            return TypeMappingResult.unsupported("DISPLAY_WIDTH_UNVERIFIED", type + " display width was not created");
        }
        int bits = switch (base) {
            case "tinyint" -> 8;
            case "smallint" -> 16;
            case "mediumint" -> 24;
            case "int" -> 32;
            default -> 64;
        };
        int jdbcType = switch (base) {
            case "tinyint" -> Types.TINYINT;
            case "smallint" -> Types.SMALLINT;
            case "bigint" -> Types.BIGINT;
            default -> Types.INTEGER;
        };
        String typeName = switch (base) {
            case "tinyint" -> "TINYINT";
            case "smallint" -> "SMALLINT";
            case "mediumint" -> "MEDIUMINT";
            case "int" -> "INT";
            default -> "BIGINT";
        } + (unsigned ? " UNSIGNED" : "");
        String className = switch (base) {
            case "bigint" -> unsigned ? "java.math.BigInteger" : "java.lang.Long";
            case "int" -> unsigned ? "java.lang.Long" : "java.lang.Integer";
            default -> "java.lang.Integer";
        };
        if (!jdbc(column, jdbcType, typeName, className, width, 0) || column.isSigned() == unsigned || !number(facts,
                width, 0) || !noCharacterFacts(facts)) {
            return inconsistent(type);
        }
        var result = exact(new IntegerType(bits, unsigned));
        return partialIntegerBounds(bits, unsigned) ? result.note("INTEGER_BOUNDS_PARTIALLY_PROBED",
                partialIntegerDetail(bits, unsigned)) : result;
    }

    /**
     * 根据原生列证据推导精确小数的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> decimal(ColumnDescription column, ColumnMetadataEvidence facts,
            int precision, int scale, String type) {
        if (precision < 1 || precision > MAX_DECIMAL_PRECISION || scale > precision) {
            return TypeMappingResult.unsupported("NUMERIC_PRECISION_EXCEEDS_TARGET", type);
        }
        if (scale > MAX_PROBED_DECIMAL_SCALE) {
            return TypeMappingResult.unsupported("NUMERIC_SCALE_UNVERIFIED",
                    "decimal scale 30 was created; a larger scale was not");
        }
        if (!jdbc(column, Types.DECIMAL, "DECIMAL", "java.math.BigDecimal", precision,
                scale) || !column.isSigned() || !number(facts, precision, scale) || !noCharacterFacts(facts)) {
            return inconsistent(type);
        }
        return exact(new DecimalType(precision, scale, false, false));
    }

    /**
     * 根据原生列证据推导浮点数的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> floating(ColumnDescription column, ColumnMetadataEvidence facts,
            boolean single, String type) {
        int catalogPrecision = single ? 12 : 22;
        int driverPrecision = single ? 12 : 23;
        if (!jdbc(column, single ? Types.REAL : Types.DOUBLE, single ? "FLOAT" : "DOUBLE",
                single ? "java.lang.Float" : "java.lang.Double", driverPrecision,
                31) || !column.isSigned() || !noCharacterFacts(facts)) {
            return inconsistent(type);
        }
        if (facts.numericPrecision().state() != MetadataValue.State.VALUE || facts.numericScale()
                .state() != MetadataValue.State.ABSENT) {
            return inconsistent(type);
        }
        if (facts.numericPrecision().value() != catalogPrecision || facts.characterLength()
                .state() != MetadataValue.State.ABSENT) {
            return inconsistent(type);
        }
        // 两种宽度的 -0.0 均变为 +0。仅对 double 验证了 NaN 和 Infinity 被拒绝。
        return exact(new FloatType(single ? 32 : 64, false, false, false));
    }

    /**
     * 根据原生列证据推导字符的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> character(ColumnDescription column, ColumnMetadataEvidence facts,
            StorageKind kind, long length, String type) {
        if (length < 1) {
            return inconsistent(type);
        }
        int jdbcType = kind == StorageKind.FIXED ? Types.CHAR : Types.VARCHAR;
        String typeName = kind == StorageKind.FIXED ? "CHAR" : "VARCHAR";
        if (!jdbc(column, jdbcType, typeName, "java.lang.String", (int) length, 0)) {
            return inconsistent(type);
        }
        if (facts.charset().state() != MetadataValue.State.VALUE || facts.collation()
                .state() != MetadataValue.State.VALUE
                || facts.characterLength().state() != MetadataValue.State.VALUE) {
            return incomplete("charset/collation/length");
        }
        if (facts.characterLength().value() != length || facts.numericPrecision()
                .state() != MetadataValue.State.ABSENT) {
            return inconsistent(type);
        }
        String charset = facts.charset().value().toLowerCase(Locale.ROOT);
        String collation = facts.collation().value().toLowerCase(Locale.ROOT);
        if (!charset.equals("utf8mb4")) {
            return TypeMappingResult.unsupported("CHARSET_UNVERIFIED", charset);
        }
        Comparison comparison;
        TypeMappingResult<LogicalType> result;
        if (collation.equals("utf8mb4_bin")) {
            comparison = Comparison.BINARY_PAD_SPACE;
            result = exact(characterType(kind, length, comparison, collation))
                    .note("BIN_CASE_FOLD_UNPROBED", "utf8mb4_bin padded spaces; 'A' versus 'a' was not compared");
        } else if (collation.equals("utf8mb4_general_ci")) {
            comparison = Comparison.NAMED;
            result = exact(characterType(kind, length, comparison, collation));
        } else {
            return TypeMappingResult.unsupported("COLLATION_UNVERIFIED", collation);
        }
        return result;
    }

    /**
     * 规范化字符类型名称的大小写。
     */
    private static CharacterType characterType(StorageKind kind, long length, Comparison comparison, String collation) {
        // varchar(5) utf8mb4 接受 5 个汉字（15 字节）和一个 U+10348（F0908D88），拒绝 6 个字符。
        return new CharacterType(kind, length, LengthUnit.CHARACTERS, null, "utf8mb4", comparison, collation, false);
    }

    /**
     * 根据原生列证据推导文本的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> text(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (!jdbc(column, Types.LONGVARCHAR, "VARCHAR", "java.lang.String", (int) TEXT_OCTETS, 0)) {
            return inconsistent("text");
        }
        if (facts.characterLength().state() != MetadataValue.State.VALUE || facts.characterLength()
                .value() != TEXT_OCTETS) {
            return inconsistent("text");
        }
        if (facts.charset().state() != MetadataValue.State.VALUE || facts.collation()
                .state() != MetadataValue.State.VALUE) {
            return incomplete("text charset");
        }
        if (!facts.charset().value().equalsIgnoreCase("utf8mb4") || !facts.collation().value()
                .equalsIgnoreCase("utf8mb4_general_ci")) {
            return TypeMappingResult.unsupported("COLLATION_UNVERIFIED", "text was created with utf8mb4_general_ci");
        }
        // 目录字符长度及字节长度均为 65535，所以它不是字符长度。
        return exact(new CharacterType(StorageKind.LARGE, null, LengthUnit.OCTETS, TEXT_OCTETS, "utf8mb4",
                Comparison.NAMED, "utf8mb4_general_ci", false));
    }

    /**
     * 根据原生列证据推导二进制的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> binary(ColumnDescription column, ColumnMetadataEvidence facts,
            StorageKind kind, long length, String type) {
        if (length < 1) {
            return inconsistent(type);
        }
        int jdbcType = kind == StorageKind.FIXED ? Types.BINARY : Types.VARBINARY;
        String typeName = kind == StorageKind.FIXED ? "BINARY" : "VARBINARY";
        if (!jdbc(column, jdbcType, typeName, "[B", (int) length, 0) || !noCharacterFacts(facts)) {
            return inconsistent(type);
        }
        if (facts.characterLength().state() != MetadataValue.State.VALUE || facts.characterLength().value() != length) {
            return inconsistent(type);
        }
        return exact(new BinaryType(kind, length, null)).note("BINARY_PAYLOAD_UNPROBED",
                "binary and varbinary metadata only; no payload was inserted");
    }

    /**
     * 返回当前二进制大对象。
     */
    private TypeMappingResult<LogicalType> blob(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (!jdbc(column, Types.LONGVARBINARY, "BLOB", "[B", (int) TEXT_OCTETS, 0) || !noCharacterFacts(facts)) {
            return inconsistent("blob");
        }
        if (facts.characterLength().state() != MetadataValue.State.VALUE || facts.characterLength()
                .value() != TEXT_OCTETS) {
            return inconsistent("blob");
        }
        return exact(new BinaryType(StorageKind.LARGE, null, TEXT_OCTETS))
                .note("BINARY_PAYLOAD_UNPROBED", "blob metadata only; no payload was inserted");
    }

    /**
     * 根据原生列证据推导日期的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> date(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (!jdbc(column, Types.DATE, "DATE", "java.sql.Date", 10, 0) || !noCharacterFacts(facts) || !numericAbsent(
                facts)) {
            return inconsistent("date");
        }
        return exact(new DateType(0, 9999, false))
                .loss("ZERO_DATE_READ_AS_NULL",
                        "0000-00-00 is stored; Connector/J 2.4.12 getObject returns null and CAST returns 0000-00-00")
                .note("DATE_YEAR_BOUNDS_UNPROBED", "only the zero date was inserted; 0001 and 9999 were not");
    }

    /**
     * 根据原生列证据推导JSON 值的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> json(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (!jdbc(column, Types.VARCHAR, "JSON", "java.lang.String", -1, 0) || !noCharacterFacts(facts)) {
            return inconsistent("json");
        }
        if (facts.characterLength().state() != MetadataValue.State.VALUE || facts.characterLength()
                .value() != JSON_OCTETS) {
            return inconsistent("json");
        }
        // 键顺序、空白及尾部零改变。未单独区分 Binary64 与 decimal。
        return TypeMappingResult.unsupported("JSON_NUMBER_DOMAIN_UNVERIFIED",
                "json text was normalized; 0.1 and 2^53+1 were not inserted, so the number domain is not mapped");
    }

    /**
     * 根据原生列证据推导时间值的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> temporal(ColumnDescription column, ColumnMetadataEvidence facts, String base,
            String digits, String type) {
        return switch (base) {
            case "datetime" -> datetime(column, facts, digits, type);
            case "timestamp" -> timestamp(column, facts, digits, type);
            default -> time(column, facts, digits, type);
        };
    }

    /**
     * 按当前产品类型约定解析日期时间值。
     */
    private TypeMappingResult<LogicalType> datetime(ColumnDescription column, ColumnMetadataEvidence facts,
            String digits, String type) {
        if (!"6".equals(digits)) {
            return TypeMappingResult.unsupported("DATETIME_FRACTION_UNVERIFIED", type + " was not created");
        }
        if (!jdbc(column, Types.TIMESTAMP, "DATETIME", "java.sql.Timestamp", 26, 0) || !noCharacterFacts(
                facts) || !numericAbsent(facts)) {
            return inconsistent(type);
        }
        return exact(new TimestampType(6, TimeZoneSemantics.LOCAL, 0, 9999, false))
                .loss("ZERO_DATETIME_READ_AS_0002_11_30",
                        "getObject of 0000-00-00 00:00:00 returned 0002-11-30; CAST kept 0000-00-00 00:00:00.000000")
                .note("DATETIME_NORMAL_GETOBJECT_UNPROBED",
                        "2020-01-01 00:00:00.123456 was read with CAST, not getObject")
                .note("DATE_YEAR_BOUNDS_UNPROBED", "years 0001 and 9999 were not inserted");
    }

    /**
     * 根据原生列证据推导时间戳的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> timestamp(ColumnDescription column, ColumnMetadataEvidence facts,
            String digits, String type) {
        if ("6".equals(digits) && (!jdbc(column, Types.TIMESTAMP, "TIMESTAMP", "java.sql.Timestamp", 26,
                0) || !noCharacterFacts(facts) || !numericAbsent(facts))) {
            return inconsistent(type);
        }
        // 仅插入了零时间戳，未比较会话时区与系统时区。
        return TypeMappingResult.unsupported("TIMESTAMP_VALUE_UNPROBED", "no non-zero timestamp value was read back");
    }

    /**
     * 根据原生列证据推导时间的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> time(ColumnDescription column, ColumnMetadataEvidence facts, String digits,
            String type) {
        if ("6".equals(digits) && (!jdbc(column, Types.TIME, "TIME", "java.sql.Time", 17, 0) || !noCharacterFacts(
                facts) || !numericAbsent(facts))) {
            return inconsistent(type);
        }
        return TypeMappingResult.unsupported("TIME_VALUE_UNPROBED",
                "time(6) metadata only; no time value was inserted");
    }

    /**
     * 将逻辑列类型映射为当前产品的原生类型。
     */
    @Override
    public TypeMappingResult<NativeColumnType> toNative(LogicalColumn column) {
        LogicalType type = column.type();
        if (type instanceof IntegerType integer) {
            String name = switch (integer.bits()) {
                case 8 -> "tinyint";
                case 16 -> "smallint";
                case 24 -> "mediumint";
                case 32 -> "int";
                default -> "bigint";
            } + (integer.unsigned() ? " unsigned" : "");
            var result = TypeMappingResult.exact(NativeColumnType.of(name));
            return partialIntegerBounds(integer.bits(), integer.unsigned())
                    ? result.note("INTEGER_BOUNDS_PARTIALLY_PROBED",
                    partialIntegerDetail(integer.bits(), integer.unsigned())) : result;
        }
        if (type instanceof DecimalType decimal) {
            return decimal(decimal);
        }
        if (type instanceof FloatType value) {
            return floating(value);
        }
        if (type instanceof BooleanType) {
            return TypeMappingResult.exact(NativeColumnType.of("tinyint(1)"))
                    .note("TINYINT1_ACCEPTS_OTHER_INTEGERS",
                            "the physical column still accepts integers other than 0 and 1");
        }
        if (type instanceof CharacterType text) {
            return character(text, column);
        }
        if (type instanceof BinaryType binary) {
            return binary(binary);
        }
        if (type instanceof DateType date) {
            var result = TypeMappingResult.exact(NativeColumnType.of("date"))
                    .note("DATE_YEAR_BOUNDS_UNPROBED", "only the zero date was inserted; 0001 and 9999 were not");
            return date.infinity() || date.minYear() < 0 || date.maxYear() > 9999
                    ? result.loss("DATE_RANGE_NOT_REPRESENTABLE",
                    "values outside the disclosed 0000..9999 window were not probed") : result;
        }
        if (type instanceof TimeType) {
            return TypeMappingResult.unsupported("TIME_VALUE_UNPROBED", "no OceanBase time value was inserted");
        }
        if (type instanceof TimestampType timestamp) {
            return timestamp(timestamp);
        }
        if (type instanceof JsonType) {
            return TypeMappingResult.unsupported("JSON_NUMBER_DOMAIN_UNVERIFIED",
                    "json number domain was not isolated");
        }
        if (type instanceof UuidType) {
            return TypeMappingResult.unsupported("UUID_TYPE_UNAVAILABLE", "no native uuid was probed");
        }
        return TypeMappingResult.unsupported("TYPE_UNMAPPED",
                type.canonical() + " has no verified OceanBase MySQL-mode target");
    }

    /**
     * 根据原生列证据推导精确小数的逻辑类型及值域。
     */
    private TypeMappingResult<NativeColumnType> decimal(DecimalType decimal) {
        if (!decimal.bounded()) {
            return TypeMappingResult.unsupported("UNBOUNDED_NUMERIC_UNMAPPED", "no unbounded exact numeric");
        }
        if (decimal.nonNegative()) {
            return TypeMappingResult.unsupported("DECIMAL_SIGN_UNVERIFIED", "decimal unsigned was not created");
        }
        if (decimal.precision() > MAX_DECIMAL_PRECISION || decimal.scale() > decimal.precision()) {
            return TypeMappingResult.unsupported("NUMERIC_PRECISION_EXCEEDS_TARGET", decimal.canonical());
        }
        if (decimal.scale() < 0 || decimal.scale() > MAX_PROBED_DECIMAL_SCALE) {
            return TypeMappingResult.unsupported("NUMERIC_SCALE_UNVERIFIED",
                    "negative scale is a syntax error; scale above 30 was not created");
        }
        if (decimal.nan()) {
            return TypeMappingResult.unsupported("DECIMAL_NAN_UNVERIFIED",
                    "NaN was rejected on double, not on decimal");
        }
        return TypeMappingResult.exact(
                NativeColumnType.of("decimal(" + decimal.precision() + "," + decimal.scale() + ")"));
    }

    /**
     * 根据原生列证据推导浮点数的逻辑类型及值域。
     */
    private TypeMappingResult<NativeColumnType> floating(FloatType value) {
        if (value.nonNegative()) {
            return TypeMappingResult.unsupported("FLOAT_SIGN_UNVERIFIED", "unsigned float and double were not created");
        }
        if (value.bits() == 32 && value.nonFinite()) {
            return TypeMappingResult.unsupported("FLOAT32_NON_FINITE_UNVERIFIED",
                    "NaN and Infinity were rejected on double only");
        }
        var result = TypeMappingResult.exact(NativeColumnType.of(value.bits() == 32 ? "float" : "double"));
        if (value.negativeZero()) {
            result = result.loss("NEGATIVE_ZERO_NOT_PRESERVED", "-0.0 is stored as 0");
        }
        return value.bits() == 64 && value.nonFinite()
                ? result.loss("NON_FINITE_NOT_REPRESENTABLE",
                "NaN and Infinity inserts were rejected on double") : result;
    }

    /**
     * 根据原生列证据推导字符的逻辑类型及值域。
     */
    private TypeMappingResult<NativeColumnType> character(CharacterType text, LogicalColumn column) {
        if (column.key()) {
            return TypeMappingResult.unsupported("TEXT_KEY_EQUALITY_UNPROVEN", "character key equality was not probed");
        }
        if (text.unit() != LengthUnit.CHARACTERS) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED",
                    "text is 65535 octets; a character large object was not probed");
        }
        if (text.nulAllowed()) {
            return TypeMappingResult.unsupported("NUL_CHARACTER_UNPROBED", "U+0000 was not inserted");
        }
        if (!text.repertoire().equals("utf8mb4") && !text.repertoire().equals("UNICODE")) {
            return TypeMappingResult.unsupported("CHARSET_UNVERIFIED", text.repertoire());
        }
        if (text.kind() == StorageKind.LARGE) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", "a character large object was not probed");
        }
        long limit = text.kind() == StorageKind.FIXED ? MAX_PROBED_CHAR : MAX_PROBED_VARCHAR;
        if (text.length() > limit) {
            return TypeMappingResult.unsupported("LENGTH_CAP_UNVERIFIED",
                    "created maximum is " + limit + " characters");
        }
        String collation;
        TypeMappingResult<NativeColumnType> result;
        String base = (text.kind() == StorageKind.FIXED ? "char(" : "varchar(") + text.length() + ")";
        switch (text.comparison()) {
            case BINARY_PAD_SPACE -> collation = "utf8mb4_bin";
            case BINARY_NO_PAD -> collation = "utf8mb4_bin";
            case DETERMINISTIC_DEFAULT -> collation = "utf8mb4_bin";
            default -> {
                if (!"utf8mb4_general_ci".equals(text.collation()) && !"utf8mb4_bin".equals(text.collation())) {
                    return TypeMappingResult.unsupported("COLLATION_UNAVAILABLE", String.valueOf(text.collation()));
                }
                collation = text.collation();
            }
        }
        result = TypeMappingResult.exact(NativeColumnType.of(base + " CHARACTER SET utf8mb4 COLLATE " + collation));
        boolean sourcePads = text.comparison() == Comparison.BINARY_PAD_SPACE || text.comparison() == Comparison.NAMED
                || (text.comparison() == Comparison.DETERMINISTIC_DEFAULT && text.kind() == StorageKind.FIXED);
        if (!sourcePads) {
            result = result.loss("PAD_SPACE_EQUALITY_CHANGED", "utf8mb4_bin treats trailing spaces as equal");
        }
        if (collation.equals("utf8mb4_bin")) {
            result = result.note("BIN_CASE_FOLD_UNPROBED", "utf8mb4_bin case folding was not compared");
        }
        if (text.repertoire().equals("UNICODE")) {
            result = result.note("UTF8MB4_UNICODE_COVERAGE_UNPROBED",
                    "one supplementary-plane character was stored; Unicode coverage was not proven");
        }
        return result;
    }

    /**
     * 根据原生列证据推导二进制的逻辑类型及值域。
     */
    private TypeMappingResult<NativeColumnType> binary(BinaryType binary) {
        String name = switch (binary.kind()) {
            case FIXED -> binary.length() <= MAX_PROBED_BINARY ? "binary(" + binary.length() + ")" : null;
            case VARYING -> binary.length() <= MAX_PROBED_VARBINARY ? "varbinary(" + binary.length() + ")" : null;
            case LARGE -> "blob";
        };
        if (name == null) {
            return TypeMappingResult.unsupported("LENGTH_CAP_UNVERIFIED", binary.canonical());
        }
        var result = TypeMappingResult.exact(NativeColumnType.of(name))
                .note("BINARY_PAYLOAD_UNPROBED", "no binary payload was inserted");
        return binary.kind() == StorageKind.LARGE && binary.maxOctets() > TEXT_OCTETS
                ? result.note("VALUE_SIZE_LIMITED_BY_TARGET",
                "blob metadata length is 65535 octets; a larger value was not inserted") : result;
    }

    /**
     * 根据原生列证据推导时间戳的逻辑类型及值域。
     */
    private TypeMappingResult<NativeColumnType> timestamp(TimestampType timestamp) {
        if (timestamp.zone() != TimeZoneSemantics.LOCAL) {
            return TypeMappingResult.unsupported("TIME_ZONE_SEMANTICS_UNMAPPED",
                    "session +08:00 was not compared with system +00:00");
        }
        if (timestamp.fractionalDigits() != 6) {
            return TypeMappingResult.unsupported("DATETIME_FRACTION_UNVERIFIED", "only datetime(6) was created");
        }
        var result = TypeMappingResult.exact(NativeColumnType.of("datetime(6)"))
                .note("DATETIME_NORMAL_GETOBJECT_UNPROBED", "a non-zero datetime was checked with CAST, not getObject");
        if (timestamp.infinity() || timestamp.minYear() < 0 || timestamp.maxYear() > 9999) {
            result = result.loss("DATE_RANGE_NOT_REPRESENTABLE",
                    "values outside the disclosed 0000..9999 window were not probed");
        }
        if (timestamp.minYear() <= 0) {
            result = result.loss("ZERO_DATETIME_READ_AS_0002_11_30",
                    "driver getObject of the zero datetime returned 0002-11-30");
        }
        return result;
    }

    /**
     * 判断当前整数位宽是否只有部分边界得到验证。
     */
    private static boolean partialIntegerBounds(int bits, boolean unsigned) {
        if (unsigned) {
            return bits == 16 || bits == 24;
        }
        return bits == 16 || bits == 24 || bits == 32;
    }

    /**
     * 说明当前整数值域尚未验证的边界。
     */
    private static String partialIntegerDetail(int bits, boolean unsigned) {
        if (bits == 24) {
            return "no mediumint value was inserted";
        }
        if (unsigned) {
            return "no unsigned " + bits + "-bit value was inserted";
        }
        return "only the most negative " + bits + "-bit value was inserted";
    }

    /**
     * 判断或取得已经固定的源端对象代次。
     */
    private static boolean pinned(MetadataProvenance provenance) {
        return provenance.product() == ProductTypeEnum.OCEANBASE
                && SERVER_PRODUCT.equals(provenance.serverProduct())
                && SERVER_VERSION.equals(provenance.serverVersion())
                && CATALOG_METHOD.equals(provenance.method());
    }

    /**
     * 根据原生列证据推导数值的逻辑类型及值域。
     */
    private static boolean number(ColumnMetadataEvidence facts, int precision, int scale) {
        return facts.numericPrecision().state() == MetadataValue.State.VALUE
                && facts.numericScale().state() == MetadataValue.State.VALUE
                && facts.numericPrecision().value() == precision && facts.numericScale().value() == scale
                && facts.characterLength().state() == MetadataValue.State.ABSENT;
    }

    /**
     * 判断数值精度和小数位数证据是否均明确缺失。
     */
    private static boolean numericAbsent(ColumnMetadataEvidence facts) {
        return facts.numericPrecision().state() == MetadataValue.State.ABSENT
                && facts.numericScale().state() == MetadataValue.State.ABSENT
                && facts.characterLength().state() == MetadataValue.State.ABSENT;
    }

    /**
     * 检查当前元数据未声明字符类型专属属性。
     */
    private static boolean noCharacterFacts(ColumnMetadataEvidence facts) {
        return facts.charset().state() == MetadataValue.State.ABSENT && facts.collation()
                .state() == MetadataValue.State.ABSENT;
    }

    /**
     * 校验原生列报告的 JDBC 类型是否属于允许范围。
     */
    private static boolean jdbc(ColumnDescription column, int jdbcType, String typeName, String className,
            int precision, int scale) {
        return column.getFieldType() == jdbcType && typeName.equals(column.getFieldTypeName())
                && className.equals(column.getFiledTypeClassName())
                && column.getPrecisionSize() == precision && column.getScaleSize() == scale;
    }

    /**
     * 比较实际回读定义与当前计划定义是否完全一致。
     */
    private static TypeMappingResult<LogicalType> exact(LogicalType type) {
        return TypeMappingResult.exact(type);
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
        return TypeMappingResult.unsupported("INCOMPLETE_METADATA", property + " was not observed");
    }
}
