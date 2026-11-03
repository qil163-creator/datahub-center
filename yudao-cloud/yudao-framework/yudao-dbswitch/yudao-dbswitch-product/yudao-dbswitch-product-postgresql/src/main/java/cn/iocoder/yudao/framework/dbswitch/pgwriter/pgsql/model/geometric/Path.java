package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.model.geometric;

import java.util.List;

/**
 * PostgreSQL path 类型的路径模型,由点列表与开闭标记构成,用于 COPY 写入几何列。
 */
public class Path {

    private final boolean isClosed;

    private final List<Point> points;

    /**
     * 校验构造参数并初始化 {@code Path}。
     */
    public Path(boolean closed, List<Point> points) {

        if (points == null) {
            throw new IllegalArgumentException("points");
        }

        this.isClosed = closed;
        this.points = points;
    }

    /**
     * 返回是否关闭。
     */
    public boolean isClosed() {
        return isClosed;
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
