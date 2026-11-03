package cn.iocoder.yudao.framework.dbswitch.product.sqlserver2000;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL Server 2000 8.00.760 SP3 桌面引擎，使用 Microsoft JDBC Driver 3.0.1301.101。
 * JDBC 类型名不带长度，服务器不存在 {@code datetime2} 类型。
 * {@code MetadataProvenance#nativeProduct} 没有 SQLSERVER2000 分支，此映射器自行固定已探测的配置元组。
 */
public final class Sqlserver2000LogicalTypeMapper implements LogicalTypeMapper {

    public static final String SERVER_PRODUCT = "Microsoft SQL Server";

    public static final String SERVER_VERSION = "8.00.760";

    public static final String METHOD = "SQLSERVER2000_CATALOG_V1";

    public static final String COLLATION = "SQL_Latin1_General_CP1_CI_AS";

    static final long LOB_OCTETS = 2147483647L;

    private static final int DECIMAL_MAX = 38;

    private static final int VARCHAR_MAX = 8000;

    private static final int NVARCHAR_MAX = 4000;

    private static final Pattern DECIMAL = Pattern.compile("decimal\\((\\d+),(\\d+)\\)");

    private static final Pattern VARCHAR = Pattern.compile("varchar\\((\\d+)\\)");

    private static final Pattern NVARCHAR = Pattern.compile("nvarchar\\((\\d+)\\)");

    /**
     * 初始化 {@code Sqlserver2000LogicalTypeMapper}。
     */
    public Sqlserver2000LogicalTypeMapper() {
    }

    /**
     * 返回当前操作使用的数据库产品。
     */
    @Override
    public ProductTypeEnum product() {
        return ProductTypeEnum.SQLSERVER2000;
    }

    /**
     * 将数据库原生列类型映射为逻辑类型。
     */
    @Override
    public TypeMappingResult<LogicalType> toLogical(ColumnDescription column) {
        ColumnMetadataEvidence facts = column.getMetadataEvidence();
        if (!provenance(facts.provenance())) {
            return TypeMappingResult.unsupported("SOURCE_PROVENANCE_UNVERIFIED",
                    "SQLSERVER2000 requires Microsoft SQL Server 8.00.760 and SQLSERVER2000_CATALOG_V1");
        }
        if (facts.charset().state() == MetadataValue.State.UNKNOWN) {
            return incomplete("charset");
        }
        if (facts.charset().state() != MetadataValue.State.ABSENT) {
            return TypeMappingResult.unsupported("DRIVER_METADATA_INCONSISTENT",
                    "SQL Server 2000 columns carry no column charset");
        }
        if (facts.sequence().state() == MetadataValue.State.VALUE || facts.sequence()
                .state() == MetadataValue.State.UNSUPPORTED) {
            return TypeMappingResult.unsupported("SEQUENCE_DEPENDENCY_UNMAPPED", "sequence dependency");
        }
        if (facts.sequence().state() != MetadataValue.State.ABSENT) {
            return incomplete("sequence");
        }
        if (facts.nativeType().state() != MetadataValue.State.VALUE) {
            return incomplete("native type");
        }
        return logical(facts.nativeType().value().trim().toLowerCase(Locale.ROOT), column, facts);
    }

    /**
     * 返回逻辑。
     */
    private TypeMappingResult<LogicalType> logical(String type, ColumnDescription column,
            ColumnMetadataEvidence facts) {
        switch (type) {
            case "bit":
                return fixed(column, facts, Types.BIT, "bit", "java.lang.Boolean", false, 1, 0, 1, 1, 0,
                        new BooleanType());
            case "tinyint":
                return fixed(column, facts, Types.TINYINT, "tinyint", "java.lang.Short", false, 3, 0, 3, 3, 0,
                        new IntegerType(8, true));
            case "smallint":
                return fixed(column, facts, Types.SMALLINT, "smallint", "java.lang.Short", true, 5, 0, 6, 5, 0,
                        new IntegerType(16, false));
            case "int":
                return fixed(column, facts, Types.INTEGER, "int", "java.lang.Integer", true, 10, 0, 11, 10, 0,
                        new IntegerType(32, false));
            case "bigint":
                return fixed(column, facts, Types.BIGINT, "bigint", "java.lang.Long", true, 19, 0, 20, 19, 0,
                        new IntegerType(64, false));
            case "money":
                return fixed(column, facts, Types.DECIMAL, "money", "java.math.BigDecimal", true, 19, 4, 21, 19, 4,
                        new DecimalType(19, 4, false, false));
            case "datetime":
                return fixed(column, facts, Types.TIMESTAMP, "datetime", "java.sql.Timestamp", false, 23, 3, 23, 23, 3,
                        new TimestampType(3, TimeZoneSemantics.LOCAL, 1753, 9999, false));
            case "smalldatetime":
                return smalldatetime(column, facts);
            case "text":
                return text(column, facts);
            case "image":
                return image(column, facts);
            default:
                break;
        }
        Matcher decimal = DECIMAL.matcher(type);
        if (type.equals("decimal") || decimal.matches()) {
            return decimal(type, column, facts, decimal);
        }
        Matcher varchar = VARCHAR.matcher(type);
        if (type.equals("varchar") || varchar.matches()) {
            return varchar(type, column, facts, varchar, false);
        }
        Matcher nvarchar = NVARCHAR.matcher(type);
        if (type.equals("nvarchar") || nvarchar.matches()) {
            return varchar(type, column, facts, nvarchar, true);
        }
        return TypeMappingResult.unsupported("NATIVE_TYPE_UNRECOGNIZED", type.isEmpty() ? "<empty>" : type);
    }

    /**
     * 根据原生列证据推导精确小数的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> decimal(String type, ColumnDescription column, ColumnMetadataEvidence facts,
            Matcher sized) {
        Integer precision = sized.matches() ? parse(sized.group(1)) : integerValue(facts.numericPrecision());
        Integer scale = sized.matches() ? parse(sized.group(2)) : integerValue(facts.numericScale());
        if (precision == null || scale == null) {
            return facts.numericPrecision().state() == MetadataValue.State.UNKNOWN
                    || facts.numericScale().state() == MetadataValue.State.UNKNOWN ? incomplete(
                    "numeric precision/scale") : inconsistent(type);
        }
        if (precision < 1 || precision > DECIMAL_MAX || scale < 0 || scale > precision) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", "decimal(" + precision + "," + scale + ")");
        }
        TypeMappingResult<LogicalType> blocked = optional(facts.numericPrecision(), precision, "numeric precision",
                type);
        if (blocked != null) {
            return blocked;
        }
        blocked = optional(facts.numericScale(), scale, "numeric scale", type);
        if (blocked != null) {
            return blocked;
        }
        if (!driver(column, Types.DECIMAL, "decimal", "java.math.BigDecimal", true, precision, scale, null)) {
            return inconsistent(type);
        }
        blocked = absentLength(facts, type);
        if (blocked != null) {
            return blocked;
        }
        blocked = plainCollation(facts);
        if (blocked != null) {
            return blocked;
        }
        return exact(new DecimalType(precision, scale, false, false));
    }

    /**
     * 返回当前变长字符串。
     */
    private TypeMappingResult<LogicalType> varchar(String type, ColumnDescription column, ColumnMetadataEvidence facts,
            Matcher sized, boolean unicode) {
        int jdbc = unicode ? Types.NVARCHAR : Types.VARCHAR;
        String typeName = unicode ? "nvarchar" : "varchar";
        int limit = unicode ? NVARCHAR_MAX : VARCHAR_MAX;
        Long length = sized.matches() ? parseLong(sized.group(1)) : longValue(facts.characterLength());
        if (length == null) {
            return facts.characterLength().state() == MetadataValue.State.UNKNOWN
                    ? incomplete("character length") : inconsistent(type);
        }
        if (length < 1 || length > limit) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", typeName + "(" + length + ")");
        }
        TypeMappingResult<LogicalType> blocked = optional(facts.characterLength(), length, "character length", type);
        if (blocked != null) {
            return blocked;
        }
        if (!driver(column, jdbc, typeName, "java.lang.String", false, length.intValue(), 0, length.intValue())) {
            return inconsistent(type);
        }
        blocked = absentNumbers(facts, type);
        if (blocked != null) {
            return blocked;
        }
        blocked = namedCollation(facts);
        if (blocked != null) {
            return blocked;
        }
        return exact(new CharacterType(StorageKind.VARYING, length, LengthUnit.CHARACTERS, null,
                unicode ? "UNICODE" : COLLATION, Comparison.NAMED, COLLATION, false));
    }

    /**
     * 根据原生列证据推导文本的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> text(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (!driver(column, Types.LONGVARCHAR, "text", "java.lang.String", false, (int) LOB_OCTETS, 0,
                (int) LOB_OCTETS)) {
            return inconsistent("text");
        }
        TypeMappingResult<LogicalType> blocked = optional(facts.characterLength(), LOB_OCTETS, "character length",
                "text");
        if (blocked != null) {
            return blocked;
        }
        blocked = absentNumbers(facts, "text");
        if (blocked != null) {
            return blocked;
        }
        blocked = namedCollation(facts);
        if (blocked != null) {
            return blocked;
        }
        return exact(new CharacterType(StorageKind.LARGE, null, LengthUnit.CHARACTERS, LOB_OCTETS, COLLATION,
                Comparison.NAMED, COLLATION, false));
    }

    /**
     * 返回当前镜像。
     */
    private TypeMappingResult<LogicalType> image(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (!driver(column, Types.LONGVARBINARY, "image", "[B", false, (int) LOB_OCTETS, 0, (int) LOB_OCTETS)) {
            return inconsistent("image");
        }
        TypeMappingResult<LogicalType> blocked = optional(facts.characterLength(), LOB_OCTETS, "character length",
                "image");
        if (blocked != null) {
            return blocked;
        }
        blocked = absentNumbers(facts, "image");
        if (blocked != null) {
            return blocked;
        }
        blocked = plainCollation(facts);
        if (blocked != null) {
            return blocked;
        }
        return exact(new BinaryType(StorageKind.LARGE, null, LOB_OCTETS));
    }

    /**
     * 核对驱动及目录事实后映射 SQL Server smalldatetime 类型。
     */
    private static TypeMappingResult<LogicalType> smalldatetime(ColumnDescription column,
            ColumnMetadataEvidence facts) {
        TypeMappingResult<LogicalType> blocked = fixed(column, facts, Types.TIMESTAMP, "smalldatetime",
                "java.sql.Timestamp",
                false, 16, 0, 16, 16, 0, new TimestampType(0, TimeZoneSemantics.LOCAL, 1900, 2079, false));
        return blocked.fidelity() == cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeFidelity.UNSUPPORTED
                ? blocked : blocked.note("SMALLDATETIME_ENDPOINT",
                "inclusive end probed as 2079-06-06 23:59; 2079-06-07 00:00 is rejected");
    }

    /**
     * 将逻辑列类型映射为当前产品的原生类型。
     */
    @Override
    public TypeMappingResult<NativeColumnType> toNative(LogicalColumn column) {
        LogicalType type = column.type();
        if (type instanceof IntegerType integer) {
            return integer(integer);
        }
        if (type instanceof DecimalType decimal) {
            return decimal(decimal);
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
        if (type instanceof TimestampType timestamp) {
            return timestamp(timestamp);
        }
        return TypeMappingResult.unsupported("TYPE_UNMAPPED",
                type.canonical() + " has no verified SQL Server 2000 target");
    }

    /**
     * 根据原生列证据推导整数的逻辑类型及值域。
     */
    private static TypeMappingResult<NativeColumnType> integer(IntegerType integer) {
        if (integer.unsigned()) {
            return integer.bits() == 8 ? TypeMappingResult.exact(NativeColumnType.of("tinyint"))
                    : TypeMappingResult.unsupported("TYPE_UNMAPPED", integer.canonical());
        }
        String sql = switch (integer.bits()) {
            case 16 -> "smallint";
            case 32 -> "int";
            case 64 -> "bigint";
            default -> null;
        };
        return sql == null ? TypeMappingResult.unsupported("TYPE_UNMAPPED", integer.canonical())
                : TypeMappingResult.exact(NativeColumnType.of(sql));
    }

    /**
     * 根据原生列证据推导精确小数的逻辑类型及值域。
     */
    private static TypeMappingResult<NativeColumnType> decimal(DecimalType decimal) {
        if (!decimal.bounded()) {
            return TypeMappingResult.unsupported("UNBOUNDED_NUMERIC_UNMAPPED", decimal.canonical());
        }
        if (decimal.nan()) {
            return TypeMappingResult.unsupported("NAN_DOMAIN_UNPROVEN", decimal.canonical());
        }
        if (decimal.nonNegative() || decimal.precision() > DECIMAL_MAX || decimal.scale() < 0 || decimal.scale() > decimal.precision()) {
            return TypeMappingResult.unsupported("NUMERIC_PRECISION_EXCEEDS_TARGET", decimal.canonical());
        }
        return TypeMappingResult.exact(
                NativeColumnType.of("decimal(" + decimal.precision() + "," + decimal.scale() + ")"));
    }

    /**
     * 根据原生列证据推导字符的逻辑类型及值域。
     */
    private static TypeMappingResult<NativeColumnType> character(CharacterType text, LogicalColumn column) {
        if (column.key()) {
            return TypeMappingResult.unsupported("TEXT_KEY_EQUALITY_UNPROVEN", "cross-product character key");
        }
        if (text.nulAllowed() || text.unit() != LengthUnit.CHARACTERS) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", text.canonical());
        }
        if (text.comparison() != Comparison.NAMED || !COLLATION.equals(text.collation())) {
            return TypeMappingResult.unsupported("COLLATION_UNVERIFIED", text.canonical());
        }
        if (text.kind() == StorageKind.LARGE) {
            if (!COLLATION.equals(text.repertoire()) || text.maxOctets() == null || text.maxOctets() > LOB_OCTETS) {
                return TypeMappingResult.unsupported("TYPE_UNMAPPED", text.canonical());
            }
            return TypeMappingResult.exact(NativeColumnType.of("text"));
        }
        if (text.kind() != StorageKind.VARYING || text.length() == null) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", text.canonical());
        }
        if ("UNICODE".equals(text.repertoire())) {
            if (text.length() < 1 || text.length() > NVARCHAR_MAX) {
                return TypeMappingResult.unsupported("LENGTH_EXCEEDS_TARGET", text.canonical());
            }
            return TypeMappingResult.exact(NativeColumnType.of("nvarchar(" + text.length() + ")"));
        }
        if (!COLLATION.equals(text.repertoire())) {
            return TypeMappingResult.unsupported("CHARSET_UNVERIFIED", text.canonical());
        }
        if (text.length() < 1 || text.length() > VARCHAR_MAX) {
            return TypeMappingResult.unsupported("LENGTH_EXCEEDS_TARGET", text.canonical());
        }
        return TypeMappingResult.exact(NativeColumnType.of("varchar(" + text.length() + ")"));
    }

    /**
     * 根据原生列证据推导二进制的逻辑类型及值域。
     */
    private static TypeMappingResult<NativeColumnType> binary(BinaryType binary) {
        if (binary.kind() != StorageKind.LARGE || binary.maxOctets() == null || binary.maxOctets() > LOB_OCTETS) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", binary.canonical());
        }
        return TypeMappingResult.exact(NativeColumnType.of("image"));
    }

    /**
     * 根据原生列证据推导时间戳的逻辑类型及值域。
     */
    private static TypeMappingResult<NativeColumnType> timestamp(TimestampType timestamp) {
        if (timestamp.zone() != TimeZoneSemantics.LOCAL) {
            return TypeMappingResult.unsupported("TIME_ZONE_SEMANTICS_UNMAPPED", timestamp.canonical());
        }
        if (timestamp.infinity() || timestamp.fractionalDigits() > 3) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", timestamp.canonical());
        }
        if (timestamp.fractionalDigits() == 0 && timestamp.minYear() >= 1900 && timestamp.maxYear() <= 2079) {
            return TypeMappingResult.exact(NativeColumnType.of("smalldatetime"))
                    .note("SMALLDATETIME_ENDPOINT",
                            "inclusive end probed as 2079-06-06 23:59; 2079-06-07 00:00 is rejected");
        }
        if (timestamp.minYear() >= 1753 && timestamp.maxYear() <= 9999) {
            return TypeMappingResult.exact(NativeColumnType.of("datetime"));
        }
        return TypeMappingResult.unsupported("DATE_RANGE_NOT_REPRESENTABLE", timestamp.canonical());
    }

    /**
     * 返回当前元数据事实的产品、驱动及观测来源。
     */
    private static boolean provenance(MetadataProvenance provenance) {
        return provenance.product() == ProductTypeEnum.SQLSERVER2000
                && SERVER_PRODUCT.equals(provenance.serverProduct())
                && SERVER_VERSION.equals(provenance.serverVersion())
                && METHOD.equals(provenance.method());
    }

    /** ResultSetMetaData 与 getColumns 长度均只有一种已探测形态的固定类型。 */
    private static TypeMappingResult<LogicalType> fixed(ColumnDescription column, ColumnMetadataEvidence facts,
            int jdbc,
            String typeName, String className, boolean signed, int precision,
            int scale, int display, int catalogPrecision, int catalogScale,
            LogicalType logical) {
        if (!driver(column, jdbc, typeName, className, signed, precision, scale, display)) {
            return inconsistent(typeName);
        }
        TypeMappingResult<LogicalType> blocked = optional(facts.numericPrecision(), catalogPrecision,
                "numeric precision", typeName);
        if (blocked != null) {
            return blocked;
        }
        blocked = optional(facts.numericScale(), catalogScale, "numeric scale", typeName);
        if (blocked != null) {
            return blocked;
        }
        blocked = absentLength(facts, typeName);
        if (blocked != null) {
            return blocked;
        }
        blocked = plainCollation(facts);
        return blocked != null ? blocked : exact(logical);
    }

    /**
     * 解析当前连接或产品使用的驱动配置。
     */
    private static boolean driver(ColumnDescription column, int jdbc, String typeName, String className, boolean signed,
            int precision, int scale, Integer display) {
        return column.getFieldType() == jdbc
                && typeName.equalsIgnoreCase(column.getFieldTypeName())
                && className.equals(column.getFiledTypeClassName())
                && column.isSigned() == signed
                && column.getPrecisionSize() == precision
                && column.getScaleSize() == scale
                && (display == null || column.getDisplaySize() == display);
    }

    /**
     * 返回当前可选。
     */
    private static TypeMappingResult<LogicalType> optional(MetadataValue<?> actual, Object expected, String property,
            String type) {
        if (actual.state() != MetadataValue.State.VALUE) {
            return actual.state() == MetadataValue.State.UNKNOWN
                    ? incomplete(property) : inconsistent(type);
        }
        return expected.equals(actual.value()) ? null : inconsistent(type);
    }

    /**
     * 检查数值精度和小数位数事实未被额外声明。
     */
    private static TypeMappingResult<LogicalType> absentNumbers(ColumnMetadataEvidence facts, String type) {
        TypeMappingResult<LogicalType> blocked = absent(facts.numericPrecision(), "numeric precision", type);
        return blocked != null ? blocked : absent(facts.numericScale(), "numeric scale", type);
    }

    /**
     * 检查当前类型的长度事实明确缺失。
     */
    private static TypeMappingResult<LogicalType> absentLength(ColumnMetadataEvidence facts, String type) {
        return absent(facts.characterLength(), "character length", type);
    }

    /**
     * 返回当前缺失标记。
     */
    private static TypeMappingResult<LogicalType> absent(MetadataValue<?> actual, String property, String type) {
        if (actual.state() == MetadataValue.State.ABSENT) {
            return null;
        }
        return actual.state() == MetadataValue.State.UNKNOWN ? incomplete(property) : inconsistent(type);
    }

    /**
     * 要求并读取当前文本类型的具名排序规则。
     */
    private static TypeMappingResult<LogicalType> namedCollation(ColumnMetadataEvidence facts) {
        if (facts.collation().state() != MetadataValue.State.VALUE) {
            return facts.collation().state() == MetadataValue.State.UNKNOWN ? incomplete("collation") : inconsistent(
                    "collation");
        }
        return COLLATION.equals(facts.collation().value()) ? null
                : TypeMappingResult.unsupported("COLLATION_UNVERIFIED", facts.collation().value());
    }

    /**
     * 检查当前非文本类型未携带不适用的排序规则事实。
     */
    private static TypeMappingResult<LogicalType> plainCollation(ColumnMetadataEvidence facts) {
        if (facts.collation().state() == MetadataValue.State.ABSENT) {
            return null;
        }
        return facts.collation().state() == MetadataValue.State.UNKNOWN ? incomplete("collation") : inconsistent(
                "collation");
    }

    /**
     * 检查并返回当前配置中的整数值。
     */
    private static Integer integerValue(MetadataValue<Integer> actual) {
        return actual.state() == MetadataValue.State.VALUE ? actual.value() : null;
    }

    /**
     * 读取当前元数据事实中的长整数值。
     */
    private static Long longValue(MetadataValue<Long> actual) {
        return actual.state() == MetadataValue.State.VALUE ? actual.value() : null;
    }

    /**
     * 返回当前解析。
     */
    private static Integer parse(String digits) {
        try {
            return Integer.valueOf(digits);
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    /**
     * 解析长整数。
     */
    private static Long parseLong(String digits) {
        try {
            return Long.valueOf(digits);
        } catch (NumberFormatException failure) {
            return null;
        }
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
