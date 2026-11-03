package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO.Binding;
import cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiExecuteRespVO;
import cn.iocoder.yudao.module.supply.dal.dataobject.appapi.AppApiDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.AppApiMapper;
import cn.iocoder.yudao.module.supply.framework.config.SupplyDataApiProperties;
import cn.iocoder.yudao.module.supply.service.app.grant.EffectiveGrantSnapshot;
import cn.iocoder.yudao.module.supply.service.app.grant.GrantSourceState;
import cn.iocoder.yudao.module.supply.service.app.grant.SupplyGrantReadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 网关执行使用同一份来源快照：批准字段在数据库执行前生效，返回前重验，字段受限来源不进缓存。
 */
class DataApiSourceScopeTest {

    private static final long APP_ID = 2L;
    private static final long API_ID = 1L;

    private DataApiExecutor executor;
    private SupplyGrantReadService grantReadService;
    private SupplyApiRedisStore redisStore;
    private AppApiMapper appApiMapper;
    private SupplyDatabaseResolver databaseResolver;
    private final List<String> executedSql = new ArrayList<>();
    private Integer executedQuotaLimit;

    /** 只替换真实取数这一步，其余执行链路保持生产实现。 */
    private class RecordingExecutor extends DataApiExecutor {
        @Override
        DataApiExecuteRespVO queryDatabase(DataApiDO api, String sql, List<Object> binds,
                                           Integer pageNo, Integer pageSize, ExecuteContext permissionCtx) {
            executedSql.add(sql);
            executedQuotaLimit = permissionCtx.quotaLimit();
            DataApiExecuteRespVO result = new DataApiExecuteRespVO();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", 1L);
            result.setRows(List.of(row));
            return result;
        }
    }

    private static EffectiveGrantSnapshot snapshot(List<String> approvedColumns, boolean fieldScoped) {
        return new EffectiveGrantSnapshot( APP_ID, API_ID, 7L, 9L,
                new Binding(3L, "public", "citizen"), tableApi().getContractHash(), "v1", approvedColumns,
                Map.of(new GrantSourceState.Identity("asset", "asset:apply:7"), 2L),
                Instant.ofEpochMilli(1_000L), null, fieldScoped, 4);
    }

    private static EffectiveGrantSnapshot quotaSnapshot() {
        var base = snapshot(List.of("id"), true);
        var source = new cn.iocoder.yudao.module.supply.api.app.dto.SupplyQuotaDTO.Source("asset","asset:apply:7",
                new cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO.Quota("ROWS", 5), List.of("id"));
        return new EffectiveGrantSnapshot(base.appId(),base.apiId(),base.aggregateRevision(),base.permissionUserId(),
                base.binding(),base.contractHash(),base.apiVersion(),base.approvedColumns(),base.sourceVersions(),
                base.evaluatedAt(),base.nextBoundary(),base.fieldScoped(),base.apiControlVersion(),List.of(source));
    }

    @Test void gatewayReservesBeforeReadingCapsRowsAndOnlyCommitsAfterGrantRevalidation() {
        var quota=mock(cn.iocoder.yudao.module.supply.service.app.SupplyQuotaService.class);
        ReflectionTestUtils.setField(executor,"quotaService",quota);
        when(grantReadService.snapshot(APP_ID,API_ID)).thenReturn(quotaSnapshot());
        var reserved=new cn.iocoder.yudao.module.supply.api.app.dto.SupplyQuotaDTO.Receipt("API:2:1","consume-0001","RESERVED","token",3,null,List.of());
        when(quota.reserve(any())).thenAnswer(call -> { assertThat(executedSql).isEmpty(); return reserved; });
        when(quota.settle(any())).thenReturn(new cn.iocoder.yudao.module.supply.api.app.dto.SupplyQuotaDTO.Receipt("API:2:1","consume-0001","COMMITTED",null,3,1,List.of()));
        var result=executeThroughGateway(Map.of("_consumeKey","consume-0001"));
        assertThat(executedQuotaLimit).isEqualTo(3); assertThat(result.getConsumptionState()).isEqualTo("COMMITTED");
        assertThat(result.getConsumptionRows()).isEqualTo(1);
        var ordered=org.mockito.Mockito.inOrder(quota,grantReadService);
        ordered.verify(grantReadService).snapshot(APP_ID,API_ID);
        ordered.verify(quota).reserve(org.mockito.ArgumentMatchers.argThat(r -> r.actorId()==9 && r.columns().equals(List.of("id"))));
        ordered.verify(grantReadService).assertStillCurrent(any());
        ordered.verify(quota).settle(new cn.iocoder.yudao.module.supply.api.app.dto.SupplyQuotaDTO.Settle("API:2:1","consume-0001","token",1,false));
        verify(redisStore,never()).getResult(any());
    }
    @Test void exhaustedQuotaNeverReachesDatabaseAndStatusReadDoesNotConsumeAgain() {
        var quota=mock(cn.iocoder.yudao.module.supply.service.app.SupplyQuotaService.class);
        ReflectionTestUtils.setField(executor,"quotaService",quota);
        when(grantReadService.snapshot(APP_ID,API_ID)).thenReturn(quotaSnapshot());
        when(quota.reserve(any())).thenThrow(new ServiceException(1_500_042_020,"额度不足"));
        assertThatThrownBy(() -> executeThroughGateway(Map.of("_consumeKey","consume-0001"))).hasMessageContaining("额度不足");
        when(quota.receipt("API:2:1","consume-0001",9)).thenReturn(
                new cn.iocoder.yudao.module.supply.api.app.dto.SupplyQuotaDTO.Receipt("API:2:1","consume-0001","COMMITTED",null,3,1,List.of()));
        var result=executeThroughGateway(Map.of("_consumeKey","consume-0001","_consumeMode","STATUS"));
        assertThat(result.getRows()).isEmpty(); assertThat(result.getConsumptionState()).isEqualTo("COMMITTED");
        assertThat(executedSql).isEmpty(); verify(quota,never()).settle(any());
    }
    @Test void grantRevocationAfterReadCancelsReservationAndDiscardsRows() {
        var quota=mock(cn.iocoder.yudao.module.supply.service.app.SupplyQuotaService.class);
        ReflectionTestUtils.setField(executor,"quotaService",quota);
        when(grantReadService.snapshot(APP_ID,API_ID)).thenReturn(quotaSnapshot());
        when(quota.reserve(any())).thenReturn(new cn.iocoder.yudao.module.supply.api.app.dto.SupplyQuotaDTO.Receipt("API:2:1","consume-0001","RESERVED","token",3,null,List.of()));
        doThrow(new ServiceException(1_500_042_010,"授权已变化")).when(grantReadService).assertStillCurrent(any());
        assertThatThrownBy(() -> executeThroughGateway(Map.of("_consumeKey","consume-0001"))).hasMessageContaining("授权已变化");
        verify(quota).settle(new cn.iocoder.yudao.module.supply.api.app.dto.SupplyQuotaDTO.Settle("API:2:1","consume-0001","token",null,true));
        verify(quota,never()).settle(org.mockito.ArgumentMatchers.argThat(r -> !r.cancel()));
    }

    /** API 定义本身声明了 salary 过滤参数：越权必须在执行前被拒绝，而不是靠参数未声明侥幸拦下。 */
    private static DataApiDO tableApi() {
        DataApiDO api = DataApiDO.builder().id(API_ID).name("citizen").apiType("QUERY")
                .configType("TABLE").databaseId(3L).tableName("citizen").status(1)
                .activeReleaseId(5L).rowVersion(4).version("v1").cacheEnabled(1)
                .requestParamsJson("[{\"name\":\"salary\",\"nullable\":1,\"paramType\":\"STRING\"},"
                        + "{\"name\":\"phone\",\"nullable\":1,\"paramType\":\"STRING\"}]")
                .build();
        api.setContractHash(DataApiContract.contractHash(api));
        return api;
    }

    @BeforeEach
    void setUp() {
        executor = new RecordingExecutor();
        grantReadService = mock(SupplyGrantReadService.class);
        redisStore = mock(SupplyApiRedisStore.class);
        appApiMapper = mock(AppApiMapper.class);
        databaseResolver = mock(SupplyDatabaseResolver.class);
        SupplyDatabaseResolver.ConnectionInfo connection = new SupplyDatabaseResolver.ConnectionInfo();
        connection.setDatabaseId(3L);
        connection.setProductType(ProductTypeEnum.POSTGRESQL);
        when(databaseResolver.resolve(anyLong())).thenReturn(connection);
        when(appApiMapper.selectByAppIdAndApiId(APP_ID, API_ID)).thenReturn(AppApiDO.builder()
                .id(4L).appId(APP_ID).apiId(API_ID).protocolVersion(2).build());
        SupplyDataApiProperties properties = new SupplyDataApiProperties();
        properties.setResultCacheEnabled(true);
        ReflectionTestUtils.setField(executor, "grantReadService", grantReadService);
        ReflectionTestUtils.setField(executor, "redisStore", redisStore);
        ReflectionTestUtils.setField(executor, "appApiMapper", appApiMapper);
        ReflectionTestUtils.setField(executor, "databaseResolver", databaseResolver);
        ReflectionTestUtils.setField(executor, "supplyAppMapper", mock(cn.iocoder.yudao.module.supply.dal.mysql.app.SupplyAppMapper.class));
        ReflectionTestUtils.setField(executor, "dataApiProperties", properties);
    }

    private DataApiExecuteRespVO executeThroughGateway(Map<String, Object> params) {
        return executor.execute(tableApi(), params, null, null, "127.0.0.1", false, false, APP_ID);
    }

    @Test
    void approvedColumnsAreProjectedBeforeTheDatabaseIsQueried() {
        when(grantReadService.snapshot( APP_ID, API_ID))
                .thenReturn(snapshot(List.of("id", "phone"), true));
        assertThat(executeThroughGateway(Map.of()).getRows()).hasSize(1);
        assertThat(executedSql).hasSize(1);
        assertThat(executedSql.getFirst()).doesNotContain("*").contains("\"id\"").contains("\"phone\"");
        assertThat(executedSql.getFirst()).doesNotContain("salary");
        verify(grantReadService).assertStillCurrent(any());
    }

    @Test
    void unapprovedFilterNeverReachesTheDatabase() {
        when(grantReadService.snapshot( APP_ID, API_ID))
                .thenReturn(snapshot(List.of("id"), true));
        assertThatThrownBy(() -> executeThroughGateway(Map.of("salary", "10000")))
                .isInstanceOf(ServiceException.class);
        assertThat(executedSql).isEmpty();
    }

    @Test
    void authorizationChangedDuringTheQueryDiscardsTheResult() {
        when(grantReadService.snapshot( APP_ID, API_ID))
                .thenReturn(snapshot(List.of("id", "phone"), true));
        doThrow(new ServiceException(1_500_042_010, "授权已变化"))
                .when(grantReadService).assertStillCurrent(any());
        assertThatThrownBy(() -> executeThroughGateway(Map.of()))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> assertThat(((ServiceException) ex).getCode()).isEqualTo(1_500_042_010));
        // 查询已经执行，但结果不允许返回。
        assertThat(executedSql).hasSize(1);
        verify(redisStore, never()).putResult(any(), any(), anyLong());
    }

    @Test
    void fieldScopedSourceNeverUsesTheResultCache() {
        when(grantReadService.snapshot( APP_ID, API_ID))
                .thenReturn(snapshot(List.of("id"), true));
        executeThroughGateway(Map.of());
        verify(redisStore, never()).putResult(any(), any(), anyLong());
        verify(redisStore, never()).getResult(any());
    }

    @Test
    void missingOrUnconfirmedGrantIsRejectedBeforeExecution() {
        when(grantReadService.snapshot( APP_ID, API_ID))
                .thenThrow(new ServiceException(1_500_042_009, "当前没有有效的来源授权"));
        assertThatThrownBy(() -> executeThroughGateway(Map.of()))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> assertThat(((ServiceException) ex).getCode()).isEqualTo(1_500_042_009));
        assertThat(executedSql).isEmpty();
    }

    @Test
    void legacyAggregateKeepsItsOwnValidation() {
        when(appApiMapper.selectByAppIdAndApiId(APP_ID, API_ID)).thenReturn(AppApiDO.builder()
                .id(4L).appId(APP_ID).apiId(API_ID).protocolVersion(0).build());
        assertThatThrownBy(() -> executeThroughGateway(Map.of())).isInstanceOf(ServiceException.class);
        verify(grantReadService, never()).snapshot( anyLong(), anyLong());
        assertThat(executedSql).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"contract", "missingContract", "version", "missingVersion", "rowVersion",
            "missingRowVersion", "database", "table", "release", "offline",
            "responseFields", "requestParams", "resourceRef"})
    void runtimeMustMatchTheAuthoritativeGrantBeforeAnyQuery(String changed) {
        DataApiDO stale = tableApi();
        switch (changed) {
            case "contract" -> stale.setContractHash("previous-contract");
            case "missingContract" -> stale.setContractHash(null);
            case "version" -> stale.setVersion("previous-version");
            case "missingVersion" -> stale.setVersion(null);
            case "rowVersion" -> stale.setRowVersion(3);
            case "missingRowVersion" -> stale.setRowVersion(null);
            case "database" -> stale.setDatabaseId(99L);
            case "table" -> stale.setTableName("other_table");
            case "release" -> stale.setActiveReleaseId(null);
            case "offline" -> stale.setStatus(0);
            // Stored hash labels alone cannot prove that the executed configuration is the approved one.
            case "responseFields" -> stale.setResponseFieldsJson("[{\"name\":\"id\",\"model\":\"HIDE\"}]");
            case "requestParams" -> stale.setRequestParamsJson("[]");
            case "resourceRef" -> stale.setResourceRef("other-resource");
            default -> throw new AssertionError(changed);
        }
        when(grantReadService.snapshot( APP_ID, API_ID))
                .thenReturn(snapshot(List.of("id"), true));

        assertThatThrownBy(() -> executor.execute(stale, Map.of(), null, null, "127.0.0.1", false, false, APP_ID))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> assertThat(((ServiceException) ex).getCode()).isEqualTo(1_500_042_010));
        assertThat(executedSql).isEmpty();
        verify(grantReadService, never()).assertStillCurrent(any());
        verify(redisStore, never()).getResult(any());
        verify(redisStore, never()).putResult(any(), any(), anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "app", "api", "fieldScoped", "controlVersion", "binding"})
    void incompleteOrMisroutedSnapshotCannotFallBackToLegacyOrCache(String changed) {
        EffectiveGrantSnapshot valid = snapshot(List.of("id"), true);
        EffectiveGrantSnapshot invalid = "null".equals(changed) ? null : new EffectiveGrantSnapshot(
                "app".equals(changed) ? 99L : valid.appId(),
                "api".equals(changed) ? 99L : valid.apiId(), valid.aggregateRevision(), valid.permissionUserId(),
                "binding".equals(changed) ? null : valid.binding(), valid.contractHash(), valid.apiVersion(),
                valid.approvedColumns(), valid.sourceVersions(), valid.evaluatedAt(), valid.nextBoundary(),
                !"fieldScoped".equals(changed), "controlVersion".equals(changed) ? null : valid.apiControlVersion());
        when(grantReadService.snapshot( APP_ID, API_ID)).thenReturn(invalid);

        assertThatThrownBy(() -> executeThroughGateway(Map.of()))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> assertThat(((ServiceException) ex).getCode()).isEqualTo(1_500_042_010));
        assertThat(executedSql).isEmpty();
        verify(redisStore, never()).getResult(any());
        verify(redisStore, never()).putResult(any(), any(), anyLong());
    }

    @Test
    void nonContractDisplayNameDoesNotInvalidateTheSamePublishedRuntime() {
        DataApiDO api = tableApi();
        api.setName("Updated display name");
        when(grantReadService.snapshot( APP_ID, API_ID)).thenReturn(snapshot(List.of("id"), true));

        assertThat(executor.execute(api, Map.of(), null, null, "127.0.0.1", false, false, APP_ID).getRows())
                .hasSize(1);
        assertThat(executedSql).hasSize(1);
        verify(grantReadService).assertStillCurrent(any());
    }
}
