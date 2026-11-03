package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers.utils.GeometricUtils;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.model.geometric.Path;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.model.geometric.Point;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * PostgreSQL path 几何类型处理器,将路径的开闭标记、点数量及各点坐标写入 COPY 二进制流。
 */
public class PathValueHandler extends BaseValueHandler<Path> {

    /**
     * 将当前类型的值编码并写入二进制输出流。
     */
    @Override
    protected void internalHandle(DataOutputStream buffer, final Path value) throws IOException {
        // 写入一个字节，表示路径是否闭合：
        byte pathIsClosed = (byte) (value.isClosed() ? 1 : 0);

        // 待写入的总字节数：
        int totalBytesToWrite = 1 + 4 + 16 * value.size();

        // 后续字节数：
        buffer.writeInt(totalBytesToWrite);
        // 检查圆形路径是否闭合
        buffer.writeByte(pathIsClosed);
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
    public int getLength(Path value) {
        throw new UnsupportedOperationException();
    }
}
