package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketCheckProcessDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceEndpointDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketCheckProcessMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceEndpointMapper;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
import cn.iocoder.yudao.module.supply.api.app.SupplyAppApi;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyAppRespDTO;
import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyApiBindingRespDTO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 独立提交草稿的服务编排契约：只验证校验、CAS 参数和调用顺序。
 * 不启动 Spring 事务或数据库；JSONB 执行、并发竞争、状态/过程回滚与提交后通知由真实 PG 测试证明。
 */
class MarketApplySubmissionTest extends BaseMockitoUnitTest {

    private void verifyPublicationReadOnly() {
        verify(catalogService).requireCompletePublishedAsset(any(DataAssetDO.class));
        verifyNoMoreInteractions(catalogService);
    }

    private static final long APPLY_ID = 77L;
    private static final long USER_ID = 11L;
    private static final long ASSET_ID = 8L;
    private static final long CATALOG_VERSION_ID = 9007199254740995L;
    private static final long SNAPSHOT_ID = 9007199254740997L;
    private static final long DATABASE_ID = 9007199254740999L;
    private static final long API_ID = 9007199254741001L;
    private static final long APP_ID = 9007199254741003L;
    private static final long RELEASE_ID = 9007199254741005L;
    private static final String DUTY_MATTER = "测试职责 ' ); SELECT 1; --\n\"字段\"";

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
    private MarketObjectGuard objectGuard;
    @Mock
    private SupplyAppApi supplyAppApi;
    @Mock
    private MarketSupplyApiGuard supplyApiGuard;
    @Mock
    private AssetCatalogService catalogService;
    @Mock
    private MarketWorkingDayService workingDayService;
    @Mock
    private MarketAuditRecorder auditRecorder;
    @Mock
    private MarketNotifyHelper notifyHelper;

    // These tests isolate workflow orchestration; publication completeness has its own provider tests.
    private final java.util.Map<Long, DataAssetDO> publishedAssets = new java.util.HashMap<>();

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                MarketResourceApplyDO.class);
    }

    @BeforeEach
    void establishTenant() {
        TenantContextHolder.clear();
        TenantContextHolder.clear();
        publishedAssets.clear();
        catalogService = mock(AssetCatalogService.class, call ->
                "requireCompletePublishedAsset".equals(call.getMethod().getName())
                        ? call.getArgument(0) : org.mockito.Answers.RETURNS_DEFAULTS.answer(call));
        assetMapper = mock(DataAssetMapper.class, call ->
                "selectForMatching".equals(call.getMethod().getName())
                        ? publishedAssets.get((Long) call.getArgument(0)) : org.mockito.Answers.RETURNS_DEFAULTS.answer(call));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "catalogService", catalogService);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "assetMapper", assetMapper);
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 2})
    void processMustInsertExactlyOneRowBeforeSuccessEffects(int rows) {
        MarketResourceApplyDO apply = prepareSubmission();
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(processMapper.insert(any(MarketCheckProcessDO.class))).thenReturn(rows);

        assertBusinessFailure(MARKET_APPLY_STATUS_DENY, () -> service.submitApply(APPLY_ID));

        assertCas(captureOnlyCas(APPLY_ID), apply, 1L);
        assertProcess(captureOnlyProcess(), APPLY_ID, USER_ID);
        var order = inOrder(applyMapper, processMapper);
        order.verify(applyMapper).update(isNull(), any(Wrapper.class));
        order.verify(processMapper).insert(any(MarketCheckProcessDO.class));
        verifyNoInteractions(auditRecorder, notifyHelper);
        // 抛异常及停止副作用不是数据库回滚证明；此处没有事务代理。
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 2, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void casMustAffectExactlyOneRowWithoutProcessAuditOrNotification(int rows) {
        MarketResourceApplyDO apply = prepareSubmission();
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(rows);

        assertBusinessFailure(MARKET_APPLY_STATUS_DENY, () -> service.submitApply(APPLY_ID));

        assertCas(captureOnlyCas(APPLY_ID), apply, 1L);
        verifyNoInteractions(processMapper, auditRecorder, notifyHelper);
    }

    @ParameterizedTest
    @CsvSource({"CAS,false", "CAS,true", "PROCESS,false", "PROCESS,true"})
    void writeExceptionsPropagateUnchangedWithoutRetryOrSuccessEffects(String stage, boolean duplicate) {
        prepareSubmission();
        RuntimeException failure = duplicate ? new DuplicateKeyException("test constraint failure")
                : new IllegalStateException("test write unavailable");
        if ("CAS".equals(stage)) {
            when(applyMapper.update(isNull(), any(Wrapper.class))).thenThrow(failure);
        } else {
            when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
            when(processMapper.insert(any(MarketCheckProcessDO.class))).thenThrow(failure);
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.submitApply(APPLY_ID)));

        captureOnlyCas(APPLY_ID);
        if ("PROCESS".equals(stage)) {
            assertProcess(captureOnlyProcess(), APPLY_ID, USER_ID);
        } else {
            verifyNoInteractions(processMapper);
        }
        verifyNoInteractions(auditRecorder, notifyHelper);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "0,77,11,8,1,1",
            "1,77,11,8,NULL,2",
            "1,77,11,8,7,0",
            "1,77,11,8,2147483647,2",
            "9007199254740993,9007199254740993,9007199254740994,9007199254740996,NULL,1",
            "9223372036854775807,9223372036854775807,9223372036854775806,9223372036854775805,19,2"
    }, nullValues = "NULL")
    void successfulSubmissionBindsSnapshotAndCasThenRecordsProcessAuditAndNotification(
            long tenantId, long applyId, long userId, long assetId, Integer revision, int shareAttr) {
        TenantContextHolder.clear();
        MarketResourceApplyDO apply = loadDraft(applyId, userId, assetId, revision);
        DataAssetDO asset = loadPublishedAsset(assetId);
        asset.setShareAttr(shareAttr);
        stubSnapshotReads(assetId);
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(processMapper.insert(any(MarketCheckProcessDO.class))).thenReturn(1);
        LocalDateTime before = LocalDateTime.now();

        service.submitApply(applyId);

        LocalDateTime after = LocalDateTime.now();
        LambdaUpdateWrapper<MarketResourceApplyDO> cas = captureOnlyCas(applyId);
        assertCas(cas, apply, tenantId);
        assertFrozenSnapshot(apply, asset, false);
        assertNotNull(apply.getReceivedTime());
        assertFalse(apply.getReceivedTime().isBefore(before));
        assertFalse(apply.getReceivedTime().isAfter(after));
        int replyDays = shareAttr == DataAssetDO.SHARE_UNCONDITIONAL ? 1 : 10;
        assertEquals(apply.getReceivedTime().plusDays(replyDays), apply.getReplyDeadline());
        assertEquals(apply.getReceivedTime().plusDays(replyDays + 20), apply.getDeliveryDeadline());
        verify(workingDayService).plusWorkingDays(apply.getReceivedTime(), replyDays);
        verify(workingDayService).plusWorkingDays(apply.getReceivedTime(), replyDays + 20);
        verifyNoMoreInteractions(workingDayService);
        assertProcess(captureOnlyProcess(), applyId, userId);
        verifySuccessOrder(apply, asset);
        verifyNoInteractions(endpointMapper, supplyApiGuard, supplyAppApi);
        assertNull(TenantContextHolder.getTenantId());
        assertFalse(TenantContextHolder.isIgnore());
    }

    @Test
    void successfulApiSubmissionFreezesPublishedBindingBeforeCas() {
        MarketResourceApplyDO apply = loadDraft(APPLY_ID, USER_ID, ASSET_ID, 3);
        apply.setApiId(API_ID);
        apply.setAppId(APP_ID);
        DataAssetDO asset = loadPublishedAsset(ASSET_ID);
        SupplyApiBindingRespDTO binding = stubBoundApi();
        SupplyAppRespDTO app = new SupplyAppRespDTO();
        app.setId(APP_ID);
        when(supplyAppApi.listAppsByCreator(USER_ID)).thenReturn(CommonResult.success(List.of(app)));
        stubSnapshotReads(ASSET_ID);
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(processMapper.insert(any(MarketCheckProcessDO.class))).thenReturn(1);

        service.submitApply(APPLY_ID);

        assertCas(captureOnlyCas(APPLY_ID), apply, 1L);
        assertFrozenSnapshot(apply, asset, true);
        assertProcess(captureOnlyProcess(), APPLY_ID, USER_ID);
        var order = inOrder(assetMapper, endpointMapper, supplyApiGuard, supplyAppApi, catalogService, applyMapper);
        order.verify(catalogService).requireCompletePublishedAsset(asset);
        order.verify(endpointMapper).selectByAssetAndApi(ASSET_ID, API_ID);
        order.verify(supplyApiGuard).requireQueryableTableApi(API_ID);
        order.verify(supplyApiGuard).requireMatchesAsset(asset, binding);
        order.verify(supplyAppApi).listAppsByCreator(USER_ID);
        order.verify(catalogService).listColumns(ASSET_ID, SNAPSHOT_ID);
        order.verify(supplyApiGuard).requireQueryableTableApi(API_ID);
        order.verify(supplyApiGuard).requireMatchesAsset(asset, binding);
        order.verify(assetMapper).selectForMatching( asset.getId());
        order.verify(applyMapper).update(isNull(), any(Wrapper.class));
        order.verifyNoMoreInteractions();
        verifySuccessOrder(apply, asset);
    }

    @Test
    void missingApplicationStopsBeforeIdentityAndAssetReads() {
        when(applyMapper.selectById(APPLY_ID)).thenReturn(null);

        assertBusinessFailure(MARKET_APPLY_NOT_EXISTS, () -> service.submitApply(APPLY_ID));

        verifyReadOnlyFailure();
        verifyNoInteractions(objectGuard, assetMapper, endpointMapper, supplyApiGuard,
                supplyAppApi, catalogService, workingDayService);
    }

    @Test
    void missingIdentityPropagatesBeforeAssetReads() {
        MarketResourceApplyDO apply = new MarketResourceApplyDO();
        apply.setId(APPLY_ID);
        apply.setApplyUserId(USER_ID);
        when(applyMapper.selectById(APPLY_ID)).thenReturn(apply);
        var failure = new IllegalStateException("test missing identity");
        when(objectGuard.requireUserId()).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.submitApply(APPLY_ID)));

        verifyReadOnlyFailure();
        verifyNoInteractions(assetMapper, endpointMapper, supplyApiGuard, supplyAppApi,
                catalogService, workingDayService);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, 12L, 9007199254740993L, Long.MAX_VALUE})
    void nonOwnerIsRejectedBeforeReadingAsset(Long applicantId) {
        MarketResourceApplyDO apply = loadDraft(APPLY_ID, USER_ID, ASSET_ID, 1);
        apply.setApplyUserId(applicantId);

        assertBusinessFailure(MARKET_APPLY_FORBIDDEN, () -> service.submitApply(APPLY_ID));

        verifyReadOnlyFailure();
        verifyNoInteractions(assetMapper, endpointMapper, supplyApiGuard, supplyAppApi,
                catalogService, workingDayService);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 1, 2, 3, 4, 5, 6, 7, 8, Integer.MAX_VALUE})
    void nonDraftCannotBeSubmitted(int status) {
        MarketResourceApplyDO apply = loadDraft(APPLY_ID, USER_ID, ASSET_ID, 1);
        apply.setStatus(status);

        assertBusinessFailure(MARKET_APPLY_STATUS_DENY, () -> service.submitApply(APPLY_ID));

        verifyReadOnlyFailure();
        verifyNoInteractions(assetMapper, endpointMapper, supplyApiGuard, supplyAppApi,
                catalogService, workingDayService);
    }

    @Test
    void missingAssetPreventsSnapshotAndWrite() {
        loadDraft(APPLY_ID, USER_ID, ASSET_ID, 1);
        when(assetMapper.selectById(ASSET_ID)).thenReturn(null);

        assertBusinessFailure(DATA_ASSET_NOT_EXISTS, () -> service.submitApply(APPLY_ID));

        verifyReadOnlyFailure();
        verifyNoInteractions(endpointMapper, supplyApiGuard, supplyAppApi, catalogService, workingDayService);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1, 0, 2, Integer.MAX_VALUE})
    void unpublishedAssetPreventsSnapshotAndWrite(Integer status) {
        loadDraft(APPLY_ID, USER_ID, ASSET_ID, 1);
        loadPublishedAsset(ASSET_ID).setStatus(status);

        assertBusinessFailure(MARKET_APPLY_ASSET_NOT_PUBLISHED, () -> service.submitApply(APPLY_ID));

        verifyReadOnlyFailure();
        verifyNoInteractions(endpointMapper, supplyApiGuard, supplyAppApi, catalogService, workingDayService);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void apiAndAppMustBeSelectedTogether(boolean onlyApi) {
        MarketResourceApplyDO apply = loadDraft(APPLY_ID, USER_ID, ASSET_ID, 1);
        apply.setApiId(onlyApi ? API_ID : null);
        apply.setAppId(onlyApi ? null : APP_ID);
        loadPublishedAsset(ASSET_ID);

        assertBusinessFailure(MARKET_APPLY_API_APP_REQUIRED, () -> service.submitApply(APPLY_ID));

        verifyReadOnlyFailure();
        verifyPublicationReadOnly();
        verifyNoInteractions(endpointMapper, supplyApiGuard, supplyAppApi, workingDayService);
    }

    @Test
    void unboundApiStopsBeforeRemoteAndMetadataReads() {
        loadApiDraft();
        when(endpointMapper.selectByAssetAndApi(ASSET_ID, API_ID)).thenReturn(null);

        assertBusinessFailure(MARKET_APPLY_API_NOT_BOUND, () -> service.submitApply(APPLY_ID));

        verifyReadOnlyFailure();
        verifyPublicationReadOnly();
        verifyNoInteractions(supplyApiGuard, supplyAppApi, workingDayService);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void apiGuardFailurePropagatesBeforeSnapshot(boolean mismatchedAsset) {
        DataAssetDO asset = loadApiDraft();
        when(endpointMapper.selectByAssetAndApi(ASSET_ID, API_ID)).thenReturn(new MarketResourceEndpointDO());
        var failure = new ServiceException(mismatchedAsset ? MARKET_APPLY_API_ASSET_MISMATCH
                : MARKET_APPLY_API_TYPE_UNSUPPORTED);
        if (mismatchedAsset) {
            SupplyApiBindingRespDTO binding = new SupplyApiBindingRespDTO();
            when(supplyApiGuard.requireQueryableTableApi(API_ID)).thenReturn(binding);
            doThrow(failure).when(supplyApiGuard).requireMatchesAsset(asset, binding);
        } else {
            when(supplyApiGuard.requireQueryableTableApi(API_ID)).thenThrow(failure);
        }

        assertSame(failure, assertThrows(ServiceException.class, () -> service.submitApply(APPLY_ID)));

        verifyReadOnlyFailure();
        verifyPublicationReadOnly();
        verifyNoInteractions(supplyAppApi, workingDayService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NULL", "EMPTY", "OTHER_APP"})
    void applicationOwnershipFailureStopsBeforeSnapshot(String mode) {
        loadApiDraft();
        stubBoundApi();
        SupplyAppRespDTO other = new SupplyAppRespDTO();
        other.setId(APP_ID + 1);
        List<SupplyAppRespDTO> apps = switch (mode) {
            case "NULL" -> null;
            case "EMPTY" -> List.of();
            default -> List.of(other);
        };
        when(supplyAppApi.listAppsByCreator(USER_ID)).thenReturn(CommonResult.success(apps));

        assertBusinessFailure(MARKET_APPLY_APP_FORBIDDEN, () -> service.submitApply(APPLY_ID));

        verifyReadOnlyFailure();
        verifyPublicationReadOnly();
        verifyNoInteractions(workingDayService);
    }

    @Test
    void applicationOwnershipRpcFailurePropagatesWithoutWrite() {
        loadApiDraft();
        stubBoundApi();
        var failure = new IllegalStateException("test app lookup unavailable");
        when(supplyAppApi.listAppsByCreator(USER_ID)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.submitApply(APPLY_ID)));

        verifyReadOnlyFailure();
        verifyPublicationReadOnly();
        verifyNoInteractions(workingDayService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNAVAILABLE", "NULL", "EMPTY", "UNKNOWN_FIELD"})
    void metadataFailureCannotBecomeAnUnboundedSnapshotOrSuccessfulSubmission(String mode) {
        MarketResourceApplyDO apply = loadDraft(APPLY_ID, USER_ID, ASSET_ID, 1);
        loadPublishedAsset(ASSET_ID);
        stubWorkingDays();
        switch (mode) {
            case "UNAVAILABLE" -> when(catalogService.listColumns(ASSET_ID, SNAPSHOT_ID))
                    .thenThrow(new IllegalStateException("test catalog unavailable"));
            case "NULL" -> when(catalogService.listColumns(ASSET_ID, SNAPSHOT_ID)).thenReturn(null);
            case "EMPTY" -> when(catalogService.listColumns(ASSET_ID, SNAPSHOT_ID)).thenReturn(List.of());
            default -> {
                apply.setFieldScope("missing_column");
                when(catalogService.listColumns(ASSET_ID, SNAPSHOT_ID))
                        .thenReturn(List.of(column("Id", "标识")));
            }
        }

        assertBusinessFailure("UNAVAILABLE".equals(mode) ? MARKET_APPLY_METADATA_UNAVAILABLE
                : MARKET_APPLY_FIELD_SCOPE_INVALID, () -> service.submitApply(APPLY_ID));

        verifyReadOnlyFailure();
        verify(catalogService).requireCompletePublishedAsset(any(DataAssetDO.class));
        verify(catalogService).listColumns(ASSET_ID, SNAPSHOT_ID);
        verifyNoMoreInteractions(catalogService);
        assertEquals("old draft snapshot", apply.getSnapshot());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void invalidNonNullRevisionIsNotSilentlyNormalized(int revision) {
        MarketResourceApplyDO apply = prepareSubmission();
        apply.setRevision(revision);

        assertBusinessFailure(MARKET_APPLY_STATUS_DENY, () -> service.submitApply(APPLY_ID));

        verifyReadOnlyFailure();
    }

    private MarketResourceApplyDO prepareSubmission() {
        MarketResourceApplyDO apply = loadDraft(APPLY_ID, USER_ID, ASSET_ID, 1);
        loadPublishedAsset(ASSET_ID);
        stubSnapshotReads(ASSET_ID);
        return apply;
    }

    private MarketResourceApplyDO loadDraft(long id, long userId, long assetId, Integer revision) {
        MarketResourceApplyDO apply = new MarketResourceApplyDO();
        apply.setId(id);
        apply.setApplyUserId(userId);
        apply.setAssetId(assetId);
        apply.setStatus(MarketResourceApplyDO.STATUS_DRAFT);
        apply.setRevision(revision);
        apply.setFieldScope("Id,名称");
        apply.setDutyMatter(DUTY_MATTER);
        apply.setSnapshot("old draft snapshot");
        apply.setCatalogVersionId(1L);
        when(applyMapper.selectById(id)).thenReturn(apply);
        when(objectGuard.requireUserId()).thenReturn(userId);
        return apply;
    }

    private DataAssetDO loadPublishedAsset(long assetId) {
        DataAssetDO asset = new DataAssetDO();
        asset.setId(assetId);
        asset.setStatus(DataAssetDO.STATUS_PUBLISHED);
        asset.setAssetType(DataAssetDO.TYPE_DATABASE_TABLE);
        asset.setDatasourceId(DATABASE_ID);
        asset.setSchemaName("public");
        asset.setTableName("test_submission_resource");
        asset.setPublishedVersion("test-v3");
        asset.setPublishedCatalogVersionId(CATALOG_VERSION_ID);
        asset.setPublishedSnapshotId(SNAPSHOT_ID);
        asset.setShareAttr(DataAssetDO.SHARE_UNCONDITIONAL);
        when(assetMapper.selectById(assetId)).thenReturn(asset);
        publishedAssets.put(assetId, asset);
        return asset;
    }

    private DataAssetDO loadApiDraft() {
        MarketResourceApplyDO apply = loadDraft(APPLY_ID, USER_ID, ASSET_ID, 1);
        apply.setApiId(API_ID);
        apply.setAppId(APP_ID);
        return loadPublishedAsset(ASSET_ID);
    }

    private SupplyApiBindingRespDTO stubBoundApi() {
        when(endpointMapper.selectByAssetAndApi(ASSET_ID, API_ID)).thenReturn(new MarketResourceEndpointDO());
        SupplyApiBindingRespDTO binding = new SupplyApiBindingRespDTO();
        binding.setId(API_ID);
        binding.setDatabaseId(DATABASE_ID);
        binding.setSchemaName("public");
        binding.setTableName("test_submission_resource");
        binding.setFieldMappingVerified(true);
        binding.setOutputFieldMapping(Map.of("id", "Id", "name", "Name"));
        binding.setActiveReleaseId(RELEASE_ID);
        binding.setVersion("test-api-v2");
        binding.setContractHash("test-submission-contract-hash");
        when(supplyApiGuard.requireQueryableTableApi(API_ID)).thenReturn(binding);
        return binding;
    }

    private void stubSnapshotReads(long assetId) {
        stubWorkingDays();
        when(catalogService.listColumns(assetId, SNAPSHOT_ID)).thenReturn(List.of(
                column("Id", "标识"), column("Name", "名称")));
    }

    private void stubWorkingDays() {
        // 只固定工作日服务返回值，不在本单测中重新实现或验证日历算法。
        when(workingDayService.plusWorkingDays(any(LocalDateTime.class), anyInt()))
                .thenAnswer(call -> ((LocalDateTime) call.getArgument(0)).plusDays((int) call.getArgument(1)));
    }

    private static MetadataColumnRespDTO column(String name, String businessName) {
        MetadataColumnRespDTO column = new MetadataColumnRespDTO();
        column.setColumnName(name);
        column.setBusinessName(businessName);
        return column;
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<MarketResourceApplyDO> captureOnlyCas(long id) {
        ArgumentCaptor<LambdaUpdateWrapper<MarketResourceApplyDO>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(applyMapper).selectById(id);
        verify(applyMapper).update(isNull(), captor.capture());
        verifyNoMoreInteractions(applyMapper);
        return captor.getValue();
    }

    private MarketCheckProcessDO captureOnlyProcess() {
        ArgumentCaptor<MarketCheckProcessDO> captor = ArgumentCaptor.forClass(MarketCheckProcessDO.class);
        verify(processMapper).insert(captor.capture());
        verifyNoMoreInteractions(processMapper);
        return captor.getValue();
    }

    private void verifyReadOnlyFailure() {
        verify(applyMapper).selectById(APPLY_ID);
        verifyNoMoreInteractions(applyMapper);
        verifyNoInteractions(processMapper, auditRecorder, notifyHelper);
    }

    private void verifySuccessOrder(MarketResourceApplyDO apply, DataAssetDO asset) {
        var order = inOrder(applyMapper, assetMapper, catalogService, processMapper, auditRecorder, notifyHelper);
        order.verify(applyMapper).selectById(apply.getId());
        order.verify(catalogService).requireCompletePublishedAsset(asset);
        order.verify(catalogService).listColumns(asset.getId(), asset.getPublishedSnapshotId());
        order.verify(assetMapper).selectForMatching( asset.getId());
        order.verify(applyMapper).update(isNull(), any(Wrapper.class));
        order.verify(processMapper).insert(any(MarketCheckProcessDO.class));
        order.verify(auditRecorder).record("SUBMIT", "APPLY", apply.getId(),
                MarketResourceApplyDO.STATUS_DRAFT, MarketResourceApplyDO.STATUS_PENDING, null, "OK");
        order.verify(notifyHelper).notifyApplySubmit(same(apply), same(asset));
        order.verifyNoMoreInteractions();
        verifyNoMoreInteractions(applyMapper, catalogService, processMapper, auditRecorder, notifyHelper);
    }

    private static void assertProcess(MarketCheckProcessDO process, long applyId, long userId) {
        assertEquals(applyId, process.getResourceApplyId());
        assertEquals(MarketCheckProcessDO.ACTION_SUBMIT, process.getAction());
        assertEquals(MarketResourceApplyDO.STATUS_DRAFT, process.getFromStatus());
        assertEquals(MarketResourceApplyDO.STATUS_PENDING, process.getToStatus());
        assertEquals(userId, process.getOperatorUserId());
        assertEquals("提交申请", process.getNote());
    }

    private static void assertCas(LambdaUpdateWrapper<MarketResourceApplyDO> cas,
                                  MarketResourceApplyDO apply, long tenantId) {
        String predicate = cas.getSqlSegment();

        assertFalse(predicate.contains("tenant_id"));
        assertBound(cas, predicate, "id", apply.getId());
        assertBound(cas, predicate, "status", MarketResourceApplyDO.STATUS_DRAFT);
        assertBound(cas, predicate, "apply_user_id", apply.getApplyUserId());
        assertBound(cas, predicate, "asset_id", apply.getAssetId());
        if (apply.getRevision() == null) {
            assertTrue(Pattern.compile("(?i)\\brevision\\s+IS\\s+NULL\\b").matcher(predicate).find());
            assertFalse(Pattern.compile("(?i)\\brevision\\s*=").matcher(predicate).find(),
                    "旧空版本仅能匹配 NULL，不能默认匹配任意版本或版本 1");
        } else {
            assertBound(cas, predicate, "revision", apply.getRevision());
            assertFalse(Pattern.compile("(?i)\\brevision\\s+IS\\s+NULL\\b").matcher(predicate).find());
        }
        assertFalse(Pattern.compile("(?i)\\bOR\\b").matcher(predicate).find());
        String sqlSet = cas.getSqlSet();
        assertBound(cas, sqlSet, "status", MarketResourceApplyDO.STATUS_PENDING);
        assertBound(cas, sqlSet, "catalog_version_id", CATALOG_VERSION_ID);
        assertBound(cas, sqlSet, "received_time", apply.getReceivedTime());
        assertBound(cas, sqlSet, "reply_deadline", apply.getReplyDeadline());
        assertBound(cas, sqlSet, "delivery_deadline", apply.getDeliveryDeadline());
        assertBound(cas, sqlSet, "revision", apply.getRevision() == null ? 1 : apply.getRevision());
        var snapshot = Pattern.compile("(?i)\\bsnapshot\\s*=\\s*CAST\\(\\s*"
                + "#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}\\s+AS\\s+jsonb\\s*\\)").matcher(sqlSet);
        assertTrue(snapshot.find(), "提交快照必须是绑定参数并显式转为 jsonb: " + sqlSet);
        assertTrue(cas.getParamNameValuePairs().containsKey(snapshot.group(1)));
        assertEquals(apply.getSnapshot(), cas.getParamNameValuePairs().get(snapshot.group(1)));
        assertFalse(snapshot.find());
        assertFalse(sqlSet.contains(DUTY_MATTER), "业务文本不能拼接进 SQL");
        assertFalse(sqlSet.contains(apply.getSnapshot()), "完整 JSON 必须作为参数传递");
        List<String> columns = Pattern.compile("(?i)(?<![\\w.])([a-z_]+)\\s*=").matcher(sqlSet)
                .results().map(result -> result.group(1)).sorted().toList();
        assertEquals(Arrays.stream(new String[]{"status", "snapshot", "catalog_version_id", "received_time",
                "reply_deadline", "delivery_deadline", "revision"}).sorted().toList(), columns,
                "只改提交字段，不回写申请内容、身份、授权或交付状态");
    }

    private static void assertBound(LambdaUpdateWrapper<?> wrapper, String sql, String column, Object expected) {
        var binding = Pattern.compile("(?i)(?<![\\w.])" + Pattern.quote(column)
                + "\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}").matcher(sql);
        assertTrue(binding.find(), "缺少参数绑定 " + column + ": " + sql);
        assertTrue(wrapper.getParamNameValuePairs().containsKey(binding.group(1)));
        assertEquals(expected, wrapper.getParamNameValuePairs().get(binding.group(1)), column);
        assertFalse(binding.find(), column + " 不应重复设置或放宽条件");
    }

    private static void assertFrozenSnapshot(MarketResourceApplyDO apply, DataAssetDO asset, boolean api) {
        assertEquals(CATALOG_VERSION_ID, apply.getCatalogVersionId());
        Map<?, ?> snapshot = JsonUtils.parseObject(apply.getSnapshot(), Map.class);
        assertNotNull(snapshot);
        assertEquals(String.valueOf(asset.getId()), snapshot.get("assetId"));
        assertEquals("test-v3", snapshot.get("assetVersion"));
        assertEquals(String.valueOf(CATALOG_VERSION_ID), snapshot.get("catalogVersionId"));
        assertEquals(String.valueOf(SNAPSHOT_ID), snapshot.get("publishedSnapshotId"));
        assertEquals(String.valueOf(DATABASE_ID), snapshot.get("databaseId"));
        assertEquals("public", snapshot.get("schemaName"));
        assertEquals("test_submission_resource", snapshot.get("tableName"));
        assertEquals("Id,名称", snapshot.get("fieldScope"));
        assertEquals(List.of("Id", "Name"), snapshot.get("frozenColumns"));
        assertEquals(DUTY_MATTER, snapshot.get("dutyMatter"));
        assertEquals(apply.getRevision() == null ? "1" : String.valueOf(apply.getRevision()), snapshot.get("revision"));
        assertEquals(api ? String.valueOf(API_ID) : "", snapshot.get("apiId"));
        assertEquals(api ? String.valueOf(APP_ID) : "", snapshot.get("appId"));
        if (api) {
            assertEquals(String.valueOf(RELEASE_ID), snapshot.get("activeReleaseId"));
            assertEquals("test-submission-contract-hash", snapshot.get("approvedContractHash"));
            assertEquals("test-api-v2", snapshot.get("apiVersion"));
        }
        assertEquals("0", snapshot.get("applyUseType"));
        assertEquals("0", snapshot.get("applyUseTimes"));
        assertEquals(api ? 18 : 15, snapshot.size());
    }

    private static void assertBusinessFailure(ErrorCode code, org.junit.jupiter.api.function.Executable action) {
        ServiceException failure = assertThrows(ServiceException.class, action);
        assertEquals(code.getCode(), failure.getCode());
    }

}
