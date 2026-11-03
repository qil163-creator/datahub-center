package cn.iocoder.yudao.module.dataforge.service.push;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

/**
 * HTTP 请求体编码：明文 JSON 或 gzip
 *
 * @author Li
 */
public final class HttpPushBodySupport {

    /**
     * 限制HTTP推送请求体支持工具的实例化，统一通过静态能力使用。
     */
    private HttpPushBodySupport() {
    }

    /**
     * 编码HTTP推送请求体支持。
     *
     * @param json JSON 内容
     * @param gzip GZIP
     * @return 转换后的字节序列
     */
    public static byte[] encode(String json, boolean gzip) {
        byte[] raw = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
        if (!gzip) {
            return raw;
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (GZIPOutputStream gzipStream = new GZIPOutputStream(buffer)) {
                gzipStream.write(raw);
            }
            return buffer.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("请求体 gzip 失败：" + ex.getMessage(), ex);
        }
    }

}
