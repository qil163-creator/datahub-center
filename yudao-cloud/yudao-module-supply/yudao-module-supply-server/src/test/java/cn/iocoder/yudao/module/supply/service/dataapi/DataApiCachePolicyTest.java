package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.governance.api.security.SafetySecurityApi;
import cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiExecuteRespVO;
import cn.iocoder.yudao.module.supply.dal.dataobject.app.SupplyAppDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.appapi.AppApiDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.dal.mysql.app.SupplyAppMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.AppApiMapper;
import cn.iocoder.yudao.module.supply.framework.config.SupplyDataApiProperties;
import cn.iocoder.yudao.module.supply.service.app.SupplyGrantValidity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证发布版本、调用主体和应用授权对结果缓存的隔离。 */
class DataApiCachePolicyTest {
    private final Map<String, Object> cache = new HashMap<>();
    private final SupplyApiRedisStore redis = mock(SupplyApiRedisStore.class);
    private final ProbeExecutor executor = new ProbeExecutor();
    private MockedStatic<SecurityFrameworkUtils> security;

    @BeforeEach
    void setUp() {
        security = mockStatic(SecurityFrameworkUtils.class);
        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
        var properties = new SupplyDataApiProperties();
        properties.setResultCacheEnabled(true);
        ReflectionTestUtils.setField(executor, "dataApiProperties", properties);
        ReflectionTestUtils.setField(executor, "redisStore", redis);
        ReflectionTestUtils.setField(executor, "safetySecurityApi", mock(SafetySecurityApi.class));
        var resolver = mock(SupplyDatabaseResolver.class);
        var connection = new SupplyDatabaseResolver.ConnectionInfo();
        connection.setProductType(ProductTypeEnum.POSTGRESQL);
        when(resolver.resolve(4L)).thenReturn(connection);
        ReflectionTestUtils.setField(executor, "databaseResolver", resolver);
        when(redis.getResult(anyString())).thenAnswer(call -> cache.get(call.getArgument(0)));
        doAnswer(call -> { cache.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(redis).putResult(anyString(), any(), anyLong());
    }

    @AfterEach
    void clearContext() {
        security.close();
    }

    @Test
    void repeatedQueryUsesCacheButAnotherCallerGetsAnIsolatedEntry() {
        assertThat(invoke(true).getRows()).containsExactly(Map.of("id", 1));
        assertThat(invoke(true).getRows()).containsExactly(Map.of("id", 1));
        assertThat(executor.reads).isEqualTo(1);
        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(10L);
        assertThat(invoke(true).getRows()).containsExactly(Map.of("id", 2));
        assertThat(executor.reads).isEqualTo(2);
        assertThat(cache).hasSize(2);
    }

    @Test
    void appGrantRevocationWhileCachedResultIsBeingReadDiscardsRowsWithoutAnotherQuery() {
        var apps = mock(SupplyAppMapper.class);
        var grants = mock(AppApiMapper.class);
        var app = SupplyAppDO.builder().id(2L)
                .appStatus(SupplyGrantValidity.APP_ENABLED).build();
        var grant = AppApiDO.builder().id(7L).appId(2L).apiId(5L)
                .permissionUserId(9L).protocolVersion(0).foreverFlag(1)
                .grantStatus(SupplyGrantValidity.STATUS_ACTIVE).grantRevision(1).build();
        when(apps.selectById(2L)).thenReturn(app);
        when(grants.selectByAppIdAndApiId(2L, 5L)).thenReturn(grant);
        ReflectionTestUtils.setField(executor, "supplyAppMapper", apps);
        ReflectionTestUtils.setField(executor, "appApiMapper", grants);

        assertThat(invoke(false, 2L).getRows()).containsExactly(Map.of("id", 1));
        assertThat(cache).hasSize(1);
        clearInvocations(redis);
        doAnswer(call -> {
            Object cached = cache.get(call.getArgument(0));
            assertThat(cached).isNotNull();
            // 应用授权在 Redis 返回旧结果前被撤销。
            grant.setGrantStatus(SupplyGrantValidity.STATUS_REVOKED);
            return cached;
        }).when(redis).getResult(anyString());

        assertThatThrownBy(() -> invoke(false, 2L)).isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getCode())
                        .isEqualTo(cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.DATA_API_NOT_AUTHORIZED.getCode()));
        verify(redis).getResult(anyString());
        verify(redis, never()).putResult(anyString(), any(), anyLong());
        assertThat(executor.reads).isEqualTo(1);
    }

    @Test
    void publicQueryCachingRemainsAvailable() {
        invoke(false);
        invoke(false);
        assertThat(executor.reads).isEqualTo(1);
    }

    @Test
    void quotaBearingSnapshotCannotBypassConsumptionThroughCachedResults() {
        var api = DataApiDO.builder().id(5L).activeReleaseId(6L)
                .configType(DataApiDO.CONFIG_TYPE_TABLE).apiType(DataApiDO.API_TYPE_QUERY)
                .cacheEnabled(1).build();
        var identity = new cn.iocoder.yudao.module.supply.service.app.grant.GrantSourceState.Identity("asset", "grant:7");
        var quota = new cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO.Quota("ROWS", 5);
        var source = new cn.iocoder.yudao.module.supply.api.app.dto.SupplyQuotaDTO.Source("asset", "grant:7", quota, List.of("id"));
        var snapshot = new cn.iocoder.yudao.module.supply.service.app.grant.EffectiveGrantSnapshot( 2L, 5L, 7L, 9L, null, "contract", "v1", List.of("id"), Map.of(identity, 1L),
                Instant.EPOCH, null, false, 1, List.of(source));

        // 即使没有字段限制，额度来源也不得被提前返回的缓存分支绕过。
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(executor, "canUseResultCache", api,
                DataApiExecuteMode.APP_GATEWAY, snapshot)).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(executor, "canUseResultCache", api,
                DataApiExecuteMode.APP_GATEWAY, null)).isTrue();
    }

    private DataApiExecuteRespVO invoke(boolean personal) {
        return invoke(personal, null);
    }

    private DataApiExecuteRespVO invoke(boolean personal, Long appId) {
        var api = DataApiDO.builder().id(5L).activeReleaseId(6L)
                .configType(DataApiDO.CONFIG_TYPE_TABLE).apiType(DataApiDO.API_TYPE_QUERY)
                .databaseId(4L).tableName("public.orders").requestParamsJson("[]").responseFieldsJson("[]")
                .cacheEnabled(1).cacheSeconds(60).build();
        return executor.execute(api, Map.of(), 1, 20, "127.0.0.1", false, false,
                appId, personal, personal ? DataApiExecuteMode.ADMIN_EXECUTE : DataApiExecuteMode.APP_GATEWAY);
    }

    private static final class ProbeExecutor extends DataApiExecutor {
        private int reads;
        @Override
        DataApiExecuteRespVO queryDatabase(DataApiDO api, String sql, List<Object> binds,
                Integer pageNo, Integer pageSize, ExecuteContext context) {
            reads++;
            var result = new DataApiExecuteRespVO();
            result.setRows(List.of(Map.of("id", reads)));
            return result;
        }
    }
}
