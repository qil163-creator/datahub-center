package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketApplyRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketApplySaveReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCheckReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryRecheckReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketCheckProcessDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDeliveryOperationDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDemandDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceEndpointDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketCheckProcessMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketApprovalEvidenceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketApprovalEvidenceRow;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDemandMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceEndpointMapper;
import cn.iocoder.yudao.module.asset.service.monitor.AssetMonitorService;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
import cn.iocoder.yudao.module.supply.api.app.SupplyAppApi;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyAppRespDTO;
import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyApiBindingRespDTO;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;
import java.util.List;
import java.time.LocalDateTime;
import java.util.function.Consumer;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.DATA_ASSET_NOT_EXISTS;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_API_ASSET_MISMATCH;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_API_NOT_BOUND;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_API_TYPE_UNSUPPORTED;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_APP_FORBIDDEN;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_ASSET_NOT_PUBLISHED;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_DUPLICATE;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_FIELD_SCOPE_INVALID;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_METADATA_UNAVAILABLE;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_QUOTA_UNSUPPORTED;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_STATUS_DENY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@MockitoSettings(strictness = Strictness.LENIENT)
class MarketApplyServiceImplTest extends BaseMockitoUnitTest {

    @BeforeEach
    void establishTenant() {
        cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.clear();
        org.springframework.test.util.ReflectionTestUtils.setField(accessService, "applyMapper", applyMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(deliveryService, "operationMapper",
                deliveryOperationMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(deliveryService, "approvalEvidenceMapper",
                approvalEvidenceMapper);
        // 详情使用真实只读交付规则；既有写流程测试仍隔离入队副作用。
        doNothing().when(deliveryService).enqueueApprovedGrant(any(), any(), any(), any());
        doNothing().when(deliveryService).enqueueRevoke(any());
        when(applyMapper.databaseNow()).thenReturn(GRANT_NOW);
        when(catalogService.requireCompletePublishedAsset(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(assetMapper.selectForMatching( org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(invocation -> assetMapper.selectById((Long) invocation.getArgument(0)));
    }

    @AfterEach
    void clearTenant() {
        cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.clear();
    }

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                MarketResourceApplyDO.class);
    }

    @InjectMocks
    private MarketApplyServiceImpl service;
    @Mock
    private MarketResourceApplyMapper applyMapper;
    @Mock
    private MarketCheckProcessMapper processMapper;
    @Mock
    private DataAssetMapper assetMapper;
    @Mock
    private MarketResourceEndpointMapper endpointMapper;
    @Mock
    private MarketDisplayHelper displayHelper;
    @Spy
    private MarketAccessServiceImpl accessService = new MarketAccessServiceImpl();
    @Mock
    private MarketObjectGuard objectGuard;
    @Mock
    private SupplyAppApi supplyAppApi;
    @Mock
    private MarketSupplyApiGuard supplyApiGuard;
    @Mock
    private MarketNotifyHelper notifyHelper;
    @Spy
    private MarketDeliveryService deliveryService = new MarketDeliveryService();
    @Mock
    private MarketApprovalEvidenceMapper approvalEvidenceMapper;
    @Mock
    private MarketDeliveryCoordinator deliveryCoordinator;
    @Mock
    private MarketDeliveryEvidenceService deliveryEvidenceService;
    @Mock
    private MarketWorkingDayService workingDayService;
    @Mock
    private MarketAuditRecorder auditRecorder;
    @Mock
    private MarketDemandMapper demandMapper;
    @Mock
    private AssetCatalogService catalogService;
    @Mock
    private AdminUserApi adminUserApi;
    @Mock
    private AssetMonitorService assetMonitorService;
    @Mock
    private cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryOperationMapper deliveryOperationMapper;

    @Test
    void submitLimitedDraftStillRequiresPublishedAsset() {
        var apply=MarketResourceApplyDO.builder().id(7L).applyUserId(3L).status(MarketResourceApplyDO.STATUS_DRAFT)
                .applyUseType(1).applyUseTimes(10).build();
        when(applyMapper.selectById(7L)).thenReturn(apply);
        when(objectGuard.requireUserId()).thenReturn(3L);
        var error=assertThrows(ServiceException.class, () -> service.submitApply(7L));
        assertEquals(cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.DATA_ASSET_NOT_EXISTS.getCode(), error.getCode());
        verifyNoInteractions(deliveryCoordinator, processMapper);
    }

    @Test
    void createApply_rejectsUnboundApi() {
        when(objectGuard.requireUserId()).thenReturn(3L);
        when(assetMapper.selectById(8L)).thenReturn(publishedTable());
        when(applyMapper.selectActiveByUserAndAsset(3L, 8L)).thenReturn(null);
        when(endpointMapper.selectByAssetAndApi(8L, 9L)).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.createApply(apiReq()));
        assertEquals(MARKET_APPLY_API_NOT_BOUND.getCode(), ex.getCode());
    }

    @Test
    void createApply_rejectsSqlApiEvenIfBound() {
        when(objectGuard.requireUserId()).thenReturn(3L);
        when(assetMapper.selectById(8L)).thenReturn(publishedTable());
        when(applyMapper.selectActiveByUserAndAsset(3L, 8L)).thenReturn(null);
        when(endpointMapper.selectByAssetAndApi(8L, 9L)).thenReturn(new MarketResourceEndpointDO());
        doThrow(new ServiceException(MARKET_APPLY_API_TYPE_UNSUPPORTED))
                .when(supplyApiGuard).requireQueryableTableApi(9L);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.createApply(apiReq()));
        assertEquals(MARKET_APPLY_API_TYPE_UNSUPPORTED.getCode(), ex.getCode());
    }

    @Test
    void createApply_rejectsMismatchedTable() {
        when(objectGuard.requireUserId()).thenReturn(3L);
        DataAssetDO asset = publishedTable();
        when(assetMapper.selectById(8L)).thenReturn(asset);
        when(applyMapper.selectActiveByUserAndAsset(3L, 8L)).thenReturn(null);
        when(endpointMapper.selectByAssetAndApi(8L, 9L)).thenReturn(new MarketResourceEndpointDO());
        SupplyApiBindingRespDTO binding = tableQueryBinding();
        when(supplyApiGuard.requireQueryableTableApi(9L)).thenReturn(binding);
        doThrow(new ServiceException(MARKET_APPLY_API_ASSET_MISMATCH))
                .when(supplyApiGuard).requireMatchesAsset(asset, binding);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.createApply(apiReq()));
        assertEquals(MARKET_APPLY_API_ASSET_MISMATCH.getCode(), ex.getCode());
    }

    @Test
    void createApply_rejectsOthersApp() {
        when(objectGuard.requireUserId()).thenReturn(3L);
        DataAssetDO asset = publishedTable();
        when(assetMapper.selectById(8L)).thenReturn(asset);
        when(applyMapper.selectActiveByUserAndAsset(3L, 8L)).thenReturn(null);
        when(endpointMapper.selectByAssetAndApi(8L, 9L)).thenReturn(new MarketResourceEndpointDO());
        when(supplyApiGuard.requireQueryableTableApi(9L)).thenReturn(tableQueryBinding());
        when(supplyAppApi.listAppsByCreator(3L)).thenReturn(CommonResult.success(List.of()));
        ServiceException ex = assertThrows(ServiceException.class, () -> service.createApply(apiReq()));
        assertEquals(MARKET_APPLY_APP_FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    void getApply_nullCheckerDoesNotThrow() {
        MarketResourceApplyDO apply = new MarketResourceApplyDO();
        apply.setId(1L);
        apply.setAssetId(8L);
        apply.setApplyUserId(3L);
        apply.setStatus(MarketResourceApplyDO.STATUS_PENDING);
        when(applyMapper.selectById(1L)).thenReturn(apply);
        when(assetMapper.selectById(8L)).thenReturn(publishedTable());
        when(processMapper.selectListByApplyId(1L)).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(displayHelper.collect(anyCollection(), any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(displayHelper.userNames(any(Long[].class)))
                .thenReturn(java.util.Map.of(3L, "申请人"));
        org.mockito.Mockito.lenient().when(displayHelper.userLabel(any(), any())).thenReturn("申请人");
        org.mockito.Mockito.lenient().when(displayHelper.deptNames(any(Long[].class)))
                .thenReturn(java.util.Map.of());
        org.mockito.Mockito.lenient().when(displayHelper.deptLabel(any(), any())).thenReturn("部门");
        org.mockito.Mockito.lenient().when(displayHelper.assets(anyCollection())).thenReturn(java.util.Map.of());
        org.mockito.Mockito.lenient().when(displayHelper.deptNames(org.mockito.ArgumentMatchers.nullable(Long.class)))
                .thenReturn(java.util.Map.of());
        service.getApply(1L);
    }

    @Test
    void pauseApply_rejectsWhenAlreadyPaused() {
        MarketResourceApplyDO apply = new MarketResourceApplyDO();
        apply.setId(1L);
        apply.setAssetId(8L);
        apply.setStatus(MarketResourceApplyDO.STATUS_APPROVED);
        apply.setPaused(1);
        when(applyMapper.selectById(1L)).thenReturn(apply);
        when(assetMapper.selectById(8L)).thenReturn(publishedTable());
        ServiceException ex = assertThrows(ServiceException.class, () -> service.pauseApply(1L, "pause"));
        assertEquals(MARKET_APPLY_STATUS_DENY.getCode(), ex.getCode());
    }

    @Test
    void createApply_rejectsQuotaPromise() {
        when(objectGuard.requireUserId()).thenReturn(3L);
        when(assetMapper.selectById(8L)).thenReturn(publishedTable());
        when(applyMapper.selectActiveByUserAndAsset(3L, 8L)).thenReturn(null);
        MarketApplySaveReqVO req = apiReq();
        req.setApiId(null);
        req.setAppId(null);
        req.setApplyUseType(MarketResourceApplyDO.USE_LIMITED);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.createApply(req));
        assertEquals(MARKET_APPLY_QUOTA_UNSUPPORTED.getCode(), ex.getCode());
    }

    @ParameterizedTest
    @CsvSource({"2,0", "-1,0", "0,19", "0,-1"})
    void createApplyRejectsUnknownOrLimitedPolicyBeforeInsert(int type, int times) {
        var req=stubInitialApply(false);
        req.setApplyUseType(type); req.setApplyUseTimes(times);
        assertEquals(MARKET_APPLY_QUOTA_UNSUPPORTED.getCode(), assertThrows(ServiceException.class,()->service.createApply(req)).getCode());
        verifyNoInteractions(processMapper, auditRecorder);
    }

    @Test
    void createApply_mapsConcurrentUniqueConflictToBusinessDuplicate() {
        MarketApplySaveReqVO req = stubInitialApply(false);
        stubInitialApplyInsert(req, 0, null);
        when(applyMapper.hasActivePendingApply( 3L, 8L)).thenReturn(true);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.createApply(req));

        assertEquals(MARKET_APPLY_DUPLICATE.getCode(), ex.getCode());
        var calls = verifyInitialApplyInsertion(false);
        calls.verify(applyMapper).hasActivePendingApply( 3L, 8L);
        verify(catalogService, org.mockito.Mockito.atMostOnce()).requireCompletePublishedAsset(any());
        verifyNoMoreInteractions(applyMapper, catalogService);
        verifyNoInteractions(processMapper);
        assertNoInitialApplySuccessEffects();
    }

    @ParameterizedTest
    @CsvSource(value = {"false,false", "true,false", "NULL,false",
            "false,true", "true,true", "NULL,true"}, nullValues = "NULL")
    void createApply_mapsRequestAndFreezesSubmittedSnapshotBeforeSingleInsert(Boolean submit, boolean withApi) {
        MarketApplySaveReqVO req = stubInitialApply(submit);
        if (withApi) {
            req.setApiId(9L);
            req.setAppId(4L);
            stubInitialApiBinding();
        }
        stubInitialApplyInsert(req, 1, 77L);

        assertEquals(77L, service.createApply(req));

        boolean submitted = !Boolean.FALSE.equals(submit);
        verifyInitialApplyInsertion(submitted);
        if (submitted) {
            verifyInitialApplySubmitEffects(77L);
        } else {
            verifyNoInteractions(processMapper, workingDayService);
            assertNoInitialApplySuccessEffects();
        }
        verify(catalogService, org.mockito.Mockito.atMostOnce()).requireCompletePublishedAsset(any());
        verifyNoMoreInteractions(applyMapper, catalogService, processMapper, notifyHelper, auditRecorder);
        // INSERT 入参断言在 Answer 内执行，不能由后来修改同一对象制造“已冻结”的假象。
    }

    @Test
    void createApply_propagatesIdentityFailureBeforeReadingAsset() {
        var failure = new IllegalStateException("test missing user identity");
        when(objectGuard.requireUserId()).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.createApply(apiReq())));

        verify(objectGuard).requireUserId();
        verifyNoMoreInteractions(objectGuard);
        verifyNoInteractions(assetMapper, applyMapper, processMapper, displayHelper, endpointMapper,
                supplyAppApi, supplyApiGuard, adminUserApi, catalogService, workingDayService);
        assertNoInitialApplySuccessEffects();
    }

    @ParameterizedTest
    @CsvSource({"0,false", "0,true", "1,false", "1,true", "8,false", "8,true"})
    void createApply_rejectsInitiallyActiveApplicationWithoutAllocatingOrWriting(int status, boolean submit) {
        MarketApplySaveReqVO req = stubInitialApply(submit);
        when(applyMapper.selectActiveByUserAndAsset(3L, 8L)).thenReturn(reusableDemandApply(status));

        assertEquals(MARKET_APPLY_DUPLICATE.getCode(),
                assertThrows(ServiceException.class, () -> service.createApply(req)).getCode());

        verify(applyMapper).selectActiveByUserAndAsset(3L, 8L);
        verifyNoMoreInteractions(applyMapper);
        verifyNoInteractions(processMapper, displayHelper, endpointMapper, supplyAppApi, supplyApiGuard,
                adminUserApi, workingDayService);
        verify(catalogService).requireCompletePublishedAsset(any());
        verifyNoMoreInteractions(catalogService);
        assertNoInitialApplySuccessEffects();
    }

    @ParameterizedTest
    @CsvSource({"0,false,false", "0,false,true", "0,true,false", "0,true,true",
            "1,false,false", "1,false,true", "1,true,false", "1,true,true",
            "9007199254740993,false,false", "9007199254740993,false,true",
            "9007199254740993,true,false", "9007199254740993,true,true",
            "9223372036854775807,false,false", "9223372036854775807,false,true",
            "9223372036854775807,true,false", "9223372036854775807,true,true"})
    void createApply_classifiesZeroInsertByTrustedTenantWithoutRetry(
            long tenantId, boolean submit, boolean competitorPresent) {
        TenantContextHolder.clear();
        MarketApplySaveReqVO req = stubInitialApply(submit);
        stubInitialApplyInsert(req, 0, 77L);
        when(applyMapper.hasActivePendingApply( 3L, 8L)).thenReturn(competitorPresent);
        when(applyMapper.selectActiveByUserAndAsset(3L, 8L))
                .thenReturn(null, reusableDemandApply(MarketResourceApplyDO.STATUS_PENDING));

        ServiceException failure = assertThrows(ServiceException.class, () -> service.createApply(req));

        assertEquals((competitorPresent ? MARKET_APPLY_DUPLICATE : MARKET_APPLY_STATUS_DENY).getCode(),
                failure.getCode());
        var calls = verifyInitialApplyInsertion(submit);
        calls.verify(applyMapper).hasActivePendingApply( 3L, 8L);
        verify(catalogService, org.mockito.Mockito.atMostOnce()).requireCompletePublishedAsset(any());
        verifyNoMoreInteractions(applyMapper, catalogService);
        verifyNoInteractions(processMapper);
        assertNoInitialApplySuccessEffects();
        assertNull(TenantContextHolder.getTenantId());
        assertFalse(TenantContextHolder.isIgnore());
    }

    @ParameterizedTest
    @CsvSource({"-1,false", "-1,true", "2,false", "2,true",
            "-2147483648,false", "-2147483648,true", "2147483647,false", "2147483647,true"})
    void createApply_rejectsUnexpectedInsertCountDespiteGeneratedKey(int inserted, boolean submit) {
        MarketApplySaveReqVO req = stubInitialApply(submit);
        stubInitialApplyInsert(req, inserted, 77L);

        assertEquals(MARKET_APPLY_STATUS_DENY.getCode(),
                assertThrows(ServiceException.class, () -> service.createApply(req)).getCode());

        verifyInitialApplyInsertion(submit);
        verify(catalogService, org.mockito.Mockito.atMostOnce()).requireCompletePublishedAsset(any());
        verifyNoMoreInteractions(applyMapper, catalogService);
        verifyNoInteractions(processMapper);
        assertNoInitialApplySuccessEffects();
    }

    @ParameterizedTest
    @CsvSource(value = {"NULL,false", "NULL,true", "0,false", "0,true", "-1,false", "-1,true"},
            nullValues = "NULL")
    void createApply_rejectsSuccessfulInsertWithoutPositiveGeneratedKey(Long generatedId, boolean submit) {
        MarketApplySaveReqVO req = stubInitialApply(submit);
        req.setId(9007199254740993L);
        stubInitialApplyInsert(req, 1, generatedId);

        assertEquals(MARKET_APPLY_STATUS_DENY.getCode(),
                assertThrows(ServiceException.class, () -> service.createApply(req)).getCode());

        verifyInitialApplyInsertion(submit);
        verify(catalogService, org.mockito.Mockito.atMostOnce()).requireCompletePublishedAsset(any());
        verifyNoMoreInteractions(applyMapper, catalogService);
        verifyNoInteractions(processMapper);
        assertNoInitialApplySuccessEffects();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 2, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void createApply_rejectsIncompleteSubmitProcessWithoutSuccessEffects(int inserted) {
        MarketApplySaveReqVO req = stubInitialApply(true);
        when(processMapper.insert(any(MarketCheckProcessDO.class))).thenReturn(inserted);

        assertEquals(MARKET_APPLY_STATUS_DENY.getCode(),
                assertThrows(ServiceException.class, () -> service.createApply(req)).getCode());

        verifyInitialApplyInsertion(true);
        verifyInitialApplyProcess(77L);
        verify(catalogService, org.mockito.Mockito.atMostOnce()).requireCompletePublishedAsset(any());
        verifyNoMoreInteractions(applyMapper, catalogService, processMapper);
        assertNoInitialApplySuccessEffects();
        // 只证明编排失败和不重投；事务回滚由主线程的 PostgreSQL 测试证明。
    }

    @ParameterizedTest
    @CsvSource({"INSERT,RUNTIME,false", "INSERT,RUNTIME,true", "INSERT,DUPLICATE,false",
            "INSERT,DUPLICATE,true", "REREAD,RUNTIME,false", "REREAD,RUNTIME,true",
            "REREAD,DUPLICATE,false", "REREAD,DUPLICATE,true", "PROCESS,RUNTIME,true",
            "PROCESS,DUPLICATE,true"})
    void createApply_propagatesStageFailureWithoutMisclassificationRetryOrSuccessEffects(
            String stage, String failureType, boolean submit) {
        MarketApplySaveReqVO req = stubInitialApply(submit);
        RuntimeException failure = "DUPLICATE".equals(failureType)
                ? new DuplicateKeyException("test unrelated initial application constraint")
                : new IllegalStateException("test initial application stage failure");
        switch (stage) {
            case "INSERT" -> doThrow(failure).when(applyMapper)
                    .insertInitialApplyIfAbsent(any(MarketResourceApplyDO.class));
            case "REREAD" -> {
                stubInitialApplyInsert(req, 0, null);
                when(applyMapper.hasActivePendingApply( 3L, 8L)).thenThrow(failure);
            }
            case "PROCESS" -> when(processMapper.insert(any(MarketCheckProcessDO.class))).thenThrow(failure);
            default -> throw new AssertionError(stage);
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.createApply(req)));

        var calls = verifyInitialApplyInsertion(submit);
        if ("REREAD".equals(stage)) {
            calls.verify(applyMapper).hasActivePendingApply( 3L, 8L);
        }
        if ("PROCESS".equals(stage)) {
            verifyInitialApplyProcess(77L);
        } else {
            verifyNoInteractions(processMapper);
        }
        verify(catalogService, org.mockito.Mockito.atMostOnce()).requireCompletePublishedAsset(any());
        verifyNoMoreInteractions(applyMapper, catalogService, processMapper);
        assertNoInitialApplySuccessEffects();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(booleans = {true})
    void createApply_metadataFailureOccursBeforeAnyInsert(Boolean submit) {
        MarketApplySaveReqVO req = stubInitialApply(submit);
        when(catalogService.listColumns(8L, 28L)).thenThrow(new IllegalStateException("test metadata unavailable"));

        assertEquals(MARKET_APPLY_METADATA_UNAVAILABLE.getCode(),
                assertThrows(ServiceException.class, () -> service.createApply(req)).getCode());

        verifyInitialApplyReadsBeforeFailedSnapshot();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NULL_COLUMNS", "EMPTY_COLUMNS", "UNKNOWN_SCOPE", "DUPLICATE_COLUMN", "WILDCARD"})
    void createApply_invalidFrozenMetadataCannotInsertIncompleteSubmission(String change) {
        MarketApplySaveReqVO req = stubInitialApply(true);
        MetadataColumnRespDTO column = new MetadataColumnRespDTO();
        column.setColumnName("id");
        switch (change) {
            case "NULL_COLUMNS" -> when(catalogService.listColumns(8L, 28L)).thenReturn(null);
            case "EMPTY_COLUMNS" -> when(catalogService.listColumns(8L, 28L)).thenReturn(List.of());
            case "UNKNOWN_SCOPE" -> req.setFieldScope("not_present");
            case "DUPLICATE_COLUMN" -> when(catalogService.listColumns(8L, 28L))
                    .thenReturn(List.of(column, column));
            case "WILDCARD" -> {
                column.setColumnName("*");
                when(catalogService.listColumns(8L, 28L)).thenReturn(List.of(column));
            }
            default -> throw new AssertionError(change);
        }

        assertEquals(MARKET_APPLY_FIELD_SCOPE_INVALID.getCode(),
                assertThrows(ServiceException.class, () -> service.createApply(req)).getCode());

        verifyInitialApplyReadsBeforeFailedSnapshot();
    }

    @ParameterizedTest
    @CsvSource({"2147483648,false", "2147483648,true", "9007199254740993,false",
            "9007199254740993,true", "9223372036854775807,false", "9223372036854775807,true"})
    void createApply_preservesLargeGeneratedKeyInResultProcessAndSuccessEffects(long generatedId, boolean submit) {
        MarketApplySaveReqVO req = stubInitialApply(submit);
        req.setId(123L);
        stubInitialApplyInsert(req, 1, generatedId);

        assertEquals(generatedId, service.createApply(req));

        verifyInitialApplyInsertion(submit);
        if (submit) {
            verifyInitialApplySubmitEffects(generatedId);
        } else {
            verifyNoInteractions(processMapper);
            assertNoInitialApplySuccessEffects();
        }
        verify(catalogService, org.mockito.Mockito.atMostOnce()).requireCompletePublishedAsset(any());
        verifyNoMoreInteractions(applyMapper, catalogService, processMapper, notifyHelper, auditRecorder);
        assertEquals(123L, req.getId(), "清理实体主键不得修改调用方请求对象");
    }

    @ParameterizedTest
    @CsvSource(value = {"NULL,false", "NULL,true", "-1,false", "-1,true", "0,false", "0,true",
            "1,false", "1,true", "9007199254740993,false", "9007199254740993,true",
            "9223372036854775807,false", "9223372036854775807,true"}, nullValues = "NULL")
    void createApply_clientProvidedIdCannotDetermineGeneratedPrimaryKey(Long clientId, boolean submit) {
        MarketApplySaveReqVO req = stubInitialApply(submit);
        req.setId(clientId);

        assertEquals(77L, service.createApply(req));

        verifyInitialApplyInsertion(submit);
        if (submit) {
            verifyInitialApplySubmitEffects(77L);
        } else {
            verifyNoInteractions(processMapper);
            assertNoInitialApplySuccessEffects();
        }
        verify(catalogService, org.mockito.Mockito.atMostOnce()).requireCompletePublishedAsset(any());
        verifyNoMoreInteractions(applyMapper, catalogService, processMapper, notifyHelper, auditRecorder);
        assertEquals(clientId, req.getId());
    }

    @Test
    void updateApply_freezesPendingContent() {
        MarketResourceApplyDO apply = new MarketResourceApplyDO();
        apply.setId(1L);
        apply.setApplyUserId(3L);
        apply.setStatus(MarketResourceApplyDO.STATUS_PENDING);
        when(applyMapper.selectById(1L)).thenReturn(apply);
        when(objectGuard.requireUserId()).thenReturn(3L);
        MarketApplySaveReqVO req = apiReq();
        req.setId(1L);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.updateApply(req));
        assertEquals(MARKET_APPLY_STATUS_DENY.getCode(), ex.getCode());
    }

    @Test
    void requestSupplement_movesPendingToSupplement() {
        MarketResourceApplyDO apply = pendingApply();
        when(applyMapper.selectById(1L)).thenReturn(apply);
        when(assetMapper.selectById(8L)).thenReturn(publishedTable());
        when(applyMapper.update(isNull(), any())).thenReturn(1);
        when(processMapper.insert(any(MarketCheckProcessDO.class))).thenReturn(1);
        when(objectGuard.requireUserId()).thenReturn(9L);
        service.requestSupplement(1L, "请补充使用依据");
        verify(processMapper).insert(any(MarketCheckProcessDO.class));
        verify(auditRecorder).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void checkApply_enqueuesGrantInsteadOfInTxSupplyCall() {
        MarketResourceApplyDO apply = pendingApply();
        when(applyMapper.selectById(1L)).thenReturn(apply);
        when(assetMapper.selectById(8L)).thenReturn(publishedTable());
        when(applyMapper.update(isNull(), any())).thenReturn(1);
        when(processMapper.insert(any(MarketCheckProcessDO.class))).thenReturn(1);
        when(objectGuard.requireUserId()).thenReturn(9L);
        MarketCheckReqVO req = new MarketCheckReqVO();
        req.setId(1L);
        req.setStatus(MarketResourceApplyDO.STATUS_APPROVED);
        req.setCheckNote("同意");
        service.checkApply(req);
        verify(deliveryService).enqueueApprovedGrant(any(MarketResourceApplyDO.class), eq(9L), any(), eq("同意"));
    }

    @Test
    void getApply_exposesThreeIndependentStatuses() {
        var vo = approvedApplyView(operation(MarketDeliveryOperationDO.STATUS_PENDING, "UNCONFIRMED"));
        assertEquals(MarketResourceApplyDO.STATUS_APPROVED, vo.getApprovalResult());
        assertEquals(MarketDeliveryService.DELIVERY_PENDING, vo.getTechProvisionStatus());
        assertEquals("PENDING", vo.getDeliveryResolution());
        assertTrue(vo.getEffective());
        assertFalse(vo.getUsable());
    }

    @Test
    void getApply_doesNotTurnAnUnknownDeliveryIntoUsableAccess() {
        // 批准 + 结果未知：绝不能显示为已开通，也不能凭超时推断远端没生效。
        var vo = approvedApplyView(operation(MarketDeliveryOperationDO.STATUS_RETRY, "UNKNOWN"));
        assertEquals("UNKNOWN", vo.getDeliveryResolution());
        assertTrue(vo.getEffective());
        assertFalse(vo.getUsable());
    }

    @Test
    void getApply_reportsAppliedOnlyWhenTheLedgerConfirmedIt() {
        var vo = approvedApplyView(operation(MarketDeliveryOperationDO.STATUS_SUCCESS, "CONFIRMED"));
        assertEquals("APPLIED", vo.getDeliveryResolution());
        assertTrue(vo.getUsable());
    }

    @Test
    void successfulButUnconfirmedLedgerMustNotAdvertiseUsableAccess() {
        var vo = approvedApplyView(operation(MarketDeliveryOperationDO.STATUS_SUCCESS, "UNCONFIRMED"));
        assertEquals("UNKNOWN", vo.getDeliveryResolution());
        assertFalse(vo.getUsable());
    }

    @Test
    void cancelledLedgerMustNotBeTreatedAsNoDeliveryRequired() {
        var vo = approvedApplyView(operation(MarketDeliveryOperationDO.STATUS_CANCELLED, "UNCONFIRMED"));
        assertEquals("UNKNOWN", vo.getDeliveryResolution());
        assertFalse(vo.getUsable());
    }

    @Test
    void technicalStatusMustComeFromTheSameLedgerAsTheResolution() {
        var vo = approvedApplyView(operation(MarketDeliveryOperationDO.STATUS_SUCCESS, "CONFIRMED"));
        assertEquals(MarketDeliveryService.DELIVERY_PENDING, vo.getDeliveryStatus());
        assertEquals(MarketDeliveryService.DELIVERY_SUCCESS, vo.getTechProvisionStatus());
    }

    @ParameterizedTest
    @CsvSource(value = {
            "SUCCESS,NULL,UNKNOWN,3", "SUCCESS,NEW_RESULT,UNKNOWN,3",
            "SUCCESS,REVIEW_REQUIRED,UNKNOWN,3", "CANCELLED,CONFIRMED,UNKNOWN,6",
            "CANCELLED,SUPERSEDED,SUPERSEDED,6", "PENDING,CONFIRMED,UNKNOWN,1",
            "RUNNING,UNCONFIRMED,PENDING,2", "RETRY,UNCONFIRMED,PENDING,4",
            "MANUAL,UNCONFIRMED,UNKNOWN,5", "NULL,UNCONFIRMED,UNKNOWN,5",
            "NEW_STATUS,UNCONFIRMED,UNKNOWN,5"
    }, nullValues = "NULL")
    void applyViewCannotPromoteIncompleteOrUnsupportedLedgerState(String status, String confirmation,
            String expected, int technicalStatus) {
        var vo = approvedApplyView(operation(status, confirmation), MarketDeliveryService.DELIVERY_SUCCESS);
        assertEquals(expected, vo.getDeliveryResolution());
        assertEquals(technicalStatus, vo.getTechProvisionStatus());
        assertFalse(vo.getUsable());
    }

    @ParameterizedTest
    @CsvSource({"6,UNKNOWN", "99,UNKNOWN", "5,UNKNOWN", "4,PENDING"})
    void cancelledOrUnconfirmedLegacyIntentDoesNotBecomeNoDeliveryRequired(int status, String expected) {
        var vo = approvedApplyView(null, status);
        assertEquals(expected, vo.getDeliveryResolution());
        assertFalse(vo.getUsable());
    }

    @Test
    void unrecognizedOperationTypeCannotBeReportedAsConfirmedRevocation() {
        var latest = operation(MarketDeliveryOperationDO.STATUS_SUCCESS, "CONFIRMED");
        latest.setOpType("UNRECOGNIZED");
        var vo = approvedApplyView(latest);
        assertEquals("UNKNOWN", vo.getDeliveryResolution());
        assertFalse(vo.getUsable());
    }

    @Test
    void getApply_doesNotShowSupersededGrantAsUsable() {
        var vo = approvedApplyView(operation(MarketDeliveryOperationDO.STATUS_SUCCESS, "SUPERSEDED"));
        assertEquals("SUPERSEDED", vo.getDeliveryResolution());
        assertFalse(vo.getUsable());
    }

    @Test
    void getApply_doesNotShowConfirmedRevokeAsApplied() {
        MarketDeliveryOperationDO revoked = operation(MarketDeliveryOperationDO.STATUS_SUCCESS, "CONFIRMED");
        revoked.setOpType(MarketDeliveryOperationDO.TYPE_REVOKE);
        var vo = approvedApplyView(revoked);
        assertEquals("REVOKED", vo.getDeliveryResolution());
        assertFalse(vo.getUsable());
    }

    @ParameterizedTest
    @CsvSource({"SUSPEND,0,SUSPENDED,true", "SUSPEND,1,SUSPENDED,false",
            "REVOKE,0,REVOKED,true", "REVOKE,1,REVOKED,false"})
    void applyWithdrawalResultIsDistinctAndNeverUsable(String action, int paused, String resolution,
                                                     boolean effective) {
        var apply = currentDetailApply();
        apply.setPaused(paused);
        var row = MarketDeliveryConfirmationFixture.operation(deliveryService, apply,
                cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO.Action.valueOf(action));
        var response = currentApplyView(apply, row);
        assertEquals(resolution, response.getDeliveryResolution());
        assertEquals(effective, response.getEffective());
        assertFalse(response.getUsable());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GRANT", "SUSPEND", "REVOKE"})
    void operationResponseSeparatesOriginalActionFromExecutionResult(String action) {
        var apply = currentDetailApply();
        var row = MarketDeliveryConfirmationFixture.operation(deliveryService, apply,
                cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO.Action.valueOf(action));
        row.setStatus("PENDING");
        row.setResolutionStatus("UNCONFIRMED");
        row.setAttemptCount(3);
        row.setResultRef("original-receipt");
        row.setLastError("等待原回执核查");
        row.setUpdateTime(GRANT_NOW);
        when(deliveryEvidenceService.authorizeCurrent( row.getOperationId()))
                .thenReturn(deliverySnapshot(row));

        var response = service.getDeliveryOperation(row.getOperationId());
        assertEquals(action, response.action());
        assertEquals(row.getOpType(), response.opType());
        assertEquals("PENDING", response.status());
        assertEquals("UNCONFIRMED", response.resolutionStatus());
        assertEquals(3, response.attemptCount());
        assertEquals("original-receipt", response.resultRef());
        assertEquals("等待原回执核查", response.lastError());
        assertEquals(GRANT_NOW, response.updateTime());
        verify(deliveryEvidenceService).authorizeCurrent( row.getOperationId());
        org.mockito.Mockito.verifyNoMoreInteractions(deliveryEvidenceService);
        org.mockito.Mockito.verifyNoInteractions(deliveryOperationMapper, applyMapper, assetMapper,
                objectGuard, deliveryCoordinator);
    }

    @Test
    void deliveryDetailDelegatesWithTrustedTenantAndReturnsOnlyAuthorizedSnapshot() {
        TenantContextHolder.clear();
        var row = MarketDeliveryOperationDO.builder().id(9_007_199_254_740_993L)
                .applyId(9_007_199_254_740_995L).operationId("tenant-9-operation")
                .opType("REVOKE").sourceRevision(12L).protocolVersion(2)
                .status("MANUAL").resolutionStatus("UNKNOWN").attemptCount(8)
                .resultRef("receipt-for-authorized-operation").lastError("需人工核查").build();
        row.setUpdateTime(GRANT_NOW);
        when(deliveryEvidenceService.authorizeCurrent( row.getOperationId()))
                .thenReturn(deliverySnapshot(row));

        var result = service.getDeliveryOperation(row.getOperationId());

        assertEquals(row.getId(), result.id());
        assertEquals(row.getApplyId(), result.applyId());
        assertEquals(row.getOperationId(), result.operationId());
        assertEquals("REVOKE", result.opType());
        assertEquals(12L, result.sourceRevision());
        assertEquals(2, result.protocolVersion());
        assertEquals("MANUAL", result.status());
        assertEquals("UNKNOWN", result.resolutionStatus());
        assertEquals(8, result.attemptCount());
        assertEquals("receipt-for-authorized-operation", result.resultRef());
        assertEquals("需人工核查", result.lastError());
        assertEquals(GRANT_NOW, result.updateTime());
        assertEquals("UNKNOWN", result.action()); // 没有冻结证据，不从技术类型推断动作。
        verify(deliveryEvidenceService).authorizeCurrent( row.getOperationId());
        org.mockito.Mockito.verifyNoMoreInteractions(deliveryEvidenceService);
        org.mockito.Mockito.verifyNoInteractions(deliveryOperationMapper, applyMapper, assetMapper,
                objectGuard, deliveryCoordinator);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FORBIDDEN", "NOT_FOUND", "STATE_CHANGED", "RUNTIME", "ERROR"})
    void deliveryDetailPropagatesAuthorizationFailureWithoutMapperFallback(String failureType) {
        Throwable failure = switch (failureType) {
            case "FORBIDDEN" -> new ServiceException(
                    cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_FORBIDDEN);
            case "NOT_FOUND" -> new ServiceException(
                    cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_NOT_EXISTS);
            case "STATE_CHANGED" -> new ServiceException(MARKET_APPLY_STATUS_DENY);
            case "RUNTIME" -> new IllegalStateException("snapshot unavailable");
            case "ERROR" -> new AssertionError("snapshot failed");
            default -> throw new AssertionError("Unsupported failure type: " + failureType);
        };
        when(deliveryEvidenceService.authorizeCurrent( "private-operation")).thenThrow(failure);

        Throwable actual = assertThrows(failure.getClass(), () -> service.getDeliveryOperation("private-operation"));

        org.junit.jupiter.api.Assertions.assertSame(failure, actual);
        verify(deliveryEvidenceService).authorizeCurrent( "private-operation");
        org.mockito.Mockito.verifyNoMoreInteractions(deliveryEvidenceService);
        org.mockito.Mockito.verifyNoInteractions(deliveryOperationMapper, applyMapper, assetMapper,
                objectGuard, deliveryCoordinator);
    }

    @Test
    void manualDeliveryRecheckRequiresProviderScopeAndReturnsOnlyLedgerState() {
        String operationId = "asset:asset:apply:1:r2:GRANT";
        MarketDeliveryOperationDO unknown = MarketDeliveryOperationDO.builder().id(11L).applyId(1L)
                .operationId(operationId).status("MANUAL").resolutionStatus("UNKNOWN")
                .protocolVersion(2).sourceRevision(2L).build();
        MarketDeliveryOperationDO settled = MarketDeliveryOperationDO.builder().id(11L).applyId(1L)
                .operationId(operationId).status("SUCCESS").resolutionStatus("CONFIRMED")
                .protocolVersion(2).sourceRevision(2L).build();
        settled.setAttemptCount(6);
        settled.setResultRef("reconciled-original-receipt");
        settled.setLastError(null);
        when(deliveryEvidenceService.authorizeCurrent( operationId))
                .thenReturn(deliverySnapshot(unknown), deliverySnapshot(settled));
        when(objectGuard.requireUserId()).thenReturn(91L);
        when(objectGuard.currentDeptId()).thenReturn(7L);
        when(deliveryCoordinator.reconcileManualUnknown( unknown, 91L, 7L, "核实原回执"))
                .thenReturn(true);

        var result = service.recheckDelivery(new MarketDeliveryRecheckReqVO(operationId, "核实原回执"));
        assertEquals("SUCCESS", result.status());
        assertEquals("CONFIRMED", result.resolutionStatus());
        assertEquals(operationId, result.operationId());
        assertEquals(2, result.protocolVersion());
        assertEquals("UNKNOWN", result.action()); // 缺冻结证据不能从成功或类型补造原动作。
        assertEquals(6, result.attemptCount());
        assertEquals("reconciled-original-receipt", result.resultRef());
        assertNull(result.lastError());
        var order = org.mockito.Mockito.inOrder(deliveryEvidenceService, deliveryCoordinator);
        order.verify(deliveryEvidenceService).authorizeCurrent( operationId);
        order.verify(deliveryCoordinator).reconcileManualUnknown( unknown, 91L, 7L, "核实原回执");
        order.verify(deliveryEvidenceService).authorizeCurrent( operationId);
        order.verifyNoMoreInteractions();
        org.mockito.Mockito.verifyNoInteractions(deliveryOperationMapper, applyMapper, assetMapper);
        verify(objectGuard, org.mockito.Mockito.never()).assertCanAuthApply(any(), any());
    }

    @Test
    void manualDeliveryRecheckStopsBeforeRemoteQueryWhenEvidenceAuthorizationDenies() {
        String operationId = "private-operation";
        ServiceException denied = new ServiceException(
                cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_FORBIDDEN);
        when(objectGuard.requireUserId()).thenReturn(91L);
        when(deliveryEvidenceService.authorizeCurrent( operationId)).thenThrow(denied);

        ServiceException actual = assertThrows(ServiceException.class, () -> service.recheckDelivery(
                new MarketDeliveryRecheckReqVO(operationId, "核查")));
        org.junit.jupiter.api.Assertions.assertSame(denied, actual);
        verify(deliveryEvidenceService).authorizeCurrent( operationId);
        org.mockito.Mockito.verifyNoMoreInteractions(deliveryEvidenceService);
        org.mockito.Mockito.verifyNoInteractions(deliveryCoordinator, deliveryOperationMapper, applyMapper, assetMapper);
        verify(objectGuard, org.mockito.Mockito.never()).assertCanAuthApply(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "USER_CLEARED", "DEPT", "DEPT_CLEARED"})
    void manualDeliveryRecheckRejectsIdentityDriftInInitialAuthorizationBeforeCoordinator(String drift) {
        MarketDeliveryOperationDO row = prepareManualDeliveryRecheck();
        when(deliveryEvidenceService.authorizeCurrent( row.getOperationId())).thenAnswer(invocation -> {
            driftManualDeliveryCaller(drift);
            return deliverySnapshot(row);
        });

        assertManualDeliveryRecheckDenied(row);

        verify(deliveryEvidenceService).authorizeCurrent( row.getOperationId());
        org.mockito.Mockito.verifyNoMoreInteractions(deliveryEvidenceService);
        org.mockito.Mockito.verifyNoInteractions(deliveryCoordinator, deliveryOperationMapper, applyMapper, assetMapper);
        verify(objectGuard, org.mockito.Mockito.never()).assertCanAuthApply(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "USER_CLEARED", "DEPT", "DEPT_CLEARED"})
    void manualDeliveryRecheckRejectsIdentityDriftOnCoordinatorReturnBeforeFinalAuthorization(String drift) {
        MarketDeliveryOperationDO row = prepareManualDeliveryRecheck();
        when(deliveryCoordinator.reconcileManualUnknown( row, 91L, 7L, "核查"))
                .thenAnswer(invocation -> {
                    driftManualDeliveryCaller(drift);
                    return true;
                });

        assertManualDeliveryRecheckDenied(row);

        verify(deliveryCoordinator).reconcileManualUnknown( row, 91L, 7L, "核查");
        org.mockito.Mockito.verifyNoMoreInteractions(deliveryCoordinator);
        verify(deliveryEvidenceService).authorizeCurrent( row.getOperationId());
        org.mockito.Mockito.verifyNoMoreInteractions(deliveryEvidenceService);
        org.mockito.Mockito.verifyNoInteractions(deliveryOperationMapper, applyMapper, assetMapper);
        verify(objectGuard, org.mockito.Mockito.never()).assertCanAuthApply(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "USER_CLEARED", "DEPT", "DEPT_CLEARED"})
    void manualDeliveryRecheckRejectsIdentityDriftInFinalAuthorizationBeforeReturningLedger(String drift) {
        MarketDeliveryOperationDO row = prepareManualDeliveryRecheck();
        when(deliveryEvidenceService.authorizeCurrent( row.getOperationId()))
                .thenReturn(deliverySnapshot(row)).thenAnswer(invocation -> {
                    driftManualDeliveryCaller(drift);
                    return deliverySnapshot(row);
                });

        assertManualDeliveryRecheckDenied(row);

        verify(deliveryCoordinator).reconcileManualUnknown( row, 91L, 7L, "核查");
        org.mockito.Mockito.verifyNoMoreInteractions(deliveryCoordinator);
        verify(deliveryEvidenceService, org.mockito.Mockito.times(2)).authorizeCurrent( row.getOperationId());
        org.mockito.Mockito.verifyNoMoreInteractions(deliveryEvidenceService);
        org.mockito.Mockito.verifyNoInteractions(deliveryOperationMapper, applyMapper, assetMapper);
        verify(objectGuard, org.mockito.Mockito.never()).assertCanAuthApply(any(), any());
    }

    @Test
    void manualDeliveryRecheckRejectsActiveTransactionBeforeAnyMapperOrGuard() {
        boolean previousActive = TransactionSynchronizationManager.isActualTransactionActive();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> service.recheckDelivery(
                    new MarketDeliveryRecheckReqVO("manual-operation", "核查")));
            org.mockito.Mockito.verifyNoInteractions(deliveryOperationMapper, applyMapper, assetMapper,
                    approvalEvidenceMapper, processMapper, demandMapper, endpointMapper,
                    objectGuard, deliveryCoordinator, deliveryEvidenceService);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(previousActive);
        }
    }

    @Test
    void manualDeliveryRecheckPreservesNullDepartmentAndStripsReasonWithoutChangingIdentity() {
        MarketDeliveryOperationDO row = prepareManualDeliveryRecheck();
        when(objectGuard.currentDeptId()).thenReturn(null);

        var result = service.recheckDelivery(new MarketDeliveryRecheckReqVO(row.getOperationId(), "  核查  "));

        assertEquals(row.getOperationId(), result.operationId());
        assertEquals("MANUAL", result.status());
        assertEquals("UNKNOWN", result.resolutionStatus());
        assertEquals(2, result.protocolVersion());
        verify(deliveryCoordinator).reconcileManualUnknown( row, 91L, null, "核查");
        org.mockito.Mockito.verifyNoMoreInteractions(deliveryCoordinator);
        verify(deliveryEvidenceService, org.mockito.Mockito.times(2)).authorizeCurrent( row.getOperationId());
        org.mockito.Mockito.verifyNoMoreInteractions(deliveryEvidenceService);
        org.mockito.Mockito.verifyNoInteractions(deliveryOperationMapper, applyMapper, assetMapper);
        verify(objectGuard, org.mockito.Mockito.never()).assertCanAuthApply(any(), any());
    }

    @Test
    void manualDeliveryRecheckDoesNotReadOrReturnLedgerWhenCoordinatorLosesCas() {
        MarketDeliveryOperationDO row = prepareManualDeliveryRecheck();
        when(deliveryCoordinator.reconcileManualUnknown( row, 91L, 7L, "核查"))
                .thenReturn(false);

        assertManualDeliveryRecheckDenied(row);

        verify(deliveryCoordinator).reconcileManualUnknown( row, 91L, 7L, "核查");
        org.mockito.Mockito.verifyNoMoreInteractions(deliveryCoordinator);
        verify(deliveryEvidenceService).authorizeCurrent( row.getOperationId());
        org.mockito.Mockito.verifyNoMoreInteractions(deliveryEvidenceService);
        org.mockito.Mockito.verifyNoInteractions(deliveryOperationMapper, applyMapper, assetMapper);
        verify(objectGuard, org.mockito.Mockito.never()).assertCanAuthApply(any(), any());
    }

    @Test
    void manualDeliveryRecheckKeepsFinalEvidenceAuthorizationFailureAfterCoordinatorSuccess() {
        MarketDeliveryOperationDO row = prepareManualDeliveryRecheck();
        ServiceException denied = new ServiceException(
                cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_FORBIDDEN);
        when(deliveryEvidenceService.authorizeCurrent( row.getOperationId()))
                .thenReturn(deliverySnapshot(row)).thenThrow(denied);

        ServiceException actual = assertThrows(ServiceException.class, () -> service.recheckDelivery(
                new MarketDeliveryRecheckReqVO(row.getOperationId(), "核查")));

        org.junit.jupiter.api.Assertions.assertSame(denied, actual);
        verify(deliveryCoordinator).reconcileManualUnknown( row, 91L, 7L, "核查");
        org.mockito.Mockito.verifyNoMoreInteractions(deliveryCoordinator);
        verify(deliveryEvidenceService, org.mockito.Mockito.times(2)).authorizeCurrent( row.getOperationId());
        org.mockito.Mockito.verifyNoMoreInteractions(deliveryEvidenceService);
        org.mockito.Mockito.verifyNoInteractions(deliveryOperationMapper, applyMapper, assetMapper);
        verify(objectGuard, org.mockito.Mockito.never()).assertCanAuthApply(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"BUSINESS", "RUNTIME", "ERROR"})
    void manualDeliveryRecheckDoesNotMaskUnexpectedIdentityLookupFailure(String failureType) {
        MarketDeliveryOperationDO row = prepareManualDeliveryRecheck();
        Throwable failure = switch (failureType) {
            case "BUSINESS" -> new ServiceException(MARKET_APPLY_API_NOT_BOUND);
            case "RUNTIME" -> new IllegalStateException("identity lookup failed");
            case "ERROR" -> new AssertionError("identity lookup failed");
            default -> throw new AssertionError("Unsupported failure type: " + failureType);
        };
        when(deliveryEvidenceService.authorizeCurrent( row.getOperationId())).thenAnswer(invocation -> {
            org.mockito.Mockito.doThrow(failure).when(objectGuard).requireUserId();
            return deliverySnapshot(row);
        });

        Throwable actual = assertThrows(failure.getClass(), () -> service.recheckDelivery(
                new MarketDeliveryRecheckReqVO(row.getOperationId(), "核查")));

        org.junit.jupiter.api.Assertions.assertSame(failure, actual);
        verify(deliveryEvidenceService).authorizeCurrent( row.getOperationId());
        org.mockito.Mockito.verifyNoMoreInteractions(deliveryEvidenceService);
        org.mockito.Mockito.verifyNoInteractions(deliveryCoordinator, deliveryOperationMapper, applyMapper, assetMapper);
        verify(objectGuard, org.mockito.Mockito.never()).assertCanAuthApply(any(), any());
    }

    private MarketDeliveryOperationDO prepareManualDeliveryRecheck() {
        MarketDeliveryOperationDO row = MarketDeliveryOperationDO.builder().id(11L).applyId(1L)
                .operationId("asset:asset:apply:1:r2:GRANT").status("MANUAL").resolutionStatus("UNKNOWN")
                .protocolVersion(2).sourceRevision(2L).build();
        // 只模拟授权后的快照，真实 Guard/快照竞争由 PostgreSQL 用例验证。
        // 各身份漂移用例在自己的委托返回处注入变化，验证入口身份连续性，不重演 Guard。
        when(deliveryEvidenceService.authorizeCurrent( row.getOperationId()))
                .thenReturn(deliverySnapshot(row));
        when(objectGuard.requireUserId()).thenReturn(91L);
        when(objectGuard.currentDeptId()).thenReturn(7L);
        when(deliveryCoordinator.reconcileManualUnknown( eq(row),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.nullable(Long.class),
                eq("核查"))).thenReturn(true);
        return row;
    }

    private static MarketDeliveryEvidenceService.Snapshot deliverySnapshot(MarketDeliveryOperationDO operation) {
        return new MarketDeliveryEvidenceService.Snapshot(operation, null, null);
    }

    private void assertManualDeliveryRecheckDenied(MarketDeliveryOperationDO row) {
        ServiceException error = assertThrows(ServiceException.class, () -> service.recheckDelivery(
                new MarketDeliveryRecheckReqVO(row.getOperationId(), "核查")));
        assertEquals(MARKET_APPLY_STATUS_DENY.getCode(), error.getCode());
    }

    private void driftManualDeliveryCaller(String drift) {
        switch (drift) {
            case "USER" -> org.mockito.Mockito.doReturn(92L).when(objectGuard).requireUserId();
            case "USER_CLEARED" -> org.mockito.Mockito.doThrow(new ServiceException(
                    cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_FORBIDDEN))
                    .when(objectGuard).requireUserId();
            case "DEPT" -> org.mockito.Mockito.doReturn(8L).when(objectGuard).currentDeptId();
            case "DEPT_CLEARED" -> org.mockito.Mockito.doReturn(null).when(objectGuard).currentDeptId();
            default -> throw new AssertionError("Unsupported caller drift: " + drift);
        }
    }

    @Test
    void getApply_separatesAProviderRejectionFromAPendingDelivery() {
        var vo = approvedApplyView(operation(MarketDeliveryOperationDO.STATUS_MANUAL, "CONFIRMED"));
        assertEquals("REJECTED", vo.getDeliveryResolution());
        assertFalse(vo.getUsable());
    }

    @Test
    void getApply_keepsManualWithoutAConfirmedResultUnknown() {
        var vo = approvedApplyView(operation(MarketDeliveryOperationDO.STATUS_MANUAL, "UNKNOWN"));
        assertEquals("UNKNOWN", vo.getDeliveryResolution());
        assertFalse(vo.getUsable());
    }

    @Test
    void getApply_fallsBackToTheLegacyColumnWhenNoLedgerEntryExists() {
        // 旧协议申请只剩反规范化列；按其原语义呈现，不伪造账本证据。
        var vo = approvedApplyView(null, MarketDeliveryService.DELIVERY_SUCCESS);
        assertEquals("APPLIED", vo.getDeliveryResolution());
        assertTrue(vo.getUsable());
        assertNull(vo.getDeliveryOperationId());
    }

    @Test
    void applyViewUsesOriginalOperationIdentityWithoutGuessingOrASecondLedgerRead() {
        var latest = operation(MarketDeliveryOperationDO.STATUS_MANUAL, "UNKNOWN");
        latest.setOperationId("original-operation:9223372036854775807:r2");
        var vo = approvedApplyView(latest);
        assertEquals(latest.getOperationId(), vo.getDeliveryOperationId());
        verify(deliveryOperationMapper).selectLatestByApply( 1L);
    }

    private MarketApplyRespVO approvedApplyView(MarketDeliveryOperationDO latest) {
        return approvedApplyView(latest, MarketDeliveryService.DELIVERY_PENDING);
    }

    private MarketApplyRespVO approvedApplyView(MarketDeliveryOperationDO latest, int deliveryStatus) {
        return approvedApplyView(latest, deliveryStatus, false);
    }

    @Test
    void confirmedDeliveryWithoutFrozenProofIsStillNotUsable() {
        var vo = approvedApplyView(operation(MarketDeliveryOperationDO.STATUS_SUCCESS, "CONFIRMED"),
                MarketDeliveryService.DELIVERY_SUCCESS, true);
        assertTrue(vo.getEffective());
        assertEquals("UNKNOWN", vo.getDeliveryResolution());
        assertFalse(vo.getUsable());
    }

    private MarketApplyRespVO approvedApplyView(MarketDeliveryOperationDO latest, int deliveryStatus,
                                               boolean missingProof) {
        return approvedApplyView(latest, deliveryStatus, missingProof, apply -> { });
    }

    private static final LocalDateTime GRANT_NOW = LocalDateTime.of(2035, 1, 2, 12, 0);

    @ParameterizedTest
    @CsvSource({"-1,true", "0,false", "1,false"})
    void applyViewUsesDatabaseClockAndExclusiveDeadline(int clockOffset, boolean effective) {
        when(applyMapper.databaseNow()).thenReturn(GRANT_NOW.plusSeconds(clockOffset));
        var vo = approvedApplyView(operation(MarketDeliveryOperationDO.STATUS_SUCCESS, "CONFIRMED"),
                MarketDeliveryService.DELIVERY_SUCCESS, false, apply -> apply.setApplyEndTime(GRANT_NOW));
        assertEquals(effective, vo.getEffective());
        assertEquals(effective, vo.getUsable());
        assertEquals(effective ? 1 : 0, vo.getAuthStatus());
    }

    @Test
    void applyViewRejectsFutureStartAccordingToDatabaseClock() {
        var vo = approvedApplyView(operation(MarketDeliveryOperationDO.STATUS_SUCCESS, "CONFIRMED"),
                MarketDeliveryService.DELIVERY_SUCCESS, false,
                apply -> {
                    apply.setApplyStartTime(GRANT_NOW.plusSeconds(1));
                    apply.setApplyEndTime(GRANT_NOW.plusHours(1));
                });
        assertFalse(vo.getEffective());
        assertFalse(vo.getUsable());
    }

    @Test
    void applyViewChecksClockAfterProcessEnrichmentAndDeliveryRead() {
        var vo = approvedApplyView(operation(MarketDeliveryOperationDO.STATUS_SUCCESS, "CONFIRMED"),
                MarketDeliveryService.DELIVERY_SUCCESS, false, apply -> apply.setApplyEndTime(GRANT_NOW));
        assertFalse(vo.getUsable());
        var order = org.mockito.Mockito.inOrder(processMapper, deliveryOperationMapper, applyMapper);
        order.verify(processMapper).selectListByApplyId(1L);
        order.verify(deliveryOperationMapper).selectLatestByApply( 1L);
        order.verify(applyMapper).databaseNow();
    }

    @Test
    void applyViewDoesNotFallbackWhenDatabaseClockIsUnavailable() {
        when(applyMapper.databaseNow()).thenReturn(null);
        assertThrows(ServiceException.class, () -> approvedApplyView(null));
        when(applyMapper.databaseNow()).thenThrow(new IllegalStateException("test clock unavailable"));
        assertThrows(IllegalStateException.class, () -> approvedApplyView(null));
    }

    @ParameterizedTest(name = "current grant changed: {0}")
    @ValueSource(strings = {"revision", "sourceRevision", "sourceGrantId", "appId", "apiId", "applyUserId",
            "purpose", "dutyMatter", "startTime", "endTime", "columns", "database", "schema", "table",
            "contract", "apiVersion"})
    void applyViewRejectsConfirmedLedgerForDifferentCurrentGrant(String changed) {
        var apply = currentDetailApply();
        // 必须先冻结旧命令，再修改当前 DO，避免夹具把旧回执悄悄重建为新范围。
        var latest = MarketDeliveryConfirmationFixture.operation(deliveryService, apply);
        switch (changed) {
            case "revision" -> apply.setRevision(2);
            case "sourceRevision" -> apply.setSourceRevision(3L);
            case "sourceGrantId" -> apply.setSourceGrantId("apply:1:r2");
            case "appId" -> apply.setAppId(4L);
            case "apiId" -> apply.setApiId(9L);
            case "applyUserId" -> apply.setApplyUserId(12L);
            case "purpose" -> apply.setPurpose("changed purpose");
            case "dutyMatter" -> apply.setDutyMatter("changed duty");
            case "startTime" -> apply.setApplyStartTime(GRANT_NOW.minusDays(1));
            case "endTime" -> apply.setApplyEndTime(GRANT_NOW.plusDays(1));
            case "columns" -> apply.setSnapshot(apply.getSnapshot().replace("[\"id\"]", "[\"id\",\"extra\"]"));
            case "database" -> apply.setSnapshot(apply.getSnapshot().replace("\"20\"", "\"21\""));
            case "schema" -> apply.setSnapshot(apply.getSnapshot().replace("public", "other_schema"));
            case "table" -> apply.setSnapshot(apply.getSnapshot().replace("t_person", "t_other"));
            case "contract" -> apply.setSnapshot(apply.getSnapshot().replace("test-contract", "changed-contract"));
            case "apiVersion" -> apply.setSnapshot(apply.getSnapshot().replace("v1", "v2"));
            default -> throw new AssertionError(changed);
        }

        var vo = currentApplyView(apply, latest);

        assertTrue(vo.getEffective());
        assertEquals("UNKNOWN", vo.getDeliveryResolution());
        assertFalse(vo.getUsable());
        assertEquals(MarketDeliveryService.DELIVERY_SUCCESS, vo.getTechProvisionStatus());
        assertEquals(latest.getOperationId(), vo.getDeliveryOperationId());
        verify(deliveryOperationMapper).selectLatestByApply( 1L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void clearingApiCannotHideAnExistingApiDelivery(boolean clearApp) {
        var apply = currentDetailApply();
        var latest = MarketDeliveryConfirmationFixture.operation(deliveryService, apply);
        apply.setApiId(null);
        if (clearApp) {
            apply.setAppId(null);
        }

        var vo = currentApplyView(apply, latest);

        assertEquals("UNKNOWN", vo.getDeliveryResolution());
        assertFalse(vo.getUsable());
        assertEquals(latest.getOperationId(), vo.getDeliveryOperationId());
        assertEquals(MarketDeliveryService.DELIVERY_SUCCESS, vo.getTechProvisionStatus());
        verify(deliveryOperationMapper).selectLatestByApply( 1L);
    }

    @ParameterizedTest
    @ValueSource(ints = {MarketDeliveryService.DELIVERY_NONE, MarketDeliveryService.DELIVERY_SUCCESS})
    void sourceRevisionCannotFallbackToLegacyWithoutLedger(int legacyStatus) {
        var apply = currentDetailApply();
        apply.setDeliveryStatus(legacyStatus);

        var vo = currentApplyView(apply, null);

        assertEquals("UNKNOWN", vo.getDeliveryResolution());
        assertFalse(vo.getUsable());
        assertNull(vo.getDeliveryOperationId());
    }

    @ParameterizedTest
    @CsvSource(value = {"0,NULL", "3,APPLIED"}, nullValues = "NULL")
    void zeroSourceRevisionRetainsGenuineLegacyCompatibility(int legacyStatus, String resolution) {
        var apply = currentDetailApply();
        apply.setSourceRevision(0L); // 0042 为历史申请填充的值，不是已交付的 v2 来源版本。
        apply.setDeliveryStatus(legacyStatus);

        var vo = currentApplyView(apply, null);

        assertEquals(resolution, vo.getDeliveryResolution());
        assertTrue(vo.getUsable());
        assertEquals(legacyStatus, vo.getTechProvisionStatus());
        assertNull(vo.getDeliveryOperationId());
        verify(deliveryOperationMapper).selectLatestByApply( 1L);
        verify(approvalEvidenceMapper).selectByApply( 1L);
    }

    @ParameterizedTest
    @ValueSource(ints = {MarketDeliveryService.DELIVERY_NONE, MarketDeliveryService.DELIVERY_SUCCESS})
    void negativeSourceRevisionCannotClaimLegacySuccess(int legacyStatus) {
        var apply = currentDetailApply();
        apply.setSourceRevision(-1L);
        apply.setDeliveryStatus(legacyStatus);

        var vo = currentApplyView(apply, null);

        assertEquals("UNKNOWN", vo.getDeliveryResolution());
        assertFalse(vo.getUsable());
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 2L})
    void genuinePersonalPreviewWithoutApiLedgerOrFormalApprovalRemainsUsable(long sourceRevision) {
        var apply = currentDetailApply();
        apply.setApiId(null);
        apply.setAppId(null);
        apply.setSourceRevision(sourceRevision);
        apply.setDeliveryStatus(MarketDeliveryService.DELIVERY_NONE);

        var vo = currentApplyView(apply, null);

        assertNull(vo.getDeliveryResolution());
        assertTrue(vo.getUsable());
        verify(deliveryOperationMapper).selectLatestByApply( 1L);
        verify(approvalEvidenceMapper).selectByApply( 1L);
    }

    @Test
    void appBindingWithoutApiCannotMasqueradeAsPersonalPreview() {
        var apply = currentDetailApply();
        apply.setApiId(null);

        var vo = currentApplyView(apply, null);

        assertEquals("UNKNOWN", vo.getDeliveryResolution());
        assertFalse(vo.getUsable());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void formalApprovalCannotFallbackWithoutLedgerOrByClearingApi(boolean clearBinding) {
        var apply = currentDetailApply();
        var frozen = MarketDeliveryConfirmationFixture.operation(deliveryService, apply);
        var evidence = new MarketApprovalEvidenceRow();
        evidence.setApprovalRef(apply.getSourceGrantId());
        evidence.setApprovalAssetId(apply.getAssetId());
        evidence.setApprovalScopeHash(MarketDeliveryPayload.approvalHash( apply.getAssetId(),
                MarketDeliveryPayload.fromJson(frozen.getPayloadJson())));
        when(approvalEvidenceMapper.selectByApply( 1L)).thenReturn(evidence);
        apply.setSourceRevision(null);
        if (clearBinding) {
            apply.setApiId(null);
            apply.setAppId(null);
        }

        var vo = currentApplyView(apply, null);

        assertEquals("UNKNOWN", vo.getDeliveryResolution());
        assertFalse(vo.getUsable());
    }

    @ParameterizedTest
    @ValueSource(strings = {"matching", "reference", "asset", "scope"})
    void applyViewRequiresExistingFormalApprovalToMatchCurrentConfirmedGrant(String evidenceCase) {
        var apply = currentDetailApply();
        var latest = MarketDeliveryConfirmationFixture.operation(deliveryService, apply);
        var evidence = new MarketApprovalEvidenceRow();
        evidence.setApprovalRef("reference".equals(evidenceCase) ? "apply:1:r2" : apply.getSourceGrantId());
        evidence.setApprovalAssetId("asset".equals(evidenceCase) ? 9L : apply.getAssetId());
        evidence.setApprovalScopeHash("scope".equals(evidenceCase) ? "different-approved-scope"
                : MarketDeliveryPayload.approvalHash( apply.getAssetId(),
                        MarketDeliveryPayload.fromJson(latest.getPayloadJson())));
        when(approvalEvidenceMapper.selectByApply( 1L)).thenReturn(evidence);

        var vo = currentApplyView(apply, latest);

        boolean matching = "matching".equals(evidenceCase);
        assertEquals(matching ? "APPLIED" : "UNKNOWN", vo.getDeliveryResolution());
        assertEquals(matching, vo.getUsable());
        assertEquals(latest.getOperationId(), vo.getDeliveryOperationId());
        assertEquals(MarketDeliveryService.DELIVERY_SUCCESS, vo.getTechProvisionStatus());
        verify(deliveryOperationMapper).selectLatestByApply( 1L);
        verify(approvalEvidenceMapper).selectByApply( 1L);
    }

    private static MarketResourceApplyDO currentDetailApply() {
        var apply = MarketDeliveryConfirmationFixture.apply();
        apply.setId(1L);
        apply.setSourceGrantId("apply:1:r1");
        // 详情还核对资产发布身份；与本测试资产的空发布版本保持一致。
        apply.setSnapshot(apply.getSnapshot().replace("\"assetId\":\"8\",",
                "\"assetId\":\"8\",\"assetVersion\":\"\",\"catalogVersionId\":\"\",\"publishedSnapshotId\":\"\","));
        return apply;
    }

    private MarketApplyRespVO approvedApplyView(MarketDeliveryOperationDO latest, int deliveryStatus,
                                               boolean missingProof, Consumer<MarketResourceApplyDO> setup) {
        MarketResourceApplyDO apply = currentDetailApply();
        apply.setDeliveryStatus(deliveryStatus);
        apply.setApiId(9L);
        apply.setAppId(4L);
        setup.accept(apply);
        MarketDeliveryOperationDO complete = null;
        if (latest == null) {
            apply.setSourceRevision(null);
        } else {
            // 原有状态/时钟回归也必须使用完整 v2 证据；时间设置完成后冻结，禁止伪造空载荷成功行。
            complete = MarketDeliveryConfirmationFixture.operation(deliveryService, apply,
                    MarketDeliveryOperationDO.TYPE_REVOKE.equals(latest.getOpType())
                            ? cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO.Action.REVOKE
                            : cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO.Action.GRANT);
            complete.setId(latest.getId());
            complete.setStatus(latest.getStatus());
            complete.setResolutionStatus(latest.getResolutionStatus());
            complete.setOpType(latest.getOpType());
            if (latest.getOperationId() != null) {
                complete.setOperationId(latest.getOperationId());
            }
        }
        if (missingProof) {
            // 先冻结原始有效命令，再移除当前证据，真实复现不能沿用旧成功的情形。
            apply.setSnapshot(null);
        }
        return currentApplyView(apply, complete);
    }

    private MarketApplyRespVO currentApplyView(MarketResourceApplyDO apply, MarketDeliveryOperationDO latest) {
        when(applyMapper.selectById(1L)).thenReturn(apply);
        when(assetMapper.selectById(8L)).thenReturn(publishedTable());
        when(processMapper.selectListByApplyId(1L)).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(displayHelper.collect(anyCollection(), any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(displayHelper.userNames(any(Long[].class))).thenReturn(java.util.Map.of());
        org.mockito.Mockito.lenient().when(displayHelper.userLabel(any(), any())).thenReturn("-");
        org.mockito.Mockito.lenient().when(displayHelper.deptNames(org.mockito.ArgumentMatchers.nullable(Long.class)))
                .thenReturn(java.util.Map.of());
        org.mockito.Mockito.lenient().when(displayHelper.deptLabel(any(), any())).thenReturn("-");
        org.mockito.Mockito.lenient().when(displayHelper.assets(anyCollection()))
                .thenReturn(java.util.Map.of(8L, publishedTable()));
        // 交付结果读取是租户内查询：调用方必须已建立可信租户上下文。
        cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.clear();
        try {
            when(deliveryOperationMapper.selectLatestByApply( 1L)).thenReturn(latest);
            return service.getApply(1L);
        } finally {
            cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.clear();
        }
    }

    private static MarketDeliveryOperationDO operation(String status, String resolution) {
        return MarketDeliveryOperationDO.builder().id(11L).applyId(1L)
                .opType(MarketDeliveryOperationDO.TYPE_GRANT).status(status)
                .resolutionStatus(resolution).sourceRevision(2L).build();
    }

    @Test
    void expireDueApplies_revokesOnlyCasWinner() {
        // 到期扫描改为逐租户、按数据库时间、逐申请短事务；调用方必须建立可信租户上下文。
        MarketResourceApplyDO apply = pendingApply();
        apply.setStatus(MarketResourceApplyDO.STATUS_APPROVED);
        cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.clear();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "transactionManager",
                new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
                    @Override
                    protected Object doGetTransaction() {
                        return new Object();
                    }

                    @Override
                    protected void doBegin(Object transaction,
                                           org.springframework.transaction.TransactionDefinition definition) {
                    }

                    @Override
                    protected void doCommit(
                            org.springframework.transaction.support.DefaultTransactionStatus status) {
                    }

                    @Override
                    protected void doRollback(
                            org.springframework.transaction.support.DefaultTransactionStatus status) {
                    }
                });
        try {
            when(applyMapper.selectExpiredDue(
                    org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(apply));
            when(applyMapper.update(isNull(), any())).thenReturn(1);
            when(processMapper.insert(any(MarketCheckProcessDO.class))).thenReturn(1);
            assertEquals(1, service.expireDueApplies());
            verify(deliveryService).enqueueRevoke(any(MarketResourceApplyDO.class));
        } finally {
            cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.clear();
        }
    }

    @Test
    void expireDueApplies_skipsApplicationsAlreadyHandledByAnotherNode() {
        MarketResourceApplyDO apply = pendingApply();
        apply.setStatus(MarketResourceApplyDO.STATUS_APPROVED);
        cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.clear();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "transactionManager",
                new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
                    @Override
                    protected Object doGetTransaction() {
                        return new Object();
                    }

                    @Override
                    protected void doBegin(Object transaction,
                                           org.springframework.transaction.TransactionDefinition definition) {
                    }

                    @Override
                    protected void doCommit(
                            org.springframework.transaction.support.DefaultTransactionStatus status) {
                    }

                    @Override
                    protected void doRollback(
                            org.springframework.transaction.support.DefaultTransactionStatus status) {
                    }
                });
        try {
            when(applyMapper.selectExpiredDue(
                    org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(apply));
            when(applyMapper.update(isNull(), any())).thenReturn(0);
            assertEquals(0, service.expireDueApplies());
            verify(deliveryService, org.mockito.Mockito.never()).enqueueRevoke(any(MarketResourceApplyDO.class));
        } finally {
            cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.clear();
        }
    }

    @Test
    void createApplyFromDemand_copiesDemandFieldsIntoDraft() {
        stubDemandDraft();
        MarketDemandDO demand = new MarketDemandDO();
        demand.setId(44L);
        demand.setTitle("区县办件");
        demand.setPurpose("需求依据");
        demand.setExpectedFields("district_code,month");
        when(demandMapper.selectById(44L)).thenReturn(demand);

        MarketResourceApplyDO result = createDemandApply();

        assertEquals("district_code,month", result.getFieldScope());
        assertEquals("区县办件", result.getDutyMatter());
        assertEquals("需求依据", result.getLegalBasis());
        assertEquals("统计", result.getPurpose());
        verifyDemandDraft(result);
    }

    @ParameterizedTest
    @CsvSource({"0,true", "0,false", "1,true", "1,false",
            "9007199254740993,true", "9007199254740993,false",
            "9223372036854775807,true", "9223372036854775807,false"})
    void createApplyFromDemand_classifiesZeroInsertByExactTenantCompetitorRead(
            long tenantId, boolean competitorPresent) {
        TenantContextHolder.clear();
        stubDemandDraft();
        stubDemandDraftInsert(0, null);
        when(applyMapper.hasActivePendingApply( 3L, 8L)).thenReturn(competitorPresent);
        // 首次候选为空后出现竞争者，不允许重新选择并静默复用另一条申请。
        when(applyMapper.selectActiveByUserAndAsset(3L, 8L))
                .thenReturn(null, reusableDemandApply(MarketResourceApplyDO.STATUS_PENDING));

        ServiceException failure = assertThrows(ServiceException.class, this::createDemandApply);

        assertEquals((competitorPresent ? MARKET_APPLY_DUPLICATE : MARKET_APPLY_STATUS_DENY).getCode(),
                failure.getCode());
        ArgumentCaptor<MarketResourceApplyDO> draft = ArgumentCaptor.forClass(MarketResourceApplyDO.class);
        var calls = verifyDemandDraftInsertion(draft);
        calls.verify(applyMapper).hasActivePendingApply( 3L, 8L);
        assertNull(draft.getValue().getId());
        verifyNoInteractions(processMapper);
        assertNoFurtherDemandDraftEffects();
        assertNull(TenantContextHolder.getTenantId());
        assertFalse(TenantContextHolder.isIgnore());
    }

    @ParameterizedTest
    @CsvSource({"-1,false", "-1,true", "2,false", "2,true",
            "-2147483648,false", "-2147483648,true", "2147483647,false", "2147483647,true"})
    void createApplyFromDemand_rejectsUnexpectedInsertCountEvenWithGeneratedKey(
            int inserted, boolean generatedKeyPresent) {
        stubDemandDraft();
        Long generatedId = generatedKeyPresent ? 77L : null;
        stubDemandDraftInsert(inserted, generatedId);

        assertEquals(MARKET_APPLY_STATUS_DENY.getCode(),
                assertThrows(ServiceException.class, this::createDemandApply).getCode());

        ArgumentCaptor<MarketResourceApplyDO> draft = ArgumentCaptor.forClass(MarketResourceApplyDO.class);
        verifyDemandDraftInsertion(draft);
        assertEquals(generatedId, draft.getValue().getId());
        verifyNoInteractions(processMapper);
        assertNoFurtherDemandDraftEffects();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L})
    void createApplyFromDemand_rejectsSuccessfulInsertWithInvalidGeneratedKey(Long generatedId) {
        stubDemandDraft();
        stubDemandDraftInsert(1, generatedId);

        assertEquals(MARKET_APPLY_STATUS_DENY.getCode(),
                assertThrows(ServiceException.class, this::createDemandApply).getCode());

        ArgumentCaptor<MarketResourceApplyDO> draft = ArgumentCaptor.forClass(MarketResourceApplyDO.class);
        verifyDemandDraftInsertion(draft);
        assertEquals(generatedId, draft.getValue().getId());
        verifyNoInteractions(processMapper);
        assertNoFurtherDemandDraftEffects();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 2})
    void createApplyFromDemand_rejectsIncompleteOrUnexpectedProcessInsertCount(int inserted) {
        stubDemandDraft();
        when(processMapper.insert(any(MarketCheckProcessDO.class))).thenReturn(inserted);

        assertEquals(MARKET_APPLY_STATUS_DENY.getCode(),
                assertThrows(ServiceException.class, this::createDemandApply).getCode());

        ArgumentCaptor<MarketResourceApplyDO> draft = ArgumentCaptor.forClass(MarketResourceApplyDO.class);
        var calls = verifyDemandDraftInsertion(draft);
        assertEquals(77L, draft.getValue().getId());
        verifyDemandDraftProcess(calls, 77L);
        assertNoFurtherDemandDraftEffects();
        // 单测只证明失败契约和不重投；草稿与过程的真实回滚由 PostgreSQL 用例验证。
    }

    @ParameterizedTest
    @CsvSource({"INSERT,RUNTIME", "INSERT,DUPLICATE", "REREAD,RUNTIME", "REREAD,DUPLICATE",
            "PROCESS,RUNTIME", "PROCESS,DUPLICATE"})
    void createApplyFromDemand_propagatesStageFailureWithoutRetryReselectOrLaterEffects(
            String stage, String failureType) {
        stubDemandDraft();
        RuntimeException failure = "DUPLICATE".equals(failureType)
                ? new DuplicateKeyException("test unrelated demand draft constraint")
                : new IllegalStateException("test demand draft stage failure");
        switch (stage) {
            case "INSERT" -> doThrow(failure).when(applyMapper)
                    .insertInitialApplyIfAbsent(any(MarketResourceApplyDO.class));
            case "REREAD" -> {
                stubDemandDraftInsert(0, null);
                when(applyMapper.hasActivePendingApply( 3L, 8L)).thenThrow(failure);
            }
            case "PROCESS" -> when(processMapper.insert(any(MarketCheckProcessDO.class)))
                    .thenThrow(failure);
            default -> throw new AssertionError(stage);
        }

        assertSame(failure, assertThrows(RuntimeException.class, this::createDemandApply));

        ArgumentCaptor<MarketResourceApplyDO> draft = ArgumentCaptor.forClass(MarketResourceApplyDO.class);
        var calls = verifyDemandDraftInsertion(draft);
        if ("REREAD".equals(stage)) {
            calls.verify(applyMapper).hasActivePendingApply( 3L, 8L);
        }
        if ("PROCESS".equals(stage)) {
            assertEquals(77L, draft.getValue().getId());
            verifyDemandDraftProcess(calls, 77L);
        } else {
            assertNull(draft.getValue().getId());
            verifyNoInteractions(processMapper);
        }
        assertNoFurtherDemandDraftEffects();
    }

    @ParameterizedTest
    @ValueSource(longs = {2147483648L, 9007199254740993L, Long.MAX_VALUE})
    void createApplyFromDemand_preservesGeneratedKeyInReturnedDraftAndProcess(long generatedId) {
        stubDemandDraft();
        stubDemandDraftInsert(1, generatedId);

        MarketResourceApplyDO result = createDemandApply();

        assertEquals(generatedId, result.getId());
        ArgumentCaptor<MarketResourceApplyDO> draft = ArgumentCaptor.forClass(MarketResourceApplyDO.class);
        var calls = verifyDemandDraftInsertion(draft);
        assertSame(result, draft.getValue());
        verifyDemandDraftProcess(calls, generatedId);
        assertNoFurtherDemandDraftEffects();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 8})
    void createApplyFromDemand_reusesEachActiveStatusFromLockedCurrent(int status) {
        MarketResourceApplyDO candidate = reusableDemandApply(status);
        MarketResourceApplyDO current = reusableDemandApply(status);
        stubDemandReuse(candidate, current);

        assertSame(current, createDemandApply());

        assertEquals(99L, current.getDemandId());
        assertEquals(200L, current.getApplyDeptId());
        assertEquals("原申请用途", current.getPurpose());
        verifyDemandReuseReads(1L, 1L, 3L, 8L);
    }

    @ParameterizedTest
    @CsvSource({"0,0", "0,1", "0,8", "1,0", "1,1", "1,8", "8,0", "8,1", "8,8"})
    void createApplyFromDemand_returnsFreshFieldsAndAllowsActiveStateChanges(
            int candidateStatus, int currentStatus) {
        MarketResourceApplyDO candidate = reusableDemandApply(candidateStatus);
        MarketResourceApplyDO current = reusableDemandApply(currentStatus);
        current.setApplyNo("APP-CURRENT");
        current.setTitle("锁内当前标题");
        current.setPurpose("锁内当前用途");
        current.setRevision(7);
        current.setDemandId(77L);
        current.setApplyDeptId(300L);
        current.setFieldScope("current_column");
        current.setDutyMatter("当前事项");
        current.setLegalBasis("当前依据");
        stubDemandReuse(candidate, current);

        MarketResourceApplyDO result = createDemandApply();

        assertSame(current, result);
        assertEquals(currentStatus, result.getStatus());
        assertEquals("APP-CURRENT", result.getApplyNo());
        assertEquals("锁内当前标题", result.getTitle());
        assertEquals("锁内当前用途", result.getPurpose());
        assertEquals(7, result.getRevision());
        assertEquals(77L, result.getDemandId());
        assertEquals(300L, result.getApplyDeptId());
        assertEquals("current_column", result.getFieldScope());
        assertEquals("当前事项", result.getDutyMatter());
        assertEquals("当前依据", result.getLegalBasis());
        assertEquals(candidateStatus, candidate.getStatus());
        assertEquals(1, candidate.getRevision());
        assertEquals(99L, candidate.getDemandId());
        assertEquals("原申请用途", candidate.getPurpose());
        verifyDemandReuseReads(1L, 1L, 3L, 8L);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, -1, 99})
    void createApplyFromDemand_doesNotUseOldCandidateStatusAsAdmissionRule(Integer candidateStatus) {
        // 候选只标识要复验的行；状态以锁内当前记录为准，不把旧对象当授权快照。
        MarketResourceApplyDO candidate = reusableDemandApply(MarketResourceApplyDO.STATUS_DRAFT);
        candidate.setStatus(candidateStatus);
        MarketResourceApplyDO current = reusableDemandApply(MarketResourceApplyDO.STATUS_PENDING);
        stubDemandReuse(candidate, current);

        assertSame(current, createDemandApply());

        verifyDemandReuseReads(1L, 1L, 3L, 8L);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1, 2, 3, 4, 5, 6, 7, 9, Integer.MAX_VALUE})
    void createApplyFromDemand_rejectsInactiveOrNullLockedStatus(Integer currentStatus) {
        MarketResourceApplyDO current = reusableDemandApply(MarketResourceApplyDO.STATUS_PENDING);
        current.setStatus(currentStatus);
        stubDemandReuse(reusableDemandApply(MarketResourceApplyDO.STATUS_PENDING), current);

        assertDemandReuseDenied();

        verifyDemandReuseReads(1L, 1L, 3L, 8L);
    }

    @Test
    void createApplyFromDemand_usesCandidateOnlyToIdentifyTheLockedRow() {
        MarketResourceApplyDO candidate = new MarketResourceApplyDO();
        candidate.setId(1L);
        MarketResourceApplyDO current = reusableDemandApply(MarketResourceApplyDO.STATUS_SUPPLEMENT);
        stubDemandReuse(candidate, current);

        assertSame(current, createDemandApply());

        assertNull(candidate.getStatus());
        assertNull(candidate.getRevision());
        assertNull(candidate.getPurpose());
        assertNull(candidate.getApplyDeptId());
        assertNull(candidate.getDemandId());
        verifyDemandReuseReads(1L, 1L, 3L, 8L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "DELETED", "ID_NULL", "ID_CHANGED", "USER_NULL",
            "USER_CHANGED", "ASSET_NULL", "ASSET_CHANGED"})
    void createApplyFromDemand_rejectsDisappearedDeletedOrReboundLockedRow(String change) {
        MarketResourceApplyDO current = reusableDemandApply(MarketResourceApplyDO.STATUS_PENDING);
        switch (change) {
            case "MISSING" -> current = null;
            case "DELETED" -> current.setDeleted(true);
            case "ID_NULL" -> current.setId(null);
            case "ID_CHANGED" -> current.setId(2L);
            case "USER_NULL" -> current.setApplyUserId(null);
            case "USER_CHANGED" -> current.setApplyUserId(4L);
            case "ASSET_NULL" -> current.setAssetId(null);
            case "ASSET_CHANGED" -> current.setAssetId(9L);
            default -> throw new AssertionError(change);
        }
        stubDemandReuse(reusableDemandApply(MarketResourceApplyDO.STATUS_PENDING), current);

        assertDemandReuseDenied();

        verifyDemandReuseReads(1L, 1L, 3L, 8L);
    }

    @Test
    void createApplyFromDemand_rejectsCandidateWithoutIdBeforeLockingOrCreatingDraft() {
        MarketResourceApplyDO candidate = reusableDemandApply(MarketResourceApplyDO.STATUS_PENDING);
        candidate.setId(null);
        when(assetMapper.selectById(8L)).thenReturn(publishedTable());
        when(applyMapper.selectActiveByUserAndAsset(3L, 8L)).thenReturn(candidate);

        assertDemandReuseDenied();

        var calls = inOrder(assetMapper, applyMapper);
        calls.verify(assetMapper).selectById(8L);
        calls.verify(applyMapper).selectActiveByUserAndAsset(3L, 8L);
        verifyNoMoreInteractions(assetMapper, applyMapper);
        assertNoDemandApplySideEffects();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createApplyFromDemand_neverSelectsSecondCandidateEvenIfFirstBecomesInvalid(boolean invalidCurrent) {
        MarketResourceApplyDO candidate = reusableDemandApply(MarketResourceApplyDO.STATUS_PENDING);
        MarketResourceApplyDO current = reusableDemandApply(MarketResourceApplyDO.STATUS_DRAFT);
        MarketResourceApplyDO secondCandidate = reusableDemandApply(MarketResourceApplyDO.STATUS_PENDING);
        secondCandidate.setId(2L);
        stubDemandReuse(candidate, current);
        when(applyMapper.selectActiveByUserAndAsset(3L, 8L)).thenReturn(candidate, secondCandidate);
        if (invalidCurrent) {
            current.setStatus(MarketResourceApplyDO.STATUS_CANCELLED);
            assertDemandReuseDenied();
        } else {
            assertSame(current, createDemandApply());
        }

        verifyDemandReuseReads(1L, 1L, 3L, 8L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOCK", "RUNTIME", "ERROR"})
    void createApplyFromDemand_propagatesLockFailureWithoutRetryOrWrite(String failureType) {
        Throwable failure = switch (failureType) {
            case "LOCK" -> new CannotAcquireLockException("test demand reuse lock failed");
            case "RUNTIME" -> new IllegalStateException("test demand reuse read failed");
            case "ERROR" -> new AssertionError("test demand reuse fatal failure");
            default -> throw new AssertionError(failureType);
        };
        stubDemandReuse(reusableDemandApply(MarketResourceApplyDO.STATUS_PENDING), null);
        when(applyMapper.selectOwnedForRenewal( 1L, 3L)).thenThrow(failure);

        assertSame(failure, assertThrows(failure.getClass(), this::createDemandApply));

        verifyDemandReuseReads(1L, 1L, 3L, 8L);
    }

    @ParameterizedTest
    @CsvSource({"0,true", "0,false", "9007199254740993,true", "9007199254740993,false",
            "9223372036854775807,true", "9223372036854775807,false"})
    void createApplyFromDemand_usesExactTrustedTenantForReuseOrDraft(long tenantId, boolean reuse) {
        TenantContextHolder.clear();
        if (reuse) {
            MarketResourceApplyDO candidate = reusableDemandApply(MarketResourceApplyDO.STATUS_PENDING);
            MarketResourceApplyDO current = reusableDemandApply(MarketResourceApplyDO.STATUS_PENDING);
            when(assetMapper.selectById(8L)).thenReturn(publishedTable());
            when(applyMapper.selectActiveByUserAndAsset(3L, 8L)).thenReturn(candidate);
            when(applyMapper.selectOwnedForRenewal( 1L, 3L)).thenReturn(current);

            assertSame(current, createDemandApply());

            verifyDemandReuseReads(tenantId, 1L, 3L, 8L);
        } else {
            stubDemandDraft();
            verifyDemandDraft(createDemandApply());
        }
        assertNull(TenantContextHolder.getTenantId());
        assertFalse(TenantContextHolder.isIgnore());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "DRAFT", "OFFLINE", "NULL_STATUS", "UNKNOWN_STATUS"})
    void createApplyFromDemand_retainsPublishedAssetValidationBeforeCandidateRead(String assetState) {
        DataAssetDO asset = publishedTable();
        switch (assetState) {
            case "MISSING" -> asset = null;
            case "DRAFT" -> asset.setStatus(0);
            case "OFFLINE" -> asset.setStatus(2);
            case "NULL_STATUS" -> asset.setStatus(null);
            case "UNKNOWN_STATUS" -> asset.setStatus(99);
            default -> throw new AssertionError(assetState);
        }
        when(assetMapper.selectById(8L)).thenReturn(asset);

        ServiceException failure = assertThrows(ServiceException.class, this::createDemandApply);

        assertEquals(("MISSING".equals(assetState)
                ? DATA_ASSET_NOT_EXISTS : MARKET_APPLY_ASSET_NOT_PUBLISHED).getCode(), failure.getCode());
        verify(assetMapper).selectById(8L);
        verifyNoMoreInteractions(assetMapper);
        verifyNoInteractions(applyMapper);
        assertNoDemandApplySideEffects();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createApplyFromDemand_preservesMissingDemandAndBlankTitleDraftBehavior(boolean missingDemand) {
        stubDemandDraft();
        if (!missingDemand) {
            MarketDemandDO demand = new MarketDemandDO();
            demand.setId(44L);
            demand.setTitle("  ");
            demand.setPurpose("需求依据");
            when(demandMapper.selectById(44L)).thenReturn(demand);
        }

        MarketResourceApplyDO result = createDemandApply();

        assertNull(result.getFieldScope());
        assertEquals(missingDemand ? null : "统计", result.getDutyMatter());
        assertEquals(missingDemand ? null : "需求依据", result.getLegalBasis());
        verifyDemandDraft(result);
    }

    @Test
    void createApplyFromDemand_keepsRequiredTransactionAndBoundedTimeoutContract() throws Exception {
        Transactional transaction = MarketApplyServiceImpl.class.getMethod("createApplyFromDemand",
                Long.class, Long.class, Long.class, Long.class, String.class).getAnnotation(Transactional.class);

        assertNotNull(transaction);
        assertEquals(Propagation.REQUIRED, transaction.propagation());
        assertEquals(10, transaction.timeout());
        assertFalse(transaction.readOnly());
        assertTrue(Arrays.asList(transaction.rollbackFor()).contains(Exception.class));
        // 这里只核对注解契约，真实代理、锁等待与外层提交由父线程的 PostgreSQL 测试证明。
    }

    private MarketApplySaveReqVO stubInitialApply(Boolean submit) {
        DataAssetDO asset = publishedTable();
        asset.setPublishedCatalogVersionId(27L);
        asset.setPublishedSnapshotId(28L);
        asset.setPublishedVersion("v3");
        asset.setShareAttr(DataAssetDO.SHARE_UNCONDITIONAL);
        when(objectGuard.requireUserId()).thenReturn(3L);
        when(assetMapper.selectById(8L)).thenReturn(asset);
        when(applyMapper.selectActiveByUserAndAsset(3L, 8L)).thenReturn(null);
        when(applyMapper.nextApplyNoSeq()).thenReturn(11L);
        when(displayHelper.nextBizNo("APP", 11L)).thenReturn("APP11");
        when(adminUserApi.getUser(3L)).thenReturn(CommonResult.success(null));
        MetadataColumnRespDTO column = new MetadataColumnRespDTO();
        column.setColumnName("id");
        when(catalogService.listColumns(8L, 28L)).thenReturn(List.of(column));
        when(workingDayService.plusWorkingDays(any(LocalDateTime.class), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(call -> ((LocalDateTime) call.getArgument(0)).plusDays((int) call.getArgument(1)));
        when(processMapper.insert(any(MarketCheckProcessDO.class))).thenReturn(1);

        MarketApplySaveReqVO req = new MarketApplySaveReqVO();
        req.setTitle("普通申请");
        req.setAssetId(8L);
        req.setDemandId(44L);
        req.setApplyDeptId(100L);
        req.setPhone("TEST-CONTACT");
        req.setEmail("applicant@example.invalid");
        req.setPurpose("统计");
        req.setDutyMatter("测试职责事项");
        req.setLegalBasis("测试使用依据");
        req.setFieldScope("id");
        req.setApplyNote("测试申请说明");
        req.setApplyStartTime(LocalDateTime.of(2026, 9, 1, 0, 0));
        req.setApplyEndTime(LocalDateTime.of(2027, 9, 1, 0, 0));
        req.setApplyUseTimes(0);
        req.setRemark("测试备注");
        req.setSubmit(submit);
        stubInitialApplyInsert(req, 1, 77L);
        return req;
    }

    private void stubInitialApiBinding() {
        when(endpointMapper.selectByAssetAndApi(8L, 9L)).thenReturn(new MarketResourceEndpointDO());
        SupplyAppRespDTO app = new SupplyAppRespDTO();
        app.setId(4L);
        when(supplyAppApi.listAppsByCreator(3L)).thenReturn(CommonResult.success(List.of(app)));
        SupplyApiBindingRespDTO binding = tableQueryBinding();
        binding.setSchemaName("public");
        binding.setFieldMappingVerified(true);
        binding.setActiveReleaseId(29L);
        binding.setContractHash("test-contract-hash");
        binding.setVersion("api-v2");
        binding.setOutputFieldMapping(java.util.Map.of("id", "id"));
        when(supplyApiGuard.requireQueryableTableApi(9L)).thenReturn(binding);
    }

    private void stubInitialApplyInsert(MarketApplySaveReqVO req, int inserted, Long generatedId) {
        doAnswer(call -> {
            MarketResourceApplyDO apply = call.getArgument(0);
            assertNull(apply.getId(), "普通创建必须清除客户端主键，由数据库返回生成主键");
            assertInitialApplyFieldsAtInsert(apply, req);
            apply.setId(generatedId);
            return inserted;
        }).when(applyMapper).insertInitialApplyIfAbsent(any(MarketResourceApplyDO.class));
    }

    private static void assertInitialApplyFieldsAtInsert(MarketResourceApplyDO apply, MarketApplySaveReqVO req) {
        assertEquals("APP11", apply.getApplyNo());
        assertEquals(req.getTitle(), apply.getTitle());
        assertEquals(req.getAssetId(), apply.getAssetId());
        assertEquals(DataAssetDO.TYPE_DATABASE_TABLE, apply.getAssetType());
        assertEquals(req.getApiId(), apply.getApiId());
        assertEquals(req.getAppId(), apply.getAppId());
        assertEquals(req.getDemandId(), apply.getDemandId());
        assertEquals(3L, apply.getApplyUserId());
        assertEquals(req.getApplyDeptId(), apply.getApplyDeptId());
        assertEquals(req.getPhone(), apply.getPhone());
        assertEquals(req.getEmail(), apply.getEmail());
        assertEquals(req.getPurpose(), apply.getPurpose());
        assertEquals(req.getDutyMatter(), apply.getDutyMatter());
        assertEquals(req.getLegalBasis(), apply.getLegalBasis());
        assertEquals(req.getFieldScope(), apply.getFieldScope());
        assertEquals(req.getApplyNote(), apply.getApplyNote());
        assertEquals(req.getApplyStartTime(), apply.getApplyStartTime());
        assertEquals(req.getApplyEndTime(), apply.getApplyEndTime());
        assertEquals(MarketResourceApplyDO.USE_UNLIMITED, apply.getApplyUseType());
        assertEquals(req.getApplyUseTimes(), apply.getApplyUseTimes());
        assertEquals(req.getRemark(), apply.getRemark());
        assertEquals(MarketResourceApplyDO.AUTH_NO, apply.getIfAuth());
        assertEquals(1, apply.getRevision());
        assertEquals(0, apply.getPaused());
        assertEquals(0, apply.getDeliveryStatus());
        if (Boolean.FALSE.equals(req.getSubmit())) {
            assertEquals(MarketResourceApplyDO.STATUS_DRAFT, apply.getStatus());
            assertNull(apply.getSnapshot());
            assertNull(apply.getCatalogVersionId());
            assertNull(apply.getReceivedTime());
            assertNull(apply.getReplyDeadline());
            assertNull(apply.getDeliveryDeadline());
            return;
        }
        assertEquals(MarketResourceApplyDO.STATUS_PENDING, apply.getStatus());
        assertEquals(27L, apply.getCatalogVersionId());
        assertNotNull(apply.getReceivedTime());
        assertEquals(apply.getReceivedTime().plusDays(1), apply.getReplyDeadline());
        assertEquals(apply.getReceivedTime().plusDays(21), apply.getDeliveryDeadline());
        assertNotNull(apply.getSnapshot(), "提交快照必须已存在于唯一一次 INSERT 的入参中");
        var snapshot = JsonUtils.parseObject(apply.getSnapshot(), java.util.Map.class);
        assertEquals("8", snapshot.get("assetId"));
        assertEquals("v3", snapshot.get("assetVersion"));
        assertEquals("27", snapshot.get("catalogVersionId"));
        assertEquals("28", snapshot.get("publishedSnapshotId"));
        assertEquals("1", snapshot.get("revision"));
        assertEquals("20", snapshot.get("databaseId"));
        assertEquals("public", snapshot.get("schemaName"));
        assertEquals("t_person", snapshot.get("tableName"));
        assertEquals(req.getDutyMatter(), snapshot.get("dutyMatter"));
        assertEquals(req.getFieldScope(), snapshot.get("fieldScope"));
        assertEquals(List.of("id"), snapshot.get("frozenColumns"));
        assertEquals(req.getApiId() == null ? "" : String.valueOf(req.getApiId()), snapshot.get("apiId"));
        assertEquals(req.getAppId() == null ? "" : String.valueOf(req.getAppId()), snapshot.get("appId"));
        if (req.getApiId() != null) {
            assertEquals("29", snapshot.get("activeReleaseId"));
            assertEquals("test-contract-hash", snapshot.get("approvedContractHash"));
            assertEquals("api-v2", snapshot.get("apiVersion"));
        }
    }

    private InOrder verifyInitialApplyInsertion(boolean submitted) {
        var calls = inOrder(applyMapper, catalogService);
        calls.verify(applyMapper).selectActiveByUserAndAsset(3L, 8L);
        calls.verify(applyMapper).nextApplyNoSeq();
        if (submitted) {
            calls.verify(catalogService).listColumns(8L, 28L);
        }
        calls.verify(applyMapper).insertInitialApplyIfAbsent(any(MarketResourceApplyDO.class));
        return calls;
    }

    private void verifyInitialApplyProcess(long generatedId) {
        ArgumentCaptor<MarketCheckProcessDO> process = ArgumentCaptor.forClass(MarketCheckProcessDO.class);
        verify(processMapper).insert(process.capture());
        assertInitialApplyProcess(process.getValue(), generatedId);
    }

    private static void assertInitialApplyProcess(MarketCheckProcessDO process, long generatedId) {
        assertEquals(generatedId, process.getResourceApplyId());
        assertEquals(MarketCheckProcessDO.ACTION_SUBMIT, process.getAction());
        assertNull(process.getFromStatus());
        assertEquals(MarketResourceApplyDO.STATUS_PENDING, process.getToStatus());
        assertEquals(3L, process.getOperatorUserId());
        assertEquals("提交申请", process.getNote());
    }

    private void verifyInitialApplySubmitEffects(long generatedId) {
        var calls = inOrder(applyMapper, processMapper, notifyHelper, auditRecorder);
        calls.verify(applyMapper).insertInitialApplyIfAbsent(any(MarketResourceApplyDO.class));
        ArgumentCaptor<MarketCheckProcessDO> process = ArgumentCaptor.forClass(MarketCheckProcessDO.class);
        calls.verify(processMapper).insert(process.capture());
        assertInitialApplyProcess(process.getValue(), generatedId);
        ArgumentCaptor<MarketResourceApplyDO> notified = ArgumentCaptor.forClass(MarketResourceApplyDO.class);
        calls.verify(notifyHelper).notifyApplySubmit(notified.capture(), any(DataAssetDO.class));
        assertEquals(generatedId, notified.getValue().getId());
        assertNotNull(notified.getValue().getSnapshot());
        calls.verify(auditRecorder).record("SUBMIT", "APPLY", generatedId, null,
                MarketResourceApplyDO.STATUS_PENDING, "统计", "OK");
        verifyNoInteractions(demandMapper, deliveryService, deliveryCoordinator, deliveryEvidenceService,
                approvalEvidenceMapper, deliveryOperationMapper, assetMonitorService, accessService);
    }

    private void verifyInitialApplyReadsBeforeFailedSnapshot() {
        var calls = inOrder(applyMapper, catalogService);
        calls.verify(applyMapper).selectActiveByUserAndAsset(3L, 8L);
        calls.verify(applyMapper).nextApplyNoSeq();
        calls.verify(catalogService).listColumns(8L, 28L);
        verify(catalogService, org.mockito.Mockito.atMostOnce()).requireCompletePublishedAsset(any());
        verifyNoMoreInteractions(applyMapper, catalogService);
        verifyNoInteractions(processMapper);
        assertNoInitialApplySuccessEffects();
    }

    private void assertNoInitialApplySuccessEffects() {
        verifyNoInteractions(notifyHelper, auditRecorder, demandMapper, deliveryService,
                deliveryCoordinator, deliveryEvidenceService, approvalEvidenceMapper,
                deliveryOperationMapper, assetMonitorService, accessService);
    }

    private MarketResourceApplyDO createDemandApply() {
        return service.createApplyFromDemand(44L, 8L, 3L, 100L, "统计");
    }

    private static MarketResourceApplyDO reusableDemandApply(int status) {
        MarketResourceApplyDO apply = pendingApply();
        apply.setStatus(status);
        apply.setDeleted(false);
        apply.setApplyNo("APP-ORIGINAL");
        apply.setTitle("原申请标题");
        apply.setDemandId(99L);
        apply.setApplyDeptId(200L);
        apply.setPurpose("原申请用途");
        return apply;
    }

    private void stubDemandReuse(MarketResourceApplyDO candidate, MarketResourceApplyDO current) {
        when(assetMapper.selectById(8L)).thenReturn(publishedTable());
        when(applyMapper.selectActiveByUserAndAsset(3L, 8L)).thenReturn(candidate);
        when(applyMapper.selectOwnedForRenewal( 1L, 3L)).thenReturn(current);
    }

    private void assertDemandReuseDenied() {
        assertEquals(MARKET_APPLY_STATUS_DENY.getCode(),
                assertThrows(ServiceException.class, this::createDemandApply).getCode());
    }

    private void verifyDemandReuseReads(long tenantId, long applyId, long userId, long assetId) {
        var calls = inOrder(assetMapper, applyMapper);
        calls.verify(assetMapper).selectById(assetId);
        calls.verify(applyMapper).selectActiveByUserAndAsset(userId, assetId);
        calls.verify(applyMapper).selectOwnedForRenewal( applyId, userId);
        // 未验证的任何调用都会失败：包括重查候选、重试锁、另建草稿和修改旧申请。
        verifyNoMoreInteractions(assetMapper, applyMapper);
        assertNoDemandApplySideEffects();
    }

    private void assertNoDemandApplySideEffects() {
        verifyNoInteractions(demandMapper, processMapper, displayHelper);
        assertNoDemandApplyExternalEffects();
    }

    private void assertNoDemandApplyExternalEffects() {
        verifyNoInteractions(objectGuard, endpointMapper, supplyAppApi, supplyApiGuard,
                notifyHelper, deliveryService, deliveryCoordinator, deliveryEvidenceService,
                approvalEvidenceMapper, deliveryOperationMapper, workingDayService,
                auditRecorder, assetMonitorService, accessService);
    }

    private void stubDemandDraft() {
        DataAssetDO asset = publishedTable();
        asset.setAssetName("示例表");
        when(assetMapper.selectById(8L)).thenReturn(asset);
        when(applyMapper.selectActiveByUserAndAsset(3L, 8L)).thenReturn(null);
        when(applyMapper.nextApplyNoSeq()).thenReturn(11L);
        when(displayHelper.nextBizNo("APP", 11L)).thenReturn("APP11");
        stubDemandDraftInsert(1, 77L);
        when(processMapper.insert(any(MarketCheckProcessDO.class))).thenReturn(1);
    }

    private void stubDemandDraftInsert(int inserted, Long generatedId) {
        doAnswer(call -> {
            MarketResourceApplyDO apply = call.getArgument(0);
            assertNull(apply.getId(), "需求草稿应使用数据库返回的主键，不得预先分配或重投旧主键");
            assertDemandDraftFields(apply);
            apply.setId(generatedId);
            return inserted;
        }).when(applyMapper).insertInitialApplyIfAbsent(any(MarketResourceApplyDO.class));
    }

    private void verifyDemandDraft(MarketResourceApplyDO draft) {
        assertEquals(77L, draft.getId());
        ArgumentCaptor<MarketResourceApplyDO> inserted = ArgumentCaptor.forClass(MarketResourceApplyDO.class);
        var calls = verifyDemandDraftInsertion(inserted);
        assertSame(draft, inserted.getValue());
        verifyDemandDraftProcess(calls, 77L);
        assertNoFurtherDemandDraftEffects();
    }

    private static void assertDemandDraftFields(MarketResourceApplyDO draft) {
        assertEquals("APP11", draft.getApplyNo());
        assertEquals("需求对接申请：示例表", draft.getTitle());
        assertEquals(8L, draft.getAssetId());
        assertEquals(DataAssetDO.TYPE_DATABASE_TABLE, draft.getAssetType());
        assertEquals(44L, draft.getDemandId());
        assertEquals(3L, draft.getApplyUserId());
        assertEquals(100L, draft.getApplyDeptId());
        assertEquals("统计", draft.getPurpose());
        assertEquals(MarketResourceApplyDO.STATUS_DRAFT, draft.getStatus());
        assertEquals(MarketResourceApplyDO.AUTH_NO, draft.getIfAuth());
        assertEquals(MarketResourceApplyDO.USE_UNLIMITED, draft.getApplyUseType());
        assertEquals(1, draft.getRevision());
        assertEquals(0, draft.getPaused());
        assertEquals(0, draft.getDeliveryStatus());
    }

    private InOrder verifyDemandDraftInsertion(ArgumentCaptor<MarketResourceApplyDO> draft) {
        var calls = inOrder(assetMapper, applyMapper, displayHelper, demandMapper, processMapper);
        calls.verify(assetMapper).selectById(8L);
        calls.verify(applyMapper).selectActiveByUserAndAsset(3L, 8L);
        calls.verify(applyMapper).nextApplyNoSeq();
        calls.verify(displayHelper).nextBizNo("APP", 11L);
        calls.verify(demandMapper).selectById(44L);
        calls.verify(applyMapper).insertInitialApplyIfAbsent(draft.capture());
        assertDemandDraftFields(draft.getValue());
        return calls;
    }

    private void verifyDemandDraftProcess(InOrder calls, long generatedId) {
        ArgumentCaptor<MarketCheckProcessDO> process = ArgumentCaptor.forClass(MarketCheckProcessDO.class);
        calls.verify(processMapper).insert(process.capture());
        assertEquals(generatedId, process.getValue().getResourceApplyId());
        assertEquals(MarketCheckProcessDO.ACTION_SUBMIT, process.getValue().getAction());
        assertNull(process.getValue().getFromStatus());
        assertEquals(MarketResourceApplyDO.STATUS_DRAFT, process.getValue().getToStatus());
        assertEquals(3L, process.getValue().getOperatorUserId());
        assertEquals("需求匹配确认后生成申请草稿", process.getValue().getNote());
    }

    private void assertNoFurtherDemandDraftEffects() {
        verifyNoMoreInteractions(assetMapper, applyMapper, displayHelper, demandMapper, processMapper);
        assertNoDemandApplyExternalEffects();
    }

    private static MarketResourceApplyDO pendingApply() {
        MarketResourceApplyDO apply = new MarketResourceApplyDO();
        apply.setId(1L);
        apply.setAssetId(8L);
        apply.setApplyUserId(3L);
        apply.setStatus(MarketResourceApplyDO.STATUS_PENDING);
        apply.setRevision(1);
        apply.setPaused(0);
        apply.setSnapshot("""
                {"assetId":"8","assetVersion":"","catalogVersionId":"","publishedSnapshotId":"",
                 "databaseId":"20","schemaName":"public","tableName":"t_person","frozenColumns":["id"]}
                """);
        return apply;
    }

    private static MarketApplySaveReqVO apiReq() {
        MarketApplySaveReqVO reqVO = new MarketApplySaveReqVO();
        reqVO.setAssetId(8L);
        reqVO.setTitle("申请");
        reqVO.setApiId(9L);
        reqVO.setAppId(4L);
        return reqVO;
    }

    private static DataAssetDO publishedTable() {
        return DataAssetDO.builder()
                .id(8L)
                .assetType(DataAssetDO.TYPE_DATABASE_TABLE)
                .status(DataAssetDO.STATUS_PUBLISHED)
                .datasourceId(20L)
                .schemaName("public")
                .tableName("t_person")
                .build();
    }

    private static SupplyApiBindingRespDTO tableQueryBinding() {
        SupplyApiBindingRespDTO binding = new SupplyApiBindingRespDTO();
        binding.setId(9L);
        binding.setStatus(1);
        binding.setApiType("QUERY");
        binding.setConfigType("TABLE");
        binding.setDatabaseId(20L);
        binding.setTableName("t_person");
        return binding;
    }

}
