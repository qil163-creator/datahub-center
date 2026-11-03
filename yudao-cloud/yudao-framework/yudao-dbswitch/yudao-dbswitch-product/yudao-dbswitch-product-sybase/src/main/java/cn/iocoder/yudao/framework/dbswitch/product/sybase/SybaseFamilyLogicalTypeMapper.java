package cn.iocoder.yudao.framework.dbswitch.product.sybase;

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
 * Sybase ASE 16.0 SP02 PL02（jconn4）原生事实与逻辑域相互映射，每项事实均在
 * 真实服务器探测，字符集为 iso_1、排序规则为 bin_iso_1，不从其他映射器复制：
 * <ul>
 * <li>tinyint 为无符号 0..255；bit 仅支持 NOT NULL 布尔值，不能存储 NULL；jconn4 将
 * smallint 和 tinyint 读取为 Integer，money 和 smallmoney 为精确固定小数 (19,4) 和 (10,4)。</li>
 * <li>float 和 double precision 均报告为 "double precision"（8 字节），显式拒绝 NaN，将 -0.0
 * 存储为 0；datetime 使用 1/300 秒刻度，插入时截断小数至刻度；smalldatetime 将秒舍入
 * 至最近分钟，范围为 1900 至 2079；time 隐式丢弃全部小数，bigdatetime 和 bigtime 保留微秒。</li>
 * <li>char(n) 和 unichar(n) 补空格，binary(n) 补零字节；比较忽略尾部空格，并在
 * bin_iso_1 下按区分大小写的二进制规则执行；unichar、univarchar、unitext 经 iso_1 客户端字符集传输，
 * 只有可由 iso_1 表示的字符能经已验证驱动路径往返。</li>
 * </ul>
 */
public final class SybaseFamilyLogicalTypeMapper implements LogicalTypeMapper {

    public enum Flavor {
        SYBASE(ProductTypeEnum.SYBASE);

        private final ProductTypeEnum product;

        /**
         * 初始化 {@code Flavor}。
         */
        Flavor(ProductTypeEnum product) {
            this.product = product;
        }
    }

    private static final long MAX_FIELD = (1L << 31) - 1;

    private static final Pattern DECIMAL = Pattern.compile("(decimal|numeric)\\(([0-9]{1,2}),([0-9]{1,2})\\)");

    private static final Pattern SIZED = Pattern.compile("((?:uni)?(?:var)?char)\\(([0-9]+)\\)");

    private static final Pattern BINARY_SIZED = Pattern.compile("(binary|varbinary)\\(([0-9]+)\\)");

    private final Flavor flavor;

    /**
     * 校验构造参数并初始化 {@code SybaseFamilyLogicalTypeMapper}。
     */
    public SybaseFamilyLogicalTypeMapper(Flavor flavor) {
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
        if (facts.charset().state() != MetadataValue.State.ABSENT) {
            return TypeMappingResult.unsupported("DRIVER_METADATA_INCONSISTENT",
                    flavor.product + " columns carry no column charset");
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
            case "tinyint":
                return jdbc(column, Types.TINYINT) && column.getPrecisionSize() == 3 && column.getScaleSize() == 0
                        ? exact(new IntegerType(8, true)) : inconsistent(type);
            case "smallint":
                return jdbc(column, Types.SMALLINT) && column.getPrecisionSize() == 5 && column.getScaleSize() == 0
                        ? exact(new IntegerType(16, false)) : inconsistent(type);
            case "int", "integer":
                return jdbc(column, Types.INTEGER) && column.getPrecisionSize() == 10 && column.getScaleSize() == 0
                        ? exact(new IntegerType(32, false)) : inconsistent(type);
            case "bigint":
                return jdbc(column, Types.BIGINT) && column.getPrecisionSize() == 19 && column.getScaleSize() == 0
                        ? exact(new IntegerType(64, false)) : inconsistent(type);
            case "bit":
                return jdbc(column, Types.BIT, Types.BOOLEAN) ? exact(new BooleanType()) : inconsistent(type);
            case "money":
                return jdbc(column, Types.DECIMAL) && column.getPrecisionSize() == 19 && column.getScaleSize() == 4
                        ? exact(new DecimalType(19, 4, false, false)) : inconsistent(type);
            case "smallmoney":
                return jdbc(column, Types.DECIMAL) && column.getPrecisionSize() == 10 && column.getScaleSize() == 4
                        ? exact(new DecimalType(10, 4, false, false)) : inconsistent(type);
            case "float", "double precision":
                return jdbc(column, Types.DOUBLE)
                        ? exact(new FloatType(64, false, false, false)) : inconsistent(type);
            case "real":
                return jdbc(column, Types.REAL) ? exact(new FloatType(32, false, false, false)) : inconsistent(type);
            case "date":
                return jdbc(column, Types.DATE) ? exact(new DateType(1, 9999, false)) : inconsistent(type);
            case "time":
                // 插入时隐式丢弃小数，因此存储值为整秒。
                return jdbc(column, Types.TIME) && column.getScaleSize() == 0
                        ? exact(new TimeType(0, false, TimeRange.TIME_OF_DAY)) : inconsistent(type);
            case "datetime":
                return jdbc(column, Types.TIMESTAMP) && column.getPrecisionSize() == 3 && column.getScaleSize() == 0
                        ? exact((LogicalType) new TimestampType(3, TimeZoneSemantics.LOCAL, 1753, 9999, false))
                        .note("DATETIME_TICK_DOMAIN",
                                "1/300s ticks; non-tick fractions truncate at insert") : inconsistent(type);
            case "smalldatetime":
                return jdbc(column, Types.TIMESTAMP) && column.getPrecisionSize() == 0 && column.getScaleSize() == 0
                        ? exact((LogicalType) new TimestampType(0, TimeZoneSemantics.LOCAL, 1900, 2079, false))
                        .note("SMALLDATETIME_MINUTE_ROUNDED",
                                "seconds round to the nearest minute at insert") : inconsistent(type);
            case "bigdatetime":
                return jdbc(column, Types.TIMESTAMP) && column.getPrecisionSize() == 6 && column.getScaleSize() == 6
                        ? exact(new TimestampType(6, TimeZoneSemantics.LOCAL, 1, 9999, false)) : inconsistent(type);
            case "bigtime":
                return jdbc(column, Types.TIME) && column.getPrecisionSize() == 6 && column.getScaleSize() == 6
                        ? exact(new TimeType(6, false, TimeRange.TIME_OF_DAY)) : inconsistent(type);
            case "text":
                return jdbc(column, Types.LONGVARCHAR)
                        ? character(StorageKind.LARGE, null, LengthUnit.OCTETS, "iso_1", MAX_FIELD) : inconsistent(
                        type);
            case "unitext":
                return jdbc(column, Types.LONGVARCHAR)
                        ? character(StorageKind.LARGE, null, LengthUnit.CHARACTERS, "iso_1", MAX_FIELD) : inconsistent(
                        type);
            case "image":
                return jdbc(column, Types.LONGVARBINARY)
                        ? exact(new BinaryType(StorageKind.LARGE, null, MAX_FIELD)) : inconsistent(type);
            default:
                break;
        }
        var decimal = DECIMAL.matcher(lower);
        if (decimal.matches()) {
            int precision = Integer.parseInt(decimal.group(2)), scale = Integer.parseInt(decimal.group(3));
            if (precision > 38) {
                return TypeMappingResult.unsupported("TYPE_UNMAPPED", "Sybase decimal precision exceeds 38");
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
        var sized = SIZED.matcher(lower);
        if (sized.matches()) {
            String base = sized.group(1);
            long length = Long.parseLong(sized.group(2));
            boolean unicode = base.startsWith("uni");
            boolean fixed = base.equals("char") || base.equals("unichar");
            int expected = fixed ? Types.CHAR : Types.VARCHAR;
            if (!jdbc(column, expected)) {
                return inconsistent(type);
            }
            if (facts.characterLength().state() != MetadataValue.State.VALUE || facts.characterLength()
                    .value() != length) {
                return incomplete("character length");
            }
            return character(fixed ? StorageKind.FIXED : StorageKind.VARYING, length,
                    unicode ? LengthUnit.CHARACTERS : LengthUnit.OCTETS, "iso_1", null);
        }
        var binary = BINARY_SIZED.matcher(lower);
        if (binary.matches()) {
            long length = Long.parseLong(binary.group(2));
            if (!jdbc(column, binary.group(1).equals("binary") ? Types.BINARY : Types.VARBINARY)) {
                return inconsistent(type);
            }
            if (facts.characterLength().state() != MetadataValue.State.VALUE || facts.characterLength()
                    .value() != length) {
                return incomplete("binary length");
            }
            return exact(
                    new BinaryType(binary.group(1).equals("binary") ? StorageKind.FIXED : StorageKind.VARYING, length,
                            null));
        }
        return TypeMappingResult.unsupported("NATIVE_TYPE_UNRECOGNIZED", type);
    }

    /**
     * 根据原生列证据推导字符的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> character(StorageKind kind, Long length, LengthUnit unit, String repertoire,
            Long maxOctets) {
        // bin_iso_1 按字节比较、忽略尾部空格且区分大小写，均已在此服务器验证。
        var result = exact(new CharacterType(kind, kind == StorageKind.LARGE ? null : length, unit,
                kind == StorageKind.LARGE ? maxOctets : null, repertoire, Comparison.BINARY_PAD_SPACE, null, false));
        // jconn4 客户端字符集为 iso_1，uni 类型只能往返 iso_1 可表示的字符。
        return unit == LengthUnit.CHARACTERS
                ? result.note("UNICODE_CODEC_LIMITED_TO_ISO_1",
                "only iso_1-representable characters round-trip through the verified driver") : result;
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
            // ASE bit 列不能存储 NULL，已在 DDL 验证，可空布尔值域没有精确目标。
            return column.nullable() ? TypeMappingResult.unsupported("BOOLEAN_NULL_UNAVAILABLE",
                    "Sybase bit cannot store NULL")
                    : TypeMappingResult.exact(NativeColumnType.of("bit"));
        }
        if (type instanceof CharacterType text) {
            return character(text, column);
        }
        if (type instanceof BinaryType binary) {
            return binary(binary);
        }
        if (type instanceof DateType date) {
            var result = TypeMappingResult.exact(NativeColumnType.of("date"));
            return date.minYear() < 1 || date.maxYear() > 9999 ? result.loss("DATE_RANGE_NOT_REPRESENTABLE",
                    date.canonical()) : result;
        }
        if (type instanceof TimeType time) {
            if (time.withTimeZone()) {
                return TypeMappingResult.unsupported("TIME_ZONE_SEMANTICS_UNMAPPED", time.canonical());
            }
            var result = TypeMappingResult.exact(NativeColumnType.of("time"));
            // Sybase time 在插入时隐式丢弃全部小数。
            if (time.fractionalDigits() > 0) {
                result = result.loss("TIME_FRACTION_TRUNCATED", "Sybase time stores whole seconds only");
            }
            return time.range() != TimeRange.TIME_OF_DAY ? result.note("TIME_OUTSIDE_DAY_NOT_REPRESENTABLE",
                    "24:00:00 is rejected loudly") : result;
        }
        if (type instanceof TimestampType timestamp) {
            if (timestamp.zone() != TimeZoneSemantics.LOCAL) {
                return TypeMappingResult.unsupported("TIME_ZONE_SEMANTICS_UNMAPPED",
                        flavor.product + " has no time-zone-aware timestamp");
            }
            int digits = timestamp.fractionalDigits();
            TypeMappingResult<NativeColumnType> result;
            if (digits == 0) {
                // 整秒始终与 datetime 刻度对齐。
                result = TypeMappingResult.exact(NativeColumnType.of("datetime"));
                if (timestamp.minYear() < 1753 || timestamp.maxYear() > 9999) {
                    result = result.loss("DATE_RANGE_NOT_REPRESENTABLE", timestamp.canonical());
                }
            } else if (digits <= 3) {
                result = TypeMappingResult.exact(NativeColumnType.of("datetime"))
                        .loss("DATETIME_TICK_ROUNDED", "fractions truncate to 1/300s ticks");
                if (timestamp.minYear() < 1753 || timestamp.maxYear() > 9999) {
                    result = result.loss("DATE_RANGE_NOT_REPRESENTABLE", timestamp.canonical());
                }
            } else if (digits <= 6) {
                result = TypeMappingResult.exact(NativeColumnType.of("bigdatetime"));
                if (timestamp.minYear() < 1 || timestamp.maxYear() > 9999) {
                    result = result.loss("DATE_RANGE_NOT_REPRESENTABLE", timestamp.canonical());
                }
            } else {
                result = TypeMappingResult.exact(NativeColumnType.of("bigdatetime"))
                        .loss("TIME_FRACTION_TRUNCATED", "bigdatetime keeps microseconds");
                if (timestamp.minYear() < 1 || timestamp.maxYear() > 9999) {
                    result = result.loss("DATE_RANGE_NOT_REPRESENTABLE", timestamp.canonical());
                }
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
        if (decimal.precision() > 38 || decimal.scale() < 0 || decimal.scale() > decimal.precision()) {
            return TypeMappingResult.unsupported("NUMERIC_PRECISION_EXCEEDS_TARGET", decimal.canonical());
        }
        var result = TypeMappingResult.exact(
                NativeColumnType.of("decimal(" + decimal.precision() + "," + decimal.scale() + ")"));
        return decimal.nan() ? result.note("NAN_REJECTED_BY_TARGET_DRIVER",
                "NaN has no decimal value; the write fails loudly") : result;
    }

    /**
     * 根据原生列证据推导浮点数的逻辑类型及值域。
     */
    private TypeMappingResult<NativeColumnType> floating(FloatType value) {
        var result = TypeMappingResult.exact(nonNegative(value.bits() == 32 ? "real" : "float", value.nonNegative()));
        if (value.nonFinite()) {
            result = result.note("NON_FINITE_REJECTED_BY_TARGET_DRIVER",
                    "NaN/Infinity writes fail loudly on " + flavor.product);
        }
        if (value.negativeZero()) {
            result = result.loss("NEGATIVE_ZERO_NOT_PRESERVED", "Sybase stores -0.0 as 0");
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
            boolean unicode = !singleByte;
            var result = TypeMappingResult.exact(NativeColumnType.of(unicode ? "unitext" : "text"));
            if (text.maxOctets() > MAX_FIELD) {
                result = result.note("VALUE_SIZE_LIMITED_BY_TARGET", "larger values fail loudly");
            }
            return unicode ? equality(result, text).note("TARGET_CHARSET_ISO_1",
                    "characters outside iso_1 are rejected loudly")
                    : equality(result, text);
        }
        // Sybase 比较 varchar 时忽略尾部空格，已验证；char(n) 会在存储时
        // 补齐短值并改变原值，因此目标统一为 varchar 或 univarchar，并单独评估其相等性。
        boolean unicode = text.unit() == LengthUnit.CHARACTERS || text.repertoire().equals("UNICODE") || familySource;
        long limit = unicode ? 8192 : 16384;
        if (text.length() > limit) {
            return TypeMappingResult.unsupported("LENGTH_EXCEEDS_TARGET", text.canonical());
        }
        String base = (unicode ? "univarchar" : "varchar") + "(" + text.length() + ")";
        var result = TypeMappingResult.exact(NativeColumnType.of(base));
        return unicode ? equality(result, text).note("TARGET_CHARSET_ISO_1",
                "characters outside iso_1 are rejected loudly")
                : equality(result, text);
    }

    /**
     * 生成当前键分量的相等比较表达式。
     */
    private TypeMappingResult<NativeColumnType> equality(TypeMappingResult<NativeColumnType> result,
            CharacterType text) {
        // bin_iso_1 忽略尾部空格且区分大小写，已验证。
        return switch (text.comparison()) {
            case BINARY_NO_PAD, DETERMINISTIC_DEFAULT -> result.loss("PAD_SPACE_EQUALITY_CHANGED",
                    "source equality is byte equality; Sybase ignores trailing spaces");
            case NAMED -> result.loss("COLLATION_EQUALITY_CHANGED",
                    "source collation " + text.collation() + " has no verified Sybase equivalent");
            case BINARY_PAD_SPACE -> result;
        };
    }

    /**
     * 根据原生列证据推导二进制的逻辑类型及值域。
     */
    private TypeMappingResult<NativeColumnType> binary(BinaryType binary) {
        switch (binary.kind()) {
            case LARGE -> {
                var result = TypeMappingResult.exact(NativeColumnType.of("image"));
                return binary.maxOctets() > MAX_FIELD ? result.note("VALUE_SIZE_LIMITED_BY_TARGET",
                        "larger values fail loudly") : result;
            }
            case FIXED -> {
                if (binary.length() > 16384) {
                    return TypeMappingResult.unsupported("LENGTH_EXCEEDS_TARGET", binary.canonical());
                }
                // binary(n) 会为短值补零字节；varbinary 配合长度 CHECK 保持精确值域。
                return TypeMappingResult.exact(NativeColumnType.checked("varbinary(" + binary.length() + ")",
                        "datalength(" + NativeColumnType.COLUMN + ") = " + binary.length()));
            }
            default -> {
                if (binary.length() > 16384) {
                    return TypeMappingResult.unsupported("LENGTH_EXCEEDS_TARGET", binary.canonical());
                }
                return TypeMappingResult.exact(NativeColumnType.of("varbinary(" + binary.length() + ")"));
            }
        }
    }

    /**
     * 根据原生列证据推导整数的逻辑类型及值域。
     */
    private static NativeColumnType integer(IntegerType type) {
        String name = switch (type.bits()) {
            case 8 -> type.unsigned() ? "tinyint" : "smallint";
            case 16 -> type.unsigned() ? "int" : "smallint";
            case 24 -> "int";
            case 32 -> type.unsigned() ? "bigint" : "int";
            default -> type.unsigned() ? "decimal(20,0)" : "bigint";
        };
        boolean exactWidth = !type.unsigned() && (type.bits() == 16 || type.bits() == 32 || type.bits() == 64)
                || type.unsigned() && type.bits() == 8;
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
