package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;

/**
 * UUID 处理器,将 UUID 按 PostgreSQL 的 16 字节存储布局写入 COPY 二进制流。
 */
public class UUIDValueHandler extends BaseValueHandler<UUID> {

    /**
     * 将当前类型的值编码并写入二进制输出流。
     */
    @Override
    protected void internalHandle(DataOutputStream buffer, final UUID value) throws IOException {
        buffer.writeInt(16);

        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(value.getMostSignificantBits());
        bb.putLong(value.getLeastSignificantBits());

        buffer.writeInt(bb.getInt(0));
        buffer.writeShort(bb.getShort(4));
        buffer.writeShort(bb.getShort(6));

        buffer.write(Arrays.copyOfRange(bb.array(), 8, 16));
    }

    /**
     * 计算当前值按目标编码写入时所占的字节数。
     */
    @Override
    public int getLength(UUID value) {
        return 16;
    }
}
