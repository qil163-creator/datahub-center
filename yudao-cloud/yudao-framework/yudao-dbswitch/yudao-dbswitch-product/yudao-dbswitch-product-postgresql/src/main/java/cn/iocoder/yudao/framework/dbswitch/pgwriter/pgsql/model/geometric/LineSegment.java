package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.model.geometric;

/**
 * PostgreSQL lseg 类型的线段模型,由两个端点构成,用于 COPY 写入几何列。
 */
public class LineSegment {

    private final Point p1;

    private final Point p2;

    /**
     * 校验构造参数并初始化 {@code LineSegment}。
     */
    public LineSegment(Point p1, Point p2) {

        if (p1 == null) {
            throw new IllegalArgumentException("p1");
        }

        if (p2 == null) {
            throw new IllegalArgumentException("p2");
        }

        this.p1 = p1;
        this.p2 = p2;
    }

    /**
     * 返回参数{@code 1}。
     */
    public Point getP1() {
        return p1;
    }

    /**
     * 返回参数{@code 2}。
     */
    public Point getP2() {
        return p2;
    }

}
