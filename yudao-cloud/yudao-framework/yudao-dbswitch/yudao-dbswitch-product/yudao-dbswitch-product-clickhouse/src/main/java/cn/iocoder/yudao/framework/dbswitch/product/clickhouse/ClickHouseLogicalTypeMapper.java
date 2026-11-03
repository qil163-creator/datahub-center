package cn.iocoder.yudao.framework.dbswitch.product.clickhouse;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将 ClickHouse 25.8.33.6（clickhouse-jdbc 0.5.0，HTTP）原生事实映射到逻辑值域及其反向映射。以下所有 JDBC 代码、
 * 精度、类和目录结构均从该服务器读取；不复制 MySQL、Doris 或
 * PostgreSQL 映射器的事实。值域或大小未得到验证的类型返回 UNSUPPORTED，而非 String。
 * <ul>
 * <li>有符号整数以 Byte/Short/Integer/Long 返回，JDBC 精度为 3/5/10/19。无符号整数以
 * ClickHouse UnsignedByte/Short/Integer/Long 返回（UInt64 是精度为 20 的 NUMERIC，而非 BIGINT）。超范围插入会回绕。
 * 向非 Nullable 整数插入 NULL 会存为 0。目录 numeric_precision 表示位宽，基数为 2。</li>
 * <li>Decimal 精度为 1..76，小数位数不能超过精度。拒绝溢出及 NaN/Infinity。
 * {@code -0} 存为正零。目录类型文本为 {@code Decimal(p, s)}，包含一个空格。</li>
 * <li>Float32 是精度为 12 的 JDBC FLOAT（非 REAL），Float64 是精度为 22 的 DOUBLE。目录数值精度
 * 缺失。{@code -0.0} 存为 {@code 0}；NaN 和两个无穷值可以往返保持。</li>
 * <li>Date 饱和到 1970-01-01..2149-06-06，包括 2149 年 06-06 后的日期。Date32 饱和到
 * 1900..2299 的完整年份。DateTime 超过 2106-02-07 06:28:15 后回绕至纪元，并拒绝小数字面值。
 * DateTime64 保持 1900-01-01..2299-12-31，并截断多余小数位；时区参数尚未证明
 * 能映射为本地时间或瞬时。JDBC 日期为 LocalDate；无附加参数的 DateTime/DateTime64 为 LocalDateTime。</li>
 * <li>String 保存 UTF-8、NUL 和尾部空格，并按字节比较（大小写敏感，不补齐）。目录
 * 不公布最大长度；64000000 字节写入成功，更大的值受 1.80 GiB 内存限制而失败，因此
 * 最大值尚未得到证明。FixedString(N) 是以 NUL 补齐、作为 Java String 返回的字节。CHAR/VARCHAR 长度
 * 不受约束：二者均存为 String。任意字节无法通过 getString 无损往返。</li>
 * <li>Bool 是 JDBC BOOLEAN。UUID 是精度为 69 的 JDBC VARCHAR 和 java.util.UUID，并折叠为小写。MergeTree
 * ORDER BY 保留重复键。CHECK 约束会生效，但不存在唯一约束。</li>
 * </ul>
 */
public final class ClickHouseLogicalTypeMapper implements LogicalTypeMapper {

    private static final Pattern DECIMAL = Pattern.compile("Decimal\\(([0-9]{1,2}),\\s*([0-9]{1,2})\\)");

    private static final Pattern DATETIME64 = Pattern.compile("DateTime64\\(([0-9])\\)");

    private static final Pattern FIXED = Pattern.compile("FixedString\\(([0-9]+)\\)");

    private static final int DATE32_MIN_YEAR = 1900;

    private static final int DATE32_MAX_YEAR = 2299;

    private static final int DATE_MIN_YEAR = 1970;

    private static final int DATE_MAX_YEAR = 2149;

    private static final int DATETIME_MIN_YEAR = 1970;

    private static final int DATETIME_MAX_YEAR = 2106;

    /**
     * 初始化 {@code ClickHouseLogicalTypeMapper}。
     */
    public ClickHouseLogicalTypeMapper() {
    }

    /**
     * 返回当前操作使用的数据库产品。
     */
    @Override
    public ProductTypeEnum product() {
        return ProductTypeEnum.CLICKHOUSE;
    }

    /**
     * 将数据库原生列类型映射为逻辑类型。
     */
    @Override
    public TypeMappingResult<LogicalType> toLogical(ColumnDescription column) {
        ColumnMetadataEvidence facts = column.getMetadataEvidence();
        if (!facts.provenance().nativeProduct(ProductTypeEnum.CLICKHOUSE)) {
            return TypeMappingResult.unsupported("SOURCE_PROVENANCE_UNVERIFIED",
                    "CLICKHOUSE native catalog provenance required");
        }
        String type = facts.nativeType().value().trim();
        if (type.startsWith("LowCardinality(") && type.endsWith(")")) {
            String inner = type.substring("LowCardinality(".length(), type.length() - 1);
            if (!inner.equals("String") && !inner.equals("Nullable(String)")) {
                return TypeMappingResult.unsupported("LOW_CARDINALITY_UNPROVEN", type);
            }
            type = inner;
        }
        if (type.startsWith("Nullable(") && type.endsWith(")")) {
            if (!column.isNullable()) {
                return inconsistent(type);
            }
            type = type.substring("Nullable(".length(), type.length() - 1);
            if (type.startsWith("Nullable(")) {
                return TypeMappingResult.unsupported("NATIVE_TYPE_UNRECOGNIZED", "nested Nullable");
            }
        } else if (column.isNullable()) {
            return inconsistent(type);
        }
        if (!noCharacterFacts(facts)) {
            return inconsistent(type);
        }
        IntFact integer = IntFact.of(type);
        if (integer != null) {
            return integer(column, facts, integer);
        }
        if (type.equals("Bool")) {
            return bool(column, facts);
        }
        Matcher decimal = DECIMAL.matcher(type);
        if (decimal.matches()) {
            return decimal(column, facts, Integer.parseInt(decimal.group(1)), Integer.parseInt(decimal.group(2)));
        }
        if (type.equals("Float32") || type.equals("Float64")) {
            return floating(column, facts, type.equals("Float32"));
        }
        if (type.equals("String")) {
            if (!stringJdbc(column, facts)) {
                return inconsistent(type);
            }
            // 64000000 字节写入成功。更大分配因容器内存限制停止，并非类型错误。
            return TypeMappingResult.unsupported("STRING_MAX_OCTETS_UNPROVEN",
                    "String has no catalog maximum; only a 64000000 byte value was proven");
        }
        if (FIXED.matcher(type).matches()) {
            return TypeMappingResult.unsupported("FIXEDSTRING_STRING_CODEC",
                    "FixedString(N) is NUL-padded bytes surfaced as a Java String");
        }
        if (type.equals("Date")) {
            return date(column, facts, false);
        }
        if (type.equals("Date32")) {
            return date(column, facts, true);
        }
        if (type.equals("DateTime")) {
            return dateTime(column, facts);
        }
        if (type.startsWith("DateTime(")) {
            return TypeMappingResult.unsupported("TIME_ZONE_SEMANTICS_UNMAPPED", type);
        }
        Matcher dateTime64 = DATETIME64.matcher(type);
        if (dateTime64.matches()) {
            return dateTime64(column, facts, Integer.parseInt(dateTime64.group(1)));
        }
        if (type.equals("UUID")) {
            return uuid(column, facts);
        }
        if (type.startsWith("Array(") || type.startsWith("Map(")) {
            return TypeMappingResult.unsupported("VALUE_CODEC_UNSUPPORTED", type);
        }
        return TypeMappingResult.unsupported("NATIVE_TYPE_UNRECOGNIZED", type);
    }

    /**
     * 根据原生列证据推导整数的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> integer(ColumnDescription column, ColumnMetadataEvidence facts,
            IntFact kind) {
        if (column.getFieldType() != kind.jdbc || column.isSigned() != !kind.unsigned || column.getPrecisionSize() != kind.jdbcPrecision
                || column.getScaleSize() != 0 || !kind.javaClass.equals(column.getFiledTypeClassName())) {
            return inconsistent(kind.name);
        }
        if (facts.numericPrecision().state() != MetadataValue.State.VALUE || facts.numericScale()
                .state() != MetadataValue.State.VALUE) {
            return incomplete("numeric precision/scale");
        }
        if (facts.numericPrecision().value() != kind.bits || facts.numericScale().value() != 0
                || facts.characterLength().state() != MetadataValue.State.ABSENT) {
            return inconsistent(kind.name);
        }
        var result = exact(new IntegerType(kind.bits, kind.unsigned))
                .note("INTEGER_OVERFLOW_WRAPS", "inserts outside the bit range wrap");
        return column.isNullable() ? result : result.note("NULL_INPUT_BECOMES_ZERO",
                "a NULL insert into a non-Nullable integer is stored as 0");
    }

    /**
     * 根据原生列证据推导布尔值的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> bool(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (column.getFieldType() != Types.BOOLEAN || !column.isSigned() || column.getPrecisionSize() != 1
                || column.getScaleSize() != 0 || !"java.lang.Boolean".equals(column.getFiledTypeClassName())) {
            return inconsistent("Bool");
        }
        if (facts.numericPrecision().state() != MetadataValue.State.VALUE || facts.numericScale()
                .state() != MetadataValue.State.VALUE) {
            return incomplete("bool precision/scale");
        }
        // 目录 numeric_precision 是 8 位存储宽度；驱动精度为 1。
        if (facts.numericPrecision().value() != 8 || facts.numericScale().value() != 0
                || facts.characterLength().state() != MetadataValue.State.ABSENT) {
            return inconsistent("Bool");
        }
        return exact(new BooleanType()).note("BOOL_NONZERO_COERCED", "a non-zero integer insert is stored as true");
    }

    /**
     * 根据原生列证据推导精确小数的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> decimal(ColumnDescription column, ColumnMetadataEvidence facts,
            int precision, int scale) {
        if (precision < 1 || precision > 76 || scale < 0 || scale > precision) {
            return inconsistent("Decimal");
        }
        if (!jdbc(column, Types.DECIMAL) || !column.isSigned() || column.getPrecisionSize() != precision
                || column.getScaleSize() != scale || !"java.math.BigDecimal".equals(column.getFiledTypeClassName())) {
            return inconsistent("Decimal");
        }
        if (facts.numericPrecision().state() != MetadataValue.State.VALUE || facts.numericScale()
                .state() != MetadataValue.State.VALUE) {
            return incomplete("numeric precision/scale");
        }
        if (facts.numericPrecision().value() != precision || facts.numericScale().value() != scale
                || facts.characterLength().state() != MetadataValue.State.ABSENT) {
            return inconsistent("Decimal");
        }
        return exact(new DecimalType(precision, scale, false, false))
                .note("DECIMAL_NEGATIVE_ZERO_NORMALIZED", "-0 is stored as positive zero")
                .note("DECIMAL_EXTRA_SCALE_TRUNCATED", "fractional digits beyond the declared scale are truncated");
    }

    /**
     * 根据原生列证据推导浮点数的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> floating(ColumnDescription column, ColumnMetadataEvidence facts,
            boolean single) {
        int jdbcType = single ? Types.FLOAT : Types.DOUBLE;
        int precision = single ? 12 : 22;
        String javaClass = single ? "java.lang.Float" : "java.lang.Double";
        if (column.getFieldType() != jdbcType || !column.isSigned() || column.getPrecisionSize() != precision
                || column.getScaleSize() != 0 || !javaClass.equals(column.getFiledTypeClassName())) {
            return inconsistent(single ? "Float32" : "Float64");
        }
        if (facts.numericPrecision().state() != MetadataValue.State.ABSENT || facts.numericScale()
                .state() != MetadataValue.State.ABSENT
                || facts.characterLength().state() != MetadataValue.State.ABSENT) {
            return inconsistent(single ? "Float32" : "Float64");
        }
        return exact(new FloatType(single ? 32 : 64, false, false, true))
                .note("NEGATIVE_ZERO_NOT_PRESERVED", "-0.0 is stored as 0");
    }

    /**
     * 根据原生列证据推导日期的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> date(ColumnDescription column, ColumnMetadataEvidence facts, boolean wide) {
        if (!jdbc(column,
                Types.DATE) || column.isSigned() || column.getPrecisionSize() != 10 || column.getScaleSize() != 0
                || !"java.time.LocalDate".equals(column.getFiledTypeClassName()) || !noNumericFacts(facts)) {
            return inconsistent(wide ? "Date32" : "Date");
        }
        if (wide) {
            return exact(new DateType(DATE32_MIN_YEAR, DATE32_MAX_YEAR, false))
                    .note("DATE_OUT_OF_RANGE_SATURATED", "dates outside 1900-01-01..2299-12-31 saturate");
        }
        // 2149 年仅到 2149-06-06 有效；该年之后的日期会饱和处理。
        return exact(new DateType(DATE_MIN_YEAR, DATE_MAX_YEAR, false))
                .loss("DATE_OUT_OF_RANGE_SATURATED", "Date saturates to 1970-01-01..2149-06-06");
    }

    /**
     * 按当前产品的精度和时区约定处理日期时间值。
     */
    private TypeMappingResult<LogicalType> dateTime(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (!localTimestamp(column, facts, 0)) {
            return inconsistent("DateTime");
        }
        return exact(new TimestampType(0, TimeZoneSemantics.LOCAL, DATETIME_MIN_YEAR, DATETIME_MAX_YEAR, false))
                .loss("DATETIME_OUT_OF_RANGE_WRAPPED", "DateTime past 2106-02-07 06:28:15 wraps to the epoch");
    }

    /**
     * 核对驱动事实和精度后生成 ClickHouse DateTime64 的逻辑类型映射。
     */
    private TypeMappingResult<LogicalType> dateTime64(ColumnDescription column, ColumnMetadataEvidence facts,
            int digits) {
        if (digits > 9 || !localTimestamp(column, facts, digits)) {
            return inconsistent("DateTime64");
        }
        return exact(new TimestampType(digits, TimeZoneSemantics.LOCAL, DATE32_MIN_YEAR, DATE32_MAX_YEAR, false))
                .note("DATETIME_OUT_OF_RANGE_SATURATED", "DateTime64 values outside 1900..2299 are rewritten");
    }

    /**
     * 根据原生列证据推导UUID 值的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> uuid(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (!jdbc(column,
                Types.VARCHAR) || column.isSigned() || column.getPrecisionSize() != 69 || column.getScaleSize() != 0
                || !"java.util.UUID".equals(column.getFiledTypeClassName()) || !noNumericFacts(facts)) {
            return inconsistent("UUID");
        }
        return exact(new UuidType());
    }

    /**
     * 将逻辑列类型映射为当前产品的原生类型。
     */
    @Override
    public TypeMappingResult<NativeColumnType> toNative(LogicalColumn column) {
        TypeMappingResult<NativeColumnType> result = nativeType(column.type(), column);
        if (column.key() && result.fidelity() != TypeFidelity.UNSUPPORTED && !(column.type() instanceof CharacterType)) {
            result = result.loss("KEY_UNIQUENESS_NOT_ENFORCED", "ordinary MergeTree ORDER BY keeps duplicate keys");
        }
        return result;
    }

    /**
     * 根据原生列信息确定数据库类型声明。
     */
    private TypeMappingResult<NativeColumnType> nativeType(LogicalType type, LogicalColumn column) {
        if (type instanceof IntegerType integer) {
            String name = switch (integer.bits()) {
                case 8 -> integer.unsigned() ? "UInt8" : "Int8";
                case 16 -> integer.unsigned() ? "UInt16" : "Int16";
                case 24, 32 -> integer.unsigned() ? "UInt32" : "Int32";
                default -> integer.unsigned() ? "UInt64" : "Int64";
            };
            var result = emit(name, column).note("INTEGER_OVERFLOW_WRAPS", "inserts outside the bit range wrap");
            return column.nullable() ? result : result.note("NULL_INPUT_BECOMES_ZERO",
                    "a NULL insert into a non-Nullable integer is stored as 0");
        }
        if (type instanceof DecimalType decimal) {
            if (!decimal.bounded()) {
                return TypeMappingResult.unsupported("UNBOUNDED_NUMERIC_UNMAPPED", "no unbounded exact numeric");
            }
            if (decimal.precision() > 76 || decimal.scale() < 0 || decimal.scale() > 76 || decimal.scale() > decimal.precision()) {
                return TypeMappingResult.unsupported("NUMERIC_PRECISION_EXCEEDS_TARGET", decimal.canonical());
            }
            var result = emit("Decimal(" + decimal.precision() + ", " + decimal.scale() + ")", column);
            return decimal.nan() ? result.loss("NAN_NOT_REPRESENTABLE", "NaN and Infinity are rejected") : result;
        }
        if (type instanceof FloatType value) {
            var result = emit(value.bits() == 32 ? "Float32" : "Float64", column);
            return value.negativeZero() ? result.loss("NEGATIVE_ZERO_NOT_PRESERVED", "-0.0 is stored as 0") : result;
        }
        if (type instanceof BooleanType) {
            return emit("Bool", column);
        }
        if (type instanceof CharacterType text) {
            return character(text, column);
        }
        if (type instanceof BinaryType) {
            return TypeMappingResult.unsupported("BINARY_TYPE_UNAVAILABLE",
                    "getString does not round-trip arbitrary bytes");
        }
        if (type instanceof DateType date) {
            var result = emit("Date32", column);
            return date.infinity() || date.minYear() < DATE32_MIN_YEAR || date.maxYear() > DATE32_MAX_YEAR
                    ? result.loss("DATE_RANGE_NOT_REPRESENTABLE",
                    "Date32 saturates outside 1900-01-01..2299-12-31") : result;
        }
        if (type instanceof TimeType) {
            return TypeMappingResult.unsupported("TIME_TYPE_UNAVAILABLE",
                    "Time is rejected unless enable_time_time64_type is set");
        }
        if (type instanceof TimestampType timestamp) {
            if (timestamp.zone() != TimeZoneSemantics.LOCAL) {
                return TypeMappingResult.unsupported("TIME_ZONE_SEMANTICS_UNMAPPED",
                        "DateTime('zone') is not a verified instant or local mapping");
            }
            int digits = Math.min(timestamp.fractionalDigits(), 9);
            var result = emit("DateTime64(" + digits + ")", column);
            if (timestamp.fractionalDigits() > 9) {
                result = result.loss("TIME_FRACTION_TRUNCATED", "DateTime64 keeps nanoseconds");
            }
            return timestamp.infinity() || timestamp.minYear() < DATE32_MIN_YEAR || timestamp.maxYear() > DATE32_MAX_YEAR
                    ? result.loss("DATE_RANGE_NOT_REPRESENTABLE",
                    "DateTime64 rewrites values outside 1900..2299") : result;
        }
        if (type instanceof UuidType) {
            return emit("UUID", column);
        }
        if (type instanceof JsonType) {
            return TypeMappingResult.unsupported("JSON_TYPE_UNPROVEN",
                    "JSON was created but its value codec was not probed");
        }
        if (type instanceof ArrayType) {
            return TypeMappingResult.unsupported("VALUE_CODEC_UNSUPPORTED", "Array has no verified cross-driver codec");
        }
        return TypeMappingResult.unsupported("TYPE_UNMAPPED", type.canonical() + " has no verified CLICKHOUSE target");
    }

    /**
     * 根据原生列证据推导字符的逻辑类型及值域。
     */
    private TypeMappingResult<NativeColumnType> character(CharacterType text, LogicalColumn column) {
        if (column.key()) {
            return TypeMappingResult.unsupported("TEXT_KEY_EQUALITY_UNPROVEN", "cross-product character key");
        }
        if (text.unit() != LengthUnit.CHARACTERS) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", "octet length semantics");
        }
        if (!text.repertoire().equals("UNICODE")) {
            return TypeMappingResult.unsupported("CHARSET_UNVERIFIED", "only Unicode text was verified");
        }
        var result = emit("String", column);
        if (text.kind() == StorageKind.LARGE) {
            result = result.note("STRING_CAPACITY_UNPROVEN",
                    "no catalog maximum; a 64000000 byte value was produced and a larger one stopped on the memory limit");
        } else {
            result = result.loss("LENGTH_NOT_ENFORCED", "String does not enforce a character length");
        }
        switch (text.comparison()) {
            case BINARY_NO_PAD -> {
            }
            case BINARY_PAD_SPACE ->
                    result = result.loss("PAD_SPACE_EQUALITY_CHANGED", "String does not ignore trailing spaces");
            case NAMED -> result = TypeMappingResult.unsupported("COLLATION_UNAVAILABLE",
                    "ClickHouse columns have no collation; " + text.collation() + " has no equivalent");
            default -> result = result.note("ORDERING_MAY_DIFFER",
                    "equality is byte equality; ordering is not the source locale");
        }
        return result;
    }

    /**
     * 按列可空属性封装原生类型声明并返回精确映射结果。
     */
    private static TypeMappingResult<NativeColumnType> emit(String sql, LogicalColumn column) {
        if (column.nullable()) {
            sql = "Nullable(" + sql + ")";
        }
        return TypeMappingResult.exact(NativeColumnType.of(sql));
    }

    /**
     * 检查当前时间戳是否满足本地日期时间及精度约定。
     */
    private static boolean localTimestamp(ColumnDescription column, ColumnMetadataEvidence facts, int scale) {
        return jdbc(column, Types.TIMESTAMP) && !column.isSigned() && column.getPrecisionSize() == 29
                && column.getScaleSize() == scale && "java.time.LocalDateTime".equals(column.getFiledTypeClassName())
                && noNumericFacts(facts);
    }

    /**
     * 检查当前列的 JDBC 文本类型证据。
     */
    private static boolean stringJdbc(ColumnDescription column, ColumnMetadataEvidence facts) {
        return jdbc(column,
                Types.VARCHAR) && !column.isSigned() && column.getPrecisionSize() == 0 && column.getScaleSize() == 0
                && "java.lang.String".equals(column.getFiledTypeClassName()) && noNumericFacts(facts);
    }

    /**
     * 检查当前元数据未声明数值类型专属属性。
     */
    private static boolean noNumericFacts(ColumnMetadataEvidence facts) {
        return facts.numericPrecision().state() == MetadataValue.State.ABSENT
                && facts.numericScale().state() == MetadataValue.State.ABSENT
                && facts.characterLength().state() == MetadataValue.State.ABSENT;
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

    /** 单个整数类型的 JDBC 和目录探测事实。{@code bits} 是 system.columns numeric_precision。 */
    private record IntFact(String name, int bits, boolean unsigned, int jdbc, int jdbcPrecision, String javaClass) {

        /**
         * 根据给定参数创建 {@code IntFact} 实例。
         */
        private static IntFact of(String name) {
            return switch (name) {
                case "Int8" -> new IntFact(name, 8, false, Types.TINYINT, 3, "java.lang.Byte");
                case "Int16" -> new IntFact(name, 16, false, Types.SMALLINT, 5, "java.lang.Short");
                case "Int32" -> new IntFact(name, 32, false, Types.INTEGER, 10, "java.lang.Integer");
                case "Int64" -> new IntFact(name, 64, false, Types.BIGINT, 19, "java.lang.Long");
                case "UInt8" -> new IntFact(name, 8, true, Types.SMALLINT, 3, "com.clickhouse.data.value.UnsignedByte");
                case "UInt16" ->
                        new IntFact(name, 16, true, Types.INTEGER, 5, "com.clickhouse.data.value.UnsignedShort");
                case "UInt32" ->
                        new IntFact(name, 32, true, Types.BIGINT, 10, "com.clickhouse.data.value.UnsignedInteger");
                case "UInt64" ->
                        new IntFact(name, 64, true, Types.NUMERIC, 20, "com.clickhouse.data.value.UnsignedLong");
                default -> null;
            };
        }
    }
}
