package cn.iocoder.yudao.framework.dbswitch.product.starrocks;

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
import java.util.regex.Pattern;

/**
 * 在本地服务器上探测的 StarRocks 3.5.21-85fccf8（JDBC 外观 MySQL 8.0.33、Connector/J 9.7.0）事实。
 * information_schema 将列字符集和排序规则报告为 NULL。CHAR/VARCHAR 长度按字节计算，
 * （varchar(5) 接受 😀X，5 字节，并拒绝 中文，6 字节）。相等比较区分大小写，且不
 * 忽略尾部空格。STRING 存为 varchar(65533)。DATETIME(p) 被拒绝，但裸 DATETIME
 * 通过 LocalDateTime 保留微秒，即使 JDBC 小数位数为 0，且 getString 渲染为 {@code .0}。
 * 年份 0 会存储，但 getObject 抛出异常；0000-00-00 和不完整日期插入后回读为 NULL。
 * 超范围整数和非有限浮点数存为 NULL；十进制溢出导致语句失败。
 * LARGEINT 在 SHOW CREATE 中为 largeint(40)，在 information_schema 中为精度 39 的 {@code bigint(20) unsigned}，
 * Connector/J 将其读取为 String。PRIMARY KEY 替换重复键；DUPLICATE KEY 保留两行。
 */
public final class StarrocksLogicalTypeMapper implements LogicalTypeMapper {

    private static final Pattern DECIMAL = Pattern.compile("decimal\\(([0-9]{1,2}),\\s*([0-9]{1,2})\\)");

    private static final Pattern STRING = Pattern.compile("(char|varchar)\\(([0-9]{1,7})\\)");

    private static final long VARCHAR_MAX = 1048576L;

    private static final int CHAR_MAX = 255;

    /**
     * 初始化 {@code StarrocksLogicalTypeMapper}。
     */
    public StarrocksLogicalTypeMapper() {
    }

    /**
     * 返回当前操作使用的数据库产品。
     */
    @Override
    public ProductTypeEnum product() {
        return ProductTypeEnum.STARROCKS;
    }

    /**
     * 将数据库原生列类型映射为逻辑类型。
     */
    @Override
    public TypeMappingResult<LogicalType> toLogical(ColumnDescription column) {
        ColumnMetadataEvidence facts = column.getMetadataEvidence();
        if (!facts.provenance().nativeProduct(ProductTypeEnum.STARROCKS)) {
            return TypeMappingResult.unsupported("SOURCE_PROVENANCE_UNVERIFIED",
                    "STARROCKS native catalog provenance required");
        }
        String type = facts.nativeType().value().trim().toLowerCase(Locale.ROOT);
        if (type.equals("bigint(20) unsigned")) {
            return largeint(column, facts);
        }
        if (type.endsWith(" unsigned")) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", "unsigned types are rejected at DDL");
        }
        if (type.equals("tinyint(4)")) {
            return integer(column, facts, "tinyint", Types.TINYINT, 3, "java.lang.Integer", 8);
        }
        if (type.equals("smallint(6)")) {
            return integer(column, facts, "smallint", Types.SMALLINT, 5, "java.lang.Integer", 16);
        }
        if (type.equals("int(11)")) {
            return integer(column, facts, "int", Types.INTEGER, 10, "java.lang.Integer", 32);
        }
        if (type.equals("bigint(20)")) {
            return integer(column, facts, "bigint", Types.BIGINT, 19, "java.lang.Long", 64);
        }
        if (type.equals("tinyint(1)")) {
            return bool(column, facts);
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
            return character(column, facts, string.group(1).equals("char"), Long.parseLong(string.group(2)), type);
        }
        if (type.equals("json")) {
            return json(column, facts);
        }
        if (type.startsWith("array<")) {
            return array(column, type);
        }
        if (type.equals("bitmap") || type.equals("hll")) {
            return TypeMappingResult.unsupported("VALUE_CODEC_UNSUPPORTED", "Connector/J reads " + type + " as String");
        }
        if (type.equals("varbinary")) {
            return binary(column, facts);
        }
        if (type.startsWith("varbinary(") || type.startsWith("binary(")) {
            return TypeMappingResult.unsupported("VALUE_CODEC_UNSUPPORTED",
                    "bounded native binary requires a separately verified catalog/codec plan");
        }
        if (type.equals("date")) {
            return date(column, facts);
        }
        if (type.equals("datetime")) {
            return datetime(column, facts);
        }
        if (type.equals("time") || type.startsWith("time(") || type.equals("timestamp") || type.startsWith("timestamp(")
                || type.startsWith("datetime(") || type.equals("string")) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED",
                    type + " is not the catalog form probed on this server");
        }
        return TypeMappingResult.unsupported("NATIVE_TYPE_UNRECOGNIZED", type);
    }

    /**
     * information_schema 报告不准确：SHOW CREATE 为 largeint(40)，目录为精度 39 的 bigint(20) unsigned。
     */
    private TypeMappingResult<LogicalType> largeint(ColumnDescription column, ColumnMetadataEvidence facts) {
        // information_schema 报告不准确：SHOW CREATE 为 largeint(40)，目录为精度 39 的 bigint(20) unsigned。
        if (column.getFieldType() != Types.CHAR || !"java.lang.String".equals(column.getFiledTypeClassName())
                || column.isSigned() || column.getPrecisionSize() != 13 || column.getScaleSize() != 0
                || !noCharacterFacts(facts)) {
            return inconsistent("largeint");
        }
        if (facts.numericPrecision().state() != MetadataValue.State.VALUE || facts.numericPrecision().value() != 39
                || facts.numericScale().state() != MetadataValue.State.ABSENT) {
            return inconsistent("largeint");
        }
        // StarrocksJdbcSupport 针对这些目录事实选择原生且检查范围的 BigDecimal 编解码器。
        return exact(new Int128Type());
    }

    /**
     * 根据原生列证据推导二进制的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> binary(ColumnDescription column, ColumnMetadataEvidence facts) {
        // Connector/J 报告 TINYTEXT/String，而目录已验证的 StarRocksDatabaseOperator
        // 通过 getBytes 读取原始字节。文本或 Base64 转换不构成二进制源授权。
        if (column.getFieldType() != Types.VARCHAR || !"java.lang.String".equals(column.getFiledTypeClassName())
                || column.isSigned() || column.getPrecisionSize() != 192 || column.getScaleSize() != 0
                || !noCharacterFacts(facts)
                || facts.numericPrecision().state() != MetadataValue.State.ABSENT
                || facts.numericScale().state() != MetadataValue.State.ABSENT
                || facts.characterLength().state() != MetadataValue.State.ABSENT) {
            return inconsistent("varbinary");
        }
        return exact(new BinaryType(StorageKind.LARGE, null, (long) StarrocksJdbcSupport.MAX_VARBINARY_BYTES))
                .note("NATIVE_BINARY_CODEC_REQUIRED",
                        "catalog-proven StarRocksDatabaseOperator getBytes and native writer byte[] codec");
    }

    /**
     * 根据原生列证据推导整数的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> integer(ColumnDescription column, ColumnMetadataEvidence facts, String name,
            int jdbc, int precision, String className, int bits) {
        if (column.getFieldType() != jdbc || !column.isSigned() || column.getPrecisionSize() != precision
                || column.getScaleSize() != 0 || !className.equals(column.getFiledTypeClassName()) || !noCharacterFacts(
                facts)) {
            return inconsistent(name);
        }
        if (facts.numericPrecision().state() != MetadataValue.State.VALUE || facts.numericScale()
                .state() != MetadataValue.State.VALUE) {
            return incomplete("numeric precision/scale");
        }
        if (facts.numericPrecision().value() != precision || facts.numericScale().value() != 0) {
            return inconsistent(name);
        }
        return exact(new IntegerType(bits, false));
    }

    /**
     * 根据原生列证据推导布尔值的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> bool(ColumnDescription column, ColumnMetadataEvidence facts) {
        if ((column.getFieldType() != Types.BIT && column.getFieldType() != Types.BOOLEAN)
                || !"java.lang.Boolean".equals(column.getFiledTypeClassName()) || column.isSigned()
                || column.getPrecisionSize() != 1 || column.getScaleSize() != 0 || !noCharacterFacts(facts)) {
            return inconsistent("boolean");
        }
        if (facts.numericPrecision().state() != MetadataValue.State.ABSENT || facts.numericScale()
                .state() != MetadataValue.State.VALUE
                || facts.numericScale().value() != 0) {
            return inconsistent("boolean");
        }
        // 非零整数存为 true（2 回读为 true）。读取器仍得到 Boolean。
        return exact(new BooleanType());
    }

    /**
     * 根据原生列证据推导精确小数的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> decimal(ColumnDescription column, ColumnMetadataEvidence facts,
            int precision, int scale) {
        if (precision < 1 || precision > 38 || scale < 0 || scale > 38 || scale > precision) {
            return inconsistent("decimal");
        }
        // Connector/J 精度为目录精度加 2（5→7、10→12、38→40）。
        if (!jdbc(column, Types.DECIMAL,
                Types.NUMERIC) || !column.isSigned() || column.getPrecisionSize() != precision + 2
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
        int jdbcType = single ? Types.REAL : Types.DOUBLE;
        String className = single ? "java.lang.Float" : "java.lang.Double";
        int driverPrecision = single ? 12 : 22;
        int catalogPrecision = single ? 7 : 15;
        if (column.getFieldType() != jdbcType || !column.isSigned() || column.getPrecisionSize() != driverPrecision
                || column.getScaleSize() != 0 || !className.equals(column.getFiledTypeClassName()) || !noCharacterFacts(
                facts)) {
            return inconsistent(single ? "float" : "double");
        }
        if (facts.numericPrecision().state() != MetadataValue.State.VALUE || facts.numericScale()
                .state() != MetadataValue.State.VALUE) {
            return incomplete("float precision/scale");
        }
        if (facts.numericPrecision().value() != catalogPrecision || facts.numericScale().value() != catalogPrecision) {
            return inconsistent(single ? "float" : "double");
        }
        var result = exact(new FloatType(single ? 32 : 64, false, false, false));
        return single ? result.loss("FLOAT32_PRECISION_NOT_PRESERVED", "16777217 is stored as 16777216") : result;
    }

    /**
     * 根据原生列证据推导字符的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> character(ColumnDescription column, ColumnMetadataEvidence facts,
            boolean fixed, long length, String type) {
        if (fixed ? length < 1 || length > CHAR_MAX : length < 1 || length > VARCHAR_MAX) {
            return inconsistent(type);
        }
        if (column.getFieldType() != Types.VARCHAR || !"java.lang.String".equals(column.getFiledTypeClassName())
                || column.isSigned() || column.getPrecisionSize() != (fixed ? 64 : length)) {
            return inconsistent(type);
        }
        if (facts.characterLength().state() != MetadataValue.State.VALUE || facts.characterLength().value() != length
                || !noCharacterFacts(facts)) {
            return inconsistent(type);
        }
        return exact(new CharacterType(fixed ? StorageKind.FIXED : StorageKind.VARYING, length, LengthUnit.OCTETS,
                null, "utf8mb4", Comparison.BINARY_NO_PAD, null, false));
    }

    /**
     * 根据原生列证据推导JSON 值的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> json(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (column.getFieldType() != Types.CHAR || !"java.lang.String".equals(column.getFiledTypeClassName())
                || column.getPrecisionSize() != 20 || !noCharacterFacts(facts)) {
            return inconsistent("json");
        }
        return exact(new JsonType(JsonRepresentation.NORMALIZED, JsonNumbers.BINARY64));
    }

    /**
     * 根据原生列证据推导数组的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> array(ColumnDescription column, String type) {
        if (column.getFieldType() != Types.VARCHAR || !"java.lang.String".equals(column.getFiledTypeClassName())) {
            return inconsistent(type);
        }
        return TypeMappingResult.unsupported("VALUE_CODEC_UNSUPPORTED", "Connector/J reads ARRAY as String");
    }

    /**
     * 根据原生列证据推导日期的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> date(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (column.getFieldType() != Types.DATE || column.isSigned() || column.getPrecisionSize() != 10
                || column.getScaleSize() != 0 || !noCharacterFacts(facts)) {
            return inconsistent("date");
        }
        if (facts.numericPrecision().state() != MetadataValue.State.ABSENT || facts.numericScale()
                .state() != MetadataValue.State.ABSENT) {
            return inconsistent("date");
        }
        return exact(new DateType(0, 9999, false))
                .loss("ZERO_DATE_SILENTLY_CONVERTED", "0000-00-00 and partial dates are inserted and read back as NULL")
                .loss("YEAR_ZERO_DRIVER_REJECTED", "0000-01-01 is stored but Connector/J getObject throws YEAR");
    }

    /**
     * 按当前产品类型约定解析日期时间值。
     */
    private TypeMappingResult<LogicalType> datetime(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (column.getFieldType() != Types.TIMESTAMP || !"java.time.LocalDateTime".equals(
                column.getFiledTypeClassName())
                || column.getPrecisionSize() != 19 || column.getScaleSize() != 0 || !noCharacterFacts(facts)) {
            return inconsistent("datetime");
        }
        if (facts.numericPrecision().state() != MetadataValue.State.ABSENT || facts.numericScale()
                .state() != MetadataValue.State.ABSENT) {
            return inconsistent("datetime");
        }
        // JDBC 小数位数保持 0。LocalDateTime 仍返回 2026-10-05T12:34:56.654321。
        return exact(new TimestampType(6, TimeZoneSemantics.LOCAL, 0, 9999, false))
                .loss("ZERO_DATE_SILENTLY_CONVERTED", "0000-00-00 00:00:00 is inserted and read back as NULL")
                .loss("YEAR_ZERO_DRIVER_REJECTED", "year 0 is stored; LocalDateTime has no year 0");
    }

    /**
     * 将逻辑列类型映射为当前产品的原生类型。
     */
    @Override
    public TypeMappingResult<NativeColumnType> toNative(LogicalColumn column) {
        return nativeType(column.type(), column);
    }

    /**
     * 根据原生列信息确定数据库类型声明。
     */
    private TypeMappingResult<NativeColumnType> nativeType(LogicalType type, LogicalColumn column) {
        if (type instanceof Int128Type) {
            return TypeMappingResult.exact(NativeColumnType.of("largeint"));
        }
        if (type instanceof IntegerType integer) {
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
                return decimal.nan() ? result.loss("NAN_NOT_REPRESENTABLE",
                        "NaN has no StarRocks LARGEINT value") : result;
            }
            if (decimal.precision() > 38 || decimal.scale() < 0 || decimal.scale() > 38 || decimal.scale() > decimal.precision()) {
                return TypeMappingResult.unsupported("NUMERIC_PRECISION_EXCEEDS_TARGET", decimal.canonical());
            }
            var result = TypeMappingResult.exact(
                    NativeColumnType.of("decimal(" + decimal.precision() + "," + decimal.scale() + ")"));
            return decimal.nan() ? result.loss("NAN_NOT_REPRESENTABLE",
                    "NaN is rejected as a decimal literal") : result;
        }
        if (type instanceof FloatType value) {
            var result = TypeMappingResult.exact(NativeColumnType.of(value.bits() == 32 ? "float" : "double"));
            if (value.bits() == 32) {
                result = result.loss("FLOAT32_PRECISION_NOT_PRESERVED", "16777217 is stored as 16777216");
            }
            if (value.negativeZero()) {
                result = result.loss("NEGATIVE_ZERO_NOT_PRESERVED", "-0.0 is stored as 0");
            }
            return value.nonFinite() ? result.loss("NON_FINITE_STORED_AS_NULL",
                    "NaN and Infinity inserts succeed and read back as NULL") : result;
        }
        if (type instanceof BooleanType) {
            return TypeMappingResult.exact(NativeColumnType.of("boolean"));
        }
        if (type instanceof CharacterType text) {
            return character(text, column);
        }
        if (type instanceof BinaryType binary) {
            if (column.key()) {
                return TypeMappingResult.unsupported("BINARY_KEY_UNSUPPORTED",
                        "native binary cannot be a StarRocks key");
            }
            if (binary.kind() != StorageKind.LARGE) {
                return TypeMappingResult.unsupported("VALUE_CODEC_UNSUPPORTED",
                        "bounded/fixed binary domain preservation requires a native plan");
            }
            var result = TypeMappingResult.exact(NativeColumnType.of("varbinary"));
            return binary.maxOctets() > StarrocksJdbcSupport.MAX_VARBINARY_BYTES
                    ? result.loss("BINARY_LENGTH_LIMIT",
                    "native StarRocks VARBINARY holds at most 1048576 bytes; larger cells are refused before writing") : result;
        }
        if (type instanceof DateType date) {
            return temporalRange(TypeMappingResult.exact(NativeColumnType.of("date")), date.minYear(), date.maxYear(),
                    date.infinity());
        }
        if (type instanceof TimeType) {
            return TypeMappingResult.unsupported("TIME_TYPE_UNAVAILABLE", "TIME is rejected at DDL");
        }
        if (type instanceof TimestampType timestamp) {
            if (timestamp.zone() != TimeZoneSemantics.LOCAL) {
                return TypeMappingResult.unsupported("TIME_ZONE_SEMANTICS_UNMAPPED",
                        "no verified instant/offset timestamp mapping");
            }
            // DATETIME(p) 是语法错误。裸 DATETIME 保留探测到的微秒。
            var result = TypeMappingResult.exact(NativeColumnType.of("datetime"));
            if (timestamp.fractionalDigits() > 6) {
                result = result.loss("TIME_FRACTION_TRUNCATED",
                        "datetime kept 6 fractional digits; DATETIME(p) is rejected");
            }
            return temporalRange(result, timestamp.minYear(), timestamp.maxYear(), timestamp.infinity());
        }
        if (type instanceof UuidType) {
            return TypeMappingResult.unsupported("UUID_TYPE_UNAVAILABLE", "no native uuid type");
        }
        if (type instanceof JsonType json) {
            var result = TypeMappingResult.exact(NativeColumnType.of("json"));
            if (json.representation() == JsonRepresentation.TEXT) {
                result = result.loss("JSON_NORMALIZED", "spaces around structural tokens are dropped");
            }
            return json.numbers() == JsonNumbers.ARBITRARY_DECIMAL
                    ? result.loss("JSON_NUMBER_PRECISION",
                    "123456789012345678901234567890 is stored as 1.2345678901234566e+29") : result;
        }
        return TypeMappingResult.unsupported("TYPE_UNMAPPED", type.canonical() + " has no verified STARROCKS target");
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
        long bytes = text.kind() == StorageKind.LARGE ? (text.maxOctets() == null ? -1 : text.maxOctets())
                : text.unit() == LengthUnit.OCTETS ? text.length() : text.length() * 4;
        if (bytes < 1) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", text.canonical());
        }
        String base = switch (text.kind()) {
            case FIXED -> bytes <= CHAR_MAX ? "char(" + bytes + ")" : null;
            case VARYING, LARGE -> bytes <= VARCHAR_MAX ? "varchar(" + bytes + ")" : null;
        };
        if (base == null) {
            return TypeMappingResult.unsupported("LENGTH_EXCEEDS_TARGET", text.canonical());
        }
        var result = TypeMappingResult.exact(NativeColumnType.of(base));
        return switch (text.comparison()) {
            case BINARY_NO_PAD -> result;
            case BINARY_PAD_SPACE ->
                    result.loss("PAD_SPACE_EQUALITY_CHANGED", "StarRocks does not ignore trailing spaces");
            case NAMED -> TypeMappingResult.unsupported("COLLATION_UNAVAILABLE",
                    "column collation is NULL and equality is case-sensitive; " + text.collation() + " has no equivalent");
            default -> result.note("ORDERING_MAY_DIFFER",
                    "equality is code-point equality; ordering is not the source locale");
        };
    }

    /**
     * 检查日期时间值处于当前类型允许的范围。
     */
    private static TypeMappingResult<NativeColumnType> temporalRange(TypeMappingResult<NativeColumnType> result,
            int minYear, int maxYear, boolean infinity) {
        if (infinity || minYear < 0 || maxYear > 9999) {
            result = result.loss("DATE_RANGE_NOT_REPRESENTABLE",
                    "values outside 0000..9999 cannot be stored unchanged");
        }
        if (minYear <= 0 && maxYear >= 0) {
            result = result.loss("YEAR_ZERO_DRIVER_REJECTED", "year 0 is stored but Connector/J cannot read it");
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
