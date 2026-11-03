package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.api.etl.EtlTargetBatchApi;
import cn.iocoder.yudao.module.dataforge.api.etl.dto.*;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DataApiEtlBatchServiceTest {
    EtlTargetBatchApi api;
    DataApiEtlBatchService service;
    @BeforeEach void setup() { api = mock(EtlTargetBatchApi.class); service = new DataApiEtlBatchService(); ReflectionTestUtils.setField(service, "batches", api); }

    @Test void publishFreezesSpecificBatchAndKeepsOldContractHashesCompatible() {
        var source = source(); var description = description();
        when(api.describe(any())).thenReturn(CommonResult.success(description));
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(7L);
            var prepared = service.prepare(source, false);
            assertNull(source.getEtlBatchHash());
            var frozen = DataApiEtlBatchService.applyPrepared(source, prepared);
            assertEquals(description.getPayloadHash(), frozen.getEtlBatchHash());
            assertEquals("dwd", frozen.getEtlSchemaName()); assertEquals(0, frozen.getCacheEnabled());
            var restored = new DataApiDO(); DataApiContract.applySnapshot(restored, DataApiContract.snapshot(frozen));
            assertEquals(DataApiContract.contractHash(frozen), DataApiContract.contractHash(restored));
            source.setEtlBatchId(11L);
            assertThrows(RuntimeException.class, () -> DataApiEtlBatchService.applyPrepared(source, prepared));
        }
        var old = DataApiDO.builder().configType("TABLE").build(); String oldHash = DataApiContract.contractHash(old);
        old.setEtlBatchId(10L); old.setEtlBatchHash("unrelated-stale-column");
        assertEquals(oldHash, DataApiContract.contractHash(old), "已有 TABLE 合同不因新增空字段变化");
    }

    @Test void unavailableOrDifferentBatchCannotBePublishedAndRollbackCannotRebind() {
        when(api.describe(any())).thenReturn(CommonResult.success(description()));
        var source = source();
        assertThrows(RuntimeException.class, () -> service.prepare(source, true));
        var unavailable = description(); unavailable.setAvailable(false);
        when(api.describe(any())).thenReturn(CommonResult.success(unavailable));
        assertThrows(RuntimeException.class, () -> service.prepare(source, false));
        unavailable.setAvailable(true); unavailable.setBatchId(99L);
        assertThrows(RuntimeException.class, () -> service.prepare(source, false));
    }

    @Test void readsUseGrantedActorExactFrozenIdentityAndCursorWithoutInventingTotal() {
        var source = bound();
        var page = new EtlTargetBatchRowsRespDTO(); page.setRows(List.of(Map.of("amount", "12345678901234567890.01")));
        page.setNextAfterRow(18L); page.setHasMore(true);
        when(api.read(any())).thenReturn(CommonResult.success(page));
        var result = service.read(source, Map.of("afterRow", "17"), 100, 45L);
        var request = ArgumentCaptor.forClass(EtlTargetBatchReadReqDTO.class); verify(api).read(request.capture());
        assertEquals(45L, request.getValue().getExecutionUserId()); assertEquals(17L, request.getValue().getAfterRow());
        assertEquals(10L, request.getValue().getBatchId()); assertEquals("a".repeat(64), request.getValue().getExpectedPayloadHash());
        assertEquals("dwd", request.getValue().getExpectedSchemaName());
        assertEquals("12345678901234567890.01", result.getRows().getFirst().get("amount"));
        assertEquals(18L, result.getNextAfterRow()); assertNull(result.getTotal());
    }

    @Test void rejectsAnonymousWriteOtherConditionsAndUnboundedPages() {
        var source = bound();
        assertThrows(RuntimeException.class, () -> service.read(source, Map.of(), 100, null));
        assertThrows(RuntimeException.class, () -> service.read(source, Map.of("status","PASS"), 100, 1L));
        assertThrows(RuntimeException.class, () -> service.read(source, Map.of("afterRow","1.2"), 100, 1L));
        assertThrows(RuntimeException.class, () -> service.read(source, Map.of(), 201, 1L));
        source.setAuthType("NONE"); assertThrows(RuntimeException.class, () -> DataApiEtlBatchService.validateMode(source));
        source.setAuthType("APPKEY"); source.setApiType("DELETE");
        assertThrows(RuntimeException.class, () -> DataApiReadOnlyGuard.assertAllowed(source, null, DataApiExecuteMode.DRAFT_READONLY_PREVIEW));
        verifyNoInteractions(api);
    }

    @Test void gatewayNeverUsesCachedBatchRowsEvenWhenLegacyCacheSwitchIsEnabled() {
        var executor = new DataApiExecutor(); var source = bound(); source.setCacheEnabled(1); source.setId(1L); source.setActiveReleaseId(2L);
        assertEquals(false, ReflectionTestUtils.invokeMethod(executor,"canUseResultCache",source,DataApiExecuteMode.APP_GATEWAY,null));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"revoked", "revised", "disabled", "unchanged"})
    void gatewayRechecksGrantAndActiveSubjectAfterRead(String change) {
        var executor = new DataApiExecutor(); var source = bound(); source.setId(1L);
        source.setRequestParamsJson("[{\"name\":\"afterRow\",\"paramType\":\"INTEGER\",\"nullable\":1,\"defaultValue\":\"100\"}]");
        var grants = mock(cn.iocoder.yudao.module.supply.dal.mysql.appapi.AppApiMapper.class);
        var apps = mock(cn.iocoder.yudao.module.supply.dal.mysql.app.SupplyAppMapper.class);
        var batches = mock(DataApiEtlBatchService.class);
        var subjects = mock(cn.iocoder.yudao.module.supply.service.app.SupplyEtlGrantPreflight.class);
        ReflectionTestUtils.setField(executor, "appApiMapper", grants);
        ReflectionTestUtils.setField(executor, "supplyAppMapper", apps);
        ReflectionTestUtils.setField(executor, "etlBatches", batches);
        ReflectionTestUtils.setField(executor, "etlGrantSubjects", subjects);
        if ("disabled".equals(change)) doNothing().doThrow(
                cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(
                        cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.DATA_API_NOT_AUTHORIZED))
                .when(subjects).requireActiveSubject(7L);
        var grant = cn.iocoder.yudao.module.supply.dal.dataobject.appapi.AppApiDO.builder()
                .appId(2L).apiId(1L).permissionUserId(7L).grantRevision(1)
                .grantStatus(cn.iocoder.yudao.module.supply.service.app.SupplyGrantValidity.STATUS_ACTIVE)
                .foreverFlag(1).build();
        var app = cn.iocoder.yudao.module.supply.dal.dataobject.app.SupplyAppDO.builder()
                .id(2L)
                .appStatus(cn.iocoder.yudao.module.supply.service.app.SupplyGrantValidity.APP_ENABLED).build();
        when(grants.selectByAppIdAndApiId(2L, 1L)).thenReturn(grant);
        when(apps.selectById(2L)).thenReturn(app);
        when(batches.read(eq(source), anyMap(), eq(20), eq(7L))).thenAnswer(call -> {
            assertEquals(100L, ((Map<?, ?>) call.getArgument(1)).get("afterRow"));
            if ("revoked".equals(change)) grant.setGrantStatus(cn.iocoder.yudao.module.supply.service.app.SupplyGrantValidity.STATUS_REVOKED);
            else if ("revised".equals(change)) grant.setGrantRevision(2);
            var result = new cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiExecuteRespVO();
            result.setRows(List.of(Map.of("amount", "12.30"))); return result;
        });
        if ("unchanged".equals(change)) {
            var result = executor.execute(source, Map.of(), null, 20, "127.0.0.1", false, false,
                    2L, false, DataApiExecuteMode.APP_GATEWAY);
            assertEquals("12.30", result.getRows().getFirst().get("amount"));
        } else {
            var failure = assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                    () -> executor.execute(source, Map.of(), null, 20, "127.0.0.1", false, false,
                            2L, false, DataApiExecuteMode.APP_GATEWAY));
            assertEquals(cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.DATA_API_NOT_AUTHORIZED.getCode(), failure.getCode());
        }
        verify(batches).read(eq(source), anyMap(), eq(20), eq(7L));
        verify(subjects, times(2)).requireActiveSubject(7L);
    }

    @Test void draftPreviewFreezesServerDescriptionBeforeExecutingWithoutPersistingOrUsingSubmittedId() {
        var facade = new DataApiServiceImpl(); var executor = mock(DataApiExecutor.class);
        ReflectionTestUtils.setField(facade,"etlBatches",service); ReflectionTestUtils.setField(facade,"dataApiExecutor",executor);
        when(api.describe(any())).thenReturn(CommonResult.success(description()));
        var config = new cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiSaveReqVO();
        config.setId(123L); config.setConfigType("ETL_BATCH"); config.setEtlBatchId(10L); config.setApiType("QUERY");
        config.setAuthType("APPKEY"); config.setResultFormat("LIST");
        var request = new cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiTestReqVO(); request.setApi(config); request.setPageSize(20); request.setParams(Map.of());
        when(executor.execute(any(),anyMap(),isNull(),eq(20),eq("127.0.0.1"),eq(false),eq(true),isNull(),eq(true),eq(DataApiExecuteMode.DRAFT_READONLY_PREVIEW)))
                .thenAnswer(call->{ var frozen=(DataApiDO)call.getArgument(0); assertNull(frozen.getId()); assertEquals("a".repeat(64),frozen.getEtlBatchHash()); return new cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiExecuteRespVO(); });
        assertNotNull(facade.testDataApi(request,"127.0.0.1")); assertNull(config.getEtlBatchHash());
    }

    private DataApiDO source() { return DataApiDO.builder().configType("ETL_BATCH").apiType("QUERY").resultFormat("LIST").authType("APPKEY").etlBatchId(10L).cacheEnabled(1).build(); }
    private DataApiDO bound() { var s = source(); s.setEtlBatchHash("a".repeat(64)); s.setDatabaseId(3L); s.setEtlSchemaName("dwd"); s.setTableName("orders"); return s; }
    private EtlTargetBatchDescribeRespDTO description() { var d = new EtlTargetBatchDescribeRespDTO(); d.setBatchId(10L); d.setAvailable(true); d.setPayloadHash("a".repeat(64)); d.setDatabaseId(3L); d.setSchemaName("dwd"); d.setTableName("orders"); return d; }
}
