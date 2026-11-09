package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.framework.config.SupplyDataApiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.DATA_API_CONCURRENCY_LIMITED;
import static cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.DATA_API_EXECUTE_FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataApiConcurrencyTimeoutGuardTest {

    private final SupplyApiRedisStore redis = mock(SupplyApiRedisStore.class);
    private final SupplyDataApiProperties properties = new SupplyDataApiProperties();
    private final DataApiExecutor executor = new DataApiExecutor();

    DataApiConcurrencyTimeoutGuardTest() {
        ReflectionTestUtils.setField(executor, "redisStore", redis);
        ReflectionTestUtils.setField(executor, "dataApiProperties", properties);
    }

    private static DataApiDO api(Integer maxConcurrency, Integer timeoutSeconds) {
        return DataApiDO.builder().id(1L).maxConcurrency(maxConcurrency).timeoutSeconds(timeoutSeconds).build();
    }

    private void acquire(DataApiDO api, Long appId, List<SupplyApiRedisStore.ConcurrencyLease> leases) {
        ReflectionTestUtils.invokeMethod(executor, "acquireConcurrency", api, appId, leases);
    }

    @Test
    void queryTimeoutUsesApiValueThenPlatformDefaultAndNeverExceedsPlatformMax() {
        assertThat(executor.queryTimeoutSeconds(api(null, null))).isEqualTo(30);
        assertThat(executor.queryTimeoutSeconds(api(null, 12))).isEqualTo(12);
        assertThat(executor.queryTimeoutSeconds(api(null, 600))).isEqualTo(300);
        properties.setDefaultQueryTimeoutSeconds(45);
        properties.setMaxQueryTimeoutSeconds(40);
        assertThat(executor.queryTimeoutSeconds(api(null, null))).isEqualTo(40);
    }

    @Test
    void forwardKeepsTenSecondDefaultUnlessApiConfiguresTimeout() {
        assertThat((Integer) ReflectionTestUtils.invokeMethod(executor, "forwardTimeoutSeconds", api(null, null)))
                .isEqualTo(10);
        assertThat((Integer) ReflectionTestUtils.invokeMethod(executor, "forwardTimeoutSeconds", api(null, 250)))
                .isEqualTo(120);
    }

    @Test
    void unlimitedApiDoesNotTouchRedis() {
        acquire(api(null, null), 7L, new ArrayList<>());
        acquire(api(0, null), 7L, new ArrayList<>());
        verify(redis, never()).acquireConcurrency(anyString(), anyInt(), anyLong());
    }

    @Test
    void apiLimitReachedIsRejectedAndLeaseCoversTimeoutPlusMargin() {
        when(redis.acquireConcurrency(eq("api:1"), eq(3), eq((20 + 30) * 1000L))).thenReturn(null);

        assertThatThrownBy(() -> acquire(api(3, 20), 7L, new ArrayList<>()))
                .isInstanceOfSatisfying(ServiceException.class, ex -> assertThat(ex.getCode())
                        .isEqualTo(DATA_API_CONCURRENCY_LIMITED.getCode()));
    }

    @Test
    void appLimitRejectionKeepsAlreadyAcquiredApiLeaseForRelease() {
        properties.setAppMaxConcurrency(5);
        var apiLease = new SupplyApiRedisStore.ConcurrencyLease("k", "t");
        when(redis.acquireConcurrency(eq("api:1"), eq(3), anyLong())).thenReturn(apiLease);
        when(redis.acquireConcurrency(eq("app:7"), eq(5), anyLong())).thenReturn(null);
        List<SupplyApiRedisStore.ConcurrencyLease> leases = new ArrayList<>();

        assertThatThrownBy(() -> acquire(api(3, null), 7L, leases)).isInstanceOf(ServiceException.class);
        // 已获得的接口租约留在列表中，由 execute 的 finally 统一释放
        assertThat(leases).containsExactly(apiLease);
        ReflectionTestUtils.invokeMethod(executor, "releaseConcurrency", leases);
        verify(redis).releaseConcurrency(apiLease);
    }

    @Test
    void redisFailureFailsClosed() {
        when(redis.acquireConcurrency(anyString(), anyInt(), anyLong())).thenThrow(new IllegalStateException("down"));

        assertThatThrownBy(() -> acquire(api(3, null), 7L, new ArrayList<>()))
                .isInstanceOfSatisfying(ServiceException.class, ex -> assertThat(ex.getCode())
                        .isEqualTo(DATA_API_EXECUTE_FAILED.getCode()));
    }

    @Test
    void snapshotCarriesNewSettingsAndConfigHashIsUnchangedWhenUnset() {
        DataApiDO plain = api(null, null);
        String before = DataApiContract.configHash(plain);
        DataApiDO limited = api(10, 15);
        assertThat(DataApiContract.configHash(limited)).isNotEqualTo(before);

        Map<String, Object> snap = DataApiContract.snapshot(limited);
        DataApiDO restored = new DataApiDO();
        DataApiContract.applySnapshot(restored, snap);
        assertThat(restored.getMaxConcurrency()).isEqualTo(10);
        assertThat(restored.getTimeoutSeconds()).isEqualTo(15);
    }
}
