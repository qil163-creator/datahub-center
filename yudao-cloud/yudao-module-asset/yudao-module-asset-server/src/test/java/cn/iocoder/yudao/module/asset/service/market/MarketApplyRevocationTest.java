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
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import cn.iocoder.yudao.module.asset.service.monitor.AssetMonitorService;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * revokeApply 的隔离编排契约，复用 BaseMockitoUnitTest 和真实 LambdaUpdateWrapper。
 * Mock Guard 只验证提供方权限入口未被绕过，不验证内部授权规则。
 * Mock Mapper/交付/通知不证明 SQL 执行、对象权限、事务回滚、终态来源落库或提交后通知；
 * 这些由主线程的真实 PostgreSQL/集成测试验证。
 */
class MarketApplyRevocationTest extends BaseMockitoUnitTest {

    private static final long APPLY_ID = 9007199254740993L;
    private static final long ACTOR_ID = 9007199254740995L;
    private static final long ASSET_ID = 5L;
    private static final String NOTE = "本次收回意见";
    private static final String FROZEN = """
            {"frozenColumns":["Id","Name"],"assetId":"5","assetVersion":"v1",
             "publishedSnapshotId":"11","catalogVersionId":"12","databaseId":"4",
             "schemaName":"public","tableName":"citizen"}
            """;

    @InjectMocks
    private MarketApplyServiceImpl service;
    @Mock
    private MarketResourceApplyMapper applyMapper;
    @Mock
    private DataAssetMapper assetMapper;
    @Mock
    private MarketObjectGuard objectGuard;
    @Mock
    private MarketCheckProcessMapper processMapper;
    @Mock
    private MarketDeliveryService deliveryService;
    @Mock
    private MarketNotifyHelper notifyHelper;
    @Mock
    private MarketAuditRecorder auditRecorder;
    @Mock
    private AssetCatalogService catalogService;
    @Mock
    private MarketSupplyApiGuard supplyApiGuard;
    @Mock
    private MarketWorkingDayService workingDayService;
    @Mock
    private MarketDeliveryEvidenceService deliveryEvidenceService;
    @Mock
    private AssetMonitorService assetMonitorService;

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
    void keepRevocationBoundaryAndClearContext() {
        try {
            // 收回补普通审计由真实 PG 生命周期专测精确核对，本类只检查其余无关协作替身。
            verifyNoInteractions(catalogService, supplyApiGuard,
                    workingDayService, deliveryEvidenceService, assetMonitorService);
        } finally {
            TenantContextHolder.clear();
        }
    }

    @ParameterizedTest
    @CsvSource(value = {
            "1,0,0,1", "0,0,5,1", "1,1,5,1", "0,1,5,1",
            "NULL,NULL,NULL,1", "1,NULL,NULL,0", "NULL,1,9007199254740993,9007199254740993",
            "0,NULL,9223372036854775807,9223372036854775807", "2,2,5,1"
    }, nullValues = "NULL")
    void revokesOnlyAfterProcessAndPreservesExactLifecycleCas(
            Integer originalAuth, Integer paused, Long sourceRevision, long tenantId) {
        TenantContextHolder.clear();
        var apply = application(originalAuth, paused, sourceRevision);
        var asset = asset();
        stubRead(apply, asset);
        String before = JsonUtils.toJsonString(apply);
        stubSuccessfulWrites(apply, before, NOTE);
        observeCurrentStateAtDeliveryAndNotification(apply, asset, before, NOTE);

        service.revokeApply(APPLY_ID, NOTE);

        assertCas(captureCas(), tenantId, originalAuth, paused, sourceRevision, NOTE);
        verifySuccess(apply, asset);
        assertOnlyRevocationChanged(before, apply, NOTE);
        assertNull(TenantContextHolder.getTenantId());
        assertFalse(TenantContextHolder.isIgnore());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "本次新的收回原因", "  ", "'; UPDATE dh_market_resource_apply SET if_auth=1; --"})
    void carriesThisRequestsNoteAsBoundDataAndNeverReusesThePreviousOpinion(String note) {
        var apply = application(1, 0, 3L);
        var asset = asset();
        stubRead(apply, asset);
        String before = JsonUtils.toJsonString(apply);
        stubSuccessfulWrites(apply, before, note);
        observeCurrentStateAtDeliveryAndNotification(apply, asset, before, note);

        service.revokeApply(APPLY_ID, note);

        var wrapper = captureCas();
        assertCas(wrapper, 1L, 1, 0, 3L, note);
        if (note != null && !note.isBlank()) {
            assertFalse(wrapper.getSqlSet().contains(note), "意见必须参数绑定，不能内联进 SQL");
        }
        verifySuccess(apply, asset);
        assertOnlyRevocationChanged(before, apply, note);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING_SNAPSHOT", "INVALID_SNAPSHOT", "CHANGED_VERSION",
            "MISSING_ASSET", "MISSING_ASSET_ID", "CHANGED_BINDING"})
    void revocationDoesNotRequireFreshMetadataOrImplicitApproval(String defect) {
        var apply = application(0, 1, null);
        DataAssetDO asset = asset();
        switch (defect) {
            case "MISSING_SNAPSHOT" -> apply.setSnapshot(null);
            case "INVALID_SNAPSHOT" -> apply.setSnapshot("legacy invalid json");
            case "CHANGED_VERSION" -> asset.setPublishedVersion("v2");
            case "MISSING_ASSET" -> asset = null;
            case "MISSING_ASSET_ID" -> {
                apply.setAssetId(null);
                asset = null;
            }
            case "CHANGED_BINDING" -> asset.setDatasourceId(99L);
            default -> throw new AssertionError(defect);
        }
        // Guard 的实际授权判断属于另一个测试边界；这里仅证明它仍被调用。
        stubRead(apply, asset);
        String before = JsonUtils.toJsonString(apply);
        stubSuccessfulWrites(apply, before, NOTE);

        service.revokeApply(APPLY_ID, NOTE);

        assertCas(captureCas(), 1L, 0, 1, null, NOTE);
        verifySuccess(apply, asset);
        assertOnlyRevocationChanged(before, apply, NOTE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_END", "EXPIRED"})
    void revocationDoesNotAddTheGrantOnlyExpirationCondition(String expiry) {
        var apply = application(1, 0, 3L);
        apply.setApplyEndTime(expiry.equals("NO_END") ? null : LocalDateTime.of(2000, 1, 1, 0, 0));
        var asset = asset();
        stubRead(apply, asset);
        String before = JsonUtils.toJsonString(apply);
        stubSuccessfulWrites(apply, before, NOTE);

        service.revokeApply(APPLY_ID, NOTE);

        assertCas(captureCas(), 1L, 1, 0, 3L, NOTE);
        verifySuccess(apply, asset);
        assertOnlyRevocationChanged(before, apply, NOTE);
    }



    private static Stream<Arguments> untrustedTenants() {
        return Stream.of(Arguments.of(null, false), Arguments.of(-1L, false),
                Arguments.of(Long.MIN_VALUE, false), Arguments.of(1L, true), Arguments.of(0L, true));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, -1})
    void casMustAffectExactlyOneRowWithoutRetryOrDownstreamCalls(int rows) {
        var apply = application(1, null, Long.MAX_VALUE);
        var asset = asset();
        stubRead(apply, asset);
        String before = JsonUtils.toJsonString(apply);
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(rows);

        assertBusinessFailure(MARKET_APPLY_STATUS_DENY, () -> service.revokeApply(APPLY_ID, NOTE));

        assertCas(captureCas(), 1L, 1, null, Long.MAX_VALUE, NOTE);
        finish(verifyThroughCas(apply, asset));
        verifyNoInteractions(processMapper, deliveryService, notifyHelper);
        assertEquals(before, JsonUtils.toJsonString(apply));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, -1})
    void processMustInsertExactlyOneRowBeforeMutatingObjectOrDelivering(int rows) {
        var apply = application(null, 1, null);
        var asset = asset();
        stubRead(apply, asset);
        String before = JsonUtils.toJsonString(apply);
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(objectGuard.requireUserId()).thenReturn(ACTOR_ID);
        when(processMapper.insert(any(MarketCheckProcessDO.class))).thenAnswer(invocation -> {
            assertEquals(before, JsonUtils.toJsonString(apply), "过程返回成功前仍须保持原观察对象");
            assertProcess(invocation.getArgument(0), NOTE);
            return rows;
        });

        assertBusinessFailure(MARKET_APPLY_STATUS_DENY, () -> service.revokeApply(APPLY_ID, NOTE));

        assertCas(captureCas(), 1L, null, 1, null, NOTE);
        finish(verifyThroughProcess(apply, asset));
        verifyNoInteractions(deliveryService, notifyHelper);
        assertEquals(before, JsonUtils.toJsonString(apply));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CAS", "PROCESS"})
    void mapperExceptionIsPreservedAndDoesNotMutateObservedObject(String stage) {
        var apply = application(1, 0, 3L);
        var asset = asset();
        stubRead(apply, asset);
        String before = JsonUtils.toJsonString(apply);
        RuntimeException failure = stage.equals("CAS")
                ? new CannotAcquireLockException("test revocation lock failure")
                : new DuplicateKeyException("test revocation process constraint failure");
        if (stage.equals("CAS")) {
            when(applyMapper.update(isNull(), any(Wrapper.class))).thenThrow(failure);
        } else {
            when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
            when(objectGuard.requireUserId()).thenReturn(ACTOR_ID);
            when(processMapper.insert(any(MarketCheckProcessDO.class))).thenAnswer(invocation -> {
                assertEquals(before, JsonUtils.toJsonString(apply));
                assertProcess(invocation.getArgument(0), NOTE);
                throw failure;
            });
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.revokeApply(APPLY_ID, NOTE)));

        assertCas(captureCas(), 1L, 1, 0, 3L, NOTE);
        if (stage.equals("CAS")) {
            finish(verifyThroughCas(apply, asset));
            verifyNoInteractions(processMapper);
        } else {
            finish(verifyThroughProcess(apply, asset));
        }
        verifyNoInteractions(deliveryService, notifyHelper);
        assertEquals(before, JsonUtils.toJsonString(apply));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PERMISSION", "ACTOR"})
    void guardFailureDoesNotFallBackToApplicantOrContinue(String stage) {
        var apply = application(0, 1, 3L);
        var asset = asset();
        stubRead(apply, asset);
        String before = JsonUtils.toJsonString(apply);
        RuntimeException failure = exception(MARKET_APPLY_FORBIDDEN);
        if (stage.equals("PERMISSION")) {
            doThrow(failure).when(objectGuard).assertCanAuthApply(same(apply), same(asset));
        } else {
            when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
            when(objectGuard.requireUserId()).thenThrow(failure);
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.revokeApply(APPLY_ID, NOTE)));

        InOrder order = stage.equals("PERMISSION") ? verifyRead(apply, asset) : verifyThroughCas(apply, asset);
        if (stage.equals("ACTOR")) {
            assertCas(captureCas(), 1L, 0, 1, 3L, NOTE);
            order.verify(objectGuard).requireUserId();
        }
        finish(order);
        verifyNoInteractions(processMapper, deliveryService, notifyHelper);
        assertEquals(before, JsonUtils.toJsonString(apply));
    }

    @Test
    void missingApplicationDoesNotResolveAssetOrCheckPermissions() {
        when(applyMapper.selectById(APPLY_ID)).thenReturn(null);

        assertBusinessFailure(MARKET_APPLY_NOT_EXISTS, () -> service.revokeApply(APPLY_ID, NOTE));

        verify(applyMapper).selectById(APPLY_ID);
        verifyNoMoreInteractions(applyMapper);
        verifyNoInteractions(assetMapper, objectGuard, processMapper, deliveryService, notifyHelper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPLICATION", "ASSET"})
    void readFailureIsNotTreatedAsPermissionToRevoke(String stage) {
        var failure = new DataAccessResourceFailureException("test revocation read unavailable");
        if (stage.equals("APPLICATION")) {
            when(applyMapper.selectById(APPLY_ID)).thenThrow(failure);
        } else {
            when(applyMapper.selectById(APPLY_ID)).thenReturn(application(1, 0, 3L));
            when(assetMapper.selectById(ASSET_ID)).thenThrow(failure);
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.revokeApply(APPLY_ID, NOTE)));

        verify(applyMapper).selectById(APPLY_ID);
        if (stage.equals("ASSET")) verify(assetMapper).selectById(ASSET_ID);
        verifyNoMoreInteractions(applyMapper, assetMapper);
        verifyNoInteractions(objectGuard, processMapper, deliveryService, notifyHelper);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, 1, 3, 4, 5, 8, Integer.MAX_VALUE})
    void rejectedCasNeverUsesObservedNonApprovedStatusAsAWritePrecondition(Integer observedStatus) {
        var apply = application(1, 0, 3L);
        apply.setStatus(observedStatus);
        var asset = asset();
        stubRead(apply, asset);
        String before = JsonUtils.toJsonString(apply);
        // 模拟数据库拒绝状态已不匹配的 CAS；不宣称 Mock 已执行数据库的状态判断。
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        assertBusinessFailure(MARKET_APPLY_STATUS_DENY, () -> service.revokeApply(APPLY_ID, NOTE));

        assertCas(captureCas(), 1L, 1, 0, 3L, NOTE);
        finish(verifyThroughCas(apply, asset));
        verifyNoInteractions(processMapper, deliveryService, notifyHelper);
        assertEquals(before, JsonUtils.toJsonString(apply));
    }

    @Test
    void deliveryFailurePreventsNotificationWithoutGrantSuspendOrApprovalFallback() {
        var apply = application(1, 1, 3L);
        var asset = asset();
        stubRead(apply, asset);
        String before = JsonUtils.toJsonString(apply);
        stubSuccessfulWrites(apply, before, NOTE);
        var failure = new IllegalStateException("test revocation delivery unavailable");
        doAnswer(invocation -> {
            assertOnlyRevocationChanged(before, apply, NOTE);
            throw failure;
        }).when(deliveryService).enqueueRevoke(same(apply));

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.revokeApply(APPLY_ID, NOTE)));

        InOrder order = verifyThroughProcess(apply, asset);
        order.verify(deliveryService).enqueueRevoke(same(apply));
        finish(order);
        verifyNoInteractions(notifyHelper);
        // 内存对象已赋值不等于数据库已提交；事务回滚由真实 PostgreSQL 用例证明。
        assertOnlyRevocationChanged(before, apply, NOTE);
    }

    @Test
    void mockedNotificationFailureOccursAfterProcessAndRevokeWithoutRetry() {
        var apply = application(1, 0, 3L);
        var asset = asset();
        stubRead(apply, asset);
        String before = JsonUtils.toJsonString(apply);
        stubSuccessfulWrites(apply, before, NOTE);
        var failure = new IllegalStateException("test notification collaborator failure");
        doAnswer(invocation -> {
            assertOnlyRevocationChanged(before, apply, NOTE);
            throw failure;
        }).when(notifyHelper).notifyApplyRevoked(same(apply), same(asset));

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.revokeApply(APPLY_ID, NOTE)));

        verifySuccess(apply, asset);
        assertOnlyRevocationChanged(before, apply, NOTE);
        // 不以 Mock 抛异常推断真实 helper 的提交后/尽力发送异常契约。
    }

    private void stubRead(MarketResourceApplyDO apply, DataAssetDO asset) {
        when(applyMapper.selectById(APPLY_ID)).thenReturn(apply);
        if (apply.getAssetId() != null) when(assetMapper.selectById(apply.getAssetId())).thenReturn(asset);
    }

    private void stubSuccessfulWrites(MarketResourceApplyDO apply, String before, String note) {
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenAnswer(invocation -> {
            assertEquals(before, JsonUtils.toJsonString(apply), "不能在 CAS 成功前修改观察对象");
            return 1;
        });
        when(objectGuard.requireUserId()).thenReturn(ACTOR_ID);
        when(processMapper.insert(any(MarketCheckProcessDO.class))).thenAnswer(invocation -> {
            assertEquals(before, JsonUtils.toJsonString(apply), "过程成功前不能修改状态、授权或意见");
            assertProcess(invocation.getArgument(0), note);
            return 1;
        });
    }

    private void observeCurrentStateAtDeliveryAndNotification(MarketResourceApplyDO apply, DataAssetDO asset,
                                                             String before, String note) {
        // 在协作者被调用的当下检查，避免只捕获事后已变化的可变 DO 产生假阳性。
        doAnswer(invocation -> {
            assertOnlyRevocationChanged(before, apply, note);
            return null;
        }).when(deliveryService).enqueueRevoke(same(apply));
        doAnswer(invocation -> {
            assertOnlyRevocationChanged(before, apply, note);
            return null;
        }).when(notifyHelper).notifyApplyRevoked(same(apply), same(asset));
    }

    private InOrder verifyRead(MarketResourceApplyDO apply, DataAssetDO asset) {
        InOrder order = inOrder(applyMapper, assetMapper, objectGuard, processMapper, deliveryService, notifyHelper);
        order.verify(applyMapper).selectById(APPLY_ID);
        if (apply.getAssetId() != null) order.verify(assetMapper).selectById(apply.getAssetId());
        order.verify(objectGuard).assertCanAuthApply(same(apply), same(asset));
        return order;
    }

    private InOrder verifyThroughCas(MarketResourceApplyDO apply, DataAssetDO asset) {
        InOrder order = verifyRead(apply, asset);
        order.verify(applyMapper).update(isNull(), any(Wrapper.class));
        // CAS 成功后、写过程前在锁内复验资产归属；CAS 失败时不发生。锁语义见 MarketProviderDecisionSerializationPostgresTest。
        if (mockingDetails(objectGuard).getInvocations().stream()
                .anyMatch(call -> call.getMethod().getName().equals("lockProviderAsset"))) {
            order.verify(objectGuard).lockProviderAsset(same(apply), eq(false));
        }
        return order;
    }

    private InOrder verifyThroughProcess(MarketResourceApplyDO apply, DataAssetDO asset) {
        InOrder order = verifyThroughCas(apply, asset);
        order.verify(objectGuard).requireUserId();
        order.verify(processMapper).insert(any(MarketCheckProcessDO.class));
        return order;
    }

    private void verifySuccess(MarketResourceApplyDO apply, DataAssetDO asset) {
        InOrder order = verifyThroughProcess(apply, asset);
        order.verify(deliveryService).enqueueRevoke(same(apply));
        order.verify(notifyHelper).notifyApplyRevoked(same(apply), same(asset));
        finish(order);
    }

    private void finish(InOrder order) {
        order.verifyNoMoreInteractions();
        // 包括不重试、不回读、不 grant/suspend、不补批准凭证，不改用审批通知。
        verifyNoMoreInteractions(applyMapper, assetMapper, objectGuard, processMapper, deliveryService, notifyHelper);
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<MarketResourceApplyDO> captureCas() {
        ArgumentCaptor<LambdaUpdateWrapper<MarketResourceApplyDO>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(applyMapper).update(isNull(), captor.capture());
        return captor.getValue();
    }

    private static void assertCas(LambdaUpdateWrapper<MarketResourceApplyDO> wrapper, long tenantId,
                                  Integer originalAuth, Integer paused, Long sourceRevision, String note) {
        assertNull(wrapper.getEntity(), "不得整体回写旧实体");
        String predicate = wrapper.getSqlSegment();

        assertFalse(predicate.contains("tenant_id"));
        assertBound(wrapper, predicate, "id", APPLY_ID);
        assertBound(wrapper, predicate, "status", MarketResourceApplyDO.STATUS_APPROVED);
        assertNullableBound(wrapper, predicate, "source_revision", sourceRevision);
        assertNullableBound(wrapper, predicate, "if_auth", originalAuth);
        assertNullableBound(wrapper, predicate, "paused", paused);
        List<String> expected = new ArrayList<>(List.of("id", "status"));
        if (sourceRevision != null) expected.add("source_revision");
        if (originalAuth != null) expected.add("if_auth");
        if (paused != null) expected.add("paused");
        assertEquals(expected.stream().sorted().toList(), assignmentColumns(predicate));
        assertFalse(predicate.contains("apply_end_time"), "收回不是开通，不应新增到期限制");
        assertFalse(predicate.contains("clock_timestamp"));
        assertFalse(Pattern.compile("(?i)\\bOR\\b").matcher(predicate).find());
        String set = wrapper.getSqlSet();
        assertEquals(List.of("check_note", "if_auth", "status"), assignmentColumns(set),
                "CAS 不得改写暂停、来源/内容版本、快照、身份或期限");
        assertBound(wrapper, set, "status", MarketResourceApplyDO.STATUS_REVOKED);
        assertBound(wrapper, set, "if_auth", MarketResourceApplyDO.AUTH_NO);
        assertBound(wrapper, set, "check_note", note);
        assertEquals(expected.size() + 3, wrapper.getParamNameValuePairs().size());
    }

    private static void assertNullableBound(LambdaUpdateWrapper<?> wrapper, String sql, String column, Object value) {
        Pattern nullCheck = Pattern.compile("(?i)\\b" + Pattern.quote(column) + "\\s+IS\\s+NULL\\b");
        if (value == null) {
            assertTrue(nullCheck.matcher(sql).find(), "历史 NULL 必须精确匹配：" + column);
            assertFalse(Pattern.compile("(?i)\\b" + Pattern.quote(column) + "\\s*=").matcher(sql).find());
        } else {
            assertBound(wrapper, sql, column, value);
            assertFalse(nullCheck.matcher(sql).find());
        }
    }

    private static void assertBound(LambdaUpdateWrapper<?> wrapper, String sql, String column, Object expected) {
        var binding = Pattern.compile("(?i)(?<![\\w.])" + Pattern.quote(column)
                + "\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}").matcher(sql);
        assertTrue(binding.find(), "缺少参数绑定 " + column + ": " + sql);
        assertTrue(wrapper.getParamNameValuePairs().containsKey(binding.group(1)));
        assertEquals(expected, wrapper.getParamNameValuePairs().get(binding.group(1)), column);
        assertFalse(binding.find(), column + " 不应重复匹配或设置");
    }

    private static List<String> assignmentColumns(String sql) {
        return Pattern.compile("(?i)(?<![\\w.])([a-z_]+)\\s*=").matcher(sql)
                .results().map(result -> result.group(1)).sorted().toList();
    }

    private static void assertOnlyRevocationChanged(String before, MarketResourceApplyDO apply, String note) {
        Map<?, ?> original = JsonUtils.parseObject(before, Map.class);
        Map<?, ?> current = JsonUtils.parseObject(JsonUtils.toJsonString(apply), Map.class);
        for (String field : List.of("status", "ifAuth", "checkNote")) {
            original.remove(field);
            current.remove(field);
        }
        assertEquals(original, current, "不改写被冻结内容、暂停开关、身份、期限、来源版本或原审批人/时间");
        assertEquals(MarketResourceApplyDO.STATUS_REVOKED, apply.getStatus());
        assertEquals(MarketResourceApplyDO.AUTH_NO, apply.getIfAuth());
        assertEquals(note, apply.getCheckNote(), "通知和交付必须接收本次意见，包括显式 null");
    }

    private static void assertProcess(MarketCheckProcessDO process, String note) {
        assertNull(process.getId());
        assertEquals(APPLY_ID, process.getResourceApplyId());
        assertEquals(MarketCheckProcessDO.ACTION_REVOKE, process.getAction());
        assertEquals(MarketResourceApplyDO.STATUS_APPROVED, process.getFromStatus());
        assertEquals(MarketResourceApplyDO.STATUS_REVOKED, process.getToStatus());
        assertEquals(ACTOR_ID, process.getOperatorUserId(), "操作人不是申请人或原审批人");
        assertEquals(note, process.getNote());
    }

    private static MarketResourceApplyDO application(Integer auth, Integer paused, Long sourceRevision) {
        LocalDateTime start = LocalDateTime.of(2020, 1, 1, 0, 0);
        return MarketResourceApplyDO.builder().id(APPLY_ID).assetId(ASSET_ID).applyUserId(9L).applyDeptId(10L)
                .status(MarketResourceApplyDO.STATUS_APPROVED).ifAuth(auth).paused(paused)
                .sourceRevision(sourceRevision).sourceGrantId("test-revocation-source").revision(3)
                .apiId(3L).appId(2L).snapshot(FROZEN).fieldScope("Id,Name").catalogVersionId(12L)
                .checkUserId(19L).checkTime(start).checkNote("原审核意见")
                .applyStartTime(start).applyEndTime(LocalDateTime.of(2099, 1, 1, 0, 0))
                .receivedTime(start).replyDeadline(start.plusDays(1)).deliveryDeadline(start.plusDays(21))
                .deliveryStatus(0).supplementNote("原补正说明").build();
    }

    private static DataAssetDO asset() {
        return DataAssetDO.builder().id(ASSET_ID).assetType(DataAssetDO.TYPE_DATABASE_TABLE)
                .publishedSnapshotId(11L).publishedCatalogVersionId(12L).publishedVersion("v1")
                .datasourceId(4L).schemaName("public").tableName("citizen").build();
    }

    private static void assertBusinessFailure(ErrorCode code, org.junit.jupiter.api.function.Executable action) {
        ServiceException failure = assertThrows(ServiceException.class, action);
        assertEquals(code.getCode(), failure.getCode());
    }
}
