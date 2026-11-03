package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import java.math.BigInteger;
import java.util.Objects;

/**
 * 与产品无关的单列值域。产品之间存在差异的每个组成部分都作为显式
 * 事实表示；映射器无法确定某部分时返回 UNSUPPORTED，而不是选择默认值。
 * {@link #canonical()} 提供稳定的表示，供诊断和黄金样例测试使用（版本 {@value #VERSION}）。
 */
public sealed interface LogicalType {

    String VERSION = "LOGICAL_TYPE_V1";

    /**
     * 将当前输入整理为当前协议使用的规范形式。
     */
    String canonical();

    enum StorageKind {FIXED, VARYING, LARGE}

    enum LengthUnit {CHARACTERS, OCTETS}

    /** 字符数据的相等及排序规则。NAMED 表示产品排序规则，其语义必须按名称匹配。 */
    enum Comparison {BINARY_NO_PAD, BINARY_PAD_SPACE, DETERMINISTIC_DEFAULT, NAMED}

    enum TimeRange {TIME_OF_DAY, TIME_OF_DAY_INCLUDING_24_00, SIGNED_DURATION_838_HOURS}

    enum TimeZoneSemantics {LOCAL, INSTANT, OFFSET}

    /** TEXT 保留输入文本（空白、键顺序及重复键）；NORMALIZED 存储解析后的文档。 */
    enum JsonRepresentation {TEXT, NORMALIZED}

    enum JsonNumbers {ARBITRARY_DECIMAL, BINARY64}

    record IntegerType(int bits, boolean unsigned) implements LogicalType {

        /**
         * 校验构造参数并初始化 {@code IntegerType}。
         */
        public IntegerType {
            if (bits != 8 && bits != 16 && bits != 24 && bits != 32 && bits != 64) {
                throw new IllegalArgumentException("Unsupported integer width");
            }
        }

        /**
         * 返回当前最小值。
         */
        public BigInteger minimum() {
            return unsigned ? BigInteger.ZERO : BigInteger.ONE.shiftLeft(bits - 1).negate();
        }

        /**
         * 返回当前最大值。
         */
        public BigInteger maximum() {
            return (unsigned ? BigInteger.ONE.shiftLeft(bits) : BigInteger.ONE.shiftLeft(bits - 1)).subtract(
                    BigInteger.ONE);
        }

        /**
         * 将当前输入整理为当前协议使用的规范形式。
         */
        @Override
        public String canonical() {
            return "INTEGER(" + bits + (unsigned ? ",UNSIGNED)" : ",SIGNED)");
        }
    }

    /** 与 IntegerType 分开，避免现有宽度分支静默将此值域缩窄为 BIGINT。 */
    record Int128Type() implements LogicalType {

        /**
         * 返回当前最小值。
         */
        public BigInteger minimum() {
            return BigInteger.ONE.shiftLeft(127).negate();
        }

        /**
         * 返回当前最大值。
         */
        public BigInteger maximum() {
            return BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE);
        }

        /**
         * 将当前输入整理为当前协议使用的规范形式。
         */
        @Override
        public String canonical() {
            return "INTEGER(128,SIGNED)";
        }
    }

    /** 精确十进制数。precision 和 scale 为 null 表示无限制 numeric；{@code nan} 表示源值域允许 NaN。 */
    record DecimalType(Integer precision, Integer scale, boolean nonNegative, boolean nan) implements LogicalType {

        /**
         * 校验构造参数并初始化 {@code DecimalType}。
         */
        public DecimalType {
            if ((precision == null) != (scale == null) || precision != null && precision < 1) {
                throw new IllegalArgumentException("Invalid decimal precision/scale");
            }
        }

        /**
         * 校验当前输入未超过约定的大小或数量上限。
         */
        public boolean bounded() {
            return precision != null;
        }

        /**
         * 将当前输入整理为当前协议使用的规范形式。
         */
        @Override
        public String canonical() {
            return "DECIMAL(" + (precision == null ? "UNBOUNDED" : precision + "," + scale)
                    + (nonNegative ? ",NON_NEGATIVE" : "") + (nan ? ",NAN" : "") + ")";
        }
    }

    record FloatType(int bits, boolean nonNegative, boolean negativeZero, boolean nonFinite) implements LogicalType {

        /**
         * 校验构造参数并初始化 {@code FloatType}。
         */
        public FloatType {
            if (bits != 32 && bits != 64) {
                throw new IllegalArgumentException("Unsupported float width");
            }
        }

        /**
         * 将当前输入整理为当前协议使用的规范形式。
         */
        @Override
        public String canonical() {
            return "FLOAT" + bits + "(" + (nonNegative ? "NON_NEGATIVE" : "SIGNED") + (negativeZero ? ",NEGATIVE_ZERO" : "")
                    + (nonFinite ? ",NON_FINITE" : "") + ")";
        }
    }

    record BooleanType() implements LogicalType {

        /**
         * 将当前输入整理为当前协议使用的规范形式。
         */
        @Override
        public String canonical() {
            return "BOOLEAN";
        }
    }

    /**
     * 字符数据。FIXED/VARYING 带有声明长度；LARGE 带有最大存储字节数。
     * 完整支持 Unicode 的存储中，{@code repertoire} 为 UNICODE；否则为产品字符集名称。
     */
    record CharacterType(StorageKind kind, Long length, LengthUnit unit, Long maxOctets, String repertoire,
                         Comparison comparison, String collation, boolean nulAllowed) implements LogicalType {

        /**
         * 校验构造参数并初始化 {@code CharacterType}。
         */
        public CharacterType {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(unit);
            Objects.requireNonNull(comparison);
            if (repertoire == null || repertoire.isBlank()) {
                throw new IllegalArgumentException("Character repertoire is required");
            }
            if (kind == StorageKind.LARGE ? length != null || maxOctets == null || maxOctets < 1 : length == null || length < 1) {
                throw new IllegalArgumentException("Invalid character length");
            }
            if (comparison == Comparison.NAMED && (collation == null || collation.isBlank())) {
                throw new IllegalArgumentException("Named collation is required");
            }
        }

        /**
         * 将当前输入整理为当前协议使用的规范形式。
         */
        @Override
        public String canonical() {
            return "CHARACTER(" + kind + "," + (length == null ? "MAX_OCTETS=" + maxOctets : length + "," + unit) + "," + repertoire
                    + "," + comparison + (collation == null ? "" : ":" + collation) + (nulAllowed ? ",NUL" : "") + ")";
        }
    }

    record BinaryType(StorageKind kind, Long length, Long maxOctets) implements LogicalType {

        /**
         * 校验构造参数并初始化 {@code BinaryType}。
         */
        public BinaryType {
            Objects.requireNonNull(kind);
            if (kind == StorageKind.LARGE ? length != null || maxOctets == null || maxOctets < 1 : length == null || length < 1) {
                throw new IllegalArgumentException("Invalid binary length");
            }
        }

        /**
         * 将当前输入整理为当前协议使用的规范形式。
         */
        @Override
        public String canonical() {
            return "BINARY(" + kind + "," + (length == null ? "MAX_OCTETS=" + maxOctets : length) + ")";
        }
    }

    /** 日历日期；年份范围及无穷值属于源值域的一部分。 */
    record DateType(int minYear, int maxYear, boolean infinity) implements LogicalType {

        /**
         * 校验构造参数并初始化 {@code DateType}。
         */
        public DateType {
            if (minYear > maxYear) {
                throw new IllegalArgumentException("Invalid date range");
            }
        }

        /**
         * 将当前输入整理为当前协议使用的规范形式。
         */
        @Override
        public String canonical() {
            return "DATE(" + minYear + ".." + maxYear + (infinity ? ",INFINITY" : "") + ")";
        }
    }

    record TimeType(int fractionalDigits, boolean withTimeZone, TimeRange range) implements LogicalType {

        /**
         * 校验构造参数并初始化 {@code TimeType}。
         */
        public TimeType {
            Objects.requireNonNull(range);
            if (fractionalDigits < 0 || fractionalDigits > 9) {
                throw new IllegalArgumentException("Invalid time precision");
            }
        }

        /**
         * 将当前输入整理为当前协议使用的规范形式。
         */
        @Override
        public String canonical() {
            return "TIME(" + fractionalDigits + "," + (withTimeZone ? "WITH_TIME_ZONE," : "") + range + ")";
        }
    }

    record TimestampType(int fractionalDigits, TimeZoneSemantics zone, int minYear, int maxYear,
                         boolean infinity) implements LogicalType {

        /**
         * 校验构造参数并初始化 {@code TimestampType}。
         */
        public TimestampType {
            Objects.requireNonNull(zone);
            if (fractionalDigits < 0 || fractionalDigits > 9 || minYear > maxYear) {
                throw new IllegalArgumentException("Invalid timestamp");
            }
        }

        /**
         * 将当前输入整理为当前协议使用的规范形式。
         */
        @Override
        public String canonical() {
            return "TIMESTAMP(" + fractionalDigits + "," + zone + "," + minYear + ".." + maxYear + (infinity ? ",INFINITY" : "") + ")";
        }
    }

    record UuidType() implements LogicalType {

        /**
         * 将当前输入整理为当前协议使用的规范形式。
         */
        @Override
        public String canonical() {
            return "UUID";
        }
    }

    record JsonType(JsonRepresentation representation, JsonNumbers numbers) implements LogicalType {

        /**
         * 校验构造参数并初始化 {@code JsonType}。
         */
        public JsonType {
            Objects.requireNonNull(representation);
            Objects.requireNonNull(numbers);
        }

        /**
         * 将当前输入整理为当前协议使用的规范形式。
         */
        @Override
        public String canonical() {
            return "JSON(" + representation + "," + numbers + ")";
        }
    }

    record IntervalType(String fields, Integer fractionalDigits) implements LogicalType {

        /**
         * 将当前输入整理为当前协议使用的规范形式。
         */
        @Override
        public String canonical() {
            return "INTERVAL(" + (fields == null ? "ALL" : fields) + "," + fractionalDigits + ")";
        }
    }

    record XmlType() implements LogicalType {

        /**
         * 将当前输入整理为当前协议使用的规范形式。
         */
        @Override
        public String canonical() {
            return "XML";
        }
    }

    record ArrayType(LogicalType element, int dimensions) implements LogicalType {

        /**
         * 校验构造参数并初始化 {@code ArrayType}。
         */
        public ArrayType {
            Objects.requireNonNull(element);
            if (dimensions < 1 || element instanceof ArrayType) {
                throw new IllegalArgumentException("Invalid array type");
            }
        }

        /**
         * 将当前输入整理为当前协议使用的规范形式。
         */
        @Override
        public String canonical() {
            return "ARRAY(" + element.canonical() + "," + dimensions + ")";
        }
    }
}
