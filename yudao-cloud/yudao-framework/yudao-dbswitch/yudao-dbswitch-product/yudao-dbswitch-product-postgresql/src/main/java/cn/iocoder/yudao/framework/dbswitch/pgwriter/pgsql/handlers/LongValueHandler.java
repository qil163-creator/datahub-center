package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 长整型处理器,将数值按 8 字节 long 写入 COPY 二进制流。
 */
public class LongValueHandler<T extends Number> extends BaseValueHandler<T> {

    /**
     * 将当前类型的值编码并写入二进制输出流。
     */
    @Override
    protected void internalHandle(DataOutputStream buffer, final T value) throws IOException {
        buffer.writeInt(8);
        buffer.writeLong(value.longValue());
    }

    /**
     * 计算当前值按目标编码写入时所占的字节数。
     */
    @Override
    public int getLength(T value) {
        return 8;
    }
}
