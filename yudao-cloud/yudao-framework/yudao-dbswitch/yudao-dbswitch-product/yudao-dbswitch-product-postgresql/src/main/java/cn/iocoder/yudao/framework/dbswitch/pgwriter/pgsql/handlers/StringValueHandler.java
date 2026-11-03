package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.util.StringUtils;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 字符串处理器,将字符串按 UTF-8 字节写入 COPY 二进制流,对应 text/varchar 列类型。
 */
public class StringValueHandler extends BaseValueHandler<String> {

    /**
     * 将当前类型的值编码并写入二进制输出流。
     */
    @Override
    protected void internalHandle(DataOutputStream buffer, final String value) throws IOException {
        byte[] utf8Bytes = StringUtils.getUtf8Bytes(value);
        buffer.writeInt(utf8Bytes.length);
        buffer.write(utf8Bytes);
    }

    /**
     * 计算当前值按目标编码写入时所占的字节数。
     */
    @Override
    public int getLength(String value) {
        byte[] utf8Bytes = StringUtils.getUtf8Bytes(value);
        return utf8Bytes.length;
    }
}
