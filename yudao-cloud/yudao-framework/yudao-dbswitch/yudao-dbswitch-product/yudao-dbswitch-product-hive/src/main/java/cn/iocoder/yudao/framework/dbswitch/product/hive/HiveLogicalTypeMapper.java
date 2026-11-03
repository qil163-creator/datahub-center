package cn.iocoder.yudao.framework.dbswitch.product.hive;

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
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeFidelity;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeMappingResult;

import java.sql.Types;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Hive 4.1.0（hive-jdbc 4.1.0 独立版，{@code SELECT version()} =
 * {@code 4.1.0 r75e40b7537c91a70ccaa31c397d21823c7528eeb}）的标量事实。已在本地
 * TEXTFILE 表；不映射 ACID、分区和视图。
 * <ul>
 * <li>DESCRIBE 报告小写 {@code tinyint/smallint/int/bigint/float/double/decimal(p,s)/string/
 * varchar(n)/char(n)/boolean/date/timestamp/binary}，不含字符集或排序规则。</li>
 * <li>CHAR 长度为 1..255，VARCHAR 长度为 1..65535；下一长度在编译时被拒绝。
 * VARCHAR(n) 和 CHAR(n) 按字符计数（{@code á}×5 可存入 VARCHAR(5)，占 10 字节）。
 * 超过 {@code n} 的类型转换会截断，而非拒绝。CHAR 列相等比较采用补空格语义
 * （{@code char(5) 'ab' = 'ab   '}），而 {@code length()} 返回未补空格的长度。VARCHAR 和
 * STRING 保留尾部空格。</li>
 * <li>STRING 和 BINARY 的 JDBC precision 为 2147483647。字符串可包含 U+0000。SQL 字面量的
 * 反斜杠表示转义（{@code 'a\\b'} 存储一个反斜杠）。</li>
 * <li>FLOAT/DOUBLE 为有符号类型，不保留 −0.0，可存储 NaN 和 Infinity。FLOAT 元数据报告
 * {@code java.lang.Float}，但 {@code getObject} 返回 Double。DECIMAL 元数据类为
 * {@code java.math.BigInteger}，但 {@code getObject} 返回 BigDecimal。precision 超过 38 时
 * 被拒绝；超出声明 decimal 的类型转换变为 NULL。超出 −128..127 的 Tinyint 类型转换会回绕。</li>
 * <li>DATE 范围为 0001-01-01..9999-12-31（年份 0 和 10000 在编译时失败）。TIMESTAMP 存储 9 位小数
 * 位。无秒的字面量（如 {@code 2020-01-02 03:04}）会变为当天午夜；
 * {@code 03:04:00} 保留分钟。普通 TEXTFILE 表不强制键唯一性。</li>
 * </ul>
 */
public final class HiveLogicalTypeMapper implements LogicalTypeMapper {

    public static final String VERSION = "4.1.0 r75e40b7537c91a70ccaa31c397d21823c7528eeb";

    private static final int CHAR_MAX = 255;

    private static final int VARCHAR_MAX = 65535;

    private static final long STRING_MAX_OCTETS = 2147483647L;

    private static final Pattern DECIMAL = Pattern.compile("decimal\\(([0-9]{1,2}),([0-9]{1,2})\\)");

    private static final Pattern TEXT = Pattern.compile("(char|varchar)\\(([0-9]{1,5})\\)");

    /**
     * 初始化 {@code HiveLogicalTypeMapper}。
     */
    public HiveLogicalTypeMapper() {
    }

    /**
     * 返回当前操作使用的数据库产品。
     */
    @Override
    public ProductTypeEnum product() {
        return ProductTypeEnum.HIVE;
    }

    /**
     * 将数据库原生列类型映射为逻辑类型。
     */
    @Override
    public TypeMappingResult<LogicalType> toLogical(ColumnDescription column) {
        ColumnMetadataEvidence facts = column.getMetadataEvidence();
        if (!verified(facts.provenance())) {
            return TypeMappingResult.unsupported("SOURCE_PROVENANCE_UNVERIFIED",
                    "HIVE native catalog provenance required");
        }
        if (facts.charset().state() == MetadataValue.State.UNKNOWN || facts.collation()
                .state() == MetadataValue.State.UNKNOWN) {
            return incomplete("charset/collation");
        }
        if (facts.charset().state() != MetadataValue.State.ABSENT || facts.collation()
                .state() != MetadataValue.State.ABSENT) {
            return inconsistent("charset");
        }
        String type = facts.nativeType().value().trim().toLowerCase(Locale.ROOT);
        if (type.endsWith(" unsigned")) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", "Hive integers are signed only");
        }
        return switch (type) {
            case "tinyint" -> integer(column, facts, 8, Types.TINYINT, "java.lang.Byte", 3);
            case "smallint" -> integer(column, facts, 16, Types.SMALLINT, "java.lang.Short", 5);
            case "int" -> integer(column, facts, 32, Types.INTEGER, "java.lang.Integer", 10);
            case "bigint" -> integer(column, facts, 64, Types.BIGINT, "java.lang.Long", 19);
            case "float" -> floating(column, facts, true);
            case "double" -> floating(column, facts, false);
            case "boolean" -> bool(column, facts);
            case "string" -> string(column, facts);
            case "date" -> date(column, facts);
            case "timestamp" -> timestamp(column, facts);
            case "binary" -> binary(column, facts);
            default -> parameterized(column, facts, type);
        };
    }

    /**
     * 解析带长度或精度参数的原生类型声明。
     */
    private TypeMappingResult<LogicalType> parameterized(ColumnDescription column, ColumnMetadataEvidence facts,
            String type) {
        var decimal = DECIMAL.matcher(type);
        if (decimal.matches()) {
            return decimal(column, facts, Integer.parseInt(decimal.group(1)), Integer.parseInt(decimal.group(2)));
        }
        var text = TEXT.matcher(type);
        if (text.matches()) {
            return character(column, facts, text.group(1).equals("char"), Long.parseLong(text.group(2)), type);
        }
        if (type.startsWith("array<") || type.startsWith("map<") || type.startsWith("struct<") || type.startsWith(
                "uniontype<")) {
            return TypeMappingResult.unsupported("VALUE_CODEC_UNSUPPORTED",
                    "Hive complex values are not scalar logical types");
        }
        if (type.startsWith("timestamp with") || type.startsWith("interval")) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", type);
        }
        return TypeMappingResult.unsupported("NATIVE_TYPE_UNRECOGNIZED", type);
    }

    /**
     * 根据原生列证据推导整数的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> integer(ColumnDescription column, ColumnMetadataEvidence facts,
            int bits, int jdbcType, String className, int precision) {
        if (column.getFieldType() != jdbcType || !column.isSigned() || !className.equals(column.getFiledTypeClassName())
                || column.getPrecisionSize() != precision || column.getScaleSize() != 0) {
            return inconsistent("integer");
        }
        if (!numeric(facts, precision, 0) || facts.characterLength().state() != MetadataValue.State.ABSENT) {
            return inconsistent("integer");
        }
        return exact(new IntegerType(bits, false));
    }

    /**
     * 根据原生列证据推导浮点数的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> floating(ColumnDescription column, ColumnMetadataEvidence facts,
            boolean single) {
        int jdbcType = single ? Types.FLOAT : Types.DOUBLE;
        String className = single ? "java.lang.Float" : "java.lang.Double";
        int precision = single ? 7 : 15;
        if (column.getFieldType() != jdbcType || column.isSigned() || !className.equals(column.getFiledTypeClassName())
                || column.getPrecisionSize() != precision || column.getScaleSize() != precision) {
            return inconsistent(single ? "float" : "double");
        }
        if (!numeric(facts, precision, precision) || facts.characterLength().state() != MetadataValue.State.ABSENT) {
            return inconsistent(single ? "float" : "double");
        }
        // −0.0 回读为 +0。NaN 和 Infinity 可通过列往返保持。Float32 会舍入。
        var result = exact(new FloatType(single ? 32 : 64, false, false, true));
        return single ? result.loss("FLOAT32_PRECISION_NOT_PRESERVED",
                "single-precision binary storage rounds decimal values") : result;
    }

    /**
     * 根据原生列证据推导精确小数的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> decimal(ColumnDescription column, ColumnMetadataEvidence facts,
            int precision, int scale) {
        if (precision < 1 || precision > 38 || scale < 0 || scale > precision) {
            return inconsistent("decimal");
        }
        if (!jdbc(column, Types.DECIMAL) || column.isSigned() || !"java.math.BigInteger".equals(
                column.getFiledTypeClassName())
                || column.getPrecisionSize() != precision || column.getScaleSize() != scale) {
            return inconsistent("decimal");
        }
        if (!numeric(facts, precision, scale) || facts.characterLength().state() != MetadataValue.State.ABSENT) {
            return inconsistent("decimal");
        }
        return exact(new DecimalType(precision, scale, false, false));
    }

    /**
     * 根据原生列证据推导布尔值的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> bool(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (column.getFieldType() != Types.BOOLEAN || column.isSigned() || !"java.lang.Boolean".equals(
                column.getFiledTypeClassName())
                || column.getPrecisionSize() != 1 || column.getScaleSize() != 0) {
            return inconsistent("boolean");
        }
        if (!numeric(facts, 1, 0) || facts.characterLength().state() != MetadataValue.State.ABSENT) {
            return inconsistent("boolean");
        }
        return exact(new BooleanType());
    }

    /**
     * 根据原生列证据推导字符串的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> string(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (!jdbc(column, Types.VARCHAR) || column.isSigned() || !"java.lang.String".equals(
                column.getFiledTypeClassName())
                || column.getPrecisionSize() != STRING_MAX_OCTETS || column.getScaleSize() != 0) {
            return inconsistent("string");
        }
        if (facts.characterLength().state() != MetadataValue.State.ABSENT
                || facts.numericPrecision().state() != MetadataValue.State.ABSENT
                || facts.numericScale().state() != MetadataValue.State.ABSENT) {
            return inconsistent("string");
        }
        return exact(new CharacterType(StorageKind.LARGE, null, LengthUnit.CHARACTERS, STRING_MAX_OCTETS, "UNICODE",
                Comparison.BINARY_NO_PAD, null, true));
    }

    /**
     * 根据原生列证据推导字符的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> character(ColumnDescription column, ColumnMetadataEvidence facts,
            boolean fixed, long length, String type) {
        int max = fixed ? CHAR_MAX : VARCHAR_MAX;
        if (length < 1 || length > max) {
            return inconsistent(type);
        }
        if (column.getFieldType() != (fixed ? Types.CHAR : Types.VARCHAR) || column.isSigned()
                || !"java.lang.String".equals(column.getFiledTypeClassName())
                || column.getPrecisionSize() != length || column.getScaleSize() != 0) {
            return inconsistent(type);
        }
        if (facts.characterLength().state() != MetadataValue.State.VALUE || facts.characterLength().value() != length) {
            return inconsistent(type);
        }
        if (facts.numericPrecision().state() != MetadataValue.State.ABSENT || facts.numericScale()
                .state() != MetadataValue.State.ABSENT) {
            return inconsistent(type);
        }
        return exact(
                new CharacterType(fixed ? StorageKind.FIXED : StorageKind.VARYING, length, LengthUnit.CHARACTERS, null,
                        "UNICODE", fixed ? Comparison.BINARY_PAD_SPACE : Comparison.BINARY_NO_PAD, null, true));
    }

    /**
     * 根据原生列证据推导日期的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> date(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (column.getFieldType() != Types.DATE || column.isSigned() || !"java.sql.Date".equals(
                column.getFiledTypeClassName())
                || column.getPrecisionSize() != 10 || column.getScaleSize() != 0) {
            return inconsistent("date");
        }
        if (!numeric(facts, 10, 0) || facts.characterLength().state() != MetadataValue.State.ABSENT) {
            return inconsistent("date");
        }
        return exact(new DateType(1, 9999, false));
    }

    /**
     * 根据原生列证据推导时间戳的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> timestamp(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (column.getFieldType() != Types.TIMESTAMP || column.isSigned() || !"java.sql.Timestamp".equals(
                column.getFiledTypeClassName())
                || column.getPrecisionSize() != 29 || column.getScaleSize() != 9) {
            return inconsistent("timestamp");
        }
        if (!numeric(facts, 29, 9) || facts.characterLength().state() != MetadataValue.State.ABSENT) {
            return inconsistent("timestamp");
        }
        return exact(new TimestampType(9, TimeZoneSemantics.LOCAL, 1, 9999, false));
    }

    /**
     * 根据原生列证据推导二进制的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> binary(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (column.getFieldType() != Types.BINARY || column.isSigned() || !"[B".equals(column.getFiledTypeClassName())
                || column.getPrecisionSize() != STRING_MAX_OCTETS || column.getScaleSize() != 0) {
            return inconsistent("binary");
        }
        if (facts.characterLength().state() != MetadataValue.State.ABSENT
                || facts.numericPrecision().state() != MetadataValue.State.ABSENT
                || facts.numericScale().state() != MetadataValue.State.ABSENT) {
            return inconsistent("binary");
        }
        return exact(new BinaryType(StorageKind.LARGE, null, STRING_MAX_OCTETS));
    }

    /**
     * 将逻辑列类型映射为当前产品的原生类型。
     */
    @Override
    public TypeMappingResult<NativeColumnType> toNative(LogicalColumn column) {
        LogicalType type = column.type();
        if (type instanceof CharacterType && column.key()) {
            return TypeMappingResult.unsupported("TEXT_KEY_EQUALITY_UNPROVEN", "cross-product character key");
        }
        TypeMappingResult<NativeColumnType> result = nativeType(type);
        if (column.key() && result.fidelity() != TypeFidelity.UNSUPPORTED) {
            result = result.loss("KEY_UNIQUENESS_NOT_ENFORCED",
                    "ordinary TEXTFILE tables do not enforce key uniqueness");
        }
        return result;
    }

    /**
     * 根据原生列信息确定数据库类型声明。
     */
    private TypeMappingResult<NativeColumnType> nativeType(LogicalType type) {
        if (type instanceof IntegerType integer) {
            String name = switch (integer.bits()) {
                case 8 -> integer.unsigned() ? "smallint" : "tinyint";
                case 16 -> integer.unsigned() ? "int" : "smallint";
                case 24 -> "int";
                case 32 -> integer.unsigned() ? "bigint" : "int";
                default -> integer.unsigned() ? null : "bigint";
            };
            return name == null ? TypeMappingResult.unsupported("UNSIGNED_INT64_UNMAPPED",
                    "Hive has no unsigned 64-bit integer")
                    : TypeMappingResult.exact(NativeColumnType.of(name));
        }
        if (type instanceof DecimalType decimal) {
            if (!decimal.bounded()) {
                return TypeMappingResult.unsupported("UNBOUNDED_NUMERIC_UNMAPPED",
                        "Hive decimal requires precision and scale");
            }
            if (decimal.precision() > 38 || decimal.scale() < 0 || decimal.scale() > decimal.precision()) {
                return TypeMappingResult.unsupported("NUMERIC_PRECISION_EXCEEDS_TARGET", decimal.canonical());
            }
            var result = TypeMappingResult.exact(
                    NativeColumnType.of("decimal(" + decimal.precision() + "," + decimal.scale() + ")"));
            return decimal.nan() ? result.loss("NAN_NOT_REPRESENTABLE",
                    "Hive decimal has no NaN; an out-of-range cast stores NULL") : result;
        }
        if (type instanceof FloatType value) {
            var result = TypeMappingResult.exact(NativeColumnType.of(value.bits() == 32 ? "float" : "double"));
            if (value.bits() == 32) {
                result = result.loss("FLOAT32_PRECISION_NOT_PRESERVED",
                        "single-precision binary storage rounds decimal values");
            }
            return value.negativeZero() ? result.loss("NEGATIVE_ZERO_NOT_PRESERVED", "−0.0 is stored as 0") : result;
        }
        if (type instanceof BooleanType) {
            return TypeMappingResult.exact(NativeColumnType.of("boolean"));
        }
        if (type instanceof CharacterType text) {
            return character(text);
        }
        if (type instanceof BinaryType binary) {
            if (binary.kind() == StorageKind.LARGE) {
                return TypeMappingResult.exact(NativeColumnType.of("binary"));
            }
            return TypeMappingResult.exact(NativeColumnType.of("binary"))
                    .loss("BINARY_LENGTH_NOT_ENFORCED", "Hive BINARY has no declared length");
        }
        if (type instanceof DateType date) {
            var result = TypeMappingResult.exact(NativeColumnType.of("date"));
            return date.infinity() || date.minYear() < 1 || date.maxYear() > 9999
                    ? result.loss("DATE_RANGE_NOT_REPRESENTABLE", "Hive DATE is 0001-01-01..9999-12-31") : result;
        }
        if (type instanceof TimeType) {
            return TypeMappingResult.unsupported("TIME_TYPE_UNAVAILABLE", "Hive has no TIME column");
        }
        if (type instanceof TimestampType timestamp) {
            if (timestamp.zone() != TimeZoneSemantics.LOCAL) {
                return TypeMappingResult.unsupported("TIME_ZONE_SEMANTICS_UNMAPPED",
                        "only the zone-less TIMESTAMP was probed");
            }
            var result = TypeMappingResult.exact(NativeColumnType.of("timestamp"));
            if (timestamp.fractionalDigits() > 9) {
                result = result.loss("TIME_FRACTION_TRUNCATED", "Hive TIMESTAMP keeps nanoseconds");
            }
            return timestamp.infinity() || timestamp.minYear() < 1 || timestamp.maxYear() > 9999
                    ? result.loss("DATE_RANGE_NOT_REPRESENTABLE", "Hive TIMESTAMP years are 0001..9999") : result;
        }
        if (type instanceof UuidType) {
            return TypeMappingResult.unsupported("UUID_TYPE_UNAVAILABLE", "Hive has no uuid column");
        }
        return TypeMappingResult.unsupported("TYPE_UNMAPPED", type.canonical() + " has no verified HIVE target");
    }

    /**
     * 根据原生列证据推导字符的逻辑类型及值域。
     */
    private TypeMappingResult<NativeColumnType> character(CharacterType text) {
        if (text.unit() != LengthUnit.CHARACTERS) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", "Hive CHAR/VARCHAR lengths are characters");
        }
        if (!text.repertoire().equals("UNICODE")) {
            return TypeMappingResult.unsupported("CHARSET_UNVERIFIED", "only Unicode text was probed");
        }
        String base;
        TypeMappingResult<NativeColumnType> result;
        switch (text.kind()) {
            case FIXED -> {
                if (text.length() > CHAR_MAX) {
                    return TypeMappingResult.unsupported("LENGTH_EXCEEDS_TARGET", text.canonical());
                }
                base = "char(" + text.length() + ")";
                result = TypeMappingResult.exact(NativeColumnType.of(base));
                if (text.comparison() != Comparison.BINARY_PAD_SPACE) {
                    result = result.loss("PAD_SPACE_EQUALITY_CHANGED", "Hive CHAR equality ignores trailing spaces");
                }
            }
            case VARYING -> {
                if (text.length() > VARCHAR_MAX) {
                    result = TypeMappingResult.exact(NativeColumnType.of("string"))
                            .loss("VARCHAR_LENGTH_NOT_ENFORCED", "values longer than varchar(65535) land on STRING");
                } else {
                    result = TypeMappingResult.exact(NativeColumnType.of("varchar(" + text.length() + ")"));
                }
                if (text.comparison() == Comparison.BINARY_PAD_SPACE) {
                    result = result.loss("PAD_SPACE_EQUALITY_CHANGED", "Hive VARCHAR/STRING keep trailing spaces");
                }
            }
            case LARGE -> {
                result = TypeMappingResult.exact(NativeColumnType.of("string"));
                if (text.comparison() == Comparison.BINARY_PAD_SPACE) {
                    result = result.loss("PAD_SPACE_EQUALITY_CHANGED", "Hive STRING keeps trailing spaces");
                }
            }
            default -> {
                return TypeMappingResult.unsupported("TYPE_UNMAPPED", text.canonical());
            }
        }
        if (text.comparison() == Comparison.NAMED) {
            return TypeMappingResult.unsupported("COLLATION_UNAVAILABLE", "Hive has no column collation");
        }
        return result;
    }

    /**
     * 返回当前已验证。
     */
    static boolean verified(MetadataProvenance provenance) {
        return provenance.product() == ProductTypeEnum.HIVE
                && "Apache Hive".equals(provenance.serverProduct())
                && "HIVE_CATALOG_V1".equals(provenance.method())
                && VERSION.equals(provenance.serverVersion());
    }

    /**
     * 根据原生列证据推导数值的逻辑类型及值域。
     */
    private static boolean numeric(ColumnMetadataEvidence facts, int precision, int scale) {
        return facts.numericPrecision().state() == MetadataValue.State.VALUE && facts.numericPrecision()
                .value() == precision
                && facts.numericScale().state() == MetadataValue.State.VALUE && facts.numericScale().value() == scale;
    }

    /**
     * 校验原生列报告的 JDBC 类型是否属于允许范围。
     */
    private static boolean jdbc(ColumnDescription column, int type) {
        return column.getFieldType() == type;
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
