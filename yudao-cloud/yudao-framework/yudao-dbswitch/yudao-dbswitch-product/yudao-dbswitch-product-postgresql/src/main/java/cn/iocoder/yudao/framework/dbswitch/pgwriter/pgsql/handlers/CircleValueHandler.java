package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers.utils.GeometricUtils;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.model.geometric.Circle;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * PostgreSQL circle 几何类型处理器,将圆心坐标与半径写入 COPY 二进制流。
 */
public class CircleValueHandler extends BaseValueHandler<Circle> {

    /**
     * 将当前类型的值编码并写入二进制输出流。
     */
    @Override
    protected void internalHandle(DataOutputStream buffer, final Circle value) throws IOException {
        buffer.writeInt(24);
        // 首先编码圆心：
        GeometricUtils.writePoint(buffer, value.getCenter());
        // 然后编码半径：
        buffer.writeDouble(value.getRadius());
    }

    /**
     * 计算当前值按目标编码写入时所占的字节数。
     */
    @Override
    public int getLength(Circle value) {
        return 24;
    }
}
