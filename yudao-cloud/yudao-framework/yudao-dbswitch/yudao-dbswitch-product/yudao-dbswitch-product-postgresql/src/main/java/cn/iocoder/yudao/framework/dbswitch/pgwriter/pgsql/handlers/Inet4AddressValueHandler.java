package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Inet4Address;

/**
 * IPv4 地址处理器,将 Inet4Address 按 PostgreSQL inet 类型的二进制格式写入 COPY 流。
 */
public class Inet4AddressValueHandler extends BaseValueHandler<Inet4Address> {

    private static final byte IPv4 = 2;

    private static final byte MASK = 32;

    private static final byte IS_CIDR = 0;

    /**
     * 将当前类型的值编码并写入二进制输出流。
     */
    @Override
    protected void internalHandle(DataOutputStream buffer, final Inet4Address value)
            throws IOException {
        buffer.writeInt(8);

        buffer.writeByte(IPv4);
        buffer.writeByte(MASK);
        buffer.writeByte(IS_CIDR);

        byte[] inet4AddressBytes = value.getAddress();

        buffer.writeByte(inet4AddressBytes.length);
        buffer.write(inet4AddressBytes);
    }

    /**
     * 计算当前值按目标编码写入时所占的字节数。
     */
    @Override
    public int getLength(Inet4Address value) {
        return 8;
    }
}
