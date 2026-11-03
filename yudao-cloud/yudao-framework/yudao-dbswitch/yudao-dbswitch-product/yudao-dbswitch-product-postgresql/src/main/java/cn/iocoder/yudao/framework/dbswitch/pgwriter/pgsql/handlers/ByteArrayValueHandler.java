package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 字节数组处理器,将 byte[] 内容原样写入 COPY 二进制流,对应 bytea 列类型。
 */
public class ByteArrayValueHandler extends BaseValueHandler<byte[]> {

    /**
     * 将当前类型的值编码并写入二进制输出流。
     */
    @Override
    protected void internalHandle(DataOutputStream buffer, final byte[] value) throws IOException {
        buffer.writeInt(value.length);
        buffer.write(value, 0, value.length);
    }

    /**
     * 计算当前值按目标编码写入时所占的字节数。
     */
    @Override
    public int getLength(byte[] value) {
        return value.length;
    }
}
