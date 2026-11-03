package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetGrantSourceDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDeliveryOperationDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.AssetGrantSourceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketApprovalEvidenceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryOperationMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO.Action;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_STATUS_DENY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 交付写入完整性的隔离编排测试。复用现有冻结快照、真实命令构建器和 LambdaUpdateWrapper。
 * 手动设置事务同步标志只验证入队门禁及回调注册/调用顺序，不建立真实事务、不执行 SQL，
 * 不能证明数据库回滚、锁、租户过滤、持久化证据或远端未知结果恢复；这些由 PG/集成测试验证。
 */
class MarketDeliveryWriteIntegrityTest extends BaseMockitoUnitTest {

    private static final long TENANT = 1L;
    private static final long APPLY_ID = 9007199254740993L;
    private static final long OPERATION_ID = 9007199254740995L;
    private static final String SOURCE_KEY = "asset:apply:" + APPLY_ID;
    private static final String GRANT_ID = "apply:" + APPLY_ID + ":r1";

    private enum Boundary { SOURCE_INSERT, SOURCE_UPDATE, OPERATION, APPLICATION }

    @InjectMocks private MarketDeliveryService service;
    @Mock private AssetGrantSourceMapper grantSourceMapper;
    @Mock private MarketDeliveryOperationMapper operationMapper;
    @Mock private MarketResourceApplyMapper applyMapper;
    @Mock private MarketApprovalEvidenceMapper approvalEvidenceMapper;
    @Mock private MarketDeliveryCoordinator coordinator;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                MarketResourceApplyDO.class);
    }

    @BeforeEach
    void establishContext() {
        TenantContextHolder.clear();
        TenantContextHolder.clear();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clearContext() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TenantContextHolder.clear();
    }

    @ParameterizedTest
    @MethodSource("abnormalRows")
    void everyWriteMustAffectExactlyOneRowAndStopAtFailure(
            Action action, boolean api, Boundary boundary, int rows) {
        var apply = application(api);
        String before = JsonUtils.toJsonString(apply);
        stubBefore(boundary, api);
        stubRows(boundary, rows);

        assertDenied(() -> enqueue(action, apply));

        verifyStopped(action, api, boundary);
        assertEquals(before, JsonUtils.toJsonString(apply), "不得用内存申请变更冒充持久回填");
    }

    private static Stream<Arguments> abnormalRows() {
        return scenarios().flatMap(args -> Stream.of(0, 2, -1)
                .map(rows -> Arguments.of(args.get()[0], args.get()[1], args.get()[2], rows)));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    void mapperExceptionIsPropagatedUnchangedWithoutRetryOrLaterWrites(
            Action action, boolean api, Boundary boundary) {
        var apply = application(api);
        stubBefore(boundary, api);
        var failure = new DataIntegrityViolationException("injected write failure");
        switch (boundary) {
            case SOURCE_INSERT -> when(grantSourceMapper.insert(any(AssetGrantSourceDO.class))).thenThrow(failure);
            case SOURCE_UPDATE -> when(grantSourceMapper.updateById(any(AssetGrantSourceDO.class))).thenThrow(failure);
            case OPERATION -> when(operationMapper.insertFrozen( any())).thenThrow(failure);
            case APPLICATION -> when(applyMapper.update(isNull(), any(Wrapper.class))).thenThrow(failure);
        }

        assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> enqueue(action, apply)));

        verifyStopped(action, api, boundary);
    }

    private static Stream<Arguments> scenarios() {
        return Stream.of(Action.values()).flatMap(action -> Stream.of(false, true)
                .flatMap(api -> Stream.of(Boundary.values())
                        .filter(boundary -> api || boundary != Boundary.OPERATION)
                        .map(boundary -> Arguments.of(action, api, boundary))));
    }

    @ParameterizedTest
    @MethodSource("invalidOperationIds")
    void oneFrozenRowWithoutPositiveIdentityCannotBackfillOrRegisterClaim(Action action, Long id) {
        stubSource(false);
        stubFrozen(1, id);

        assertDenied(() -> enqueue(action, application(true)));

        verifyStopped(action, true, Boundary.OPERATION);
    }

    private static Stream<Arguments> invalidOperationIds() {
        return Stream.of(Action.values()).flatMap(action -> Stream.of(null, 0L, -1L)
                .map(id -> Arguments.of(action, id)));
    }

    @ParameterizedTest
    @MethodSource("apiSuccess")
    void successfulApiWritesFreezeCommandBeforeBackfillAndClaimOnlyAfterCommit(
            Action action, boolean existing, boolean claimed) {
        var apply = application(true);
        String before = JsonUtils.toJsonString(apply);
        stubSource(existing);
        stubFrozen(1, OPERATION_ID);
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        enqueue(action, apply);

        long revision = existing ? 6L : 3L;
        var order = inOrder(grantSourceMapper, approvalEvidenceMapper, operationMapper, applyMapper);
        order.verify(grantSourceMapper).selectSourceForUpdate( SOURCE_KEY);
        if (existing) {
            order.verify(grantSourceMapper).updateById(any(AssetGrantSourceDO.class));
        } else {
            order.verify(grantSourceMapper).selectByApplyId(APPLY_ID);
            order.verify(grantSourceMapper).insert(any(AssetGrantSourceDO.class));
        }
        if (action == Action.GRANT) order.verify(approvalEvidenceMapper).selectByApply( APPLY_ID);
        order.verify(operationMapper).insertFrozen( any());
        order.verify(applyMapper).update(isNull(), any(Wrapper.class));
        order.verifyNoMoreInteractions();
        assertSource(action, existing, apply, revision);
        assertBackfill(MarketDeliveryService.DELIVERY_PENDING, revision);
        var operation = captureOperation();
        assertEquals(OPERATION_ID, operation.getId());
        assertEquals(APPLY_ID, operation.getApplyId());
        assertEquals(GRANT_ID, operation.getSourceGrantId());
        assertEquals(action == Action.GRANT ? "GRANT" : "REVOKE", operation.getOpType());
        assertEquals(1, operation.getExpectedRevision());
        assertEquals(revision, operation.getSourceRevision());
        assertEquals(apply.getAppId(), operation.getAppId());
        assertEquals(apply.getApiId(), operation.getApiId());
        assertEquals("asset:" + SOURCE_KEY + ":r" + revision + ":" + action, operation.getOperationId());
        var command = MarketDeliveryPayload.fromJson(operation.getPayloadJson());
        assertEquals(service.buildCommand(apply, action, operation.getOperationId(), revision), command);
        assertEquals(MarketDeliveryPayload.hash( command), operation.getPayloadHash());
        if (action == Action.GRANT) assertEquals(List.of("id"), command.scope().approvedColumns());
        else assertNull(command.scope());
        assertEquals(before, JsonUtils.toJsonString(apply));
        assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
        verifyNoInteractions(coordinator);

        var claimedRow = MarketDeliveryOperationDO.builder().id(OPERATION_ID).claimToken("claimed-token").build();
        when(coordinator.claim( eq(OPERATION_ID), anyString()))
                .thenReturn(claimed ? Optional.of(claimedRow) : Optional.empty());
        fireAfterCommit();

        verify(coordinator).claim( eq(OPERATION_ID), argThat(token -> token != null && !token.isBlank()));
        if (claimed) verify(coordinator).execute( same(claimedRow));
        verifyNoMoreInteractions(coordinator, grantSourceMapper, operationMapper, applyMapper, approvalEvidenceMapper);
    }

    private static Stream<Arguments> apiSuccess() {
        return Stream.of(Action.values()).flatMap(action -> Stream.of(false, true)
                .flatMap(existing -> Stream.of(false, true).map(claimed -> Arguments.of(action, existing, claimed))));
    }

    @ParameterizedTest
    @MethodSource("localSuccess")
    void missingApiOrAppOnlyPersistsLocalSourceAndNeverInventsRemoteOperation(
            Action action, boolean existing, String missing) {
        var apply = application(true);
        if (!"APP".equals(missing)) apply.setApiId(null);
        if (!"API".equals(missing)) apply.setAppId(null);
        stubSource(existing);
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        enqueue(action, apply);

        verifySourceLookupAndWrite(existing);
        long revision = existing ? 6L : 3L;
        assertSource(action, existing, apply, revision);
        verifyEvidenceRead(action);
        assertBackfill(MarketDeliveryService.DELIVERY_NONE, revision);
        assertNoCallback();
        verifyNoInteractions(operationMapper);
        verifyNoMoreInteractions(grantSourceMapper, applyMapper, approvalEvidenceMapper);
    }

    private static Stream<Arguments> localSuccess() {
        return Stream.of(Action.values()).flatMap(action -> Stream.of(false, true)
                .flatMap(existing -> Stream.of("APP", "API", "BOTH")
                        .map(missing -> Arguments.of(action, existing, missing))));
    }

    @ParameterizedTest
    @MethodSource("positiveOperationIds")
    void positiveFrozenIdentityIsPassedLosslesslyToClaim(Action action, long id) {
        stubSource(false);
        stubFrozen(1, id);
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        enqueue(action, application(true));

        verifyNoInteractions(coordinator);
        when(coordinator.claim( eq(id), anyString())).thenReturn(Optional.empty());
        fireAfterCommit();
        verify(coordinator).claim( eq(id), anyString());
        verifyNoMoreInteractions(coordinator);
    }

    private static Stream<Arguments> positiveOperationIds() {
        return Stream.of(Action.values()).flatMap(action -> Stream.of(1L, Long.MAX_VALUE)
                .map(id -> Arguments.of(action, id)));
    }

    @ParameterizedTest
    @EnumSource(Action.class)
    void failedImmediateClaimDoesNotRetryOrRewriteCommittedIntent(Action action) {
        stubSource(false);
        stubFrozen(1, OPERATION_ID);
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        enqueue(action, application(true));
        when(coordinator.claim( eq(OPERATION_ID), anyString()))
                .thenThrow(new IllegalStateException("claim unavailable"));
        clearInvocations(grantSourceMapper, operationMapper, applyMapper, approvalEvidenceMapper);

        assertDoesNotThrow(this::fireAfterCommit);

        verify(coordinator).claim( eq(OPERATION_ID), anyString());
        verifyNoMoreInteractions(coordinator);
        verifyNoInteractions(grantSourceMapper, operationMapper, applyMapper, approvalEvidenceMapper);
    }

    @ParameterizedTest
    @MethodSource("missingTransaction")
    void bothActualTransactionAndSynchronizationAreRequiredBeforeAnyWrite(Action action, boolean actual) {
        if (actual) TransactionSynchronizationManager.clearSynchronization();
        else TransactionSynchronizationManager.setActualTransactionActive(false);

        var failure = assertThrows(IllegalStateException.class, () -> enqueue(action, application(true)));

        assertEquals("Market delivery enqueue requires an active transaction", failure.getMessage());
        verifyNoInteractions(grantSourceMapper, operationMapper, applyMapper, approvalEvidenceMapper, coordinator);
    }

    private static Stream<Arguments> missingTransaction() {
        return Stream.of(Action.values()).flatMap(action -> Stream.of(false, true)
                .map(actual -> Arguments.of(action, actual)));
    }



    private static Stream<Arguments> untrustedTenants() {
        return Stream.of(Action.values()).flatMap(action -> Stream.of(
                Arguments.of(action, null, false), Arguments.of(action, -1L, false),
                Arguments.of(action, TENANT, true)));
    }

    private void stubBefore(Boundary boundary, boolean api) {
        if (boundary == Boundary.SOURCE_UPDATE) {
            when(grantSourceMapper.selectSourceForUpdate( SOURCE_KEY)).thenReturn(existingSource());
        } else if (boundary != Boundary.SOURCE_INSERT) {
            when(grantSourceMapper.insert(any(AssetGrantSourceDO.class))).thenReturn(1);
        }
        if (boundary == Boundary.APPLICATION && api) stubFrozen(1, OPERATION_ID);
    }

    private void stubRows(Boundary boundary, int rows) {
        switch (boundary) {
            case SOURCE_INSERT -> when(grantSourceMapper.insert(any(AssetGrantSourceDO.class))).thenReturn(rows);
            case SOURCE_UPDATE -> when(grantSourceMapper.updateById(any(AssetGrantSourceDO.class))).thenReturn(rows);
            case OPERATION -> stubFrozen(rows, OPERATION_ID);
            case APPLICATION -> when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(rows);
        }
    }

    private void stubSource(boolean existing) {
        if (existing) {
            when(grantSourceMapper.selectSourceForUpdate( SOURCE_KEY)).thenReturn(existingSource());
            when(grantSourceMapper.updateById(any(AssetGrantSourceDO.class))).thenReturn(1);
        } else {
            // 不模拟来源主键回填：本轮只约束受影响行数，不新增来源主键契约。
            when(grantSourceMapper.insert(any(AssetGrantSourceDO.class))).thenReturn(1);
        }
    }

    private void stubFrozen(int rows, Long id) {
        when(operationMapper.insertFrozen( any())).thenAnswer(invocation -> {
            MarketDeliveryOperationDO row = invocation.getArgument(0);
            row.setId(id);
            return rows;
        });
    }

    private void verifyStopped(Action action, boolean api, Boundary boundary) {
        verifySourceLookupAndWrite(boundary == Boundary.SOURCE_UPDATE);
        boolean sourceFailure = boundary == Boundary.SOURCE_INSERT || boundary == Boundary.SOURCE_UPDATE;
        if (sourceFailure) {
            verifyNoInteractions(approvalEvidenceMapper, operationMapper, applyMapper);
        } else {
            verifyEvidenceRead(action);
            if (api) verify(operationMapper).insertFrozen( any());
            else verifyNoInteractions(operationMapper);
            if (boundary == Boundary.APPLICATION) verify(applyMapper).update(isNull(), any(Wrapper.class));
            else verifyNoInteractions(applyMapper);
        }
        assertNoCallback();
        verifyNoMoreInteractions(grantSourceMapper, operationMapper, applyMapper, approvalEvidenceMapper);
    }

    private void verifySourceLookupAndWrite(boolean existing) {
        verify(grantSourceMapper).selectSourceForUpdate( SOURCE_KEY);
        if (existing) verify(grantSourceMapper).updateById(any(AssetGrantSourceDO.class));
        else {
            verify(grantSourceMapper).selectByApplyId(APPLY_ID);
            verify(grantSourceMapper).insert(any(AssetGrantSourceDO.class));
        }
    }

    private void verifyEvidenceRead(Action action) {
        if (action == Action.GRANT) verify(approvalEvidenceMapper).selectByApply( APPLY_ID);
        else verifyNoInteractions(approvalEvidenceMapper);
    }

    private void assertNoCallback() {
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        verifyNoInteractions(coordinator);
    }

    private void assertSource(Action action, boolean existing, MarketResourceApplyDO apply, long revision) {
        var captor = ArgumentCaptor.forClass(AssetGrantSourceDO.class);
        if (existing) verify(grantSourceMapper).updateById(captor.capture());
        else verify(grantSourceMapper).insert(captor.capture());
        var source = captor.getValue();
        if (existing) assertEquals(19L, source.getId());
        else assertNull(source.getId());
        assertEquals(APPLY_ID, source.getApplyId());
        assertEquals(AssetGrantSourceDO.TYPE_APPLY, source.getSourceType());
        assertEquals(SOURCE_KEY, source.getSourceKey());
        assertEquals(switch (action) {
            case GRANT -> "ACTIVE";
            case SUSPEND -> "SUSPENDED";
            case REVOKE -> "REVOKED";
        }, source.getSourceStatus());
        assertEquals(action == Action.GRANT ? 0 : 1, source.getRevoked());
        assertEquals(revision, source.getRevision());
        assertEquals(apply.getAppId(), source.getAppId());
        assertEquals(apply.getApiId(), source.getApiId());
        assertEquals(apply.getAssetId(), source.getAssetId());
        assertEquals(apply.getSnapshot(), source.getScopeJson());
        assertEquals(apply.getApplyStartTime(), source.getStartTime());
        assertEquals(apply.getApplyEndTime(), source.getEndTime());
    }

    @SuppressWarnings("unchecked")
    private void assertBackfill(int status, long revision) {
        ArgumentCaptor<LambdaUpdateWrapper<MarketResourceApplyDO>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(applyMapper).update(isNull(), captor.capture());
        var wrapper = captor.getValue();
        assertNull(wrapper.getEntity());
        String condition = wrapper.getSqlSegment();
        assertEquals(List.of("id"), assignmentColumns(condition));
        assertBound(wrapper, condition, "id", APPLY_ID);
        String set = wrapper.getSqlSet();
        assertEquals(List.of("delivery_status", "source_grant_id", "source_revision"), assignmentColumns(set));
        assertBound(wrapper, set, "delivery_status", status);
        assertBound(wrapper, set, "source_grant_id", GRANT_ID);
        assertBound(wrapper, set, "source_revision", revision);
        assertEquals(4, wrapper.getParamNameValuePairs().size());
    }

    private MarketDeliveryOperationDO captureOperation() {
        var captor = ArgumentCaptor.forClass(MarketDeliveryOperationDO.class);
        verify(operationMapper).insertFrozen( captor.capture());
        return captor.getValue();
    }

    private static void assertBound(LambdaUpdateWrapper<?> wrapper, String sql, String column, Object value) {
        var matcher = Pattern.compile("(?i)(?<![\\w.])" + Pattern.quote(column)
                + "\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}").matcher(sql);
        assertTrue(matcher.find(), "缺少参数绑定：" + column);
        assertEquals(value, wrapper.getParamNameValuePairs().get(matcher.group(1)));
        assertFalse(matcher.find());
    }

    private static List<String> assignmentColumns(String sql) {
        return Pattern.compile("(?i)(?<![\\w.])([a-z_]+)\\s*=").matcher(sql)
                .results().map(result -> result.group(1)).sorted().toList();
    }

    private static void assertDenied(org.junit.jupiter.api.function.Executable action) {
        assertEquals(MARKET_APPLY_STATUS_DENY.getCode(), assertThrows(ServiceException.class, action).getCode());
    }

    private static AssetGrantSourceDO existingSource() {
        return AssetGrantSourceDO.builder().id(19L).applyId(APPLY_ID).sourceType(AssetGrantSourceDO.TYPE_APPLY)
                .sourceKey(SOURCE_KEY).sourceStatus("ACTIVE").revision(5L).revoked(0).build();
    }

    private static MarketResourceApplyDO application(boolean api) {
        var apply = MarketDeliveryConfirmationFixture.apply();
        apply.setId(APPLY_ID);
        apply.setSourceGrantId(GRANT_ID);
        if (!api) apply.setApiId(null);
        return apply;
    }

    private void enqueue(Action action, MarketResourceApplyDO apply) {
        switch (action) {
            case GRANT -> service.enqueueGrant(apply);
            case SUSPEND -> service.enqueueSuspend(apply);
            case REVOKE -> service.enqueueRevoke(apply);
        }
    }

    private void fireAfterCommit() {
        var callbacks = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, callbacks.size());
        callbacks.getFirst().afterCommit();
    }
}
