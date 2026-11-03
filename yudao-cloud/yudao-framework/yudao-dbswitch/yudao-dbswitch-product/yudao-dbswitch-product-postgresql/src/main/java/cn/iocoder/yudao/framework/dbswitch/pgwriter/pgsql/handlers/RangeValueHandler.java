package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.model.range.Range;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * PostgreSQL 范围类型处理器,写入范围标志位与上下界值,界值由传入的元素处理器序列化。
 */
public class RangeValueHandler<TElementType> extends BaseValueHandler<Range<TElementType>> {

    private final IValueHandler<TElementType> valueHandler;

    /**
     * 初始化 {@code RangeValueHandler}。
     */
    public RangeValueHandler(IValueHandler<TElementType> valueHandler) {
        this.valueHandler = valueHandler;
    }

    /**
     * 将当前类型的值编码并写入二进制输出流。
     */
    @SuppressWarnings("NullAway") // 仅当边界值非 null 时，无穷边界检查才会通过
    @Override
    protected void internalHandle(DataOutputStream buffer, Range<TElementType> value)
            throws IOException {
        buffer.writeInt(getLength(value));
        buffer.writeByte(value.getFlags());

        if (value.isEmpty()) {
            return;
        }

        if (!value.isLowerBoundInfinite()) {
            valueHandler.handle(buffer, value.getLowerBound());
        }

        if (!value.isUpperBoundInfinite()) {
            valueHandler.handle(buffer, value.getUpperBound());
        }
    }

    /**
     * 计算当前值按目标编码写入时所占的字节数。
     */
    @SuppressWarnings("NullAway") // 仅当边界值非 null 时，无穷边界检查才会通过
    @Override
    public int getLength(Range<TElementType> value) {
        int totalLen = 1;

        if (!value.isEmpty()) {
            if (!value.isLowerBoundInfinite()) {
                totalLen += 4 + valueHandler.getLength(value.getLowerBound());
            }

            if (!value.isUpperBoundInfinite()) {
                totalLen += 4 + valueHandler.getLength(value.getUpperBound());
            }
        }

        return totalLen;
    }
}

