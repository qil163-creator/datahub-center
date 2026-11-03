package cn.iocoder.yudao.module.dataforge.service.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 请求体明文 / gzip
 */
class HttpPushBodySupportTest {

    @Test
    @DisplayName("未启用 gzip 时返回 UTF-8 原文")
    void plainJson() {
        byte[] raw = HttpPushBodySupport.encode("{\"a\":1}", false);
        assertEquals("{\"a\":1}", new String(raw, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("启用 gzip 时输出 GZIP 魔数")
    void gzipMagic() {
        byte[] gzip = HttpPushBodySupport.encode("{\"objectName\":\"T\",\"dataList\":[]}", true);
        assertTrue(gzip.length > 2);
        assertEquals((byte) 0x1f, gzip[0]);
        assertEquals((byte) 0x8b, gzip[1]);
    }

}
