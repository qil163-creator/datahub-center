package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.iocoder.yudao.module.supply.dal.dataobject.apilog.ApiLogDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.dal.mysql.apilog.ApiLogMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DataApiRejectLoggingTest {

    @ParameterizedTest @ValueSource(strings={"IP", "RATE", "BREAKER"})
    void guardRejectionsAreRecordedExactlyOnceWithoutParameters(String stage) {
        DataApiExecutor executor = new DataApiExecutor();
        ApiLogMapper logs = mock(ApiLogMapper.class);
        SupplyApiRedisStore redis = mock(SupplyApiRedisStore.class);
        ReflectionTestUtils.setField(executor, "apiLogMapper", logs);
        ReflectionTestUtils.setField(executor, "redisStore", redis);
        DataApiDO api = DataApiDO.builder().id(8L).apiType("QUERY").configType("TABLE")
                .limitEnabled("RATE".equals(stage) ? 1 : 0).breakerEnabled("BREAKER".equals(stage) ? 1 : 0)
                .ipWhitelist("IP".equals(stage) ? "192.0.2.1" : null).build();
        when(redis.isCircuitOpen( anyString(), anyLong())).thenReturn(true);
        assertThatThrownBy(() -> executor.execute(api, Map.of("privateValue", "DO_NOT_LOG", "_operationMode", "STATUS"), 1, 10,
                "192.0.2.2", true, true)).isInstanceOf(RuntimeException.class);
        ArgumentCaptor<ApiLogDO> record = ArgumentCaptor.forClass(ApiLogDO.class);
        verify(logs, times(1)).insert(record.capture());
        assertThat(record.getValue().getRejectStage()).isEqualTo(stage);
        assertThat(record.getValue().getStatus()).isZero();
        assertThat(record.getValue().getParamsJson()).doesNotContain("DO_NOT_LOG");
        verify(redis, never()).recordBreakerError( anyString(), anyLong(), anyLong(), anyInt(), anyLong());
    }

    @Test void postForwardNeverReadsOrWritesQueryCache() {
        DataApiExecutor executor = new DataApiExecutor();
        var properties = new cn.iocoder.yudao.module.supply.framework.config.SupplyDataApiProperties();
        properties.setResultCacheEnabled(true);
        ReflectionTestUtils.setField(executor,"dataApiProperties",properties);
        var api = DataApiDO.builder().id(1L).activeReleaseId(2L).apiType("QUERY").configType("FORWARD")
                .method("POST").cacheEnabled(1).build();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(executor,"canUseResultCache",api,
                DataApiExecuteMode.APP_GATEWAY,null)).isFalse();
        api.setForwardMethod("GET");
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(executor,"canUseResultCache",api,
                DataApiExecuteMode.APP_GATEWAY,null)).isTrue();
    }

    @Test void legacyAppKeyWriteIsRejectedAndLoggedBeforeAnyDispatch() {
        var gateway = new DataApiGatewayService();
        var apps = mock(cn.iocoder.yudao.module.supply.dal.mysql.app.SupplyAppMapper.class);
        var apis = mock(cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiMapper.class);
        var grants = mock(cn.iocoder.yudao.module.supply.dal.mysql.appapi.AppApiMapper.class);
        var executor = mock(DataApiExecutor.class);
        var logs = mock(ApiLogMapper.class);
        ReflectionTestUtils.setField(gateway,"appMapper",apps);
        ReflectionTestUtils.setField(gateway,"dataApiMapper",apis);
        ReflectionTestUtils.setField(gateway,"appApiMapper",grants);
        ReflectionTestUtils.setField(gateway,"dataApiExecutor",executor);
        ReflectionTestUtils.setField(gateway,"apiLogMapper",logs);
        var app = cn.iocoder.yudao.module.supply.dal.dataobject.app.SupplyAppDO.builder()
                .id(3L).appStatus(1).build();
        var api = DataApiDO.builder().id(8L).status(1).path("/permit").version("v1")
                .method("POST").configType("SQL").apiType("INSERT").authType("APPKEY").build();
        when(apis.selectListByPathAndVersion("/permit","v1")).thenReturn(java.util.List.of(api));
        when(apps.selectByAppKey("test-app")).thenReturn(app);
        when(grants.selectByAppIdAndApiId(3L,8L)).thenReturn(
                cn.iocoder.yudao.module.supply.dal.dataobject.appapi.AppApiDO.builder()
                        .appId(3L).apiId(8L).grantStatus(1).foreverFlag(1).build());
        assertThatThrownBy(() -> gateway.invoke("v1","/permit","POST",Map.of("_operationKey","permit-case-001"),
                null,null,"192.0.2.2",Map.of("X-AppKey","test-app")))
                .hasMessageContaining("v2");
        verifyNoInteractions(executor);
        var logged = ArgumentCaptor.forClass(ApiLogDO.class);
        verify(logs).insert(logged.capture());
        assertThat(logged.getValue().getRejectStage()).isEqualTo("AUTH");
        assertThat(logged.getValue().getAppId()).isEqualTo(3L);
    }
}
