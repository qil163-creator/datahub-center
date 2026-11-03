package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.model.geometric;

/**
 * PostgreSQL point 类型的点模型,持有 x、y 坐标,用于 COPY 写入几何列。
 */
public class Point {

    private final double x;

    private final double y;

    /**
     * 初始化 {@code Point}。
     */
    public Point(double x, double y) {
        this.x = x;
        this.y = y;
    }

    /**
     * 返回{@code x}。
     */
    public double getX() {
        return x;
    }

    /**
     * 返回{@code y}。
     */
    public double getY() {
        return y;
    }

}
