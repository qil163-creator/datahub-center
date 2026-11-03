package cn.iocoder.yudao.framework.dbswitch.product.postgresql;

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
import java.util.regex.Pattern;

/**
 * 通过 pgjdbc 42.7，在 PostgreSQL 12 及以上版本的原生事实（pg_catalog.format_type 文本）与逻辑域之间相互映射。
 * PostgreSQL 16 普通读取通过 JDBC 4.2 LocalTime 访问器保留 TIME 微秒精度，并拒绝 24:00:00；
 * pgjdbc 在 JVM 时区构造 java.sql.Timestamp；json 和 jsonb 返回 PGobject，其他
 * 产品驱动无法绑定该对象，因此这些转换方向在文本编解码器实现前保持 UNSUPPORTED。
 */
public final class PostgresLogicalTypeMapper implements LogicalTypeMapper {

    static final int MIN_YEAR = -4712, MAX_DATE_YEAR = 5874897, MAX_TIMESTAMP_YEAR = 294276;

    private static final long MAX_FIELD_OCTETS = (1L << 30) - 1;

    private static final Pattern NUMERIC = Pattern.compile("numeric\\(([0-9]+),(-?[0-9]+)\\)");

    private static final Pattern SIZED = Pattern.compile("(character varying|character)\\(([0-9]+)\\)");

    private static final Pattern TEMPORAL = Pattern.compile(
            "(time|timestamp)(?:\\(([0-6])\\))? (with|without) time zone");

    /**
     * 返回当前操作使用的数据库产品。
     */
    @Override
    public ProductTypeEnum product() {
        return ProductTypeEnum.POSTGRESQL;
    }

    /**
     * 将数据库原生列类型映射为逻辑类型。
     */
    @Override
    public TypeMappingResult<LogicalType> toLogical(ColumnDescription column) {
        ColumnMetadataEvidence facts = column.getMetadataEvidence();
        if (!facts.provenance().nativeProduct(ProductTypeEnum.POSTGRESQL)) {
            return TypeMappingResult.unsupported("SOURCE_PROVENANCE_UNVERIFIED",
                    "PostgreSQL native catalog provenance required");
        }
        if (facts.sequence().state() == MetadataValue.State.VALUE || facts.sequence()
                .state() == MetadataValue.State.UNSUPPORTED) {
            return TypeMappingResult.unsupported("SEQUENCE_DEPENDENCY_UNMAPPED", "owned or shared sequence dependency");
        }
        if (facts.charset().state() != MetadataValue.State.ABSENT) {
            return TypeMappingResult.unsupported("DRIVER_METADATA_INCONSISTENT",
                    "PostgreSQL columns carry no column charset");
        }
        var result = logical(facts.nativeType().value(), column, facts);
        return facts.sequence()
                .state() == MetadataValue.State.UNKNOWN && result.fidelity() != cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeFidelity.UNSUPPORTED
                ? result.note("SEQUENCE_DEPENDENCY_UNOBSERVED",
                "no default/identity; owned-sequence objects are not recreated") : result;
    }

    /**
     * 返回逻辑。
     */
    private TypeMappingResult<LogicalType> logical(String type, ColumnDescription column,
            ColumnMetadataEvidence facts) {
        if (type.endsWith("[]")) {
            return TypeMappingResult.unsupported("VALUE_CODEC_UNSUPPORTED", "arrays require an ARRAY target");
        }
        switch (type) {
            case "smallint":
                return jdbc(column, Types.SMALLINT) ? exact(new IntegerType(16, false)) : inconsistent(type);
            case "integer":
                return jdbc(column, Types.INTEGER) ? exact(new IntegerType(32, false)) : inconsistent(type);
            case "bigint":
                return jdbc(column, Types.BIGINT) ? exact(new IntegerType(64, false)) : inconsistent(type);
            case "real":
                return jdbc(column, Types.REAL) ? exact(new FloatType(32, false, true, true)) : inconsistent(type);
            case "double precision":
                return jdbc(column, Types.DOUBLE) ? exact(new FloatType(64, false, true, true)) : inconsistent(type);
            case "boolean":
                return jdbc(column, Types.BIT, Types.BOOLEAN) ? exact(new BooleanType()) : inconsistent(type);
            case "numeric":
                return jdbc(column, Types.NUMERIC) ? exact(new DecimalType(null, null, false, true)) : inconsistent(
                        type);
            case "text", "character varying":
                return jdbc(column, Types.VARCHAR) ? character(StorageKind.LARGE, null, facts) : inconsistent(type);
            case "bytea":
                return jdbc(column, Types.BINARY) ? exact(
                        new BinaryType(StorageKind.LARGE, null, MAX_FIELD_OCTETS)) : inconsistent(type);
            case "date":
                return jdbc(column, Types.DATE) ? exact(new DateType(MIN_YEAR, MAX_DATE_YEAR, true)) : inconsistent(
                        type);
            case "uuid":
                return jdbc(column, Types.OTHER) ? exact(new UuidType()) : inconsistent(type);
            case "json", "jsonb":
                return TypeMappingResult.unsupported("VALUE_CODEC_UNSUPPORTED",
                        "pgjdbc returns PGobject for " + type + "; no cross-driver text codec");
            case "interval":
                return TypeMappingResult.unsupported("VALUE_CODEC_UNSUPPORTED", "pgjdbc returns PGInterval");
            case "xml":
                return exact(new XmlType());
            default:
                break;
        }
        var numeric = NUMERIC.matcher(type);
        if (numeric.matches()) {
            int precision = Integer.parseInt(numeric.group(1)), scale = Integer.parseInt(numeric.group(2));
            if (scale < 0) {
                return TypeMappingResult.unsupported("TYPE_UNMAPPED", "negative numeric scale");
            }
            if (facts.numericPrecision().state() != MetadataValue.State.VALUE || facts.numericScale()
                    .state() != MetadataValue.State.VALUE) {
                return incomplete("numeric precision/scale");
            }
            if (!jdbc(column, Types.NUMERIC) || !Integer.valueOf(precision).equals(facts.numericPrecision().value())
                    || !Integer.valueOf(scale)
                    .equals(facts.numericScale().value()) || column.getPrecisionSize() != precision
                    || column.getScaleSize() != scale) {
                return inconsistent(type);
            }
            // 受约束的 numeric 仍允许 NaN；非 PostgreSQL 驱动会显式拒绝（已验证），绝不舍入。
            return exact(new DecimalType(precision, scale, false, true));
        }
        var sized = SIZED.matcher(type);
        if (sized.matches()) {
            long length = Long.parseLong(sized.group(2));
            boolean fixed = sized.group(1).equals("character");
            if (!jdbc(column, fixed ? Types.CHAR : Types.VARCHAR)) {
                return inconsistent(type);
            }
            if (facts.characterLength().state() != MetadataValue.State.VALUE) {
                return incomplete("character length");
            }
            if (facts.characterLength().value() != length) {
                return inconsistent(type);
            }
            return character(fixed ? StorageKind.FIXED : StorageKind.VARYING, length, facts);
        }
        var temporal = TEMPORAL.matcher(type);
        if (temporal.matches()) {
            int digits = temporal.group(2) == null ? 6 : Integer.parseInt(temporal.group(2));
            boolean zone = temporal.group(3).equals("with");
            if (temporal.group(1).equals("time")) {
                if (zone) {
                    return TypeMappingResult.unsupported("TIME_ZONE_SEMANTICS_UNMAPPED", "time with time zone");
                }
                if (!jdbc(column, Types.TIME)) {
                    return inconsistent(type);
                }
                return exact((LogicalType) new TimeType(digits, false, TimeRange.TIME_OF_DAY_INCLUDING_24_00))
                        .loss("TIME_24_00_WRAPPED",
                                "native 24:00:00 has no exact clock carrier and is rejected before exposure");
            }
            if (!jdbc(column, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE)) {
                return inconsistent(type);
            }
            if (zone) {
                return exact(new TimestampType(digits, TimeZoneSemantics.INSTANT, MIN_YEAR, MAX_TIMESTAMP_YEAR, true));
            }
            var local = exact(
                    (LogicalType) new TimestampType(digits, TimeZoneSemantics.LOCAL, MIN_YEAR, MAX_TIMESTAMP_YEAR,
                            true));
            return java.time.ZoneId.systemDefault().getRules().isFixedOffset() ? local
                    : local.loss("TIMESTAMP_JVM_ZONE_DST_SHIFT",
                    "pgjdbc materializes java.sql.Timestamp in the JVM zone; wall times in its DST gaps shift");
        }
        return TypeMappingResult.unsupported("NATIVE_TYPE_UNRECOGNIZED", type);
    }

    /**
     * 根据原生列证据推导字符的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> character(StorageKind kind, Long length, ColumnMetadataEvidence facts) {
        Comparison comparison = Comparison.DETERMINISTIC_DEFAULT;
        String collation = null;
        if (facts.collation().state() == MetadataValue.State.VALUE) {
            String value = facts.collation().value();
            if (!value.matches("(?:pg_catalog\\.)?\"?(?:C|POSIX)\"?")) {
                return TypeMappingResult.unsupported("COLLATION_UNVERIFIED", "explicit PostgreSQL collation " + value);
            }
            // character(n) 在任何排序规则下都忽略尾部空格。
            comparison = kind == StorageKind.FIXED ? Comparison.BINARY_PAD_SPACE : Comparison.BINARY_NO_PAD;
            collation = "C";
        }
        // 数据库默认排序规则具有确定性，相等性按字节判断，排序顺序则取决于区域设置。
        return exact(new CharacterType(kind, length, LogicalType.LengthUnit.CHARACTERS,
                kind == StorageKind.LARGE ? MAX_FIELD_OCTETS : null, "UNICODE", comparison, collation, false));
    }

    /**
     * 将逻辑列类型映射为当前产品的原生类型。
     */
    @Override
    public TypeMappingResult<NativeColumnType> toNative(LogicalColumn column) {
        LogicalType type = column.type();
        if (type instanceof IntegerType integer) {
            return TypeMappingResult.exact(integer(integer));
        }
        if (type instanceof Int128Type integer) {
            return TypeMappingResult.exact(NativeColumnType.checked("numeric(39,0)",
                    NativeColumnType.COLUMN + " BETWEEN " + integer.minimum() + " AND " + integer.maximum()));
        }
        if (type instanceof DecimalType decimal) {
            if (!decimal.bounded()) {
                return TypeMappingResult.exact(nonNegative("numeric", decimal.nonNegative()));
            }
            if (decimal.precision() > 1000 || decimal.scale() < 0 || decimal.scale() > 1000) {
                return TypeMappingResult.unsupported("NUMERIC_PRECISION_EXCEEDS_TARGET", decimal.canonical());
            }
            return TypeMappingResult.exact(
                    nonNegative("numeric(" + decimal.precision() + "," + decimal.scale() + ")", decimal.nonNegative()));
        }
        if (type instanceof FloatType value) {
            return TypeMappingResult.exact(
                    nonNegative(value.bits() == 32 ? "real" : "double precision", value.nonNegative()));
        }
        if (type instanceof BooleanType) {
            return TypeMappingResult.exact(NativeColumnType.of("boolean"));
        }
        if (type instanceof CharacterType text) {
            return character(text, column);
        }
        if (type instanceof BinaryType binary) {
            var result = TypeMappingResult.exact(switch (binary.kind()) {
                case FIXED -> NativeColumnType.checked("bytea",
                        "pg_catalog.octet_length(" + NativeColumnType.COLUMN + ") = " + binary.length());
                case VARYING -> NativeColumnType.checked("bytea",
                        "pg_catalog.octet_length(" + NativeColumnType.COLUMN + ") <= " + binary.length());
                case LARGE -> NativeColumnType.of("bytea");
            });
            return binary.kind() == StorageKind.LARGE && binary.maxOctets() > MAX_FIELD_OCTETS
                    ? result.note("VALUE_SIZE_LIMITED_BY_TARGET",
                    "PostgreSQL fields hold at most 1 GiB; larger values fail loudly") : result;
        }
        if (type instanceof DateType date) {
            var result = TypeMappingResult.exact(NativeColumnType.of("date"));
            return date.minYear() < MIN_YEAR || date.maxYear() > MAX_DATE_YEAR ? result.loss(
                    "DATE_RANGE_NOT_REPRESENTABLE", date.canonical()) : result;
        }
        if (type instanceof TimeType time) {
            if (time.withTimeZone()) {
                return TypeMappingResult.unsupported("TIME_ZONE_SEMANTICS_UNMAPPED", time.canonical());
            }
            if (column.sourceProduct() == ProductTypeEnum.SQLSERVER && time.fractionalDigits() == 7
                    && time.range() == TimeRange.TIME_OF_DAY) {
                if (column.key()) {
                    return TypeMappingResult.unsupported("LOSSY_TIME_KEY_UNSUPPORTED",
                            "100ns clock keys can collide after microsecond quantization");
                }
                return TypeMappingResult.exact(NativeColumnType.of("time(6) without time zone"))
                        .loss("TIME_FRACTION_TRUNCATED", "SQLSERVER_TIME7_FLOOR_MICROS_V1; sourceQuantum=100ns; "
                                + "targetQuantum=1000ns; rounding=FLOOR; maximumLoss=900ns; midnightCarry=false");
            }
            var result = TypeMappingResult.exact(
                    NativeColumnType.of("time(" + Math.min(time.fractionalDigits(), 6) + ") without time zone"));
            if (time.fractionalDigits() > 6) {
                result = result.loss("TIME_FRACTION_TRUNCATED", "PostgreSQL time keeps microseconds");
            }
            return time.range() == TimeRange.SIGNED_DURATION_838_HOURS
                    ? result.note("TIME_OUTSIDE_DAY_NOT_REPRESENTABLE",
                    "PostgreSQL time holds 00:00:00..24:00:00") : result;
        }
        if (type instanceof TimestampType timestamp) {
            if (timestamp.zone() == TimeZoneSemantics.OFFSET) {
                return TypeMappingResult.unsupported("TIME_ZONE_SEMANTICS_UNMAPPED",
                        "PostgreSQL timestamptz does not keep the offset");
            }
            int digits = Math.min(timestamp.fractionalDigits(), 6);
            var result = TypeMappingResult.exact(NativeColumnType.of("timestamp(" + digits + ") "
                    + (timestamp.zone() == TimeZoneSemantics.INSTANT ? "with" : "without") + " time zone"));
            if (timestamp.fractionalDigits() > 6) {
                result = result.loss("TIME_FRACTION_TRUNCATED", "PostgreSQL timestamps keep microseconds");
            }
            return timestamp.minYear() < MIN_YEAR || timestamp.maxYear() > MAX_TIMESTAMP_YEAR
                    ? result.loss("DATE_RANGE_NOT_REPRESENTABLE", timestamp.canonical()) : result;
        }
        if (type instanceof UuidType) {
            return TypeMappingResult.exact(NativeColumnType.of("uuid"));
        }
        if (type instanceof JsonType json) {
            return TypeMappingResult.exact(
                    NativeColumnType.of(json.representation() == JsonRepresentation.TEXT ? "json" : "jsonb"));
        }
        return TypeMappingResult.unsupported("TYPE_UNMAPPED", type.canonical() + " has no verified PostgreSQL target");
    }

    /**
     * 根据原生列证据推导字符的逻辑类型及值域。
     */
    private static TypeMappingResult<NativeColumnType> character(CharacterType text, LogicalColumn column) {
        if (column.key()) {
            return TypeMappingResult.unsupported("TEXT_KEY_EQUALITY_UNPROVEN", "cross-product character key");
        }
        if (text.unit() == LogicalType.LengthUnit.OCTETS) {
            return octetCharacter(text, column);
        }
        String base = switch (text.kind()) {
            case FIXED -> "character(" + text.length() + ")";
            case VARYING -> "character varying(" + text.length() + ")";
            case LARGE -> "text";
        };
        if (text.length() != null && text.length() > 10485760) {
            return TypeMappingResult.unsupported("LENGTH_EXCEEDS_TARGET", base);
        }
        TypeMappingResult<NativeColumnType> result;
        boolean fixed = text.kind() == StorageKind.FIXED;
        switch (text.comparison()) {
            // character(n) 忽略尾部空格（PAD SPACE），character varying 和 text 则不忽略（NO PAD）。
            case BINARY_NO_PAD -> {
                result = TypeMappingResult.exact(NativeColumnType.of(base + " COLLATE \"C\""));
                if (fixed) {
                    result = result.loss("PAD_SPACE_EQUALITY_CHANGED",
                            "source NO PAD; PostgreSQL character(n) ignores trailing spaces");
                }
            }
            case BINARY_PAD_SPACE -> {
                result = TypeMappingResult.exact(NativeColumnType.of(base + " COLLATE \"C\""));
                if (!fixed) {
                    result = result.loss("PAD_SPACE_EQUALITY_CHANGED",
                            "source ignores trailing spaces; PostgreSQL text does not");
                }
            }
            case NAMED -> result = TypeMappingResult.exact(NativeColumnType.of(base))
                    .loss("COLLATION_EQUALITY_CHANGED",
                            "source collation " + text.collation() + " has no verified PostgreSQL equivalent");
            default -> result = TypeMappingResult.exact(NativeColumnType.of(base));
        }
        if (text.nulAllowed()) {
            result = result.note("NUL_CHARACTER_REJECTED_BY_TARGET", "PostgreSQL rejects U+0000 loudly");
        }
        return result.note("TARGET_ENCODING_UTF8_REQUIRED",
                "characters outside the target database encoding fail loudly");
    }

    /**
     * 仅有 Unicode 字符集不能证明源端字节采用 UTF8，例如 Oracle 的 BYTE 语义。
     */
    private static TypeMappingResult<NativeColumnType> octetCharacter(CharacterType text, LogicalColumn column) {
        // 仅有 Unicode 字符集不能证明源端字节采用 UTF8，例如 Oracle 的 BYTE 语义。
        // 准入范围保持在此映射实现已验证的两种原生 UTF8 源端布局内。
        if (column.sourceProduct() != ProductTypeEnum.DORIS && column.sourceProduct() != ProductTypeEnum.STARROCKS) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", "source UTF8 octet semantics were not verified");
        }
        // 仅保留已证的有限变长 Unicode 字节域；不能把字节上限误写成 varchar 字符上限。
        if (text.kind() != StorageKind.VARYING && text.kind() != StorageKind.LARGE || text.comparison() != Comparison.BINARY_NO_PAD) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", "only finite varying NO PAD octet text is verified");
        }
        if (!text.repertoire().equals("UNICODE") && !text.repertoire().equals("utf8mb4")) {
            return TypeMappingResult.unsupported("CHARSET_UNVERIFIED",
                    "only utf8mb4 or Unicode octet text is verified");
        }
        long octets = text.kind() == StorageKind.LARGE ? text.maxOctets() : text.length();
        if (text.kind() != StorageKind.LARGE && octets > MAX_FIELD_OCTETS) {
            return TypeMappingResult.unsupported("LENGTH_EXCEEDS_TARGET", "PostgreSQL text field octet limit");
        }
        // mapper 无目标连接；固定编码条件在每次非 NULL 写入时验证，不宣称建表前已探测编码。
        var result = TypeMappingResult.exact(NativeColumnType.checked("text COLLATE \"C\"",
                NativeColumnType.COLUMN + " IS NULL OR (pg_catalog.current_setting('server_encoding') = 'UTF8' AND pg_catalog.octet_length("
                        + NativeColumnType.COLUMN + ") <= " + Math.min(octets, MAX_FIELD_OCTETS) + ")"));
        if (octets > MAX_FIELD_OCTETS) {
            result = result.loss("LENGTH_EXCEEDS_TARGET",
                    "PostgreSQL text accepts fewer UTF8 octets; oversized values fail before storage");
        }
        if (text.nulAllowed()) {
            result = result.note("NUL_CHARACTER_REJECTED_BY_TARGET", "PostgreSQL rejects U+0000 loudly");
        }
        return result.note("TARGET_ENCODING_UTF8_REQUIRED",
                "octet CHECK rejects non-NULL values unless the target database encoding is UTF8");
    }

    /**
     * 根据原生列证据推导整数的逻辑类型及值域。
     */
    private static NativeColumnType integer(IntegerType type) {
        // PostgreSQL 不支持 1 字节、3 字节或无符号整数；扩大类型后使用 CHECK 恢复源端值域。
        String name = switch (type.bits()) {
            case 8 -> "smallint";
            case 16 -> type.unsigned() ? "integer" : "smallint";
            case 24 -> "integer";
            case 32 -> type.unsigned() ? "bigint" : "integer";
            default -> type.unsigned() ? "numeric(20,0)" : "bigint";
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
