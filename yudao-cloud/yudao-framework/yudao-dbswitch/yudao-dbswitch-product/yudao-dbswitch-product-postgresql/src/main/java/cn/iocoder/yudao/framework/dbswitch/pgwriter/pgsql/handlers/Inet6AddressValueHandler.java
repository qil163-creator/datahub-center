package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Inet6Address;

/**
 * IPv6 地址处理器,将 Inet6Address 按 PostgreSQL inet 类型的二进制格式写入 COPY 流。
 */
public class Inet6AddressValueHandler extends BaseValueHandler<Inet6Address> {

    private static final byte IPv6 = 3;

    private static final int MASK = 128;

    private static final byte IS_CIDR = 0;

    /**
     * 将当前类型的值编码并写入二进制输出流。
     */
    @Override
    protected void internalHandle(DataOutputStream buffer, final Inet6Address value)
            throws IOException {
        buffer.writeInt(20);

        buffer.writeByte(IPv6);
        buffer.writeByte(MASK);
        buffer.writeByte(IS_CIDR);

        byte[] inet6AddressBytes = value.getAddress();
        buffer.writeByte(inet6AddressBytes.length);
        buffer.write(inet6AddressBytes);
    }

    /**
     * 计算当前值按目标编码写入时所占的字节数。
     */
    @Override
    public int getLength(Inet6Address value) {
        return 20;
    }
}
