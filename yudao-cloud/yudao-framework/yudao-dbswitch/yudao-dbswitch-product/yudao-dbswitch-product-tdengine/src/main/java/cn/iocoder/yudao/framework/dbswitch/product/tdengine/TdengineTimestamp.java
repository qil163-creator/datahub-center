package cn.iocoder.yudao.framework.dbswitch.product.tdengine;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 原生 TDengine 时间戳：按数据库精度表示的有符号纪元计数（3=ms、6=us、9=ns）。
 * 整数目标接收精确计数；不进行 double、毫秒或本地时间转换。
 * 窄化访问器失败而非截断。TDengine 写入器仅在精度相同时接受它。
 */
public final class TdengineTimestamp extends Number implements Comparable<TdengineTimestamp> {

    private static final long serialVersionUID = 1L;

    private final long epoch;

    private final int digits;

    /**
     * 校验构造参数并初始化 {@code TdengineTimestamp}。
     */
    public TdengineTimestamp(long epoch, int digits) {
        if (digits != 3 && digits != 6 && digits != 9) {
            throw new IllegalArgumentException("TDengine timestamp precision is invalid");
        }
        this.epoch = epoch;
        this.digits = digits;
    }

    /**
     * 返回当前数位。
     */
    public static int digits(String precision) {
        return switch (precision == null ? "" : precision) {
            case "ms" -> 3;
            case "us" -> 6;
            case "ns" -> 9;
            default -> throw new IllegalArgumentException("TDengine timestamp precision is unsupported");
        };
    }

    /**
     * 返回精度。
     */
    public static String precision(int digits) {
        return switch (digits) {
            case 3 -> "ms";
            case 6 -> "us";
            case 9 -> "ns";
            default -> throw new IllegalArgumentException("TDengine timestamp precision is invalid");
        };
    }

    /**
     * 返回纪元。
     */
    public long epoch() {
        return epoch;
    }

    /**
     * 返回数位。
     */
    public int digits() {
        return digits;
    }

    /**
     * 转换为时间点。
     */
    public Instant toInstant() {
        long unit = switch (digits) {
            case 3 -> 1_000L;
            case 6 -> 1_000_000L;
            default -> 1_000_000_000L;
        };
        return Instant.ofEpochSecond(Math.floorDiv(epoch, unit), Math.floorMod(epoch, unit) * (1_000_000_000L / unit));
    }

    /**
     * 返回纪元。
     */
    @Override
    public long longValue() {
        return epoch;
    }

    /**
     * 读取并转换当前值为整数。
     */
    @Override
    public int intValue() {
        return Math.toIntExact(epoch);
    }

    /**
     * 校验并读取单精度浮点配置值。
     */
    @Override
    public float floatValue() {
        if (epoch < -(1L << 24) || epoch > (1L << 24)) {
            throw new ArithmeticException("TDengine timestamp is not exact as float");
        }
        return epoch;
    }

    /**
     * 校验并读取双精度浮点配置值。
     */
    @Override
    public double doubleValue() {
        if (epoch < -(1L << 53) || epoch > (1L << 53)) {
            throw new ArithmeticException("TDengine timestamp is not exact as double");
        }
        return epoch;
    }

    /**
     * 校验并读取短整数配置值。
     */
    @Override
    public short shortValue() {
        if (epoch != (short) epoch) {
            throw new ArithmeticException("TDengine timestamp narrowing");
        }
        return (short) epoch;
    }

    /**
     * 校验并读取字节整数配置值。
     */
    @Override
    public byte byteValue() {
        if (epoch != (byte) epoch) {
            throw new ArithmeticException("TDengine timestamp narrowing");
        }
        return (byte) epoch;
    }

    /**
     * 将输入数值转换为 BigDecimal 精确小数。
     */
    public BigDecimal toBigDecimal() {
        return BigDecimal.valueOf(epoch);
    }

    /**
     * 比较目标。
     */
    @Override
    public int compareTo(TdengineTimestamp other) {
        if (other.digits != digits) {
            throw new IllegalArgumentException("TDengine timestamps with different precisions are not comparable");
        }
        return Long.compare(epoch, other.epoch);
    }

    /**
     * 比较当前对象与给定对象是否具有相同的值。
     */
    @Override
    public boolean equals(Object value) {
        return value instanceof TdengineTimestamp other && other.epoch == epoch && other.digits == digits;
    }

    /**
     * 计算与值相等语义一致的哈希码。
     */
    @Override
    public int hashCode() {
        return Long.hashCode(epoch) * 31 + digits;
    }

    /** 十进制纪元计数，同时也是 BigDecimal/BIGINT 转换的精确文本。 */
    @Override
    public String toString() {
        return Long.toString(epoch);
    }
}
