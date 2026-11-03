package cn.iocoder.yudao.framework.dbswitch.product.oscar;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalColumn;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalType;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalType.BinaryType;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalType.DateType;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalType.DecimalType;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalType.StorageKind;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalTypeMapper;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.NativeColumnType;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeFidelity;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeMappingResult;

import java.sql.Types;
import java.util.Locale;

/**
 * 神通 V8.0.251217（JDBC 产品名 OSCAR，驱动 com.oscar.Driver 7.20）上读到的列事实。
 * 无界 NUMBER、DATE 含时刻、字符集和 TIMESTAMP 年份都没有在这台服务器上被证明，因此不从 Oracle 或达梦抄。
 */
public final class OscarLogicalTypeMapper implements LogicalTypeMapper {

    /** {@code VERSION()} 与 {@code DatabaseMetaData.getDatabaseProductVersion()} 的全文。 */
    public static final String SERVER_VERSION =
            "神通数据库管理系统V8.0.251217 for Linux(x86 64bit) (2000 connections) (license invalid after 180 days)";

    public static final String METHOD = "OSCAR_JDBC_V1";

    /** 未写精度的 NUMBER 被驱动报告为 numeric(1000,38)，不是无界小数。 */
    private static final int NUMBER_PRECISION = 1000;

    private static final int NUMBER_SCALE = 38;

    /** {@code DatabaseMetaData.getColumns} 与结果集 display size 都是这个值；只回读过 5 字节。 */
    private static final long BLOB_REPORTED_OCTETS = 2147483647L;

    /**
     * 初始化 {@code OscarLogicalTypeMapper}。
     */
    public OscarLogicalTypeMapper() {
    }

    /**
     * 返回当前操作使用的数据库产品。
     */
    @Override
    public ProductTypeEnum product() {
        return ProductTypeEnum.OSCAR;
    }

    /**
     * 将数据库原生列类型映射为逻辑类型。
     */
    @Override
    public TypeMappingResult<LogicalType> toLogical(ColumnDescription column) {
        ColumnMetadataEvidence facts = column.getMetadataEvidence();
        if (!probed(facts.provenance())) {
            return TypeMappingResult.unsupported("SOURCE_PROVENANCE_UNVERIFIED",
                    "OSCAR V8.0.251217 JDBC provenance required");
        }
        if (facts.nativeType().state() != MetadataValue.State.VALUE) {
            return incomplete("native type");
        }
        if (facts.charset().state() != MetadataValue.State.ABSENT || facts.collation()
                .state() != MetadataValue.State.ABSENT) {
            return inconsistent("charset");
        }
        String type = facts.nativeType().value().trim().toLowerCase(Locale.ROOT);
        TypeMappingResult<LogicalType> result = switch (type) {
            case "numeric" -> numeric(column, facts);
            case "date" -> date(column, facts);
            case "timestamp" -> timestamp(column);
            case "char", "bpchar", "varchar", "clob" -> character(column, type);
            case "blob" -> blob(column, facts);
            default -> TypeMappingResult.unsupported("NATIVE_TYPE_UNRECOGNIZED", type);
        };
        if (result.fidelity() != TypeFidelity.UNSUPPORTED && facts.sequence().state() == MetadataValue.State.UNKNOWN) {
            result = result.note("SEQUENCE_DEPENDENCY_UNOBSERVED",
                    "no owned-sequence observation; dependent sequences are not recreated");
        }
        return result;
    }

    /**
     * 根据原生列证据推导数值的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> numeric(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (!jdbc(column, Types.NUMERIC) || !column.isSigned() || !"java.math.BigDecimal".equals(
                column.getFiledTypeClassName())) {
            return inconsistent("numeric");
        }
        if (facts.numericPrecision().state() != MetadataValue.State.VALUE || facts.numericScale()
                .state() != MetadataValue.State.VALUE) {
            return incomplete("numeric precision/scale");
        }
        int precision = facts.numericPrecision().value();
        int scale = facts.numericScale().value();
        if (column.getPrecisionSize() != precision || column.getScaleSize() != scale || facts.characterLength()
                .state() != MetadataValue.State.ABSENT) {
            return inconsistent("numeric");
        }
        if (precision < 1 || precision > NUMBER_PRECISION || scale < 0 || scale > NUMBER_SCALE || scale > precision) {
            return TypeMappingResult.unsupported("NUMERIC_PRECISION_EXCEEDS_TARGET", precision + "," + scale);
        }
        return TypeMappingResult.exact(new DecimalType(precision, scale, false, false));
    }

    /**
     * 根据原生列证据推导日期的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> date(ColumnDescription column, ColumnMetadataEvidence facts) {
        // 写入带时刻的 TIMESTAMP 字面量后，读回仍是 2020-01-02。DATE '0001-01-01' 与 DATE '9999-12-31' 被接受。
        if (!jdbc(column, Types.DATE) || !"java.sql.Date".equals(column.getFiledTypeClassName())
                || column.getPrecisionSize() != 8 || column.getScaleSize() != 0) {
            return inconsistent("date");
        }
        if (facts.numericPrecision().state() != MetadataValue.State.ABSENT || facts.numericScale()
                .state() != MetadataValue.State.ABSENT
                || facts.characterLength().state() != MetadataValue.State.ABSENT) {
            return inconsistent("date");
        }
        TypeMappingResult<LogicalType> result = TypeMappingResult.exact(new DateType(1, 9999, false));
        return result.note("DATE_TIME_NOT_STORED", "a timestamp literal was stored as a calendar date");
    }

    /**
     * 根据原生列证据推导时间戳的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> timestamp(ColumnDescription column) {
        if (!jdbc(column, Types.TIMESTAMP) || !"java.sql.Timestamp".equals(column.getFiledTypeClassName())) {
            return inconsistent("timestamp");
        }
        // 6 位小数能回读，但没有探测年份上下界，不能写成一个日期范围。
        return TypeMappingResult.unsupported("TIMESTAMP_YEAR_BOUNDS_UNPROVEN",
                "TIMESTAMP '2020-01-02 03:04:05.123456' round-tripped; year bounds were not probed");
    }

    /**
     * 根据原生列证据推导字符的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> character(ColumnDescription column, String type) {
        int expected = switch (type) {
            case "char", "bpchar" -> Types.CHAR;
            case "varchar" -> Types.VARCHAR;
            default -> Types.CLOB;
        };
        String expectedClass = type.equals("clob") ? "java.sql.Clob" : "java.lang.String";
        if (!jdbc(column, expected) || !expectedClass.equals(column.getFiledTypeClassName())) {
            return inconsistent(type);
        }
        // SYS_CONTEXT / server_encoding 都没有给出字符集。没有探测到的字汇不能填 UNICODE。
        return TypeMappingResult.unsupported("REPERTOIRE_UNOBSERVED", type + " charset was not observed");
    }

    /**
     * 返回当前二进制大对象。
     */
    private TypeMappingResult<LogicalType> blob(ColumnDescription column, ColumnMetadataEvidence facts) {
        if (!jdbc(column, Types.BLOB) || !"java.sql.Blob".equals(column.getFiledTypeClassName())
                || column.getPrecisionSize() != 0 || column.getScaleSize() != 0) {
            return inconsistent("blob");
        }
        if (facts.numericPrecision().state() != MetadataValue.State.ABSENT || facts.characterLength()
                .state() != MetadataValue.State.ABSENT) {
            return inconsistent("blob");
        }
        TypeMappingResult<LogicalType> result = TypeMappingResult.exact(
                new BinaryType(StorageKind.LARGE, null, BLOB_REPORTED_OCTETS));
        return result.note("BLOB_CAPACITY_NOT_FILLED",
                "the driver reports 2147483647 octets; only 5 bytes including 0x00 were read back");
    }

    /**
     * 将逻辑列类型映射为当前产品的原生类型。
     */
    @Override
    public TypeMappingResult<NativeColumnType> toNative(LogicalColumn column) {
        LogicalType type = column.type();
        if (type instanceof DecimalType decimal) {
            return decimal(decimal);
        }
        if (type instanceof DateType date) {
            return date(date);
        }
        if (type instanceof BinaryType binary) {
            return binary(binary);
        }
        if (type instanceof LogicalType.TimestampType) {
            return TypeMappingResult.unsupported("TIMESTAMP_YEAR_BOUNDS_UNPROVEN",
                    "TIMESTAMP year bounds were not probed");
        }
        if (type instanceof LogicalType.CharacterType) {
            return TypeMappingResult.unsupported("REPERTOIRE_UNOBSERVED", "character repertoire was not observed");
        }
        return TypeMappingResult.unsupported("TYPE_UNMAPPED", type.canonical());
    }

    /**
     * 根据原生列证据推导精确小数的逻辑类型及值域。
     */
    private TypeMappingResult<NativeColumnType> decimal(DecimalType decimal) {
        if (!decimal.bounded()) {
            return TypeMappingResult.unsupported("UNBOUNDED_NUMERIC_UNMAPPED", "bare NUMBER is numeric(1000,38)");
        }
        if (decimal.nan()) {
            return TypeMappingResult.unsupported("NAN_UNPROVEN", "NaN was not probed");
        }
        int precision = decimal.precision();
        int scale = decimal.scale();
        if (precision < 1 || precision > NUMBER_PRECISION || scale < 0 || scale > NUMBER_SCALE || scale > precision) {
            return TypeMappingResult.unsupported("NUMERIC_PRECISION_EXCEEDS_TARGET", decimal.canonical());
        }
        String sql = precision == NUMBER_PRECISION && scale == NUMBER_SCALE ? "NUMBER" : "NUMBER(" + precision + "," + scale + ")";
        return TypeMappingResult.exact(NativeColumnType.of(sql));
    }

    /**
     * 根据原生列证据推导日期的逻辑类型及值域。
     */
    private TypeMappingResult<NativeColumnType> date(DateType date) {
        if (date.infinity()) {
            return TypeMappingResult.unsupported("INFINITY_UNPROVEN", "infinite dates were not probed");
        }
        if (date.minYear() < 1 || date.maxYear() > 9999) {
            return TypeMappingResult.unsupported("DATE_RANGE_NOT_REPRESENTABLE", date.canonical());
        }
        return TypeMappingResult.exact(NativeColumnType.of("DATE"));
    }

    /**
     * 根据原生列证据推导二进制的逻辑类型及值域。
     */
    private TypeMappingResult<NativeColumnType> binary(BinaryType binary) {
        if (binary.kind() != StorageKind.LARGE || binary.maxOctets() == null || binary.maxOctets() > BLOB_REPORTED_OCTETS) {
            return TypeMappingResult.unsupported("BINARY_FORM_UNPROVEN",
                    "only BLOB up to the reported display size was probed");
        }
        return TypeMappingResult.exact(NativeColumnType.of("BLOB"));
    }

    /**
     * 构造当前连接探测得到的能力或类型证据。
     */
    private static boolean probed(MetadataProvenance provenance) {
        return provenance.product() == ProductTypeEnum.OSCAR
                && "OSCAR".equals(provenance.serverProduct())
                && cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.OscarNativeCatalog.verifiedVersion(
                provenance.serverVersion())
                && METHOD.equals(provenance.method());
    }

    /**
     * 校验原生列报告的 JDBC 类型是否属于允许范围。
     */
    private static boolean jdbc(ColumnDescription column, int type) {
        return column.getFieldType() == type;
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
