package cn.iocoder.yudao.framework.dbswitch.dbcommon.domain;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

/** 有序键的相等验证依据。跨产品重放要求目标值域无损且映射为单射。 */
public record CursorKeyContract(ProductTypeEnum product, String nativeFingerprint, List<Domain> keys) {

    /**
     * 校验构造参数并初始化 {@code CursorKeyContract}。
     */
    public CursorKeyContract {
        if (product == null || product == ProductTypeEnum.UNKNOWN || nativeFingerprint == null
                || !nativeFingerprint.matches("[0-9a-f]{64}") || keys == null || keys.isEmpty() || keys.size() > 32) {
            throw new IllegalArgumentException("Invalid cursor key contract");
        }
        keys = List.copyOf(keys);
    }

    /**
     * 检查当前类型和对象事实满足数据重放的兼容条件。
     */
    public void requireReplayCompatible(CursorKeyContract target) {
        if (target == null || keys.size() != target.keys.size()) {
            throw incompatible();
        }
        if (product == target.product) {
            if (!nativeFingerprint.equals(target.nativeFingerprint)) {
                throw incompatible();
            }
            return;
        }
        for (int i = 0; i < keys.size(); i++) {
            Numeric sourceDomain = keys.get(i).numeric(), targetDomain = target.keys.get(i).numeric();
            if (sourceDomain == null || targetDomain == null || targetDomain.scale() < sourceDomain.scale()
                    || targetDomain.minimum().compareTo(sourceDomain.minimum()) > 0
                    || targetDomain.maximum().compareTo(sourceDomain.maximum()) < 0) {
                throw incompatible();
            }
        }
    }

    /**
     * 根据当前输入构建并返回 {@code IllegalArgumentException}。
     */
    private static IllegalArgumentException incompatible() {
        return new IllegalArgumentException(
                "Source and target cursor keys do not have a verified lossless equality mapping");
    }

    /** 非数值键当前仅能在完全相同的原生契约下重放。 */
    public record Domain(Numeric numeric) {

        /**
         * 根据当前输入构建并返回 {@code Domain}。
         */
        public static Domain nativeOnly() {
            return new Domain(null);
        }

        /**
         * 按当前值域约定解析或转换整数。
         */
        public static Domain integer(int bits, boolean unsigned) {
            if (bits < 1 || bits > 64) {
                throw new IllegalArgumentException("Invalid integer key width");
            }
            BigInteger magnitude = BigInteger.ONE.shiftLeft(unsigned ? bits : bits - 1);
            return new Domain(new Numeric(unsigned ? BigDecimal.ZERO : new BigDecimal(magnitude.negate()),
                    new BigDecimal(magnitude.subtract(BigInteger.ONE)), 0));
        }

        /**
         * 按当前值域约定解析或转换精确小数。
         */
        public static Domain decimal(int precision, int scale, boolean unsigned) {
            if (precision < 1 || precision > 1000 || scale < 0 || scale > precision) {
                return nativeOnly();
            }
            BigDecimal maximum = new BigDecimal(BigInteger.TEN.pow(precision).subtract(BigInteger.ONE), scale);
            return new Domain(new Numeric(unsigned ? BigDecimal.ZERO : maximum.negate(), maximum, scale));
        }
    }

    public record Numeric(BigDecimal minimum, BigDecimal maximum, int scale) {

        /**
         * 校验构造参数并初始化 {@code Numeric}。
         */
        public Numeric {
            if (minimum == null || maximum == null || minimum.compareTo(maximum) > 0 || scale < 0 || scale > 1000) {
                throw new IllegalArgumentException("Invalid exact numeric key domain");
            }
        }
    }
}
