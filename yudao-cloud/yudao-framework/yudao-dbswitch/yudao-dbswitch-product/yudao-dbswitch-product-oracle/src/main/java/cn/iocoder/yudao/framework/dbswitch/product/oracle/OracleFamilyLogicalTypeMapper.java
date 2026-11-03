package cn.iocoder.yudao.framework.dbswitch.product.oracle;

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
 * Oracle 23ai/26ai Free 23.26.3、KingbaseES V008R006C009B0014（Oracle 模式）及 DM8 8.1.5.60 原生事实与
 * 逻辑值域的双向映射。每项事实均在真实服务端探测，未复制其他映射器的结论：
 * <ul>
 * <li>三者均接受无界 NUMBER；Oracle 与 Kingbase 将空字符串存为 NULL，DM 保留 ''。
 * DM 在 LENGTH_IN_CHAR=0 时按字节计算 VARCHAR，且不同于另外两者，其 VARCHAR 比较采用 PAD SPACE。</li>
 * <li>Oracle DATE 与 Kingbase Oracle 模式 DATE 携带日内时间，DM DATE 则仅有日期。Oracle 驱动
 * 通过 getObject 返回 oracle.sql.TIMESTAMP/TIMESTAMPTZ/TIMESTAMPLTZ/INTERVAL 对象，因此这些源类型
 * 没有跨驱动值编解码器；Kingbase 与 DM 物化为 java.sql.Timestamp 子类，已验证可移植。</li>
 * <li>Oracle 负小数位数的 NUMBER 在插入时静默舍入；Oracle FLOAT(p) 是 NUMBER 子类，
 * 驱动读取为 Double；Kingbase 在 DDL 中将 NUMBER(p,-s) 规范为 numeric(p+s,0)，两者均不映射。</li>
 * <li>Oracle RAW 存储实际字节长度，不填充，上限为 2000；DM BINARY 为较短值补零，
 * VARBINARY 上限为 8188；Kingbase 没有 RAW，使用 bytea。三者的 BLOB/CLOB 值均经已验证的
 * 流水线规范化为 Clob→String、Blob→byte[]；CLOB 在各产品中均将 '' 保持为空值。</li>
 * <li>三者均存在 BOOLEAN：Oracle 23ai SQL BOOLEAN、Kingbase bool、DM BOOLEAN。Oracle TSTZ 保留
 * 存储的偏移量，Kingbase 与 DM 将其规范为会话时区。已验证端点上无法创建 Oracle JSON/XMLTYPE，
 * 受 ASSM 表空间或 XDB 库限制；Kingbase JSON 读取为私有 KBobject，DM JSON 则是
 * 经过 JSONB 规范化的文本，通过 Clob 读取，并保留精确小数。</li>
 * </ul>
 */
public final class OracleFamilyLogicalTypeMapper implements LogicalTypeMapper {

    public enum Flavor {
        ORACLE(ProductTypeEnum.ORACLE), KINGBASE(ProductTypeEnum.KINGBASE), DM(ProductTypeEnum.DM);

        private final ProductTypeEnum product;

        /**
         * 初始化 {@code Flavor}。
         */
        Flavor(ProductTypeEnum product) {
            this.product = product;
        }
    }

    private static final long GIB = 1L << 30;

    private static final Pattern DECIMAL = Pattern.compile(
            "(numeric|number|decimal)(?:\\(([0-9]{1,2}),(-?[0-9]{1,3})\\))?");

    private static final Pattern FIXED_TEXT = Pattern.compile("character\\(([0-9]+)(?: ?(b|c|byte|char))?\\)");

    private static final Pattern VARYING_TEXT = Pattern.compile(
            "character varying\\(([0-9]+)(?: ?(b|c|byte|char))?\\)");

    private static final Pattern ORACLE_TEXT = Pattern.compile(
            "(varchar2|char|nvarchar2|nchar)\\(([0-9]+)(?: ?(b|c|byte|char))?\\)");

    private static final Pattern ORACLE_RAW = Pattern.compile("raw\\(([0-9]+)\\)");

    private static final Pattern DM_TEXT = Pattern.compile("(varchar2?|char)\\(([0-9]+)(?: ?(b|c|byte|char))?\\)");

    private static final Pattern DM_BINARY = Pattern.compile("(binary|varbinary)\\(([0-9]+)\\)");

    private static final Pattern ORACLE_TEMPORAL = Pattern.compile(
            "timestamp\\(([0-9])\\)( with time zone| with local time zone)?");

    private static final Pattern KB_TEMPORAL = Pattern.compile("timestamp\\(([0-9])\\) (with|without) time zone");

    private static final Pattern DM_TEMPORAL = Pattern.compile("(time|timestamp|datetime)\\(([0-9])\\)");

    private final Flavor flavor;

    /**
     * 校验构造参数并初始化 {@code OracleFamilyLogicalTypeMapper}。
     */
    public OracleFamilyLogicalTypeMapper(Flavor flavor) {
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
        var result = logical(facts.nativeType().value(), column, facts);
        return facts.sequence().state() == MetadataValue.State.UNKNOWN && result.fidelity() != TypeFidelity.UNSUPPORTED
                ? result.note("SEQUENCE_DEPENDENCY_UNOBSERVED",
                "no owned-sequence observation; dependent sequences are not recreated") : result;
    }

    /**
     * 返回逻辑。
     */
    private TypeMappingResult<LogicalType> logical(String type, ColumnDescription column,
            ColumnMetadataEvidence facts) {
        if (flavor == Flavor.ORACLE) {
            return oracleLogical(type, column, facts);
        }
        if (flavor == Flavor.KINGBASE) {
            return kingbaseLogical(type, column, facts);
        }
        return dmLogical(type, column, facts);
    }

    // ---------------------------------------------------------------- Oracle 源端

    /**
     * 无界 NUMBER：原始驱动报告精度 0、小数位数 -127；运行时连接池中。
     */
    private TypeMappingResult<LogicalType> oracleLogical(String type, ColumnDescription column,
            ColumnMetadataEvidence facts) {
        String lower = type.toLowerCase(Locale.ROOT);
        if (lower.startsWith("float(")) {
            return TypeMappingResult.unsupported("FLOAT_PRECISION_UNMAPPED",
                    "FLOAT(p) is a NUMBER subclass the driver reads as Double");
        }
        var raw = ORACLE_RAW.matcher(lower);
        if (raw.matches()) {
            long length = Long.parseLong(raw.group(1));
            if (!jdbc(column, Types.VARBINARY, Types.BINARY) || column.getPrecisionSize() != length) {
                return inconsistent(type);
            }
            if (facts.characterLength().state() != MetadataValue.State.VALUE || facts.characterLength()
                    .value() != length) {
                return incomplete("binary length");
            }
            return exact(new BinaryType(StorageKind.VARYING, length, null));
        }
        var text = ORACLE_TEXT.matcher(lower);
        if (text.matches()) {
            long length = Long.parseLong(text.group(2));
            String base = text.group(1);
            boolean fixed = base.equals("char") || base.equals("nchar");
            int expected = switch (base) {
                case "varchar2" -> Types.VARCHAR;
                case "char" -> Types.CHAR;
                case "nvarchar2" -> Types.NVARCHAR;
                default -> Types.NCHAR;
            };
            if (!jdbc(column, expected) || column.getPrecisionSize() != length) {
                return inconsistent(type);
            }
            if (facts.characterLength().state() != MetadataValue.State.VALUE || facts.characterLength()
                    .value() != length) {
                return incomplete("character length");
            }
            String unit = text.group(3);
            boolean charUnit = base.startsWith("n") || unit != null && (unit.equals("c") || unit.equals("char"));
            return character(fixed ? StorageKind.FIXED : StorageKind.VARYING, length,
                    charUnit ? LengthUnit.CHARACTERS : LengthUnit.OCTETS, "UNICODE", null, null);
        }
        var decimal = DECIMAL.matcher(lower);
        if (decimal.matches() && decimal.group(1).equals("number")) {
            if (decimal.group(2) == null) {
                // 无界 NUMBER：原始驱动报告精度 0、小数位数 -127；运行时连接池中
                // 符合 J2EE13 的会话报告 0/0。两种情况下目录精度及小数位数均缺失。
                if (!jdbc(column, Types.NUMERIC) || column.getPrecisionSize() != 0
                        || column.getScaleSize() != -127 && column.getScaleSize() != 0) {
                    return inconsistent(type);
                }
                if (facts.numericPrecision().state() != MetadataValue.State.ABSENT || facts.numericScale()
                        .state() != MetadataValue.State.ABSENT) {
                    return incomplete("unbounded numeric facts");
                }
                return exact(new DecimalType(null, null, false, false));
            }
            int precision = Integer.parseInt(decimal.group(2)), scale = Integer.parseInt(decimal.group(3));
            if (scale < 0) {
                return TypeMappingResult.unsupported("NEGATIVE_SCALE_NUMERIC_ROUNDS",
                        "Oracle rounds NUMBER(p,-s) values on insert");
            }
            if (!jdbc(column,
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
        switch (lower) {
            case "binary_float":
                return jdbc(column, 100) && column.getPrecisionSize() == 0 && column.getScaleSize() == 0
                        ? exact(new FloatType(32, false, false, true)) : inconsistent(type);
            case "binary_double":
                return jdbc(column, 101) && column.getPrecisionSize() == 0 && column.getScaleSize() == 0
                        ? exact(new FloatType(64, false, false, true)) : inconsistent(type);
            case "date":
                // ojdbc 驱动将 DATE 暴露为精度 7 的 TIMESTAMP，其值携带日内时间。
                return jdbc(column, Types.TIMESTAMP) && column.getPrecisionSize() == 7 && column.getScaleSize() == 0
                        ? exact(new TimestampType(0, TimeZoneSemantics.LOCAL, -4712, 9999, false)) : inconsistent(type);
            case "blob":
                return jdbc(column, Types.BLOB) ? exact(
                        new BinaryType(StorageKind.LARGE, null, 4L * GIB - 1)) : inconsistent(type);
            case "clob", "nclob":
                return jdbc(column, Types.CLOB, Types.NCLOB)
                        ? character(StorageKind.LARGE, null, LengthUnit.CHARACTERS, "UNICODE", 4L * GIB - 1,
                        null) : inconsistent(type);
            case "boolean":
                return jdbc(column, Types.BOOLEAN) ? exact(new BooleanType()) : inconsistent(type);
            case "json":
                return TypeMappingResult.unsupported("JSON_UNAVAILABLE_ON_VERIFIED_ENDPOINT",
                        "the verified Oracle endpoint stores tables in a non-ASSM tablespace that rejects JSON columns");
            case "xmltype":
                return TypeMappingResult.unsupported("XMLTYPE_UNAVAILABLE_ON_VERIFIED_ENDPOINT",
                        "the verified Oracle endpoint fails XMLTYPE DDL (ORA-00600 unable to load XDB library)");
            default:
                break;
        }
        var temporal = ORACLE_TEMPORAL.matcher(lower);
        if (temporal.matches()) {
            int digits = Integer.parseInt(temporal.group(1));
            boolean zone = temporal.group(2) != null;
            boolean local = " with local time zone".equals(temporal.group(2));
            int expected = zone ? (local ? -102 : -101) : Types.TIMESTAMP;
            if (!jdbc(column, expected) || column.getScaleSize() != digits) {
                return inconsistent(type);
            }
            return TypeMappingResult.unsupported("VALUE_CODEC_UNSUPPORTED",
                    "ojdbc returns oracle.sql." + (zone ? (local ? "TIMESTAMPLTZ" : "TIMESTAMPTZ") : "TIMESTAMP")
                            + "; the verified full path reads getObject without a cross-driver codec");
        }
        if (lower.startsWith("interval ")) {
            return TypeMappingResult.unsupported("VALUE_CODEC_UNSUPPORTED", "ojdbc returns a private INTERVAL object");
        }
        return TypeMappingResult.unsupported("NATIVE_TYPE_UNRECOGNIZED", type);
    }

    // ---------------------------------------------------------------- Kingbase 源端

    /**
     * Oracle 模式 DATE 携带日内时间，驱动将其报告为小数位数 0 的 TIMESTAMP。
     */
    private TypeMappingResult<LogicalType> kingbaseLogical(String type, ColumnDescription column,
            ColumnMetadataEvidence facts) {
        String lower = type.toLowerCase(Locale.ROOT);
        var decimal = DECIMAL.matcher(lower);
        if (decimal.matches() && decimal.group(1).equals("numeric")) {
            if (decimal.group(2) == null) {
                if (!jdbc(column, Types.NUMERIC) || column.getPrecisionSize() != 0 || column.getScaleSize() != 0) {
                    return inconsistent(type);
                }
                if (facts.numericPrecision().state() != MetadataValue.State.ABSENT || facts.numericScale()
                        .state() != MetadataValue.State.ABSENT) {
                    return incomplete("unbounded numeric facts");
                }
                return exact(new DecimalType(null, null, false, true));
            }
            int precision = Integer.parseInt(decimal.group(2)), scale = Integer.parseInt(decimal.group(3));
            if (scale < 0) {
                return TypeMappingResult.unsupported("NEGATIVE_SCALE_NUMERIC_ROUNDS",
                        "Kingbase normalizes NUMBER(p,-s) at DDL");
            }
            if (!jdbc(column,
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
            return exact(new DecimalType(precision, scale, false, true));
        }
        if (lower.equals("real")) {
            return jdbc(column, Types.REAL) ? exact(new FloatType(32, false, true, true)) : inconsistent(type);
        }
        if (lower.equals("double precision")) {
            return jdbc(column, Types.DOUBLE) ? exact(new FloatType(64, false, true, true)) : inconsistent(type);
        }
        if (lower.equals("boolean")) {
            return jdbc(column, Types.BIT, Types.BOOLEAN) ? exact(new BooleanType()) : inconsistent(type);
        }
        if (lower.equals("bytea") || lower.equals("blob")) {
            return jdbc(column, Types.BINARY, Types.VARBINARY) ? exact(
                    new BinaryType(StorageKind.LARGE, null, GIB - 1)) : inconsistent(type);
        }
        if (lower.equals("clob") || lower.equals("text")) {
            return jdbc(column, Types.VARCHAR, Types.LONGVARCHAR) ? character(StorageKind.LARGE, null,
                    LengthUnit.CHARACTERS, "UNICODE", GIB - 1, null)
                    : inconsistent(type);
        }
        var varying = VARYING_TEXT.matcher(lower);
        if (varying.matches()) {
            long length = Long.parseLong(varying.group(1));
            if (!jdbc(column, Types.VARCHAR) || column.getPrecisionSize() != length) {
                return inconsistent(type);
            }
            if (facts.characterLength().state() != MetadataValue.State.VALUE || facts.characterLength()
                    .value() != length) {
                return incomplete("character length");
            }
            String unit = varying.group(2);
            boolean charUnit = unit == null || unit.equals("c") || unit.equals("char");
            return character(StorageKind.VARYING, length, charUnit ? LengthUnit.CHARACTERS : LengthUnit.OCTETS,
                    "UNICODE", null, null);
        }
        var fixed = FIXED_TEXT.matcher(lower);
        if (fixed.matches()) {
            long length = Long.parseLong(fixed.group(1));
            if (!jdbc(column, Types.CHAR) || column.getPrecisionSize() != length) {
                return inconsistent(type);
            }
            if (facts.characterLength().state() != MetadataValue.State.VALUE || facts.characterLength()
                    .value() != length) {
                return incomplete("character length");
            }
            String unit = fixed.group(2);
            boolean charUnit = unit == null || unit.equals("c") || unit.equals("char");
            return character(StorageKind.FIXED, length, charUnit ? LengthUnit.CHARACTERS : LengthUnit.OCTETS, "UNICODE",
                    null, null);
        }
        switch (lower) {
            case "date":
                // Oracle 模式 DATE 携带日内时间，驱动将其报告为小数位数 0 的 TIMESTAMP。
                return jdbc(column, Types.TIMESTAMP) && column.getScaleSize() == 0
                        ? exact(new TimestampType(0, TimeZoneSemantics.LOCAL, -4712, 5874897, false)) : inconsistent(
                        type);
            case "timestamp without time zone":
                return jdbc(column, Types.TIMESTAMP) ? exact(
                        new TimestampType(6, TimeZoneSemantics.LOCAL, -4712, 294276, false)) : inconsistent(type);
            case "timestamp with time zone":
                return jdbc(column, Types.TIMESTAMP) ? exact(
                        new TimestampType(6, TimeZoneSemantics.INSTANT, -4712, 294276, false)) : inconsistent(type);
            default:
                break;
        }
        var temporal = KB_TEMPORAL.matcher(lower);
        if (temporal.matches()) {
            int digits = Integer.parseInt(temporal.group(1));
            boolean zone = temporal.group(2).equals("with");
            if (!jdbc(column, Types.TIMESTAMP) || column.getScaleSize() != digits) {
                return inconsistent(type);
            }
            return exact(
                    new TimestampType(digits, zone ? TimeZoneSemantics.INSTANT : TimeZoneSemantics.LOCAL, -4712, 294276,
                            false));
        }
        if (lower.startsWith("interval ")) {
            return TypeMappingResult.unsupported("VALUE_CODEC_UNSUPPORTED",
                    "the Kingbase driver returns a private interval object");
        }
        if (lower.equals("json")) {
            return TypeMappingResult.unsupported("VALUE_CODEC_UNSUPPORTED",
                    "the Kingbase driver returns com.kingbase8.util.KBobject for json");
        }
        return TypeMappingResult.unsupported("NATIVE_TYPE_UNRECOGNIZED", type);
    }

    // ---------------------------------------------------------------- DM 源端

    /**
     * DM 对所有文本类型采用 PAD SPACE 比较，已在 TEXT 与 CLOB 上探测。
     */
    private TypeMappingResult<LogicalType> dmLogical(String type, ColumnDescription column,
            ColumnMetadataEvidence facts) {
        String lower = type.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "tinyint":
                return jdbc(column, Types.TINYINT) && column.getPrecisionSize() == 3 && column.getScaleSize() == 0
                        ? exact(new IntegerType(8, false)) : inconsistent(type);
            case "smallint":
                return jdbc(column, Types.SMALLINT) && column.getPrecisionSize() == 5 && column.getScaleSize() == 0
                        ? exact(new IntegerType(16, false)) : inconsistent(type);
            case "int", "integer":
                return jdbc(column, Types.INTEGER) && column.getPrecisionSize() == 10 && column.getScaleSize() == 0
                        ? exact(new IntegerType(32, false)) : inconsistent(type);
            case "bigint":
                return jdbc(column, Types.BIGINT) && column.getPrecisionSize() == 19 && column.getScaleSize() == 0
                        ? exact(new IntegerType(64, false)) : inconsistent(type);
            case "double", "float":
                return jdbc(column, Types.DOUBLE) && column.getPrecisionSize() == 53 && column.getScaleSize() == 0
                        ? exact(new FloatType(64, false, true, false)) : inconsistent(type);
            case "real":
                return jdbc(column, Types.REAL) && column.getPrecisionSize() == 24 && column.getScaleSize() == 0
                        ? exact(new FloatType(32, false, true, false)) : inconsistent(type);
            case "bit":
                return jdbc(column, Types.BIT) ? exact(new BooleanType()) : inconsistent(type);
            case "boolean":
                return jdbc(column, Types.BOOLEAN) ? exact(new BooleanType()) : inconsistent(type);
            case "date":
                return jdbc(column, Types.DATE) ? exact(new DateType(-4712, 9999, false)) : inconsistent(type);
            case "text", "clob":
                return jdbc(column, Types.CLOB, Types.LONGVARCHAR)
                        // DM 对所有文本类型采用 PAD SPACE 比较，已在 TEXT 与 CLOB 上探测。
                        ? character(StorageKind.LARGE, null, LengthUnit.CHARACTERS, "UNICODE", 4L * GIB - 1,
                        Comparison.BINARY_PAD_SPACE)
                        : inconsistent(type);
            case "blob", "image":
                return jdbc(column, Types.BLOB, Types.LONGVARBINARY)
                        ? exact(new BinaryType(StorageKind.LARGE, null, 4L * GIB - 1)) : inconsistent(type);
            case "json":
                return jdbc(column, Types.CLOB, Types.LONGVARCHAR)
                        ? exact(new JsonType(JsonRepresentation.NORMALIZED, JsonNumbers.ARBITRARY_DECIMAL))
                        .note("JSON_DUPLICATE_KEYS_REJECTED",
                                "DM rejects duplicate JSON keys loudly at insert") : inconsistent(type);
            case "time":
                return time(column, 0, type);
            case "timestamp":
                return timestamp(column, 6, type);
            case "datetime":
                return timestamp(column, 6, type);
            default:
                break;
        }
        var decimal = DECIMAL.matcher(lower);
        if (decimal.matches() && (decimal.group(1).equals("number") || decimal.group(1).equals("decimal"))) {
            if (decimal.group(2) == null) {
                if (!jdbc(column, Types.NUMERIC,
                        Types.DECIMAL) || column.getPrecisionSize() != 0 || column.getScaleSize() != 0) {
                    return inconsistent(type);
                }
                if (facts.numericPrecision().state() != MetadataValue.State.ABSENT || facts.numericScale()
                        .state() != MetadataValue.State.ABSENT) {
                    return incomplete("unbounded numeric facts");
                }
                return exact(new DecimalType(null, null, false, false));
            }
            int precision = Integer.parseInt(decimal.group(2)), scale = Integer.parseInt(decimal.group(3));
            if (scale < 0) {
                return TypeMappingResult.unsupported("NEGATIVE_SCALE_NUMERIC_ROUNDS",
                        "DM rejects negative scales at DDL");
            }
            if (!jdbc(column, Types.NUMERIC,
                    Types.DECIMAL) || column.getPrecisionSize() != precision || column.getScaleSize() != scale) {
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
        var text = DM_TEXT.matcher(lower);
        if (text.matches()) {
            long length = Long.parseLong(text.group(2));
            boolean fixed = text.group(1).equals("char");
            if (!jdbc(column, fixed ? Types.CHAR : Types.VARCHAR) || column.getPrecisionSize() != length) {
                return inconsistent(type);
            }
            if (facts.characterLength().state() != MetadataValue.State.VALUE || facts.characterLength()
                    .value() != length) {
                return incomplete("character length");
            }
            boolean charUnit = "char".equals(text.group(3)) || "c".equals(text.group(3));
            // DM 对 VARCHAR 与 CHAR 采用 PAD SPACE 比较，已探测；LENGTH_IN_CHAR=0 使默认单位为字节。
            return character(fixed ? StorageKind.FIXED : StorageKind.VARYING, length,
                    charUnit ? LengthUnit.CHARACTERS : LengthUnit.OCTETS, "UNICODE", null, Comparison.BINARY_PAD_SPACE);
        }
        var binary = DM_BINARY.matcher(lower);
        if (binary.matches()) {
            long length = Long.parseLong(binary.group(2));
            if (!jdbc(column, binary.group(1)
                    .equals("binary") ? Types.BINARY : Types.VARBINARY) || column.getPrecisionSize() != length) {
                return inconsistent(type);
            }
            if (facts.characterLength().state() != MetadataValue.State.VALUE || facts.characterLength()
                    .value() != length) {
                return incomplete("binary length");
            }
            // DM BINARY 为较短值补零；两种类型均最多允许 n 字节，因此 VARYING 才是准确的值域。
            return exact(new BinaryType(StorageKind.VARYING, length, null));
        }
        var temporal = DM_TEMPORAL.matcher(lower);
        if (temporal.matches()) {
            int digits = Integer.parseInt(temporal.group(2));
            if (temporal.group(1).equals("time")) {
                return time(column, digits, type);
            }
            return timestamp(column, digits, type);
        }
        if (lower.startsWith("interval ")) {
            return TypeMappingResult.unsupported("VALUE_CODEC_UNSUPPORTED",
                    "the DM driver returns a private DmdbInterval object");
        }
        return TypeMappingResult.unsupported("NATIVE_TYPE_UNRECOGNIZED", type);
    }

    /**
     * 根据原生列证据推导时间的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> time(ColumnDescription column, int digits, String type) {
        if (!jdbc(column, Types.TIME) || column.getScaleSize() != digits) {
            return inconsistent(type);
        }
        // java.sql.Time 保留毫秒，TIME(4..6) 经其读取会丢失末尾数字位。
        var result = exact(new TimeType(Math.min(digits, 3), false, TimeRange.TIME_OF_DAY));
        return digits > 3 ? result.loss("TIME_FRACTION_TRUNCATED", "java.sql.Time keeps milliseconds only") : result;
    }

    /**
     * 根据原生列证据推导时间戳的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> timestamp(ColumnDescription column, int digits, String type) {
        // DM 的 DmdbTimestamp 继承 java.sql.Timestamp，已验证可通过流水线移植。
        if (!jdbc(column, Types.TIMESTAMP) || column.getScaleSize() != digits) {
            return inconsistent(type);
        }
        var result = exact(new TimestampType(digits, TimeZoneSemantics.LOCAL, -4712, 9999, false));
        return java.time.ZoneId.systemDefault().getRules().isFixedOffset() ? result
                : result.loss("TIMESTAMP_JVM_ZONE_DST_SHIFT", "the driver materializes timestamps in the JVM zone");
    }

    /**
     * 根据原生列证据推导字符的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> character(StorageKind kind, Long length, LengthUnit unit, String repertoire,
            Long maxOctets, Comparison comparison) {
        if (comparison == null) {
            comparison = kind == StorageKind.FIXED ? Comparison.BINARY_PAD_SPACE : Comparison.BINARY_NO_PAD;
        }
        return exact(new CharacterType(kind, kind == StorageKind.LARGE ? null : length, unit,
                kind == StorageKind.LARGE ? maxOctets : null, repertoire, comparison, null, flavor == Flavor.DM));
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
            return TypeMappingResult.exact(NativeColumnType.of("BOOLEAN"));
        }
        if (type instanceof CharacterType text) {
            return character(text, column);
        }
        if (type instanceof BinaryType binary) {
            return binary(binary);
        }
        if (type instanceof DateType date) {
            var result = TypeMappingResult.exact(NativeColumnType.of("DATE"));
            return date.minYear() < -4712 || date.maxYear() > dateMaxYear()
                    ? result.loss("DATE_RANGE_NOT_REPRESENTABLE", date.canonical()) : result;
        }
        if (type instanceof TimeType time) {
            if (flavor != Flavor.DM) {
                return TypeMappingResult.unsupported("TIME_TYPE_UNAVAILABLE",
                        flavor.product + " has no time-only column type");
            }
            if (time.withTimeZone()) {
                return TypeMappingResult.unsupported("TIME_ZONE_SEMANTICS_UNMAPPED", time.canonical());
            }
            int digits = Math.min(time.fractionalDigits(), tsFspMax());
            var result = TypeMappingResult.exact(NativeColumnType.of("TIME(" + digits + ")"));
            if (time.fractionalDigits() > tsFspMax()) {
                result = result.loss("TIME_FRACTION_TRUNCATED", "DM time keeps microseconds");
            }
            return time.range() != TimeRange.TIME_OF_DAY ? result.note("TIME_OUTSIDE_DAY_NOT_REPRESENTABLE",
                    "24:00:00 is rejected loudly") : result;
        }
        if (type instanceof TimestampType timestamp) {
            return timestamp(timestamp);
        }
        if (type instanceof UuidType) {
            return TypeMappingResult.unsupported("UUID_TYPE_UNAVAILABLE", "no native uuid type");
        }
        if (type instanceof JsonType json) {
            if (flavor != Flavor.DM) {
                return TypeMappingResult.unsupported("JSON_TARGET_UNAVAILABLE",
                        flavor.product + " has no verified JSON target path");
            }
            var result = TypeMappingResult.exact(NativeColumnType.of("JSON"));
            if (json.representation() == JsonRepresentation.TEXT) {
                result = result.loss("JSON_NORMALIZED", "whitespace, key order and duplicate keys are normalized");
            }
            return result.note("JSON_DUPLICATE_KEYS_REJECTED", "duplicate keys are rejected loudly at insert");
        }
        return TypeMappingResult.unsupported("TYPE_UNMAPPED",
                type.canonical() + " has no verified " + flavor.product + " target");
    }

    /**
     * 根据原生列证据推导精确小数的逻辑类型及值域。
     */
    private TypeMappingResult<NativeColumnType> decimal(DecimalType decimal) {
        if (!decimal.bounded()) {
            var result = TypeMappingResult.exact(NativeColumnType.of("NUMBER"));
            return decimal.nan() && flavor != Flavor.KINGBASE
                    ? result.note("NAN_REJECTED_BY_TARGET_DRIVER",
                    "NaN has no NUMBER value; the write fails loudly") : result;
        }
        if (decimal.precision() > 38 || decimal.scale() < 0 || decimal.scale() > decimal.precision()) {
            return TypeMappingResult.unsupported("NUMERIC_PRECISION_EXCEEDS_TARGET", decimal.canonical());
        }
        var result = TypeMappingResult.exact(
                NativeColumnType.of("NUMBER(" + decimal.precision() + "," + decimal.scale() + ")"));
        if (decimal.nan() && flavor != Flavor.KINGBASE) {
            result = result.note("NAN_REJECTED_BY_TARGET_DRIVER", "NaN has no NUMBER value; the write fails loudly");
        }
        return result;
    }

    /**
     * 根据原生列证据推导浮点数的逻辑类型及值域。
     */
    private TypeMappingResult<NativeColumnType> floating(FloatType value) {
        String name = value.bits() == 32 ? (flavor == Flavor.DM ? "REAL" : "BINARY_FLOAT")
                : (flavor == Flavor.DM ? "DOUBLE" : "BINARY_DOUBLE");
        var result = TypeMappingResult.exact(nonNegative(name, value.nonNegative()));
        if (value.nonFinite() && flavor != Flavor.KINGBASE) {
            result = result.note("NON_FINITE_REJECTED_BY_TARGET_DRIVER",
                    "NaN/Infinity writes fail loudly on " + flavor.product);
        }
        if (value.negativeZero() && flavor == Flavor.ORACLE) {
            result = result.loss("NEGATIVE_ZERO_NOT_PRESERVED", "Oracle BINARY_FLOAT/BINARY_DOUBLE store -0.0 as 0");
        }
        return result;
    }

    /**
     * 根据原生列证据推导时间戳的逻辑类型及值域。
     */
    private TypeMappingResult<NativeColumnType> timestamp(TimestampType timestamp) {
        int digits = Math.min(timestamp.fractionalDigits(), tsFspMax());
        TypeMappingResult<NativeColumnType> result;
        switch (timestamp.zone()) {
            case LOCAL -> result = TypeMappingResult.exact(NativeColumnType.of("TIMESTAMP(" + digits + ")"));
            case INSTANT ->
                // 时间线上的时刻就是完整值域，三者均原样存储，偏移量规范化对该时刻不可见。
                    result = TypeMappingResult.exact(NativeColumnType.of("TIMESTAMP(" + digits + ") WITH TIME ZONE"));
            default -> result = flavor == Flavor.ORACLE
                    ? TypeMappingResult.exact(NativeColumnType.of("TIMESTAMP(" + digits + ") WITH TIME ZONE"))
                    : TypeMappingResult.unsupported("TIME_ZONE_SEMANTICS_UNMAPPED",
                    flavor.product + " timestamptz does not keep the offset");
        }
        if (timestamp.fractionalDigits() > tsFspMax()) {
            result = result.loss("TIME_FRACTION_TRUNCATED",
                    flavor.product + " timestamps keep " + tsFspMax() + " digits");
        }
        if (timestamp.minYear() < -4712 || timestamp.maxYear() > tsMaxYear()) {
            result = result.loss("DATE_RANGE_NOT_REPRESENTABLE", timestamp.canonical());
        }
        return result;
    }

    /**
     * 根据原生列证据推导整数的逻辑类型及值域。
     */
    private NativeColumnType integer(IntegerType type) {
        if (flavor == Flavor.DM) {
            String name = switch (type.bits()) {
                case 8 -> type.unsigned() ? "SMALLINT" : "TINYINT";
                case 16 -> type.unsigned() ? "INT" : "SMALLINT";
                case 24 -> "INT";
                case 32 -> type.unsigned() ? "BIGINT" : "INT";
                default -> type.unsigned() ? "DECIMAL(20,0)" : "BIGINT";
            };
            return NativeColumnType.of(name);
        }
        // NUMBER(p,0) 通过十进制数字精确覆盖所有整数宽度，无需 CHECK。
        int digits = switch (type.bits()) {
            case 8 -> 3;
            case 16 -> 5;
            case 24 -> type.unsigned() ? 8 : 7;
            case 32 -> 10;
            default -> type.unsigned() ? 20 : 19;
        };
        return NativeColumnType.of("NUMBER(" + digits + ",0)");
    }

    /**
     * 根据原生列证据推导字符的逻辑类型及值域。
     */
    private TypeMappingResult<NativeColumnType> character(CharacterType text, LogicalColumn column) {
        if (column.key()) {
            return TypeMappingResult.unsupported("TEXT_KEY_EQUALITY_UNPROVEN", "cross-product character key");
        }
        boolean familySource = text.repertoire().equals("utf8mb4");
        if (!text.repertoire().equals("UNICODE") && !familySource) {
            return TypeMappingResult.unsupported("CHARSET_UNVERIFIED",
                    "only Unicode and utf8mb4 repertoires are verified");
        }
        if (text.kind() == StorageKind.LARGE) {
            var result = TypeMappingResult.exact(NativeColumnType.of("CLOB"));
            if (text.maxOctets() > 4L * GIB - 1) {
                result = result.note("VALUE_SIZE_LIMITED_BY_TARGET", "larger values fail loudly");
            }
            // DM 对所有文本类型采用 PAD SPACE 比较，已在 CLOB 上探测；其他两者不填充。
            if (pads() && text.comparison() != Comparison.BINARY_PAD_SPACE) {
                result = result.loss("PAD_SPACE_EQUALITY_CHANGED",
                        "source does not ignore trailing spaces; " + flavor.product + " does");
            }
            if (!pads() && text.comparison() == Comparison.BINARY_PAD_SPACE) {
                result = result.loss("PAD_SPACE_EQUALITY_CHANGED",
                        "source ignores trailing spaces; " + flavor.product + " does not");
            }
            if (text.comparison() == Comparison.NAMED) {
                result = result.loss("COLLATION_EQUALITY_CHANGED",
                        "source collation " + text.collation() + " has no verified " + flavor.product + " equivalent");
            }
            // Kingbase 驱动即使对 TEXT/CLOB 也将 '' 存为 NULL；Oracle 将 '' 保留为空 LOB。
            return flavor == Flavor.KINGBASE ? emptyStringLoss(result, column.nullable()) : result;
        }
        boolean charUnit = text.unit() == LengthUnit.CHARACTERS;
        long limit = charUnit ? charDeclMax() : byteMax();
        if (text.length() > limit) {
            return TypeMappingResult.unsupported("LENGTH_EXCEEDS_TARGET", text.canonical());
        }
        String base;
        if (flavor == Flavor.DM) {
            // DM VARCHAR 在 LENGTH_IN_CHAR=0 时按字节计数；以字符为单位的源保留声明长度，
            // 但超出字节上限的多字节值在插入时明确失败。
            base = (text.kind() == StorageKind.FIXED ? "CHAR" : "VARCHAR") + "(" + text.length() + ")";
        } else if (flavor == Flavor.KINGBASE) {
            base = (text.kind() == StorageKind.FIXED ? "CHAR" : "VARCHAR2") + "(" + text.length()
                    + (charUnit ? " CHAR" : " BYTE") + ")";
        } else {
            base = (text.kind() == StorageKind.FIXED ? "CHAR" : "VARCHAR2") + "(" + text.length()
                    + (charUnit ? " CHAR" : " BYTE") + ")";
        }
        var result = TypeMappingResult.exact(NativeColumnType.of(base));
        switch (text.comparison()) {
            case BINARY_NO_PAD -> {
                if (pads()) {
                    result = result.loss("PAD_SPACE_EQUALITY_CHANGED",
                            "source NO PAD; " + flavor.product + " ignores trailing spaces");
                }
            }
            case BINARY_PAD_SPACE -> {
                if (!pads()) {
                    result = result.loss("PAD_SPACE_EQUALITY_CHANGED",
                            "source ignores trailing spaces; " + flavor.product + " does not");
                }
            }
            case NAMED -> result = result.loss("COLLATION_EQUALITY_CHANGED",
                    "source collation " + text.collation() + " has no verified " + flavor.product + " equivalent");
            default -> {
                if (pads()) {
                    result = result.loss("PAD_SPACE_EQUALITY_CHANGED",
                            "source equality is byte equality; " + flavor.product + " ignores trailing spaces");
                }
            }
        }
        if (charUnit) {
            result = result.note("TARGET_BYTE_CAP_ENFORCED",
                    flavor.product + " caps stored bytes; longer multi-byte values fail loudly");
        }
        if (text.nulAllowed() && flavor != Flavor.DM) {
            result = result.note("NUL_CHARACTER_REJECTED_BY_TARGET", flavor.product + " rejects U+0000 loudly");
        }
        return emptyStringLoss(result, column.nullable());
    }

    /**
     * 根据原生列证据推导二进制的逻辑类型及值域。
     */
    private TypeMappingResult<NativeColumnType> binary(BinaryType binary) {
        switch (binary.kind()) {
            case LARGE -> {
                var result = TypeMappingResult.exact(NativeColumnType.of("BLOB"));
                if (binary.maxOctets() > 4L * GIB - 1) {
                    result = result.note("VALUE_SIZE_LIMITED_BY_TARGET", "larger values fail loudly");
                }
                // Kingbase 驱动即使对 bytea/BLOB 也将空 byte[] 存为 NULL；Oracle 与 DM 保持为空。
                return flavor == Flavor.KINGBASE ? emptyBinaryLoss(result, true) : result;
            }
            case FIXED, VARYING -> {
                if (binary.length() > binaryMax()) {
                    return TypeMappingResult.unsupported("LENGTH_EXCEEDS_TARGET", binary.canonical());
                }
                if (flavor == Flavor.ORACLE)
                // RAW 存储实际字节长度，绝不填充，是固定及变长值域的超集。
                {
                    return emptyBinaryLoss(TypeMappingResult.exact(NativeColumnType.of("RAW(" + binary.length() + ")")),
                            true);
                }
                if (flavor == Flavor.KINGBASE)
                // bytea 加已验证且强制执行的 CHECK 可精确保持声明值域。
                {
                    return emptyBinaryLoss(TypeMappingResult.exact(NativeColumnType.checked("BYTEA",
                            (binary.kind() == StorageKind.FIXED ? "octet_length(" + NativeColumnType.COLUMN + ") = " : "octet_length(" + NativeColumnType.COLUMN + ") <= ")
                                    + binary.length())), true);
                }
                // DM BINARY 补零，因此 VARBINARY 可对固定及变长源均精确保持值。
                return TypeMappingResult.exact(NativeColumnType.of("VARBINARY(" + binary.length() + ")"));
            }
            default -> throw new IllegalStateException(binary.kind().name());
        }
    }

    /**
     * 根据目标兼容模式记录空字符串转为 NULL 的映射损失。
     */
    private TypeMappingResult<NativeColumnType> emptyStringLoss(TypeMappingResult<NativeColumnType> result,
            boolean nullable) {
        if (!emptyStringNull()) {
            return result;
        }
        return nullable ? result.loss("EMPTY_STRING_BECOMES_NULL", flavor.product + " stores '' as NULL")
                : result.note("EMPTY_STRING_REJECTED_WHEN_NOT_NULL", "'' becomes NULL and violates NOT NULL loudly");
    }

    /**
     * 记录空二进制值转为 NULL 对当前可空值域的影响。
     */
    private TypeMappingResult<NativeColumnType> emptyBinaryLoss(TypeMappingResult<NativeColumnType> result,
            boolean nullable) {
        return nullable ? result.loss("EMPTY_BLOB_BECOMES_NULL",
                flavor.product + " stores an empty binary value as NULL")
                : result.note("EMPTY_BLOB_REJECTED_WHEN_NOT_NULL",
                "an empty binary value becomes NULL and violates NOT NULL loudly");
    }

    // ---------------------------------------------------------------- 各产品变体的已探测事实

    /**
     * 判断当前 Oracle 家族兼容模式是否将空字符串视为 NULL。
     */
    private boolean emptyStringNull() {
        return flavor != Flavor.DM;
    }

    /**
     * 判断当前产品是否对字符值进行填充。
     */
    private boolean pads() {
        return flavor == Flavor.DM;
    }

    /**
     * 返回当前产品受支持字符类型的字节长度上限。
     */
    private long byteMax() {
        return switch (flavor) {
            case ORACLE -> 4000L;
            case KINGBASE -> 10485760L;
            case DM -> 32767L;
        };
    }

    /**
     * 返回当前产品受支持字符类型的声明长度上限。
     */
    private long charDeclMax() {
        return switch (flavor) {
            case ORACLE -> 4000L;
            case KINGBASE -> 10485760L;
            case DM -> 32767L;
        };
    }

    /**
     * 返回当前产品受支持二进制类型的长度上限。
     */
    private long binaryMax() {
        return switch (flavor) {
            case ORACLE -> 2000L;
            case KINGBASE -> GIB - 1;
            case DM -> 8188L;
        };
    }

    /**
     * 返回当前产品时间戳支持的小数秒精度上限。
     */
    private int tsFspMax() {
        return flavor == Flavor.ORACLE ? 9 : 6;
    }

    /**
     * 返回当前产品日期值支持的年份上限。
     */
    private int dateMaxYear() {
        return flavor == Flavor.KINGBASE ? 5874897 : 9999;
    }

    /**
     * 返回当前产品时间戳支持的年份上限。
     */
    private int tsMaxYear() {
        return flavor == Flavor.KINGBASE ? 294276 : 9999;
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
