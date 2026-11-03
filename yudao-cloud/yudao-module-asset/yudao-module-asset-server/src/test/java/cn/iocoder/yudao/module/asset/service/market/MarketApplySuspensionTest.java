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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
 * 暂停/恢复的隔离编排契约：复用 BaseMockitoUnitTest、真实 LambdaUpdateWrapper 与冻结范围校验。
 * Mock Guard 仅验证权限入口没有被绕过；Mock Mapper 不执行 SQL，也不证明行锁、租户过滤或回滚。
 * 事务注解检查只验证声明，真实代理事务、数据库时钟和持久交付由 PostgreSQL 用例验证；
 * 普通审计仍为替身，不能据此证明审计强持久性。
 */
class MarketApplySuspensionTest extends BaseMockitoUnitTest {

    private static final long APPLY_ID = 9007199254740993L;
    private static final long ACTOR_ID = 9007199254740995L;
    private static final long ASSET_ID = 5L;
    private static final String NOTE = "本次暂停意见'; --";
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
    private MarketAuditRecorder auditRecorder;
    @Mock
    private MarketNotifyHelper notifyHelper;
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
    void establishTrustedTenant() {
        TenantContextHolder.clear();
        TenantContextHolder.clear();
    }

    @AfterEach
    void keepBoundaryAndClearContext() {
        try {
            verifyNoInteractions(notifyHelper, catalogService, supplyApiGuard,
                    workingDayService, deliveryEvidenceService, assetMonitorService);
        } finally {
            TenantContextHolder.clear();
        }
    }

    @ParameterizedTest
    @CsvSource(value = {
            "false,1,0,0,1", "false,0,NULL,NULL,0",
            "false,NULL,2,9223372036854775807,9223372036854775807",
            "false,2,0,9007199254740993,9007199254740993",
            "true,1,1,3,1", "true,0,1,NULL,1",
            "true,NULL,1,9223372036854775807,1", "true,2,1,9007199254740993,1"
    }, nullValues = "NULL")
    void onlyPausedChangesAfterProcessAndDeliveryRespectsBothSwitches(
            boolean resume, Integer auth, Integer paused, Long revision, long tenant) {
        TenantContextHolder.clear();
        var apply = application(resume, auth, paused, revision);
        var asset = asset();
        stubRead(apply, asset);
        String before = JsonUtils.toJsonString(apply);
        successfulWrites(resume, apply, before);
        observeDelivery(resume, apply, before);

        invoke(resume);

        assertCas(captureCas(), resume, tenant, auth, paused, revision);
        verifySuccess(resume, apply, asset);
        assertOnlyPausedChanged(before, apply, resume);
        assertNull(TenantContextHolder.getTenantId());
        assertFalse(TenantContextHolder.isIgnore());
    }



    private static Stream<Arguments> untrustedTenants() {
        return Stream.of(false, true).flatMap(resume -> Stream.of(
                Arguments.of(resume, null, false), Arguments.of(resume, -1L, false),
                Arguments.of(resume, 1L, true)));
    }

    @ParameterizedTest
    @CsvSource(value = {
            "false,NULL,0", "true,NULL,1", "false,2,1", "true,2,0",
            "false,3,0", "true,6,1"
    }, nullValues = "NULL")
    void nullOrNonApprovedStatusAndRepeatedTargetStateAreRejectedBeforeCas(
            boolean resume, Integer status, Integer paused) {
        var apply = application(resume, 1, paused, 3L);
        apply.setStatus(status);
        var asset = asset();
        stubRead(apply, asset);
        String before = JsonUtils.toJsonString(apply);

        assertBusinessFailure(MARKET_APPLY_STATUS_DENY, () -> invoke(resume));

        finish(verifyRead(apply, asset));
        verifyNoInteractions(processMapper, deliveryService, auditRecorder);
        assertEquals(before, JsonUtils.toJsonString(apply));
    }

    @ParameterizedTest
    @CsvSource({"false,0", "false,2", "false,-1", "true,0", "true,2", "true,-1"})
    void casMustAffectExactlyOneRowWithoutRetryOrDownstreamCalls(boolean resume, int rows) {
        var apply = application(resume, null, resume ? 1 : null, null);
        var asset = asset();
        stubRead(apply, asset);
        String before = JsonUtils.toJsonString(apply);
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(rows);

        assertBusinessFailure(MARKET_APPLY_STATUS_DENY, () -> invoke(resume));

        assertCas(captureCas(), resume, 1L, null, resume ? 1 : null, null);
        finish(verifyThroughCas(apply, asset));
        verifyNoInteractions(processMapper, deliveryService, auditRecorder);
        assertEquals(before, JsonUtils.toJsonString(apply));
    }

    @ParameterizedTest
    @CsvSource({"false,0", "false,2", "false,-1", "true,0", "true,2", "true,-1"})
    void processMustInsertExactlyOneRowBeforeMutationDeliveryOrAudit(boolean resume, int rows) {
        var apply = application(resume, 1, resume ? 1 : 0, 3L);
        var asset = asset();
        stubRead(apply, asset);
        String before = JsonUtils.toJsonString(apply);
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(objectGuard.requireUserId()).thenReturn(ACTOR_ID);
        when(processMapper.insert(any(MarketCheckProcessDO.class))).thenAnswer(invocation -> {
            assertEquals(before, JsonUtils.toJsonString(apply), "过程成功前不能修改原观察对象");
            assertProcess(invocation.getArgument(0), resume);
            return rows;
        });

        assertBusinessFailure(MARKET_APPLY_STATUS_DENY, () -> invoke(resume));

        finish(verifyThroughProcess(apply, asset));
        verifyNoInteractions(deliveryService, auditRecorder);
        assertEquals(before, JsonUtils.toJsonString(apply));
    }

    @ParameterizedTest
    @CsvSource({"false,CAS", "true,CAS", "false,PROCESS", "true,PROCESS"})
    void mapperExceptionIsPreservedWithoutMutationDeliveryAuditOrRetry(boolean resume, String stage) {
        var apply = application(resume, 1, resume ? 1 : 0, 3L);
        var asset = asset();
        stubRead(apply, asset);
        String before = JsonUtils.toJsonString(apply);
        RuntimeException failure = stage.equals("CAS")
                ? new CannotAcquireLockException("test suspension lock failure")
                : new DuplicateKeyException("test suspension process constraint failure");
        if (stage.equals("CAS")) {
            when(applyMapper.update(isNull(), any(Wrapper.class))).thenThrow(failure);
        } else {
            when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
            when(objectGuard.requireUserId()).thenReturn(ACTOR_ID);
            when(processMapper.insert(any(MarketCheckProcessDO.class))).thenAnswer(invocation -> {
                assertEquals(before, JsonUtils.toJsonString(apply));
                assertProcess(invocation.getArgument(0), resume);
                throw failure;
            });
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> invoke(resume)));

        if (stage.equals("CAS")) {
            finish(verifyThroughCas(apply, asset));
            verifyNoInteractions(processMapper);
        } else {
            finish(verifyThroughProcess(apply, asset));
        }
        verifyNoInteractions(deliveryService, auditRecorder);
        assertEquals(before, JsonUtils.toJsonString(apply));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void permissionFailureStopsBeforeCasAndDoesNotFallbackToApplicant(boolean resume) {
        var apply = application(resume, 1, resume ? 1 : 0, 3L);
        var asset = asset();
        stubRead(apply, asset);
        String before = JsonUtils.toJsonString(apply);
        var failure = exception(MARKET_APPLY_FORBIDDEN);
        doThrow(failure).when(objectGuard).assertCanAuthApply(same(apply), same(asset));

        assertSame(failure, assertThrows(ServiceException.class, () -> invoke(resume)));

        finish(verifyRead(apply, asset));
        verifyNoInteractions(processMapper, deliveryService, auditRecorder);
        assertEquals(before, JsonUtils.toJsonString(apply));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingActorAfterCasDoesNotMutateTheObjectOrFallbackToAnOldActor(boolean resume) {
        var apply = application(resume, 1, resume ? 1 : 0, 3L);
        var asset = asset();
        stubRead(apply, asset);
        String before = JsonUtils.toJsonString(apply);
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        var failure = exception(MARKET_APPLY_FORBIDDEN);
        when(objectGuard.requireUserId()).thenThrow(failure);

        assertSame(failure, assertThrows(ServiceException.class, () -> invoke(resume)));

        InOrder order = verifyThroughCas(apply, asset);
        order.verify(objectGuard).requireUserId();
        finish(order);
        verifyNoInteractions(processMapper, deliveryService, auditRecorder);
        assertEquals(before, JsonUtils.toJsonString(apply));
    }

    @ParameterizedTest
    @CsvSource({"false,INVALID_SNAPSHOT", "true,INVALID_SNAPSHOT",
            "false,MISSING_ASSET", "true,MISSING_ASSET", "false,CHANGED_VERSION", "true,CHANGED_VERSION"})
    void onlyResumeRequiresFrozenScopeMatchingCurrentAsset(boolean resume, String defect) {
        var apply = application(resume, 0, resume ? 1 : 0, 3L);
        DataAssetDO asset = asset();
        switch (defect) {
            case "INVALID_SNAPSHOT" -> apply.setSnapshot("legacy invalid json");
            case "MISSING_ASSET" -> asset = null;
            case "CHANGED_VERSION" -> asset.setPublishedVersion("v2");
            default -> throw new AssertionError(defect);
        }
        stubRead(apply, asset);
        String before = JsonUtils.toJsonString(apply);
        if (resume) {
            // 即使 ifAuth=0、恢复后仍为 SUSPEND，恢复入口也不能跳过冻结范围核验。
            assertBusinessFailure(MARKET_APPLY_SCOPE_UNCONFIRMED, () -> invoke(true));
            finish(verifyRead(apply, asset));
            verifyNoInteractions(processMapper, deliveryService, auditRecorder);
            assertEquals(before, JsonUtils.toJsonString(apply));
        } else {
            // 暂停是收紧权限；过期期限、旧快照或已变化资产不应妨碍暂停。
            successfulWrites(false, apply, before);
            observeDelivery(false, apply, before);
            invoke(false);
            assertCas(captureCas(), false, 1L, 0, 0, 3L);
            verifySuccess(false, apply, asset);
            assertOnlyPausedChanged(before, apply, false);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingApplicationDoesNotResolveAssetOrCheckObjectPermission(boolean resume) {
        when(applyMapper.selectById(APPLY_ID)).thenReturn(null);

        assertBusinessFailure(MARKET_APPLY_NOT_EXISTS, () -> invoke(resume));

        verify(applyMapper).selectById(APPLY_ID);
        verifyNoMoreInteractions(applyMapper);
        verifyNoInteractions(assetMapper, objectGuard, processMapper, deliveryService, auditRecorder);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publicMethodDeclaresBoundedRequiredTransactionWithCheckedExceptionRollback(boolean resume)
            throws NoSuchMethodException {
        var method = resume ? MarketApplyServiceImpl.class.getMethod("resumeApply", Long.class)
                : MarketApplyServiceImpl.class.getMethod("pauseApply", Long.class, String.class);
        var transaction = method.getAnnotation(Transactional.class);

        assertNotNull(transaction);
        assertEquals(Propagation.REQUIRED, transaction.propagation());
        assertEquals(10, transaction.timeout());
        assertFalse(transaction.readOnly());
        assertArrayEquals(new Class<?>[]{Exception.class}, transaction.rollbackFor());
        assertEquals(0, transaction.noRollbackFor().length);
        // 声明不等于运行时事务生效；这里不构造假事务管理器来声称已验证回滚。
        verifyNoInteractions(applyMapper, assetMapper, objectGuard, processMapper, deliveryService, auditRecorder);
    }

    private void invoke(boolean resume) {
        if (resume) service.resumeApply(APPLY_ID);
        else service.pauseApply(APPLY_ID, NOTE);
    }

    private void stubRead(MarketResourceApplyDO apply, DataAssetDO asset) {
        when(applyMapper.selectById(APPLY_ID)).thenReturn(apply);
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset);
    }

    private void successfulWrites(boolean resume, MarketResourceApplyDO apply, String before) {
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenAnswer(invocation -> {
            assertEquals(before, JsonUtils.toJsonString(apply), "CAS 前不应修改旧观察对象");
            return 1;
        });
        when(objectGuard.requireUserId()).thenReturn(ACTOR_ID);
        when(processMapper.insert(any(MarketCheckProcessDO.class))).thenAnswer(invocation -> {
            assertEquals(before, JsonUtils.toJsonString(apply), "过程成功前不能修改暂停开关");
            assertProcess(invocation.getArgument(0), resume);
            return 1;
        });
    }

    private void observeDelivery(boolean resume, MarketResourceApplyDO apply, String before) {
        var observer = doAnswer(invocation -> {
            assertOnlyPausedChanged(before, apply, resume);
            return null;
        });
        if (resume && Integer.valueOf(1).equals(apply.getIfAuth())) {
            observer.when(deliveryService).enqueueGrant(same(apply));
        } else {
            observer.when(deliveryService).enqueueSuspend(same(apply));
        }
    }

    private InOrder verifyRead(MarketResourceApplyDO apply, DataAssetDO asset) {
        InOrder order = inOrder(applyMapper, assetMapper, objectGuard, processMapper, deliveryService, auditRecorder);
        order.verify(applyMapper).selectById(APPLY_ID);
        order.verify(assetMapper).selectById(ASSET_ID);
        order.verify(objectGuard).assertCanAuthApply(same(apply), same(asset));
        return order;
    }

    private InOrder verifyThroughCas(MarketResourceApplyDO apply, DataAssetDO asset) {
        InOrder order = verifyRead(apply, asset);
        order.verify(applyMapper).update(isNull(), any(Wrapper.class));
        // CAS 成功后、写过程前在锁内复验资产归属；CAS 失败时不发生。锁语义见 MarketProviderDecisionSerializationPostgresTest。
        if (mockingDetails(objectGuard).getInvocations().stream()
                .anyMatch(call -> call.getMethod().getName().equals("lockProviderAsset"))) {
            order.verify(objectGuard).lockProviderAsset(same(apply), anyBoolean());
        }
        return order;
    }

    private InOrder verifyThroughProcess(MarketResourceApplyDO apply, DataAssetDO asset) {
        InOrder order = verifyThroughCas(apply, asset);
        order.verify(objectGuard).requireUserId();
        order.verify(processMapper).insert(any(MarketCheckProcessDO.class));
        return order;
    }

    private void verifySuccess(boolean resume, MarketResourceApplyDO apply, DataAssetDO asset) {
        InOrder order = verifyThroughProcess(apply, asset);
        if (resume && Integer.valueOf(1).equals(apply.getIfAuth())) {
            order.verify(deliveryService).enqueueGrant(same(apply));
        } else {
            order.verify(deliveryService).enqueueSuspend(same(apply));
        }
        order.verify(auditRecorder).record(resume ? "RESUME" : "PAUSE", "APPLY", APPLY_ID,
                MarketResourceApplyDO.STATUS_APPROVED, MarketResourceApplyDO.STATUS_APPROVED,
                resume ? null : NOTE, "OK");
        verify(objectGuard).lockProviderAsset(same(apply), eq(resume)); // 只有恢复需在锁内复验冻结绑定
        finish(order);
    }

    private void finish(InOrder order) {
        order.verifyNoMoreInteractions();
        // 无重试、重读、整实体回写、REVOKE、批准凭证补写或额外审计。
        verifyNoMoreInteractions(applyMapper, assetMapper, objectGuard, processMapper, deliveryService, auditRecorder);
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<MarketResourceApplyDO> captureCas() {
        ArgumentCaptor<LambdaUpdateWrapper<MarketResourceApplyDO>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(applyMapper).update(isNull(), captor.capture());
        return captor.getValue();
    }

    private static void assertCas(LambdaUpdateWrapper<MarketResourceApplyDO> wrapper, boolean resume,
                                  long tenant, Integer auth, Integer paused, Long revision) {
        assertNull(wrapper.getEntity(), "不得整体回写旧实体");
        String predicate = wrapper.getSqlSegment();

        assertFalse(predicate.contains("tenant_id"));
        assertBound(wrapper, predicate, "id", APPLY_ID);
        assertBound(wrapper, predicate, "status", MarketResourceApplyDO.STATUS_APPROVED);
        assertNullableBound(wrapper, predicate, "source_revision", revision);
        assertNullableBound(wrapper, predicate, "if_auth", auth);
        assertNullableBound(wrapper, predicate, "paused", paused);
        List<String> expected = new ArrayList<>(List.of("id", "status"));
        if (revision != null) expected.add("source_revision");
        if (auth != null) expected.add("if_auth");
        if (paused != null) expected.add("paused");
        assertEquals(expected.stream().sorted().toList(), assignmentColumns(predicate));
        if (resume) {
            assertTrue(predicate.contains("(apply_end_time IS NULL OR apply_end_time > clock_timestamp()"
                    + " AT TIME ZONE 'Asia/Shanghai')"), "恢复必须由数据库时钟原子校验期限");
        } else {
            assertFalse(predicate.contains("apply_end_time"));
            assertFalse(predicate.contains("clock_timestamp"));
            assertFalse(Pattern.compile("(?i)\\bOR\\b").matcher(predicate).find());
        }
        String set = wrapper.getSqlSet();
        assertEquals(List.of("paused"), assignmentColumns(set), "只允许改 paused，不覆盖授权开关或批准内容");
        assertBound(wrapper, set, "paused", resume ? 0 : 1);
        assertFalse(set.contains(NOTE));
        assertEquals(expected.size() + 1, wrapper.getParamNameValuePairs().size());
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

    private static void assertProcess(MarketCheckProcessDO process, boolean resume) {
        assertNull(process.getId());
        assertEquals(APPLY_ID, process.getResourceApplyId());
        assertEquals(resume ? MarketCheckProcessDO.ACTION_RESUME : MarketCheckProcessDO.ACTION_PAUSE,
                process.getAction());
        assertEquals(MarketResourceApplyDO.STATUS_APPROVED, process.getFromStatus());
        assertEquals(MarketResourceApplyDO.STATUS_APPROVED, process.getToStatus());
        assertEquals(ACTOR_ID, process.getOperatorUserId(), "不能复用申请人或原审批人");
        assertEquals(resume ? "恢复" : NOTE, process.getNote());
    }

    private static void assertOnlyPausedChanged(String before, MarketResourceApplyDO apply, boolean resume) {
        Map<?, ?> original = JsonUtils.parseObject(before, Map.class);
        Map<?, ?> current = JsonUtils.parseObject(JsonUtils.toJsonString(apply), Map.class);
        original.remove("paused");
        current.remove("paused");
        assertEquals(original, current, "状态、授权、来源版本、快照、身份、期限及旧审批信息不应被改写");
        assertEquals(resume ? 0 : 1, apply.getPaused());
    }

    private static MarketResourceApplyDO application(boolean resume, Integer auth, Integer paused, Long revision) {
        LocalDateTime start = LocalDateTime.of(2020, 1, 1, 0, 0);
        return MarketResourceApplyDO.builder().id(APPLY_ID).assetId(ASSET_ID).applyUserId(9L).applyDeptId(10L)
                .status(MarketResourceApplyDO.STATUS_APPROVED).ifAuth(auth).paused(paused)
                .sourceRevision(revision).sourceGrantId("test-suspension-source").revision(3)
                .apiId(3L).appId(2L).snapshot(FROZEN).fieldScope("Id,Name").catalogVersionId(12L)
                .checkUserId(19L).checkTime(start).checkNote("原审核意见")
                .applyStartTime(start).applyEndTime(resume ? start.plusYears(80) : start.plusDays(1))
                .receivedTime(start).replyDeadline(start.plusDays(1)).deliveryDeadline(start.plusDays(21))
                .deliveryStatus(0).supplementNote("原补正说明").build();
    }

    private static DataAssetDO asset() {
        return DataAssetDO.builder().id(ASSET_ID).assetType(DataAssetDO.TYPE_DATABASE_TABLE)
                .publishedSnapshotId(11L).publishedCatalogVersionId(12L).publishedVersion("v1")
                .datasourceId(4L).schemaName("public").tableName("citizen").build();
    }

    private static void assertBusinessFailure(ErrorCode code, org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(ServiceException.class, action);
        assertEquals(code.getCode(), failure.getCode());
    }
}
