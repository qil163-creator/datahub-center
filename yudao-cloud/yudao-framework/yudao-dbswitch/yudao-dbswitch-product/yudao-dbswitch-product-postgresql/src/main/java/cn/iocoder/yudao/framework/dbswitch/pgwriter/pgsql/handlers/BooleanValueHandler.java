package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 布尔值处理器,将 Boolean 按 1 字节写入 COPY 二进制流。
 */
public class BooleanValueHandler extends BaseValueHandler<Boolean> {

    /**
     * 将当前类型的值编码并写入二进制输出流。
     */
    @Override
    protected void internalHandle(DataOutputStream buffer, final Boolean value) throws IOException {
        buffer.writeInt(1);
        if (value) {
            buffer.writeByte(1);
        } else {
            buffer.writeByte(0);
        }
    }

    /**
     * 计算当前值按目标编码写入时所占的字节数。
     */
    @Override
    public int getLength(Boolean value) {
        return 1;
    }
}
