package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * PostgreSQL hstore 类型处理器,将键值对 Map 按 hstore 二进制格式写入 COPY 流,值允许为 null。
 */
public class HstoreValueHandler extends BaseValueHandler<Map<String, String>> {

    /**
     * 将当前类型的值编码并写入二进制输出流。
     */
    @Override
    protected void internalHandle(DataOutputStream buffer, final Map<String, String> value)
            throws IOException {

        // 写入临时 ByteArrayOutputStream：
        ByteArrayOutputStream byteArrayOutput = new ByteArrayOutputStream();

        // 并将其包装为 DataOutputStream：
        DataOutputStream hstoreOutput = new DataOutputStream(byteArrayOutput);

        // 先写入待写值的数量：
        hstoreOutput.writeInt(value.size());

        // 遍历数组并写入每个值：
        for (Map.Entry<String, String> entry : value.entrySet()) {
            // 写入键：
            writeKey(hstoreOutput, entry.getKey());
            // 值可能为 null，需要使用另一种方法：
            writeValue(hstoreOutput, entry.getValue());
        }

        // 将整个字节数组写入 COPY 缓冲区：
        buffer.writeInt(byteArrayOutput.size());
        buffer.write(byteArrayOutput.toByteArray());
    }

    /**
     * 写入键。
     */
    private void writeKey(DataOutputStream buffer, String key) throws IOException {
        writeText(buffer, key);
    }

    /**
     * 写入值。
     */
    private void writeValue(DataOutputStream buffer, String value) throws IOException {
        if (value == null) {
            buffer.writeInt(-1);
        } else {
            writeText(buffer, value);
        }
    }

    /**
     * 写入文本。
     */
    private void writeText(DataOutputStream buffer, String text) throws IOException {
        byte[] textBytes = StringUtils.getUtf8Bytes(text);

        buffer.writeInt(textBytes.length);
        buffer.write(textBytes);
    }

    /**
     * 计算当前值按目标编码写入时所占的字节数。
     */
    @Override
    public int getLength(Map<String, String> value) {
        throw new UnsupportedOperationException();
    }
}
