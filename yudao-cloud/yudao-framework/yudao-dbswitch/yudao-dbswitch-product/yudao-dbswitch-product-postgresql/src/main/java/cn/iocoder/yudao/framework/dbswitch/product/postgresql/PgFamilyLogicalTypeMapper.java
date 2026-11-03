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
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeFidelity;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeMappingResult;

import java.sql.Types;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * HighGo 6.0.4.2、UXsinoDB 2.1.0.3 和 Greenplum 7.1.0 的原生事实（目录 format_type 文本）与逻辑
 * 域相互映射；这些事实均在真实服务器探测，不是从 PostgreSQL 16 复制而来：
 * <ul>
 * <li>HighGo 将 {@code text} 报告为 JDBC LONGVARCHAR，与其他家族成员不同；在 DDL 阶段拒绝负标度
 * numeric；其驱动也拒绝 pgjdbc 用于 json 和 jsonb 的 Types.OTHER 字符串绑定
 * （22007 日期解析错误），因此 JSON 目标仍为 UNSUPPORTED。</li>
 * <li>UXDB（PG10 形态，仅使用 {@code ux_catalog}）的 getObject 返回 {@code UXobject} 或 {@code UXInterval}，
 * {@code bit(n)} 的 getObject 会因布尔类型转换失败；json 和 jsonb 目标也无法验证
 * （拒绝 String 加 Types.OTHER 绑定）；其显式列排序规则报告为 {@code ux_catalog."C"}。</li>
 * <li>Greenplum 使用 pgjdbc 42.7.13，因此值处理路径与已验证的 PostgreSQL 一致，包括
 * 类型化 json 和 jsonb 目标绑定；分布策略和追加优化存储属于表级事实，在类型
 * 映射器之外处理。</li>
 * <li>三者通过毫秒精度的 java.sql.Time 都会将 24:00:00 转换为 00:00:00，并按 JVM 时区物化
 * 时间戳；json、jsonb 和 interval 读取为驱动私有对象，尚无跨驱动编解码器。</li>
 * </ul>
 */
public final class PgFamilyLogicalTypeMapper implements LogicalTypeMapper {

    public enum Flavor {
        HIGHGO(ProductTypeEnum.HIGHGO, true, false, "pg_catalog"),
        UXDB(ProductTypeEnum.UXDB, false, false, "ux_catalog"),
        GREENPLUM(ProductTypeEnum.GREENPLUM, false, true, "pg_catalog");

        private final ProductTypeEnum product;

        /** HighGo 驱动将 text 报告为 LONGVARCHAR，而非 VARCHAR。 */
        private final boolean textLongVarchar;

        /** 已验证的 json 和 jsonb 目标 Types.OTHER 字符串绑定，仅适用于 pgjdbc。 */
        private final boolean jsonTargetBindable;

        /** 原生证据中限定显式列排序规则名称的目录模式。 */
        private final String collationNamespace;

        /**
         * 初始化 {@code Flavor}。
         */
        Flavor(ProductTypeEnum product, boolean textLongVarchar, boolean jsonTargetBindable,
                String collationNamespace) {
            this.product = product;
            this.textLongVarchar = textLongVarchar;
            this.jsonTargetBindable = jsonTargetBindable;
            this.collationNamespace = collationNamespace;
        }
    }

    static final int MIN_YEAR = -4712, MAX_DATE_YEAR = 5874897, MAX_TIMESTAMP_YEAR = 294276;

    private static final long MAX_FIELD_OCTETS = (1L << 30) - 1;

    private static final Pattern NUMERIC = Pattern.compile("numeric\\(([0-9]+),(-?[0-9]+)\\)");

    private static final Pattern SIZED = Pattern.compile("(character varying|character)\\(([0-9]+)\\)");

    private static final Pattern TEMPORAL = Pattern.compile(
            "(time|timestamp)(?:\\(([0-6])\\))? (with|without) time zone");

    private final Flavor flavor;

    /**
     * 校验构造参数并初始化 {@code PgFamilyLogicalTypeMapper}。
     */
    public PgFamilyLogicalTypeMapper(Flavor flavor) {
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
        if (facts.sequence().state() == MetadataValue.State.VALUE || facts.sequence()
                .state() == MetadataValue.State.UNSUPPORTED) {
            return TypeMappingResult.unsupported("SEQUENCE_DEPENDENCY_UNMAPPED", "owned or shared sequence dependency");
        }
        if (facts.charset().state() != MetadataValue.State.ABSENT) {
            return TypeMappingResult.unsupported("DRIVER_METADATA_INCONSISTENT",
                    flavor.product + " columns carry no column charset");
        }
        var result = logical(facts.nativeType().value(), column, facts);
        return facts.sequence().state() == MetadataValue.State.UNKNOWN && result.fidelity() != TypeFidelity.UNSUPPORTED
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
                return jdbc(column, type.equals("text") && flavor.textLongVarchar ? Types.LONGVARCHAR : Types.VARCHAR)
                        ? character(StorageKind.LARGE, null, facts) : inconsistent(type);
            case "bytea":
                return jdbc(column, Types.BINARY) ? exact(
                        new BinaryType(StorageKind.LARGE, null, MAX_FIELD_OCTETS)) : inconsistent(type);
            case "date":
                return jdbc(column, Types.DATE) ? exact(new DateType(MIN_YEAR, MAX_DATE_YEAR, true)) : inconsistent(
                        type);
            case "uuid":
                return jdbc(column, Types.OTHER) ? exact(new UuidType()) : inconsistent(type);
            case "json", "jsonb":
                return TypeMappingResult.unsupported("VALUE_CODEC_UNSUPPORTED", "the " + flavor.product
                        + " driver returns a private object for " + type + "; no cross-driver text codec");
            case "interval":
                return TypeMappingResult.unsupported("VALUE_CODEC_UNSUPPORTED",
                        "the " + flavor.product + " driver returns a private interval object");
            case "xml":
                return exact(new XmlType());
            default:
                break;
        }
        var numeric = NUMERIC.matcher(type);
        if (numeric.matches()) {
            int precision = Integer.parseInt(numeric.group(1)), scale = Integer.parseInt(numeric.group(2));
            // 已验证：HighGo、UXDB、Greenplum 在 DDL 阶段拒绝负标度 numeric，因此原生事实不会出现该类型。
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
            // 受约束的 numeric 仍允许 NaN；非家族驱动会显式拒绝（已验证），绝不舍入。
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
                var result = exact((LogicalType) new TimeType(digits, false, TimeRange.TIME_OF_DAY_INCLUDING_24_00))
                        .loss("TIME_24_00_WRAPPED", "the " + flavor.product + " driver reads 24:00:00 as 00:00:00");
                return digits > 3 ? result.loss("TIME_FRACTION_TRUNCATED",
                        "java.sql.Time keeps milliseconds only") : result;
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
                    "the driver materializes java.sql.Timestamp in the JVM zone; wall times in its DST gaps shift");
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
            if (!value.matches("(?:(?:pg|ux)_catalog\\.)?\"?(?:C|POSIX)\"?")) {
                return TypeMappingResult.unsupported("COLLATION_UNVERIFIED",
                        "explicit " + flavor.product + " collation " + value);
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
        if (type instanceof DecimalType decimal) {
            if (!decimal.bounded()) {
                return TypeMappingResult.exact(nonNegative("numeric", decimal.nonNegative()));
            }
            // 已验证：三个服务器都拒绝 scale<0，且 DDL 最多接受精度和标度 1000。
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
                        "octet_length(" + NativeColumnType.COLUMN + ") = " + binary.length());
                case VARYING -> NativeColumnType.checked("bytea",
                        "octet_length(" + NativeColumnType.COLUMN + ") <= " + binary.length());
                case LARGE -> NativeColumnType.of("bytea");
            });
            return binary.kind() == StorageKind.LARGE && binary.maxOctets() > MAX_FIELD_OCTETS
                    ? result.note("VALUE_SIZE_LIMITED_BY_TARGET",
                    flavor.product + " fields hold at most 1 GiB; larger values fail loudly") : result;
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
            var result = TypeMappingResult.exact(
                    NativeColumnType.of("time(" + Math.min(time.fractionalDigits(), 6) + ") without time zone"));
            if (time.fractionalDigits() > 6) {
                result = result.loss("TIME_FRACTION_TRUNCATED", flavor.product + " time keeps microseconds");
            }
            return time.range() == TimeRange.SIGNED_DURATION_838_HOURS
                    ? result.note("TIME_OUTSIDE_DAY_NOT_REPRESENTABLE",
                    flavor.product + " time holds 00:00:00..24:00:00") : result;
        }
        if (type instanceof TimestampType timestamp) {
            if (timestamp.zone() == TimeZoneSemantics.OFFSET) {
                return TypeMappingResult.unsupported("TIME_ZONE_SEMANTICS_UNMAPPED",
                        flavor.product + " timestamptz does not keep the offset");
            }
            int digits = Math.min(timestamp.fractionalDigits(), 6);
            var result = TypeMappingResult.exact(NativeColumnType.of("timestamp(" + digits + ") "
                    + (timestamp.zone() == TimeZoneSemantics.INSTANT ? "with" : "without") + " time zone"));
            if (timestamp.fractionalDigits() > 6) {
                result = result.loss("TIME_FRACTION_TRUNCATED", flavor.product + " timestamps keep microseconds");
            }
            return timestamp.minYear() < MIN_YEAR || timestamp.maxYear() > MAX_TIMESTAMP_YEAR
                    ? result.loss("DATE_RANGE_NOT_REPRESENTABLE", timestamp.canonical()) : result;
        }
        if (type instanceof UuidType) {
            return TypeMappingResult.exact(NativeColumnType.of("uuid"));
        }
        if (type instanceof JsonType json) {
            // 仅 pgjdbc（Greenplum）能使用目标 Types.OTHER 元数据类型绑定跨驱动 JSON 字符串，
            // HighGo 和 UXDB 驱动拒绝该绑定（已验证，错误码 22007）。
            return flavor.jsonTargetBindable ? TypeMappingResult.exact(
                    NativeColumnType.of(json.representation() == JsonRepresentation.TEXT ? "json" : "jsonb"))
                    : TypeMappingResult.unsupported("JSON_BINDING_UNVERIFIED",
                    flavor.product + " rejects the Types.OTHER String binding for json/jsonb");
        }
        return TypeMappingResult.unsupported("TYPE_UNMAPPED",
                type.canonical() + " has no verified " + flavor.product + " target");
    }

    /**
     * 根据原生列证据推导字符的逻辑类型及值域。
     */
    private TypeMappingResult<NativeColumnType> character(CharacterType text, LogicalColumn column) {
        if (column.key()) {
            return TypeMappingResult.unsupported("TEXT_KEY_EQUALITY_UNPROVEN", "cross-product character key");
        }
        if (text.unit() != LogicalType.LengthUnit.CHARACTERS) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", "octet length semantics");
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
                            "source NO PAD; " + flavor.product + " character(n) ignores trailing spaces");
                }
            }
            case BINARY_PAD_SPACE -> {
                result = TypeMappingResult.exact(NativeColumnType.of(base + " COLLATE \"C\""));
                if (!fixed) {
                    result = result.loss("PAD_SPACE_EQUALITY_CHANGED",
                            "source ignores trailing spaces; " + flavor.product + " text does not");
                }
            }
            case NAMED -> result = TypeMappingResult.exact(NativeColumnType.of(base))
                    .loss("COLLATION_EQUALITY_CHANGED",
                            "source collation " + text.collation() + " has no verified " + flavor.product + " equivalent");
            default -> result = TypeMappingResult.exact(NativeColumnType.of(base));
        }
        if (text.nulAllowed()) {
            result = result.note("NUL_CHARACTER_REJECTED_BY_TARGET", flavor.product + " rejects U+0000 loudly");
        }
        return result.note("TARGET_ENCODING_UTF8_REQUIRED",
                "characters outside the target database encoding fail loudly");
    }

    /**
     * 根据原生列证据推导整数的逻辑类型及值域。
     */
    private static NativeColumnType integer(IntegerType type) {
        // 不支持 1 字节、3 字节或无符号整数；扩大类型后用 CHECK 恢复源端值域，已验证三个产品都会强制执行。
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
