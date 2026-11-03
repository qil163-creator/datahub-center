package cn.iocoder.yudao.framework.dbswitch.product.sqlserver;

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
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * SQL Server 2022 RTM-CU27（mssql-jdbc 13.4.0）原生事实与逻辑域相互映射；每项事实均在
 * 真实服务器探测，不从其他映射器复制：
 * <ul>
 * <li>tinyint 为无符号 0..255，bit 为可空布尔值；money 和 smallmoney 为精确固定小数 (19,4) 和 (10,4)。
 * float(p) 保留 -0.0 并显式拒绝 NaN 和 Infinity；目录精度为 53、24，驱动报告为 15、7。</li>
 * <li>date 和 datetime2 保留 0001-01-01 至 9999-12-31；datetime 按 1/300 秒刻度存储，插入时舍入；smalldatetime
 * 将秒舍入至最近分钟，范围为 1900 至 2079；datetimeoffset 读取为私有编解码类型 microsoft.sql.DateTimeOffset。</li>
 * <li>time(s) 使用已验证的 JDBC 4.2 LocalTime 读取器和 ISO 文本绑定器，保留 0 至 7 位精度。
 * char(n) 和 binary(n) 分别补空格和零字节；
 * varchar 比较忽略尾部空格，默认排序规则 SQL_Latin1_General_CP1_CI_AS 不区分大小写，
 * 所以字符相等性仅按排序规则名称声明；varchar 使用代码页 1252，范围外字符会被替换。</li>
 * <li>uniqueidentifier 读取为规范字符串，从 java.util.UUID 绑定；XML 以字符串读取和绑定。
 * rowversion、sql_variant、hierarchyid、geometry 和 geography 没有可移植值域。</li>
 * </ul>
 */
public final class SqlServerFamilyLogicalTypeMapper implements LogicalTypeMapper {

    public enum Flavor {
        SQLSERVER(ProductTypeEnum.SQLSERVER);

        private final ProductTypeEnum product;

        /**
         * 初始化 {@code Flavor}。
         */
        Flavor(ProductTypeEnum product) {
            this.product = product;
        }
    }

    private static final long MAX_FIELD = (1L << 31) - 1;

    private static final String COLLATION = "SQL_Latin1_General_CP1_CI_AS";

    private static final Pattern DECIMAL = Pattern.compile("(decimal|numeric)\\(([0-9]{1,2}),([0-9]{1,2})\\)");

    private static final Pattern SIZED = Pattern.compile(
            "(char|varchar|nchar|nvarchar|binary|varbinary)\\(([0-9]+|max)\\)");

    private static final Pattern TEMPORAL = Pattern.compile("(datetime2|time|datetimeoffset)\\(([0-7])\\)");

    private static final Pattern FLOAT_P = Pattern.compile("float\\(([0-9]+)\\)");

    private final Flavor flavor;

    /**
     * 校验构造参数并初始化 {@code SqlServerFamilyLogicalTypeMapper}。
     */
    public SqlServerFamilyLogicalTypeMapper(Flavor flavor) {
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
            case "tinyint":
                return jdbc(column, Types.TINYINT) && column.getPrecisionSize() == 3 && column.getScaleSize() == 0
                        ? exact(new IntegerType(8, true)) : inconsistent(type);
            case "smallint":
                return jdbc(column, Types.SMALLINT) && column.getPrecisionSize() == 5 && column.getScaleSize() == 0
                        ? exact(new IntegerType(16, false)) : inconsistent(type);
            case "int":
                return jdbc(column, Types.INTEGER) && column.getPrecisionSize() == 10 && column.getScaleSize() == 0
                        ? exact(new IntegerType(32, false)) : inconsistent(type);
            case "bigint":
                return jdbc(column, Types.BIGINT) && column.getPrecisionSize() == 19 && column.getScaleSize() == 0
                        ? exact(new IntegerType(64, false)) : inconsistent(type);
            case "bit":
                return jdbc(column, Types.BIT) && column.getPrecisionSize() == 1
                        ? exact(new BooleanType()) : inconsistent(type);
            case "money":
                return jdbc(column, Types.DECIMAL) && column.getPrecisionSize() == 19 && column.getScaleSize() == 4
                        ? exact(new DecimalType(19, 4, false, false)) : inconsistent(type);
            case "smallmoney":
                return jdbc(column, Types.DECIMAL) && column.getPrecisionSize() == 10 && column.getScaleSize() == 4
                        ? exact(new DecimalType(10, 4, false, false)) : inconsistent(type);
            case "date":
                return jdbc(column, Types.DATE) && column.getPrecisionSize() == 10
                        ? exact(new DateType(1, 9999, false)) : inconsistent(type);
            case "datetime":
                return jdbc(column, Types.TIMESTAMP) && column.getPrecisionSize() == 23 && column.getScaleSize() == 3
                        ? exact((LogicalType) new TimestampType(3, TimeZoneSemantics.LOCAL, 1753, 9999, false))
                        .note("DATETIME_TICK_ROUNDED",
                                "1/300s ticks; non-tick fractions round at insert") : inconsistent(type);
            case "smalldatetime":
                return jdbc(column, Types.TIMESTAMP) && column.getPrecisionSize() == 16 && column.getScaleSize() == 0
                        ? exact((LogicalType) new TimestampType(0, TimeZoneSemantics.LOCAL, 1900, 2079, false))
                        .note("SMALLDATETIME_MINUTE_ROUNDED",
                                "seconds round to the nearest minute at insert") : inconsistent(type);
            case "uniqueidentifier":
                return jdbc(column, Types.CHAR) && column.getPrecisionSize() == 36
                        ? exact(new UuidType()) : inconsistent(type);
            case "xml":
                return jdbc(column, Types.LONGNVARCHAR, Types.SQLXML) ? exact(new XmlType()) : inconsistent(type);
            case "timestamp":
                return TypeMappingResult.unsupported("ROWVERSION_SEMANTICS",
                        "an auto-generated 8-byte stamp has no portable value domain");
            case "sql_variant":
                return TypeMappingResult.unsupported("VALUE_CODEC_UNSUPPORTED",
                        "the driver returns a private sql_variant object");
            case "hierarchyid", "geometry", "geography":
                return TypeMappingResult.unsupported("VALUE_CODEC_UNSUPPORTED",
                        "raw CLR bytes have no cross-product meaning");
            case "text":
                return jdbc(column, Types.LONGVARCHAR)
                        ? character(StorageKind.LARGE, null, LengthUnit.OCTETS, "cp1252", MAX_FIELD,
                        facts) : inconsistent(type);
            case "ntext":
                return jdbc(column, Types.LONGNVARCHAR)
                        ? character(StorageKind.LARGE, null, LengthUnit.CHARACTERS, "UNICODE", MAX_FIELD,
                        facts) : inconsistent(type);
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
                return TypeMappingResult.unsupported("TYPE_UNMAPPED", "SQL Server decimal precision exceeds 38");
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
        var floating = FLOAT_P.matcher(lower);
        if (floating.matches()) {
            int bits = Integer.parseInt(floating.group(1));
            if (bits <= 24) {
                return jdbc(column, Types.REAL) && facts.numericPrecision().state() == MetadataValue.State.VALUE
                        && facts.numericPrecision().value() == 24
                        ? exact(new FloatType(32, false, true, false)) : inconsistent(type);
            }
            return jdbc(column, Types.DOUBLE) && facts.numericPrecision().state() == MetadataValue.State.VALUE
                    && facts.numericPrecision().value() == 53
                    ? exact(new FloatType(64, false, true, false)) : inconsistent(type);
        }
        if (lower.equals("real")) {
            return jdbc(column, Types.REAL) && facts.numericPrecision().state() == MetadataValue.State.VALUE
                    && facts.numericPrecision().value() == 24
                    ? exact(new FloatType(32, false, true, false)) : inconsistent(type);
        }
        var temporal = TEMPORAL.matcher(lower);
        if (temporal.matches()) {
            int digits = Integer.parseInt(temporal.group(2));
            String base = temporal.group(1);
            if (base.equals("datetimeoffset")) {
                return TypeMappingResult.unsupported("VALUE_CODEC_UNSUPPORTED",
                        "the driver returns microsoft.sql.DateTimeOffset");
            }
            if (base.equals("time")) {
                if (!jdbc(column, Types.TIME) || column.getScaleSize() != digits) {
                    return inconsistent(type);
                }
                return exact(new TimeType(digits, false, TimeRange.TIME_OF_DAY));
            }
            if (!jdbc(column, Types.TIMESTAMP) || column.getScaleSize() != digits) {
                return inconsistent(type);
            }
            return exact(new TimestampType(digits, TimeZoneSemantics.LOCAL, 1, 9999, false));
        }
        var sized = SIZED.matcher(lower);
        if (sized.matches()) {
            String base = sized.group(1);
            boolean max = sized.group(2).equals("max");
            long length = max ? -1 : Long.parseLong(sized.group(2));
            boolean unicode = base.startsWith("n");
            if (base.equals("binary") || base.equals("varbinary")) {
                if (!jdbc(column, base.equals("binary") ? Types.BINARY : Types.VARBINARY)) {
                    return inconsistent(type);
                }
                if (facts.characterLength().state() != MetadataValue.State.VALUE) {
                    return incomplete("binary length");
                }
                if (facts.characterLength().value() != length) {
                    return inconsistent(type);
                }
                return max
                        ? exact(new BinaryType(StorageKind.LARGE, null, MAX_FIELD))
                        : exact(
                        new BinaryType(base.equals("binary") ? StorageKind.FIXED : StorageKind.VARYING, length, null));
            }
            int expected = switch (base) {
                case "char" -> Types.CHAR;
                case "varchar" -> Types.VARCHAR;
                case "nchar" -> Types.NCHAR;
                default -> Types.NVARCHAR;
            };
            if (!jdbc(column, expected)) {
                return inconsistent(type);
            }
            if (facts.characterLength().state() != MetadataValue.State.VALUE) {
                return incomplete("character length");
            }
            if (facts.characterLength().value() != length) {
                return inconsistent(type);
            }
            if (max) {
                return character(StorageKind.LARGE, null, unicode ? LengthUnit.CHARACTERS : LengthUnit.OCTETS,
                        unicode ? "UNICODE" : "cp1252", MAX_FIELD, facts);
            }
            return character(base.equals("char") || base.equals("nchar") ? StorageKind.FIXED : StorageKind.VARYING,
                    length,
                    unicode ? LengthUnit.CHARACTERS : LengthUnit.OCTETS, unicode ? "UNICODE" : "cp1252", null, facts);
        }
        return TypeMappingResult.unsupported("NATIVE_TYPE_UNRECOGNIZED", type);
    }

    /**
     * 根据原生列证据推导字符的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> character(StorageKind kind, Long length, LengthUnit unit, String repertoire,
            Long maxOctets, ColumnMetadataEvidence facts) {
        // 已验证服务器使用默认排序规则；其他排序规则会改变大小写、重音和补齐相等性，尚未验证。
        if (facts.collation().state() != MetadataValue.State.VALUE) {
            return incomplete("collation");
        }
        String collation = facts.collation().value();
        if (!COLLATION.equals(collation)) {
            return TypeMappingResult.unsupported("COLLATION_UNVERIFIED", "explicit SQL Server collation " + collation);
        }
        if (kind == StorageKind.LARGE && unit == LengthUnit.CHARACTERS) {
            return exact(new CharacterType(kind, null, unit, maxOctets, repertoire, Comparison.NAMED, collation, false))
                    .note("TARGET_UTF16_UNITS", "supplementary characters count as two UTF-16 units");
        }
        return exact(new CharacterType(kind, kind == StorageKind.LARGE ? null : length, unit,
                kind == StorageKind.LARGE ? maxOctets : null, repertoire, Comparison.NAMED, collation, false));
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
            return TypeMappingResult.exact(NativeColumnType.of("bit"));
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
            int digits = Math.min(time.fractionalDigits(), 7);
            var result = TypeMappingResult.exact(NativeColumnType.of("time(" + digits + ")"));
            if (time.fractionalDigits() > 7) {
                result = result.loss("TIME_FRACTION_TRUNCATED", "SQL Server time keeps 100ns fractions");
            }
            return time.range() != TimeRange.TIME_OF_DAY ? result.note("TIME_OUTSIDE_DAY_NOT_REPRESENTABLE",
                    "24:00:00 is rejected loudly") : result;
        }
        if (type instanceof TimestampType timestamp) {
            if (timestamp.zone() == TimeZoneSemantics.OFFSET) {
                return TypeMappingResult.unsupported("TIME_ZONE_SEMANTICS_UNMAPPED", timestamp.canonical());
            }
            int digits = Math.min(timestamp.fractionalDigits(), 7);
            var result = TypeMappingResult.exact(NativeColumnType.of(
                    timestamp.zone() == TimeZoneSemantics.INSTANT ? "datetimeoffset(" + digits + ")" : "datetime2(" + digits + ")"));
            if (timestamp.fractionalDigits() > 7) {
                result = result.note("TIME_FRACTION_REJECTED_BY_TARGET",
                        "fractions beyond 7 digits are rejected loudly");
            }
            if (timestamp.minYear() < 1 || timestamp.maxYear() > 9999) {
                result = result.loss("DATE_RANGE_NOT_REPRESENTABLE", timestamp.canonical());
            }
            return result;
        }
        if (type instanceof UuidType) {
            return TypeMappingResult.exact(NativeColumnType.of("uniqueidentifier"));
        }
        if (type instanceof XmlType) {
            return TypeMappingResult.exact(NativeColumnType.of("xml"))
                    .note("XML_BINDING_REQUIRES_TEXT", "non-String XML codecs are rejected loudly");
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
        var result = TypeMappingResult.exact(
                nonNegative(value.bits() == 32 ? "real" : "float(53)", value.nonNegative()));
        return value.nonFinite() ? result.note("NON_FINITE_REJECTED_BY_TARGET_DRIVER",
                "NaN/Infinity writes fail loudly on " + flavor.product) : result;
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
            // Unicode 源端使用 nvarchar(max)，单字节字符集使用 varchar(max)。
            boolean unicode = !singleByte;
            var result = TypeMappingResult.exact(NativeColumnType.of(unicode ? "nvarchar(max)" : "varchar(max)"));
            if (text.maxOctets() > MAX_FIELD) {
                result = result.note("VALUE_SIZE_LIMITED_BY_TARGET", "larger values fail loudly");
            }
            return comparison(result, text, unicode);
        }
        boolean unicode = text.unit() == LengthUnit.CHARACTERS || text.repertoire().equals("UNICODE") || familySource;
        long limit = unicode ? 4000 : 8000;
        if (text.length() > limit) {
            return TypeMappingResult.unsupported("LENGTH_EXCEEDS_TARGET", text.canonical());
        }
        // SQL Server 比较 varchar 时忽略尾部空格，已验证；char(n) 则会在存储时为短值
        // 补空格并改变原值，因此目标统一为 varchar 或 nvarchar，并单独评估其相等性。
        String base = (unicode ? "nvarchar" : "varchar") + "(" + text.length() + ")";
        var result = TypeMappingResult.exact(NativeColumnType.of(base));
        // 通过已验证写入器，单字节目标会显式拒绝代码页范围外字符。
        if (!unicode) {
            result = result.note("TARGET_CODE_PAGE_LIMITED", "characters outside the code page are rejected loudly");
        } else if (text.repertoire().equals("UNICODE")) {
            result = result.note("TARGET_UTF16_UNITS", "supplementary characters count as two UTF-16 units");
        }
        return comparison(result, text, unicode);
    }

    /**
     * 返回当前比较。
     */
    private TypeMappingResult<NativeColumnType> comparison(TypeMappingResult<NativeColumnType> result,
            CharacterType text, boolean unicode) {
        // SQL Server 在任意排序规则下都忽略尾部空格，各命名排序规则保留自己的大小写及重音规则。
        return switch (text.comparison()) {
            case BINARY_NO_PAD, DETERMINISTIC_DEFAULT -> result.loss("PAD_SPACE_EQUALITY_CHANGED",
                    "source equality is byte equality; " + flavor.product + " ignores trailing spaces");
            case NAMED -> result.loss("COLLATION_EQUALITY_CHANGED",
                    "source collation " + text.collation() + " has no verified " + flavor.product + " equivalent");
            case BINARY_PAD_SPACE -> result;
        };
    }

    /**
     * 根据原生列证据推导二进制的逻辑类型及值域。
     */
    private TypeMappingResult<NativeColumnType> binary(BinaryType binary) {
        switch (binary.kind()) {
            case LARGE -> {
                var result = TypeMappingResult.exact(NativeColumnType.of("varbinary(max)"));
                return binary.maxOctets() > MAX_FIELD ? result.note("VALUE_SIZE_LIMITED_BY_TARGET",
                        "larger values fail loudly") : result;
            }
            case FIXED -> {
                if (binary.length() > 8000) {
                    return TypeMappingResult.unsupported("LENGTH_EXCEEDS_TARGET", binary.canonical());
                }
                // binary(n) 会为短值补零字节；varbinary 配合长度 CHECK 保持精确值域。
                return TypeMappingResult.exact(NativeColumnType.checked("varbinary(" + binary.length() + ")",
                        "datalength(" + NativeColumnType.COLUMN + ") = " + binary.length()));
            }
            default -> {
                if (binary.length() > 8000) {
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
            case 8 -> type.unsigned() ? "smallint" : "smallint";
            case 16 -> type.unsigned() ? "int" : "smallint";
            case 24 -> "int";
            case 32 -> type.unsigned() ? "bigint" : "int";
            default -> type.unsigned() ? "decimal(20,0)" : "bigint";
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
