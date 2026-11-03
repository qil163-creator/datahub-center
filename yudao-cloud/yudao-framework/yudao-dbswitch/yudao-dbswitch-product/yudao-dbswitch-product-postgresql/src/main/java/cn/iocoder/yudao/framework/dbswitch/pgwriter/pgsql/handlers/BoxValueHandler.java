package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers.utils.GeometricUtils;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.model.geometric.Box;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * PostgreSQL box 几何类型处理器,将矩形的高低两个角点写入 COPY 二进制流。
 */
public class BoxValueHandler extends BaseValueHandler<Box> {

    /**
     * 将当前类型的值编码并写入二进制输出流。
     */
    @Override
    protected void internalHandle(DataOutputStream buffer, final Box value) throws IOException {
        buffer.writeInt(32);

        GeometricUtils.writePoint(buffer, value.getHigh());
        GeometricUtils.writePoint(buffer, value.getLow());
    }

    /**
     * 计算当前值按目标编码写入时所占的字节数。
     */
    @Override
    public int getLength(Box value) {
        return 32;
    }
}
