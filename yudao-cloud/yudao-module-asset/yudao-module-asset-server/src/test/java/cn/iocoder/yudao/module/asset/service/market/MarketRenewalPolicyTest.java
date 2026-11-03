package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketApplySaveReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCheckReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketRenewalReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketCheckProcessDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceEndpointDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketApprovalEvidenceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketCheckProcessMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryOperationMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceEndpointMapper;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import cn.iocoder.yudao.module.asset.service.monitor.AssetMonitorService;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 续期独立政策的领域回归：继承范围不可通过补正扩大，父状态不级联子申请或来源。
 * Mapper/日历/远端 Guard 是替身；这些用例不证明真实事务、Supply 聚合或跨服务投递。
 */
class MarketRenewalPolicyTest extends BaseMockitoUnitTest {

    private void verifyPublicationReadOnly() {
        verify(catalogService).requireCompletePublishedAsset(any(DataAssetDO.class));
        verifyNoMoreInteractions(catalogService);
    }

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 30, 10, 0);
    private static final long PARENT = 7L;
    private static final long CHILD = 99L;
    private static final long USER = 9L;
    private static final long ASSET = 5L;

    @InjectMocks private MarketApplyServiceImpl service;
    @Mock private MarketResourceApplyMapper applyMapper;
    @Mock private MarketCheckProcessMapper processMapper;
    @Mock private DataAssetMapper assetMapper;
    @Mock private MarketResourceEndpointMapper endpointMapper;
    @Mock private MarketObjectGuard objectGuard;
    @Mock private MarketDisplayHelper displayHelper;
    @Mock private MarketDeliveryService deliveryService;
    @Mock private MarketAuditRecorder auditRecorder;
    @Mock private MarketNotifyHelper notifyHelper;
    @Mock private MarketWorkingDayService workingDayService;
    @Mock private MarketSupplyApiGuard supplyApiGuard;
    @Mock private SupplyAppApi supplyAppApi;
    @Mock private AssetCatalogService catalogService;
    @Mock private AssetMonitorService assetMonitorService;
    @Mock private PlatformTransactionManager transactionManager;

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
    @ValueSource(strings = {"assetId", "apiId", "appId", "purpose", "dutyMatter", "legalBasis",
            "fieldScope", "applyUseType", "applyUseTimes"})
    void supplementCannotChangeInheritedPurposeObligationsScopeOrBinding(String field) {
        MarketResourceApplyDO child = supplement();
        load(child);
        MarketApplySaveReqVO request = BeanUtils.toBean(child, MarketApplySaveReqVO.class);
        switch (field) {
            case "assetId" -> request.setAssetId(6L);
            case "apiId" -> request.setApiId(2L);
            case "appId" -> request.setAppId(3L);
            case "purpose" -> request.setPurpose("新用途");
            case "dutyMatter" -> request.setDutyMatter("");
            case "legalBasis" -> request.setLegalBasis("新依据");
            case "fieldScope" -> request.setFieldScope("Id,Secret");
            case "applyUseType" -> request.setApplyUseType(MarketResourceApplyDO.USE_LIMITED);
            default -> request.setApplyUseTimes(100);
        }

        ServiceException failure = assertThrows(ServiceException.class, () -> service.updateApply(request));

        assertEquals(MARKET_APPLY_RENEWAL_CONFLICT.getCode(), failure.getCode());
        verifyNoInteractions(assetMapper, endpointMapper, supplyApiGuard, supplyAppApi,
                catalogService, processMapper, auditRecorder, deliveryService);
        verify(applyMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    void omittedInheritedValuesArePreservedWhileSupplementNoteCanBeEdited() {
        MarketResourceApplyDO child = supplement();
        load(child);
        DataAssetDO asset = publishedAsset();
        loadPublishedAsset(asset);
        when(applyMapper.update(any(MarketResourceApplyDO.class), any(Wrapper.class))).thenReturn(1);
        MarketApplySaveReqVO request = new MarketApplySaveReqVO();
        request.setId(CHILD);
        request.setAssetId(ASSET);
        request.setTitle("补充材料");
        request.setApplyNote("补充附件说明");

        service.updateApply(request);

        var updated = ArgumentCaptor.forClass(MarketResourceApplyDO.class);
        verify(applyMapper).update(updated.capture(), any(Wrapper.class));
        MarketResourceApplyDO actual = updated.getValue();
        assertEquals(child.getPurpose(), actual.getPurpose());
        assertEquals(child.getDutyMatter(), actual.getDutyMatter());
        assertEquals(child.getLegalBasis(), actual.getLegalBasis());
        assertEquals(child.getFieldScope(), actual.getFieldScope());
        assertEquals(child.getAssetId(), actual.getAssetId());
        assertEquals(child.getApplyUseType(), actual.getApplyUseType());
        assertEquals(child.getApplyUseTimes(), actual.getApplyUseTimes());
        assertEquals("补充附件说明", actual.getApplyNote());
        assertEquals(3, actual.getRevision());
        assertNull(actual.getSnapshot(), "编辑不能覆写冻结证据");
        assertEquals(2, child.getRevision(), "读取版本不应被修改");
    }

    @Test
    void apiBindingCannotBeRemovedByOmittingNullableApiAndAppFields() {
        MarketResourceApplyDO child = apiSupplement();
        load(child);
        DataAssetDO asset = publishedAsset();
        loadPublishedAsset(asset);
        stubDeliveryBinding();
        when(applyMapper.update(any(MarketResourceApplyDO.class), any(Wrapper.class))).thenReturn(1);
        MarketApplySaveReqVO request = new MarketApplySaveReqVO();
        request.setId(CHILD);
        request.setAssetId(ASSET);

        service.updateApply(request);

        var updated = ArgumentCaptor.forClass(MarketResourceApplyDO.class);
        verify(applyMapper).update(updated.capture(), any(Wrapper.class));
        assertEquals(2L, updated.getValue().getApiId());
        assertEquals(3L, updated.getValue().getAppId());
        verify(endpointMapper).selectByAssetAndApi(ASSET, 2L);
        verify(supplyAppApi).listAppsByCreator(USER);
    }

    @Test
    void resubmissionKeepsInheritedColumnsAndPublicationProofWhenCurrentMetadataAddsColumns() {
        MarketResourceApplyDO child = supplement();
        load(child);
        loadPublishedAsset(publishedAsset());
        when(catalogService.listColumns(ASSET, 11L)).thenReturn(List.of(column("Id"), column("Secret")));
        stubWorkingDays();
        successfulWrites();
        String before = JsonUtils.toJsonString(child);

        service.resubmitApply(CHILD, 2);

        LambdaUpdateWrapper<MarketResourceApplyDO> cas = captureCas();
        Map<String, Object> expected = snapshot(child);
        expected.put("revision", "3");
        assertEquals(expected, snapshotFromCas(cas));
        assertEquals(List.of("Id"), snapshotFromCas(cas).get("frozenColumns"));
        assertEquals(12L, bound(cas, cas.getSqlSet(), "catalog_version_id"));
        assertEquals(2, bound(cas, cas.getSqlSegment(), "revision"));
        assertEquals(3, bound(cas, cas.getSqlSet(), "revision"));
        assertEquals(before, JsonUtils.toJsonString(child));
        assertNewDeadlines(cas, 1);
        verify(applyMapper, never()).selectById(PARENT);
        verifyNoInteractions(supplyApiGuard, deliveryService);
    }

    @Test
    void resubmissionRejectsMissingInheritedPhysicalColumnRatherThanRefreezingASmallerRange() {
        MarketResourceApplyDO child = supplement();
        load(child);
        loadPublishedAsset(publishedAsset());
        when(catalogService.listColumns(ASSET, 11L)).thenReturn(List.of(column("Secret")));

        ServiceException failure = assertThrows(ServiceException.class, () -> service.resubmitApply(CHILD));

        assertEquals(MARKET_APPLY_SCOPE_UNCONFIRMED.getCode(), failure.getCode());
        verify(applyMapper, never()).update(any(), any(Wrapper.class));
        verifyNoInteractions(processMapper, auditRecorder, deliveryService, workingDayService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"assetVersion", "catalogVersion", "publishedSnapshot", "database", "schema", "table"})
    void resubmissionRejectsChangedAssetBindingOrPublicationWithoutReplacingOldEvidence(String field) {
        MarketResourceApplyDO child = supplement();
        load(child);
        DataAssetDO asset = publishedAsset();
        switch (field) {
            case "assetVersion" -> asset.setPublishedVersion("v2");
            case "catalogVersion" -> asset.setPublishedCatalogVersionId(13L);
            case "publishedSnapshot" -> asset.setPublishedSnapshotId(14L);
            case "database" -> asset.setDatasourceId(40L);
            case "schema" -> asset.setSchemaName("audit");
            default -> asset.setTableName("Citizen");
        }
        loadPublishedAsset(asset);
        String original = child.getSnapshot();

        ServiceException failure = assertThrows(ServiceException.class, () -> service.resubmitApply(CHILD));

        assertEquals(MARKET_APPLY_SCOPE_UNCONFIRMED.getCode(), failure.getCode());
        assertEquals(original, child.getSnapshot());
        verify(applyMapper, never()).update(any(), any(Wrapper.class));
        verifyPublicationReadOnly();
        verifyNoInteractions(processMapper, auditRecorder, workingDayService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"database", "schema", "table", "contract", "version", "release"})
    void apiResubmissionRejectsChangedFrozenApiIdentity(String field) {
        MarketResourceApplyDO child = apiSupplement();
        load(child);
        loadPublishedAsset(publishedAsset());
        when(catalogService.listColumns(ASSET, 11L)).thenReturn(List.of(column("Id")));
        SupplyApiBindingRespDTO binding = stubDeliveryBinding();
        switch (field) {
            case "database" -> binding.setDatabaseId(40L);
            case "schema" -> binding.setSchemaName("audit");
            case "table" -> binding.setTableName("Citizen");
            case "contract" -> binding.setContractHash("test-new-contract");
            case "version" -> binding.setVersion("v2");
            default -> binding.setActiveReleaseId(22L);
        }

        ServiceException failure = assertThrows(ServiceException.class, () -> service.resubmitApply(CHILD));

        assertEquals(MARKET_APPLY_SCOPE_UNCONFIRMED.getCode(), failure.getCode());
        verify(applyMapper, never()).update(any(), any(Wrapper.class));
        verifyNoInteractions(processMapper, auditRecorder, deliveryService, workingDayService);
    }

    @Test
    void apiResubmissionKeepsOldScopeAndContractWhenAdditionalOutputColumnsAreAvailable() {
        MarketResourceApplyDO child = apiSupplement();
        load(child);
        loadPublishedAsset(publishedAsset());
        when(catalogService.listColumns(ASSET, 11L)).thenReturn(List.of(column("Id"), column("Secret")));
        SupplyApiBindingRespDTO binding = stubDeliveryBinding();
        binding.setOutputFieldMapping(Map.of("id", "Id", "secret", "Secret"));
        stubWorkingDays();
        successfulWrites();

        service.resubmitApply(CHILD, 2);

        var expected = snapshot(child);
        expected.put("revision", "3");
        assertEquals(expected, snapshotFromCas(captureCas()));
        verify(supplyAppApi).listAppsByCreator(USER);
        verifyNoInteractions(deliveryService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"apiId", "appId", "fieldScope", "dutyMatter", "catalogVersionId", "frozenColumns"})
    void incompleteOrConflictingHistoricalRenewalEvidenceCannotBeRefrozenToAppearValid(String key) {
        MarketResourceApplyDO child = supplement();
        Map<String, Object> evidence = snapshot(child);
        evidence.remove(key);
        child.setSnapshot(JsonUtils.toJsonString(evidence));
        load(child);
        loadPublishedAsset(publishedAsset());

        ServiceException failure = assertThrows(ServiceException.class, () -> service.resubmitApply(CHILD));

        assertEquals(MARKET_APPLY_SCOPE_UNCONFIRMED.getCode(), failure.getCode());
        verify(applyMapper, never()).update(any(), any(Wrapper.class));
        verifyPublicationReadOnly();
        verifyNoInteractions(workingDayService, supplyApiGuard, processMapper, auditRecorder);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void newRenewalRecalculatesReceivingAndSlaWithoutInheritingParentDeadlines(int shareAttr) {
        MarketResourceApplyDO parent = supplement();
        parent.setId(PARENT);
        parent.setRenewalOfApplyId(null);
        parent.setStatus(MarketResourceApplyDO.STATUS_APPROVED);
        parent.setIfAuth(MarketResourceApplyDO.AUTH_YES);
        parent.setReceivedTime(NOW.minusMonths(1));
        parent.setReplyDeadline(NOW.minusDays(20));
        parent.setDeliveryDeadline(NOW.minusDays(10));
        when(objectGuard.requireUserId()).thenReturn(USER);
        when(applyMapper.selectOwnedForRenewal( PARENT, USER)).thenReturn(parent);
        when(applyMapper.databaseNow()).thenReturn(NOW);
        when(applyMapper.nextApplyNoSeq()).thenReturn(88L);
        when(displayHelper.nextBizNo("APP", 88L)).thenReturn("APP-88");
        DataAssetDO asset = publishedAsset();
        asset.setShareAttr(shareAttr);
        // 首次续期资格仍由父申请有效性决定，本轮不增加资产必须当前发布的新业务政策。
        if (shareAttr == 2) asset.setStatus(DataAssetDO.STATUS_UNPUBLISHED);
        loadPublishedAsset(asset);
        stubWorkingDays();
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        doAnswer(call -> {
            ((MarketResourceApplyDO) call.getArgument(0)).setId(CHILD);
            return 1;
        }).when(applyMapper).insertRenewal(any(MarketResourceApplyDO.class));
        when(processMapper.insert(any(MarketCheckProcessDO.class))).thenReturn(1);
        MarketRenewalReqVO request = new MarketRenewalReqVO();
        request.setId(PARENT);
        request.setOperationId("test-independent-renewal");
        request.setApplyEndTime(parent.getApplyEndTime().plusMonths(6));

        assertEquals(CHILD, service.createRenewal(request));

        var inserted = ArgumentCaptor.forClass(MarketResourceApplyDO.class);
        verify(applyMapper).insertRenewal(inserted.capture());
        MarketResourceApplyDO child = inserted.getValue();
        int replyDays = shareAttr == DataAssetDO.SHARE_UNCONDITIONAL ? 1 : 10;
        assertEquals(NOW, child.getReceivedTime());
        assertEquals(NOW.plusDays(replyDays), child.getReplyDeadline());
        assertEquals(NOW.plusDays(replyDays + 20), child.getDeliveryDeadline());
        assertEquals(parent.getSnapshot(), child.getSnapshot());
        assertEquals(parent.getPurpose(), child.getPurpose());
        assertEquals(parent.getDutyMatter(), child.getDutyMatter());
        assertEquals(parent.getLegalBasis(), child.getLegalBasis());
        assertNull(child.getSupplementNote());
        assertEquals(NOW.minusMonths(1), parent.getReceivedTime());
        verifyNoInteractions(catalogService, supplyApiGuard, deliveryService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAUSE", "REVOKE", "EXPIRE"})
    void parentLifecycleChangeDoesNotCancelIndependentPendingChildApproval(String action) {
        MarketResourceApplyDO parent = supplement();
        parent.setId(PARENT);
        parent.setRenewalOfApplyId(null);
        parent.setStatus(MarketResourceApplyDO.STATUS_APPROVED);
        parent.setIfAuth(MarketResourceApplyDO.AUTH_YES);
        parent.setSourceRevision(4L);
        MarketResourceApplyDO child = supplement();
        child.setStatus(MarketResourceApplyDO.STATUS_PENDING);
        when(applyMapper.selectById(CHILD)).thenReturn(child);
        loadPublishedAsset(publishedAsset());
        when(objectGuard.requireUserId()).thenReturn(USER);
        successfulWrites();
        if ("EXPIRE".equals(action)) {
            parent.setApplyEndTime(NOW.minusSeconds(1));
            when(applyMapper.selectExpiredDue( 100)).thenReturn(List.of(parent));
            when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            assertEquals(1, service.expireDueApplies());
        } else {
            when(applyMapper.selectById(PARENT)).thenReturn(parent);
            if ("PAUSE".equals(action)) service.pauseApply(PARENT, "test-pause");
            else service.revokeApply(PARENT, "test-revoke");
        }
        assertEquals(MarketResourceApplyDO.STATUS_PENDING, child.getStatus());
        MarketCheckReqVO request = new MarketCheckReqVO();
        request.setId(CHILD);
        request.setStatus(MarketResourceApplyDO.STATUS_APPROVED);
        request.setExpectedRevision(2);

        service.checkApply(request);

        assertEquals(MarketResourceApplyDO.STATUS_APPROVED, child.getStatus());
        assertEquals(MarketResourceApplyDO.AUTH_YES, child.getIfAuth());
        verify(deliveryService).enqueueApprovedGrant(eq(child), eq(USER), eq(0L), isNull());
        if ("PAUSE".equals(action)) verify(deliveryService).enqueueSuspend(parent);
        else verify(deliveryService).enqueueRevoke(parent);
        verify(deliveryService, never()).enqueueRevoke(child);
        verify(deliveryService, never()).enqueueSuspend(child);
        verifyNoMoreInteractions(deliveryService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAUSE", "REVOKE", "EXPIRE"})
    void confirmedChildGrantKeepsItsOwnSourceAndEffectivenessAfterParentStops(String action) {
        var access = new MarketAccessServiceImpl();
        ReflectionTestUtils.setField(access, "applyMapper", applyMapper);
        when(applyMapper.databaseNow()).thenReturn(NOW);
        var delivery = new MarketDeliveryService();
        var operations = mock(MarketDeliveryOperationMapper.class);
        ReflectionTestUtils.setField(delivery, "operationMapper", operations);
        ReflectionTestUtils.setField(delivery, "approvalEvidenceMapper", mock(MarketApprovalEvidenceMapper.class));
        var parent = MarketDeliveryConfirmationFixture.apply();
        parent.setApplyEndTime(NOW.plusMonths(1));
        var child = BeanUtils.toBean(parent, MarketResourceApplyDO.class);
        child.setId(CHILD);
        child.setRenewalOfApplyId(PARENT);
        child.setSourceGrantId(delivery.sourceGrantId(child));
        child.setApplyEndTime(NOW.plusMonths(6));
        var receipt = MarketDeliveryConfirmationFixture.operation(delivery, child);
        when(operations.selectLatestByApply( CHILD)).thenReturn(receipt);
        String before = JsonUtils.toJsonString(child);
        switch (action) {
            case "PAUSE" -> parent.setPaused(1);
            case "REVOKE" -> parent.setStatus(MarketResourceApplyDO.STATUS_REVOKED);
            default -> parent.setApplyEndTime(NOW.minusSeconds(1));
        }

        assertFalse(access.isEffective(parent));
        assertTrue(access.isEffective(child));
        assertTrue(delivery.isConfirmedApply(child));
        assertEquals("asset:apply:7", delivery.sourceKey(parent));
        assertEquals("asset:apply:99", delivery.sourceKey(child));
        assertNotEquals(delivery.sourceGrantId(parent), delivery.sourceGrantId(child));
        assertEquals(before, JsonUtils.toJsonString(child));
        verify(operations, never()).selectLatestByApply( PARENT);
    }

    private void loadPublishedAsset(DataAssetDO asset) {
        when(assetMapper.selectById(ASSET)).thenReturn(asset);
        publishedAssets.put(ASSET, asset);
    }

    private void load(MarketResourceApplyDO child) {
        when(applyMapper.selectById(CHILD)).thenReturn(child);
        when(objectGuard.requireUserId()).thenReturn(USER);
    }

    private static MarketResourceApplyDO supplement() {
        var child = MarketResourceApplyDO.builder().id(CHILD).assetId(ASSET).assetType(DataAssetDO.TYPE_DATABASE_TABLE)
                .applyUserId(USER).renewalOfApplyId(PARENT).revision(2).catalogVersionId(12L)
                .purpose("统计分析").dutyMatter("履职义务").legalBasis("测试依据").fieldScope("")
                .applyUseType(MarketResourceApplyDO.USE_UNLIMITED).applyUseTimes(0)
                .applyEndTime(NOW.plusMonths(6)).paused(0).ifAuth(MarketResourceApplyDO.AUTH_NO)
                .status(MarketResourceApplyDO.STATUS_SUPPLEMENT).supplementNote("请补充材料").build();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("assetId", "5");
        evidence.put("assetVersion", "v1");
        evidence.put("catalogVersionId", "12");
        evidence.put("publishedSnapshotId", "11");
        evidence.put("databaseId", "4");
        evidence.put("schemaName", "public");
        evidence.put("tableName", "citizen");
        evidence.put("apiId", "");
        evidence.put("appId", "");
        evidence.put("fieldScope", "");
        evidence.put("dutyMatter", child.getDutyMatter());
        evidence.put("revision", "2");
        evidence.put("frozenColumns", List.of("Id"));
        child.setSnapshot(JsonUtils.toJsonString(evidence));
        return child;
    }

    private static DataAssetDO publishedAsset() {
        var asset = new DataAssetDO();
        asset.setId(ASSET);
        asset.setStatus(DataAssetDO.STATUS_PUBLISHED);
        asset.setAssetType(DataAssetDO.TYPE_DATABASE_TABLE);
        asset.setDatasourceId(4L);
        asset.setSchemaName("public");
        asset.setTableName("citizen");
        asset.setPublishedVersion("v1");
        asset.setPublishedSnapshotId(11L);
        asset.setPublishedCatalogVersionId(12L);
        asset.setShareAttr(DataAssetDO.SHARE_UNCONDITIONAL);
        return asset;
    }

    private static MarketResourceApplyDO apiSupplement() {
        MarketResourceApplyDO child = supplement();
        child.setApiId(2L);
        child.setAppId(3L);
        Map<String, Object> evidence = snapshot(child);
        evidence.put("apiId", "2");
        evidence.put("appId", "3");
        evidence.put("approvedContractHash", "test-contract");
        evidence.put("apiVersion", "v1");
        evidence.put("activeReleaseId", "21");
        child.setSnapshot(JsonUtils.toJsonString(evidence));
        return child;
    }

    private SupplyApiBindingRespDTO stubDeliveryBinding() {
        when(endpointMapper.selectByAssetAndApi(ASSET, 2L)).thenReturn(new MarketResourceEndpointDO());
        var app = new SupplyAppRespDTO();
        app.setId(3L);
        when(supplyAppApi.listAppsByCreator(USER)).thenReturn(CommonResult.success(List.of(app)));
        var binding = new SupplyApiBindingRespDTO();
        binding.setId(2L);
        binding.setDatabaseId(4L);
        binding.setSchemaName("public");
        binding.setTableName("citizen");
        binding.setFieldMappingVerified(true);
        binding.setActiveReleaseId(21L);
        binding.setContractHash("test-contract");
        binding.setVersion("v1");
        binding.setOutputFieldMapping(Map.of("id", "Id"));
        when(supplyApiGuard.requireQueryableTableApi(2L)).thenReturn(binding);
        return binding;
    }

    private static MetadataColumnRespDTO column(String name) {
        var column = new MetadataColumnRespDTO();
        column.setColumnName(name);
        return column;
    }

    private void stubWorkingDays() {
        when(workingDayService.plusWorkingDays(any(LocalDateTime.class), anyInt())).thenAnswer(call ->
                ((LocalDateTime) call.getArgument(0)).plusDays((Integer) call.getArgument(1)));
    }

    private void successfulWrites() {
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(processMapper.insert(any(MarketCheckProcessDO.class))).thenReturn(1);
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<MarketResourceApplyDO> captureCas() {
        ArgumentCaptor<LambdaUpdateWrapper<MarketResourceApplyDO>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(applyMapper).update(isNull(), captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> snapshot(MarketResourceApplyDO child) {
        return new LinkedHashMap<>(JsonUtils.parseObject(child.getSnapshot(), Map.class));
    }

    private static Map<?, ?> snapshotFromCas(LambdaUpdateWrapper<?> cas) {
        var match = Pattern.compile("snapshot\\s*=\\s*CAST\\(#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}\\s+AS\\s+jsonb\\)")
                .matcher(cas.getSqlSet());
        assertTrue(match.find());
        return JsonUtils.parseObject((String) cas.getParamNameValuePairs().get(match.group(1)), Map.class);
    }

    private static Object bound(LambdaUpdateWrapper<?> cas, String sql, String column) {
        var match = Pattern.compile("(?<![\\w.])" + column + "\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}").matcher(sql);
        assertTrue(match.find(), column + ": " + sql);
        return cas.getParamNameValuePairs().get(match.group(1));
    }

    private static void assertNewDeadlines(LambdaUpdateWrapper<?> cas, int replyDays) {
        var received = assertInstanceOf(LocalDateTime.class, bound(cas, cas.getSqlSet(), "received_time"));
        assertEquals(received.plusDays(replyDays), bound(cas, cas.getSqlSet(), "reply_deadline"));
        assertEquals(received.plusDays(replyDays + 20), bound(cas, cas.getSqlSet(), "delivery_deadline"));
    }
}
