package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCheckReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketCheckProcessDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.monitor.AssetUsageLogDO;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_FORBIDDEN;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_NOT_EXISTS;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_SCOPE_UNCONFIRMED;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_STATUS_DENY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 审批通过/驳回的隔离编排契约：复用 BaseMockitoUnitTest 和真实字段范围/CAS 构造逻辑。
 * 仅 Mock 外部协作者，不加载 Spring 或数据库；Guard Mock 不证明其内部授权算法。
 * 过程写入失败后的对象/调用断言不证明真实事务回滚；SQL 参数断言不证明 PostgreSQL 执行。
 * 通知和审计抛错场景仅验证协作者异常传播，不宣称真实 best-effort 实现必然抛错或持久化成功。
 */
class MarketApplyCheckingTest extends BaseMockitoUnitTest {

    private static final long APPLY_ID = 77L;
    private static final long OWNER_ID = 11L;
    private static final long ASSET_ID = 8L;
    private static final long CHECKER_ID = 9L;
    private static final long DEPT_ID = 19L;
    private static final String NOTE = "本轮审核意见";
    private static final LocalDateTime OLD_CHECK_TIME = LocalDateTime.of(2020, 1, 1, 0, 0);

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
    private MarketDeliveryService deliveryService;
    @Mock
    private AssetMonitorService assetMonitorService;
    @Mock
    private MarketNotifyHelper notifyHelper;
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
    void forbidNewIntegrationsAndClearContext() {
        try {
            verifyNoInteractions(demandMapper, endpointMapper, displayHelper, accessService, adminUserApi,
                    supplyAppApi, supplyApiGuard, deliveryCoordinator, deliveryEvidenceService,
                    deliveryOperationMapper, workingDayService, catalogService, transactionManager);
        } finally {
            TenantContextHolder.clear();
        }
    }

    @ParameterizedTest
    @MethodSource("successfulBoundaries")
    void successKeepsOriginalCasAndFreezingButPublishesCurrentReviewOnlyAfterProcess(
            int status, long tenant, long id, long owner, long assetId, long checker, Integer revision) {
        TenantContextHolder.clear();
        Fixture fixture = loadPending(id, owner, assetId, revision);
        MarketCheckReqVO req = request(id, status, NOTE);
        stubSuccess(fixture, req, checker, DEPT_ID);

        service.checkApply(req);

        assertSuccessfulWrites(fixture, req, tenant, checker);
        verifySuccessOrder(fixture, req, DEPT_ID, true);
        assertNull(TenantContextHolder.getTenantId());
        assertFalse(TenantContextHolder.isIgnore());
    }

    static Stream<Arguments> successfulBoundaries() {
        return decisions().flatMap(status -> Stream.of(
                Arguments.of(status, 0L, APPLY_ID, OWNER_ID, ASSET_ID, CHECKER_ID, 1),
                Arguments.of(status, 1L, APPLY_ID, OWNER_ID, ASSET_ID, CHECKER_ID, null),
                Arguments.of(status, 1L, APPLY_ID, OWNER_ID, ASSET_ID, CHECKER_ID, 3),
                Arguments.of(status, 1L, APPLY_ID, OWNER_ID, ASSET_ID, CHECKER_ID, Integer.MAX_VALUE),
                Arguments.of(status, 9007199254740993L, 9007199254740995L, 9007199254740997L,
                        9007199254740999L, 9007199254741001L, Integer.MAX_VALUE - 1),
                Arguments.of(status, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE - 1,
                        Long.MAX_VALUE - 2, Long.MAX_VALUE - 3, 19)));
    }

    @ParameterizedTest
    @MethodSource("reviewNotes")
    void reviewNoteIsBoundAndDeliveredVerbatimIncludingNullBlankAndQuotedValues(int status, String note) {
        Fixture fixture = loadPending();
        MarketCheckReqVO req = request(APPLY_ID, status, note);
        stubSuccess(fixture, req, CHECKER_ID, DEPT_ID);

        service.checkApply(req);

        assertSuccessfulWrites(fixture, req, 1L, CHECKER_ID);
        verifySuccessOrder(fixture, req, DEPT_ID, true);
    }

    static Stream<Arguments> reviewNotes() {
        return decisions().flatMap(status -> Stream.of(null, "", " \t\r\n", NOTE, "  保留两侧空格  ",
                        "审".repeat(500), " ".repeat(500), "意见 '); SELECT 1; --\n\"审核\" \\ ${note} #{value}")
                .map(note -> Arguments.of(status, note)));
    }



    static Stream<Arguments> untrustedTenants() {
        return decisions().flatMap(status -> Stream.of(Arguments.of(status, null, false),
                Arguments.of(status, -1L, false), Arguments.of(status, Long.MIN_VALUE, false),
                Arguments.of(status, null, true), Arguments.of(status, 0L, true),
                Arguments.of(status, 1L, true), Arguments.of(status, 9007199254740993L, true),
                Arguments.of(status, Long.MAX_VALUE, true)));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, 1, 4, 5, 6, 7, 8, 9, -1, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void invalidRequestedDecisionIsRejectedAfterGuardButBeforeActorAndWrites(Integer status) {
        Fixture fixture = loadPending();

        assertBusinessFailure(MARKET_APPLY_STATUS_DENY,
                () -> service.checkApply(request(APPLY_ID, status, NOTE)));

        finish(verifyGuardedRead(fixture));
        assertOriginal(fixture);
    }

    @ParameterizedTest
    @MethodSource("nonPendingStates")
    void nonPendingOrUnknownCurrentStateCannotBeApprovedOrRejected(int status, Integer current) {
        Fixture fixture = loadPendingWithState(current, 3);
        when(objectGuard.requireUserId()).thenReturn(CHECKER_ID);

        assertBusinessFailure(MARKET_APPLY_STATUS_DENY,
                () -> service.checkApply(request(APPLY_ID, status, NOTE)));

        InOrder order = verifyGuardedRead(fixture);
        order.verify(objectGuard).requireUserId();
        finish(order);
        assertOriginal(fixture);
    }

    static Stream<Arguments> nonPendingStates() {
        return decisions().flatMap(status -> Stream.of(null, 0, 2, 3, 4, 5, 6, 7, 8, 9, -1,
                        Integer.MIN_VALUE, Integer.MAX_VALUE)
                .map(current -> Arguments.of(status, current)));
    }

    @ParameterizedTest
    @MethodSource("invalidRevisions")
    void invalidRevisionFailsWithoutUpdatingOrInventingANewVersion(int status, int revision) {
        Fixture fixture = loadPendingWithState(MarketResourceApplyDO.STATUS_PENDING, revision);
        when(objectGuard.requireUserId()).thenReturn(CHECKER_ID);

        assertBusinessFailure(MARKET_APPLY_STATUS_DENY,
                () -> service.checkApply(request(APPLY_ID, status, NOTE)));

        InOrder order = verifyGuardedRead(fixture);
        order.verify(objectGuard).requireUserId();
        finish(order);
        assertOriginal(fixture);
    }

    static Stream<Arguments> invalidRevisions() {
        return decisions().flatMap(status -> Stream.of(0, -1, Integer.MIN_VALUE)
                .map(revision -> Arguments.of(status, revision)));
    }

    @ParameterizedTest
    @MethodSource("guardFailures")
    void guardFailureIsPreservedEvenForApplicantAndInvalidRequestedDecision(int status, String type) {
        Fixture fixture = loadPending(APPLY_ID, CHECKER_ID, ASSET_ID, 3);
        RuntimeException failure = "FORBIDDEN".equals(type) ? exception(MARKET_APPLY_FORBIDDEN)
                : new IllegalStateException("test checking guard unavailable");
        doThrow(failure).when(objectGuard).assertCanCheckApply(same(fixture.apply()), same(fixture.asset()));

        assertSame(failure, assertThrows(RuntimeException.class,
                () -> service.checkApply(request(APPLY_ID, status, NOTE))));

        finish(verifyGuardedRead(fixture));
        assertOriginal(fixture);
    }

    static Stream<Arguments> guardFailures() {
        return Stream.of(2, 3, 9).flatMap(status -> Stream.of("FORBIDDEN", "OTHER")
                .map(type -> Arguments.of(status, type)));
    }

    @ParameterizedTest
    @MethodSource("missingAssets")
    void missingAssetStillRequiresGuardAndNeverFallsBackToApplicant(int status, boolean absentReference) {
        Fixture fixture = loadMissingAsset(absentReference);
        ServiceException failure = exception(MARKET_APPLY_FORBIDDEN);
        doThrow(failure).when(objectGuard).assertCanCheckApply(same(fixture.apply()), isNull());

        assertSame(failure, assertThrows(ServiceException.class,
                () -> service.checkApply(request(APPLY_ID, status, NOTE))));

        finish(verifyGuardedRead(fixture));
        assertOriginal(fixture);
    }

    @ParameterizedTest
    @MethodSource("missingAssets")
    void guardAllowedMissingAssetStillCannotBeApprovedButCanBeRejected(int status, boolean absentReference) {
        Fixture fixture = loadMissingAsset(absentReference);
        MarketCheckReqVO req = request(APPLY_ID, status, NOTE);
        if (approved(req)) {
            when(objectGuard.requireUserId()).thenReturn(CHECKER_ID);

            assertBusinessFailure(MARKET_APPLY_SCOPE_UNCONFIRMED, () -> service.checkApply(req));

            InOrder order = verifyGuardedRead(fixture);
            order.verify(objectGuard).requireUserId();
            finish(order);
            assertOriginal(fixture);
        } else {
            stubSuccess(fixture, req, CHECKER_ID, DEPT_ID);

            service.checkApply(req);

            assertSuccessfulWrites(fixture, req, 1L, CHECKER_ID);
            verifySuccessOrder(fixture, req, DEPT_ID, false);
        }
    }

    static Stream<Arguments> missingAssets() {
        return decisions().flatMap(status -> Stream.of(true, false)
                .map(absent -> Arguments.of(status, absent)));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void missingApplicationFailsBeforeAssetGuardOrWrites(int status) {
        when(applyMapper.selectById(APPLY_ID)).thenReturn(null);

        assertBusinessFailure(MARKET_APPLY_NOT_EXISTS,
                () -> service.checkApply(request(APPLY_ID, status, NOTE)));

        InOrder order = pipelineOrder();
        order.verify(applyMapper).selectById(APPLY_ID);
        finish(order);
    }

    @ParameterizedTest
    @MethodSource("readFailures")
    void mapperReadExceptionIsNotTranslatedRetriedOrFollowedByEffects(int status, String stage, String type) {
        RuntimeException failure = databaseFailure(type);
        if ("APPLY".equals(stage)) {
            when(applyMapper.selectById(APPLY_ID)).thenThrow(failure);
        } else {
            when(applyMapper.selectById(APPLY_ID)).thenReturn(pendingApplication(APPLY_ID, OWNER_ID, ASSET_ID, 3));
            when(assetMapper.selectById(ASSET_ID)).thenThrow(failure);
        }

        assertSame(failure, assertThrows(RuntimeException.class,
                () -> service.checkApply(request(APPLY_ID, status, NOTE))));

        InOrder order = pipelineOrder();
        order.verify(applyMapper).selectById(APPLY_ID);
        if ("ASSET".equals(stage)) {
            order.verify(assetMapper).selectById(ASSET_ID);
        }
        finish(order);
    }

    static Stream<Arguments> readFailures() {
        return decisions().flatMap(status -> Stream.of("APPLY", "ASSET")
                .flatMap(stage -> failureTypes().map(type -> Arguments.of(status, stage, type))));
    }

    @ParameterizedTest
    @MethodSource("actorFailures")
    void operatorFailureIsPreservedBeforeCasWithoutUsingApplicantAsFallback(int status, String type) {
        Fixture fixture = loadPending();
        RuntimeException failure = "FORBIDDEN".equals(type) ? exception(MARKET_APPLY_FORBIDDEN)
                : new IllegalStateException("test checking actor unavailable");
        when(objectGuard.requireUserId()).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class,
                () -> service.checkApply(request(APPLY_ID, status, NOTE))));

        InOrder order = verifyGuardedRead(fixture);
        order.verify(objectGuard).requireUserId();
        finish(order);
        assertOriginal(fixture);
    }

    static Stream<Arguments> actorFailures() {
        return decisions().flatMap(status -> Stream.of("FORBIDDEN", "OTHER")
                .map(type -> Arguments.of(status, type)));
    }

    @ParameterizedTest
    @MethodSource("invalidWriteCounts")
    void casAndProcessMustEachAffectExactlyOneRowBeforeAnySuccessObjectOrEffect(
            int status, String stage, int count) {
        Fixture fixture = loadPending();
        MarketCheckReqVO req = request(APPLY_ID, status, NOTE);
        when(objectGuard.requireUserId()).thenReturn(CHECKER_ID);
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn("CAS".equals(stage) ? count : 1);
        if ("PROCESS".equals(stage)) {
            when(processMapper.insert(any(MarketCheckProcessDO.class))).thenReturn(count);
        }

        assertBusinessFailure(MARKET_APPLY_STATUS_DENY, () -> service.checkApply(req));

        assertCas(captureCas(), fixture.apply(), req, 1L, CHECKER_ID);
        InOrder order = verifyThroughCas(fixture);
        if ("PROCESS".equals(stage)) {
            assertProcess(captureProcess(), req, CHECKER_ID);
            order.verify(processMapper).insert(any(MarketCheckProcessDO.class));
        }
        finish(order);
        assertOriginal(fixture);
    }

    static Stream<Arguments> invalidWriteCounts() {
        return decisions().flatMap(status -> Stream.of("CAS", "PROCESS")
                .flatMap(stage -> Stream.of(0, -1, 2, Integer.MIN_VALUE, Integer.MAX_VALUE)
                        .map(count -> Arguments.of(status, stage, count))));
    }

    @ParameterizedTest
    @MethodSource("writeFailures")
    void casOrProcessExceptionIsPreservedWithoutRetryMutationOrLaterEffects(int status, String stage, String type) {
        Fixture fixture = loadPending();
        MarketCheckReqVO req = request(APPLY_ID, status, NOTE);
        RuntimeException failure = databaseFailure(type);
        when(objectGuard.requireUserId()).thenReturn(CHECKER_ID);
        if ("CAS".equals(stage)) {
            when(applyMapper.update(isNull(), any(Wrapper.class))).thenThrow(failure);
        } else {
            when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
            when(processMapper.insert(any(MarketCheckProcessDO.class))).thenThrow(failure);
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.checkApply(req)));

        assertCas(captureCas(), fixture.apply(), req, 1L, CHECKER_ID);
        InOrder order = verifyThroughCas(fixture);
        if ("PROCESS".equals(stage)) {
            assertProcess(captureProcess(), req, CHECKER_ID);
            order.verify(processMapper).insert(any(MarketCheckProcessDO.class));
        }
        finish(order);
        assertOriginal(fixture);
    }

    static Stream<Arguments> writeFailures() {
        return decisions().flatMap(status -> Stream.of("CAS", "PROCESS")
                .flatMap(stage -> failureTypes().map(type -> Arguments.of(status, stage, type))));
    }

    @ParameterizedTest
    @MethodSource("invalidSnapshots")
    void approvalRejectsMalformedIncompleteOrMismatchedFrozenEvidenceWithoutMetadataFallback(String snapshot) {
        MarketResourceApplyDO apply = pendingApplication(APPLY_ID, OWNER_ID, ASSET_ID, 3);
        apply.setSnapshot(snapshot);
        Fixture fixture = load(apply, publishedTable(ASSET_ID));
        when(objectGuard.requireUserId()).thenReturn(CHECKER_ID);

        assertBusinessFailure(MARKET_APPLY_SCOPE_UNCONFIRMED,
                () -> service.checkApply(request(APPLY_ID, MarketResourceApplyDO.STATUS_APPROVED, NOTE)));

        InOrder order = verifyGuardedRead(fixture);
        order.verify(objectGuard).requireUserId();
        finish(order);
        assertOriginal(fixture);
    }

    @ParameterizedTest
    @MethodSource("invalidSnapshots")
    void rejectionCanCloseTheSameUntrustedOldEvidenceWithoutRepairOrGrant(String snapshot) {
        MarketResourceApplyDO apply = pendingApplication(APPLY_ID, OWNER_ID, ASSET_ID, 3);
        apply.setSnapshot(snapshot);
        Fixture fixture = load(apply, publishedTable(ASSET_ID));
        MarketCheckReqVO req = request(APPLY_ID, MarketResourceApplyDO.STATUS_REJECTED, NOTE);
        stubSuccess(fixture, req, CHECKER_ID, DEPT_ID);

        service.checkApply(req);

        assertSuccessfulWrites(fixture, req, 1L, CHECKER_ID);
        verifySuccessOrder(fixture, req, DEPT_ID, false);
    }

    static Stream<Arguments> invalidSnapshots() {
        List<String> snapshots = new ArrayList<>();
        snapshots.add(null);
        snapshots.addAll(List.of("", " ", "not-json", "null", "[]", "{}",
                "{\"frozenColumns\":[]}", "{\"frozenColumns\":\"Id\"}", "{\"frozenColumns\":[\"*\"]}",
                "{\"frozenColumns\":[null]}", "{\"frozenColumns\":[\"Id\",\"Id\"]}",
                "{\"frozenColumns\":[1]}", "{\"frozenColumns\":[\" \"]}"));
        for (String key : List.of("assetId", "assetVersion", "catalogVersionId", "publishedSnapshotId",
                "databaseId", "schemaName", "tableName")) {
            Map<String, Object> missing = frozenSnapshot(ASSET_ID);
            missing.remove(key);
            snapshots.add(JsonUtils.toJsonString(missing));
            Map<String, Object> mismatch = frozenSnapshot(ASSET_ID);
            mismatch.put(key, "another-object-or-version");
            snapshots.add(JsonUtils.toJsonString(mismatch));
            Map<String, Object> wrongType = frozenSnapshot(ASSET_ID);
            wrongType.put(key, 8L);
            snapshots.add(JsonUtils.toJsonString(wrongType));
        }
        return snapshots.stream().map(snapshot -> Arguments.of((Object) snapshot));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ID", "DATABASE", "SCHEMA", "TABLE", "VERSION", "CATALOG", "PUBLICATION"})
    void approvalAlsoChecksLiveAssetIdentityAndPhysicalBinding(String changed) {
        MarketResourceApplyDO apply = pendingApplication(APPLY_ID, OWNER_ID, ASSET_ID, 3);
        DataAssetDO asset = publishedTable(ASSET_ID);
        switch (changed) {
            case "ID" -> asset.setId(ASSET_ID + 1);
            case "DATABASE" -> asset.setDatasourceId(null);
            case "SCHEMA" -> asset.setSchemaName(" ");
            case "TABLE" -> asset.setTableName(null);
            case "VERSION" -> asset.setPublishedVersion("v2");
            case "CATALOG" -> asset.setPublishedCatalogVersionId(52L);
            case "PUBLICATION" -> asset.setPublishedSnapshotId(42L);
            default -> throw new AssertionError(changed);
        }
        Fixture fixture = load(apply, asset);
        when(objectGuard.requireUserId()).thenReturn(CHECKER_ID);

        assertBusinessFailure(MARKET_APPLY_SCOPE_UNCONFIRMED,
                () -> service.checkApply(request(APPLY_ID, MarketResourceApplyDO.STATUS_APPROVED, NOTE)));

        InOrder order = verifyGuardedRead(fixture);
        order.verify(objectGuard).requireUserId();
        finish(order);
        assertOriginal(fixture);
    }

    @ParameterizedTest
    @MethodSource("lateFailures")
    void postProcessFailureStopsAtItsStageWithoutRetryOrOutOfOrderLaterEffect(int status, String stage) {
        Fixture fixture = loadPending();
        MarketCheckReqVO req = request(APPLY_ID, status, NOTE);
        RuntimeException failure = new IllegalStateException("test checking stage " + stage);
        when(objectGuard.requireUserId()).thenReturn(CHECKER_ID);
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(processMapper.insert(any(MarketCheckProcessDO.class))).thenReturn(1);
        if (approved(req)) {
            if ("DEPT".equals(stage)) {
                when(objectGuard.currentDeptId()).thenThrow(failure);
            } else {
                when(objectGuard.currentDeptId()).thenReturn(DEPT_ID);
            }
        }
        switch (stage) {
            case "DEPT" -> { }
            case "DELIVERY" -> doThrow(failure).when(deliveryService)
                    .enqueueApprovedGrant(same(fixture.apply()), eq(CHECKER_ID), eq(DEPT_ID), eq(NOTE));
            case "USAGE_READ" -> when(assetMapper.selectById(ASSET_ID)).thenReturn(fixture.asset()).thenThrow(failure);
            case "USAGE" -> doThrow(failure).when(assetMonitorService)
                    .recordUsageLog(same(fixture.asset()), eq(AssetUsageLogDO.ACTION_APPLY));
            case "NOTIFY" -> doThrow(failure).when(notifyHelper)
                    .notifyApplyChecked(same(fixture.apply()), same(fixture.asset()), eq(label(req)));
            case "AUDIT" -> doThrow(failure).when(auditRecorder).record(action(req), "APPLY", APPLY_ID,
                    MarketResourceApplyDO.STATUS_PENDING, status, NOTE, "OK");
            default -> throw new AssertionError(stage);
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.checkApply(req)));

        assertSuccessfulWrites(fixture, req, 1L, CHECKER_ID);
        finish(verifyAfterProcessUntil(fixture, req, DEPT_ID, true, stage));
    }

    static Stream<Arguments> lateFailures() {
        return Stream.concat(Stream.of("DEPT", "DELIVERY", "USAGE_READ", "USAGE", "NOTIFY", "AUDIT")
                        .map(stage -> Arguments.of(MarketResourceApplyDO.STATUS_APPROVED, stage)),
                Stream.of("NOTIFY", "AUDIT").map(stage -> Arguments.of(MarketResourceApplyDO.STATUS_REJECTED, stage)));
    }

    @Test
    void absentUsageAssetDoesNotInventAFailureOrSkipTheExistingNotificationAndAudit() {
        Fixture fixture = loadPending();
        MarketCheckReqVO req = request(APPLY_ID, MarketResourceApplyDO.STATUS_APPROVED, NOTE);
        stubSuccess(fixture, req, CHECKER_ID, DEPT_ID);
        when(assetMapper.selectById(ASSET_ID)).thenReturn(fixture.asset(), null);

        service.checkApply(req);

        assertSuccessfulWrites(fixture, req, 1L, CHECKER_ID);
        verifySuccessOrder(fixture, req, DEPT_ID, false);
    }

    @Test
    void nullableDepartmentIsPassedThroughWithoutNewPolicyOrApplicantDepartmentFallback() {
        Fixture fixture = loadPending();
        MarketCheckReqVO req = request(APPLY_ID, MarketResourceApplyDO.STATUS_APPROVED, NOTE);
        stubSuccess(fixture, req, CHECKER_ID, null);

        service.checkApply(req);

        assertSuccessfulWrites(fixture, req, 1L, CHECKER_ID);
        verifySuccessOrder(fixture, req, null, true);
    }

    private Fixture loadPending() {
        return loadPending(APPLY_ID, OWNER_ID, ASSET_ID, 3);
    }

    private Fixture loadPending(long id, long owner, long asset, Integer revision) {
        return load(pendingApplication(id, owner, asset, revision), publishedTable(asset));
    }

    private Fixture loadPendingWithState(Integer status, Integer revision) {
        MarketResourceApplyDO apply = pendingApplication(APPLY_ID, OWNER_ID, ASSET_ID, revision);
        apply.setStatus(status);
        return load(apply, publishedTable(ASSET_ID));
    }

    private Fixture loadMissingAsset(boolean absentReference) {
        MarketResourceApplyDO apply = pendingApplication(APPLY_ID, OWNER_ID, ASSET_ID, 3);
        if (absentReference) {
            apply.setAssetId(null);
        }
        return load(apply, null);
    }

    private Fixture load(MarketResourceApplyDO apply, DataAssetDO asset) {
        when(applyMapper.selectById(apply.getId())).thenReturn(apply);
        if (apply.getAssetId() != null) {
            when(assetMapper.selectById(apply.getAssetId())).thenReturn(asset);
        }
        return new Fixture(apply, asset, JsonUtils.toJsonString(apply), nonReviewFields(apply));
    }

    private static MarketResourceApplyDO pendingApplication(long id, long owner, long assetId, Integer revision) {
        return MarketResourceApplyDO.builder().id(id).applyUserId(owner).applyDeptId(71L).assetId(assetId)
                .assetType(DataAssetDO.TYPE_DATABASE_TABLE).status(MarketResourceApplyDO.STATUS_PENDING)
                .revision(revision).snapshot(JsonUtils.toJsonString(frozenSnapshot(assetId)))
                .fieldScope("legacy-form-is-not-approval-evidence").catalogVersionId(51L)
                .checkUserId(31L).checkTime(OLD_CHECK_TIME).checkNote("上一轮审核意见")
                .ifAuth(MarketResourceApplyDO.AUTH_NO).sourceRevision(7L).sourceGrantId("test-checking-source")
                .receivedTime(OLD_CHECK_TIME).replyDeadline(OLD_CHECK_TIME.plusDays(1))
                .deliveryDeadline(OLD_CHECK_TIME.plusDays(21)).applyStartTime(OLD_CHECK_TIME)
                .applyEndTime(OLD_CHECK_TIME.plusYears(1)).deliveryStatus(0).paused(0).build();
    }

    private static DataAssetDO publishedTable(long id) {
        DataAssetDO asset = new DataAssetDO();
        asset.setId(id);
        asset.setStatus(DataAssetDO.STATUS_PUBLISHED);
        asset.setAssetType(DataAssetDO.TYPE_DATABASE_TABLE);
        asset.setPublishedVersion("v1");
        asset.setPublishedCatalogVersionId(51L);
        asset.setPublishedSnapshotId(41L);
        asset.setDatasourceId(61L);
        asset.setSchemaName("public");
        asset.setTableName("citizen");
        return asset;
    }

    private static Map<String, Object> frozenSnapshot(long assetId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("assetId", Long.toString(assetId));
        snapshot.put("assetVersion", "v1");
        snapshot.put("catalogVersionId", "51");
        snapshot.put("publishedSnapshotId", "41");
        snapshot.put("databaseId", "61");
        snapshot.put("schemaName", "public");
        snapshot.put("tableName", "citizen");
        snapshot.put("frozenColumns", List.of("Id", "physical,column", "Case Sensitive"));
        return snapshot;
    }

    private void stubSuccess(Fixture fixture, MarketCheckReqVO req, long checker, Long department) {
        when(objectGuard.requireUserId()).thenReturn(checker);
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenAnswer(invocation -> {
            assertOriginal(fixture);
            return 1;
        });
        when(processMapper.insert(any(MarketCheckProcessDO.class))).thenAnswer(invocation -> {
            assertOriginal(fixture); // 过程真正返回成功前，禁止提前改写供交付/通知使用的观察对象。
            assertProcess(invocation.getArgument(0), req, checker);
            return 1;
        });
        if (approved(req)) {
            when(objectGuard.currentDeptId()).thenReturn(department);
            doAnswer(invocation -> {
                assertCurrentReview(fixture, req, checker);
                return null;
            }).when(deliveryService).enqueueApprovedGrant(same(fixture.apply()), eq(checker), eq(department), eq(req.getCheckNote()));
        }
        doAnswer(invocation -> {
            // 在调用当下读取，不能仅捕获同一个可变对象、等事后修改后再断言。
            assertCurrentReview(fixture, req, checker);
            return null;
        }).when(notifyHelper).notifyApplyChecked(same(fixture.apply()), same(fixture.asset()), eq(label(req)));
    }

    private void assertSuccessfulWrites(Fixture fixture, MarketCheckReqVO req, long tenant, long checker) {
        LambdaUpdateWrapper<MarketResourceApplyDO> cas = captureCas();
        assertCas(cas, fixture.apply(), req, tenant, checker);
        assertProcess(captureProcess(), req, checker);
        assertCurrentReview(fixture, req, checker);
        assertSame(boundValue(cas, cas.getSqlSet(), "check_time"), fixture.apply().getCheckTime(),
                "UPDATE 与成功对象必须共享同一个 checkTime 变量");
    }

    private static void assertCurrentReview(Fixture fixture, MarketCheckReqVO req, long checker) {
        assertEquals(req.getStatus(), fixture.apply().getStatus());
        assertEquals(approved(req) ? MarketResourceApplyDO.AUTH_YES : MarketResourceApplyDO.AUTH_NO,
                fixture.apply().getIfAuth());
        assertEquals(checker, fixture.apply().getCheckUserId());
        assertEquals(req.getCheckNote(), fixture.apply().getCheckNote(), "不能泄漏上一轮审核意见");
        assertNotNull(fixture.apply().getCheckTime());
        assertNotEquals(OLD_CHECK_TIME, fixture.apply().getCheckTime());
        assertEquals(fixture.nonReview(), nonReviewFields(fixture.apply()),
                "审批不改写快照、内容版本、有效期、sourceRevision、交付或暂停状态");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nonReviewFields(MarketResourceApplyDO apply) {
        Map<String, Object> fields = new LinkedHashMap<>(JsonUtils.parseObject(JsonUtils.toJsonString(apply), Map.class));
        List.of("status", "ifAuth", "checkUserId", "checkTime", "checkNote").forEach(fields::remove);
        return fields;
    }

    private static void assertOriginal(Fixture fixture) {
        assertEquals(fixture.original(), JsonUtils.toJsonString(fixture.apply()),
                "过程成功前必须保留完整观察对象；这不是数据库回滚断言");
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<MarketResourceApplyDO> captureCas() {
        ArgumentCaptor<LambdaUpdateWrapper<MarketResourceApplyDO>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(applyMapper).update(isNull(), captor.capture());
        return captor.getValue();
    }

    private MarketCheckProcessDO captureProcess() {
        ArgumentCaptor<MarketCheckProcessDO> captor = ArgumentCaptor.forClass(MarketCheckProcessDO.class);
        verify(processMapper).insert(captor.capture());
        return captor.getValue();
    }

    private InOrder pipelineOrder() {
        return inOrder(applyMapper, assetMapper, objectGuard, processMapper, deliveryService,
                assetMonitorService, notifyHelper, auditRecorder);
    }

    private InOrder verifyGuardedRead(Fixture fixture) {
        InOrder order = pipelineOrder();
        order.verify(applyMapper).selectById(fixture.apply().getId());
        if (fixture.apply().getAssetId() != null) {
            order.verify(assetMapper).selectById(fixture.apply().getAssetId());
        }
        order.verify(objectGuard).assertCanCheckApply(same(fixture.apply()), same(fixture.asset()));
        return order;
    }

    private InOrder verifyThroughCas(Fixture fixture) {
        InOrder order = verifyGuardedRead(fixture);
        order.verify(objectGuard).requireUserId();
        order.verify(applyMapper).update(isNull(), any(Wrapper.class));
        // CAS 成功后、写过程前在锁内复验资产归属；CAS 失败时不发生。锁语义见 MarketProviderDecisionSerializationPostgresTest。
        if (mockingDetails(objectGuard).getInvocations().stream()
                .anyMatch(call -> call.getMethod().getName().equals("lockProviderAsset"))) {
            order.verify(objectGuard).lockProviderAsset(same(fixture.apply()), anyBoolean());
        }
        return order;
    }

    private InOrder verifyAfterProcessUntil(Fixture fixture, MarketCheckReqVO req, Long dept, boolean usagePresent,
                                           String stop) {
        InOrder order = verifyThroughCas(fixture);
        order.verify(processMapper).insert(any(MarketCheckProcessDO.class));
        if (approved(req)) {
            order.verify(objectGuard).currentDeptId();
            if ("DEPT".equals(stop)) {
                return order;
            }
            order.verify(deliveryService).enqueueApprovedGrant(same(fixture.apply()),
                    eq(fixture.apply().getCheckUserId()), eq(dept), eq(req.getCheckNote()));
            if ("DELIVERY".equals(stop)) {
                return order;
            }
            order.verify(assetMapper).selectById(fixture.apply().getAssetId());
            if ("USAGE_READ".equals(stop)) {
                return order;
            }
            if (usagePresent) {
                order.verify(assetMonitorService).recordUsageLog(same(fixture.asset()), eq(AssetUsageLogDO.ACTION_APPLY));
            }
            if ("USAGE".equals(stop)) {
                return order;
            }
        }
        order.verify(notifyHelper).notifyApplyChecked(same(fixture.apply()), same(fixture.asset()), eq(label(req)));
        if ("NOTIFY".equals(stop)) {
            return order;
        }
        order.verify(auditRecorder).record(action(req), "APPLY", req.getId(),
                MarketResourceApplyDO.STATUS_PENDING, req.getStatus(), req.getCheckNote(), "OK");
        return order;
    }

    private void verifySuccessOrder(Fixture fixture, MarketCheckReqVO req, Long dept, boolean usagePresent) {
        finish(verifyAfterProcessUntil(fixture, req, dept, usagePresent, "AUDIT"));
    }

    private void finish(InOrder order) {
        order.verifyNoMoreInteractions();
        verifyNoMoreInteractions(applyMapper, assetMapper, objectGuard, processMapper, deliveryService,
                assetMonitorService, notifyHelper, auditRecorder);
    }

    private static void assertCas(LambdaUpdateWrapper<MarketResourceApplyDO> cas, MarketResourceApplyDO observed,
                                  MarketCheckReqVO req, long tenant, long checker) {
        assertNull(cas.getEntity());
        String predicate = cas.getSqlSegment();

        assertFalse(predicate.contains("tenant_id"));
        assertBound(cas, predicate, "id", observed.getId());
        assertBound(cas, predicate, "status", MarketResourceApplyDO.STATUS_PENDING);
        assertBound(cas, predicate, "apply_user_id", observed.getApplyUserId());
        assertBound(cas, predicate, "asset_id", observed.getAssetId());
        if (observed.getRevision() == null) {
            assertTrue(Pattern.compile("(?i)\\brevision\\s+IS\\s+NULL\\b").matcher(predicate).find());
            assertEquals(List.of("apply_user_id", "asset_id", "id", "status"), assignmentColumns(predicate));
        } else {
            assertBound(cas, predicate, "revision", observed.getRevision());
            assertFalse(Pattern.compile("(?i)\\brevision\\s+IS\\s+NULL\\b").matcher(predicate).find());
            assertEquals(List.of("apply_user_id", "asset_id", "id", "revision", "status"), assignmentColumns(predicate));
        }
        assertFalse(Pattern.compile("(?i)\\bOR\\b").matcher(predicate).find());
        String sqlSet = cas.getSqlSet();
        assertEquals(List.of("check_note", "check_time", "check_user_id", "if_auth", "status"), assignmentColumns(sqlSet));
        assertBound(cas, sqlSet, "status", req.getStatus());
        assertBound(cas, sqlSet, "check_user_id", checker);
        assertBound(cas, sqlSet, "check_note", req.getCheckNote());
        assertBound(cas, sqlSet, "if_auth", approved(req) ? MarketResourceApplyDO.AUTH_YES : MarketResourceApplyDO.AUTH_NO);
        assertInstanceOf(LocalDateTime.class, boundValue(cas, sqlSet, "check_time"));
        assertEquals(observed.getRevision() == null ? 9 : 10, cas.getParamNameValuePairs().size());
        if (req.getCheckNote() != null && !req.getCheckNote().isBlank()) {
            assertFalse((predicate + sqlSet).contains(req.getCheckNote()), "审核意见只绑定为值，不拼接 SQL");
        }
    }

    private static List<String> assignmentColumns(String sql) {
        return Pattern.compile("(?i)(?<![\\w.])([a-z_]+)\\s*=").matcher(sql)
                .results().map(result -> result.group(1)).sorted().toList();
    }

    private static Object boundValue(LambdaUpdateWrapper<?> wrapper, String sql, String column) {
        var binding = Pattern.compile("(?i)(?<![\\w.])" + Pattern.quote(column)
                + "\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}").matcher(sql);
        assertTrue(binding.find(), "缺少参数绑定 " + column + ": " + sql);
        String key = binding.group(1);
        assertTrue(wrapper.getParamNameValuePairs().containsKey(key), "NULL 值也必须真实绑定");
        assertFalse(binding.find(), column + " 不应重复设置或匹配");
        return wrapper.getParamNameValuePairs().get(key);
    }

    private static void assertBound(LambdaUpdateWrapper<?> wrapper, String sql, String column, Object expected) {
        assertEquals(expected, boundValue(wrapper, sql, column), column);
    }

    private static void assertProcess(MarketCheckProcessDO process, MarketCheckReqVO req, long checker) {
        assertNull(process.getId());
        assertEquals(req.getId(), process.getResourceApplyId());
        assertEquals(action(req), process.getAction());
        assertEquals(MarketResourceApplyDO.STATUS_PENDING, process.getFromStatus());
        assertEquals(req.getStatus(), process.getToStatus());
        assertEquals(checker, process.getOperatorUserId());
        assertEquals(req.getCheckNote(), process.getNote());
    }

    private static MarketCheckReqVO request(long id, Integer status, String note) {
        MarketCheckReqVO req = new MarketCheckReqVO();
        req.setId(id);
        req.setStatus(status);
        req.setCheckNote(note);
        return req;
    }

    private static Stream<Integer> decisions() {
        return Stream.of(MarketResourceApplyDO.STATUS_APPROVED, MarketResourceApplyDO.STATUS_REJECTED);
    }

    private static boolean approved(MarketCheckReqVO req) {
        return Integer.valueOf(MarketResourceApplyDO.STATUS_APPROVED).equals(req.getStatus());
    }

    private static String action(MarketCheckReqVO req) {
        return approved(req) ? MarketCheckProcessDO.ACTION_APPROVE : MarketCheckProcessDO.ACTION_REJECT;
    }

    private static String label(MarketCheckReqVO req) {
        return approved(req) ? "通过" : "驳回";
    }

    private static Stream<String> failureTypes() {
        return Stream.of("DUPLICATE", "LOCK", "OTHER");
    }

    private static RuntimeException databaseFailure(String type) {
        return switch (type) {
            case "DUPLICATE" -> new DuplicateKeyException("test checking constraint failure");
            case "LOCK" -> new CannotAcquireLockException("test checking lock failure");
            default -> new IllegalStateException("test checking database unavailable");
        };
    }

    private static void assertBusinessFailure(ErrorCode code, org.junit.jupiter.api.function.Executable action) {
        ServiceException failure = assertThrows(ServiceException.class, action);
        assertEquals(code.getCode(), failure.getCode());
    }

    private record Fixture(MarketResourceApplyDO apply, DataAssetDO asset, String original,
                           Map<String, Object> nonReview) {
    }
}
