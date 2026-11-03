package cn.iocoder.yudao.framework.dbswitch.pgwriter.util;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * BigDecimal 转换工具类,将 Integer、Long、Float、Double 等数值转换为 BigDecimal,供 numeric 类型写入使用。
 */
public final class BigDecimalUtils {

    /**
     * 初始化 {@code BigDecimalUtils}。
     */
    private BigDecimalUtils() {
    }

    /**
     * 将输入数值转换为 BigDecimal 精确小数。
     */
    public static BigDecimal toBigDecimal(Integer intValue) {
        return new BigDecimal(intValue.toString());
    }

    /**
     * 将输入数值转换为 BigDecimal 精确小数。
     */
    public static BigDecimal toBigDecimal(Integer intValue, MathContext mathContext) {
        return new BigDecimal(intValue.toString(), mathContext);
    }

    /**
     * 将输入数值转换为 BigDecimal 精确小数。
     */
    public static BigDecimal toBigDecimal(Long longValue) {
        return new BigDecimal(longValue.toString());
    }

    /**
     * 将输入数值转换为 BigDecimal 精确小数。
     */
    public static BigDecimal toBigDecimal(Long longValue, MathContext mathContext) {
        return new BigDecimal(longValue.toString(), mathContext);
    }

    /**
     * 将输入数值转换为 BigDecimal 精确小数。
     */
    public static BigDecimal toBigDecimal(Float floatValue) {
        return new BigDecimal(floatValue.toString());
    }

    /**
     * 将输入数值转换为 BigDecimal 精确小数。
     */
    public static BigDecimal toBigDecimal(Float floatValue, MathContext mathContext) {
        return new BigDecimal(floatValue.toString(), mathContext);
    }

    /**
     * 将输入数值转换为 BigDecimal 精确小数。
     */
    public static BigDecimal toBigDecimal(Double doubleValue) {
        return new BigDecimal(doubleValue.toString());
    }

    /**
     * 将输入数值转换为 BigDecimal 精确小数。
     */
    public static BigDecimal toBigDecimal(Double doubleValue, MathContext mathContext) {
        return new BigDecimal(doubleValue.toString(), mathContext);
    }

}
