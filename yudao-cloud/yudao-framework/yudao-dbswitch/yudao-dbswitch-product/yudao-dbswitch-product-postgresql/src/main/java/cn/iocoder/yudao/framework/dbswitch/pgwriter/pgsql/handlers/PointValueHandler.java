package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers.utils.GeometricUtils;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.model.geometric.Point;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * PostgreSQL point 几何类型处理器,将点的 x、y 坐标写入 COPY 二进制流。
 */
public class PointValueHandler extends BaseValueHandler<Point> {

    /**
     * 将当前类型的值编码并写入二进制输出流。
     */
    @Override
    protected void internalHandle(DataOutputStream buffer, final Point value) throws IOException {
        buffer.writeInt(16);

        GeometricUtils.writePoint(buffer, value);
    }

    /**
     * 计算当前值按目标编码写入时所占的字节数。
     */
    @Override
    public int getLength(Point value) {
        return 16;
    }
}
