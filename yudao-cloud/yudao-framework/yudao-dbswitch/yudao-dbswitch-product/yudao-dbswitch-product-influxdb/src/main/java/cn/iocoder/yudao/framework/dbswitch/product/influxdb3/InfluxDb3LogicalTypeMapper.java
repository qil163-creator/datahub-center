package cn.iocoder.yudao.framework.dbswitch.product.influxdb3;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalColumn;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalType;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalType.Comparison;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalType.LengthUnit;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalType.StorageKind;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalType.TimeZoneSemantics;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalTypeMapper;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.NativeColumnType;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeFidelity;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeMappingResult;

import java.sql.Types;

/**
 * 将 InfluxDB 3 Core 3.11.5（修订 f083f73c92、镜像 sha256:43991052bc05、Flight SQL JDBC 18.1.0）原生事实映射到
 * 逻辑值域及其反向映射。每个事实都在该服务器上探测；不复制 PostgreSQL 的事实：
 * <ul>
 * <li>HTTP 查询 API 和 Flight SQL 均拒绝 CREATE TABLE（{@code DDL not supported: CreateMemoryTable}）。
 * 只有在线协议写入后才出现表。SQL 模式为 {@code iox}；数据库名不是模式。
 * 未加引号的标识符折叠为小写；双引号保留写入时的大小写。</li>
 * <li>{@code information_schema.columns.data_type} 为目录类型：{@code Int64}、{@code UInt64}、
 * {@code Float64}、{@code Utf8}、{@code Boolean}、{@code Timestamp(ns)}、{@code Dictionary(Int32, Utf8)}。
 * {@code system.influxdb_schema} 为 {@code Utf8View}，此驱动无法读取它。JDBC {@code getColumnClassName}
 * 为 null，{@code isSigned} 为 false，{@code SELECT} 的精度和小数位数均为 0。</li>
 * <li>{@code UInt64} 完整存储（转换为 varchar 会保留十进制文本），但 {@code getObject} 返回有符号
 * long，因此无符号最大值读为 -1。此编解码器会被拒绝，而非映射为字符串。</li>
 * <li>标签为 {@code Dictionary(Int32, Utf8)}，字节长度为 null。Utf8 字段报告字节长度 2147483647，
 * 保留尾部空格、大小写、Unicode 和 U+0000，与标签不属于同一种列类型。</li>
 * <li>{@code Timestamp(ns)} 存储完整 int64 纳秒范围（约 1677..2262）。服务器渲染为 UTC，但
 * UTC+8 JVM 上的 Flight JDBC 18.1.0 返回的 {@code java.sql.Timestamp} 瞬时提前 16 小时，
 * 同时保留秒内纳秒。线协议拒绝 NaN 和 Inf。-0.0 可往返保持。十进制转换仅作为
 * 表达式（{@code Decimal128}）存在；未存储十进制列。</li>
 * </ul>
 */
public final class InfluxDb3LogicalTypeMapper implements LogicalTypeMapper {

    /** {@code influxdb3 --version} 和 HTTP {@code /ping} 报告的发行版本，非 JDBC 产品版本。 */
    public static final String SERVER_PRODUCT = "InfluxDB 3 Core";

    public static final String SERVER_VERSION = "3.11.5";

    public static final String CATALOG_METHOD = "INFLUXDB3_CATALOG_V1";

    /** Utf8 字段的 information_schema character_octet_length。Dictionary 标签报告 null。 */
    private static final long UTF8_MAX_OCTETS = 2147483647L;

    private static final int FLOAT64_BINARY_PRECISION = 24;

    private static final int TIME_MIN_YEAR = 1677;

    private static final int TIME_MAX_YEAR = 2262;

    /**
     * 初始化 {@code InfluxDb3LogicalTypeMapper}。
     */
    public InfluxDb3LogicalTypeMapper() {
    }

    /**
     * 返回当前操作使用的数据库产品。
     */
    @Override
    public ProductTypeEnum product() {
        return ProductTypeEnum.INFLUXDB3;
    }

    /**
     * 将数据库原生列类型映射为逻辑类型。
     */
    @Override
    public TypeMappingResult<LogicalType> toLogical(ColumnDescription column) {
        ColumnMetadataEvidence facts = column.getMetadataEvidence();
        if (!pinned(facts.provenance())) {
            return TypeMappingResult.unsupported("SOURCE_PROVENANCE_UNVERIFIED",
                    "INFLUXDB3_CATALOG_V1 for InfluxDB 3 Core 3.11.5 required");
        }
        String type = facts.nativeType().value().trim();
        if (type.startsWith("Dictionary(")) {
            return dictionary(column, type);
        }
        if (type.startsWith("Decimal")) {
            return TypeMappingResult.unsupported("DECIMAL_COLUMN_UNPROVEN",
                    "decimal exists only as a query cast, not as a stored column");
        }
        if (!driverShape(column) || !noCharacterFacts(facts)) {
            return inconsistent(type);
        }
        return switch (type) {
            case "Int64" -> integer(column, facts);
            case "UInt64" -> uint64(column, facts);
            case "Float64" -> floating(column, facts);
            case "Boolean" -> bool(column, facts);
            case "Utf8" -> text(column, facts);
            case "Timestamp(ns)" -> timestamp(column, facts);
            default -> TypeMappingResult.unsupported("NATIVE_TYPE_UNRECOGNIZED", type);
        };
    }

    /**
     * 根据原生列证据推导整数的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> integer(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (!jdbc(column, Types.BIGINT) || !numericAbsent(facts)) {
            return inconsistent("Int64");
        }
        return exact(new LogicalType.IntegerType(64, false));
    }

    /**
     * 值已存储。Flight JDBC getObject/getLong/getBigDecimal 仍返回有符号 long。
     */
    private TypeMappingResult<LogicalType> uint64(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (!jdbc(column, Types.BIGINT) || !numericAbsent(facts)) {
            return inconsistent("UInt64");
        }
        // 值已存储。Flight JDBC getObject/getLong/getBigDecimal 仍返回有符号 long。
        return TypeMappingResult.unsupported("UINT64_SIGNED_CODEC",
                "Flight JDBC reads UInt64 as a signed long and wraps values above the signed maximum");
    }

    /**
     * 根据原生列证据推导浮点数的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> floating(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (!jdbc(column, Types.DOUBLE) || facts.numericScale().state() != MetadataValue.State.ABSENT
                || facts.characterLength().state() != MetadataValue.State.ABSENT) {
            return inconsistent("Float64");
        }
        if (facts.numericPrecision().state() != MetadataValue.State.VALUE) {
            return incomplete("float precision");
        }
        // 实际观察的目录结构：numeric_precision 24，基数 2，小数位数 null。Arrow 类型仍为 Float64。
        if (facts.numericPrecision().value() != FLOAT64_BINARY_PRECISION) {
            return inconsistent("Float64");
        }
        // -0.0 可往返保持。线协议解析器拒绝 NaN 和 Inf，因此不会存储。
        return exact(new LogicalType.FloatType(64, false, true, false));
    }

    /**
     * 根据原生列证据推导布尔值的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> bool(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (!jdbc(column, Types.BOOLEAN) || !numericAbsent(facts)) {
            return inconsistent("Boolean");
        }
        return exact(new LogicalType.BooleanType());
    }

    /**
     * 根据原生列证据推导文本的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> text(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (!jdbc(column, Types.VARCHAR) || !numbersAbsent(facts)) {
            return inconsistent("Utf8");
        }
        if (facts.characterLength().state() != MetadataValue.State.VALUE || facts.characterLength()
                .value() != UTF8_MAX_OCTETS) {
            return inconsistent("Utf8");
        }
        return exact(new LogicalType.CharacterType(StorageKind.LARGE, null, LengthUnit.OCTETS, UTF8_MAX_OCTETS, "Utf8",
                Comparison.BINARY_NO_PAD, null, true));
    }

    /**
     * 根据原生列证据推导时间戳的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> timestamp(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (!jdbc(column, Types.TIMESTAMP) || !numericAbsent(facts)) {
            return inconsistent("Timestamp(ns)");
        }
        return exact(new LogicalType.TimestampType(9, TimeZoneSemantics.INSTANT, TIME_MIN_YEAR, TIME_MAX_YEAR, false))
                .loss("TIMESTAMP_JDBC_EPOCH_SHIFTED",
                        "Flight JDBC 18.1.0 materializes Timestamp(ns) as java.sql.Timestamp; on the UTC+8 probe the epoch was 16 hours early and only the nanosecond-of-second survived");
    }

    /**
     * 返回当前字典。
     */
    private TypeMappingResult<LogicalType> dictionary(ColumnDescription column, String type) {
        if (!driverShape(column) || !jdbc(column, Types.VARCHAR)) {
            return inconsistent(type);
        }
        ColumnMetadataEvidence facts = column.getMetadataEvidence();
        if (!numbersAbsent(facts) || facts.characterLength().state() != MetadataValue.State.ABSENT
                || !noCharacterFacts(facts)) {
            return inconsistent(type);
        }
        return TypeMappingResult.unsupported("DICTIONARY_NOT_ORDINARY_COLUMN",
                type + " is a tag dictionary, not a Utf8 field; octet length is null and the column is part of the series key");
    }

    /**
     * 将逻辑列类型映射为当前产品的原生类型。
     */
    @Override
    public TypeMappingResult<NativeColumnType> toNative(LogicalColumn column) {
        if (column.key() && column.type() instanceof LogicalType.CharacterType) {
            return TypeMappingResult.unsupported("TEXT_KEY_EQUALITY_UNPROVEN",
                    "a tag is a dictionary series key, not a Utf8 field, so a character key has no verified target");
        }
        TypeMappingResult<NativeColumnType> result = nativeType(column.type());
        if (column.key() && result.fidelity() != TypeFidelity.UNSUPPORTED) {
            result = result.loss("KEY_UNIQUENESS_NOT_ENFORCED",
                    "a repeated tag set and time keeps the last line-protocol point; fields are not a primary key");
        }
        return result;
    }

    /**
     * 根据原生列信息确定数据库类型声明。
     */
    private TypeMappingResult<NativeColumnType> nativeType(LogicalType type) {
        if (type instanceof LogicalType.IntegerType integer) {
            if (integer.unsigned()) {
                return TypeMappingResult.unsupported("UINT64_SIGNED_CODEC",
                        "the only unsigned column is UInt64 and Flight JDBC reads it as a signed long");
            }
            return TypeMappingResult.exact(NativeColumnType.of("Int64"));
        }
        if (type instanceof LogicalType.DecimalType) {
            return TypeMappingResult.unsupported("DECIMAL_COLUMN_UNAVAILABLE",
                    "no stored decimal column; CREATE TABLE is rejected");
        }
        if (type instanceof LogicalType.FloatType value) {
            var result = TypeMappingResult.exact(NativeColumnType.of("Float64"));
            return value.nonFinite() ? result.note("NON_FINITE_REJECTED_BY_TARGET",
                    "NaN and Infinity are rejected by line protocol") : result;
        }
        if (type instanceof LogicalType.BooleanType) {
            return TypeMappingResult.exact(NativeColumnType.of("Boolean"));
        }
        if (type instanceof LogicalType.CharacterType text) {
            return character(text);
        }
        if (type instanceof LogicalType.BinaryType) {
            return TypeMappingResult.unsupported("BINARY_TYPE_UNAVAILABLE", "no binary field type was stored");
        }
        if (type instanceof LogicalType.DateType) {
            return TypeMappingResult.unsupported("DATE_TYPE_UNAVAILABLE", "no date column type was stored");
        }
        if (type instanceof LogicalType.TimeType) {
            return TypeMappingResult.unsupported("TIME_TYPE_UNAVAILABLE", "no time-of-day column type was stored");
        }
        if (type instanceof LogicalType.TimestampType timestamp) {
            return timestamp(timestamp);
        }
        if (type instanceof LogicalType.UuidType) {
            return TypeMappingResult.unsupported("UUID_TYPE_UNAVAILABLE", "no uuid column type was stored");
        }
        if (type instanceof LogicalType.JsonType) {
            return TypeMappingResult.unsupported("JSON_TYPE_UNAVAILABLE", "no json column type was stored");
        }
        if (type instanceof LogicalType.XmlType) {
            return TypeMappingResult.unsupported("XML_TYPE_UNAVAILABLE", "no xml column type was stored");
        }
        return TypeMappingResult.unsupported("TYPE_UNMAPPED", type.canonical() + " has no verified INFLUXDB3 target");
    }

    /**
     * 根据原生列证据推导时间戳的逻辑类型及值域。
     */
    private TypeMappingResult<NativeColumnType> timestamp(LogicalType.TimestampType timestamp) {
        if (timestamp.zone() != TimeZoneSemantics.INSTANT) {
            return TypeMappingResult.unsupported("TIME_ZONE_SEMANTICS_UNMAPPED",
                    "Timestamp(ns) is a UTC instant, not a local or offset wall time");
        }
        var result = TypeMappingResult.exact(NativeColumnType.of("Timestamp(ns)"));
        if (timestamp.fractionalDigits() > 9) {
            result = result.loss("TIME_FRACTION_TRUNCATED", "Timestamp(ns) keeps nanoseconds");
        }
        if (timestamp.infinity() || timestamp.minYear() < TIME_MIN_YEAR || timestamp.maxYear() > TIME_MAX_YEAR) {
            result = result.loss("DATE_RANGE_NOT_REPRESENTABLE",
                    "Influx time is int64 nanoseconds, about 1677..2262, with no infinity");
        }
        return result.loss("TIMESTAMP_JDBC_EPOCH_SHIFTED",
                "Flight JDBC 18.1.0 materializes Timestamp(ns) as java.sql.Timestamp; on the UTC+8 probe the epoch was 16 hours early");
    }

    /**
     * 根据原生列证据推导字符的逻辑类型及值域。
     */
    private TypeMappingResult<NativeColumnType> character(LogicalType.CharacterType text) {
        if (text.unit() != LengthUnit.CHARACTERS && text.unit() != LengthUnit.OCTETS) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", text.canonical());
        }
        if (!text.repertoire().equals("UNICODE") && !text.repertoire().equals("Utf8")) {
            return TypeMappingResult.unsupported("CHARSET_UNVERIFIED", "only Utf8 fields were stored");
        }
        long bytes;
        try {
            bytes = text.kind() == StorageKind.LARGE ? text.maxOctets()
                    : text.unit() == LengthUnit.OCTETS ? text.length() : Math.multiplyExact(text.length(), 4L);
        } catch (ArithmeticException overflow) {
            return TypeMappingResult.unsupported("LENGTH_EXCEEDS_TARGET", text.canonical());
        }
        if (bytes > UTF8_MAX_OCTETS) {
            return TypeMappingResult.unsupported("LENGTH_EXCEEDS_TARGET", text.canonical());
        }
        var result = TypeMappingResult.exact(NativeColumnType.of("Utf8"));
        if (text.kind() == StorageKind.FIXED || text.comparison() == Comparison.BINARY_PAD_SPACE) {
            result = result.loss("PAD_SPACE_EQUALITY_CHANGED", "a Utf8 field keeps trailing spaces");
        } else if (text.comparison() == Comparison.NAMED) {
            return TypeMappingResult.unsupported("COLLATION_UNAVAILABLE", "Utf8 fields have no named collation");
        } else {
            result = result.note("ORDERING_MAY_DIFFER",
                    "equality distinguishes case and trailing spaces; ordering was not probed");
        }
        return result;
    }

    /** JDBC 产品版本 "2" 及 SELECT version() 的 DataFusion 51.0.0 不是此发行版本。 */
    static boolean pinned(MetadataProvenance provenance) {
        return provenance.product() == ProductTypeEnum.INFLUXDB3
                && SERVER_PRODUCT.equals(provenance.serverProduct())
                && SERVER_VERSION.equals(provenance.serverVersion())
                && CATALOG_METHOD.equals(provenance.method());
    }

    /**
     * 检查驱动报告的类、符号及精度事实是否符合当前类型约定。
     */
    private static boolean driverShape(ColumnDescription column) {
        return column.getFiledTypeClassName() == null && !column.isSigned()
                && column.getPrecisionSize() == 0 && column.getScaleSize() == 0;
    }

    /**
     * 判断数值精度和小数位数证据是否均明确缺失。
     */
    private static boolean numericAbsent(ColumnMetadataEvidence facts) {
        return numbersAbsent(facts) && facts.characterLength().state() == MetadataValue.State.ABSENT;
    }

    /**
     * 检查数值精度及小数位数事实均明确缺失。
     */
    private static boolean numbersAbsent(ColumnMetadataEvidence facts) {
        return facts.numericPrecision().state() == MetadataValue.State.ABSENT
                && facts.numericScale().state() == MetadataValue.State.ABSENT;
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
