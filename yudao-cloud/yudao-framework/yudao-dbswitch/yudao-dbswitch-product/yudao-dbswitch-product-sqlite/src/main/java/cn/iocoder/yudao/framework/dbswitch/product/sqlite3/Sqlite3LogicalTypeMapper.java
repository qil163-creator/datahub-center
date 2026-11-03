package cn.iocoder.yudao.framework.dbswitch.product.sqlite3;

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
 * SQLite 3.31.1 和 3.49.1 使用对应 sqlite-jdbc 文件驱动，{@code PRAGMA encoding=UTF-8}。
 * 下方普通亲和性事实均已在两个版本的
 * 临时文件中探测，它们不属于 SQL 类型系统：
 * <ul>
 * <li>{@code WHERE 1=0} 元数据对每列都报告 {@code java.lang.Object}，显示长度为 2147483647。
 *     3.31.1 不支持 {@code STRICT}；较新版本的 STRICT、ANY 和表约束需要单独的原生
 *     结构事实，不能使这些亲和性映射成为 EXACT 值域；不存在 {@code information_schema}。</li>
 * <li>声明类型表示亲和性；非键列能存储整数、实数、文本和二进制值。超出
 *     有符号 64 位的值会成为不精确的 REAL，且 {@code getLong} 饱和；{@code -0.0} 变为 {@code 0.0}。
 *     {@code setDouble(NaN)} 存储为 NULL；Inf 和 -Inf 仍为 REAL。</li>
 * <li>{@code VARCHAR} 和 {@code CHAR} 不强制长度也不补齐；{@code DECIMAL(p,s)} 存储为
 *     整数、二进制实数或文本，忽略标度；{@code BOOLEAN} 可为 0、1 或其他整数，不是布尔类型。
 *     {@code DATE} 和 {@code DATETIME} 将 ISO 值保留为文本，{@code getDate} 和 {@code getTimestamp} 会失败，
 *     也能存储数值；{@code DATETIME} 的 JDBC 类型为 {@code DATE}，而非 {@code TIMESTAMP}。</li>
 * <li>接受 1000000000 字节的 {@code zeroblob}，1000000001 字节则报 {@code SQLITE_TOOBIG}
 *     （"String or BLOB exceeds size limit"，字符串或 BLOB 超出大小限制）。</li>
 * </ul>
 * 未探测的声明类型保持未识别；共享目录检查实际引擎版本、编码和
 * PRAGMA 事实；跨产品规划器还会比较驱动可空信息与目录事实，
 * 尤其两个 JDBC 版本对 INTEGER 主键的报告方式不同。
 */
public final class Sqlite3LogicalTypeMapper implements LogicalTypeMapper {

    static final long MAX_OCTETS = 1_000_000_000L;

    private static final Pattern VARCHAR = Pattern.compile("varchar\\((\\d+)\\)");

    private static final Pattern CHAR = Pattern.compile("char\\((\\d+)\\)");

    private static final Pattern DECIMAL = Pattern.compile("decimal\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)");

    /**
     * 初始化 {@code Sqlite3LogicalTypeMapper}。
     */
    public Sqlite3LogicalTypeMapper() {
    }

    /**
     * 返回当前操作使用的数据库产品。
     */
    @Override
    public ProductTypeEnum product() {
        return ProductTypeEnum.SQLITE3;
    }

    /**
     * 将数据库原生列类型映射为逻辑类型。
     */
    @Override
    public TypeMappingResult<LogicalType> toLogical(ColumnDescription column) {
        ColumnMetadataEvidence facts = column.getMetadataEvidence();
        if (!provenance(facts.provenance())) {
            return TypeMappingResult.unsupported("SOURCE_PROVENANCE_UNVERIFIED",
                    "SQLite file catalog requires SQLITE_CATALOG_V1, product SQLite, version 3.31.1 or 3.49.1");
        }
        if (facts.nativeType().state() != MetadataValue.State.VALUE) {
            return incomplete("native type");
        }
        String declared = facts.nativeType().value().trim();
        String type = declared.toLowerCase(Locale.ROOT);
        if (type.equals("integer")) {
            return integer(column, facts, declared);
        }
        if (type.equals("real")) {
            return real(column, facts, declared);
        }
        if (type.equals("text")) {
            return text(column, facts, declared);
        }
        if (type.equals("blob")) {
            return blob(column, facts, declared);
        }
        if (type.equals("numeric")) {
            return numeric(column, facts, declared);
        }
        if (type.equals("boolean")) {
            return bool(column, facts, declared);
        }
        if (type.equals("date")) {
            return date(column, facts, declared);
        }
        if (type.equals("datetime")) {
            return datetime(column, facts, declared);
        }
        Matcher varchar = VARCHAR.matcher(type);
        if (varchar.matches()) {
            return varchar(column, facts, declared, varchar.group(1));
        }
        Matcher character = CHAR.matcher(type);
        if (character.matches()) {
            return character(column, facts, declared, character.group(1));
        }
        Matcher decimal = DECIMAL.matcher(type);
        if (decimal.matches()) {
            return decimal(column, facts, declared, decimal.group(1), decimal.group(2));
        }
        return TypeMappingResult.unsupported("NATIVE_TYPE_UNRECOGNIZED", declared.isEmpty() ? "<empty>" : declared);
    }

    /**
     * 根据原生列证据推导整数的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> integer(ColumnDescription column, ColumnMetadataEvidence facts,
            String declared) {
        TypeMappingResult<LogicalType> blocked = gate(column, facts, declared, Types.INTEGER, "INTEGER", true, 0, 0,
                null, null, null);
        if (blocked != null) {
            return blocked;
        }
        return exact(new IntegerType(64, false))
                .loss("DECLARED_TYPE_AFFINITY_NOT_STRICT",
                        "INTEGER affinity stored real, text and blob on a non-key column")
                .loss("INT64_OVERFLOW_STORED_AS_REAL",
                        "a literal above signed 64-bit is stored as inexact REAL and getLong saturates");
    }

    /**
     * Inf 和 -Inf 可以往返；NaN 不能，setDouble(NaN) 存储为 NULL；负零存储为 0.0。
     */
    private TypeMappingResult<LogicalType> real(ColumnDescription column, ColumnMetadataEvidence facts,
            String declared) {
        TypeMappingResult<LogicalType> blocked = gate(column, facts, declared, Types.REAL, "REAL", true, 0, 0, null,
                null, null);
        if (blocked != null) {
            return blocked;
        }
        // Inf 和 -Inf 可以往返；NaN 不能，setDouble(NaN) 存储为 NULL；负零存储为 0.0。
        return exact(new FloatType(64, false, false, true))
                .loss("DECLARED_TYPE_AFFINITY_NOT_STRICT",
                        "REAL affinity converts integers to binary64 and still stores text and blob")
                .loss("NAN_STORED_AS_NULL", "setDouble(NaN) is stored as NULL; Inf and -Inf remain real");
    }

    /**
     * 根据原生列证据推导文本的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> text(ColumnDescription column, ColumnMetadataEvidence facts,
            String declared) {
        TypeMappingResult<LogicalType> blocked = gate(column, facts, declared, Types.VARCHAR, "TEXT", false, 0, 0, null,
                null, null);
        if (blocked != null) {
            return blocked;
        }
        return exact(new CharacterType(StorageKind.LARGE, null, LengthUnit.CHARACTERS, MAX_OCTETS, "UTF-8",
                Comparison.BINARY_NO_PAD, null, true))
                .loss("DECLARED_TYPE_AFFINITY_NOT_STRICT",
                        "TEXT affinity converts numbers to text but stores BLOB unchanged");
    }

    /**
     * 返回当前二进制大对象。
     */
    private TypeMappingResult<LogicalType> blob(ColumnDescription column, ColumnMetadataEvidence facts,
            String declared) {
        TypeMappingResult<LogicalType> blocked = gate(column, facts, declared, Types.BLOB, "BLOB", false, 0, 0, null,
                null, null);
        if (blocked != null) {
            return blocked;
        }
        // 对非二进制存储类别调用 getBytes 不能得到原存储值，不存在统一的二进制值域。
        return TypeMappingResult.unsupported("BLOB_AFFINITY_MIXED_STORAGE",
                "BLOB affinity stores integer and text unchanged");
    }

    /**
     * 根据原生列证据推导数值的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> numeric(ColumnDescription column, ColumnMetadataEvidence facts,
            String declared) {
        TypeMappingResult<LogicalType> blocked = gate(column, facts, declared, Types.NUMERIC, "NUMERIC", true, 0, 0,
                null, null, null);
        if (blocked != null) {
            return blocked;
        }
        return TypeMappingResult.unsupported("NUMERIC_AFFINITY_MIXED_STORAGE",
                "NUMERIC stores integers, reals and non-numeric text; there is no decimal storage class");
    }

    /**
     * 根据原生列证据推导布尔值的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> bool(ColumnDescription column, ColumnMetadataEvidence facts,
            String declared) {
        TypeMappingResult<LogicalType> blocked = gate(column, facts, declared, Types.BOOLEAN, "BOOLEAN", false, 0, 0,
                null, null, null);
        if (blocked != null) {
            return blocked;
        }
        return TypeMappingResult.unsupported("BOOLEAN_IS_NUMERIC_AFFINITY",
                "TRUE/FALSE and 0/1 are integers, other integers are stored, and non-numeric text stays text");
    }

    /**
     * 根据原生列证据推导日期的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> date(ColumnDescription column, ColumnMetadataEvidence facts,
            String declared) {
        TypeMappingResult<LogicalType> blocked = gate(column, facts, declared, Types.DATE, "DATE", false, 0, 0, null,
                null, null);
        if (blocked != null) {
            return blocked;
        }
        return TypeMappingResult.unsupported("DATE_STORED_AS_TEXT_OR_NUMBER",
                "ISO dates stay text and getDate fails; integers and reals are stored as numbers");
    }

    /**
     * 按当前产品类型约定解析日期时间值。
     */
    private TypeMappingResult<LogicalType> datetime(ColumnDescription column, ColumnMetadataEvidence facts,
            String declared) {
        // 已探测 JDBC 类型为 DATE（91）而非 TIMESTAMP，类型名称仍为 DATETIME。
        TypeMappingResult<LogicalType> blocked = gate(column, facts, declared, Types.DATE, "DATETIME", false, 0, 0,
                null, null, null);
        if (blocked != null) {
            return blocked;
        }
        return TypeMappingResult.unsupported("DATETIME_STORED_AS_TEXT_OR_NUMBER",
                "ISO datetimes stay text and getTimestamp fails; setTimestamp stores integer milliseconds");
    }

    /**
     * 返回当前变长字符串。
     */
    private TypeMappingResult<LogicalType> varchar(ColumnDescription column, ColumnMetadataEvidence facts,
            String declared, String digits) {
        Integer length = canonical(digits);
        if (length == null) {
            return TypeMappingResult.unsupported("NATIVE_TYPE_UNRECOGNIZED", declared);
        }
        TypeMappingResult<LogicalType> blocked = gate(column, facts, declared, Types.VARCHAR, "VARCHAR", false, length,
                0,
                null, null, length.longValue());
        if (blocked != null) {
            return blocked;
        }
        if (length == 0) {
            return TypeMappingResult.unsupported("ZERO_LENGTH_NOT_A_DOMAIN",
                    "VARCHAR(0) is accepted and reports precision 0; it is not a character domain");
        }
        return exact(new CharacterType(StorageKind.VARYING, length.longValue(), LengthUnit.CHARACTERS, null, "UTF-8",
                Comparison.BINARY_NO_PAD, null, true))
                .loss("DECLARED_TYPE_AFFINITY_NOT_STRICT",
                        "VARCHAR has TEXT affinity: numbers become text and BLOB stays BLOB")
                .loss("LENGTH_NOT_ENFORCED", "the declared VARCHAR length does not reject a longer text value");
    }

    /**
     * 根据原生列证据推导字符的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> character(ColumnDescription column, ColumnMetadataEvidence facts,
            String declared, String digits) {
        Integer length = canonical(digits);
        if (length == null || length < 1) {
            return TypeMappingResult.unsupported("NATIVE_TYPE_UNRECOGNIZED", declared);
        }
        TypeMappingResult<LogicalType> blocked = gate(column, facts, declared, Types.CHAR, "CHAR", false, length, 0,
                null, null, length.longValue());
        if (blocked != null) {
            return blocked;
        }
        return exact(new CharacterType(StorageKind.FIXED, length.longValue(), LengthUnit.CHARACTERS, null, "UTF-8",
                Comparison.BINARY_NO_PAD, null, true))
                .loss("DECLARED_TYPE_AFFINITY_NOT_STRICT", "CHAR has TEXT affinity and does not pad")
                .loss("LENGTH_NOT_ENFORCED", "the declared CHAR length does not reject a longer text value");
    }

    /**
     * 根据原生列证据推导精确小数的逻辑类型及值域。
     */
    private TypeMappingResult<LogicalType> decimal(ColumnDescription column, ColumnMetadataEvidence facts,
            String declared,
            String precisionDigits, String scaleDigits) {
        Integer precision = canonical(precisionDigits);
        Integer scale = canonical(scaleDigits);
        if (precision == null || scale == null || precision < 1) {
            return TypeMappingResult.unsupported("NATIVE_TYPE_UNRECOGNIZED", declared);
        }
        // 已探测的 DECIMAL 在 JDBC 元数据中为无符号，但值是有符号实数，标度可能大于精度。
        TypeMappingResult<LogicalType> blocked = gate(column, facts, declared, Types.DECIMAL, "DECIMAL", false,
                precision, scale,
                precision, scale, null);
        if (blocked != null) {
            return blocked;
        }
        return TypeMappingResult.unsupported("DECIMAL_STORED_AS_REAL_OR_TEXT",
                "DECIMAL(p,s) is stored as integer or binary real, scale is not applied, and non-numeric text stays text");
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
        if (type instanceof FloatType value) {
            return floating(value);
        }
        if (type instanceof DecimalType decimal) {
            return TypeMappingResult.unsupported(
                    decimal.bounded() ? "DECIMAL_STORED_AS_REAL_OR_TEXT" : "NUMERIC_AFFINITY_MIXED_STORAGE",
                    "SQLite has no exact decimal storage class");
        }
        if (type instanceof BooleanType) {
            return TypeMappingResult.unsupported("BOOLEAN_IS_NUMERIC_AFFINITY",
                    "a BOOLEAN declaration is not a boolean domain");
        }
        if (type instanceof CharacterType text) {
            return character(text, column);
        }
        if (type instanceof BinaryType binary) {
            return binary(binary);
        }
        if (type instanceof DateType) {
            return TypeMappingResult.unsupported("DATE_STORED_AS_TEXT_OR_NUMBER",
                    "a DATE declaration does not store dates");
        }
        if (type instanceof TimeType) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED",
                    "TIME was not probed and SQLite has no TIME storage class");
        }
        if (type instanceof TimestampType timestamp) {
            return timestamp.zone() == TimeZoneSemantics.LOCAL
                    ? TypeMappingResult.unsupported("DATETIME_STORED_AS_TEXT_OR_NUMBER",
                    "DATETIME does not preserve a local timestamp")
                    : TypeMappingResult.unsupported("TIME_ZONE_SEMANTICS_UNMAPPED",
                    "no offset or instant datetime was probed");
        }
        if (type instanceof UuidType) {
            return TypeMappingResult.unsupported("UUID_TYPE_UNAVAILABLE", "no UUID declared type was probed");
        }
        return TypeMappingResult.unsupported("TYPE_UNMAPPED", type.canonical() + " has no verified SQLite target");
    }

    /**
     * 根据原生列证据推导整数的逻辑类型及值域。
     */
    private static TypeMappingResult<NativeColumnType> integer(IntegerType integer) {
        TypeMappingResult<NativeColumnType> result = TypeMappingResult.exact(NativeColumnType.of("INTEGER"))
                .loss("DECLARED_TYPE_AFFINITY_NOT_STRICT",
                        "a non-key INTEGER column stores real, text and blob as well")
                .loss("INT64_OVERFLOW_STORED_AS_REAL", "a literal above signed 64-bit is stored as inexact REAL");
        return integer.bits() == 64 && !integer.unsigned() ? result
                : result.loss("INTEGER_WIDTH_NOT_ENFORCED",
                "SQLite has no enforced smaller or unsigned integer domain");
    }

    /**
     * 根据原生列证据推导浮点数的逻辑类型及值域。
     */
    private static TypeMappingResult<NativeColumnType> floating(FloatType value) {
        TypeMappingResult<NativeColumnType> result = TypeMappingResult.exact(NativeColumnType.of("REAL"))
                .loss("DECLARED_TYPE_AFFINITY_NOT_STRICT",
                        "a REAL column converts integers to binary64 and still accepts text and blob");
        if (value.negativeZero()) {
            result = result.loss("NEGATIVE_ZERO_NOT_PRESERVED", "-0.0 is stored as 0.0");
        }
        if (value.nonFinite()) {
            result = result.loss("NAN_STORED_AS_NULL", "setDouble(NaN) is stored as NULL; Inf and -Inf remain real");
        }
        if (value.nonNegative()) {
            result = result.loss("SIGNED_DOMAIN_NOT_ENFORCED", "REAL accepts negative values");
        }
        return result;
    }

    /**
     * 根据原生列证据推导字符的逻辑类型及值域。
     */
    private static TypeMappingResult<NativeColumnType> character(CharacterType text, LogicalColumn column) {
        if (text.unit() != LengthUnit.CHARACTERS) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", "octet character length was not probed");
        }
        if (!text.repertoire().equals("UTF-8") && !text.repertoire().equals("UNICODE")) {
            return TypeMappingResult.unsupported("CHARSET_UNVERIFIED",
                    "only UTF-8 or Unicode sources were compared with PRAGMA encoding");
        }
        if (text.comparison() == Comparison.NAMED) {
            return TypeMappingResult.unsupported("COLLATION_UNAVAILABLE",
                    "only the default BINARY collation was probed");
        }
        String sql;
        if (text.kind() == StorageKind.LARGE) {
            sql = "TEXT";
        } else if (text.length() == null || text.length() < 1 || text.length() > MAX_OCTETS) {
            return TypeMappingResult.unsupported("LENGTH_EXCEEDS_TARGET", text.canonical());
        } else {
            sql = (text.kind() == StorageKind.FIXED ? "CHAR(" : "VARCHAR(") + text.length() + ")";
        }
        TypeMappingResult<NativeColumnType> result = TypeMappingResult.exact(NativeColumnType.of(sql))
                .loss("DECLARED_TYPE_AFFINITY_NOT_STRICT",
                        "text affinity still stores BLOB and converts numbers to text");
        if (text.kind() != StorageKind.LARGE) {
            result = result.loss("LENGTH_NOT_ENFORCED",
                    "CHAR/VARCHAR length does not reject a longer value and CHAR does not pad");
        }
        if (text.comparison() == Comparison.BINARY_PAD_SPACE) {
            result = result.loss("PAD_SPACE_EQUALITY_CHANGED", "SQLite does not ignore trailing spaces");
        }
        if (text.kind() == StorageKind.LARGE && text.maxOctets() != null && text.maxOctets() > MAX_OCTETS) {
            result = result.note("VALUE_SIZE_LIMITED_BY_TARGET",
                    "a value of 1000000001 bytes fails with SQLITE_TOOBIG; 1000000000 is accepted");
        }
        if (column.key() && column.nullable()) {
            result = result.loss("PRIMARY_KEY_DUPLICATE_NULLS", "a TEXT primary key stored two NULLs");
        }
        return result;
    }

    /**
     * 根据原生列证据推导二进制的逻辑类型及值域。
     */
    private static TypeMappingResult<NativeColumnType> binary(BinaryType binary) {
        if (binary.kind() != StorageKind.LARGE) {
            return TypeMappingResult.unsupported("TYPE_UNMAPPED", "sized BINARY was not probed");
        }
        TypeMappingResult<NativeColumnType> result = TypeMappingResult.exact(NativeColumnType.of("BLOB"))
                .loss("BLOB_AFFINITY_MIXED_STORAGE", "a BLOB column stores integer and text unchanged");
        return binary.maxOctets() > MAX_OCTETS
                ? result.note("VALUE_SIZE_LIMITED_BY_TARGET",
                "a value of 1000000001 bytes fails with SQLITE_TOOBIG; 1000000000 is accepted")
                : result;
    }

    /**
     * 返回当前元数据事实的产品、驱动及观测来源。
     */
    private static boolean provenance(MetadataProvenance provenance) {
        return provenance.nativeProduct(ProductTypeEnum.SQLITE3);
    }

    /** null 表示两个驱动的已探测空投影形态一致；返回结果即表示待报告的失败。 */
    private static TypeMappingResult<LogicalType> gate(ColumnDescription column, ColumnMetadataEvidence facts,
            String declared,
            int jdbc, String typeName, boolean signed, int precision, int scale,
            Integer catalogPrecision, Integer catalogScale, Long catalogLength) {
        if (column.getFieldType() != jdbc || column.getFieldTypeName() == null || !column.getFieldTypeName()
                .equalsIgnoreCase(typeName)
                || !"java.lang.Object".equals(column.getFiledTypeClassName()) || column.isSigned() != signed
                || column.getPrecisionSize() != precision || column.getScaleSize() != scale
                || column.getDisplaySize() != Integer.MAX_VALUE) {
            return inconsistent(declared);
        }
        TypeMappingResult<LogicalType> closed = optionalOnly(facts.charset(), "charset", "CHARSET_UNVERIFIED");
        if (closed != null) {
            return closed;
        }
        closed = optionalOnly(facts.collation(), "collation", "COLLATION_UNVERIFIED");
        if (closed != null) {
            return closed;
        }
        if (facts.sequence().state() == MetadataValue.State.VALUE || facts.sequence()
                .state() == MetadataValue.State.UNSUPPORTED) {
            return TypeMappingResult.unsupported("SEQUENCE_DEPENDENCY_UNMAPPED",
                    "no SQLite column sequence was probed");
        }
        if (facts.sequence().state() != MetadataValue.State.ABSENT) {
            return incomplete("sequence");
        }
        closed = exactNumber(facts.numericPrecision(), catalogPrecision, "numeric precision");
        if (closed != null) {
            return closed;
        }
        closed = exactNumber(facts.numericScale(), catalogScale, "numeric scale");
        if (closed != null) {
            return closed;
        }
        return exactNumber(facts.characterLength(), catalogLength, "character length");
    }

    /**
     * 读取允许缺失的元数据事实，并拒绝未知或矛盾状态。
     */
    private static TypeMappingResult<LogicalType> optionalOnly(MetadataValue<?> value, String property,
            String valueCode) {
        if (value.state() == MetadataValue.State.ABSENT) {
            return null;
        }
        if (value.state() == MetadataValue.State.VALUE) {
            return TypeMappingResult.unsupported(valueCode, property + " is not a probed SQLite column fact");
        }
        return incomplete(property);
    }

    /**
     * 核对元数据数值与预期精确数值一致。
     */
    private static TypeMappingResult<LogicalType> exactNumber(MetadataValue<?> value, Number expected,
            String property) {
        if (expected == null) {
            if (value.state() == MetadataValue.State.ABSENT) {
                return null;
            }
            return value.state() == MetadataValue.State.UNKNOWN ? incomplete(property) : inconsistent(property);
        }
        if (value.state() == MetadataValue.State.UNKNOWN) {
            return incomplete(property);
        }
        if (value.state() != MetadataValue.State.VALUE || !expected.equals(value.value())) {
            return inconsistent(property);
        }
        return null;
    }

    /** 拒绝前导零，避免将未经探测的拼写归并为已探测精度。 */
    private static Integer canonical(String digits) {
        try {
            int value = Integer.parseInt(digits);
            return digits.equals(Integer.toString(value)) ? value : null;
        } catch (NumberFormatException ignored) {
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
                "catalog " + type + " disagrees with the verified sqlite-jdbc ResultSetMetaData profile");
    }

    /**
     * 返回元数据证据不完整时的类型映射结果。
     */
    private static TypeMappingResult<LogicalType> incomplete(String property) {
        return TypeMappingResult.unsupported("INCOMPLETE_METADATA",
                property + " was not observed; UNKNOWN cannot be EXACT");
    }
}
