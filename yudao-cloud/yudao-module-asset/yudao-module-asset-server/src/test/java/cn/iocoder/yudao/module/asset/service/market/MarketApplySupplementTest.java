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
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryOperationMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDemandMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceEndpointMapper;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import cn.iocoder.yudao.module.asset.service.monitor.AssetMonitorService;
import cn.iocoder.yudao.module.supply.api.app.SupplyAppApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_FORBIDDEN;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_NOT_EXISTS;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_STATUS_DENY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 要求补正的隔离编排契约：检查者/提供方 Guard、原版本 CAS、过程成功后才记录审计。
 * 复用 BaseMockitoUnitTest、真实 LambdaUpdateWrapper 与既有协作者契约，不加载 Spring/数据库。
 * Mock Guard 只证明服务没有绕过权限入口，不证明 Guard 内部的角色/提供方算法；
 * SQL 参数和异常断言不证明真实 PostgreSQL 执行、事务回滚或 best-effort 审计持久化。
 */
class MarketApplySupplementTest extends BaseMockitoUnitTest {

    private static final long APPLY_ID = 77L;
    private static final long OWNER_ID = 11L;
    private static final long ASSET_ID = 8L;
    private static final long CHECKER_ID = 9L;
    private static final String NOTE = "请补充职责依据";

    @InjectMocks
    private MarketApplyServiceImpl service;
    @Mock
    private MarketResourceApplyMapper applyMapper;
    @Mock
    private MarketCheckProcessMapper processMapper;
    @Mock
    private DataAssetMapper assetMapper;
    @Mock
    private MarketObjectGuard objectGuard;
    @Mock
    private MarketAuditRecorder auditRecorder;
    @Mock
    private MarketDemandMapper demandMapper;
    @Mock
    private MarketResourceEndpointMapper endpointMapper;
    @Mock
    private MarketDisplayHelper displayHelper;
    @Mock
    private MarketAccessService accessService;
    @Mock
    private AdminUserApi adminUserApi;
    @Mock
    private SupplyAppApi supplyAppApi;
    @Mock
    private MarketSupplyApiGuard supplyApiGuard;
    @Mock
    private AssetMonitorService assetMonitorService;
    @Mock
    private MarketNotifyHelper notifyHelper;
    @Mock
    private MarketDeliveryService deliveryService;
    @Mock
    private MarketDeliveryCoordinator deliveryCoordinator;
    @Mock
    private MarketDeliveryEvidenceService deliveryEvidenceService;
    @Mock
    private MarketDeliveryOperationMapper deliveryOperationMapper;
    @Mock
    private MarketWorkingDayService workingDayService;
    @Mock
    private AssetCatalogService catalogService;
    @Mock
    private PlatformTransactionManager transactionManager;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                MarketResourceApplyDO.class);
    }

    @BeforeEach
    void establishTenant() {
        TenantContextHolder.clear();
        TenantContextHolder.clear();
    }

    @AfterEach
    void preserveSupplementBoundaryAndClearTenant() {
        try {
            // 补正不是重提、交付或授权：不能新增元数据/应用读取、通知、交付、重算时限等副作用。
            verifyNoInteractions(demandMapper, endpointMapper, displayHelper, accessService, adminUserApi,
                    supplyAppApi, supplyApiGuard, assetMonitorService, notifyHelper, deliveryService,
                    deliveryCoordinator, deliveryEvidenceService, deliveryOperationMapper,
                    workingDayService, catalogService, transactionManager);
        } finally {
            TenantContextHolder.clear();
        }
    }

    @ParameterizedTest
    @CsvSource(value = {
            "0,77,11,8,9,1",
            "1,77,11,8,9,NULL",
            "1,77,11,8,9,3",
            "1,77,11,8,9,2147483647",
            "9007199254740993,9007199254740995,9007199254740997,9007199254740999,9007199254741001,2147483646",
            "9223372036854775807,9223372036854775807,9223372036854775806,9223372036854775805,9223372036854775804,19"
    }, nullValues = "NULL")
    void successUsesOriginalIdentityAndRevisionWithoutChangingFrozenApplication(
            long tenantId, long applyId, long ownerId, long assetId, long checkerId, Integer revision) {
        TenantContextHolder.clear();
        Fixture fixture = loadPending(applyId, ownerId, assetId, revision);
        String original = JsonUtils.toJsonString(fixture.apply());
        stubSuccessfulWrites(checkerId);

        service.requestSupplement(applyId, NOTE);

        assertCas(captureOnlyCas(applyId), fixture.apply(), tenantId, NOTE);
        assertProcess(captureOnlyProcess(), applyId, checkerId, NOTE);
        assertNotEquals(ownerId, checkerId, "过程操作人取检查者，不应偷换成申请人");
        verifySuccessOrder(fixture, NOTE);
        assertEquals(original, JsonUtils.toJsonString(fixture.apply()), "观察值、快照、版本及期限都不能被改写");
        assertNull(TenantContextHolder.getTenantId());
        assertFalse(TenantContextHolder.isIgnore());
    }

    @ParameterizedTest
    @MethodSource("allowedNotes")
    void nullableBlankAndBoundaryNotesRemainUnchangedAndAreBoundAsValues(String note) {
        Fixture fixture = loadPending();
        String original = JsonUtils.toJsonString(fixture.apply());
        stubSuccessfulWrites(CHECKER_ID);
        assertTrue(note == null || note.length() <= 500, "夹具遵守控制器 @Size(max = 500) 的边界");

        service.requestSupplement(APPLY_ID, note);

        assertCas(captureOnlyCas(APPLY_ID), fixture.apply(), 1L, note);
        assertProcess(captureOnlyProcess(), APPLY_ID, CHECKER_ID, note);
        verifySuccessOrder(fixture, note);
        assertEquals(original, JsonUtils.toJsonString(fixture.apply()));
    }

    static Stream<Arguments> allowedNotes() {
        // 不对隔离 Service 强加控制器并不存在的 @NotBlank，也不在 Service 中新增 501 字符校验。
        return Stream.of(null, "", " \t\r\n", NOTE, "  保留说明两侧空格  ", "补".repeat(500), " ".repeat(500),
                        "说明 '); SELECT 1; --\n\"补正\" \\ #{ew.paramNameValuePairs.MPGENVAL1} ${note}")
                .map(note -> Arguments.of((Object) note));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {DataAssetDO.STATUS_UNPUBLISHED, DataAssetDO.STATUS_PUBLISHED, DataAssetDO.STATUS_OFFLINE})
    void doesNotAddPublishedAssetOrMetadataValidationBeyondExistingCheckerGuard(Integer assetStatus) {
        Fixture fixture = loadPending();
        fixture.asset().setStatus(assetStatus);
        fixture.asset().setPublishedSnapshotId(null);
        fixture.asset().setPublishedCatalogVersionId(null);
        String original = JsonUtils.toJsonString(fixture.apply());
        stubSuccessfulWrites(CHECKER_ID);

        service.requestSupplement(APPLY_ID, NOTE);

        assertCas(captureOnlyCas(APPLY_ID), fixture.apply(), 1L, NOTE);
        assertProcess(captureOnlyProcess(), APPLY_ID, CHECKER_ID, NOTE);
        verifySuccessOrder(fixture, NOTE);
        assertEquals(original, JsonUtils.toJsonString(fixture.apply()));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {MarketResourceApplyDO.STATUS_DRAFT, MarketResourceApplyDO.STATUS_APPROVED,
            MarketResourceApplyDO.STATUS_REJECTED, MarketResourceApplyDO.STATUS_CANCELLED,
            MarketResourceApplyDO.STATUS_EXPIRED, MarketResourceApplyDO.STATUS_REVOKED,
            MarketResourceApplyDO.STATUS_SUPPLEMENT, 7, 9, -1, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void everyNonPendingOrUnknownStatusIsRejectedWithoutCasOrLaterEffects(Integer status) {
        Fixture fixture = loadPending();
        fixture.apply().setStatus(status);
        String original = JsonUtils.toJsonString(fixture.apply());

        assertBusinessFailure(MARKET_APPLY_STATUS_DENY, () -> service.requestSupplement(APPLY_ID, NOTE));

        verifyGuardedReadOnlyFailure(fixture);
        assertEquals(original, JsonUtils.toJsonString(fixture.apply()));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void invalidContentRevisionIsRejectedWithoutIncrementOrWrite(int revision) {
        Fixture fixture = loadPending();
        fixture.apply().setRevision(revision);
        String original = JsonUtils.toJsonString(fixture.apply());

        assertBusinessFailure(MARKET_APPLY_STATUS_DENY, () -> service.requestSupplement(APPLY_ID, NOTE));

        verifyGuardedReadOnlyFailure(fixture);
        assertEquals(original, JsonUtils.toJsonString(fixture.apply()));
    }

    @ParameterizedTest
    @CsvSource({"CHECK_PERMISSION,1", "PROVIDER,1", "UNAVAILABLE,1",
            "CHECK_PERMISSION,2", "PROVIDER,2", "UNAVAILABLE,2"})
    void checkerGuardFailureIsPreservedBeforeStatusValidationEvenForApplicant(String reason, int status) {
        Fixture fixture = loadPending();
        fixture.apply().setApplyUserId(CHECKER_ID);
        fixture.apply().setStatus(status);
        String original = JsonUtils.toJsonString(fixture.apply());
        // 两类权限拒绝由既有 Guard 决定；这里只验证相同对象传入且原异常不被状态异常掩盖。
        RuntimeException failure = "UNAVAILABLE".equals(reason)
                ? new IllegalStateException("test supplement guard unavailable") : exception(MARKET_APPLY_FORBIDDEN);
        doThrow(failure).when(objectGuard).assertCanCheckApply(same(fixture.apply()), same(fixture.asset()));

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.requestSupplement(APPLY_ID, NOTE)));

        verifyGuardedReadOnlyFailure(fixture);
        assertEquals(original, JsonUtils.toJsonString(fixture.apply()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void absentAssetReferenceOrMissingAssetStillGoesThroughCheckerGuard(boolean referenceAbsent) {
        MarketResourceApplyDO observed = pendingApplication(APPLY_ID, OWNER_ID, ASSET_ID, 3);
        if (referenceAbsent) {
            observed.setAssetId(null);
        } else {
            when(assetMapper.selectById(ASSET_ID)).thenReturn(null);
        }
        when(applyMapper.selectById(APPLY_ID)).thenReturn(observed);
        ServiceException failure = exception(MARKET_APPLY_FORBIDDEN);
        doThrow(failure).when(objectGuard).assertCanCheckApply(same(observed), isNull());
        String original = JsonUtils.toJsonString(observed);

        assertSame(failure, assertThrows(ServiceException.class, () -> service.requestSupplement(APPLY_ID, NOTE)));

        InOrder order = inOrder(applyMapper, assetMapper, objectGuard);
        order.verify(applyMapper).selectById(APPLY_ID);
        if (referenceAbsent) {
            verifyNoInteractions(assetMapper);
        } else {
            order.verify(assetMapper).selectById(ASSET_ID);
        }
        order.verify(objectGuard).assertCanCheckApply(same(observed), isNull());
        verifyFinished(order);
        verifyNoInteractions(processMapper, auditRecorder);
        assertEquals(original, JsonUtils.toJsonString(observed));
    }

    @Test
    void missingApplicationIsRejectedBeforeAssetLookupOrGuard() {
        when(applyMapper.selectById(APPLY_ID)).thenReturn(null);

        assertBusinessFailure(MARKET_APPLY_NOT_EXISTS, () -> service.requestSupplement(APPLY_ID, NOTE));

        verify(applyMapper).selectById(APPLY_ID);
        verifyNoMoreInteractions(applyMapper);
        verifyNoInteractions(assetMapper, objectGuard, processMapper, auditRecorder);
    }

    @ParameterizedTest
    @CsvSource({"APPLY,DUPLICATE", "APPLY,LOCK", "APPLY,OTHER",
            "ASSET,DUPLICATE", "ASSET,LOCK", "ASSET,OTHER"})
    void mapperReadFailureIsNotTranslatedSwallowedOrRetried(String stage, String type) {
        RuntimeException failure = databaseFailure(type);
        if ("APPLY".equals(stage)) {
            when(applyMapper.selectById(APPLY_ID)).thenThrow(failure);
        } else {
            when(applyMapper.selectById(APPLY_ID))
                    .thenReturn(pendingApplication(APPLY_ID, OWNER_ID, ASSET_ID, 3));
            when(assetMapper.selectById(ASSET_ID)).thenThrow(failure);
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.requestSupplement(APPLY_ID, NOTE)));

        InOrder order = inOrder(applyMapper, assetMapper);
        order.verify(applyMapper).selectById(APPLY_ID);
        if ("ASSET".equals(stage)) {
            order.verify(assetMapper).selectById(ASSET_ID);
        } else {
            verifyNoInteractions(assetMapper);
        }
        verifyFinished(order);
        verifyNoInteractions(objectGuard, processMapper, auditRecorder);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 2, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void casMustAffectExactlyOneRowWithoutProcessAuditOrRetry(int rows) {
        Fixture fixture = loadPending();
        String original = JsonUtils.toJsonString(fixture.apply());
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(rows);

        assertBusinessFailure(MARKET_APPLY_STATUS_DENY, () -> service.requestSupplement(APPLY_ID, NOTE));

        assertCas(captureOnlyCas(APPLY_ID), fixture.apply(), 1L, NOTE);
        verifyFinished(verifyThroughCas(fixture));
        verifyNoInteractions(processMapper, auditRecorder);
        assertEquals(original, JsonUtils.toJsonString(fixture.apply()));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 2, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void processMustInsertExactlyOneRowBeforeAuditWithoutRetry(int rows) {
        Fixture fixture = loadPending();
        String original = JsonUtils.toJsonString(fixture.apply());
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(objectGuard.requireUserId()).thenReturn(CHECKER_ID);
        when(processMapper.insert(any(MarketCheckProcessDO.class))).thenReturn(rows);

        assertBusinessFailure(MARKET_APPLY_STATUS_DENY, () -> service.requestSupplement(APPLY_ID, NOTE));

        assertCas(captureOnlyCas(APPLY_ID), fixture.apply(), 1L, NOTE);
        assertProcess(captureOnlyProcess(), APPLY_ID, CHECKER_ID, NOTE);
        InOrder order = verifyThroughCas(fixture);
        order.verify(objectGuard).requireUserId();
        order.verify(processMapper).insert(any(MarketCheckProcessDO.class));
        verifyFinished(order);
        verifyNoInteractions(auditRecorder);
        assertEquals(original, JsonUtils.toJsonString(fixture.apply()));
    }

    @ParameterizedTest
    @CsvSource({"CAS,DUPLICATE", "CAS,LOCK", "CAS,OTHER",
            "PROCESS,DUPLICATE", "PROCESS,LOCK", "PROCESS,OTHER"})
    void writeFailureIsPreservedWithoutReReadFallbackRetryOrSuccessAudit(String stage, String type) {
        Fixture fixture = loadPending();
        String original = JsonUtils.toJsonString(fixture.apply());
        RuntimeException failure = databaseFailure(type);
        if ("CAS".equals(stage)) {
            when(applyMapper.update(isNull(), any(Wrapper.class))).thenThrow(failure);
        } else {
            when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
            when(objectGuard.requireUserId()).thenReturn(CHECKER_ID);
            when(processMapper.insert(any(MarketCheckProcessDO.class))).thenThrow(failure);
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.requestSupplement(APPLY_ID, NOTE)));

        assertCas(captureOnlyCas(APPLY_ID), fixture.apply(), 1L, NOTE);
        InOrder order = verifyThroughCas(fixture);
        if ("PROCESS".equals(stage)) {
            assertProcess(captureOnlyProcess(), APPLY_ID, CHECKER_ID, NOTE);
            order.verify(objectGuard).requireUserId();
            order.verify(processMapper).insert(any(MarketCheckProcessDO.class));
        } else {
            verifyNoInteractions(processMapper);
        }
        verifyFinished(order);
        verifyNoInteractions(auditRecorder);
        assertEquals(original, JsonUtils.toJsonString(fixture.apply()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"FORBIDDEN", "OTHER"})
    void operatorResolutionFailureDoesNotUseApplicantAsFallbackOrAppendProcess(String type) {
        Fixture fixture = loadPending();
        String original = JsonUtils.toJsonString(fixture.apply());
        RuntimeException failure = "FORBIDDEN".equals(type)
                ? exception(MARKET_APPLY_FORBIDDEN) : new IllegalStateException("test supplement operator unavailable");
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(objectGuard.requireUserId()).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.requestSupplement(APPLY_ID, NOTE)));

        assertCas(captureOnlyCas(APPLY_ID), fixture.apply(), 1L, NOTE);
        InOrder order = verifyThroughCas(fixture);
        order.verify(objectGuard).requireUserId();
        verifyFinished(order);
        verifyNoInteractions(processMapper, auditRecorder);
        assertEquals(original, JsonUtils.toJsonString(fixture.apply()));
    }

    private Fixture loadPending() {
        return loadPending(APPLY_ID, OWNER_ID, ASSET_ID, 3);
    }

    private Fixture loadPending(long applyId, long ownerId, long assetId, Integer revision) {
        MarketResourceApplyDO apply = pendingApplication(applyId, ownerId, assetId, revision);
        DataAssetDO asset = new DataAssetDO();
        asset.setId(assetId);
        asset.setStatus(DataAssetDO.STATUS_PUBLISHED);
        asset.setAssetType(DataAssetDO.TYPE_DATABASE_TABLE);
        when(applyMapper.selectById(applyId)).thenReturn(apply);
        when(assetMapper.selectById(assetId)).thenReturn(asset);
        return new Fixture(apply, asset);
    }

    private static MarketResourceApplyDO pendingApplication(long applyId, long ownerId, long assetId,
                                                           Integer revision) {
        LocalDateTime received = LocalDateTime.of(2020, 1, 1, 0, 0);
        return MarketResourceApplyDO.builder().id(applyId).applyUserId(ownerId).assetId(assetId)
                .status(MarketResourceApplyDO.STATUS_PENDING).revision(revision)
                .snapshot("{\"previous\":\"frozen\",\"frozenColumns\":[\"Id\"],\"revision\":\"legacy\"}")
                .fieldScope("Id").catalogVersionId(9007199254740995L).supplementNote("上一轮补正说明")
                .receivedTime(received).replyDeadline(received.plusDays(1)).deliveryDeadline(received.plusDays(21))
                .applyStartTime(received).applyEndTime(received.plusYears(1))
                .ifAuth(MarketResourceApplyDO.AUTH_NO).sourceRevision(9L).sourceGrantId("test-supplement-source")
                .deliveryStatus(0).paused(0).build();
    }

    private void stubSuccessfulWrites(long checkerId) {
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(objectGuard.requireUserId()).thenReturn(checkerId);
        when(processMapper.insert(any(MarketCheckProcessDO.class))).thenReturn(1);
    }

    private static RuntimeException databaseFailure(String type) {
        return switch (type) {
            case "DUPLICATE" -> new DuplicateKeyException("test supplement constraint failure");
            case "LOCK" -> new CannotAcquireLockException("test supplement lock failure");
            default -> new IllegalStateException("test supplement database unavailable");
        };
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<MarketResourceApplyDO> captureOnlyCas(long applyId) {
        ArgumentCaptor<LambdaUpdateWrapper<MarketResourceApplyDO>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(applyMapper).selectById(applyId);
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

    private InOrder verifyGuardedRead(Fixture fixture) {
        InOrder order = inOrder(applyMapper, assetMapper, objectGuard, processMapper, auditRecorder);
        order.verify(applyMapper).selectById(fixture.apply().getId());
        order.verify(assetMapper).selectById(fixture.apply().getAssetId());
        order.verify(objectGuard).assertCanCheckApply(same(fixture.apply()), same(fixture.asset()));
        return order;
    }

    private InOrder verifyThroughCas(Fixture fixture) {
        InOrder order = verifyGuardedRead(fixture);
        order.verify(applyMapper).update(isNull(), any(Wrapper.class));
        // CAS 成功后、写过程前在锁内复验资产归属；CAS 失败时不发生。锁语义见 MarketProviderDecisionSerializationPostgresTest。
        if (mockingDetails(objectGuard).getInvocations().stream()
                .anyMatch(call -> call.getMethod().getName().equals("lockProviderAsset"))) {
            order.verify(objectGuard).lockProviderAsset(same(fixture.apply()), eq(false));
        }
        return order;
    }

    private void verifyGuardedReadOnlyFailure(Fixture fixture) {
        verifyFinished(verifyGuardedRead(fixture));
        verifyNoInteractions(processMapper, auditRecorder);
    }

    private void verifySuccessOrder(Fixture fixture, String note) {
        InOrder order = verifyThroughCas(fixture);
        order.verify(objectGuard).requireUserId();
        order.verify(processMapper).insert(any(MarketCheckProcessDO.class));
        order.verify(auditRecorder).record("SUPPLEMENT", "APPLY", fixture.apply().getId(),
                MarketResourceApplyDO.STATUS_PENDING, MarketResourceApplyDO.STATUS_SUPPLEMENT, note, "OK");
        verifyFinished(order);
    }

    private void verifyFinished(InOrder order) {
        order.verifyNoMoreInteractions();
        verifyNoMoreInteractions(applyMapper, assetMapper, objectGuard, processMapper, auditRecorder);
    }

    private static void assertProcess(MarketCheckProcessDO process, long applyId, long checkerId, String note) {
        assertNull(process.getId(), "主键交给既有 Mapper/平台生成机制");
        assertEquals(applyId, process.getResourceApplyId());
        assertEquals(MarketCheckProcessDO.ACTION_SUPPLEMENT, process.getAction());
        assertEquals(MarketResourceApplyDO.STATUS_PENDING, process.getFromStatus());
        assertEquals(MarketResourceApplyDO.STATUS_SUPPLEMENT, process.getToStatus());
        assertEquals(checkerId, process.getOperatorUserId());
        assertEquals(note, process.getNote());
    }

    private static void assertCas(LambdaUpdateWrapper<MarketResourceApplyDO> cas,
                                  MarketResourceApplyDO observed, long tenantId, String note) {
        assertNull(cas.getEntity(), "不能通过更新实体附带重写快照或其他申请字段");
        String predicate = cas.getSqlSegment();

        assertFalse(predicate.contains("tenant_id"));
        assertBound(cas, predicate, "id", observed.getId());
        assertBound(cas, predicate, "status", MarketResourceApplyDO.STATUS_PENDING);
        assertBound(cas, predicate, "apply_user_id", observed.getApplyUserId());
        assertBound(cas, predicate, "asset_id", observed.getAssetId());
        if (observed.getRevision() == null) {
            assertTrue(Pattern.compile("(?i)\\brevision\\s+IS\\s+NULL\\b").matcher(predicate).find());
            assertFalse(Pattern.compile("(?i)\\brevision\\s*=").matcher(predicate).find(),
                    "历史 NULL 版本仍匹配 NULL，补正不将其初始化为 1");
            assertEquals(List.of("apply_user_id", "asset_id", "id", "status"),
                    assignmentColumns(predicate));
        } else {
            assertBound(cas, predicate, "revision", observed.getRevision());
            assertFalse(Pattern.compile("(?i)\\brevision\\s+IS\\s+NULL\\b").matcher(predicate).find());
            assertEquals(List.of("apply_user_id", "asset_id", "id", "revision", "status"),
                    assignmentColumns(predicate));
        }
        assertFalse(Pattern.compile("(?i)\\bOR\\b").matcher(predicate).find(), "CAS 条件不得用 OR 放宽");
        String sqlSet = cas.getSqlSet();
        assertBound(cas, sqlSet, "status", MarketResourceApplyDO.STATUS_SUPPLEMENT);
        assertBound(cas, sqlSet, "supplement_note", note);
        assertEquals(List.of("status", "supplement_note"), assignmentColumns(sqlSet),
                "仅 SET 状态和补正说明；不增版本、不刷新快照或期限、不改授权/交付字段");
        assertEquals(observed.getRevision() == null ? 6 : 7, cas.getParamNameValuePairs().size());
        if (note != null && !note.isBlank()) {
            assertFalse((predicate + sqlSet).contains(note), "说明必须绑定参数，不拼接或渲染 SQL");
        }
    }

    private static List<String> assignmentColumns(String sql) {
        return Pattern.compile("(?i)(?<![\\w.])([a-z_]+)\\s*=").matcher(sql)
                .results().map(result -> result.group(1)).sorted().toList();
    }

    private static void assertBound(LambdaUpdateWrapper<?> wrapper, String sql, String column, Object expected) {
        var binding = Pattern.compile("(?i)(?<![\\w.])" + Pattern.quote(column)
                + "\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}").matcher(sql);
        assertTrue(binding.find(), "缺少参数绑定 " + column + ": " + sql);
        assertTrue(wrapper.getParamNameValuePairs().containsKey(binding.group(1)), "NULL 值也必须真实绑定");
        assertEquals(expected, wrapper.getParamNameValuePairs().get(binding.group(1)), column);
        assertFalse(binding.find(), column + " 不应重复设置或匹配");
    }

    private static void assertBusinessFailure(ErrorCode code, org.junit.jupiter.api.function.Executable action) {
        ServiceException failure = assertThrows(ServiceException.class, action);
        assertEquals(code.getCode(), failure.getCode());
    }

    private record Fixture(MarketResourceApplyDO apply, DataAssetDO asset) {
    }

}
