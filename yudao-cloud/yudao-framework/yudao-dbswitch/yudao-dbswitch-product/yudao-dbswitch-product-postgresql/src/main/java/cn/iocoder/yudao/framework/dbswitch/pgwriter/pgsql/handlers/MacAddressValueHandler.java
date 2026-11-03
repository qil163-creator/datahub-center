package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.model.network.MacAddress;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * MAC 地址处理器,将 6 字节 MAC 地址写入 COPY 二进制流,对应 macaddr 列类型。
 */
public class MacAddressValueHandler extends BaseValueHandler<MacAddress> {

    /**
     * 将当前类型的值编码并写入二进制输出流。
     */
    @Override
    protected void internalHandle(DataOutputStream buffer, final MacAddress value)
            throws IOException {
        buffer.writeInt(6);
        buffer.write(value.getAddressBytes());
    }

    /**
     * 计算当前值按目标编码写入时所占的字节数。
     */
    @Override
    public int getLength(MacAddress value) {
        return 6;
    }
}
