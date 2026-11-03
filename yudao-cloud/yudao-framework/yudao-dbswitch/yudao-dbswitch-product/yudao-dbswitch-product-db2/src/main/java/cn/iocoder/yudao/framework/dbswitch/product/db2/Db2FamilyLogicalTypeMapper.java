package cn.iocoder.yudao.framework.dbswitch.product.db2;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalColumn;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalType;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalType.*;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalTypeMapper;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.NativeColumnType;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeMappingResult;

import java.sql.Types;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * DB2 LUW 12.1.5.0（db2jcc4 4.13.127）原生事实与逻辑值域的双向映射。每项事实均在
 * 真实服务端探测，数据库代码页为 1208，即 UTF-8；未复制其他映射器的结论：
 * <ul>
 * <li>BOOLEAN 类型存在，读取为 java.lang.Boolean；DECIMAL 最多 31 位；DOUBLE 明确拒绝 NaN，
 * 并将 -0.0 存为 0。DECFLOAT 没有精确逻辑值域：它舍入为 16 或 34 位有效数字，允许
 * sNaN、Infinity、-0，而驱动读取 NaN 值时会失败。</li>
 * <li>TIME 存储整秒，驱动静默丢弃小数部分；TIMESTAMP(p) 在
 * p &lt;= 9 时保留 p 位，超过 9 位的值在读取时截断；DATE 范围为 0001-01-01..9999-12-31。</li>
 * <li>VARCHAR(n) 按 UTF-8 字节计数，14 个中日韩字符共 42 字节，会超出 VARCHAR(40)；CHAR 用空格填充；
 * CHAR FOR BIT DATA 使用 0x20 字节填充，因此不属于固定二进制值域。比较忽略尾部
 * 空格且区分大小写；CLOB/DBCLOB 通过 Clob 编解码器读取，XML 读取为私有 DB2Xml 对象。</li>
 * </ul>
 */
public final class Db2FamilyLogicalTypeMapper implements LogicalTypeMapper {

    public enum Flavor {
        DB2(ProductTypeEnum.DB2);

        private final ProductTypeEnum product;

        /**
         * 初始化 {@code Flavor}。
         */
        Flavor(ProductTypeEnum product) {
            this.product = product;
        }
    }

    private static final long MAX_FIELD = (1L << 31) - 1;

    private static final int VARCHAR_MAX = 32672;

    private static final Pattern DECIMAL = Pattern.compile("decimal\\(([0-9]{1,2}),([0-9]{1,2})\\)");

    private static final Pattern SIZED = Pattern.compile(
            "(character|varchar|graphic|vargraphic)\\(([0-9]+)\\)( for bit data)?");

    private static final Pattern LOB = Pattern.compile("(clob|blob|dbclob)\\(([0-9]+)\\)");

    private static final Pattern TEMPORAL = Pattern.compile("timestamp\\(([0-9]{1,2})\\)");

    private final Flavor flavor;

    /**
     * 校验构造参数并初始化 {@code Db2FamilyLogicalTypeMapper}。
     */
    public Db2FamilyLogicalTypeMapper(Flavor flavor) {
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
        if (!facts.provenance().nativeProduct(flavor.product)) {
            return TypeMappingResult.unsupported("SOURCE_PROVENANCE_UNVERIFIED",
                    flavor.product + " native catalog provenance required");
        }
        if (facts.collation().state() != MetadataValue.State.ABSENT) {
            return TypeMappingResult.unsupported("DRIVER_METADATA_INCONSISTENT",
                    flavor.product + " columns carry no column collation");
        }
        if (facts.sequence().state() == MetadataValue.State.VALUE) {
            return TypeMappingResult.unsupported("SEQUENCE_DEPENDENCY_UNMAPPED", "sequence dependency");
        }
        return logical(facts.nativeType().value(), column, facts);
    }

    /**
     * 返回逻辑。
     */
    private TypeMappingResult<LogicalType> logical(String type, ColumnDescription column,
            ColumnMetadataEvidence facts) {
        String lower = type.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "smallint":
                return jdbc(column, Types.SMALLINT) && column.getPrecisionSize() == 5 && column.getScaleSize() == 0
                        ? exact(new IntegerType(16, false)) : inconsistent(type);
            case "integer":
                return jdbc(column, Types.INTEGER) && column.getPrecisionSize() == 10 && column.getScaleSize() == 0
                        ? exact(new IntegerType(32, false)) : inconsistent(type);
            case "bigint":
                return jdbc(column, Types.BIGINT) && column.getPrecisionSize() == 19 && column.getScaleSize() == 0
                        ? exact(new IntegerType(64, false)) : inconsistent(type);
            case "boolean":
                return TypeMappingResult.unsupported("BOOLEAN_DRIVER_CODEC_UNVERIFIED",
                        "db2jcc4 4.13.127 cannot read the BOOLEAN display size or bind JDBC type 16");
            case "real":
                return jdbc(column, Types.REAL) ? exact(new FloatType(32, false, false, false)) : inconsistent(type);
            case "double", "float":
                return jdbc(column, Types.DOUBLE) ? exact(new FloatType(64, false, false, false)) : inconsistent(type);
            case "decfloat":
                return TypeMappingResult.unsupported("DECFLOAT_SEMANTICS_UNMAPPED",
                        "significant-digit rounding, sNaN/Infinity/-0 and a NaN read failure have no logical domain");
            case "date":
                return jdbc(column, Types.DATE) ? exact(new DateType(1, 9999, false)) : inconsistent(type);
            case "time":
                return jdbc(column, Types.TIME) ? exact(new TimeType(0, false, TimeRange.TIME_OF_DAY)) : inconsistent(
                        type);
            case "long varchar":
                return jdbc(column, Types.LONGVARCHAR)
                        ? character(StorageKind.LARGE, null, LengthUnit.OCTETS, "UNICODE", 32700L) : inconsistent(type);
            case "xml":
                return TypeMappingResult.unsupported("VALUE_CODEC_UNSUPPORTED",
                        "the driver returns a private DB2Xml object");
            default:
                break;
        }
        var decimal = DECIMAL.matcher(lower);
        if (decimal.matches()) {
            int precision = Integer.parseInt(decimal.group(1)), scale = Integer.parseInt(decimal.group(2));
            if (precision > 31) {
                return TypeMappingResult.unsupported("TYPE_UNMAPPED", "DB2 decimal precision exceeds 31");
            }
            if (!jdbc(column, Types.DECIMAL,
                    Types.NUMERIC) || column.getPrecisionSize() != precision || column.getScaleSize() != scale) {
                return inconsistent(type);
            }
            if (facts.numericPrecision().state() != MetadataValue.State.VALUE || facts.numericScale()
                    .state() != MetadataValue.State.VALUE) {
                return incomplete("numeric precision/scale");
            }
            if (facts.numericPrecision().value() != precision || facts.numericScale().value() != scale) {
                return inconsistent(type);
            }
            return exact(new DecimalType(precision, scale, false, false));
        }
        var temporal = TEMPORAL.matcher(lower);
        if (temporal.matches()) {
            int digits = Integer.parseInt(temporal.group(1));
            if (!jdbc(column, Types.TIMESTAMP) || column.getScaleSize() != digits) {
                return inconsistent(type);
            }
            var result = exact(
                    (LogicalType) new TimestampType(Math.min(digits, 9), TimeZoneSemantics.LOCAL, 1, 9999, false));
            return digits > 9 ? result.loss("TIME_FRACTION_TRUNCATED",
                    "the driver reads at most 9 fractional digits") : result;
        }
        var sized = SIZED.matcher(lower);
        if (sized.matches()) {
            String base = sized.group(1);
            long length = Long.parseLong(sized.group(2));
            boolean bitData = sized.group(3) != null;
            if (bitData) {
                if (base.equals("character")) {
                    return TypeMappingResult.unsupported("CHAR_FOR_BIT_DATA_PADS_WITH_SPACES",
                            "0x20 padding collapses the fixed-binary equality domain");
                }
                if (!jdbc(column, Types.VARBINARY)) {
                    return inconsistent(type);
                }
                if (facts.characterLength().state() != MetadataValue.State.VALUE || facts.characterLength()
                        .value() != length) {
                    return incomplete("binary length");
                }
                return exact(new BinaryType(StorageKind.VARYING, length, null));
            }
            boolean fixed = base.equals("character") || base.equals("graphic");
            int expected = fixed ? Types.CHAR : Types.VARCHAR;
            // 新版 JCC 将 GRAPHIC/VARGRAPHIC 暴露为 JDBC 国家字符类型；原生值域未变。
            boolean compatible = base.equals("graphic") ? jdbc(column, Types.CHAR, Types.NCHAR)
                    : base.equals("vargraphic") ? jdbc(column, Types.VARCHAR, Types.NVARCHAR) : jdbc(column, expected);
            if (!compatible) {
                return inconsistent(type);
            }
            if (facts.characterLength().state() != MetadataValue.State.VALUE || facts.characterLength()
                    .value() != length) {
                return incomplete("character length");
            }
            return character(fixed ? StorageKind.FIXED : StorageKind.VARYING, length,
                    base.equals("graphic") || base.equals("vargraphic") ? LengthUnit.CHARACTERS : LengthUnit.OCTETS,
                    "UNICODE", null);
        }
        var lob = LOB.matcher(lower);
        if (lob.matches()) {
            String base = lob.group(1);
            long length = Long.parseLong(lob.group(2));
            if (base.equals("blob")) {
                return jdbc(column, Types.BLOB, Types.LONGVARBINARY)
                        ? exact(new BinaryType(StorageKind.LARGE, null, length)) : inconsistent(type);
            }
            if (!(base.equals("dbclob") ? jdbc(column, Types.CLOB, Types.NCLOB)
                    : jdbc(column, Types.CLOB, Types.LONGVARCHAR))) {
                return inconsistent(type);
            }
            // DBCLOB 按双字节字符计数，字节上限是声明长度的两倍。
            return character(StorageKind.LARGE, null, base.equals("dbclob") ? LengthUnit.CHARACTERS : LengthUnit.OCTETS,
                    "UNICODE", base.equals("dbclob") ? 2 * length : length);
        }
        return TypeMappingResult.unsupported("NATIVE_TYPE_UNRECOGNIZED", type);
    }

    /**
     * 根据原生列证据推导字符的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> character(StorageKind kind, Long length, LengthUnit unit, String repertoire,
            Long maxOctets) {
        // DB2 字符比较忽略尾部空格且区分大小写，已探测验证。
        return exact(new CharacterType(kind, kind == StorageKind.LARGE ? null : length, unit,
                kind == StorageKind.LARGE ? maxOctets : null, repertoire, Comparison.BINARY_PAD_SPACE, null, false));
    }

    // ---------------------------------------------------------------- 目标端

    /**
     * 将逻辑列类型映射为当前产品的原生类型。
     */
    @Override
    public TypeMappingResult<NativeColumnType> toNative(LogicalColumn column) {
        LogicalType type = column.type();
        if (type instanceof IntegerType integer) {
            return TypeMappingResult.exact(integer(integer));
        }
        if (type instanceof DecimalType decimal) {
            return decimal(decimal);
        }
        if (type instanceof FloatType value) {
            return floating(value);
        }
        if (type instanceof BooleanType) {
            return TypeMappingResult.unsupported("BOOLEAN_DRIVER_CODEC_UNVERIFIED",
                    "db2jcc4 4.13.127 cannot read the BOOLEAN display size or bind JDBC type 16");
        }
        if (type instanceof CharacterType text) {
            return character(text, column);
        }
        if (type instanceof BinaryType binary) {
            return binary(binary);
        }
        if (type instanceof DateType date) {
            var result = TypeMappingResult.exact(NativeColumnType.of("DATE"));
            return date.minYear() < 1 || date.maxYear() > 9999 ? result.loss("DATE_RANGE_NOT_REPRESENTABLE",
                    date.canonical()) : result;
        }
        if (type instanceof TimeType time) {
            if (time.withTimeZone()) {
                return TypeMappingResult.unsupported("TIME_ZONE_SEMANTICS_UNMAPPED", time.canonical());
            }
            var result = TypeMappingResult.exact(NativeColumnType.of("TIME"));
            // 驱动会静默丢弃小数部分，已探测验证。
            if (time.fractionalDigits() > 0) {
                result = result.loss("TIME_FRACTION_TRUNCATED", "DB2 TIME stores whole seconds only");
            }
            return time.range() != TimeRange.TIME_OF_DAY ? result.note("TIME_OUTSIDE_DAY_NOT_REPRESENTABLE",
                    "24:00:00 is rejected loudly") : result;
        }
        if (type instanceof TimestampType timestamp) {
            if (timestamp.zone() != TimeZoneSemantics.LOCAL) {
                return TypeMappingResult.unsupported("TIME_ZONE_SEMANTICS_UNMAPPED",
                        flavor.product + " has no verified time-zone-aware timestamp");
            }
            int digits = Math.min(timestamp.fractionalDigits(), 12);
            var result = TypeMappingResult.exact(NativeColumnType.of("TIMESTAMP(" + digits + ")"));
            // 驱动最多读取 9 位小数秒，超过 9 位的值无法往返保持不变。
            if (timestamp.fractionalDigits() > 9) {
                result = result.loss("TIME_FRACTION_TRUNCATED", "the driver reads at most 9 fractional digits");
            }
            if (timestamp.minYear() < 1 || timestamp.maxYear() > 9999) {
                result = result.loss("DATE_RANGE_NOT_REPRESENTABLE", timestamp.canonical());
            }
            return result;
        }
        return TypeMappingResult.unsupported("TYPE_UNMAPPED",
                type.canonical() + " has no verified " + flavor.product + " target");
    }

    /**
     * 根据原生列证据推导精确小数的逻辑类型及值域。
     */
    private TypeMappingResult<NativeColumnType> decimal(DecimalType decimal) {
        if (!decimal.bounded()) {
            return TypeMappingResult.unsupported("UNBOUNDED_NUMERIC_UNMAPPED", decimal.canonical());
        }
        if (decimal.precision() > 31 || decimal.scale() < 0 || decimal.scale() > decimal.precision()) {
            return TypeMappingResult.unsupported("NUMERIC_PRECISION_EXCEEDS_TARGET", decimal.canonical());
        }
        var result = TypeMappingResult.exact(
                NativeColumnType.of("DECIMAL(" + decimal.precision() + "," + decimal.scale() + ")"));
        return decimal.nan() ? result.note("NAN_REJECTED_BY_TARGET_DRIVER",
                "NaN has no decimal value; the write fails loudly") : result;
    }

    /**
     * 根据原生列证据推导浮点数的逻辑类型及值域。
     */
    private TypeMappingResult<NativeColumnType> floating(FloatType value) {
        var result = TypeMappingResult.exact(nonNegative(value.bits() == 32 ? "REAL" : "DOUBLE", value.nonNegative()));
        if (value.nonFinite()) {
            result = result.note("NON_FINITE_REJECTED_BY_TARGET_DRIVER",
                    "NaN/Infinity writes fail loudly on " + flavor.product);
        }
        if (value.negativeZero()) {
            result = result.loss("NEGATIVE_ZERO_NOT_PRESERVED", "DB2 stores -0.0 as 0");
        }
        return result;
    }

    /**
     * 根据原生列证据推导字符的逻辑类型及值域。
     */
    private TypeMappingResult<NativeColumnType> character(CharacterType text, LogicalColumn column) {
        if (column.key()) {
            return TypeMappingResult.unsupported("TEXT_KEY_EQUALITY_UNPROVEN", "cross-product character key");
        }
        boolean familySource = text.repertoire().equals("utf8mb4");
        boolean singleByte = text.repertoire().equals("iso_1") || text.repertoire().equals("cp1252");
        if (!text.repertoire().equals("UNICODE") && !familySource && !singleByte) {
            return TypeMappingResult.unsupported("CHARSET_UNVERIFIED",
                    "only Unicode and single-byte Latin repertoires are verified");
        }
        if (text.kind() == StorageKind.LARGE) {
            var result = TypeMappingResult.exact(NativeColumnType.of("CLOB"));
            if (text.maxOctets() > MAX_FIELD) {
                result = result.note("VALUE_SIZE_LIMITED_BY_TARGET", "larger values fail loudly");
            }
            return equality(result, text);
        }
        // DB2 VARCHAR 按数据库代码页中的 UTF-8 字节计数，已探测验证：以字节为单位的 Unicode 源可精确匹配，
        // 以字符为单位的源则为每个声明字符分配 4 字节，可容纳任意 UTF-8 字符。DB2 比较
        // VARCHAR 时按尾部空格填充，已探测验证，因此采用空格填充的 FIXED 源可保持值与相等语义。
        long length = text.unit() == LengthUnit.OCTETS || singleByte ? text.length() : 4 * text.length();
        if (length > VARCHAR_MAX) {
            return TypeMappingResult.unsupported("LENGTH_EXCEEDS_TARGET", text.canonical());
        }
        String base = "VARCHAR(" + length + ")";
        var result = TypeMappingResult.exact(NativeColumnType.of(base));
        if (text.unit() == LengthUnit.CHARACTERS && !singleByte) {
            result = result.note("TARGET_UTF8_BYTE_UNITS",
                    "the declared length is 4x the character length; longer values fail loudly");
        }
        return equality(result, text);
    }

    /**
     * 生成当前键分量的相等比较表达式。
     */
    private TypeMappingResult<NativeColumnType> equality(TypeMappingResult<NativeColumnType> result,
            CharacterType text) {
        // DB2 字符比较忽略尾部空格且区分大小写，已探测验证。
        return switch (text.comparison()) {
            case BINARY_NO_PAD, DETERMINISTIC_DEFAULT -> result.loss("PAD_SPACE_EQUALITY_CHANGED",
                    "source equality is byte equality; DB2 ignores trailing spaces");
            case NAMED -> result.loss("COLLATION_EQUALITY_CHANGED",
                    "source collation " + text.collation() + " has no verified DB2 equivalent");
            case BINARY_PAD_SPACE -> result;
        };
    }

    /**
     * 根据原生列证据推导二进制的逻辑类型及值域。
     */
    private TypeMappingResult<NativeColumnType> binary(BinaryType binary) {
        switch (binary.kind()) {
            case LARGE -> {
                var result = TypeMappingResult.exact(NativeColumnType.of("BLOB"));
                return binary.maxOctets() > MAX_FIELD ? result.note("VALUE_SIZE_LIMITED_BY_TARGET",
                        "larger values fail loudly") : result;
            }
            case FIXED -> {
                if (binary.length() > VARCHAR_MAX) {
                    return TypeMappingResult.unsupported("LENGTH_EXCEEDS_TARGET", binary.canonical());
                }
                // CHAR FOR BIT DATA 使用 0x20 填充；VARCHAR FOR BIT DATA 加长度 CHECK 可精确保持值域。
                return TypeMappingResult.exact(NativeColumnType.checked("VARCHAR(" + binary.length() + ") FOR BIT DATA",
                        "LENGTH(" + NativeColumnType.COLUMN + ") = " + binary.length()));
            }
            default -> {
                if (binary.length() > VARCHAR_MAX) {
                    return TypeMappingResult.unsupported("LENGTH_EXCEEDS_TARGET", binary.canonical());
                }
                return TypeMappingResult.exact(NativeColumnType.of("VARCHAR(" + binary.length() + ") FOR BIT DATA"));
            }
        }
    }

    /**
     * 根据原生列证据推导整数的逻辑类型及值域。
     */
    private static NativeColumnType integer(IntegerType type) {
        String name = switch (type.bits()) {
            case 8 -> type.unsigned() ? "SMALLINT" : "SMALLINT";
            case 16 -> type.unsigned() ? "INTEGER" : "SMALLINT";
            case 24 -> "INTEGER";
            case 32 -> type.unsigned() ? "BIGINT" : "INTEGER";
            default -> type.unsigned() ? "DECIMAL(20,0)" : "BIGINT";
        };
        boolean exactWidth = !type.unsigned() && (type.bits() == 16 || type.bits() == 32 || type.bits() == 64);
        return exactWidth ? NativeColumnType.of(name) : NativeColumnType.checked(name,
                NativeColumnType.COLUMN + " BETWEEN " + type.minimum() + " AND " + type.maximum());
    }

    /**
     * 校验输入数值非负后返回。
     */
    private static NativeColumnType nonNegative(String type, boolean nonNegative) {
        return nonNegative ? NativeColumnType.checked(type, NativeColumnType.COLUMN + " >= 0") : NativeColumnType.of(
                type);
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
