package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.model.range;

import java.util.Objects;

// https://github.com/npgsql/npgsql/blob/d4132d0d546594629bcef658bcb1418b4a8624cc/src/Npgsql/NpgsqlTypes/NpgsqlRange.cs

/**
 * PostgreSQL 范围类型模型,持有上下界值及包含/无穷等标志位,用于 COPY 写入各种 range 列类型。
 */
public class Range<TElementType> {

    private int flags;

    private TElementType lowerBound;

    private TElementType upperBound;

    /**
     * 使用给定参数初始化 {@code Range}。
     */
    public Range(TElementType lowerBound, TElementType upperBound) {
        this(lowerBound, true, false, upperBound, true, false);
    }

    /**
     * 使用给定参数初始化 {@code Range}。
     */
    public Range(TElementType lowerBound, boolean lowerBoundIsInclusive, TElementType upperBound,
            boolean upperBoundIsInclusive) {
        this(lowerBound, lowerBoundIsInclusive, false, upperBound, upperBoundIsInclusive, false);
    }

    /**
     * 使用给定参数初始化 {@code Range}。
     */
    public Range(TElementType lowerBound, boolean lowerBoundIsInclusive, boolean lowerBoundInfinite,
            TElementType upperBound, boolean upperBoundIsInclusive, boolean upperBoundInfinite) {
        this(lowerBound, upperBound,
                evaluateBoundaryFlags(lowerBoundIsInclusive, upperBoundIsInclusive, lowerBoundInfinite,
                        upperBoundInfinite));
    }


    /**
     * 初始化 {@code Range}。
     */
    private Range(TElementType lowerBound, TElementType upperBound, int flags) {
        this.lowerBound = (flags & RangeFlags.LowerBoundInfinite) != 0 ? null : lowerBound;
        this.upperBound = (flags & RangeFlags.UpperBoundInfinite) != 0 ? null : upperBound;
        this.flags = flags;

        if (lowerBound == null) {
            this.flags |= RangeFlags.LowerBoundInfinite;
        }

        if (upperBound == null) {
            this.flags |= RangeFlags.UpperBoundInfinite;
        }

        if (isEmptyRange(lowerBound, upperBound, flags)) {
            this.lowerBound = null;
            this.upperBound = null;
            this.flags = RangeFlags.Empty;
        }
    }

    /**
     * 判断空范围。
     */
    private boolean isEmptyRange(TElementType lowerBound, TElementType upperBound, int flags) {
        // ---------------------------------------------------------------------------------
        // 仅检查明确属于错误的情况：
        //   1. 边界不能为默认值，包括 null。
        //   2. 边界必须有限，不能为无穷。
        //   3. 边界必须包含端点。
        //   4. 两个边界必须被判定为相等。
        //
        // 参考：
        //  - https://github.com/npgsql/npgsql/pull/1939
        //  - https://github.com/npgsql/npgsql/issues/1943
        // ---------------------------------------------------------------------------------

        if ((flags & RangeFlags.Empty) == RangeFlags.Empty) {
            return true;
        }

        if ((flags & RangeFlags.Infinite) == RangeFlags.Infinite) {
            return false;
        }

        if ((flags & RangeFlags.Inclusive) == RangeFlags.Inclusive) {
            return false;
        }

        return Objects.equals(lowerBound, upperBound);
    }


    /**
     * 根据范围上下界及包含性计算 PostgreSQL 范围标记。
     */
    private static int evaluateBoundaryFlags(boolean lowerBoundIsInclusive,
            boolean upperBoundIsInclusive, boolean lowerBoundInfinite, boolean upperBoundInfinite) {

        int result = RangeFlags.None;

        // 这是唯一计算标志的位置。
        if (lowerBoundIsInclusive) {
            result |= RangeFlags.LowerBoundInclusive;
        }
        if (upperBoundIsInclusive) {
            result |= RangeFlags.UpperBoundInclusive;
        }
        if (lowerBoundInfinite) {
            result |= RangeFlags.LowerBoundInfinite;
        }
        if (upperBoundInfinite) {
            result |= RangeFlags.UpperBoundInfinite;
        }

        // PostgreSQL 会自动转换包含端点的无穷边界。
        // 参考：https://www.postgresql.org/docs/current/static/rangetypes.html#RANGETYPES-INFINITE
        if ((result & RangeFlags.LowerInclusiveInfinite) == RangeFlags.LowerInclusiveInfinite) {
            result &= ~RangeFlags.LowerBoundInclusive;
        }

        if ((result & RangeFlags.UpperInclusiveInfinite) == RangeFlags.UpperInclusiveInfinite) {
            result &= ~RangeFlags.UpperBoundInclusive;
        }

        return result;
    }

    /**
     * 返回标记集合。
     */
    public int getFlags() {
        return flags;
    }

    /**
     * 判断空。
     */
    public boolean isEmpty() {
        return (flags & RangeFlags.Empty) != 0;
    }

    /**
     * 判断当前范围的下界是否为无穷边界。
     */
    public boolean isLowerBoundInfinite() {
        return (flags & RangeFlags.LowerBoundInfinite) != 0;
    }

    /**
     * 判断当前范围的上界是否为无穷边界。
     */
    public boolean isUpperBoundInfinite() {
        return (flags & RangeFlags.UpperBoundInfinite) != 0;
    }

    /**
     * 返回读取下界。
     */
    public TElementType getLowerBound() {
        return lowerBound;
    }

    /**
     * 设置读取下界。
     */
    public void setLowerBound(TElementType lowerBound) {
        this.lowerBound = lowerBound;
    }

    /**
     * 返回读取上界。
     */
    public TElementType getUpperBound() {
        return upperBound;
    }

    /**
     * 设置读取上界。
     */
    public void setUpperBound(TElementType upperBound) {
        this.upperBound = upperBound;
    }
}
