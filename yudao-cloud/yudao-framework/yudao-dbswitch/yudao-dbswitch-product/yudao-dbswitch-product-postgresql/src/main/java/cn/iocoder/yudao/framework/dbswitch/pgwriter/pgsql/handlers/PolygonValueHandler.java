package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers.utils.GeometricUtils;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.model.geometric.Point;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.model.geometric.Polygon;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * PostgreSQL polygon 几何类型处理器,将多边形的顶点数量及各顶点坐标写入 COPY 二进制流。
 */
public class PolygonValueHandler extends BaseValueHandler<Polygon> {

    /**
     * 将当前类型的值编码并写入二进制输出流。
     */
    @Override
    protected void internalHandle(DataOutputStream buffer, final Polygon value) throws IOException {
        // 待写入的总字节数：
        int totalBytesToWrite = 4 + 16 * value.size();

        // 后续字节数：
        buffer.writeInt(totalBytesToWrite);

        // 写入点：
        buffer.writeInt(value.getPoints().size());

        // 写入列表中的每个点：
        for (Point p : value.getPoints()) {
            GeometricUtils.writePoint(buffer, p);
        }

    }

    /**
     * 计算当前值按目标编码写入时所占的字节数。
     */
    @Override
    public int getLength(Polygon value) {
        throw new UnsupportedOperationException();
    }
}
