package cn.iocoder.yudao.framework.dbswitch.product.mysql;

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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 共享 MySQL 家族实现，包含明确的逐产品事实，均已在真实服务端观测：
 * MySQL 8.4 与 TiDB 8.5 使用 Connector/J 9.x，MariaDB 11.4 使用 MariaDB Connector/J 3.5。
 * <ul>
 * <li>整数的目录精度与驱动精度不同：MariaDB 驱动精度跟随显示宽度，TiDB 的
 * 目录 NUMERIC_PRECISION 为隐藏显示宽度；TiDB 对无符号 DECIMAL 报告驱动精度 p+1。</li>
 * <li>FLOAT 读取为六位有效数字（MySQL/MariaDB），或在标准范围极限附近写入不精确
 * （TiDB）；家族内所有写入将 -0.0 规范为 0，只有 MySQL 可保存 -0 DOUBLE。</li>
 * <li>MariaDB 驱动在 JVM 时区内将 DATETIME 物化为 java.sql.Timestamp，对负值或超过 24 小时的 TIME
 * 进行回绕，并静默改写零日期或部分日期；Connector/J 返回 LocalDateTime，并拒绝这些值。</li>
 * <li>TIMESTAMP 时刻按 JVM 时区读写，而会话时区可能不同，已观察到 8 小时偏移。</li>
 * <li>三个产品的 utf8mb4_bin 均为 PAD SPACE；utf8mb4_0900_bin（MySQL/TiDB）及 utf8mb4_nopad_bin（MariaDB）
 * 采用 NO PAD 码点排序规则。TiDB 不执行 CHECK 约束，因此本映射器绝不依赖 CHECK。</li>
 * </ul>
 */
public final class MysqlFamilyLogicalTypeMapper implements LogicalTypeMapper {

    public enum Flavor {
        MYSQL(ProductTypeEnum.MYSQL, "utf8mb4_0900_bin",
                Set.of("utf8mb4_general_ci", "utf8mb4_unicode_ci", "utf8mb4_0900_ai_ci")),
        MARIADB(ProductTypeEnum.MARIADB, "utf8mb4_nopad_bin",
                Set.of("utf8mb4_general_ci", "utf8mb4_unicode_ci", "utf8mb4_0900_ai_ci", "utf8mb4_uca1400_ai_ci")),
        TIDB(ProductTypeEnum.TIDB, "utf8mb4_0900_bin",
                Set.of("utf8mb4_general_ci", "utf8mb4_unicode_ci", "utf8mb4_0900_ai_ci"));

        private final ProductTypeEnum product;

        private final String noPadBinaryCollation;

        private final Set<String> namedCollations;

        /**
         * 初始化 {@code Flavor}。
         */
        Flavor(ProductTypeEnum product, String noPadBinaryCollation, Set<String> namedCollations) {
            this.product = product;
            this.noPadBinaryCollation = noPadBinaryCollation;
            this.namedCollations = namedCollations;
        }
    }

    private static final Pattern INTEGER = Pattern.compile(
            "(tinyint|smallint|mediumint|int|bigint)(?:\\(([0-9]{1,3})\\))?( unsigned)?");

    private static final Pattern DECIMAL = Pattern.compile("decimal\\(([0-9]{1,2}),\\s*([0-9]{1,2})\\)( unsigned)?");

    private static final Pattern FLOATING = Pattern.compile("(float|double)( unsigned)?");

    private static final Pattern STRING = Pattern.compile("(char|varchar|binary|varbinary)\\(([0-9]{1,5})\\)");

    private static final Pattern TEMPORAL = Pattern.compile("(datetime|timestamp|time)(?:\\(([0-6])\\))?");

    private static final Map<String, Long> LARGE = Map.of("tinytext", 255L, "text", 65535L, "mediumtext", 16777215L,
            "longtext", 4294967295L, "tinyblob", 255L, "blob", 65535L, "mediumblob", 16777215L, "longblob",
            4294967295L);

    private final Flavor flavor;

    /**
     * 校验构造参数并初始化 {@code MysqlFamilyLogicalTypeMapper}。
     */
    public MysqlFamilyLogicalTypeMapper(Flavor flavor) {
        this.flavor = Objects.requireNonNull(flavor, "flavor");
    }

    /**
     * 返回当前操作使用的数据库产品。
     */
    @Override
    public ProductTypeEnum product() {
        return flavor.product;
    }

    /**
     * 返回变体。
     */
    public Flavor flavor() {
        return flavor;
    }

    /**
     * 将数据库原生列类型映射为逻辑类型。
     */
    @Override
    public TypeMappingResult<LogicalType> toLogical(ColumnDescription column) {
        ColumnMetadataEvidence facts = column.getMetadataEvidence();
        if (!verifiedSource(facts.provenance())) {
            return TypeMappingResult.unsupported("SOURCE_PROVENANCE_UNVERIFIED",
                    flavor.product + " native catalog provenance required");
        }
        String type = facts.nativeType().value().trim().toLowerCase(Locale.ROOT);
        if (type.endsWith(" zerofill")) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", "zerofill display semantics");
        }
        var integer = INTEGER.matcher(type);
        if (integer.matches()) {
            return integer(column, facts, integer.group(1), integer.group(2), integer.group(3) != null);
        }
        var decimal = DECIMAL.matcher(type);
        if (decimal.matches()) {
            return decimal(column, facts, Integer.parseInt(decimal.group(1)), Integer.parseInt(decimal.group(2)),
                    decimal.group(3) != null);
        }
        var floating = FLOATING.matcher(type);
        if (floating.matches()) {
            return floating(column, facts, floating.group(1).equals("float"), floating.group(2) != null);
        }
        if (type.matches("(float|double)\\([0-9]+,[0-9]+\\)( unsigned)?")) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", "deprecated fixed-decimals floating point");
        }
        if (flavor == Flavor.MARIADB && type.equals("longtext") && "JSON".equalsIgnoreCase(column.getFieldTypeName())) {
            return exact(new JsonType(JsonRepresentation.TEXT, JsonNumbers.ARBITRARY_DECIMAL));
        }
        var string = STRING.matcher(type);
        if (string.matches()) {
            long length = Long.parseLong(string.group(2));
            return string.group(1).endsWith("char") ? character(column, facts,
                    string.group(1).equals("char") ? StorageKind.FIXED : StorageKind.VARYING, length, type)
                    : binary(column, facts, string.group(1).equals("binary") ? StorageKind.FIXED : StorageKind.VARYING,
                    length, type);
        }
        if (LARGE.containsKey(type)) {
            return type.endsWith("text") ? character(column, facts, StorageKind.LARGE, LARGE.get(type), type)
                    : binary(column, facts, StorageKind.LARGE, LARGE.get(type), type);
        }
        if (type.startsWith("enum(") || type.startsWith("set(")) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", "ENUM/SET value lists");
        }
        if (type.startsWith("bit(")) {
            return TypeMappingResult.unsupported("BOOLEAN_DRIVER_COERCION", "BIT values are exposed as Boolean/bytes");
        }
        if (type.matches("year(?:\\(4\\))?")) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", "YEAR is read as java.sql.Date by the drivers");
        }
        if (!noCharacterFacts(facts)) {
            return inconsistent(type);
        }
        if (type.equals("date")) {
            if (!jdbc(column, Types.DATE)) {
                return inconsistent(type);
            }
            var result = exact(new DateType(0, 9999, false));
            return flavor == Flavor.MARIADB ? result.loss("ZERO_DATE_SILENTLY_CONVERTED",
                    "MariaDB Connector/J returns NULL or a normalized date for zero/partial dates")
                    : result.note("ZERO_DATE_READER_CONTRACT",
                    "zero dates and year 0 must fail in the reader (zeroDateTimeBehavior=EXCEPTION)");
        }
        var temporal = TEMPORAL.matcher(type);
        if (temporal.matches()) {
            return temporal(column, temporal.group(1),
                    temporal.group(2) == null ? 0 : Integer.parseInt(temporal.group(2)), type);
        }
        if (type.equals("json") && flavor != Flavor.MARIADB) {
            return jdbc(column, Types.LONGVARCHAR, Types.VARCHAR) ? exact(
                    new JsonType(JsonRepresentation.NORMALIZED, JsonNumbers.BINARY64)) : inconsistent(type);
        }
        if (type.equals("uuid") && flavor == Flavor.MARIADB) {
            return jdbc(column, Types.OTHER) && "java.util.UUID".equals(column.getFiledTypeClassName()) ? exact(
                    new UuidType()) : inconsistent(type);
        }
        return TypeMappingResult.unsupported("NATIVE_TYPE_UNRECOGNIZED", type);
    }

    /**
     * 根据原生列证据推导整数的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> integer(ColumnDescription column, ColumnMetadataEvidence facts, String base,
            String width, boolean unsigned) {
        int bits = switch (base) {
            case "tinyint" -> 8;
            case "smallint" -> 16;
            case "mediumint" -> 24;
            case "int" -> 32;
            default -> 64;
        };
        int digits = switch (base) {
            case "tinyint" -> 3;
            case "smallint" -> 5;
            case "mediumint" -> unsigned ? 8 : 7;
            case "int" -> 10;
            default -> unsigned ? 20 : 19;
        };
        int expectedJdbc = switch (base) {
            case "tinyint" -> Types.TINYINT;
            case "smallint" -> Types.SMALLINT;
            case "bigint" -> Types.BIGINT;
            default -> Types.INTEGER;
        };
        // 作为 Boolean 暴露的 TINYINT(1)/BIT 会将所有非零值变为 true。
        if ("java.lang.Boolean".equals(
                column.getFiledTypeClassName()) || column.getFieldType() == Types.BIT || column.getFieldType() == Types.BOOLEAN) {
            return TypeMappingResult.unsupported("BOOLEAN_DRIVER_COERCION", "driver exposes " + base + " as Boolean");
        }
        int expectedPrecision = flavor == Flavor.MARIADB && width != null ? Math.min(Integer.parseInt(width),
                digits) : digits;
        if (column.isSigned() == unsigned || column.getFieldType() != expectedJdbc || column.getPrecisionSize() != expectedPrecision
                || column.getScaleSize() != 0 || !noCharacterFacts(facts)) {
            return inconsistent(base);
        }
        if (facts.numericPrecision().state() != MetadataValue.State.VALUE || facts.numericScale()
                .state() != MetadataValue.State.VALUE) {
            return incomplete("numeric precision/scale");
        }
        int catalog = facts.numericPrecision().value();
        boolean catalogMatches = switch (flavor) {
            // MySQL 对有符号及无符号 MEDIUMINT 均报告精度 7；TiDB 报告声明的显示宽度。
            case MYSQL -> catalog == (base.equals("mediumint") ? 7 : digits);
            case MARIADB -> catalog == digits;
            case TIDB -> catalog >= 1 && catalog <= 255;
        };
        if (!catalogMatches || facts.numericScale().value() != 0) {
            return inconsistent(base);
        }
        return exact(new IntegerType(bits, unsigned));
    }

    /**
     * 根据原生列证据推导精确小数的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> decimal(ColumnDescription column, ColumnMetadataEvidence facts,
            int precision, int scale, boolean unsigned) {
        if (precision < 1 || precision > 65 || scale > 30 || scale > precision) {
            return inconsistent("decimal");
        }
        int expectedPrecision = flavor == Flavor.TIDB && unsigned ? precision + 1 : precision;
        if (!jdbc(column, Types.DECIMAL,
                Types.NUMERIC) || column.isSigned() == unsigned || column.getPrecisionSize() != expectedPrecision
                || column.getScaleSize() != scale || !noCharacterFacts(facts)) {
            return inconsistent("decimal");
        }
        if (facts.numericPrecision().state() != MetadataValue.State.VALUE || facts.numericScale()
                .state() != MetadataValue.State.VALUE) {
            return incomplete("numeric precision/scale");
        }
        if (facts.numericPrecision().value() != precision || facts.numericScale().value() != scale) {
            return inconsistent("decimal");
        }
        return exact(new DecimalType(precision, scale, unsigned, false));
    }

    /**
     * 根据原生列证据推导浮点数的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> floating(ColumnDescription column, ColumnMetadataEvidence facts,
            boolean single, boolean unsigned) {
        if (!jdbc(column, single ? Types.REAL : Types.DOUBLE) || column.isSigned() == unsigned || !noCharacterFacts(
                facts)) {
            return inconsistent(single ? "float" : "double");
        }
        var result = exact(new FloatType(single ? 32 : 64, unsigned, !single && flavor == Flavor.MYSQL, false));
        return single ? result.loss("FLOAT32_PRECISION_NOT_PRESERVED", flavor == Flavor.TIDB
                ? "TiDB FLOAT values near the normal limit and -0 are not preserved"
                : "FLOAT values are read with six significant digits") : result;
    }

    /**
     * 根据原生列证据推导字符的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> character(ColumnDescription column, ColumnMetadataEvidence facts,
            StorageKind kind, long length, String type) {
        if (!jdbc(column, Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                Types.CLOB)) {
            return inconsistent(type);
        }
        if (facts.charset().state() != MetadataValue.State.VALUE || facts.collation()
                .state() != MetadataValue.State.VALUE
                || facts.characterLength().state() != MetadataValue.State.VALUE) {
            return incomplete("charset/collation/length");
        }
        if (facts.characterLength().value() != length) {
            return inconsistent(type);
        }
        String charset = facts.charset().value().toLowerCase(Locale.ROOT), collation = facts.collation().value()
                .toLowerCase(Locale.ROOT);
        if (!charset.matches("[a-z0-9_]{1,32}") || !collation.matches("[a-z0-9_]{1,64}") || !collation.startsWith(
                charset + "_")) {
            return inconsistent(type);
        }
        Comparison comparison = collation.equals(charset + "_0900_bin") || collation.equals(
                charset + "_nopad_bin") ? Comparison.BINARY_NO_PAD
                : collation.equals(charset + "_bin") ? Comparison.BINARY_PAD_SPACE : Comparison.NAMED;
        return exact(new CharacterType(kind, kind == StorageKind.LARGE ? null : length, LengthUnit.CHARACTERS,
                kind == StorageKind.LARGE ? length : null, charset, comparison, collation, true));
    }

    /**
     * 根据原生列证据推导二进制的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> binary(ColumnDescription column, ColumnMetadataEvidence facts,
            StorageKind kind, long length, String type) {
        if (!jdbc(column, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB) || !noCharacterFacts(facts)) {
            return inconsistent(type);
        }
        if (facts.characterLength().state() != MetadataValue.State.VALUE) {
            return incomplete("binary length");
        }
        if (facts.characterLength().value() != length) {
            return inconsistent(type);
        }
        return exact(
                kind == StorageKind.LARGE ? new BinaryType(kind, null, length) : new BinaryType(kind, length, null));
    }

    /**
     * 根据原生列证据推导时间值的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> temporal(ColumnDescription column, String base, int digits, String type) {
        switch (base) {
            case "timestamp":
                return TypeMappingResult.unsupported("TIMESTAMP_SESSION_ZONE_CODEC_UNVERIFIED",
                        "TIMESTAMP instants are converted through the JVM zone while the session zone may differ");
            case "time": {
                if (!jdbc(column, Types.TIME)) {
                    return inconsistent(type);
                }
                var result = exact(new TimeType(digits, false, TimeRange.SIGNED_DURATION_838_HOURS));
                if (digits > 3) {
                    result = result.loss("TIME_FRACTION_TRUNCATED", "java.sql.Time keeps milliseconds only");
                }
                return flavor == Flavor.MARIADB ? result.loss("TIME_DURATION_WRAPPED",
                        "MariaDB Connector/J wraps negative or 24h+ TIME values")
                        : result.note("TIME_OUTSIDE_DAY_REJECTED_BY_READER",
                        "Connector/J rejects TIME values outside 00:00:00..23:59:59");
            }
            default: {
                if (!jdbc(column, Types.TIMESTAMP)) {
                    return inconsistent(type);
                }
                var result = exact(new TimestampType(digits, TimeZoneSemantics.LOCAL, 0, 9999, false));
                if (flavor != Flavor.MARIADB) {
                    return result.note("ZERO_DATE_READER_CONTRACT",
                            "zero datetimes must fail in the reader (zeroDateTimeBehavior=EXCEPTION)");
                }
                result = result.loss("ZERO_DATE_SILENTLY_CONVERTED",
                        "MariaDB Connector/J returns NULL or a normalized value for zero/partial dates");
                return java.time.ZoneId.systemDefault().getRules().isFixedOffset() ? result
                        : result.loss("TIMESTAMP_JVM_ZONE_DST_SHIFT",
                        "MariaDB Connector/J materializes java.sql.Timestamp in the JVM zone");
            }
        }
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
            };
            return TypeMappingResult.exact(NativeColumnType.of(name + (integer.unsigned() ? " unsigned" : "")));
        }
        if (type instanceof DecimalType decimal) {
            if (!decimal.bounded()) {
                return TypeMappingResult.unsupported("UNBOUNDED_NUMERIC_UNMAPPED", "no unbounded exact numeric");
            }
            if (decimal.precision() > 65 || decimal.scale() < 0 || decimal.scale() > 30 || decimal.scale() > decimal.precision()) {
                return TypeMappingResult.unsupported("NUMERIC_PRECISION_EXCEEDS_TARGET", decimal.canonical());
            }
            var result = TypeMappingResult.exact(
                    NativeColumnType.of("decimal(" + decimal.precision() + "," + decimal.scale() + ")"
                            + (decimal.nonNegative() ? " unsigned" : "")));
            if (!decimal.nan()) {
                return result;
            }
            // 已观察：Connector/J 在单条及批量写入中拒绝 NaN，而 MariaDB Connector/J 批次将其存为 0。
            return flavor == Flavor.MARIADB ? result.loss("NAN_NOT_REPRESENTABLE",
                    "MariaDB Connector/J batch writes store NaN as 0")
                    : result.note("NAN_REJECTED_BY_TARGET_DRIVER", "NaN has no decimal value; the write fails loudly");
        }
        if (type instanceof FloatType value) {
            var result = TypeMappingResult.exact(NativeColumnType.of(
                    (value.bits() == 32 ? "float" : "double") + (value.nonNegative() ? " unsigned" : "")));
            if (value.bits() == 32) {
                result = result.loss("FLOAT32_PRECISION_NOT_PRESERVED",
                        "single-precision values near the normal limit are not stored exactly");
            }
            if (value.negativeZero()) {
                result = result.loss("NEGATIVE_ZERO_NOT_PRESERVED", "-0.0 is stored as 0");
            }
            return value.nonFinite() ? result.note("NON_FINITE_REJECTED_BY_TARGET_DRIVER",
                    "NaN/Infinity writes fail loudly") : result;
        }
        if (type instanceof BooleanType) {
            return TypeMappingResult.exact(NativeColumnType.of("tinyint(1)"));
        }
        if (type instanceof CharacterType text) {
            return character(text, column);
        }
        if (type instanceof BinaryType binary) {
            String name = switch (binary.kind()) {
                case FIXED -> binary.length() <= 255 ? "binary(" + binary.length() + ")" : null;
                case VARYING -> binary.length() <= 65535 ? "varbinary(" + binary.length() + ")" : null;
                case LARGE -> binary.maxOctets() <= 255 ? "tinyblob" : binary.maxOctets() <= 65535 ? "blob"
                        : binary.maxOctets() <= 16777215 ? "mediumblob" : "longblob";
            };
            if (name == null) {
                return TypeMappingResult.unsupported("LENGTH_EXCEEDS_TARGET", binary.canonical());
            }
            return TypeMappingResult.exact(NativeColumnType.of(name))
                    .note("VALUE_SIZE_LIMITED_BY_TARGET",
                            "max_allowed_packet (and TiDB txn-entry-size-limit) reject larger values loudly");
        }
        if (type instanceof DateType date) {
            var result = TypeMappingResult.exact(NativeColumnType.of("date"));
            return date.infinity() || date.minYear() < 0 || date.maxYear() > 9999
                    ? result.loss("DATE_RANGE_NOT_REPRESENTABLE",
                    "values outside 0000..9999 (BC, infinity) cannot be stored unchanged") : result;
        }
        if (type instanceof TimeType time) {
            if (time.withTimeZone()) {
                return TypeMappingResult.unsupported("TIME_ZONE_SEMANTICS_UNMAPPED", time.canonical());
            }
            var result = TypeMappingResult.exact(
                    NativeColumnType.of("time(" + Math.min(time.fractionalDigits(), 6) + ")"));
            return time.fractionalDigits() > 6 ? result.loss("TIME_FRACTION_TRUNCATED",
                    "MySQL-family time keeps microseconds") : result;
        }
        if (type instanceof TimestampType timestamp) {
            if (timestamp.zone() != TimeZoneSemantics.LOCAL) {
                return TypeMappingResult.unsupported("TIME_ZONE_SEMANTICS_UNMAPPED",
                        "no verified instant/offset timestamp mapping");
            }
            var result = TypeMappingResult.exact(
                    NativeColumnType.of("datetime(" + Math.min(timestamp.fractionalDigits(), 6) + ")"));
            if (timestamp.fractionalDigits() > 6) {
                result = result.loss("TIME_FRACTION_TRUNCATED", "datetime keeps microseconds");
            }
            return timestamp.infinity() || timestamp.minYear() < 0 || timestamp.maxYear() > 9999
                    ? result.loss("DATE_RANGE_NOT_REPRESENTABLE",
                    "values outside 0000..9999 (BC, infinity) cannot be stored unchanged") : result;
        }
        if (type instanceof UuidType) {
            return flavor == Flavor.MARIADB ? TypeMappingResult.exact(NativeColumnType.of("uuid"))
                    : TypeMappingResult.unsupported("UUID_TYPE_UNAVAILABLE",
                    "no native uuid; Connector/J cannot bind java.util.UUID");
        }
        if (type instanceof JsonType json) {
            if (flavor == Flavor.MARIADB) {
                return TypeMappingResult.exact(NativeColumnType.of("json"));
            }
            var result = TypeMappingResult.exact(NativeColumnType.of("json"));
            if (json.representation() == JsonRepresentation.TEXT) {
                result = result.loss("JSON_NORMALIZED", "whitespace, key order and duplicate keys are normalized");
            }
            return json.numbers() == JsonNumbers.ARBITRARY_DECIMAL
                    ? result.loss("JSON_NUMBER_PRECISION", "JSON numbers are stored as int64/uint64/double") : result;
        }
        return TypeMappingResult.unsupported("TYPE_UNMAPPED",
                type.canonical() + " has no verified " + flavor.product + " target");
    }

    /**
     * 根据原生列证据推导字符的逻辑类型及值域。
     */
    private TypeMappingResult<NativeColumnType> character(CharacterType text, LogicalColumn column) {
        if (text.unit() != LengthUnit.CHARACTERS) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", "octet length semantics");
        }
        boolean familySource = text.repertoire().equals("utf8mb4") && (column.sourceProduct() == ProductTypeEnum.MYSQL
                || column.sourceProduct() == ProductTypeEnum.MARIADB || column.sourceProduct() == ProductTypeEnum.TIDB
                || column.sourceProduct() == ProductTypeEnum.GBASE8A);
        if (!text.repertoire().equals("UNICODE") && !familySource) {
            return TypeMappingResult.unsupported("CHARSET_UNVERIFIED", "only utf8mb4 or Unicode sources are verified");
        }
        String collation;
        boolean lossyCollation = false;
        switch (text.comparison()) {
            case BINARY_NO_PAD -> collation = flavor.noPadBinaryCollation;
            case BINARY_PAD_SPACE -> collation = "utf8mb4_bin";
            // PostgreSQL 默认排序规则比较字节，character(n) 还会忽略尾部空格。
            case DETERMINISTIC_DEFAULT ->
                    collation = text.kind() == StorageKind.FIXED ? "utf8mb4_bin" : flavor.noPadBinaryCollation;
            default -> {
                if (!familySource || !flavor.namedCollations.contains(text.collation())) {
                    return TypeMappingResult.unsupported("COLLATION_UNAVAILABLE",
                            "collation " + text.collation() + " in " + flavor.product);
                }
                collation = text.collation();
                lossyCollation = true;
            }
        }
        // 键需要相同的相等语义；仅证明家族内产品之间的二进制排序规则相同。
        if (column.key() && (!familySource || text.comparison() == Comparison.NAMED)) {
            return TypeMappingResult.unsupported("TEXT_KEY_EQUALITY_UNPROVEN", "cross-product character key");
        }
        String base = switch (text.kind()) {
            case FIXED -> text.length() <= 255 ? "char(" + text.length() + ")" : null;
            case VARYING -> text.length() <= 16383 ? "varchar(" + text.length() + ")" : null;
            case LARGE -> text.maxOctets() <= 255 ? "tinytext" : text.maxOctets() <= 65535 ? "text"
                    : text.maxOctets() <= 16777215 ? "mediumtext" : "longtext";
        };
        if (base == null) {
            return TypeMappingResult.unsupported("LENGTH_EXCEEDS_TARGET", text.canonical());
        }
        var result = TypeMappingResult.exact(NativeColumnType.of(base + " CHARACTER SET utf8mb4 COLLATE " + collation));
        if (lossyCollation) {
            result = result.loss("COLLATION_EQUALITY_UNVERIFIED",
                    "named collation semantics are not verified across products");
        }
        return text.comparison() == Comparison.DETERMINISTIC_DEFAULT
                ? result.note("ORDERING_MAY_DIFFER",
                "equality is byte equality; ordering is code point, not the source locale") : result;
    }

    /**
     * 已观察契约：MariaDB 11.4+ 目录通过 MARIADB_CATALOG_V1 读取器及 MariaDB Connector/J 3.x 读取。
     */
    private boolean verifiedSource(MetadataProvenance provenance) {
        return switch (flavor) {
            case MYSQL -> provenance.nativeProduct(ProductTypeEnum.MYSQL);
            case TIDB -> provenance.nativeProduct(ProductTypeEnum.TIDB);
            // 已观察契约：MariaDB 11.4+ 目录通过 MARIADB_CATALOG_V1 读取器及 MariaDB Connector/J 3.x 读取。
            case MARIADB ->
                    provenance.product() == ProductTypeEnum.MARIADB && "MariaDB".equals(provenance.serverProduct())
                            && "MARIADB_CATALOG_V1".equals(provenance.method())
                            && provenance.serverVersion().matches("11\\.(?:[4-9]|[1-9][0-9])\\.[0-9]+(?:-.*)?");
        };
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
    private static boolean jdbc(ColumnDescription column, int... types) {
        for (int type : types) {
            if (column.getFieldType() == type) {
                return true;
            }
        }
        return false;
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
        return TypeMappingResult.unsupported("INCOMPLETE_METADATA",
                property + " was not observed; UNKNOWN cannot be EXACT");
    }
}
