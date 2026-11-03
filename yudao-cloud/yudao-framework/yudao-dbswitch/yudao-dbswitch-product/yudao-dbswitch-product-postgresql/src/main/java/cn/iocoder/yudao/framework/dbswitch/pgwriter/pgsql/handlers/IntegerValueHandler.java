package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 整型处理器,将数值按 4 字节 int 写入 COPY 二进制流。
 */
public class IntegerValueHandler<T extends Number> extends BaseValueHandler<T> {

    /**
     * 将当前类型的值编码并写入二进制输出流。
     */
    @Override
    protected void internalHandle(DataOutputStream buffer, final T value) throws IOException {
        buffer.writeInt(4);
        buffer.writeInt(value.intValue());
    }

    /**
     * 计算当前值按目标编码写入时所占的字节数。
     */
    @Override
    public int getLength(T value) {
        return 4;
    }
}
