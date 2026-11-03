package cn.iocoder.yudao.module.dataforge.service.push;

import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushChannelConfigVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP URL 编码与 2xx 判定
 */
class HttpPushUrlBuilderTest {

    @Test
    @DisplayName("无查询参数时原样返回")
    void noParams() {
        DataPushChannelConfigVO.HttpConfig http = new DataPushChannelConfigVO.HttpConfig();
        http.setUrl("https://example.com/push");
        assertEquals("https://example.com/push", HttpPushUrlBuilder.build(http));
    }

    @Test
    @DisplayName("查询参数做 URL 编码，已有问号则追加 &")
    void encodeParams() {
        DataPushChannelConfigVO.HttpConfig http = new DataPushChannelConfigVO.HttpConfig();
        http.setUrl("https://example.com/push?env=prod");
        DataPushChannelConfigVO.KeyValue a = new DataPushChannelConfigVO.KeyValue();
        a.setKey("q");
        a.setValue("a b");
        DataPushChannelConfigVO.KeyValue b = new DataPushChannelConfigVO.KeyValue();
        b.setKey("code");
        b.setValue("中文");
        http.setParams(List.of(a, b));
        String url = HttpPushUrlBuilder.build(http);
        assertTrue(url.startsWith("https://example.com/push?env=prod&"));
        assertTrue(url.contains("q=a+b") || url.contains("q=a%20b"));
        assertTrue(url.contains("code=%E4%B8%AD%E6%96%87"));
    }

    @Test
    @DisplayName("鉴权类查询参数不会拼进 URL")
    void skipSecretQueryParams() {
        DataPushChannelConfigVO.HttpConfig http = new DataPushChannelConfigVO.HttpConfig();
        http.setUrl("https://example.com/push");
        DataPushChannelConfigVO.KeyValue token = new DataPushChannelConfigVO.KeyValue();
        token.setKey("token");
        token.setValue("secret");
        DataPushChannelConfigVO.KeyValue env = new DataPushChannelConfigVO.KeyValue();
        env.setKey("env");
        env.setValue("prod");
        http.setParams(List.of(token, env));
        assertEquals("https://example.com/push?env=prod", HttpPushUrlBuilder.build(http));
    }

    @Test
    @DisplayName("缺少地址时失败")
    void missingUrl() {
        assertThrows(IllegalArgumentException.class, () -> HttpPushUrlBuilder.build(null));
        assertThrows(IllegalArgumentException.class,
                () -> HttpPushUrlBuilder.build(new DataPushChannelConfigVO.HttpConfig()));
    }

    @Test
    @DisplayName("仅 2xx 视为成功")
    void successStatus() {
        assertTrue(HttpPushUrlBuilder.isSuccessStatus(200));
        assertTrue(HttpPushUrlBuilder.isSuccessStatus(204));
        assertFalse(HttpPushUrlBuilder.isSuccessStatus(199));
        assertFalse(HttpPushUrlBuilder.isSuccessStatus(300));
        assertFalse(HttpPushUrlBuilder.isSuccessStatus(404));
        assertFalse(HttpPushUrlBuilder.isSuccessStatus(500));
    }

    @Test
    @DisplayName("实时日志只保留主机和端口")
    void hostOnly() {
        assertEquals("consumer.example.com",
                HttpPushUrlBuilder.hostOnly("https://consumer.example.com/push?token=secret"));
        assertEquals("127.0.0.1:8080",
                HttpPushUrlBuilder.hostOnly("http://127.0.0.1:8080/api/data"));
        assertEquals("", HttpPushUrlBuilder.hostOnly(null));
    }

}
