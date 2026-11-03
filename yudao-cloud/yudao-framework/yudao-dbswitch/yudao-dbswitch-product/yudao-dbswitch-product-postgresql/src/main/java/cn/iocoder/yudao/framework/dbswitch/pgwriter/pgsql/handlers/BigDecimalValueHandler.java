package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers;

import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;

import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * BigDecimal 数值处理器,将数值按 PostgreSQL numeric 的万进制分组二进制格式写入 COPY 流。
 * 将 BigDecimal 转换为 Postgres Numeric 的算法主要参考
 * Intermine 的实现：
 * <p>
 * https://github.com/intermine/intermine/blob/master/intermine/objectstore/main/src/org/intermine/sql/writebatch/BatchWriterPostgresCopyImpl.java
 */
public class BigDecimalValueHandler<T extends Number> extends BaseValueHandler<T> {

    private static final int DECIMAL_DIGITS = 4;

    private static final BigInteger TEN_THOUSAND = new BigInteger("10000");

    /**
     * 将当前类型的值编码并写入二进制输出流。
     */
    @Override
    protected void internalHandle(final DataOutputStream buffer, final T value) throws IOException {
        final BigDecimal tmpValue = getNumericAsBigDecimal(value);

        // 小数位数：
        final int fractionDigits = tmpValue.scale();

        // 小数分组数量：
        final int fractionGroups = fractionDigits > 0 ? (fractionDigits + 3) / 4 : 0;

        final List<Integer> digits = digits(tmpValue);

        buffer.writeInt(8 + (2 * digits.size()));
        buffer.writeShort(digits.size());
        buffer.writeShort(digits.size() - fractionGroups - 1);
        buffer.writeShort(tmpValue.signum() == 1 ? 0x0000 : 0x4000);
        buffer.writeShort(Math.max(fractionDigits, 0));

        // 写入各个数字组：
        for (int pos = digits.size() - 1; pos >= 0; pos--) {
            final int valueToWrite = digits.get(pos);
            buffer.writeShort(valueToWrite);
        }
    }

    /**
     * 计算当前值按目标编码写入时所占的字节数。
     */
    @Override
    public int getLength(final T value) {
        final List<Integer> digits = digits(getNumericAsBigDecimal(value));
        return (8 + (2 * digits.size()));
    }

    /**
     * 按 JDBC 精确小数转换约定取得 BigDecimal 值。
     */
    private static BigDecimal getNumericAsBigDecimal(final Number source) {
        return JdbcValueConversions.toDecimal(source);
    }

    /**
     * 返回当前数位。
     */
    private List<Integer> digits(final BigDecimal value) {
        BigInteger unscaledValue = value.unscaledValue();

        if (value.signum() == -1) {
            unscaledValue = unscaledValue.negate();
        }

        final List<Integer> digits = new ArrayList<>();

        if (value.scale() > 0) {
            // 标度需要为 4 的倍数：
            int scaleRemainder = value.scale() % 4;

            // 调整第一个值的标度：
            if (scaleRemainder != 0) {
                final BigInteger[] result = unscaledValue.divideAndRemainder(BigInteger.TEN.pow(scaleRemainder));
                final int digit = result[1].intValue() * (int) Math.pow(10, DECIMAL_DIGITS - scaleRemainder);
                digits.add(digit);
                unscaledValue = result[0];
            }

            while (!unscaledValue.equals(BigInteger.ZERO)) {
                final BigInteger[] result = unscaledValue.divideAndRemainder(TEN_THOUSAND);
                digits.add(result[1].intValue());
                unscaledValue = result[0];
            }
        } else {
            BigInteger originalValue = unscaledValue.multiply(BigInteger.TEN.pow(Math.abs(value.scale())));
            while (!originalValue.equals(BigInteger.ZERO)) {
                final BigInteger[] result = originalValue.divideAndRemainder(TEN_THOUSAND);
                digits.add(result[1].intValue());
                originalValue = result[0];
            }
        }

        return digits;
    }
}
