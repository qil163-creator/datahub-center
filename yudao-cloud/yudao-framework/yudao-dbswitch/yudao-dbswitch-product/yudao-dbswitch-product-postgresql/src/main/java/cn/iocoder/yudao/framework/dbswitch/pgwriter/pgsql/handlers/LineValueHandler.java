package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.model.geometric.Line;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * PostgreSQL line 几何类型处理器,将直线方程的 A、B、C 三个系数写入 COPY 二进制流。
 */
public class LineValueHandler extends BaseValueHandler<Line> {

    /**
     * 将当前类型的值编码并写入二进制输出流。
     */
    @Override
    protected void internalHandle(DataOutputStream buffer, final Line value) throws IOException {
        buffer.writeInt(24);

        buffer.writeDouble(value.getA());
        buffer.writeDouble(value.getB());
        buffer.writeDouble(value.getC());
    }

    /**
     * 计算当前值按目标编码写入时所占的字节数。
     */
    @Override
    public int getLength(Line value) {
        return 24;
    }
}
