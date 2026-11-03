package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.model.range;

// https://github.com/npgsql/npgsql/blob/d4132d0d546594629bcef658bcb1418b4a8624cc/src/Npgsql/NpgsqlTypes/NpgsqlRange.cs

/**
 * 范围标志位常量类,定义空范围、上下界包含与无穷等位标志,供范围类型二进制编码使用。
 */
public class RangeFlags {

    public static final int None = 0;

    public static final int Empty = 1;

    public static final int LowerBoundInclusive = 2;

    public static final int UpperBoundInclusive = 4;

    public static final int LowerBoundInfinite = 8;

    public static final int UpperBoundInfinite = 16;

    public static final int Inclusive = LowerBoundInclusive | UpperBoundInclusive;

    public static final int Infinite = LowerBoundInfinite | UpperBoundInfinite;

    public static final int LowerInclusiveInfinite = LowerBoundInclusive | LowerBoundInfinite;

    public static final int UpperInclusiveInfinite = UpperBoundInclusive | UpperBoundInfinite;
}
