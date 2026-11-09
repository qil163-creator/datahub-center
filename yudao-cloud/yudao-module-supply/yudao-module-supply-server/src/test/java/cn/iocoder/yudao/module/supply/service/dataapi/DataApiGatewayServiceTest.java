package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.hutool.crypto.SmUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.supply.dal.dataobject.app.SupplyAppDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.appapi.AppApiDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.dal.mysql.app.SupplyAppMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.AppApiMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiMapper;
import cn.iocoder.yudao.module.supply.framework.config.SupplyDataApiProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;

class DataApiGatewayServiceTest {

    @ParameterizedTest
    @CsvSource(value = {"NULL,NULL,true", "'',NULL,true", "' ',NULL,true", "hash,hash,true",
            "hash,NULL,false", "hash,'',false", "hash,other,false", "hash,HASH,false", "hash,' hash ',false"},
            nullValues = "NULL")
    void gatewayKeepsItsOriginalContractBoundary(String current, String approved, boolean accepted) {
        var mapper = org.mockito.Mockito.mock(AppApiMapper.class);
        var service = new DataApiGatewayService();
        ReflectionTestUtils.setField(service, "appApiMapper", mapper);
        var app = SupplyAppDO.builder().id(1L).appStatus(1).build();
        var api = DataApiDO.builder().id(10L).contractHash(current).build();
        when(mapper.selectByAppIdAndApiId(1L, 10L)).thenReturn(AppApiDO.builder()
                .id(501L).appId(1L).apiId(10L).foreverFlag(1).grantStatus(1)
                .approvedContractHash(approved).build());
        if (accepted) {
            ReflectionTestUtils.invokeMethod(service, "validateAuthorization", app, api);
        } else {
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "validateAuthorization", app, api))
                    .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode())
                            .isEqualTo(cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants
                                    .DATA_API_CONTRACT_MISMATCH.getCode()));
        }
    }

    @Test
    void signatureCoversQueryAndBody() {
        byte[] body = "{\"id\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String baseline = DataApiSignature.sign("secret", "POST", "/services/v1/orders",
                "a=1", body, "app-1", "1720000000000", "nonce-1", "application/json");

        assertThat(DataApiSignature.sign("secret", "POST", "/services/v1/orders",
                "a=2", body, "app-1", "1720000000000", "nonce-1", "application/json"))
                .isNotEqualTo(baseline);
        assertThat(DataApiSignature.sign("secret", "POST", "/services/v1/orders",
                "a=1", "{\"id\":2}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "app-1", "1720000000000", "nonce-1", "application/json"))
                .isNotEqualTo(baseline);
    }

    @Test
    void retiredUnderscoreSm3SignatureIsRejected() {
        try (GatewayFixture fixture = new GatewayFixture()) {
            String timestamp = String.valueOf(fixture.now);
            // 已退役的旧协议：SM3(secret + METHOD_PATH_AppKey_Timestamp_Nonce)。平台只认 HMAC-SM3，不得降级接受。
            String retired = SmUtil.sm3("secret" + "GET_/services/v1/orders_app-1_" + timestamp + "_nonce-1");
            Map<String, String> headers = Map.of(
                    DataApiGatewayService.HEADER_APP_KEY, "app-1",
                    DataApiGatewayService.HEADER_TIMESTAMP, timestamp,
                    DataApiGatewayService.HEADER_NONCE, "nonce-1",
                    DataApiGatewayService.HEADER_SIGN, retired);

            assertThatThrownBy(() -> fixture.service.invoke("v1", "/orders", "GET", Map.of(), null, null,
                    "127.0.0.1", "", null, headers))
                    .isInstanceOf(ServiceException.class)
                    .hasMessageContaining("签名不正确");
            org.mockito.Mockito.verifyNoInteractions(fixture.redisStore);
        }
    }

    @Test
    void secondPrecisionTimestampIsRejected() {
        try (GatewayFixture fixture = new GatewayFixture()) {
            String seconds = String.valueOf(fixture.now / 1000);
            Map<String, String> headers = Map.of(
                    DataApiGatewayService.HEADER_APP_KEY, "app-1",
                    DataApiGatewayService.HEADER_TIMESTAMP, seconds,
                    DataApiGatewayService.HEADER_NONCE, "nonce-1",
                    DataApiGatewayService.HEADER_SIGN, "00");

            assertThatThrownBy(() -> fixture.service.invoke("v1", "/orders", "GET", Map.of(), null, null,
                    "127.0.0.1", "", null, headers))
                    .isInstanceOf(ServiceException.class)
                    .hasMessageContaining("毫秒");
        }
    }

    @Test
    void forwardUrlShouldRejectLocalTargets() {
        SupplyDataApiProperties properties = new SupplyDataApiProperties();
        properties.setForwardAllowHosts(Set.of("example.com", "::1", "ff02::1"));

        assertThatThrownBy(() -> DataApiExecutor.validateForwardUrl("http://127.0.0.1/internal", properties))
                .hasMessageContaining("转发地址未配置在允许访问的主机白名单中");
        assertThatThrownBy(() -> DataApiExecutor.validateForwardUrl("http://[::1]/internal", properties))
                .hasMessageContaining("转发地址不能指向内网或本机地址");
        assertThatThrownBy(() -> DataApiExecutor.validateForwardUrl("http://[ff02::1]/internal", properties))
                .hasMessageContaining("转发地址不能指向内网或本机地址");
        assertThatThrownBy(() -> DataApiExecutor.validateForwardUrl("file:///etc/passwd", properties))
                .hasMessageContaining("转发地址仅允许 HTTP/HTTPS");
        assertThatThrownBy(() -> DataApiExecutor.validateForwardUrl("https://not-allowed.example/api", properties))
                .hasMessageContaining("转发地址未配置在允许访问的主机白名单中");
    }

    @Test
    void forwardGetQueryIsEncodedAndKeepsExistingQuery() {
        java.util.LinkedHashMap<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("name", "张 三&x=1");
        params.put("ids", List.of(1, 2));
        params.put("skip", null);

        assertThat(DataApiExecutor.appendQuery("https://example.com/api?fixed=1", params))
                .isEqualTo("https://example.com/api?fixed=1&name=%E5%BC%A0%20%E4%B8%89%26x%3D1&ids=1&ids=2");
        assertThatThrownBy(() -> DataApiExecutor.appendQuery("not a url", Map.of()))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void sameNonceIsRejectedAndUsesRemainingWindowTtl() {
        try (GatewayFixture fixture = new GatewayFixture()) {
            org.mockito.Mockito.when(fixture.redisStore.reserveNonce(
                    org.mockito.ArgumentMatchers.eq("app-1"), org.mockito.ArgumentMatchers.eq("nonce-1"),
                    org.mockito.ArgumentMatchers.anyLong()))
                    .thenReturn(true)
                    .thenReturn(false);

            String timestamp = String.valueOf(fixture.now);
            String sign = DataApiSignature.sign("secret", "GET", "/services/v1/orders", "", new byte[0],
                    "app-1", timestamp, "nonce-1", null);
            Map<String, String> headers = Map.of(
                    DataApiGatewayService.HEADER_APP_KEY, "app-1",
                    DataApiGatewayService.HEADER_TIMESTAMP, timestamp,
                    DataApiGatewayService.HEADER_NONCE, "nonce-1",
                    DataApiGatewayService.HEADER_SIGN, sign);
            fixture.service.invoke("v1", "/orders", "GET", Map.of(), null, null, "127.0.0.1", "", null, headers);
            assertThatThrownBy(() -> fixture.service.invoke("v1", "/orders", "GET", Map.of(), null, null,
                    "127.0.0.1", "", null, headers))
                    .isInstanceOf(ServiceException.class)
                    .hasMessageContaining("Nonce");
            org.mockito.Mockito.verify(fixture.redisStore, org.mockito.Mockito.times(2))
                    .reserveNonce(
                            org.mockito.ArgumentMatchers.eq("app-1"),
                            org.mockito.ArgumentMatchers.eq("nonce-1"),
                            org.mockito.ArgumentMatchers.eq(DataApiGatewayService.SIGN_TIMESTAMP_VALID_MS
                                    + DataApiGatewayService.CLOCK_SKEW_MARGIN_MS));
        }
    }

    /** 已上线 SIGN 查询接口 + 已授权应用（密钥明文 "secret"，以 SM4 密文落库）的最小网关夹具。 */
    private static final class GatewayFixture implements AutoCloseable {

        private static final String MASTER = "gateway-test-master";

        private final org.mockito.MockedStatic<cn.hutool.extra.spring.SpringUtil> spring =
                org.mockito.Mockito.mockStatic(cn.hutool.extra.spring.SpringUtil.class);

        final long now = 1_720_000_000_000L;
        final SupplyApiRedisStore redisStore = org.mockito.Mockito.mock(SupplyApiRedisStore.class);
        final DataApiGatewayService service = new DataApiGatewayService();

        GatewayFixture() {
            DataApiMapper dataApiMapper = org.mockito.Mockito.mock(DataApiMapper.class);
            SupplyAppMapper appMapper = org.mockito.Mockito.mock(SupplyAppMapper.class);
            AppApiMapper appApiMapper = org.mockito.Mockito.mock(AppApiMapper.class);
            DataApiExecutor executor = org.mockito.Mockito.mock(DataApiExecutor.class);
            ReflectionTestUtils.setField(service, "dataApiMapper", dataApiMapper);
            ReflectionTestUtils.setField(service, "appMapper", appMapper);
            ReflectionTestUtils.setField(service, "appApiMapper", appApiMapper);
            ReflectionTestUtils.setField(service, "redisStore", redisStore);
            ReflectionTestUtils.setField(service, "dataApiExecutor", executor);
            ReflectionTestUtils.setField(service, "apiLogMapper",
                    org.mockito.Mockito.mock(cn.iocoder.yudao.module.supply.dal.mysql.apilog.ApiLogMapper.class));
            service.setClock(Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC));
            DataApiDO api = DataApiDO.builder()
                    .id(1L).status(DataApiDO.STATUS_ONLINE)
                    .path("/orders").version("v1").method("GET")
                    .apiType(DataApiDO.API_TYPE_QUERY).authType(DataApiDO.AUTH_TYPE_SIGN)
                    .configType(DataApiDO.CONFIG_TYPE_SQL).build();
            spring.when(() -> cn.hutool.extra.spring.SpringUtil.getProperty("mybatis-plus.encryptor.password"))
                    .thenReturn(MASTER);
            SupplyAppDO app = SupplyAppDO.builder().id(2L).appKey("app-1")
                    .secret(SupplyAppSecretCipher.encrypt("secret", "SM4", MASTER)).build();
            when(dataApiMapper.selectListByPathAndVersion("/orders", "v1")).thenReturn(List.of(api));
            when(appMapper.selectByAppKey("app-1")).thenReturn(app);
            when(appApiMapper.selectByAppIdAndApiId(2L, 1L)).thenReturn(AppApiDO.builder()
                    .appId(2L).apiId(1L).foreverFlag(1).build());
            when(executor.execute(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(),
                    anyBoolean(), any())).thenReturn(
                    new cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiExecuteRespVO());
        }

        @Override
        public void close() {
            spring.close();
        }
    }

}
