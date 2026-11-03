package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketRenewalReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketCheckProcessDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketCheckProcessMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_RENEWAL_CONFLICT;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * createRenewal 的写入完整性与协作顺序契约。
 * 直接调用 Service，事务管理器仅为 Mock：注解检查不证明 Spring 代理生效，
 * rollback/commit 的调用顺序不证明真实保存点、数据库回滚或提交可见性。
 * PostgreSQL 唯一约束、生成主键和事务行为由独立真实数据库测试验证。
 */
class MarketRenewalIntegrityTest extends BaseMockitoUnitTest {

    private static final long TENANT = 1L;
    private static final long PARENT = 7L;
    private static final long USER = 9L;
    private static final long ASSET = 5L;
    private static final long GENERATED = 9007199254740993L;
    private static final String OPERATION = "renew-integrity";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime ORIGINAL_END = LocalDateTime.of(2026, 12, 31, 0, 0);
    private static final LocalDateTime REQUESTED_END =
            LocalDateTime.of(2027, 6, 30, 0, 0).withNano(123456789);
    private static final LocalDateTime STORED_END = REQUESTED_END.truncatedTo(ChronoUnit.MICROS);

    @InjectMocks
    private MarketApplyServiceImpl service;
    @Mock
    private MarketResourceApplyMapper applyMapper;
    @Mock
    private MarketCheckProcessMapper processMapper;
    @Mock
    private MarketObjectGuard objectGuard;
    @Mock
    private MarketDisplayHelper displayHelper;
    @Mock
    private MarketAuditRecorder auditRecorder;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private MarketDeliveryService deliveryService;
    @Mock
    private MarketNotifyHelper notifyHelper;
    @Mock
    private AssetCatalogService catalogService;
    @Mock
    private MarketSupplyApiGuard supplyApiGuard;
    @Mock
    private MarketWorkingDayService workingDayService;
    @Mock
    private DataAssetMapper assetMapper;

    private MarketResourceApplyDO original;
    private SimpleTransactionStatus insertionStatus;

    @BeforeEach
    void establishFixture() {
        TenantContextHolder.clear();
        TenantContextHolder.clear();
        original = approved();
        insertionStatus = new SimpleTransactionStatus();
    }

    @AfterEach
    void clearContextAndCheckUnrelatedWrites() {
        try {
            verify(applyMapper, never()).insert(any(MarketResourceApplyDO.class));
            verify(applyMapper, never()).updateById(any(MarketResourceApplyDO.class));
            verifyNoInteractions(deliveryService, notifyHelper, catalogService, supplyApiGuard);
            if (!mockingDetails(workingDayService).getInvocations().isEmpty()) {
                verify(workingDayService).plusWorkingDays(NOW, 1);
                verify(workingDayService).plusWorkingDays(NOW, 21);
                verifyNoMoreInteractions(workingDayService);
                verify(assetMapper).selectById(ASSET);
                verifyNoMoreInteractions(assetMapper);
            } else {
                verifyNoInteractions(assetMapper, workingDayService);
            }
        } finally {
            TenantContextHolder.clear();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, -1})
    void unexpectedApplicationRowsRejectBeforeProcessAndAudit(int rows) {
        prepareInsertion();
        stubInsert(rows, GENERATED);

        assertConflict();

        verify(applyMapper).insertRenewal(any(MarketResourceApplyDO.class));
        verify(transactionManager).rollback(insertionStatus);
        verify(transactionManager, never()).commit(any());
        verify(applyMapper, times(2)).selectRenewalByOperation( OPERATION);
        verifyNoInteractions(processMapper, auditRecorder);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L})
    void missingOrNonpositiveGeneratedIdRejectsEvenWhenOneRowWasReported(Long generatedId) {
        prepareInsertion();
        stubInsert(1, generatedId);

        assertConflict();

        verify(applyMapper).insertRenewal(any(MarketResourceApplyDO.class));
        verify(transactionManager).rollback(insertionStatus);
        verify(transactionManager, never()).commit(any());
        verify(applyMapper, times(2)).selectRenewalByOperation( OPERATION);
        verifyNoInteractions(processMapper, auditRecorder);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, -1})
    void unexpectedProcessRowsRejectWithoutAuditOrAutomaticRetry(int rows) {
        prepareInsertion();
        stubInsert(1, GENERATED);
        when(processMapper.insert(any(MarketCheckProcessDO.class))).thenReturn(rows);

        assertConflict();

        verify(applyMapper).insertRenewal(any(MarketResourceApplyDO.class));
        verify(processMapper).insert(any(MarketCheckProcessDO.class));
        verify(transactionManager).commit(insertionStatus);
        verify(applyMapper, times(2)).selectRenewalByOperation( OPERATION);
        verifyNoInteractions(auditRecorder);
    }

    @Test
    void positiveLongMaximumIdIsReturnedAndUsedUnchangedByProcessAndAudit() {
        prepareInsertion();
        stubInsert(1, Long.MAX_VALUE);
        successfulProcess();

        assertEquals(Long.MAX_VALUE, service.createRenewal(request()));

        var captor = ArgumentCaptor.forClass(MarketCheckProcessDO.class);
        verify(processMapper).insert(captor.capture());
        MarketCheckProcessDO process = captor.getValue();
        assertAll(
                () -> assertEquals(Long.MAX_VALUE, process.getResourceApplyId()),
                () -> assertEquals(MarketCheckProcessDO.ACTION_RENEW, process.getAction()),
                () -> assertNull(process.getFromStatus()),
                () -> assertEquals(MarketResourceApplyDO.STATUS_PENDING, process.getToStatus()),
                () -> assertEquals(USER, process.getOperatorUserId()),
                () -> assertEquals("续期申请（原申请 7）", process.getNote()));
        verifySuccessfulAudit(Long.MAX_VALUE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSERT", "PROCESS"})
    void writeFailureIsPropagatedUnchangedWithoutAuditOrRetry(String stage) {
        prepareInsertion();
        IllegalStateException failure = new IllegalStateException("fixture write failure");
        if ("INSERT".equals(stage)) {
            doThrow(failure).when(applyMapper).insertRenewal(any(MarketResourceApplyDO.class));
        } else {
            stubInsert(1, GENERATED);
            when(processMapper.insert(any(MarketCheckProcessDO.class))).thenThrow(failure);
        }

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.createRenewal(request())));

        verify(applyMapper).insertRenewal(any(MarketResourceApplyDO.class));
        verify(applyMapper, times(2)).selectRenewalByOperation( OPERATION);
        if ("INSERT".equals(stage)) {
            verify(transactionManager).rollback(insertionStatus);
            verify(transactionManager, never()).commit(any());
            verifyNoInteractions(processMapper);
        } else {
            verify(transactionManager).commit(insertionStatus);
            verify(processMapper).insert(any(MarketCheckProcessDO.class));
        }
        verifyNoInteractions(auditRecorder);
    }

    @Test
    void nestedCompletionFailureDoesNotProceedToProcessOrAudit() {
        prepareInsertion();
        stubInsert(1, GENERATED);
        IllegalStateException failure = new IllegalStateException("fixture completion failure");
        doThrow(failure).when(transactionManager).commit(insertionStatus);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.createRenewal(request())));

        verify(applyMapper).insertRenewal(any(MarketResourceApplyDO.class));
        verify(transactionManager).commit(insertionStatus);
        verify(applyMapper, times(2)).selectRenewalByOperation( OPERATION);
        verifyNoInteractions(processMapper, auditRecorder);
    }

    @Test
    void copyIsCleanedAndNormalizedBeforeInsertionWhileOriginalAndRequestStayUnchanged() {
        prepareInsertion();
        MarketResourceApplyDO before = BeanUtils.toBean(original, MarketResourceApplyDO.class);
        MarketRenewalReqVO request = request();
        doAnswer(call -> {
            MarketResourceApplyDO created = call.getArgument(0);
            assertNotSame(original, created);
            assertAll(
                    () -> assertNull(created.getId()),
                    () -> assertNull(created.getCreator()),
                    () -> assertNull(created.getUpdater()),
                    () -> assertNull(created.getCreateTime()),
                    () -> assertNull(created.getUpdateTime()),
                    () -> assertNull(created.getDeleted()),
                    () -> assertNull(created.getCheckUserId()),
                    () -> assertNull(created.getCheckTime()),
                    () -> assertNull(created.getCheckNote()),
                    () -> assertNull(created.getFirstUsedTime()),
                    () -> assertNull(created.getBpmInstanceId()),
                    () -> assertNull(created.getSourceGrantId()),
                    () -> assertNull(created.getSourceRevision()),
                    () -> assertEquals("APP-88", created.getApplyNo()),
                    () -> assertEquals(PARENT, created.getRenewalOfApplyId()),
                    () -> assertEquals(OPERATION, created.getRenewalOperationId()),
                    () -> assertEquals(STORED_END, created.getApplyEndTime()),
                    () -> assertEquals(STORED_END, created.getRenewalRequestedEndTime()),
                    () -> assertEquals(MarketResourceApplyDO.STATUS_PENDING, created.getStatus()),
                    () -> assertEquals(MarketResourceApplyDO.AUTH_NO, created.getIfAuth()),
                    () -> assertEquals(0, created.getPaused()),
                    () -> assertEquals(1, created.getRevision()),
                    () -> assertEquals(MarketDeliveryService.DELIVERY_NONE, created.getDeliveryStatus()));
            assertEquals(original.getSnapshot(), created.getSnapshot());
            // 对全部其他持久字段比较，避免只覆盖 id/期限而遗漏复制证据。
            MarketResourceApplyDO expected = BeanUtils.toBean(original, MarketResourceApplyDO.class);
            expected.setId(null);
            expected.clean();
            expected.setDeleted(null);
            expected.setApplyNo("APP-88");
            expected.setRenewalOfApplyId(PARENT);
            expected.setRenewalOperationId(OPERATION);
            expected.setApplyEndTime(STORED_END);
            expected.setRenewalRequestedEndTime(STORED_END);
            expected.setStatus(MarketResourceApplyDO.STATUS_PENDING);
            expected.setIfAuth(MarketResourceApplyDO.AUTH_NO);
            expected.setPaused(0);
            expected.setRevision(1);
            expected.setSourceRevision(null);
            expected.setSourceGrantId(null);
            expected.setDeliveryStatus(MarketDeliveryService.DELIVERY_NONE);
            expected.setCheckUserId(null);
            expected.setCheckTime(null);
            expected.setCheckNote(null);
            expected.setFirstUsedTime(null);
            expected.setBpmInstanceId(null);
            expected.setSupplementNote(null);
            expected.setReceivedTime(NOW);
            expected.setReplyDeadline(NOW.plusDays(1));
            expected.setDeliveryDeadline(NOW.plusDays(21));
            assertEquals(expected, created);
            created.setId(GENERATED);
            return 1;
        }).when(applyMapper).insertRenewal(any(MarketResourceApplyDO.class));
        successfulProcess();

        assertEquals(GENERATED, service.createRenewal(request));

        assertEquals(before, original);
        assertEquals("  " + OPERATION + "  ", request.getOperationId());
        assertEquals(REQUESTED_END, request.getApplyEndTime());
        verifySuccessfulAudit(GENERATED);
    }

    @Test
    void processUsesTheActorCapturedBeforeWritesWithoutReadingIdentityAgain() {
        prepareInsertion();
        when(objectGuard.requireUserId()).thenReturn(USER, USER + 1);
        stubInsert(1, GENERATED);
        successfulProcess();

        assertEquals(GENERATED, service.createRenewal(request()));

        verify(objectGuard).requireUserId();
        var captor = ArgumentCaptor.forClass(MarketCheckProcessDO.class);
        verify(processMapper).insert(captor.capture());
        assertEquals(USER, captor.getValue().getOperatorUserId());
        verifySuccessfulAudit(GENERATED);
    }

    @Test
    void publicEntryDeclaresIndependentReadCommittedTransactionWithBoundedTimeout() throws Exception {
        var method = MarketApplyServiceImpl.class.getMethod("createRenewal", MarketRenewalReqVO.class);
        Transactional definition = method.getAnnotation(Transactional.class);
        assertNotNull(definition);
        assertAll(
                () -> assertEquals(Propagation.REQUIRES_NEW, definition.propagation()),
                () -> assertEquals(Isolation.READ_COMMITTED, definition.isolation()),
                () -> assertEquals(10, definition.timeout()),
                () -> assertFalse(definition.readOnly()),
                () -> assertArrayEquals(new Class<?>[]{Exception.class}, definition.rollbackFor()));
    }

    @Test
    void insertionRequestsNestedPropagationAndCompletesBeforeProcessAndAudit() {
        prepareInsertion();
        stubInsert(1, GENERATED);
        successfulProcess();

        assertEquals(GENERATED, service.createRenewal(request()));

        InOrder calls = inOrder(applyMapper, transactionManager, processMapper, auditRecorder);
        calls.verify(applyMapper).selectRenewalByOperation( OPERATION);
        calls.verify(applyMapper).selectOwnedForRenewal( PARENT, USER);
        calls.verify(applyMapper).selectRenewalByOperation( OPERATION);
        calls.verify(applyMapper).databaseNow();
        calls.verify(applyMapper).hasActiveRenewalByParent( PARENT);
        calls.verify(applyMapper).hasActivePendingApply( USER, ASSET);
        calls.verify(applyMapper).nextApplyNoSeq();
        var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        calls.verify(transactionManager).getTransaction(definition.capture());
        assertEquals(TransactionDefinition.PROPAGATION_NESTED,
                definition.getValue().getPropagationBehavior());
        calls.verify(applyMapper).insertRenewal(any(MarketResourceApplyDO.class));
        calls.verify(transactionManager).commit(insertionStatus);
        calls.verify(processMapper).insert(any(MarketCheckProcessDO.class));
        calls.verify(auditRecorder).record("RENEW", "APPLY", GENERATED, null,
                MarketResourceApplyDO.STATUS_PENDING, "续期申请", "OK");
        calls.verifyNoMoreInteractions();
        verify(transactionManager, never()).rollback(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void identicalReplayBeforeOrAfterParentLockReturnsWithoutWriting(boolean afterLock) {
        when(objectGuard.requireUserId()).thenReturn(USER);
        MarketResourceApplyDO winner = winner();
        // 当前截止期限可能已经被后续补正修改；比较的是首次请求证据。
        winner.setApplyEndTime(STORED_END.plusYears(1));
        if (afterLock) {
            when(applyMapper.selectRenewalByOperation( OPERATION)).thenReturn(null, winner);
            when(applyMapper.selectOwnedForRenewal( PARENT, USER)).thenReturn(original);
        } else {
            when(applyMapper.selectRenewalByOperation( OPERATION)).thenReturn(winner);
        }

        assertEquals(GENERATED, service.createRenewal(request()));

        InOrder calls = inOrder(applyMapper);
        calls.verify(applyMapper).selectRenewalByOperation( OPERATION);
        if (afterLock) {
            calls.verify(applyMapper).selectOwnedForRenewal( PARENT, USER);
            calls.verify(applyMapper).selectRenewalByOperation( OPERATION);
        }
        calls.verifyNoMoreInteractions();
        verifyNoInteractions(transactionManager, processMapper, auditRecorder, displayHelper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETED", "OWNER", "PARENT", "REQUESTED_END"})
    void incompatibleReplayRejectsWithoutWriting(String field) {
        when(objectGuard.requireUserId()).thenReturn(USER);
        MarketResourceApplyDO winner = winner();
        switch (field) {
            case "DELETED" -> winner.setDeleted(true);
            case "OWNER" -> winner.setApplyUserId(USER + 1);
            case "PARENT" -> winner.setRenewalOfApplyId(PARENT + 1);
            case "REQUESTED_END" -> winner.setRenewalRequestedEndTime(STORED_END.plusNanos(1000));
            default -> throw new AssertionError(field);
        }
        when(applyMapper.selectRenewalByOperation( OPERATION)).thenReturn(winner);

        assertConflict();

        verify(applyMapper).selectRenewalByOperation( OPERATION);
        verifyNoMoreInteractions(applyMapper);
        verifyNoInteractions(transactionManager, processMapper, auditRecorder, displayHelper);
    }

    @Test
    void duplicateWinnerIsReadOnlyAfterNestedRollbackAndReturnedWithoutAnotherWrite() {
        prepareInsertion();
        when(applyMapper.selectRenewalByOperation( OPERATION)).thenReturn(null, null, winner());
        duplicateInsert();

        assertEquals(GENERATED, service.createRenewal(request()));

        verifyRollbackBeforeReadback();
        verify(applyMapper).hasActiveRenewalByParent( PARENT);
        verify(applyMapper).hasActivePendingApply( USER, ASSET);
        verifyNoInteractions(processMapper, auditRecorder);
    }

    @Test
    void duplicateWinnerWithDifferentEvidenceIsRejectedAfterRollbackWithoutAudit() {
        prepareInsertion();
        MarketResourceApplyDO winner = winner();
        winner.setRenewalRequestedEndTime(null);
        when(applyMapper.selectRenewalByOperation( OPERATION)).thenReturn(null, null, winner);
        duplicateInsert();

        assertConflict();

        verifyRollbackBeforeReadback();
        verifyNoInteractions(processMapper, auditRecorder);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PARENT", "PENDING"})
    void duplicateWithoutWinnerRechecksActiveConflictsAfterRollback(String conflict) {
        prepareInsertion();
        if ("PARENT".equals(conflict)) {
            when(applyMapper.hasActiveRenewalByParent( PARENT)).thenReturn(false, true);
        } else {
            when(applyMapper.hasActivePendingApply( USER, ASSET)).thenReturn(false, true);
        }
        duplicateInsert();

        assertConflict();

        InOrder calls = verifyRollbackBeforeReadback();
        calls.verify(applyMapper).hasActiveRenewalByParent( PARENT);
        if ("PENDING".equals(conflict)) {
            calls.verify(applyMapper).hasActivePendingApply( USER, ASSET);
        }
        calls.verifyNoMoreInteractions();
        verifyNoInteractions(processMapper, auditRecorder);
    }

    @Test
    void unexplainedDuplicateIsPropagatedUnchangedInsteadOfBeingSuppressedOrRetried() {
        prepareInsertion();
        DuplicateKeyException failure = duplicateInsert();

        assertSame(failure, assertThrows(DuplicateKeyException.class,
                () -> service.createRenewal(request())));

        InOrder calls = verifyRollbackBeforeReadback();
        calls.verify(applyMapper).hasActiveRenewalByParent( PARENT);
        calls.verify(applyMapper).hasActivePendingApply( USER, ASSET);
        calls.verifyNoMoreInteractions();
        verifyNoInteractions(processMapper, auditRecorder);
    }

    @Test
    void failedConflictReadbackIsPropagatedWithoutRepeatingInsertion() {
        prepareInsertion();
        IllegalStateException failure = new IllegalStateException("fixture readback failure");
        when(applyMapper.selectRenewalByOperation( OPERATION))
                .thenReturn(null, null).thenThrow(failure);
        duplicateInsert();

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.createRenewal(request())));

        verifyRollbackBeforeReadback();
        verifyNoInteractions(processMapper, auditRecorder);
    }

    @Test
    void nestedBeginFailureDoesNotInvokeAnyWriteOrAudit() {
        prepareBeforeTransaction();
        IllegalStateException failure = new IllegalStateException("fixture begin failure");
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.createRenewal(request())));

        verify(applyMapper, never()).insertRenewal(any(MarketResourceApplyDO.class));
        verify(transactionManager, never()).commit(any());
        verify(transactionManager, never()).rollback(any());
        verify(applyMapper, times(2)).selectRenewalByOperation( OPERATION);
        verifyNoInteractions(processMapper, auditRecorder);
    }

    private void prepareInsertion() {
        prepareBeforeTransaction();
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(insertionStatus);
    }

    private void prepareBeforeTransaction() {
        when(objectGuard.requireUserId()).thenReturn(USER);
        when(applyMapper.selectOwnedForRenewal( PARENT, USER)).thenReturn(original);
        when(applyMapper.databaseNow()).thenReturn(NOW);
        when(applyMapper.nextApplyNoSeq()).thenReturn(88L);
        when(displayHelper.nextBizNo("APP", 88L)).thenReturn("APP-88");
        var asset = new DataAssetDO();
        asset.setId(ASSET);
        asset.setStatus(DataAssetDO.STATUS_PUBLISHED);
        asset.setShareAttr(DataAssetDO.SHARE_UNCONDITIONAL);
        when(assetMapper.selectById(ASSET)).thenReturn(asset);
        when(workingDayService.plusWorkingDays(any(LocalDateTime.class), anyInt())).thenAnswer(call ->
                ((LocalDateTime) call.getArgument(0)).plusDays((Integer) call.getArgument(1)));
    }

    private void stubInsert(int rows, Long generatedId) {
        doAnswer(call -> {
            MarketResourceApplyDO renewal = call.getArgument(0);
            renewal.setId(generatedId);
            return rows;
        }).when(applyMapper).insertRenewal(any(MarketResourceApplyDO.class));
    }

    private void successfulProcess() {
        when(processMapper.insert(any(MarketCheckProcessDO.class))).thenReturn(1);
    }

    private DuplicateKeyException duplicateInsert() {
        DuplicateKeyException failure = new DuplicateKeyException("fixture unique conflict");
        doThrow(failure).when(applyMapper).insertRenewal(any(MarketResourceApplyDO.class));
        return failure;
    }

    private InOrder verifyRollbackBeforeReadback() {
        InOrder calls = inOrder(applyMapper, transactionManager);
        calls.verify(applyMapper).selectRenewalByOperation( OPERATION);
        calls.verify(applyMapper).selectOwnedForRenewal( PARENT, USER);
        calls.verify(applyMapper).selectRenewalByOperation( OPERATION);
        calls.verify(applyMapper).databaseNow();
        calls.verify(applyMapper).hasActiveRenewalByParent( PARENT);
        calls.verify(applyMapper).hasActivePendingApply( USER, ASSET);
        calls.verify(applyMapper).nextApplyNoSeq();
        calls.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        calls.verify(applyMapper).insertRenewal(any(MarketResourceApplyDO.class));
        calls.verify(transactionManager).rollback(insertionStatus);
        calls.verify(applyMapper).selectRenewalByOperation( OPERATION);
        verify(applyMapper).insertRenewal(any(MarketResourceApplyDO.class));
        verify(transactionManager, never()).commit(any());
        return calls;
    }

    private void assertConflict() {
        ServiceException failure = assertThrows(ServiceException.class,
                () -> service.createRenewal(request()));
        assertEquals(MARKET_APPLY_RENEWAL_CONFLICT.getCode(), failure.getCode());
    }

    private void verifySuccessfulAudit(long id) {
        verify(auditRecorder).record("RENEW", "APPLY", id, null,
                MarketResourceApplyDO.STATUS_PENDING, "续期申请", "OK");
    }

    private static MarketRenewalReqVO request() {
        var request = new MarketRenewalReqVO();
        request.setId(PARENT);
        request.setOperationId("  " + OPERATION + "  ");
        request.setApplyEndTime(REQUESTED_END);
        return request;
    }

    private static MarketResourceApplyDO winner() {
        return MarketResourceApplyDO.builder().id(GENERATED).applyUserId(USER)
                .renewalOfApplyId(PARENT).renewalOperationId(OPERATION)
                .renewalRequestedEndTime(STORED_END).applyEndTime(STORED_END).build();
    }

    private static MarketResourceApplyDO approved() {
        var apply = MarketResourceApplyDO.builder()
                .id(PARENT).applyNo("APP-original").title("续期夹具")
                .assetId(ASSET).assetType(1).apiId(3L).appId(2L).demandId(4L)
                .applyDeptId(6L).applyUserId(USER).phone("fixture-contact")
                .email("fixture@example.invalid").purpose("fixture purpose")
                .fieldScope("id").applyNote("fixture note")
                .applyStartTime(NOW.minusDays(1)).applyEndTime(ORIGINAL_END)
                .applyUseType(MarketResourceApplyDO.USE_UNLIMITED).applyUseTimes(15)
                .status(MarketResourceApplyDO.STATUS_APPROVED)
                .ifAuth(MarketResourceApplyDO.AUTH_YES).paused(0)
                .checkUserId(11L).checkTime(NOW.minusHours(1)).checkNote("原批准")
                .revision(3).sourceRevision(18L).sourceGrantId("prior-source")
                .snapshot("{\"frozenColumns\":[\"id\"],\"tableName\":\"fixture_table\"}")
                .catalogVersionId(12L).dutyMatter("fixture duty").legalBasis("fixture basis")
                .supplementNote("fixture supplement").receivedTime(NOW.minusDays(2))
                .replyDeadline(NOW.plusDays(2)).deliveryDeadline(NOW.plusDays(3))
                .deliveryStatus(MarketDeliveryService.DELIVERY_SUCCESS)
                .firstUsedTime(NOW.minusMinutes(1)).bpmInstanceId("prior-bpm")
                .remark("fixture remark").renewalOfApplyId(6L)
                .renewalOperationId("previous-operation").renewalRequestedEndTime(ORIGINAL_END)
                .build();
        apply.setCreateTime(NOW.minusDays(3));
        apply.setUpdateTime(NOW.minusDays(1));
        apply.setCreator("fixture-creator");
        apply.setUpdater("fixture-updater");
        apply.setDeleted(false);
        return apply;
    }
}
