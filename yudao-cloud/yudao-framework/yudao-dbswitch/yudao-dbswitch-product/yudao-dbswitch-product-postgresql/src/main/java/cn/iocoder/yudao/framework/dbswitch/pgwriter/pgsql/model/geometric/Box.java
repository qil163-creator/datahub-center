package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.model.geometric;

/**
 * PostgreSQL box 类型的矩形模型,由高、低两个角点构成,用于 COPY 写入几何列。
 */
public class Box {

    private final Point high;

    private final Point low;

    /**
     * 校验构造参数并初始化 {@code Box}。
     */
    public Box(Point high, Point low) {
        if (high == null) {
            throw new IllegalArgumentException("high");
        }
        if (low == null) {
            throw new IllegalArgumentException("low");
        }
        this.high = high;
        this.low = low;
    }

    /**
     * 返回上界。
     */
    public Point getHigh() {
        return high;
    }

    /**
     * 返回低。
     */
    public Point getLow() {
        return low;
    }

}
