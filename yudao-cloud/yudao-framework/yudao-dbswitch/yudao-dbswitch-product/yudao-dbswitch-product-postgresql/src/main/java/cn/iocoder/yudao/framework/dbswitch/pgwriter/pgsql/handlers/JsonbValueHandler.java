package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * PostgreSQL jsonb 类型处理器,先写入协议版本号再将 JSON 字符串按 UTF-8 字节写入 COPY 流。
 */
public class JsonbValueHandler extends BaseValueHandler<String> {

    private final int jsonbProtocolVersion;

    /**
     * 使用给定参数初始化 {@code JsonbValueHandler}。
     */
    public JsonbValueHandler() {
        this(1);
    }

    /**
     * 初始化 {@code JsonbValueHandler}。
     */
    public JsonbValueHandler(int jsonbProtocolVersion) {
        this.jsonbProtocolVersion = jsonbProtocolVersion;
    }

    /**
     * 将当前类型的值编码并写入二进制输出流。
     */
    @Override
    protected void internalHandle(DataOutputStream buffer, final String value) throws IOException {

        byte[] utf8Bytes = value.getBytes("UTF-8");

        // 写入待复制数据的长度：
        buffer.writeInt(utf8Bytes.length + 1);
        // 写入 Jsonb 协议版本：
        buffer.writeByte(jsonbProtocolVersion);
        // 复制数据：
        buffer.write(utf8Bytes);
    }

    /**
     * 计算当前值按目标编码写入时所占的字节数。
     */
    @Override
    public int getLength(String value) {
        throw new UnsupportedOperationException();
    }
}
