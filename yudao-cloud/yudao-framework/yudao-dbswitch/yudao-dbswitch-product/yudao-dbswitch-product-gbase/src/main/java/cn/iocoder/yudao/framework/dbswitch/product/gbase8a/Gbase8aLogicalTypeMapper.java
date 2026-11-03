package cn.iocoder.yudao.framework.dbswitch.product.gbase8a;

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
import java.util.Map;
import java.util.regex.Pattern;

/**
 * GBase 8a 8.6.2.43-R7-free 原生事实（目录 COLUMN_TYPE 文本）与逻辑值域之间的双向映射，经
 * gbase-connector-java-8.3.81.53-build55.5.3 在真实服务器探测；不复制 MySQL 8.4 系的任何事实：
 * <ul>
 * <li>整数值域排除最小负值（tinyint -127..127、smallint -32767..32767、
 * int -2147483647..2147483647，bigint 排除 ±9223372036854775807）；驱动 precision 跟随显示
 * 宽度（4/6/9/11/20），目录 precision 为值域宽度（3/5/8/10/20）。</li>
 * <li>无符号整数、BIT、ENUM、SET、JSON、zerofill、负 scale decimal 及带精度的
 * datetime(n)/time(n) 在 DDL 阶段被拒绝；decimal 最大 precision 65 / scale 30。</li>
 * <li>TIMESTAMP 只存秒，总带 NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE；DATETIME 保留
 * 微秒。零日期在 DML 阶段被拒绝（NO_ZERO_DATE）。</li>
 * <li>驱动将 TIME 值返回为 String，读取时将补充平面字符显示为 '?'，写入时
 * 则明确拒绝；utf8mb4 下 VARCHAR 最多保存 8191 个字符，TEXT 保存 8191 个字符，
 * BLOB 保存 32767 字节。utf8mb4_bin 区分大小写，采用 PAD SPACE。PRIMARY KEY 和 CHECK 仅是元数据：
 * 允许存储重复值且不强制 CHECK，所以二者均不能作为映射保证。</li>
 * </ul>
 */
public final class Gbase8aLogicalTypeMapper implements LogicalTypeMapper {

    private static final Pattern INTEGER = Pattern.compile(
            "(tinyint|smallint|mediumint|int|bigint)(?:\\(([0-9]{1,3})\\))?( unsigned)?");

    private static final Pattern DECIMAL = Pattern.compile("decimal\\(([0-9]{1,2}),\\s*([0-9]{1,2})\\)");

    private static final Pattern FLOATING = Pattern.compile("(float|double)");

    private static final Pattern STRING = Pattern.compile("(char|varchar|binary|varbinary)\\(([0-9]{1,5})\\)");

    private static final Pattern TEMPORAL = Pattern.compile("(datetime|timestamp|time)(?:\\(([0-6])\\))?");

    private static final Map<String, Long> LARGE = Map.of("tinytext", 255L, "text", 8191L, "tinyblob", 255L, "blob",
            32767L);

    /**
     * 初始化 {@code Gbase8aLogicalTypeMapper}。
     */
    public Gbase8aLogicalTypeMapper() {
    }

    /**
     * 返回当前操作使用的数据库产品。
     */
    @Override
    public ProductTypeEnum product() {
        return ProductTypeEnum.GBASE8A;
    }

    /**
     * 将数据库原生列类型映射为逻辑类型。
     */
    @Override
    public TypeMappingResult<LogicalType> toLogical(ColumnDescription column) {
        ColumnMetadataEvidence facts = column.getMetadataEvidence();
        if (!facts.provenance().nativeProduct(ProductTypeEnum.GBASE8A)) {
            return TypeMappingResult.unsupported("SOURCE_PROVENANCE_UNVERIFIED",
                    "GBASE8A native catalog provenance required");
        }
        String type = facts.nativeType().value().trim().toLowerCase(Locale.ROOT);
        if (type.endsWith(" zerofill") || type.endsWith(" unsigned")) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", type + " is rejected at DDL");
        }
        if (type.startsWith(
                "tinyint(1)") && (column.getFieldType() == Types.BIT || column.getFieldType() == Types.BOOLEAN
                || "java.lang.Boolean".equals(column.getFiledTypeClassName()))) {
            return TypeMappingResult.unsupported("BOOLEAN_DRIVER_COERCION", "the driver exposes tinyint(1) as Boolean");
        }
        var integer = INTEGER.matcher(type);
        if (integer.matches()) {
            return integer(column, facts, integer.group(1), integer.group(2));
        }
        var decimal = DECIMAL.matcher(type);
        if (decimal.matches()) {
            return decimal(column, facts, Integer.parseInt(decimal.group(1)), Integer.parseInt(decimal.group(2)));
        }
        var floating = FLOATING.matcher(type);
        if (floating.matches() && type.matches("(float|double)")) {
            return floating(column, facts, type.equals("float"));
        }
        if (type.matches("(float|double)\\([0-9]+,[0-9]+\\)")) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED",
                    "fixed-decimals floating point storage semantics are unverified");
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
        if (type.startsWith("bit(") || type.startsWith("enum(") || type.startsWith("set(") || type.equals("json")) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", type + " is rejected at DDL");
        }
        if (type.matches("year(?:\\(4\\))?")) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", "YEAR is read as java.sql.Date by the driver");
        }
        if (!noCharacterFacts(facts)) {
            return inconsistent(type);
        }
        if (type.equals("date")) {
            // NO_ZERO_DATE 是默认 sql_mode 的一部分：零日期在 DML 阶段被拒绝，不可能存在。
            return jdbc(column, Types.DATE) ? exact(new DateType(1, 9999, false)) : inconsistent(type);
        }
        var temporal = TEMPORAL.matcher(type);
        if (temporal.matches()) {
            return temporal(column, temporal.group(1), type);
        }
        return TypeMappingResult.unsupported("NATIVE_TYPE_UNRECOGNIZED", type);
    }

    /**
     * 根据原生列证据推导整数的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> integer(ColumnDescription column, ColumnMetadataEvidence facts, String base,
            String width) {
        int bits = switch (base) {
            case "tinyint" -> 8;
            case "smallint" -> 16;
            case "mediumint" -> 24;
            case "int" -> 32;
            default -> 64;
        };
        int driverPrecision = switch (base) {
            case "tinyint" -> 4;
            case "smallint" -> 6;
            case "mediumint" -> 9;
            case "int" -> 11;
            default -> 20;
        };
        int catalogPrecision = switch (base) {
            case "tinyint" -> 3;
            case "smallint" -> 5;
            case "mediumint" -> 8;
            case "int" -> 10;
            default -> 20;
        };
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
        // 已验证：每个整数值域都排除最小负值；声明的逻辑值域是其超集。
        return exact(new IntegerType(bits, false)).note("INTEGER_DOMAIN_NARROWED",
                "GBase 8a " + base + " excludes the most negative value (verified -127/-32767/-2147483647 and bigint ±9223372036854775807 rejected)");
    }

    /**
     * 根据原生列证据推导精确小数的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> decimal(ColumnDescription column, ColumnMetadataEvidence facts,
            int precision, int scale) {
        if (precision < 1 || precision > 65 || scale < 0 || scale > 30 || scale > precision) {
            return inconsistent("decimal");
        }
        if (!jdbc(column, Types.DECIMAL, Types.NUMERIC) || !column.isSigned() || column.getPrecisionSize() != precision
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
        return exact(new DecimalType(precision, scale, false, false));
    }

    /**
     * 根据原生列证据推导浮点数的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> floating(ColumnDescription column, ColumnMetadataEvidence facts,
            boolean single) {
        int expectedPrecision = single ? 12 : 22;
        if (!jdbc(column,
                single ? Types.REAL : Types.DOUBLE) || !column.isSigned() || column.getPrecisionSize() != expectedPrecision
                || !noCharacterFacts(facts)) {
            return inconsistent(single ? "float" : "double");
        }
        if (facts.numericPrecision().state() != MetadataValue.State.VALUE) {
            return incomplete("float precision");
        }
        if (facts.numericPrecision().value() != expectedPrecision || facts.numericScale()
                .state() != MetadataValue.State.ABSENT) {
            return inconsistent(single ? "float" : "double");
        }
        var result = exact(new FloatType(single ? 32 : 64, false, false, false));
        // 服务器将 -0.0 规范化为 0，无法通过 SQL 字面量写入 NaN/Infinity。
        return single ? result.loss("FLOAT32_PRECISION_NOT_PRESERVED",
                "single-precision binary storage rounds decimal values") : result;
    }

    /**
     * 根据原生列证据推导字符的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> character(ColumnDescription column, ColumnMetadataEvidence facts,
            StorageKind kind, long length, String type) {
        if (!jdbc(column, Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.CLOB)) {
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
        // 已验证：utf8mb4_bin 区分大小写，但忽略尾部空格比较（PAD SPACE）；不存在 NO PAD 排序规则。
        Comparison comparison = collation.equals(charset + "_bin") ? Comparison.BINARY_PAD_SPACE : Comparison.NAMED;
        // 已验证：驱动读取时将补充平面字符显示为 '?'，写入时拒绝。
        return exact(new CharacterType(kind, kind == StorageKind.LARGE ? null : length, LengthUnit.CHARACTERS,
                kind == StorageKind.LARGE ? length * 4 : null, charset, comparison, collation, true))
                .loss("FOUR_BYTE_CHARACTERS_NOT_PRESERVED",
                        "the driver renders supplementary-plane characters as '?' on read");
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
    private TypeMappingResult<LogicalType> temporal(ColumnDescription column, String base, String type) {
        switch (base) {
            case "datetime": {
                if (!jdbc(column, Types.TIMESTAMP) || column.getPrecisionSize() != 26 || column.getScaleSize() != 0) {
                    return inconsistent(type);
                }
                return exact(new TimestampType(6, TimeZoneSemantics.LOCAL, 1, 9999, false));
            }
            case "timestamp":
                // 已验证：截断到秒，且隐式附带 NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE。
                return TypeMappingResult.unsupported("TIMESTAMP_ON_UPDATE_CODEC_UNVERIFIED",
                        "GBase 8a TIMESTAMP stores seconds only with implicit NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE");
            default:
                // 已验证：驱动将 TIME 值返回为 String，跨驱动目标无法原样绑定。
                return TypeMappingResult.unsupported("TIME_VALUE_CODEC_UNSUPPORTED",
                        "the GBase 8a driver reads TIME as String");
        }
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
                    "GBase 8a PRIMARY KEY is metadata only; duplicates are stored");
        }
        return result;
    }

    /**
     * 根据原生列信息确定数据库类型声明。
     */
    private TypeMappingResult<NativeColumnType> nativeType(LogicalType type, LogicalColumn column) {
        if (type instanceof IntegerType integer) {
            // 已验证值域：每种宽度的最小负值均被拒绝；无符号值域超出目标范围。
            String name = switch (integer.bits()) {
                case 8 -> "tinyint";
                case 16 -> "smallint";
                case 24 -> "mediumint";
                case 32 -> "int";
                default -> integer.unsigned() ? "decimal(20,0)" : "bigint";
            };
            // 扩宽的无符号值域可放入下一级有符号类型（int 容纳 0..16777215、bigint 容纳 0..4294967295、decimal(20,0) 容纳 0..2^64-1）。
            if (integer.unsigned() && (integer.bits() == 24 || integer.bits() == 32 || integer.bits() == 64)) {
                String widened = integer.bits() == 24 ? "int" : integer.bits() == 32 ? "bigint" : "decimal(20,0)";
                return TypeMappingResult.exact(NativeColumnType.of(widened));
            }
            if (integer.unsigned() && (integer.bits() == 8 || integer.bits() == 16)) {
                return TypeMappingResult.exact(NativeColumnType.of(name))
                        .loss("INTEGER_DOMAIN_NARROWED",
                                "unsigned " + integer.bits() + "-bit values above " + (integer.bits() == 8 ? 127 : 32767) + " are not representable");
            }
            var result = TypeMappingResult.exact(NativeColumnType.of(name));
            return result.loss("INTEGER_DOMAIN_NARROWED",
                    "the most negative " + integer.bits() + "-bit value is not representable"
                            + (integer.bits() == 64 ? " (verified ±9223372036854775807 rejected, 9223372036854775806 accepted)" : " (verified)"));
        }
        if (type instanceof DecimalType decimal) {
            if (!decimal.bounded()) {
                return TypeMappingResult.unsupported("UNBOUNDED_NUMERIC_UNMAPPED", "no unbounded exact numeric");
            }
            if (decimal.precision() > 65 || decimal.scale() < 0 || decimal.scale() > 30 || decimal.scale() > decimal.precision()) {
                return TypeMappingResult.unsupported("NUMERIC_PRECISION_EXCEEDS_TARGET", decimal.canonical());
            }
            var result = TypeMappingResult.exact(
                    NativeColumnType.of("decimal(" + decimal.precision() + "," + decimal.scale() + ")"));
            return decimal.nan() ? result.loss("NAN_NOT_REPRESENTABLE",
                    "NaN has no verified GBase 8a decimal value") : result;
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
            return value.nonFinite() ? result.loss("NON_FINITE_NOT_REPRESENTABLE",
                    "NaN/Infinity cannot be written through the verified path") : result;
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
                case VARYING -> binary.length() <= 32767 ? "varbinary(" + binary.length() + ")" : null;
                case LARGE -> "blob";
            };
            if (name == null) {
                return TypeMappingResult.unsupported("LENGTH_EXCEEDS_TARGET", binary.canonical());
            }
            var result = TypeMappingResult.exact(NativeColumnType.of(name));
            return binary.kind() == StorageKind.LARGE
                    ? result.note("VALUE_SIZE_LIMITED_BY_TARGET",
                    "BLOB holds 32767 bytes; larger values fail loudly") : result;
        }
        if (type instanceof DateType date) {
            var result = TypeMappingResult.exact(NativeColumnType.of("date"));
            if (date.minYear() < 1) {
                result = result.note("ZERO_DATE_REJECTED_BY_TARGET", "zero dates are rejected at DML (NO_ZERO_DATE)");
            }
            return date.infinity() || date.minYear() < 0 || date.maxYear() > 9999
                    ? result.loss("DATE_RANGE_NOT_REPRESENTABLE",
                    "values outside 0001..9999 (BC, infinity) cannot be stored unchanged") : result;
        }
        if (type instanceof TimeType) {
            return TypeMappingResult.unsupported("TIME_VALUE_CODEC_UNSUPPORTED",
                    "the GBase 8a driver reads TIME as String");
        }
        if (type instanceof TimestampType timestamp) {
            if (timestamp.zone() != TimeZoneSemantics.LOCAL) {
                return TypeMappingResult.unsupported("TIME_ZONE_SEMANTICS_UNMAPPED",
                        "no verified instant/offset timestamp mapping");
            }
            var result = TypeMappingResult.exact(NativeColumnType.of("datetime"));
            if (timestamp.fractionalDigits() > 6) {
                result = result.loss("TIME_FRACTION_TRUNCATED", "datetime keeps microseconds");
            }
            if (timestamp.minYear() < 1) {
                result = result.note("ZERO_DATE_REJECTED_BY_TARGET",
                        "zero datetimes are rejected at DML (NO_ZERO_DATE)");
            }
            return timestamp.infinity() || timestamp.minYear() < 0 || timestamp.maxYear() > 9999
                    ? result.loss("DATE_RANGE_NOT_REPRESENTABLE",
                    "values outside 0001..9999 (BC, infinity) cannot be stored unchanged") : result;
        }
        if (type instanceof UuidType) {
            return TypeMappingResult.unsupported("UUID_TYPE_UNAVAILABLE", "no native uuid type");
        }
        if (type instanceof JsonType) {
            return TypeMappingResult.unsupported("JSON_TYPE_UNAVAILABLE", "json is rejected at DDL");
        }
        return TypeMappingResult.unsupported("TYPE_UNMAPPED", type.canonical() + " has no verified GBASE8A target");
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
        // 生成的表带 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin；utf8mb4 的每个 VARCHAR 最多保留 8191 个字符。
        String base = switch (text.kind()) {
            case FIXED -> text.length() <= 255 ? "char(" + text.length() + ")" : null;
            case VARYING -> text.length() <= 8191 ? "varchar(" + text.length() + ")" : null;
            case LARGE -> "text";
        };
        if (base == null) {
            return TypeMappingResult.unsupported("LENGTH_EXCEEDS_TARGET", text.canonical());
        }
        var result = TypeMappingResult.exact(NativeColumnType.of(base));
        switch (text.comparison()) {
            case BINARY_NO_PAD -> result = result.loss("PAD_SPACE_EQUALITY_CHANGED",
                    "utf8mb4_bin ignores trailing spaces; the source does not");
            case BINARY_PAD_SPACE -> {
            }
            case NAMED -> result = result.loss("COLLATION_EQUALITY_CHANGED",
                    "collation " + text.collation() + " is replaced by utf8mb4_bin");
            default -> result = result.note("ORDERING_MAY_DIFFER",
                    "equality is byte equality; ordering is code point, not the source locale");
        }
        return result.note("FOUR_BYTE_CHARACTERS_REJECTED_BY_TARGET",
                "the driver rejects supplementary-plane characters loudly on write");
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
