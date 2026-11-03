package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.model.geometric;

import java.util.List;

/**
 * PostgreSQL polygon 类型的多边形模型,由顶点列表构成,用于 COPY 写入几何列。
 */
public class Polygon {

    private final List<Point> points;

    /**
     * 校验构造参数并初始化 {@code Polygon}。
     */
    public Polygon(List<Point> points) {

        if (points == null) {
            throw new IllegalArgumentException("points");
        }

        this.points = points;
    }

    /**
     * 返回点集合。
     */
    public List<Point> getPoints() {
        return points;
    }

    /**
     * 返回大小。
     */
    public int size() {
        return points.size();
    }
}
