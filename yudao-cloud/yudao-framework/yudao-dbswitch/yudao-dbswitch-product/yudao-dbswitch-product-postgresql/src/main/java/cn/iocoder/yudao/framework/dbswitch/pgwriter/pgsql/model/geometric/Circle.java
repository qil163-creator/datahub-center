package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.model.geometric;

/**
 * PostgreSQL circle 类型的圆模型,由圆心与半径构成,用于 COPY 写入几何列。
 */
public class Circle {

    private final Point center;

    private final double radius;

    /**
     * 校验构造参数并初始化 {@code Circle}。
     */
    public Circle(Point center, double radius) {
        if (center == null) {
            throw new IllegalArgumentException("center");
        }
        this.center = center;
        this.radius = radius;
    }

    /**
     * 返回中心。
     */
    public Point getCenter() {
        return center;
    }

    /**
     * 返回半径。
     */
    public double getRadius() {
        return radius;
    }

}
