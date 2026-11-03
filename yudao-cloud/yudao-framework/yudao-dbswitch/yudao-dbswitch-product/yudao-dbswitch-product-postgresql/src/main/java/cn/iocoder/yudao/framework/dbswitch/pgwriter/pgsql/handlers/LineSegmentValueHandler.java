package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers.utils.GeometricUtils;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.model.geometric.LineSegment;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * PostgreSQL lseg 几何类型处理器,将线段的两个端点坐标写入 COPY 二进制流。
 */
public class LineSegmentValueHandler extends BaseValueHandler<LineSegment> {

    /**
     * 将当前类型的值编码并写入二进制输出流。
     */
    @Override
    protected void internalHandle(DataOutputStream buffer, final LineSegment value)
            throws IOException {
        buffer.writeInt(32);

        GeometricUtils.writePoint(buffer, value.getP1());
        GeometricUtils.writePoint(buffer, value.getP2());
    }

    /**
     * 计算当前值按目标编码写入时所占的字节数。
     */
    @Override
    public int getLength(LineSegment value) {
        return 32;
    }
}
