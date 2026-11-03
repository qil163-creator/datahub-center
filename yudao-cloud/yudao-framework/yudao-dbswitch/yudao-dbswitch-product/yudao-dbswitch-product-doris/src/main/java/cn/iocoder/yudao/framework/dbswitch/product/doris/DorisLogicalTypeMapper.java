package cn.iocoder.yudao.framework.dbswitch.product.doris;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalColumn;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalType;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalType.*;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalTypeMapper;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.NativeColumnType;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeFidelity;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeMappingResult;

import java.sql.Types;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 将 Doris 4.0.8（协议 5.7.99，MySQL Connector/J 9.7.0）原生事实映射到逻辑值域及其反向映射；每个事实都
 * 在真实服务器上探测，不复制 MySQL 8.4 系列映射器的事实：
 * <ul>
 * <li>目录使用 MySQL 显示宽度（tinyint(4) ... bigint(20)）报告整数，并使用值域十进制位数
 * 作为 numeric_precision（3/5/10/19）；BOOLEAN 显示为 {@code tinyint(1)}，numeric_precision 为 null，而
 * 驱动将其暴露为 BIT/Boolean。</li>
 * <li>传统 DECIMAL 在目录中规范化为 {@code decimalv3(p, s)}（含空格）；最大精度
 * 为 38，溢出时默默存为 NULL。Connector/J 将 LARGEINT 读取为 String。</li>
 * <li>CHAR/VARCHAR 长度按字节计算（已验证：varchar(5) 拒绝 6 字节）；二者均报告精度为 85 的 JDBC CHAR(1)，
 * 因此固定或变长属性仅来自目录类型。服务器唯一字符集为 utf8mb4，
 * 唯一 NO PAD 排序规则为 utf8mb4_0900_bin；information_schema 将字符集、排序规则报告为 NULL。</li>
 * <li>DATETIME(p) 默认小数位为 0，并默默截断；LocalDateTime 以
 * 声明精度返回。产品 URL 使用 zeroDateTimeBehavior=convertToNull 时，零日期读为 NULL，
 * 没有该设置时显式失败。JSON 将数字存为 double，并移除空白。FLOAT、DOUBLE 和 DECIMAL
 * 的 -0.0 规范化为 0；NaN/Infinity 无法通过 SQL 字面值写入。</li>
 * <li>不存在 TIME 和 TIMESTAMP 类型，DDL 拒绝 PRIMARY KEY；生成的跨产品 DDL
 * 使用 DUPLICATE KEY 模型并保留重复键：列类型从不强制唯一性。</li>
 * </ul>
 */
public final class DorisLogicalTypeMapper implements LogicalTypeMapper {

    private static final Pattern INTEGER = Pattern.compile("(tinyint|smallint|int|bigint)\\(([0-9]{1,3})\\)");

    private static final Pattern DECIMAL = Pattern.compile("(?:decimal|decimalv3)\\(([0-9]{1,2}),\\s*([0-9]{1,2})\\)");

    private static final Pattern STRING = Pattern.compile("(char|varchar)\\(([0-9]{1,5})\\)");

    private static final Pattern TEMPORAL = Pattern.compile("(datetime|datetimev2)(?:\\(([0-6])\\))?");

    private static final long STRING_MAX_OCTETS = 2147483643L;

    /**
     * 初始化 {@code DorisLogicalTypeMapper}。
     */
    public DorisLogicalTypeMapper() {
    }

    /**
     * 返回当前操作使用的数据库产品。
     */
    @Override
    public ProductTypeEnum product() {
        return ProductTypeEnum.DORIS;
    }

    /**
     * 将数据库原生列类型映射为逻辑类型。
     */
    @Override
    public TypeMappingResult<LogicalType> toLogical(ColumnDescription column) {
        ColumnMetadataEvidence facts = column.getMetadataEvidence();
        if (!facts.provenance().nativeProduct(ProductTypeEnum.DORIS)) {
            return TypeMappingResult.unsupported("SOURCE_PROVENANCE_UNVERIFIED",
                    "DORIS native catalog provenance required");
        }
        String type = facts.nativeType().value().trim().toLowerCase(Locale.ROOT);
        if (type.endsWith(" unsigned")) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", "unsigned integer types are rejected at DDL");
        }
        if (type.startsWith(
                "tinyint(1)") && (column.getFieldType() == Types.BIT || column.getFieldType() == Types.BOOLEAN
                || "java.lang.Boolean".equals(column.getFiledTypeClassName()))) {
            return TypeMappingResult.exact(new BooleanType());
        }
        var integer = INTEGER.matcher(type);
        if (integer.matches()) {
            return integer(column, facts, integer.group(1));
        }
        if (type.equals("largeint")) {
            // 原生读取器校验有符号 128 位十进制传输文本，并返回 BigDecimal。
            if (column.getFieldType() != Types.CHAR || !"java.lang.String".equals(column.getFiledTypeClassName())
                    || column.getPrecisionSize() != 85 || column.getScaleSize() != 0 || !noCharacterFacts(facts)
                    || facts.numericPrecision().state() != MetadataValue.State.VALUE || facts.numericPrecision()
                    .value() != 39
                    || facts.numericScale().state() != MetadataValue.State.ABSENT) {
                return inconsistent(type);
            }
            return exact(new Int128Type());
        }
        var decimal = DECIMAL.matcher(type);
        if (decimal.matches()) {
            return decimal(column, facts, Integer.parseInt(decimal.group(1)), Integer.parseInt(decimal.group(2)));
        }
        if (type.equals("float")) {
            return floating(column, facts, true);
        }
        if (type.equals("double")) {
            return floating(column, facts, false);
        }
        var string = STRING.matcher(type);
        if (string.matches()) {
            long length = Long.parseLong(string.group(2));
            return character(column, facts, string.group(1).equals("char") ? StorageKind.FIXED : StorageKind.VARYING,
                    length, type);
        }
        if (type.equals("string")) {
            if (!jdbc(column, Types.VARCHAR, Types.LONGVARCHAR, Types.CLOB)) {
                return inconsistent(type);
            }
            if (facts.characterLength().state() != MetadataValue.State.VALUE || facts.characterLength()
                    .value() != STRING_MAX_OCTETS) {
                return inconsistent(type);
            }
            return exact(new CharacterType(StorageKind.LARGE, null, LengthUnit.OCTETS, STRING_MAX_OCTETS, "utf8mb4",
                    Comparison.BINARY_NO_PAD, "utf8mb4_0900_bin", true));
        }
        if (type.equals("json")) {
            if (!jdbc(column, Types.LONGVARCHAR, Types.VARCHAR)) {
                return inconsistent(type);
            }
            // 已验证：空白被规范化，数字存为 double。
            return exact(new JsonType(JsonRepresentation.NORMALIZED, JsonNumbers.BINARY64));
        }
        if (type.startsWith("array<")) {
            return TypeMappingResult.unsupported("VALUE_CODEC_UNSUPPORTED", "Connector/J reads ARRAY as String");
        }
        if (type.equals("bitmap") || type.equals("hll") || type.equals("quantile_state")) {
            return TypeMappingResult.unsupported("NATIVE_TYPE_UNRECOGNIZED", type);
        }
        if (!noCharacterFacts(facts)) {
            return inconsistent(type);
        }
        if (type.equals("date") || type.equals("datev2")) {
            if (!jdbc(column, Types.DATE)) {
                return inconsistent(type);
            }
            return exact(new DateType(0, 9999, false)).loss("ZERO_DATE_SILENTLY_CONVERTED",
                    "year-0 dates read as NULL under zeroDateTimeBehavior=convertToNull and fail loudly without it");
        }
        var temporal = TEMPORAL.matcher(type);
        if (temporal.matches()) {
            if (!jdbc(column, Types.TIMESTAMP) || !"java.time.LocalDateTime".equals(column.getFiledTypeClassName())) {
                return inconsistent(type);
            }
            int digits = temporal.group(2) == null ? 0 : Integer.parseInt(temporal.group(2));
            return exact(new TimestampType(digits, TimeZoneSemantics.LOCAL, 0, 9999, false)).loss(
                    "ZERO_DATE_SILENTLY_CONVERTED",
                    "year-0 datetimes read as NULL under zeroDateTimeBehavior=convertToNull and fail loudly without it");
        }
        if (type.equals("time") || type.equals("timestamp")) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", type + " is rejected at DDL");
        }
        return TypeMappingResult.unsupported("NATIVE_TYPE_UNRECOGNIZED", type);
    }

    /**
     * 根据原生列证据推导整数的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> integer(ColumnDescription column, ColumnMetadataEvidence facts,
            String base) {
        int bits = switch (base) {
            case "tinyint" -> 8;
            case "smallint" -> 16;
            case "int" -> 32;
            default -> 64;
        };
        int driverPrecision = switch (base) {
            case "tinyint" -> 3;
            case "smallint" -> 5;
            case "int" -> 10;
            default -> 19;
        };
        int catalogPrecision = driverPrecision;
        int expectedJdbc = switch (base) {
            case "tinyint" -> Types.TINYINT;
            case "smallint" -> Types.SMALLINT;
            case "bigint" -> Types.BIGINT;
            default -> Types.INTEGER;
        };
        if (column.getFieldType() != expectedJdbc || !column.isSigned() || column.getPrecisionSize() != driverPrecision
                || column.getScaleSize() != 0 || !noCharacterFacts(facts)) {
            return inconsistent(base);
        }
        if (facts.numericPrecision().state() != MetadataValue.State.VALUE || facts.numericScale()
                .state() != MetadataValue.State.VALUE) {
            return incomplete("numeric precision/scale");
        }
        if (facts.numericPrecision().value() != catalogPrecision || facts.numericScale().value() != 0) {
            return inconsistent(base);
        }
        return exact(new IntegerType(bits, false));
    }

    /**
     * 根据原生列证据推导精确小数的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> decimal(ColumnDescription column, ColumnMetadataEvidence facts,
            int precision, int scale) {
        if (precision < 1 || precision > 38 || scale < 0 || scale > 38 || scale > precision) {
            return inconsistent("decimalv3");
        }
        if (!jdbc(column, Types.DECIMAL, Types.NUMERIC) || !column.isSigned() || column.getPrecisionSize() != precision
                || column.getScaleSize() != scale || !noCharacterFacts(facts)) {
            return inconsistent("decimalv3");
        }
        if (facts.numericPrecision().state() != MetadataValue.State.VALUE || facts.numericScale()
                .state() != MetadataValue.State.VALUE) {
            return incomplete("numeric precision/scale");
        }
        if (facts.numericPrecision().value() != precision || facts.numericScale().value() != scale) {
            return inconsistent("decimalv3");
        }
        return exact(new DecimalType(precision, scale, false, false));
    }

    /**
     * 根据原生列证据推导浮点数的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> floating(ColumnDescription column, ColumnMetadataEvidence facts,
            boolean single) {
        if (!jdbc(column,
                single ? Types.REAL : Types.DOUBLE) || !column.isSigned() || column.getPrecisionSize() != (single ? 12 : 22)
                || !noCharacterFacts(facts)) {
            return inconsistent(single ? "float" : "double");
        }
        if (facts.numericPrecision().state() != MetadataValue.State.VALUE || facts.numericScale()
                .state() != MetadataValue.State.VALUE) {
            return incomplete("float precision/scale");
        }
        // 已验证目录结构：float 报告 7/7，double 报告 15/15。
        int expected = single ? 7 : 15;
        if (facts.numericPrecision().value() != expected || facts.numericScale().value() != expected) {
            return inconsistent(single ? "float" : "double");
        }
        var result = exact(new FloatType(single ? 32 : 64, false, false, false));
        return single ? result.loss("FLOAT32_PRECISION_NOT_PRESERVED",
                "single-precision binary storage rounds decimal values") : result;
    }

    /**
     * 根据原生列证据推导字符的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> character(ColumnDescription column, ColumnMetadataEvidence facts,
            StorageKind kind, long length, String type) {
        // CHAR 和 VARCHAR 均返回精度为 85 的 JDBC CHAR(1)；由目录类型名称和长度决定。
        if (!jdbc(column, Types.CHAR, Types.VARCHAR)) {
            return inconsistent(type);
        }
        if (facts.characterLength().state() != MetadataValue.State.VALUE || facts.characterLength().value() != length) {
            return inconsistent(type);
        }
        return exact(new CharacterType(kind, length, LengthUnit.OCTETS, null, "utf8mb4", Comparison.BINARY_NO_PAD,
                "utf8mb4_0900_bin", true));
    }

    /**
     * 将逻辑列类型映射为当前产品的原生类型。
     */
    @Override
    public TypeMappingResult<NativeColumnType> toNative(LogicalColumn column) {
        LogicalType type = column.type();
        TypeMappingResult<NativeColumnType> result = nativeType(type, column);
        if (column.key() && result.fidelity() != TypeFidelity.UNSUPPORTED) {
            result = result.loss("KEY_UNIQUENESS_NOT_ENFORCED",
                    "the generated DUPLICATE KEY model stores duplicate keys");
        }
        return result;
    }

    /**
     * 根据原生列信息确定数据库类型声明。
     */
    private TypeMappingResult<NativeColumnType> nativeType(LogicalType type, LogicalColumn column) {
        if (type instanceof Int128Type) {
            return TypeMappingResult.exact(NativeColumnType.of("largeint"));
        }
        if (type instanceof IntegerType integer) {
            // 原生 LARGEINT 编解码器在有符号 128 位值域内保留无符号 64 位值。
            String name = switch (integer.bits()) {
                case 8 -> integer.unsigned() ? "smallint" : "tinyint";
                case 16 -> integer.unsigned() ? "int" : "smallint";
                case 24 -> "int";
                case 32 -> integer.unsigned() ? "bigint" : "int";
                default -> integer.unsigned() ? "largeint" : "bigint";
            };
            return TypeMappingResult.exact(NativeColumnType.of(name));
        }
        if (type instanceof DecimalType decimal) {
            if (!decimal.bounded()) {
                return TypeMappingResult.unsupported("UNBOUNDED_NUMERIC_UNMAPPED", "no unbounded exact numeric");
            }
            if (decimal.precision() == 39 && decimal.scale() == 0) {
                var result = TypeMappingResult.exact(NativeColumnType.of("largeint"))
                        .loss("NUMERIC_RANGE_NOT_REPRESENTABLE",
                                "LARGEINT is signed 128-bit; the native writer rejects values outside -2^127..2^127-1");
                return decimal.nan() ? result.loss("NAN_NOT_REPRESENTABLE", "NaN has no Doris LARGEINT value") : result;
            }
            if (decimal.precision() > 38 || decimal.scale() < 0 || decimal.scale() > 38 || decimal.scale() > decimal.precision()) {
                return TypeMappingResult.unsupported("NUMERIC_PRECISION_EXCEEDS_TARGET", decimal.canonical());
            }
            var result = TypeMappingResult.exact(
                    NativeColumnType.of("decimal(" + decimal.precision() + "," + decimal.scale() + ")"));
            // 已验证：真实服务器将超出声明值域的值默默存为 NULL。
            return decimal.nan() ? result.loss("NAN_NOT_REPRESENTABLE", "NaN has no Doris decimal value") : result;
        }
        if (type instanceof FloatType value) {
            var result = TypeMappingResult.exact(NativeColumnType.of(value.bits() == 32 ? "float" : "double"));
            if (value.bits() == 32) {
                result = result.loss("FLOAT32_PRECISION_NOT_PRESERVED",
                        "single-precision binary storage rounds decimal values");
            }
            if (value.negativeZero()) {
                result = result.loss("NEGATIVE_ZERO_NOT_PRESERVED", "-0.0 is stored as 0");
            }
            return value.nonFinite() ? result.note("NON_FINITE_REJECTED_BY_TARGET",
                    "NaN/Infinity have no SQL literal; writes fail loudly") : result;
        }
        if (type instanceof BooleanType) {
            return TypeMappingResult.exact(NativeColumnType.of("boolean"));
        }
        if (type instanceof CharacterType text) {
            return character(text, column);
        }
        if (type instanceof BinaryType) {
            return TypeMappingResult.unsupported("BINARY_TYPE_UNAVAILABLE", "Doris has no binary column type");
        }
        if (type instanceof DateType date) {
            var result = TypeMappingResult.exact(NativeColumnType.of("date"));
            return date.infinity() || date.minYear() < 0 || date.maxYear() > 9999
                    ? result.loss("DATE_RANGE_NOT_REPRESENTABLE",
                    "values outside 0000..9999 (BC, infinity) cannot be stored unchanged") : result;
        }
        if (type instanceof TimeType) {
            return TypeMappingResult.unsupported("TIME_TYPE_UNAVAILABLE", "TIME is rejected at DDL");
        }
        if (type instanceof TimestampType timestamp) {
            if (timestamp.zone() != TimeZoneSemantics.LOCAL) {
                return TypeMappingResult.unsupported("TIME_ZONE_SEMANTICS_UNMAPPED",
                        "no verified instant/offset timestamp mapping");
            }
            int digits = Math.min(timestamp.fractionalDigits(), 6);
            var result = TypeMappingResult.exact(
                    NativeColumnType.of(digits == 0 ? "datetime" : "datetime(" + digits + ")"));
            if (timestamp.fractionalDigits() > 6) {
                result = result.loss("TIME_FRACTION_TRUNCATED", "datetime keeps microseconds");
            }
            return timestamp.infinity() || timestamp.minYear() < 0 || timestamp.maxYear() > 9999
                    ? result.loss("DATE_RANGE_NOT_REPRESENTABLE",
                    "values outside 0000..9999 (BC, infinity) cannot be stored unchanged") : result;
        }
        if (type instanceof UuidType) {
            return TypeMappingResult.unsupported("UUID_TYPE_UNAVAILABLE", "no native uuid type");
        }
        if (type instanceof JsonType json) {
            var result = TypeMappingResult.exact(NativeColumnType.of("json"));
            if (json.representation() == JsonRepresentation.TEXT) {
                result = result.loss("JSON_NORMALIZED", "whitespace is dropped by the binary JSON storage");
            }
            return json.numbers() == JsonNumbers.ARBITRARY_DECIMAL
                    ? result.loss("JSON_NUMBER_PRECISION",
                    "JSON numbers are stored as double (verified 1.2345678901234568e+29 rounding)") : result;
        }
        return TypeMappingResult.unsupported("TYPE_UNMAPPED", type.canonical() + " has no verified DORIS target");
    }

    /**
     * 根据原生列证据推导字符的逻辑类型及值域。
     */
    private TypeMappingResult<NativeColumnType> character(CharacterType text, LogicalColumn column) {
        if (column.key()) {
            return TypeMappingResult.unsupported("TEXT_KEY_EQUALITY_UNPROVEN", "cross-product character key");
        }
        if (text.unit() != LengthUnit.CHARACTERS && text.unit() != LengthUnit.OCTETS) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", text.canonical());
        }
        if (!text.repertoire().equals("UNICODE") && !text.repertoire().equals("utf8mb4")) {
            return TypeMappingResult.unsupported("CHARSET_UNVERIFIED", "only utf8mb4 or Unicode sources are verified");
        }
        // Doris 的 CHAR/VARCHAR 长度按字节计算；按字符计长的数据源每个字符至多需要 4 字节。
        // 大文本类型不携带长度；始终映射为 STRING，不进行字节算术。
        long bytes = text.kind() == StorageKind.LARGE ? 0 : text.unit() == LengthUnit.OCTETS ? text.length() : text.length() * 4;
        String base = switch (text.kind()) {
            case FIXED -> bytes <= 255 ? "char(" + bytes + ")" : null;
            case VARYING -> bytes <= 65533 ? "varchar(" + bytes + ")" : "string";
            case LARGE -> "string";
        };
        if (base == null) {
            return TypeMappingResult.unsupported("LENGTH_EXCEEDS_TARGET", text.canonical());
        }
        var result = TypeMappingResult.exact(NativeColumnType.of(base));
        switch (text.comparison()) {
            case BINARY_NO_PAD -> {
            } // utf8mb4_0900_bin 使用 NO PAD 码点比较：精确匹配。
            case BINARY_PAD_SPACE ->
                    result = result.loss("PAD_SPACE_EQUALITY_CHANGED", "Doris does not ignore trailing spaces");
            case NAMED -> result = TypeMappingResult.unsupported("COLLATION_UNAVAILABLE",
                    "Doris carries the single collation utf8mb4_0900_bin; " + text.collation() + " has no equivalent");
            default -> result = result.note("ORDERING_MAY_DIFFER",
                    "equality is code-point equality; ordering is not the source locale");
        }
        return result;
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
