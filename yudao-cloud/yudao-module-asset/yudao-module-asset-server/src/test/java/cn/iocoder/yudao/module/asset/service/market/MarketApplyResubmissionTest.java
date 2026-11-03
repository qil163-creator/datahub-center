package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketCheckProcessDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketCheckProcessMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceEndpointMapper;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
import cn.iocoder.yudao.module.supply.api.app.SupplyAppApi;
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
import org.springframework.dao.CannotAcquireLockException;
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
 * 补正重提的隔离编排契约：旧版本 CAS、新版本冻结、失败停止副作用且不自动重试。
 * 复用 BaseMockitoUnitTest 和现有 Mapper/Guard，不加载 Spring 或数据库。
 * SQL 参数断言不证明 JSONB 可执行；异常断言不证明事务回滚或审计持久化，需另由真实 PG 验证。
 */
class MarketApplyResubmissionTest extends BaseMockitoUnitTest {

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
    private static final String DUTY_MATTER = "测试补正职责 ' ); SELECT 1; --\n\"字段\"";
    private static final LocalDateTime OLD_RECEIVED_TIME = LocalDateTime.of(2020, 1, 1, 0, 0);

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
    @Mock
    private MarketDeliveryService deliveryService;

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
    void preserveResubmissionBoundaryAndClearTenant() {
        try {
            // 本轮不增加 validateDelivery、重新查询应用归属、通知或直接交付语义。
            verifyNoInteractions(endpointMapper, supplyAppApi, notifyHelper, deliveryService);
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void limitedLegacyResubmissionWithoutLimitIsRejectedBeforePublicationReads() {
        var observed = loadSupplement(APPLY_ID, USER_ID, ASSET_ID, 3);
        observed.setApplyUseType(1);
        observed.setApplyUseTimes(null);
        assertBusinessFailure(MARKET_APPLY_QUOTA_UNSUPPORTED, () -> service.resubmitApply(APPLY_ID));
        verifyReadOnlyFailure();
        verifyNoInteractions(assetMapper, catalogService, workingDayService, supplyApiGuard);
    }

    @Test
    void renewalCannotRefreezeMissingApprovedQuotaEvidence() {
        var observed = loadSupplement(APPLY_ID, USER_ID, ASSET_ID, 3);
        observed.setRenewalOfApplyId(76L);
        observed.setApplyUseType(2);
        observed.setApplyUseTimes(100);
        assertBusinessFailure(MARKET_APPLY_QUOTA_UNSUPPORTED, () -> service.resubmitApply(APPLY_ID));
        verifyReadOnlyFailure();
        verifyNoInteractions(assetMapper, catalogService, workingDayService, supplyApiGuard);
    }

    @Test
    void limitedResubmissionFreezesExactQuotaWithoutChangingObservedApproval() {
        var observed = prepareResubmission();
        observed.setApplyUseType(2);
        observed.setApplyUseTimes(100);
        String original = JsonUtils.toJsonString(observed);
        stubSuccessfulWrites();
        service.resubmitApply(APPLY_ID);
        String frozen = assertCas(captureOnlyCas(APPLY_ID), observed, 1L, 4);
        Map<?, ?> evidence = JsonUtils.parseObject(frozen, Map.class);
        assertEquals("2", evidence.get("applyUseType"));
        assertEquals("100", evidence.get("applyUseTimes"));
        assertEquals(String.valueOf(CATALOG_VERSION_ID), evidence.get("catalogVersionId"));
        assertEquals(original, JsonUtils.toJsonString(observed));
        verify(assetMapper).selectForMatching( ASSET_ID);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publicationChangeDuringSnapshotReadRejectsBeforeCas(boolean offline) {
        var observed = prepareResubmission();
        String original = JsonUtils.toJsonString(observed);
        when(catalogService.listColumns(ASSET_ID, SNAPSHOT_ID)).thenAnswer(call -> {
            var changed = new DataAssetDO();
            changed.setId(ASSET_ID);
            changed.setStatus(offline ? DataAssetDO.STATUS_OFFLINE : DataAssetDO.STATUS_PUBLISHED);
            changed.setPublishedCatalogVersionId(offline ? CATALOG_VERSION_ID : CATALOG_VERSION_ID + 1);
            publishedAssets.put(ASSET_ID, changed);
            return List.of(column("Id", "标识"), column("Name", "名称"));
        });
        assertBusinessFailure(MARKET_APPLY_ASSET_NOT_PUBLISHED, () -> service.resubmitApply(APPLY_ID));
        verifyReadOnlyFailure();
        verify(assetMapper).selectForMatching( ASSET_ID);
        assertEquals(original, JsonUtils.toJsonString(observed));
    }

    @ParameterizedTest
    @CsvSource(value = {
            "0,77,11,8,1,2,1",
            "1,77,11,8,NULL,1,2",
            "1,77,11,8,7,8,0",
            "1,77,11,8,2147483646,2147483647,2",
            "9007199254740993,9007199254740993,9007199254740994,9007199254740996,NULL,1,1",
            "9223372036854775807,9223372036854775807,9223372036854775806,9223372036854775805,19,20,2"
    }, nullValues = "NULL")
    void successUsesOldVersionForCasAndNewVersionForSnapshotWithoutMutatingObservedApplication(
            long tenantId, long applyId, long userId, long assetId, Integer oldRevision,
            int newRevision, int shareAttr) {
        TenantContextHolder.clear();
        MarketResourceApplyDO observed = loadSupplement(applyId, userId, assetId, oldRevision);
        String original = JsonUtils.toJsonString(observed);
        DataAssetDO asset = loadPublishedAsset(assetId);
        asset.setShareAttr(shareAttr);
        stubSnapshotReads(assetId);
        stubSuccessfulWrites();
        LocalDateTime before = LocalDateTime.now();

        service.resubmitApply(applyId);

        LocalDateTime after = LocalDateTime.now();
        LambdaUpdateWrapper<MarketResourceApplyDO> cas = captureOnlyCas(applyId);
        String snapshot = assertCas(cas, observed, tenantId, newRevision);
        assertFrozenSnapshot(snapshot, observed, asset, newRevision, false);
        assertNewDeadlines(cas, before, after, shareAttr);
        assertEquals(original, JsonUtils.toJsonString(observed), "CAS 观察值不能被新快照或版本覆盖");
        assertProcess(captureOnlyProcess(), applyId, userId);
        verifySuccessOrder(observed, asset);
        verify(assetMapper).selectById(assetId);
        verifyNoMoreInteractions(assetMapper);
        verifyNoInteractions(supplyApiGuard);
        assertNull(TenantContextHolder.getTenantId());
        assertFalse(TenantContextHolder.isIgnore());
    }

    @Test
    void apiResubmissionFreezesCurrentPublishedBindingOnceWithoutAddingDeliveryValidation() {
        MarketResourceApplyDO observed = prepareResubmission();
        observed.setApiId(API_ID);
        observed.setAppId(APP_ID);
        String original = JsonUtils.toJsonString(observed);
        DataAssetDO asset = assetMapper.selectById(ASSET_ID);
        // 获取同一测试夹具引用，不计入被测调用的读取次数。
        clearInvocations(assetMapper);
        SupplyApiBindingRespDTO binding = stubBoundApi();
        stubSuccessfulWrites();

        service.resubmitApply(APPLY_ID);

        String snapshot = assertCas(captureOnlyCas(APPLY_ID), observed, 1L, 4);
        assertFrozenSnapshot(snapshot, observed, asset, 4, true);
        assertEquals(original, JsonUtils.toJsonString(observed));
        assertProcess(captureOnlyProcess(), APPLY_ID, USER_ID);
        var order = inOrder(assetMapper, catalogService, supplyApiGuard, applyMapper, processMapper, auditRecorder);
        order.verify(applyMapper).selectById(APPLY_ID);
        order.verify(catalogService).requireCompletePublishedAsset(asset);
        order.verify(catalogService).listColumns(ASSET_ID, SNAPSHOT_ID);
        order.verify(supplyApiGuard).requireQueryableTableApi(API_ID);
        order.verify(supplyApiGuard).requireMatchesAsset(asset, binding);
        order.verify(assetMapper).selectForMatching( asset.getId());
        order.verify(applyMapper).update(isNull(), any(Wrapper.class));
        order.verify(processMapper).insert(any(MarketCheckProcessDO.class));
        order.verify(auditRecorder).record("RESUBMIT", "APPLY", APPLY_ID,
                MarketResourceApplyDO.STATUS_SUPPLEMENT, MarketResourceApplyDO.STATUS_PENDING, null, "OK");
        order.verifyNoMoreInteractions();
        verifyNoMoreInteractions(applyMapper, catalogService, supplyApiGuard, processMapper, auditRecorder);
        verify(assetMapper).selectById(ASSET_ID);
        verifyNoMoreInteractions(assetMapper);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 2})
    void processMustInsertExactlyOneRowBeforeAuditWithoutRetry(int rows) {
        MarketResourceApplyDO observed = prepareResubmission();
        String original = JsonUtils.toJsonString(observed);
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(processMapper.insert(any(MarketCheckProcessDO.class))).thenReturn(rows);

        assertBusinessFailure(MARKET_APPLY_STATUS_DENY, () -> service.resubmitApply(APPLY_ID));

        assertCas(captureOnlyCas(APPLY_ID), observed, 1L, 4);
        assertProcess(captureOnlyProcess(), APPLY_ID, USER_ID);
        var order = inOrder(applyMapper, processMapper);
        order.verify(applyMapper).update(isNull(), any(Wrapper.class));
        order.verify(processMapper).insert(any(MarketCheckProcessDO.class));
        order.verifyNoMoreInteractions();
        verifyNoInteractions(auditRecorder, notifyHelper);
        assertEquals(original, JsonUtils.toJsonString(observed));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 2, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void casMustAffectExactlyOneRowWithoutProcessAuditNotificationOrRetry(int rows) {
        MarketResourceApplyDO observed = prepareResubmission();
        String original = JsonUtils.toJsonString(observed);
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(rows);

        assertBusinessFailure(MARKET_APPLY_STATUS_DENY, () -> service.resubmitApply(APPLY_ID));

        assertCas(captureOnlyCas(APPLY_ID), observed, 1L, 4);
        verifyNoInteractions(processMapper, auditRecorder, notifyHelper);
        assertEquals(original, JsonUtils.toJsonString(observed));
    }

    @ParameterizedTest
    @CsvSource({"CAS,DUPLICATE", "CAS,LOCK", "CAS,OTHER",
            "PROCESS,DUPLICATE", "PROCESS,LOCK", "PROCESS,OTHER"})
    void writeExceptionsPropagateUnchangedWithoutReReadRetryOrSuccessEffects(String stage, String type) {
        MarketResourceApplyDO observed = prepareResubmission();
        String original = JsonUtils.toJsonString(observed);
        RuntimeException failure = switch (type) {
            case "DUPLICATE" -> new DuplicateKeyException("test resubmission constraint failure");
            case "LOCK" -> new CannotAcquireLockException("test resubmission lock failure");
            default -> new IllegalStateException("test resubmission write unavailable");
        };
        if ("CAS".equals(stage)) {
            when(applyMapper.update(isNull(), any(Wrapper.class))).thenThrow(failure);
        } else {
            when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
            when(processMapper.insert(any(MarketCheckProcessDO.class))).thenThrow(failure);
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.resubmitApply(APPLY_ID)));

        assertCas(captureOnlyCas(APPLY_ID), observed, 1L, 4);
        if ("PROCESS".equals(stage)) {
            assertProcess(captureOnlyProcess(), APPLY_ID, USER_ID);
        } else {
            verifyNoInteractions(processMapper);
        }
        verifyNoInteractions(auditRecorder, notifyHelper);
        assertEquals(original, JsonUtils.toJsonString(observed));
    }

    @Test
    void missingApplicationStopsBeforeIdentityAndAssetReads() {
        when(applyMapper.selectById(APPLY_ID)).thenReturn(null);

        assertBusinessFailure(MARKET_APPLY_NOT_EXISTS, () -> service.resubmitApply(APPLY_ID));

        verifyReadOnlyFailure();
        verifyNoInteractions(objectGuard, assetMapper, supplyApiGuard, catalogService, workingDayService);
    }

    @Test
    void missingIdentityPropagatesBeforeAssetReads() {
        MarketResourceApplyDO observed = new MarketResourceApplyDO();
        observed.setId(APPLY_ID);
        observed.setApplyUserId(USER_ID);
        observed.setStatus(MarketResourceApplyDO.STATUS_SUPPLEMENT);
        when(applyMapper.selectById(APPLY_ID)).thenReturn(observed);
        var failure = new IllegalStateException("test missing identity");
        when(objectGuard.requireUserId()).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.resubmitApply(APPLY_ID)));

        verifyReadOnlyFailure();
        verifyNoInteractions(assetMapper, supplyApiGuard, catalogService, workingDayService);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, 12L, 9007199254740993L, Long.MAX_VALUE})
    void nonOwnerCannotResubmitEvenBeforeAssetLookup(Long ownerId) {
        MarketResourceApplyDO observed = loadSupplement(APPLY_ID, USER_ID, ASSET_ID, 3);
        observed.setApplyUserId(ownerId);

        assertBusinessFailure(MARKET_APPLY_FORBIDDEN, () -> service.resubmitApply(APPLY_ID));

        verifyReadOnlyFailure();
        verifyNoInteractions(assetMapper, supplyApiGuard, catalogService, workingDayService);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1, 0, 1, 2, 3, 4, 5, 6, 7, 9, Integer.MAX_VALUE})
    void onlySupplementStatusMayBeResubmitted(Integer status) {
        MarketResourceApplyDO observed = loadSupplement(APPLY_ID, USER_ID, ASSET_ID, 3);
        observed.setStatus(status);

        assertBusinessFailure(MARKET_APPLY_STATUS_DENY, () -> service.resubmitApply(APPLY_ID));

        verifyReadOnlyFailure();
        verifyNoInteractions(assetMapper, supplyApiGuard, catalogService, workingDayService);
    }

    @Test
    void missingAssetPreventsSnapshotAndWrite() {
        loadSupplement(APPLY_ID, USER_ID, ASSET_ID, 3);
        when(assetMapper.selectById(ASSET_ID)).thenReturn(null);

        assertBusinessFailure(DATA_ASSET_NOT_EXISTS, () -> service.resubmitApply(APPLY_ID));

        verifyReadOnlyFailure();
        verifyNoInteractions(supplyApiGuard, catalogService, workingDayService);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1, 0, 2, Integer.MAX_VALUE})
    void unpublishedAssetCannotBeResubmitted(Integer status) {
        loadSupplement(APPLY_ID, USER_ID, ASSET_ID, 3);
        loadPublishedAsset(ASSET_ID).setStatus(status);

        assertBusinessFailure(MARKET_APPLY_ASSET_NOT_PUBLISHED, () -> service.resubmitApply(APPLY_ID));

        verifyReadOnlyFailure();
        verifyNoInteractions(supplyApiGuard, catalogService, workingDayService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPLICATION", "ASSET"})
    void readFailurePropagatesWithoutRetryOrWrite(String stage) {
        var failure = new IllegalStateException("test resubmission read unavailable");
        if ("APPLICATION".equals(stage)) {
            when(applyMapper.selectById(APPLY_ID)).thenThrow(failure);
        } else {
            loadSupplement(APPLY_ID, USER_ID, ASSET_ID, 3);
            when(assetMapper.selectById(ASSET_ID)).thenThrow(failure);
        }

        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.resubmitApply(APPLY_ID)));

        verifyReadOnlyFailure();
        if ("APPLICATION".equals(stage)) {
            verifyNoInteractions(objectGuard, assetMapper);
        } else {
            verify(assetMapper).selectById(ASSET_ID);
            verifyNoMoreInteractions(assetMapper);
        }
        verifyNoInteractions(supplyApiGuard, catalogService, workingDayService);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void invalidOrExhaustedRevisionIsRejectedBeforeFreezingInsteadOfWrappingOrNormalizing(int revision) {
        MarketResourceApplyDO observed = loadSupplement(APPLY_ID, USER_ID, ASSET_ID, revision);
        String original = JsonUtils.toJsonString(observed);
        loadPublishedAsset(ASSET_ID);

        assertBusinessFailure(MARKET_APPLY_STATUS_DENY, () -> service.resubmitApply(APPLY_ID));

        verifyReadOnlyFailure();
        verifyPublicationReadOnly();
        verifyNoInteractions(supplyApiGuard, workingDayService);
        assertEquals(original, JsonUtils.toJsonString(observed));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNAVAILABLE", "NULL", "EMPTY", "UNKNOWN_FIELD"})
    void metadataFailureCannotReuseOldSnapshotOrBecomeAnUnboundedApproval(String mode) {
        MarketResourceApplyDO observed = loadSupplement(APPLY_ID, USER_ID, ASSET_ID, 3);
        loadPublishedAsset(ASSET_ID);
        stubWorkingDays();
        switch (mode) {
            case "UNAVAILABLE" -> when(catalogService.listColumns(ASSET_ID, SNAPSHOT_ID))
                    .thenThrow(new IllegalStateException("test catalog unavailable"));
            case "NULL" -> when(catalogService.listColumns(ASSET_ID, SNAPSHOT_ID)).thenReturn(null);
            case "EMPTY" -> when(catalogService.listColumns(ASSET_ID, SNAPSHOT_ID)).thenReturn(List.of());
            default -> {
                observed.setFieldScope("missing_column");
                when(catalogService.listColumns(ASSET_ID, SNAPSHOT_ID))
                        .thenReturn(List.of(column("Id", "标识")));
            }
        }
        String original = JsonUtils.toJsonString(observed);

        assertBusinessFailure("UNAVAILABLE".equals(mode) ? MARKET_APPLY_METADATA_UNAVAILABLE
                : MARKET_APPLY_FIELD_SCOPE_INVALID, () -> service.resubmitApply(APPLY_ID));

        verifyReadOnlyFailure();
        verify(catalogService).requireCompletePublishedAsset(any(DataAssetDO.class));
        verify(catalogService).listColumns(ASSET_ID, SNAPSHOT_ID);
        verifyNoMoreInteractions(catalogService);
        verifyNoInteractions(supplyApiGuard);
        assertEquals(original, JsonUtils.toJsonString(observed));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TYPE", "ASSET_MISMATCH", "UNAVAILABLE"})
    void apiGuardFailuresPropagateBeforeCasWithoutRetry(String mode) {
        MarketResourceApplyDO observed = loadSupplement(APPLY_ID, USER_ID, ASSET_ID, 3);
        observed.setApiId(API_ID);
        observed.setAppId(APP_ID);
        String original = JsonUtils.toJsonString(observed);
        DataAssetDO asset = loadPublishedAsset(ASSET_ID);
        stubSnapshotReads(ASSET_ID);
        RuntimeException failure;
        if ("ASSET_MISMATCH".equals(mode)) {
            SupplyApiBindingRespDTO binding = stubBoundApi();
            failure = new ServiceException(MARKET_APPLY_API_ASSET_MISMATCH);
            doThrow(failure).when(supplyApiGuard).requireMatchesAsset(asset, binding);
        } else {
            failure = "TYPE".equals(mode) ? new ServiceException(MARKET_APPLY_API_TYPE_UNSUPPORTED)
                    : new IllegalStateException("test binding lookup unavailable");
            when(supplyApiGuard.requireQueryableTableApi(API_ID)).thenThrow(failure);
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.resubmitApply(APPLY_ID)));

        verifyReadOnlyFailure();
        verify(supplyApiGuard).requireQueryableTableApi(API_ID);
        if ("ASSET_MISMATCH".equals(mode)) {
            verify(supplyApiGuard).requireMatchesAsset(same(asset), any(SupplyApiBindingRespDTO.class));
        }
        verifyNoMoreInteractions(supplyApiGuard);
        assertEquals(original, JsonUtils.toJsonString(observed));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNVERIFIED", "NO_RELEASE", "NO_HASH", "BLANK_HASH", "NO_SCHEMA",
            "NO_TABLE", "NO_DATABASE", "NO_MAPPING", "EMPTY_MAPPING", "MISSING_COLUMN"})
    void incompleteApiPublicationEvidenceCannotBeFrozen(String mode) {
        MarketResourceApplyDO observed = loadSupplement(APPLY_ID, USER_ID, ASSET_ID, 3);
        observed.setApiId(API_ID);
        observed.setAppId(APP_ID);
        String original = JsonUtils.toJsonString(observed);
        DataAssetDO asset = loadPublishedAsset(ASSET_ID);
        stubSnapshotReads(ASSET_ID);
        SupplyApiBindingRespDTO binding = stubBoundApi();
        switch (mode) {
            case "UNVERIFIED" -> binding.setFieldMappingVerified(false);
            case "NO_RELEASE" -> binding.setActiveReleaseId(null);
            case "NO_HASH" -> binding.setContractHash(null);
            case "BLANK_HASH" -> binding.setContractHash(" \t");
            case "NO_SCHEMA" -> binding.setSchemaName(null);
            case "NO_TABLE" -> binding.setTableName("");
            case "NO_DATABASE" -> binding.setDatabaseId(null);
            case "NO_MAPPING" -> binding.setOutputFieldMapping(null);
            case "EMPTY_MAPPING" -> binding.setOutputFieldMapping(Map.of());
            default -> binding.setOutputFieldMapping(Map.of("id", "Id"));
        }

        assertBusinessFailure(MARKET_APPLY_API_TYPE_UNSUPPORTED, () -> service.resubmitApply(APPLY_ID));

        verifyReadOnlyFailure();
        verify(supplyApiGuard).requireQueryableTableApi(API_ID);
        verify(supplyApiGuard).requireMatchesAsset(asset, binding);
        verifyNoMoreInteractions(supplyApiGuard);
        assertEquals(original, JsonUtils.toJsonString(observed));
    }

    private MarketResourceApplyDO prepareResubmission() {
        MarketResourceApplyDO observed = loadSupplement(APPLY_ID, USER_ID, ASSET_ID, 3);
        loadPublishedAsset(ASSET_ID);
        stubSnapshotReads(ASSET_ID);
        return observed;
    }

    private MarketResourceApplyDO loadSupplement(long id, long userId, long assetId, Integer revision) {
        MarketResourceApplyDO apply = new MarketResourceApplyDO();
        apply.setId(id);
        apply.setApplyUserId(userId);
        apply.setAssetId(assetId);
        apply.setStatus(MarketResourceApplyDO.STATUS_SUPPLEMENT);
        apply.setRevision(revision);
        apply.setFieldScope("Id,名称");
        apply.setDutyMatter(DUTY_MATTER);
        apply.setSnapshot("{\"previous\":\"snapshot\",\"frozenColumns\":[\"LegacyColumn\"]}");
        apply.setCatalogVersionId(1L);
        apply.setReceivedTime(OLD_RECEIVED_TIME);
        apply.setReplyDeadline(OLD_RECEIVED_TIME.plusDays(1));
        apply.setDeliveryDeadline(OLD_RECEIVED_TIME.plusDays(21));
        apply.setSupplementNote("请补充职责依据");
        apply.setIfAuth(MarketResourceApplyDO.AUTH_NO);
        apply.setSourceRevision(9L);
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
        asset.setTableName("test_resubmission_resource");
        asset.setPublishedVersion("test-v3");
        asset.setPublishedCatalogVersionId(CATALOG_VERSION_ID);
        asset.setPublishedSnapshotId(SNAPSHOT_ID);
        asset.setShareAttr(DataAssetDO.SHARE_UNCONDITIONAL);
        when(assetMapper.selectById(assetId)).thenReturn(asset);
        publishedAssets.put(assetId, asset);
        return asset;
    }

    private SupplyApiBindingRespDTO stubBoundApi() {
        SupplyApiBindingRespDTO binding = new SupplyApiBindingRespDTO();
        binding.setId(API_ID);
        binding.setDatabaseId(DATABASE_ID);
        binding.setSchemaName("public");
        binding.setTableName("test_resubmission_resource");
        binding.setFieldMappingVerified(true);
        binding.setOutputFieldMapping(Map.of("id", "Id", "name", "Name"));
        binding.setActiveReleaseId(RELEASE_ID);
        binding.setVersion("test-api-v2");
        binding.setContractHash("test-resubmission-contract-hash");
        when(supplyApiGuard.requireQueryableTableApi(API_ID)).thenReturn(binding);
        return binding;
    }

    private void stubSnapshotReads(long assetId) {
        stubWorkingDays();
        when(catalogService.listColumns(assetId, SNAPSHOT_ID)).thenReturn(List.of(
                column("Id", "标识"), column("Name", "名称")));
    }

    private void stubWorkingDays() {
        // 只控制协作者返回值，不在隔离单测中重写或验证工作日日历算法。
        when(workingDayService.plusWorkingDays(any(LocalDateTime.class), anyInt()))
                .thenAnswer(call -> ((LocalDateTime) call.getArgument(0)).plusDays((int) call.getArgument(1)));
    }

    private void stubSuccessfulWrites() {
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(processMapper.insert(any(MarketCheckProcessDO.class))).thenReturn(1);
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

    private void verifySuccessOrder(MarketResourceApplyDO observed, DataAssetDO asset) {
        var order = inOrder(applyMapper, assetMapper, catalogService, processMapper, auditRecorder);
        order.verify(applyMapper).selectById(observed.getId());
        order.verify(catalogService).requireCompletePublishedAsset(asset);
        order.verify(catalogService).listColumns(asset.getId(), asset.getPublishedSnapshotId());
        order.verify(assetMapper).selectForMatching( asset.getId());
        order.verify(applyMapper).update(isNull(), any(Wrapper.class));
        order.verify(processMapper).insert(any(MarketCheckProcessDO.class));
        order.verify(auditRecorder).record("RESUBMIT", "APPLY", observed.getId(),
                MarketResourceApplyDO.STATUS_SUPPLEMENT, MarketResourceApplyDO.STATUS_PENDING, null, "OK");
        order.verifyNoMoreInteractions();
        verifyNoMoreInteractions(applyMapper, catalogService, processMapper, auditRecorder);
        verifyNoInteractions(notifyHelper);
    }

    private static void assertProcess(MarketCheckProcessDO process, long applyId, long userId) {
        assertEquals(applyId, process.getResourceApplyId());
        assertEquals(MarketCheckProcessDO.ACTION_RESUBMIT, process.getAction());
        assertEquals(MarketResourceApplyDO.STATUS_SUPPLEMENT, process.getFromStatus());
        assertEquals(MarketResourceApplyDO.STATUS_PENDING, process.getToStatus());
        assertEquals(userId, process.getOperatorUserId());
        assertEquals("补正后重新提交", process.getNote());
    }

    private static String assertCas(LambdaUpdateWrapper<MarketResourceApplyDO> cas,
                                    MarketResourceApplyDO observed, long tenantId, int newRevision) {
        String predicate = cas.getSqlSegment();

        assertFalse(predicate.contains("tenant_id"));
        assertBound(cas, predicate, "id", observed.getId());
        assertBound(cas, predicate, "status", MarketResourceApplyDO.STATUS_SUPPLEMENT);
        assertBound(cas, predicate, "apply_user_id", observed.getApplyUserId());
        assertBound(cas, predicate, "asset_id", observed.getAssetId());
        if (observed.getRevision() == null) {
            assertTrue(Pattern.compile("(?i)\\brevision\\s+IS\\s+NULL\\b").matcher(predicate).find());
            assertFalse(Pattern.compile("(?i)\\brevision\\s*=").matcher(predicate).find(),
                    "旧空版本只匹配 NULL，不是任意版本，也不是新版本 1");
        } else {
            assertBound(cas, predicate, "revision", observed.getRevision());
            assertFalse(Pattern.compile("(?i)\\brevision\\s+IS\\s+NULL\\b").matcher(predicate).find());
        }
        assertFalse(Pattern.compile("(?i)\\bOR\\b").matcher(predicate).find());
        String sqlSet = cas.getSqlSet();
        assertBound(cas, sqlSet, "status", MarketResourceApplyDO.STATUS_PENDING);
        assertBound(cas, sqlSet, "catalog_version_id", CATALOG_VERSION_ID);
        assertBound(cas, sqlSet, "revision", newRevision);
        assertInstanceOf(LocalDateTime.class, boundValue(cas, sqlSet, "received_time"));
        assertInstanceOf(LocalDateTime.class, boundValue(cas, sqlSet, "reply_deadline"));
        assertInstanceOf(LocalDateTime.class, boundValue(cas, sqlSet, "delivery_deadline"));
        var binding = Pattern.compile("(?i)\\bsnapshot\\s*=\\s*CAST\\(\\s*"
                + "#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}\\s+AS\\s+jsonb\\s*\\)").matcher(sqlSet);
        assertTrue(binding.find(), "快照必须绑定参数并显式 CAST 为 jsonb: " + sqlSet);
        assertTrue(cas.getParamNameValuePairs().containsKey(binding.group(1)));
        String snapshot = assertInstanceOf(String.class, cas.getParamNameValuePairs().get(binding.group(1)));
        assertFalse(binding.find(), "快照只能设置一次");
        assertNotEquals(observed.getSnapshot(), snapshot, "不能复用上一轮快照");
        assertFalse(sqlSet.contains(DUTY_MATTER), "业务文本不能拼接进 SQL");
        assertFalse(sqlSet.contains(snapshot), "完整 JSON 必须作为参数传递");
        List<String> columns = Pattern.compile("(?i)(?<![\\w.])([a-z_]+)\\s*=").matcher(sqlSet)
                .results().map(result -> result.group(1)).sorted().toList();
        assertEquals(Arrays.stream(new String[]{"status", "snapshot", "catalog_version_id", "received_time",
                "reply_deadline", "delivery_deadline", "revision"}).sorted().toList(), columns,
                "只设置重提字段，不能重写申请内容、身份、授权来源或交付状态");
        Map<?, ?> frozen = JsonUtils.parseObject(snapshot, Map.class);
        assertNotNull(frozen);
        assertEquals(String.valueOf(newRevision), frozen.get("revision"), "新快照版本必须与 SET 版本一致");
        return snapshot;
    }

    private void assertNewDeadlines(LambdaUpdateWrapper<MarketResourceApplyDO> cas,
                                    LocalDateTime before, LocalDateTime after, int shareAttr) {
        String sqlSet = cas.getSqlSet();
        LocalDateTime received = assertInstanceOf(LocalDateTime.class, boundValue(cas, sqlSet, "received_time"));
        assertFalse(received.isBefore(before));
        assertFalse(received.isAfter(after));
        int replyDays = shareAttr == DataAssetDO.SHARE_UNCONDITIONAL ? 1 : 10;
        assertBound(cas, sqlSet, "reply_deadline", received.plusDays(replyDays));
        assertBound(cas, sqlSet, "delivery_deadline", received.plusDays(replyDays + 20));
        verify(workingDayService).plusWorkingDays(received, replyDays);
        verify(workingDayService).plusWorkingDays(received, replyDays + 20);
        verifyNoMoreInteractions(workingDayService);
    }

    private static Object boundValue(LambdaUpdateWrapper<?> wrapper, String sql, String column) {
        var binding = Pattern.compile("(?i)(?<![\\w.])" + Pattern.quote(column)
                + "\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}").matcher(sql);
        assertTrue(binding.find(), "缺少参数绑定 " + column + ": " + sql);
        assertTrue(wrapper.getParamNameValuePairs().containsKey(binding.group(1)));
        Object value = wrapper.getParamNameValuePairs().get(binding.group(1));
        assertFalse(binding.find(), column + " 不应重复设置或放宽条件");
        return value;
    }

    private static void assertBound(LambdaUpdateWrapper<?> wrapper, String sql, String column, Object expected) {
        assertEquals(expected, boundValue(wrapper, sql, column), column);
    }

    private static void assertFrozenSnapshot(String json, MarketResourceApplyDO observed,
                                             DataAssetDO asset, int revision, boolean api) {
        Map<?, ?> snapshot = JsonUtils.parseObject(json, Map.class);
        assertNotNull(snapshot);
        assertEquals(String.valueOf(asset.getId()), snapshot.get("assetId"));
        assertEquals("test-v3", snapshot.get("assetVersion"));
        assertEquals(String.valueOf(CATALOG_VERSION_ID), snapshot.get("catalogVersionId"));
        assertEquals(String.valueOf(SNAPSHOT_ID), snapshot.get("publishedSnapshotId"));
        assertEquals(String.valueOf(DATABASE_ID), snapshot.get("databaseId"));
        assertEquals("public", snapshot.get("schemaName"));
        assertEquals("test_resubmission_resource", snapshot.get("tableName"));
        assertEquals(observed.getFieldScope(), snapshot.get("fieldScope"));
        assertEquals(List.of("Id", "Name"), snapshot.get("frozenColumns"));
        assertEquals(DUTY_MATTER, snapshot.get("dutyMatter"));
        assertEquals(String.valueOf(revision), snapshot.get("revision"));
        assertEquals(api ? String.valueOf(API_ID) : "", snapshot.get("apiId"));
        assertEquals(api ? String.valueOf(APP_ID) : "", snapshot.get("appId"));
        if (api) {
            assertEquals(String.valueOf(RELEASE_ID), snapshot.get("activeReleaseId"));
            assertEquals("test-resubmission-contract-hash", snapshot.get("approvedContractHash"));
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
