package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers.utils;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.model.geometric.Point;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 几何写入工具类,提供将点坐标(x、y 双精度值)写入 COPY 二进制流的公共方法。
 */
public class GeometricUtils {

    /**
     * 写入点。
     */
    public static void writePoint(DataOutputStream buffer, final Point value) throws IOException {
        buffer.writeDouble(value.getX());
        buffer.writeDouble(value.getY());
    }

}
