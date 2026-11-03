package cn.iocoder.yudao.module.dataforge.service.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataPushPayloadLimiterTest {

    @Test
    @DisplayName("超大 byte[] 省略，短字节转 hex")
    void omitLargeBytes() throws Exception {
        assertEquals("[omitted, 8 bytes]",
                DataPushPayloadLimiter.limitValue(new byte[8], 4, 100));
        assertEquals("6162", DataPushPayloadLimiter.limitValue(new byte[]{'a', 'b'}, 16, 100));
    }

    @Test
    @DisplayName("超长字符串截断")
    void truncateLongString() throws Exception {
        String value = (String) DataPushPayloadLimiter.limitValue("abcdefghij", 16, 4);
        assertTrue(value.startsWith("abcd"));
        assertTrue(value.endsWith("...(truncated)"));
    }

    @Test
    @DisplayName("行体积估算随字段增长")
    void estimateGrows() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1);
        int small = DataPushPayloadLimiter.estimateRowBytes(row);
        row.put("name", "abcdefgh");
        assertTrue(DataPushPayloadLimiter.estimateRowBytes(row) > small);
    }

    @Test
    @DisplayName("二进制流超限只记长度，不整段缓存")
    void omitLargeBinaryStream() throws Exception {
        byte[] payload = new byte[32];
        Object omitted = DataPushPayloadLimiter.limitBinaryStream(new ByteArrayInputStream(payload), 8);
        assertEquals("[omitted, 32 bytes]", omitted);
        assertEquals("6162", DataPushPayloadLimiter.limitBinaryStream(
                new ByteArrayInputStream(new byte[]{'a', 'b'}), 16));
    }

    @Test
    @DisplayName("字符流超限截断并排空剩余")
    void truncateCharStream() throws Exception {
        Object value = DataPushPayloadLimiter.limitCharStream(new StringReader("abcdefghij"), 4);
        assertEquals("abcd...(truncated)", value);
    }

}
