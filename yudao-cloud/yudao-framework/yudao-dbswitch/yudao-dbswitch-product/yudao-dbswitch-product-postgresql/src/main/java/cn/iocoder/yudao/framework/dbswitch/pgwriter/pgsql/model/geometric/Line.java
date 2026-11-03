package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.model.geometric;

/**
 * PostgreSQL line 类型的直线模型,以 Ax+By+C=0 的三个系数表示,用于 COPY 写入几何列。
 */
public class Line {

    private double a;

    private double b;

    private double c;

    /**
     * 初始化 {@code Line}。
     */
    public Line(double a, double b, double c) {
        this.a = a;
        this.b = b;
        this.c = c;
    }

    /**
     * 返回{@code a}。
     */
    public double getA() {
        return a;
    }

    /**
     * 返回{@code b}。
     */
    public double getB() {
        return b;
    }

    /**
     * 返回连接。
     */
    public double getC() {
        return c;
    }

}
