package cn.iocoder.yudao.framework.dbswitch.core.model;

import java.math.BigInteger;
import java.util.Objects;
import java.util.regex.Pattern;

/** SQL Server 标识生成定义与非 MVCC 观测，包含独立的尚未调用状态。 */
public record SqlServerIdentityMetadata(String dataType, BigInteger seed, BigInteger increment,
                                        BigInteger currentValue, boolean called, boolean notForReplication) {

    private static final BigInteger MAX_DECIMAL = BigInteger.TEN.pow(38).subtract(BigInteger.ONE);

    /**
     * 校验构造参数并初始化 {@code SqlServerIdentityMetadata}。
     */
    public SqlServerIdentityMetadata {
        Objects.requireNonNull(dataType);
        Objects.requireNonNull(seed);
        Objects.requireNonNull(increment);
        Objects.requireNonNull(currentValue);
        var bounds = bounds(dataType);
        if (increment.signum() == 0 || increment.abs().compareTo(MAX_DECIMAL) > 0
                || seed.compareTo(bounds[0]) < 0 || seed.compareTo(bounds[1]) > 0
                || currentValue.abs().compareTo(MAX_DECIMAL) > 0) {
            throw new IllegalArgumentException("Invalid native SQL Server identity observation");
        }
    }

    /**
     * 返回当前最小值。
     */
    public BigInteger minimum() {
        return bounds(dataType)[0];
    }

    /**
     * 返回当前最大值。
     */
    public BigInteger maximum() {
        return bounds(dataType)[1];
    }

    /**
     * 根据当前序列事实推导下一次可观测值。
     */
    public BigInteger nextObservedValue() {
        return called ? currentValue.add(increment) : currentValue;
    }

    /**
     * 返回定义。
     */
    public String definition() {
        return "IDENTITY(" + seed + "," + increment + ")" + (notForReplication ? " NOT FOR REPLICATION" : "");
    }

    /**
     * 比较两份表结构定义是否一致。
     */
    public boolean sameDefinition(SqlServerIdentityMetadata other) {
        return other != null && dataType.equals(other.dataType) && seed.equals(other.seed)
                && increment.equals(other.increment) && notForReplication == other.notForReplication;
    }

    /** 按观测到的最远推进位置继续，同时考虑显式写入的乱序行值。 */
    public BigInteger alignedNext(SqlServerIdentityMetadata target, SqlServerIdentityMetadata beforeReplacement,
            BigInteger targetExtreme) {
        if (!sameDefinition(target) || beforeReplacement != null && !sameDefinition(beforeReplacement)) {
            throw new IllegalArgumentException("Source and target identity definitions differ");
        }
        BigInteger next = forward(nextObservedValue(), target.nextObservedValue());
        if (beforeReplacement != null) {
            next = forward(next, beforeReplacement.nextObservedValue());
        }
        if (targetExtreme != null) {
            next = forward(next, targetExtreme.add(increment));
        }
        if (next.compareTo(minimum()) < 0 || next.compareTo(maximum()) > 0) {
            throw new IllegalArgumentException("Identity exhausted after explicit value preservation");
        }
        return next;
    }

    /**
     * 根据序列增量的符号判断是否向前递增。
     */
    private BigInteger forward(BigInteger left, BigInteger right) {
        return increment.signum() > 0 ? left.max(right) : left.min(right);
    }

    /**
     * 返回当前边界集合。
     */
    private static BigInteger[] bounds(String type) {
        return switch (type) {
            case "tinyint" -> new BigInteger[]{BigInteger.ZERO, BigInteger.valueOf(255)};
            case "smallint" ->
                    new BigInteger[]{BigInteger.valueOf(Short.MIN_VALUE), BigInteger.valueOf(Short.MAX_VALUE)};
            case "int" ->
                    new BigInteger[]{BigInteger.valueOf(Integer.MIN_VALUE), BigInteger.valueOf(Integer.MAX_VALUE)};
            case "bigint" -> new BigInteger[]{BigInteger.valueOf(Long.MIN_VALUE), BigInteger.valueOf(Long.MAX_VALUE)};
            default -> {
                var decimal = Pattern.compile("(?:decimal|numeric)\\(([1-9][0-9]?),0\\)").matcher(type);
                if (!decimal.matches() || Integer.parseInt(decimal.group(1)) > 38) {
                    throw new IllegalArgumentException("Unsupported SQL Server identity type");
                }
                var maximum = BigInteger.TEN.pow(Integer.parseInt(decimal.group(1))).subtract(BigInteger.ONE);
                yield new BigInteger[]{maximum.negate(), maximum};
            }
        };
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "SqlServerIdentityMetadata[type=" + dataType + ", called=" + called + "]";
    }
}
