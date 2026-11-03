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
    void signatureShouldFollowSm3Protocol() {
        String signature = DataApiGatewayService.sign("app-1", "secret", "get",
                "/services/v1/orders", "1720000000000", "nonce-1");

        String signatureString = "GET_/services/v1/orders_app-1_1720000000000_nonce-1";
        assertThat(DataApiGatewayService.signatureString("get", "/services/v1/orders",
                "app-1", "1720000000000", "nonce-1")).isEqualTo(signatureString);
        assertThat(signature).isEqualTo(SmUtil.sm3("secret" + signatureString));
    }

    @Test
    void v2MustNotFallBackToLegacySignature() {
        byte[] body = "{\"id\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String v2 = DataApiSignatureV2.sign("secret", "POST", "/services/v1/orders",
                "a=1", body, "app-1", "1720000000000", "nonce-1", "application/json", "1");
        String v1 = DataApiGatewayService.sign("app-1", "secret", "POST",
                "/services/v1/orders", "a=1", "{\"id\":1}", "1720000000000", "nonce-1");
        assertThat(v2).isNotEqualTo(v1);
    }

    @Test
    void queryAndBodyMustNotChangeTheAgreedSignatureString() {
        String baseline = DataApiGatewayService.sign("app-1", "secret", "POST",
                "/services/v1/orders", "a=1", "{\"id\":1}",
                "1720000000000", "nonce-1");

        assertThat(DataApiGatewayService.sign("app-1", "secret", "POST",
                "/services/v1/orders", "a=2", "{\"id\":2}",
                "1720000000000", "nonce-1")).isEqualTo(baseline);
    }

    @Test
    void canonicalRequestShouldReturnTheExactProtocolString() {
        String canonical = DataApiGatewayService.canonicalRequest("v1", "POST",
                "/services/v1/orders", "a=1", "body", "app-1",
                "1720000000000", "nonce-1");

        assertThat(canonical).isEqualTo("POST_/services/v1/orders_app-1_1720000000000_nonce-1");
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
    void sameNonceIsRejectedAndUsesRemainingWindowTtl() {
        DataApiMapper dataApiMapper = org.mockito.Mockito.mock(DataApiMapper.class);
        SupplyAppMapper appMapper = org.mockito.Mockito.mock(SupplyAppMapper.class);
        AppApiMapper appApiMapper = org.mockito.Mockito.mock(AppApiMapper.class);
        SupplyApiRedisStore redisStore = org.mockito.Mockito.mock(SupplyApiRedisStore.class);
        DataApiExecutor executor = org.mockito.Mockito.mock(DataApiExecutor.class);
        DataApiGatewayService service = new DataApiGatewayService();
        ReflectionTestUtils.setField(service, "dataApiMapper", dataApiMapper);
        ReflectionTestUtils.setField(service, "appMapper", appMapper);
        ReflectionTestUtils.setField(service, "appApiMapper", appApiMapper);
        ReflectionTestUtils.setField(service, "redisStore", redisStore);
        ReflectionTestUtils.setField(service, "dataApiExecutor", executor);
        long now = 1_720_000_000_000L;
        service.setClock(Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC));

        DataApiDO api = DataApiDO.builder()
                .id(1L).status(DataApiDO.STATUS_ONLINE)
                .path("/orders").version("v1").method("GET")
                .apiType(DataApiDO.API_TYPE_QUERY).authType(DataApiDO.AUTH_TYPE_SIGN)
                .configType(DataApiDO.CONFIG_TYPE_SQL).build();
        SupplyAppDO app = SupplyAppDO.builder()
                .id(2L).appKey("app-1").secret("secret")
                .signProtocol(SupplyAppDO.SIGN_PROTOCOL_BOTH).build();
        when(dataApiMapper.selectListByPathAndVersion("/orders", "v1")).thenReturn(List.of(api));
        when(appMapper.selectByAppKey("app-1")).thenReturn(app);
        when(appApiMapper.selectByAppIdAndApiId(2L, 1L)).thenReturn(AppApiDO.builder()
                .appId(2L).apiId(1L).foreverFlag(1).build());
        when(executor.execute(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(),
                anyBoolean(), any())).thenReturn(new cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiExecuteRespVO());
        org.mockito.Mockito.when(redisStore.reserveNonce(
                org.mockito.ArgumentMatchers.eq("app-1"), org.mockito.ArgumentMatchers.eq("nonce-1"),
                org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(true)
                .thenReturn(false);

        String timestamp = String.valueOf(now);
        String sign = DataApiGatewayService.sign("app-1", "secret", "GET",
                "/services/v1/orders", timestamp, "nonce-1");
        Map<String, String> headers = Map.of(
                DataApiGatewayService.HEADER_APP_KEY, "app-1",
                DataApiGatewayService.HEADER_TIMESTAMP, timestamp,
                DataApiGatewayService.HEADER_NONCE, "nonce-1",
                DataApiGatewayService.HEADER_SIGN, sign);
        service.invoke("v1", "/orders", "GET", Map.of(), null, null, "127.0.0.1", "", null, headers);
        assertThatThrownBy(() -> service.invoke("v1", "/orders", "GET", Map.of(), null, null,
                "127.0.0.1", "", null, headers))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("Nonce");
        org.mockito.Mockito.verify(redisStore, org.mockito.Mockito.times(2))
                .reserveNonce(
                        org.mockito.ArgumentMatchers.eq("app-1"),
                        org.mockito.ArgumentMatchers.eq("nonce-1"),
                        org.mockito.ArgumentMatchers.eq(DataApiGatewayService.SIGN_TIMESTAMP_VALID_MS
                                + DataApiGatewayService.CLOCK_SKEW_MARGIN_MS));
    }

}
