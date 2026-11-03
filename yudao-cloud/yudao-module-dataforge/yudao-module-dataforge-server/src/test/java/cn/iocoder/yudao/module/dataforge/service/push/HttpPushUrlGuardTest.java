package cn.iocoder.yudao.module.dataforge.service.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 推送 URL：白名单、私网与元数据拦截
 */
class HttpPushUrlGuardTest {

    @Test
    @DisplayName("仅允许 http/https，拒绝用户名密码")
    void protocolAndUserInfo() {
        assertThrows(IllegalArgumentException.class,
                () -> HttpPushUrlGuard.validate("ftp://example.com", List.of(), false));
        assertThrows(IllegalArgumentException.class,
                () -> HttpPushUrlGuard.validate("http://user:pass@8.8.8.8/push", List.of(), false));
    }

    @Test
    @DisplayName("白名单为空时拦截回环、私网、云元数据")
    void blockPrivateWhenNoAllowList() {
        assertThrows(IllegalArgumentException.class,
                () -> HttpPushUrlGuard.validate("http://127.0.0.1/push", List.of(), false));
        assertThrows(IllegalArgumentException.class,
                () -> HttpPushUrlGuard.validate("http://10.1.2.3/push", List.of(), false));
        assertThrows(IllegalArgumentException.class,
                () -> HttpPushUrlGuard.validate("http://192.168.0.8/push", List.of(), false));
        assertThrows(IllegalArgumentException.class,
                () -> HttpPushUrlGuard.validate("http://169.254.169.254/latest", List.of(), false));
    }

    @Test
    @DisplayName("白名单为空时允许公网 IP")
    void allowPublicIp() {
        assertDoesNotThrow(() -> HttpPushUrlGuard.validate("https://8.8.8.8/push", List.of(), false));
    }

    @Test
    @DisplayName("配置白名单后主机必须命中，命中后允许内网")
    void allowListRequiredAndCanIncludePrivate() {
        assertThrows(IllegalArgumentException.class,
                () -> HttpPushUrlGuard.validate("https://8.8.8.8/push", List.of("api.example.com"), false));
        assertDoesNotThrow(() -> HttpPushUrlGuard.validate(
                "http://10.1.2.3/push", List.of("10.1.2.3"), false));
        assertTrue(HttpPushUrlGuard.hostAllowed("api.foo.com", List.of("*.foo.com")));
        assertFalse(HttpPushUrlGuard.hostAllowed("foo.com", List.of("*.foo.com")));
    }

    @Test
    @DisplayName("云元数据地址即使在白名单也拒绝")
    void metadataAlwaysBlocked() {
        assertThrows(IllegalArgumentException.class,
                () -> HttpPushUrlGuard.validate("http://169.254.169.254/", List.of("169.254.169.254"), false));
    }

}
