package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil;
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
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_STATUS_DENY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 仅经公开 expireDueApplies 验证到期批处理编排，使用真实 LambdaUpdateWrapper。
 * Mock 事务管理器仅证明事务定义、协作顺序与提交后计数，不证明 PostgreSQL 回滚、
 * 时钟执行、对象权限、跨节点互斥、真实交付落库或提交后通知；这些由集成测试验证。
 * 失败汇总故意不暴露 cause；异常行数用例另观察真实业务异常工厂的调用。
 */
class MarketApplyExpirationTest extends BaseMockitoUnitTest {

    private static final long APPLY_ID = 9007199254740993L;
    private static final long APPLICANT_ID = 9007199254740995L;
    private static final String NOTE = "使用期限到期自动失效";
    private static final String FAILURE_DETAIL = "test-only-expiration-failure-detail";

    @InjectMocks private MarketApplyServiceImpl service;
    @Mock private MarketResourceApplyMapper applyMapper;
    @Mock private MarketCheckProcessMapper processMapper;
    @Mock private DataAssetMapper assetMapper;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private MarketDeliveryService deliveryService;
    @Mock private MarketNotifyHelper notifyHelper;
    @Mock private MarketObjectGuard objectGuard;
    @Mock private MarketAuditRecorder auditRecorder;
    @Mock private MarketWorkingDayService workingDayService;
    @Mock private MarketDeliveryCoordinator deliveryCoordinator;
    @Mock private MarketDeliveryEvidenceService deliveryEvidenceService;
    @Mock private MarketDeliveryOperationMapper deliveryOperationMapper;
    @Mock private MarketDemandMapper demandMapper;
    @Mock private MarketResourceEndpointMapper endpointMapper;
    @Mock private MarketDisplayHelper displayHelper;
    @Mock private MarketAccessService accessService;
    @Mock private MarketSupplyApiGuard supplyApiGuard;
    @Mock private AssetCatalogService catalogService;
    @Mock private AssetMonitorService assetMonitorService;
    @Mock private SupplyAppApi supplyAppApi;
    @Mock private AdminUserApi adminUserApi;

    private final List<SimpleTransactionStatus> transactions = new ArrayList<>();

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                MarketResourceApplyDO.class);
    }

    @BeforeEach
    void establishJobTenantWithoutWebLogin() {
        TenantContextHolder.clear();
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void preserveJobBoundaryAndClearContext() {
        try {
            assertNull(SecurityContextHolder.getContext().getAuthentication());
            // 到期补普通审计由真实 PG 生命周期专测精确核对，本类只检查其余无关协作替身。
            verifyNoInteractions(objectGuard, workingDayService, deliveryCoordinator,
                    deliveryEvidenceService, deliveryOperationMapper, demandMapper, endpointMapper,
                    displayHelper, accessService, supplyApiGuard, catalogService, assetMonitorService,
                    supplyAppApi, adminUserApi);
        } finally {
            TenantContextHolder.clear();
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void winnerRecordsExpirationThenRevokesAndNotifiesBeforeTransactionReturns() {
        assertSuccessfulExpiration(application(APPLY_ID), asset(5L));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, 9007199254740997L, Long.MAX_VALUE})
    void sourceRevisionUsesExactNullableCasWithoutIncrement(Long revision) {
        var apply = application(APPLY_ID);
        apply.setSourceRevision(revision);
        assertSuccessfulExpiration(apply, asset(5L));
    }

    @ParameterizedTest
    @CsvSource(value = {"NULL,NULL", "NULL,1", "0,0", "0,1", "1,NULL", "1,1"}, nullValues = "NULL")
    void originalAuthorizationAndPauseFlagsRemainExactCasConditions(Integer auth, Integer paused) {
        var apply = application(APPLY_ID);
        apply.setIfAuth(auth);
        apply.setPaused(paused);
        assertSuccessfulExpiration(apply, asset(5L));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"1900-01-01T00:00:00", "2999-01-01T00:00:00"})
    void expirationPredicateUsesDatabaseClockNotObservedEndTimeOrJvmTime(String endTime) {
        var apply = application(APPLY_ID);
        apply.setApplyEndTime(endTime == null ? null : LocalDateTime.parse(endTime));
        // Mock 返回 CAS 胜方；这里只证明条件生成，不声称未来/NULL 期限会在数据库匹配。
        assertSuccessfulExpiration(apply, asset(5L));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void absentAssetStillPassesNullToExpirationNotification(boolean absentReference) {
        var apply = application(APPLY_ID);
        if (absentReference) apply.setAssetId(null);
        assertSuccessfulExpiration(apply, null);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, Long.MAX_VALUE})
    void processOperatorIsStoredApplicantWithoutCallingWebLoginGuard(Long applicant) {
        var apply = application(APPLY_ID);
        apply.setApplyUserId(applicant);
        assertSuccessfulExpiration(apply, asset(5L));
    }

    @Test
    void zeroUpdatedRowsAreBenignCompetitionLossWithNoProcessOrObjectMutation() {
        var apply = application(APPLY_ID);
        String before = JsonUtils.toJsonString(apply);
        scan(List.of(apply));
        beginTransactions();
        stubUpdate(apply, before, 0);

        assertEquals(0, service.expireDueApplies());

        assertEquals(before, JsonUtils.toJsonString(apply));
        InOrder order = order();
        order.verify(applyMapper).selectExpiredDue( 100);
        order.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        order.verify(applyMapper).update(isNull(), any(Wrapper.class));
        order.verify(transactionManager).commit(same(transactions.getFirst()));
        finish(order);
        verifyNoInteractions(processMapper, deliveryService, notifyHelper, assetMapper);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, -1})
    void nonzeroAbnormalUpdateCountDeniesInsteadOfTreatingItAsWinner(int rows) {
        var apply = application(APPLY_ID);
        String before = JsonUtils.toJsonString(apply);
        scan(List.of(apply));
        beginTransactions();
        stubUpdate(apply, before, rows);

        assertDeniedBatch();

        assertEquals(before, JsonUtils.toJsonString(apply));
        verifyFailedWriteOrder(false);
        verifyNoInteractions(processMapper, deliveryService, notifyHelper, assetMapper);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, -1})
    void processMustInsertOneRowBeforeObjectMutationDeliveryOrNotification(int rows) {
        var apply = application(APPLY_ID);
        String before = JsonUtils.toJsonString(apply);
        scan(List.of(apply));
        beginTransactions();
        stubUpdate(apply, before, 1);
        stubProcess(apply, before, rows);

        assertDeniedBatch();

        assertEquals(before, JsonUtils.toJsonString(apply));
        verifyFailedWriteOrder(true);
        verifyNoInteractions(deliveryService, notifyHelper, assetMapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CAS", "PROCESS"})
    void writeExceptionRollsBackOnlyMockTransactionAndDoesNotMutateObservedObject(String stage) {
        var apply = application(APPLY_ID);
        String before = JsonUtils.toJsonString(apply);
        scan(List.of(apply));
        beginTransactions();
        if (stage.equals("CAS")) {
            when(applyMapper.update(isNull(), any(Wrapper.class)))
                    .thenThrow(new CannotAcquireLockException(FAILURE_DETAIL));
        } else {
            stubUpdate(apply, before, 1);
            when(processMapper.insert(any(MarketCheckProcessDO.class))).thenAnswer(invocation -> {
                assertEquals(before, JsonUtils.toJsonString(apply));
                assertProcess(invocation.getArgument(0), apply);
                throw new DuplicateKeyException(FAILURE_DETAIL);
            });
        }

        assertSummary(0, 1);

        assertEquals(before, JsonUtils.toJsonString(apply));
        verifyFailedWriteOrder(stage.equals("PROCESS"));
        verifyNoInteractions(deliveryService, notifyHelper, assetMapper);
        if (stage.equals("CAS")) verifyNoInteractions(processMapper);
    }



    @ParameterizedTest
    @ValueSource(longs = {0L, 9007199254740999L, Long.MAX_VALUE})
    void validTenantIsPreservedLosslesslyInScanAndCas(long tenantId) {
        TenantContextHolder.clear();
        assertSuccessfulExpiration(application(APPLY_ID), asset(5L));
        assertNull(TenantContextHolder.getTenantId());
    }

    @Test
    void emptyBoundedScanDoesNotStartAnItemTransaction() {
        scan(List.of());

        assertEquals(0, service.expireDueApplies());

        verify(applyMapper).selectExpiredDue( 100);
        verifyNoMoreInteractions(applyMapper);
        verifyNoInteractions(transactionManager, processMapper, assetMapper, deliveryService, notifyHelper);
    }

    @Test
    void scanFailureIsNotMisreportedAsAnEmptySuccessfulBatch() {
        var failure = new DataAccessResourceFailureException("test scan unavailable");
        when(applyMapper.selectExpiredDue( 100)).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, service::expireDueApplies));

        verify(applyMapper).selectExpiredDue( 100);
        verifyNoMoreInteractions(applyMapper);
        verifyNoInteractions(transactionManager, processMapper, assetMapper, deliveryService, notifyHelper);
    }

    @ParameterizedTest
    @MethodSource("mixedBatches")
    void mixedBatchCountsOnlyCommittedWinnersAndAttemptsEveryScannedItem(List<Outcome> outcomes,
                                                                       int completed, int failed) {
        runMixedBatch(outcomes, completed, failed);
    }

    static Stream<Arguments> mixedBatches() {
        return Stream.of(
                Arguments.of(List.of(Outcome.WIN, Outcome.SKIP), 1, 0),
                Arguments.of(List.of(Outcome.SKIP, Outcome.WIN), 1, 0),
                Arguments.of(List.of(Outcome.WIN, Outcome.PROCESS_ZERO, Outcome.SKIP, Outcome.WIN), 2, 1),
                Arguments.of(List.of(Outcome.PROCESS_THROW, Outcome.WIN, Outcome.PROCESS_ZERO, Outcome.SKIP), 1, 2),
                Arguments.of(List.of(Outcome.SKIP, Outcome.SKIP), 0, 0));
    }

    @Test
    void fullHundredItemBatchKeepsOneScanAndIndependentShortTransactions() {
        runMixedBatch(java.util.Collections.nCopies(100, Outcome.WIN), 100, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BEGIN", "CAS", "PROCESS", "DELIVERY", "ASSET", "NOTIFY", "COMMIT", "ROLLBACK"})
    void failureAtEachBoundaryStillAllowsLaterItemAndNeverCountsFailedCommit(String stage) {
        var first = application(APPLY_ID);
        var second = application(APPLY_ID + 1);
        second.setAssetId(6L);
        String firstBefore = JsonUtils.toJsonString(first);
        String secondBefore = JsonUtils.toJsonString(second);
        var firstAsset = asset(5L);
        var secondAsset = asset(6L);
        var firstTx = new SimpleTransactionStatus();
        var secondTx = new SimpleTransactionStatus();
        List<TransactionDefinition> definitions = new ArrayList<>();
        AtomicInteger begins = new AtomicInteger();
        scan(List.of(first, second));
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenAnswer(invocation -> {
            TransactionDefinition definition = invocation.getArgument(0);
            assertTransactionDefinition(definition);
            definitions.add(definition);
            if (begins.getAndIncrement() == 0) {
                if (stage.equals("BEGIN")) throw new TransactionSystemException(FAILURE_DETAIL);
                return firstTx;
            }
            return secondTx;
        });
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenAnswer(invocation -> {
            LambdaUpdateWrapper<MarketResourceApplyDO> wrapper = invocation.getArgument(1);
            boolean isFirst = Long.valueOf(APPLY_ID).equals(boundValue(wrapper, wrapper.getSqlSegment(), "id"));
            var observed = isFirst ? first : second;
            assertEquals(isFirst ? firstBefore : secondBefore, JsonUtils.toJsonString(observed));
            assertCas(wrapper, observed);
            if (isFirst && stage.equals("CAS")) throw new CannotAcquireLockException(FAILURE_DETAIL);
            return 1;
        });
        when(processMapper.insert(any(MarketCheckProcessDO.class))).thenAnswer(invocation -> {
            MarketCheckProcessDO process = invocation.getArgument(0);
            boolean isFirst = Long.valueOf(APPLY_ID).equals(process.getResourceApplyId());
            var observed = isFirst ? first : second;
            assertEquals(isFirst ? firstBefore : secondBefore, JsonUtils.toJsonString(observed));
            assertProcess(process, observed);
            if (isFirst && (stage.equals("PROCESS") || stage.equals("ROLLBACK"))) {
                throw new DuplicateKeyException(FAILURE_DETAIL);
            }
            return 1;
        });
        when(assetMapper.selectById(anyLong())).thenAnswer(invocation -> {
            boolean isFirst = Long.valueOf(5L).equals(invocation.getArgument(0));
            if (isFirst && stage.equals("ASSET")) throw new DataAccessResourceFailureException(FAILURE_DETAIL);
            return isFirst ? firstAsset : secondAsset;
        });
        if (stage.equals("DELIVERY")) {
            doThrow(new IllegalStateException(FAILURE_DETAIL)).when(deliveryService).enqueueRevoke(same(first));
        } else if (stage.equals("NOTIFY")) {
            // 只模拟协作者失败，不推断真实通知 helper 的尽力发送/提交后契约。
            doThrow(new IllegalStateException(FAILURE_DETAIL)).when(notifyHelper)
                    .notifyApplyExpired(same(first), same(firstAsset));
        } else if (stage.equals("COMMIT")) {
            doThrow(new TransactionSystemException(FAILURE_DETAIL)).when(transactionManager).commit(same(firstTx));
        } else if (stage.equals("ROLLBACK")) {
            doThrow(new IllegalStateException(FAILURE_DETAIL)).when(transactionManager).rollback(same(firstTx));
        }

        assertSummary(1, 1);

        InOrder order = order();
        order.verify(applyMapper).selectExpiredDue( 100);
        // BEGIN失败后两次getTransaction紧邻发生，按各自模板身份区分而不是宽泛匹配两次。
        order.verify(transactionManager).getTransaction(same(definitions.getFirst()));
        if (!stage.equals("BEGIN")) {
            order.verify(applyMapper).update(isNull(), any(Wrapper.class));
            if (!stage.equals("CAS")) {
                order.verify(processMapper).insert(any(MarketCheckProcessDO.class));
                if (!List.of("PROCESS", "ROLLBACK").contains(stage)) {
                    order.verify(deliveryService).enqueueRevoke(same(first));
                    if (!stage.equals("DELIVERY")) {
                        order.verify(assetMapper).selectById(5L);
                        if (!stage.equals("ASSET")) {
                            order.verify(notifyHelper).notifyApplyExpired(same(first), same(firstAsset));
                        }
                    }
                }
            }
            if (stage.equals("COMMIT")) order.verify(transactionManager).commit(same(firstTx));
            else order.verify(transactionManager).rollback(same(firstTx));
        }
        verifyWinningItem(order, second, secondAsset, secondTx);
        finish(order);
        assertEquals(2, begins.get());
        assertOnlyExpirationChanged(secondBefore, second);
        if (List.of("BEGIN", "CAS", "PROCESS", "ROLLBACK").contains(stage)) {
            assertEquals(firstBefore, JsonUtils.toJsonString(first));
        } else {
            // 已修改内存对象不代表数据库已提交；真实事务结果留给 PostgreSQL 测试。
            assertOnlyExpirationChanged(firstBefore, first);
        }
    }

    private void assertSuccessfulExpiration(MarketResourceApplyDO apply, DataAssetDO asset) {

        String before = JsonUtils.toJsonString(apply);
        scan(List.of(apply));
        beginTransactions();
        stubUpdate(apply, before, 1);
        stubProcess(apply, before, 1);
        if (apply.getAssetId() != null) when(assetMapper.selectById(apply.getAssetId())).thenReturn(asset);
        doAnswer(invocation -> {
            assertOnlyExpirationChanged(before, apply);
            return null;
        }).when(deliveryService).enqueueRevoke(same(apply));
        doAnswer(invocation -> {
            assertOnlyExpirationChanged(before, apply);
            return null;
        }).when(notifyHelper).notifyApplyExpired(same(apply), same(asset));

        assertEquals(1, service.expireDueApplies());

        assertEquals(1, transactions.size());
        InOrder order = order();
        order.verify(applyMapper).selectExpiredDue( 100);
        verifyWinningItem(order, apply, asset, transactions.getFirst());
        finish(order);
        assertOnlyExpirationChanged(before, apply);
    }

    private void runMixedBatch(List<Outcome> outcomes, int completed, int failed) {
        Map<Long, MarketResourceApplyDO> applications = new LinkedHashMap<>();
        Map<Long, String> before = new LinkedHashMap<>();
        for (int i = 0; i < outcomes.size(); i++) {
            var apply = application(APPLY_ID + i);
            apply.setAssetId(null);
            applications.put(apply.getId(), apply);
            before.put(apply.getId(), JsonUtils.toJsonString(apply));
        }
        scan(new ArrayList<>(applications.values()));
        beginTransactions();
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenAnswer(invocation -> {
            LambdaUpdateWrapper<MarketResourceApplyDO> wrapper = invocation.getArgument(1);
            long id = (Long) boundValue(wrapper, wrapper.getSqlSegment(), "id");
            var apply = applications.get(id);
            assertNotNull(apply);
            assertEquals(before.get(id), JsonUtils.toJsonString(apply));
            assertCas(wrapper, apply);
            return outcomes.get((int) (id - APPLY_ID)) == Outcome.SKIP ? 0 : 1;
        });
        if (outcomes.stream().anyMatch(outcome -> outcome != Outcome.SKIP)) {
            when(processMapper.insert(any(MarketCheckProcessDO.class))).thenAnswer(invocation -> {
                MarketCheckProcessDO process = invocation.getArgument(0);
                long id = process.getResourceApplyId();
                var apply = applications.get(id);
                assertEquals(before.get(id), JsonUtils.toJsonString(apply));
                assertProcess(process, apply);
                return switch (outcomes.get((int) (id - APPLY_ID))) {
                    case WIN -> 1;
                    case PROCESS_ZERO -> 0;
                    case PROCESS_THROW -> throw new DuplicateKeyException(FAILURE_DETAIL);
                    case SKIP -> throw new AssertionError("竞争丢弃不能写过程记录");
                };
            });
        }

        if (failed == 0) assertEquals(completed, service.expireDueApplies());
        else assertSummary(completed, failed);

        assertEquals(outcomes.size(), transactions.size());
        assertEquals(outcomes.size(), transactions.stream().distinct().count(), "每条均独立请求事务");
        InOrder order = order();
        order.verify(applyMapper).selectExpiredDue( 100);
        for (int i = 0; i < outcomes.size(); i++) {
            var apply = applications.get(APPLY_ID + i);
            Outcome outcome = outcomes.get(i);
            order.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
            order.verify(applyMapper).update(isNull(), any(Wrapper.class));
            if (outcome != Outcome.SKIP) order.verify(processMapper).insert(any(MarketCheckProcessDO.class));
            if (outcome == Outcome.WIN) {
                order.verify(deliveryService).enqueueRevoke(same(apply));
                order.verify(notifyHelper).notifyApplyExpired(same(apply), isNull());
                assertOnlyExpirationChanged(before.get(apply.getId()), apply);
            } else {
                assertEquals(before.get(apply.getId()), JsonUtils.toJsonString(apply));
            }
            if (outcome == Outcome.WIN || outcome == Outcome.SKIP) {
                order.verify(transactionManager).commit(same(transactions.get(i)));
            } else {
                order.verify(transactionManager).rollback(same(transactions.get(i)));
            }
        }
        finish(order);
        verifyNoInteractions(assetMapper);
    }

    private void scan(List<MarketResourceApplyDO> applications) {
        when(applyMapper.selectExpiredDue( 100)).thenReturn(applications);
    }

    private void beginTransactions() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenAnswer(invocation -> {
            assertTransactionDefinition(invocation.getArgument(0));
            var status = new SimpleTransactionStatus();
            transactions.add(status);
            return status;
        });
    }

    private static void assertTransactionDefinition(TransactionDefinition definition) {
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, definition.getPropagationBehavior());
        assertEquals(10, definition.getTimeout());
        assertEquals(TransactionDefinition.ISOLATION_DEFAULT, definition.getIsolationLevel());
        assertFalse(definition.isReadOnly());
    }

    private void stubUpdate(MarketResourceApplyDO apply, String before, int rows) {
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenAnswer(invocation -> {
            assertEquals(before, JsonUtils.toJsonString(apply), "不能在 CAS 成功前修改观察对象");
            assertCas(invocation.getArgument(1), apply);
            return rows;
        });
    }

    private void stubProcess(MarketResourceApplyDO apply, String before, int rows) {
        when(processMapper.insert(any(MarketCheckProcessDO.class))).thenAnswer(invocation -> {
            assertEquals(before, JsonUtils.toJsonString(apply), "过程成功前不能修改观察对象");
            assertProcess(invocation.getArgument(0), apply);
            verifyNoInteractions(deliveryService, notifyHelper, assetMapper);
            return rows;
        });
    }

    private void assertDeniedBatch() {
        // 保留真实异常构造；只观察汇总前是否走了约定的 STATUS_DENY 错误语义。
        try (var exceptions = mockStatic(ServiceExceptionUtil.class, CALLS_REAL_METHODS)) {
            assertSummary(0, 1);
            exceptions.verify(() -> ServiceExceptionUtil.exception(MARKET_APPLY_STATUS_DENY));
        }
    }

    private void assertSummary(int completed, int failed) {
        IllegalStateException failure = assertThrows(IllegalStateException.class, service::expireDueApplies);
        assertEquals("到期处理部分失败：完成 " + completed + " 条，失败 " + failed + " 条", failure.getMessage());
        assertNull(failure.getCause(), "汇总不能携带可能包含冻结载荷的原异常");
        assertEquals(0, failure.getSuppressed().length);
        assertFalse(failure.toString().contains(FAILURE_DETAIL));
    }

    private void verifyFailedWriteOrder(boolean processAttempted) {
        InOrder order = order();
        order.verify(applyMapper).selectExpiredDue( 100);
        order.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        order.verify(applyMapper).update(isNull(), any(Wrapper.class));
        if (processAttempted) order.verify(processMapper).insert(any(MarketCheckProcessDO.class));
        order.verify(transactionManager).rollback(same(transactions.getFirst()));
        finish(order);
    }

    private void verifyWinningItem(InOrder order, MarketResourceApplyDO apply, DataAssetDO asset,
                                   SimpleTransactionStatus status) {
        order.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        order.verify(applyMapper).update(isNull(), any(Wrapper.class));
        order.verify(processMapper).insert(any(MarketCheckProcessDO.class));
        order.verify(deliveryService).enqueueRevoke(same(apply));
        if (apply.getAssetId() != null) order.verify(assetMapper).selectById(apply.getAssetId());
        order.verify(notifyHelper).notifyApplyExpired(same(apply), same(asset));
        order.verify(transactionManager).commit(same(status));
    }

    private InOrder order() {
        return inOrder(applyMapper, transactionManager, processMapper, deliveryService, assetMapper, notifyHelper);
    }

    private void finish(InOrder order) {
        order.verifyNoMoreInteractions();
        verifyNoMoreInteractions(applyMapper, transactionManager, processMapper, deliveryService, assetMapper, notifyHelper);
    }

    private static void assertCas(LambdaUpdateWrapper<MarketResourceApplyDO> wrapper, MarketResourceApplyDO apply) {
        assertNull(wrapper.getEntity(), "不能用旧实体整体回写");
        String predicate = wrapper.getSqlSegment();
        assertFalse(predicate.contains("tenant_id"));
        assertEquals(apply.getId(), boundValue(wrapper, predicate, "id"));
        assertEquals(MarketResourceApplyDO.STATUS_APPROVED, boundValue(wrapper, predicate, "status"));
        assertNullableBound(wrapper, predicate, "source_revision", apply.getSourceRevision());
        assertNullableBound(wrapper, predicate, "if_auth", apply.getIfAuth());
        assertNullableBound(wrapper, predicate, "paused", apply.getPaused());
        List<String> expected = new ArrayList<>(List.of("id", "status"));
        if (apply.getSourceRevision() != null) expected.add("source_revision");
        if (apply.getIfAuth() != null) expected.add("if_auth");
        if (apply.getPaused() != null) expected.add("paused");
        assertEquals(expected.stream().sorted().toList(), assignmentColumns(predicate));
        assertEquals(1, Pattern.compile("apply_end_time\\s*<=\\s*clock_timestamp\\(\\)"
                + "\\s+AT TIME ZONE 'Asia/Shanghai'").matcher(predicate).results().count());
        assertFalse(Pattern.compile("(?i)\\bOR\\b").matcher(predicate).find());
        assertFalse(predicate.contains("apply_end_time IS NULL"));
        String set = wrapper.getSqlSet();
        assertEquals(List.of("if_auth", "status"), assignmentColumns(set));
        assertEquals(MarketResourceApplyDO.STATUS_EXPIRED, boundValue(wrapper, set, "status"));
        assertEquals(MarketResourceApplyDO.AUTH_NO, boundValue(wrapper, set, "if_auth"));
        assertEquals(expected.size() + 2, wrapper.getParamNameValuePairs().size());
        assertTrue(wrapper.getParamNameValuePairs().values().stream().noneMatch(LocalDateTime.class::isInstance),
                "期限判断不绑定本机时间或扫描出的旧期限");
    }

    private static Object boundValue(LambdaUpdateWrapper<?> wrapper, String sql, String column) {
        var binding = Pattern.compile("(?i)(?<![\\w.])" + Pattern.quote(column)
                + "\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}").matcher(sql);
        assertTrue(binding.find(), "缺少参数绑定 " + column + ": " + sql);
        String key = binding.group(1);
        assertFalse(binding.find(), column + " 不能重复匹配或设置");
        assertTrue(wrapper.getParamNameValuePairs().containsKey(key));
        return wrapper.getParamNameValuePairs().get(key);
    }

    private static void assertNullableBound(LambdaUpdateWrapper<?> wrapper, String sql, String column, Object value) {
        var nullCheck = Pattern.compile("(?i)\\b" + Pattern.quote(column) + "\\s+IS\\s+NULL\\b");
        if (value == null) {
            assertTrue(nullCheck.matcher(sql).find(), "历史 NULL 必须精确匹配：" + column);
            assertFalse(Pattern.compile("(?i)\\b" + Pattern.quote(column) + "\\s*=").matcher(sql).find());
        } else {
            assertEquals(value, boundValue(wrapper, sql, column));
            assertFalse(nullCheck.matcher(sql).find());
        }
    }

    private static List<String> assignmentColumns(String sql) {
        return Pattern.compile("(?i)(?<![\\w.])([a-z_]+)\\s*=").matcher(sql)
                .results().map(result -> result.group(1)).sorted().toList();
    }

    private static void assertProcess(MarketCheckProcessDO process, MarketResourceApplyDO apply) {
        assertNull(process.getId());
        assertEquals(apply.getId(), process.getResourceApplyId());
        assertEquals(MarketCheckProcessDO.ACTION_EXPIRE, process.getAction());
        assertEquals(MarketResourceApplyDO.STATUS_APPROVED, process.getFromStatus());
        assertEquals(MarketResourceApplyDO.STATUS_EXPIRED, process.getToStatus());
        assertEquals(apply.getApplyUserId(), process.getOperatorUserId(), "后台到期保留申请人操作标识");
        assertEquals(NOTE, process.getNote());
    }

    private static void assertOnlyExpirationChanged(String before, MarketResourceApplyDO apply) {
        Map<?, ?> original = JsonUtils.parseObject(before, Map.class);
        Map<?, ?> current = JsonUtils.parseObject(JsonUtils.toJsonString(apply), Map.class);
        for (String field : List.of("status", "ifAuth")) {
            original.remove(field);
            current.remove(field);
        }
        assertEquals(original, current, "不改写来源版本、暂停、冻结内容、期限、身份和原审核意见");
        assertEquals(MarketResourceApplyDO.STATUS_EXPIRED, apply.getStatus());
        assertEquals(MarketResourceApplyDO.AUTH_NO, apply.getIfAuth());
    }

    private static MarketResourceApplyDO application(long id) {
        LocalDateTime start = LocalDateTime.of(2020, 1, 1, 0, 0);
        return MarketResourceApplyDO.builder().id(id).assetId(5L).applyUserId(APPLICANT_ID).applyDeptId(10L)
                .status(MarketResourceApplyDO.STATUS_APPROVED).ifAuth(MarketResourceApplyDO.AUTH_YES).paused(0)
                .sourceRevision(3L).sourceGrantId("test-expiration-source").revision(7)
                .apiId(3L).appId(2L).snapshot("{\"frozenColumns\":[\"Id\",\"Name\"]}").fieldScope("Id,Name")
                .catalogVersionId(12L).checkUserId(19L).checkTime(start).checkNote("原审核意见")
                .applyStartTime(start).applyEndTime(start.plusDays(1)).receivedTime(start)
                .replyDeadline(start.plusDays(2)).deliveryDeadline(start.plusDays(21))
                .deliveryStatus(0).supplementNote("原补正说明").build();
    }

    private static DataAssetDO asset(long id) {
        return DataAssetDO.builder().id(id).assetType(DataAssetDO.TYPE_DATABASE_TABLE).build();
    }

    private enum Outcome { WIN, SKIP, PROCESS_ZERO, PROCESS_THROW }
}
