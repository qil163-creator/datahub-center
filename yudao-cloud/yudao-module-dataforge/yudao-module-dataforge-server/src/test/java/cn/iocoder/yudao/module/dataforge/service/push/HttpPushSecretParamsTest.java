package cn.iocoder.yudao.module.dataforge.service.push;

import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushChannelConfigVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpPushSecretParamsTest {

    @Test
    @DisplayName("查询参数和 URL Query 中的 Token 都被拒绝")
    void rejectSecretInQuery() {
        DataPushChannelConfigVO.HttpConfig http = new DataPushChannelConfigVO.HttpConfig();
        http.setUrl("https://example.com/push");
        DataPushChannelConfigVO.KeyValue token = new DataPushChannelConfigVO.KeyValue();
        token.setKey("access_token");
        token.setValue("abc");
        http.setParams(List.of(token));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> HttpPushSecretParams.validate(http));
        assertTrue(ex.getMessage().contains("请求头"));

        DataPushChannelConfigVO.HttpConfig urlToken = new DataPushChannelConfigVO.HttpConfig();
        urlToken.setUrl("https://example.com/push?token=abc");
        assertThrows(IllegalArgumentException.class, () -> HttpPushSecretParams.validate(urlToken));
    }

    @Test
    @DisplayName("业务查询参数允许")
    void allowBusinessParams() {
        DataPushChannelConfigVO.HttpConfig http = new DataPushChannelConfigVO.HttpConfig();
        http.setUrl("https://example.com/push?env=prod");
        DataPushChannelConfigVO.KeyValue env = new DataPushChannelConfigVO.KeyValue();
        env.setKey("batch");
        env.setValue("1");
        http.setParams(List.of(env));
        assertDoesNotThrow(() -> HttpPushSecretParams.validate(http));
    }

}
