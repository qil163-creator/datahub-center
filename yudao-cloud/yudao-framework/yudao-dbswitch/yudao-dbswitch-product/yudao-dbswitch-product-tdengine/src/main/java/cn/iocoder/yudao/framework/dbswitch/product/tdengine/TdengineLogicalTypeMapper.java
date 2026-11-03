package cn.iocoder.yudao.framework.dbswitch.product.tdengine;

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
 * TDengine 3.3.8.8（WebSocket JDBC 3.9.0）普通列事实。以下每个边界都在该服务器上探测；
 * 不复制 MySQL 系列事实。标签不是列：JSON 仅作为 TAG 存在，此处拒绝。
 * <ul>
 * <li>目录类型来自 DESCRIBE。VARCHAR/NCHAR/VARBINARY 长度为 DESCRIBE 长度。JDBC 将
 * VARCHAR 报告为类型名 BINARY 和 {@code byte[]}；NCHAR 为 String。{@code BINARY(n)} 存为 VARCHAR(n)。</li>
 * <li>VARCHAR 和 VARBINARY 长度按字节计算，为 1..65517。NCHAR 长度按 Unicode 码点计算。最后
 * 证明可接受的长度为 16376；16377..19999 未探测，20000 被拒绝，因此更长 NCHAR 仍不受支持。
 * 预编译绑定可存储 U+0000；含有它的 SQL 字面值被拒绝。NCHAR 相等比较区分大小写，
 * 且不忽略尾部空格。</li>
 * <li>无符号整数是精确的 8/16/32/64 位值域。驱动将其拓宽为 Short/Integer/Long/BigInteger，
 * 除 BIGINT UNSIGNED 外均报告 signed=true。超过位宽的下一个值被拒绝。字面值
 * 2^64-1 在 TINYINT/SMALLINT/INT UNSIGNED 上存为类型最大值，在 BIGINT UNSIGNED 上精确存储。</li>
 * <li>FLOAT 是 IEEE binary32（十进制值舍入，保留 -0.0，拒绝 NaN/Infinity）。DOUBLE 保留
 * -0.0。DECIMAL 精度为 1..38；NaN 存为 0，多出的小数位会舍入。</li>
 * <li>TIMESTAMP 是 UTC 瞬时。小数位数由数据库 PRECISION（ms=3、us=6、ns=9）决定，不是列
 * 属性；JDBC 小数位数为 0。ms/us 的 SQL 字面值覆盖年份 0001..9999。超出约
 * 1678..2262 的纳秒值会被接受，但回读为不同瞬时。主时间戳拒绝 NULL；其他
 * 列接受 NULL。重复时间戳，或时间戳加 INT COMPOSITE KEY，会覆盖先前行。</li>
 * <li>可以创建 BLOB 和 GEOMETRY 列，但其值编解码器未验证往返。JSON、BOOLEAN
 * 以及未指定长度的 VARCHAR/NCHAR/VARBINARY 在 DDL 阶段被拒绝。</li>
 * </ul>
 */
public final class TdengineLogicalTypeMapper implements LogicalTypeMapper {

    static final Pattern VERSION = Pattern.compile("3\\.3\\.8\\.[0-9]+");

    private static final Pattern INTEGER = Pattern.compile("(tinyint|smallint|int|bigint)( unsigned)?");

    private static final Pattern DECIMAL = Pattern.compile("decimal\\(([0-9]{1,2}),\\s*([0-9]{1,2})\\)");

    private static final Pattern SIZED = Pattern.compile("(varchar|nchar|varbinary|geometry)\\(([0-9]{1,5})\\)");

    private static final int MAX_BYTES = 65517;

    private static final int MAX_NCHAR = 16376;

    /**
     * 初始化 {@code TdengineLogicalTypeMapper}。
     */
    public TdengineLogicalTypeMapper() {
    }

    /**
     * 返回当前操作使用的数据库产品。
     */
    @Override
    public ProductTypeEnum product() {
        return ProductTypeEnum.TDENGINE;
    }

    /**
     * 将数据库原生列类型映射为逻辑类型。
     */
    @Override
    public TypeMappingResult<LogicalType> toLogical(ColumnDescription column) {
        ColumnMetadataEvidence facts = column.getMetadataEvidence();
        if (!proved(facts.provenance())) {
            return TypeMappingResult.unsupported("SOURCE_PROVENANCE_UNVERIFIED",
                    "TDENGINE 3.3.8.x catalog provenance required");
        }
        if (facts.charset().state() != MetadataValue.State.ABSENT || facts.collation()
                .state() != MetadataValue.State.ABSENT) {
            return inconsistent("charset/collation");
        }
        String type = facts.nativeType().value().trim().toLowerCase(Locale.ROOT);
        if (type.equals("json") || type.startsWith("json")) {
            return TypeMappingResult.unsupported("JSON_TAG_ONLY",
                    "JSON is rejected as a regular column; it exists only as a TAG");
        }
        if (type.equals("blob")) {
            return TypeMappingResult.unsupported("VALUE_CODEC_UNSUPPORTED",
                    "BLOB was created but its bytes were not round-tripped");
        }
        if (type.equals("geometry") || type.startsWith("geometry(")) {
            return TypeMappingResult.unsupported("VALUE_CODEC_UNSUPPORTED",
                    "GEOMETRY was created but its bytes were not round-tripped");
        }
        if (type.equals("bool")) {
            return bool(column, facts);
        }
        var integer = INTEGER.matcher(type);
        if (integer.matches()) {
            return integer(column, facts, integer.group(1), integer.group(2) != null);
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
        var sized = SIZED.matcher(type);
        if (sized.matches() && !sized.group(1).equals("geometry")) {
            return sized(column, facts, sized.group(1), Integer.parseInt(sized.group(2)));
        }
        if (type.equals("timestamp")) {
            return timestamp(column, facts);
        }
        return TypeMappingResult.unsupported("NATIVE_TYPE_UNRECOGNIZED", type);
    }

    /**
     * 根据原生列证据推导布尔值的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> bool(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (!shape(column, Types.BOOLEAN, "BOOL", "java.lang.Boolean", 1, 0, false) || !noLength(facts)) {
            return inconsistent("bool");
        }
        return exact(new BooleanType());
    }

    /**
     * 根据原生列证据推导整数的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> integer(ColumnDescription column, ColumnMetadataEvidence facts, String base,
            boolean unsigned) {
        int bits = switch (base) {
            case "tinyint" -> 8;
            case "smallint" -> 16;
            case "int" -> 32;
            default -> 64;
        };
        int precision = switch (bits) {
            case 8 -> 3;
            case 16 -> 5;
            case 32 -> 10;
            default -> unsigned ? 20 : 19;
        };
        int jdbc = switch (bits) {
            case 8 -> unsigned ? Types.SMALLINT : Types.TINYINT;
            case 16 -> unsigned ? Types.INTEGER : Types.SMALLINT;
            case 32 -> unsigned ? Types.BIGINT : Types.INTEGER;
            default -> unsigned ? Types.NUMERIC : Types.BIGINT;
        };
        String typeName = base.toUpperCase(Locale.ROOT) + (unsigned ? " UNSIGNED" : "");
        String className = switch (bits) {
            case 8 -> unsigned ? "java.lang.Short" : "java.lang.Byte";
            case 16 -> unsigned ? "java.lang.Integer" : "java.lang.Short";
            case 32 -> unsigned ? "java.lang.Long" : "java.lang.Integer";
            default -> unsigned ? "java.math.BigInteger" : "java.lang.Long";
        };
        boolean signedFlag = !(unsigned && bits == 64);
        if (!shape(column, jdbc, typeName, className, precision, 0, signedFlag) || !noLength(facts)) {
            return inconsistent(typeName);
        }
        if (!precision(facts, precision, 0)) {
            return incomplete("numeric precision/scale");
        }
        return exact(new IntegerType(bits, unsigned));
    }

    /**
     * 根据原生列证据推导精确小数的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> decimal(ColumnDescription column, ColumnMetadataEvidence facts,
            int precision, int scale) {
        if (precision < 1 || precision > 38 || scale < 0 || scale > precision) {
            return inconsistent("decimal");
        }
        if (!shape(column, Types.DECIMAL, "DECIMAL", "java.math.BigDecimal", precision, scale, false) || !noLength(
                facts)) {
            return inconsistent("decimal");
        }
        if (!precision(facts, precision, scale)) {
            return incomplete("numeric precision/scale");
        }
        // 负值可往返保持。NaN 不属于存储值域：NaN 字面值存为 0。
        return exact(new DecimalType(precision, scale, false, false));
    }

    /**
     * 根据原生列证据推导浮点数的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> floating(ColumnDescription column, ColumnMetadataEvidence facts,
            boolean single) {
        int jdbc = single ? Types.FLOAT : Types.DOUBLE;
        String name = single ? "FLOAT" : "DOUBLE";
        String className = single ? "java.lang.Float" : "java.lang.Double";
        int precision = single ? 12 : 22;
        if (!shape(column, jdbc, name, className, precision, 0, true) || !noLength(facts)) {
            return inconsistent(name);
        }
        if (!precision(facts, precision, 0)) {
            return incomplete("float precision/scale");
        }
        var result = exact(new FloatType(single ? 32 : 64, false, true, false));
        return single ? result.loss("FLOAT32_PRECISION_NOT_PRESERVED",
                "binary32 storage rounds decimal values; -0.0 is preserved") : result;
    }

    /**
     * 为类型声明补充所需长度或精度信息。
     */
    private TypeMappingResult<LogicalType> sized(ColumnDescription column, ColumnMetadataEvidence facts, String kind,
            int length) {
        int max = kind.equals("nchar") ? MAX_NCHAR : MAX_BYTES;
        if (length < 1 || length > max) {
            return inconsistent(kind);
        }
        if (facts.characterLength().state() != MetadataValue.State.VALUE || facts.characterLength().value() != length) {
            return inconsistent(kind);
        }
        if (facts.numericPrecision().state() != MetadataValue.State.ABSENT || facts.numericScale()
                .state() != MetadataValue.State.ABSENT) {
            return inconsistent(kind);
        }
        return switch (kind) {
            case "varchar" -> shape(column, Types.VARCHAR, "BINARY", "[B", length, 0, false)
                    ? exact(new CharacterType(StorageKind.VARYING, (long) length, LengthUnit.OCTETS, null, "BYTES",
                    Comparison.BINARY_NO_PAD, null, true))
                    : inconsistent("varchar");
            case "nchar" -> shape(column, Types.NCHAR, "NCHAR", "java.lang.String", length, 0, false)
                    ? exact(
                    new CharacterType(StorageKind.VARYING, (long) length, LengthUnit.CHARACTERS, null, "UNICODE",
                            Comparison.BINARY_NO_PAD, null, true))
                    : inconsistent("nchar");
            case "varbinary" -> shape(column, Types.VARBINARY, "VARBINARY", "[B", length, 0, false)
                    ? exact(new BinaryType(StorageKind.VARYING, (long) length, null))
                    : inconsistent("varbinary");
            default -> TypeMappingResult.unsupported("NATIVE_TYPE_UNRECOGNIZED", kind);
        };
    }

    /**
     * 根据原生列证据推导时间戳的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> timestamp(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (!shape(column, Types.TIMESTAMP, "TIMESTAMP", "java.sql.Timestamp", 0, 0, false) || !noLength(facts)) {
            return inconsistent("timestamp");
        }
        if (facts.numericPrecision().state() != MetadataValue.State.ABSENT) {
            return inconsistent("timestamp");
        }
        if (facts.numericScale().state() != MetadataValue.State.VALUE) {
            return incomplete("timestamp precision");
        }
        int digits = facts.numericScale().value();
        if (digits != 3 && digits != 6 && digits != 9) {
            return inconsistent("timestamp");
        }
        int minYear = digits == 9 ? 1678 : 1;
        int maxYear = digits == 9 ? 2262 : 9999;
        var result = exact(new TimestampType(digits, TimeZoneSemantics.INSTANT, minYear, maxYear, false));
        return digits == 9 ? result.loss("TIMESTAMP_OVERFLOW_SILENT",
                "nanosecond literals outside about 1678..2262 are stored as a different instant") : result;
    }

    /**
     * 将逻辑列类型映射为当前产品的原生类型。
     */
    @Override
    public TypeMappingResult<NativeColumnType> toNative(LogicalColumn column) {
        if (column.key() && column.type() instanceof CharacterType) {
            return TypeMappingResult.unsupported("TEXT_KEY_EQUALITY_UNPROVEN", "cross-product character key");
        }
        TypeMappingResult<NativeColumnType> result = nativeType(column.type());
        if (column.key() && result.fidelity() != TypeFidelity.UNSUPPORTED) {
            result = result.loss("KEY_UNIQUENESS_NOT_ENFORCED",
                    "a duplicate timestamp, or timestamp plus INT composite key, overwrites the previous row");
        }
        return result;
    }

    /**
     * 根据原生列信息确定数据库类型声明。
     */
    private TypeMappingResult<NativeColumnType> nativeType(LogicalType type) {
        if (type instanceof IntegerType integer) {
            return TypeMappingResult.exact(NativeColumnType.of(integerName(integer)));
        }
        if (type instanceof DecimalType decimal) {
            if (!decimal.bounded()) {
                return TypeMappingResult.unsupported("UNBOUNDED_NUMERIC_UNMAPPED", "no unbounded exact numeric");
            }
            if (decimal.precision() > 38 || decimal.scale() < 0 || decimal.scale() > decimal.precision()) {
                return TypeMappingResult.unsupported("NUMERIC_PRECISION_EXCEEDS_TARGET", decimal.canonical());
            }
            var result = TypeMappingResult.exact(
                    NativeColumnType.of("DECIMAL(" + decimal.precision() + "," + decimal.scale() + ")"));
            return decimal.nan() ? result.loss("NAN_STORED_AS_ZERO", "a NaN literal is stored as 0") : result;
        }
        if (type instanceof FloatType value) {
            var result = TypeMappingResult.exact(NativeColumnType.of(value.bits() == 32 ? "FLOAT" : "DOUBLE"));
            if (value.bits() == 32) {
                result = result.loss("FLOAT32_PRECISION_NOT_PRESERVED", "binary32 storage rounds decimal values");
            }
            return value.nonFinite() ? result.note("NON_FINITE_REJECTED_BY_TARGET",
                    "NaN and Infinity are rejected") : result;
        }
        if (type instanceof BooleanType) {
            return TypeMappingResult.exact(NativeColumnType.of("BOOL"));
        }
        if (type instanceof CharacterType text) {
            return character(text);
        }
        if (type instanceof BinaryType binary) {
            return binary(binary);
        }
        if (type instanceof DateType) {
            return TypeMappingResult.unsupported("DATE_TYPE_UNAVAILABLE", "no date column type");
        }
        if (type instanceof TimeType) {
            return TypeMappingResult.unsupported("TIME_TYPE_UNAVAILABLE", "no time column type");
        }
        if (type instanceof TimestampType timestamp) {
            return timestamp(timestamp);
        }
        if (type instanceof UuidType) {
            return TypeMappingResult.unsupported("UUID_TYPE_UNAVAILABLE", "no native uuid type");
        }
        if (type instanceof JsonType) {
            return TypeMappingResult.unsupported("JSON_TAG_ONLY", "JSON is not a regular column");
        }
        return TypeMappingResult.unsupported("TYPE_UNMAPPED", type.canonical() + " has no verified TDENGINE column");
    }

    /**
     * 24 位值适合下一个已探测位宽。无符号 64 位具有专用列类型和 BigInteger 编解码器。
     */
    private static String integerName(IntegerType integer) {
        // 24 位值适合下一个已探测位宽。无符号 64 位具有专用列类型和 BigInteger 编解码器。
        return switch (integer.bits()) {
            case 8 -> integer.unsigned() ? "TINYINT UNSIGNED" : "TINYINT";
            case 16 -> integer.unsigned() ? "SMALLINT UNSIGNED" : "SMALLINT";
            case 24, 32 -> integer.unsigned() ? "INT UNSIGNED" : "INT";
            default -> integer.unsigned() ? "BIGINT UNSIGNED" : "BIGINT";
        };
    }

    /**
     * 根据原生列证据推导字符的逻辑类型及值域。
     */
    private static TypeMappingResult<NativeColumnType> character(CharacterType text) {
        if (text.kind() == StorageKind.LARGE) {
            return TypeMappingResult.unsupported("UNBOUNDED_CHARACTER_UNMAPPED", "NCHAR and VARCHAR require a length");
        }
        if (!text.repertoire().equals("UNICODE") && !text.repertoire().equals("utf8mb4") && !text.repertoire()
                .equals("BYTES")) {
            return TypeMappingResult.unsupported("CHARSET_UNVERIFIED", text.repertoire());
        }
        boolean octets = text.unit() == LengthUnit.OCTETS;
        if (!octets && text.unit() != LengthUnit.CHARACTERS) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", text.canonical());
        }
        long length = text.length();
        if (octets ? length > MAX_BYTES : length > MAX_NCHAR) {
            return TypeMappingResult.unsupported("LENGTH_EXCEEDS_TARGET", text.canonical());
        }
        var result = TypeMappingResult.exact(NativeColumnType.of((octets ? "VARCHAR(" : "NCHAR(") + length + ")"));
        if (text.comparison() == Comparison.NAMED) {
            return TypeMappingResult.unsupported("COLLATION_UNAVAILABLE", "TDengine has no column collation");
        }
        if (text.comparison() == Comparison.BINARY_PAD_SPACE) {
            result = result.loss("PAD_SPACE_EQUALITY_CHANGED", "NCHAR and VARCHAR keep trailing spaces");
        } else if (text.comparison() != Comparison.BINARY_NO_PAD) {
            result = result.note("ORDERING_MAY_DIFFER", "equality is case-sensitive; ordering was not probed");
        }
        return result;
    }

    /**
     * 根据原生列证据推导二进制的逻辑类型及值域。
     */
    private static TypeMappingResult<NativeColumnType> binary(BinaryType binary) {
        if (binary.kind() != StorageKind.VARYING) {
            return TypeMappingResult.unsupported(
                    binary.kind() == StorageKind.LARGE ? "UNBOUNDED_BINARY_UNMAPPED" : "TYPE_UNMAPPED",
                    "only VARBINARY(n) was probed");
        }
        if (binary.length() > MAX_BYTES) {
            return TypeMappingResult.unsupported("LENGTH_EXCEEDS_TARGET", binary.canonical());
        }
        return TypeMappingResult.exact(NativeColumnType.of("VARBINARY(" + binary.length() + ")"));
    }

    /**
     * 根据原生列证据推导时间戳的逻辑类型及值域。
     */
    private static TypeMappingResult<NativeColumnType> timestamp(TimestampType timestamp) {
        if (timestamp.zone() != TimeZoneSemantics.INSTANT) {
            return TypeMappingResult.unsupported("TIME_ZONE_SEMANTICS_UNMAPPED",
                    "TIMESTAMP stores a UTC instant, not a local wall time");
        }
        var result = TypeMappingResult.exact(NativeColumnType.of("TIMESTAMP"));
        // 列类型不能声明位数。未设置 PRECISION 时创建的数据库默认使用毫秒。
        if (timestamp.fractionalDigits() > 3) {
            result = result.loss("TIME_FRACTION_TRUNCATED",
                    "the TIMESTAMP column does not declare precision; the default database precision is milliseconds");
        }
        if (timestamp.infinity() || timestamp.minYear() < 1 || timestamp.maxYear() > 9999) {
            result = result.loss("DATE_RANGE_NOT_REPRESENTABLE",
                    "SQL timestamps cover year 0001..9999 and reject year 10000; infinity was not probed");
        }
        return result;
    }

    /**
     * 返回当前产品操作已经验证的能力证据。
     */
    static boolean proved(MetadataProvenance source) {
        return source.product() == ProductTypeEnum.TDENGINE && "TDengine".equals(source.serverProduct())
                && "TDENGINE_CATALOG_V1".equals(source.method()) && VERSION.matcher(source.serverVersion()).matches();
    }

    /**
     * 读取或构建当前关系及字段的结构信息。
     */
    private static boolean shape(ColumnDescription column, int jdbc, String typeName, String className,
            int precision, int scale, boolean signed) {
        return column.getFieldType() == jdbc && typeName.equals(column.getFieldTypeName())
                && className.equals(column.getFiledTypeClassName()) && column.getPrecisionSize() == precision
                && column.getScaleSize() == scale && column.isSigned() == signed;
    }

    /**
     * 检查当前原生类型未携带不适用的长度属性。
     */
    private static boolean noLength(ColumnMetadataEvidence facts) {
        return facts.characterLength().state() == MetadataValue.State.ABSENT;
    }

    /**
     * 返回精度。
     */
    private static boolean precision(ColumnMetadataEvidence facts, int precision, int scale) {
        return facts.numericPrecision().state() == MetadataValue.State.VALUE && facts.numericPrecision()
                .value() == precision
                && facts.numericScale().state() == MetadataValue.State.VALUE && facts.numericScale().value() == scale;
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
