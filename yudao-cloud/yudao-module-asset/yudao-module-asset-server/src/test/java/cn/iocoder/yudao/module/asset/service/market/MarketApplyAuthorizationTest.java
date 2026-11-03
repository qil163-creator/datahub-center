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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * authApply 的隔离编排契约；使用真实 LambdaUpdateWrapper 和冻结范围校验。
 * Mock Guard 只证明调用边界，不能证明其内部权限规则；Mock Mapper/交付/通知不证明
 * SQL 执行、事务回滚、提交后通知或真实来源版本推进，这些由 PostgreSQL/集成测试验证。
 */
class MarketApplyAuthorizationTest extends BaseMockitoUnitTest {

    private static final long APPLY_ID = 9007199254740993L;
    private static final long ACTOR_ID = 9007199254740995L;
    private static final long ASSET_ID = 5L;
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
    void clearContextAndCheckUnrelatedCollaborators() {
        try {
            // 开通/停用补普通审计由真实 PG 生命周期专测精确核对，本类只检查其余无关协作替身。
            verifyNoInteractions(catalogService, supplyApiGuard,
                    workingDayService, deliveryEvidenceService);
        } finally {
            TenantContextHolder.clear();
        }
    }

    @ParameterizedTest
    @CsvSource(value = {
            "1,0,0,0,1", "0,1,0,5,1", "1,0,1,5,1", "0,1,1,5,1",
            "1,NULL,NULL,NULL,1", "0,NULL,NULL,NULL,1",
            "1,0,NULL,9007199254740993,0", "0,1,NULL,9007199254740993,0",
            "1,0,2,9223372036854775807,9223372036854775807",
            "0,1,2,9223372036854775807,9223372036854775807"
    }, nullValues = "NULL")
    void changesOnlyAuthorizationAfterProcessAndPreservesExactLifecycleCas(
            int target, Integer originalAuth, Integer paused, Long sourceRevision, long tenantId) {
        TenantContextHolder.clear();
        var apply = application(originalAuth, paused, sourceRevision);
        var asset = asset();
        stubRead(apply, asset);
        String before = JsonUtils.toJsonString(apply);
        stubSuccessfulWrites(apply, target, before);
        boolean grant = target == 1 && !Objects.equals(paused, 1);
        // 在实际调用时观察状态，不能只捕获之后已被修改的可变 DO。
        if (grant) {
            doAnswer(invocation -> {
                assertOnlyAuthChanged(before, apply, target);
                return null;
            }).when(deliveryService).enqueueGrant(same(apply));
        } else {
            doAnswer(invocation -> {
                assertOnlyAuthChanged(before, apply, target);
                return null;
            }).when(deliveryService).enqueueSuspend(same(apply));
        }
        doAnswer(invocation -> {
            assertOnlyAuthChanged(before, apply, target);
            return null;
        }).when(notifyHelper).notifyApplyChecked(same(apply), same(asset), eq(note(target)));

        service.authApply(APPLY_ID, target);

        assertCas(captureCas(), tenantId, originalAuth, paused, sourceRevision, target);
        verifySuccess(apply, asset, target);
        assertOnlyAuthChanged(before, apply, target);
        assertNull(TenantContextHolder.getTenantId());
        assertFalse(TenantContextHolder.isIgnore());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1, 2, Integer.MAX_VALUE})
    void everyNonOneInputRetainsLegacyDisableSemantics(Integer input) {
        var apply = application(1, 0, 7L);
        var asset = asset();
        stubRead(apply, asset);
        String before = JsonUtils.toJsonString(apply);
        stubSuccessfulWrites(apply, 0, before);

        service.authApply(APPLY_ID, input);

        assertCas(captureCas(), 1L, 1, 0, 7L, 0);
        verifySuccess(apply, asset, 0);
        assertOnlyAuthChanged(before, apply, 0);
    }

    @ParameterizedTest
    @CsvSource(value = {"1,0", "1,1", "0,1", "0,NULL"}, nullValues = "NULL")
    void sameTargetIsNoOpAfterPermissionAndKeepsAllStoredContent(int target, Integer paused) {
        var apply = application(target, paused, null);
        if (target == 0) {
            apply.setSnapshot(null); // 停用的幂等重试不要求修复历史冻结证据。
        }
        var asset = asset();
        stubRead(apply, asset);
        String before = JsonUtils.toJsonString(apply);

        service.authApply(APPLY_ID, target);

        verifyReadOnly(apply, asset);
        assertEquals(before, JsonUtils.toJsonString(apply));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void sameTargetDoesNotBypassPermission(int target) {
        var apply = application(target, 0, 2L);
        var asset = asset();
        stubRead(apply, asset);
        var failure = exception(MARKET_APPLY_FORBIDDEN);
        doThrow(failure).when(objectGuard).assertCanAuthApply(same(apply), same(asset));

        assertSame(failure, assertThrows(ServiceException.class, () -> service.authApply(APPLY_ID, target)));

        verifyReadOnly(apply, asset);
        assertEquals(target, apply.getIfAuth());
    }

    @ParameterizedTest
    @CsvSource({"0,MISSING", "1,MISSING", "0,WILDCARD", "1,WILDCARD",
            "0,VERSION", "1,VERSION", "0,ASSET", "1,ASSET"})
    void enableChecksFrozenScopeEvenWhenAlreadyEnabled(int originalAuth, String defect) {
        var apply = application(originalAuth, 0, 3L);
        DataAssetDO asset = asset();
        switch (defect) {
            case "MISSING" -> apply.setSnapshot(null);
            case "WILDCARD" -> apply.setSnapshot(FROZEN.replace("[\"Id\",\"Name\"]", "[\"*\"]"));
            case "VERSION" -> asset.setPublishedVersion("v2");
            case "ASSET" -> asset = null;
            default -> throw new AssertionError(defect);
        }
        stubRead(apply, asset);
        String before = JsonUtils.toJsonString(apply);

        assertBusinessFailure(MARKET_APPLY_SCOPE_UNCONFIRMED, () -> service.authApply(APPLY_ID, 1));

        verifyReadOnly(apply, asset);
        assertEquals(before, JsonUtils.toJsonString(apply));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING_SNAPSHOT", "CHANGED_VERSION", "MISSING_ASSET_ID"})
    void disableCanSuspendLegacyOrChangedScopeWithoutRefreezing(String defect) {
        var apply = application(1, 1, null);
        DataAssetDO asset = asset();
        switch (defect) {
            case "MISSING_SNAPSHOT" -> apply.setSnapshot(null);
            case "CHANGED_VERSION" -> asset.setPublishedVersion("v2");
            case "MISSING_ASSET_ID" -> {
                apply.setAssetId(null);
                apply.setSnapshot("legacy invalid json");
                asset = null;
            }
            default -> throw new AssertionError(defect);
        }
        stubRead(apply, asset);
        String before = JsonUtils.toJsonString(apply);
        stubSuccessfulWrites(apply, 0, before);

        service.authApply(APPLY_ID, 0);

        assertCas(captureCas(), 1L, 1, 1, null, 0);
        verifySuccess(apply, asset, 0);
        assertOnlyAuthChanged(before, apply, 0);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, 1, 3, 4, 5, 8})
    void nullAndUnapprovedStatesAreBusinessFailuresNotNullPointerFailures(Integer status) {
        var apply = application(0, 0, 2L);
        apply.setStatus(status);
        var asset = asset();
        stubRead(apply, asset);
        String before = JsonUtils.toJsonString(apply);

        assertBusinessFailure(MARKET_APPLY_STATUS_DENY, () -> service.authApply(APPLY_ID, 1));

        verifyReadOnly(apply, asset);
        assertEquals(before, JsonUtils.toJsonString(apply));
    }



    private static Stream<Arguments> untrustedTenants() {
        return Stream.of(Arguments.of(null, false, 1), Arguments.of(-1L, false, 0),
                Arguments.of(Long.MIN_VALUE, false, 1), Arguments.of(1L, true, 0),
                Arguments.of(0L, true, 1));
    }

    @ParameterizedTest
    @CsvSource({"1,0", "0,0", "1,2", "0,2", "1,-1", "0,-1"})
    void casMustAffectExactlyOneRowAndDoesNotRetry(int target, int rows) {
        var apply = application(1 - target, 0, 3L);
        var asset = asset();
        stubRead(apply, asset);
        String before = JsonUtils.toJsonString(apply);
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(rows);

        assertBusinessFailure(MARKET_APPLY_STATUS_DENY, () -> service.authApply(APPLY_ID, target));

        assertCas(captureCas(), 1L, 1 - target, 0, 3L, target);
        finish(verifyThroughCas(apply, asset));
        verifyNoInteractions(processMapper, deliveryService, notifyHelper);
        assertEquals(before, JsonUtils.toJsonString(apply));
    }

    @ParameterizedTest
    @CsvSource({"1,0", "0,0", "1,2", "0,2", "1,-1", "0,-1"})
    void processMustInsertOneRowBeforeChangingObjectOrProducingSideEffects(int target, int rows) {
        var apply = application(1 - target, 0, 3L);
        var asset = asset();
        stubRead(apply, asset);
        String before = JsonUtils.toJsonString(apply);
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(objectGuard.requireUserId()).thenReturn(ACTOR_ID);
        when(processMapper.insert(any(MarketCheckProcessDO.class))).thenAnswer(invocation -> {
            assertEquals(before, JsonUtils.toJsonString(apply), "写过程时仍须保持观察对象的旧授权值");
            assertProcess(invocation.getArgument(0), target);
            return rows;
        });

        assertBusinessFailure(MARKET_APPLY_STATUS_DENY, () -> service.authApply(APPLY_ID, target));

        assertCas(captureCas(), 1L, 1 - target, 0, 3L, target);
        finish(verifyThroughProcess(apply, asset));
        verifyNoInteractions(deliveryService, notifyHelper);
        assertEquals(before, JsonUtils.toJsonString(apply));
    }

    @ParameterizedTest
    @CsvSource({"1,CAS", "0,CAS", "1,PROCESS", "0,PROCESS"})
    void mapperFailureIsPreservedWithoutRetryOrInMemoryMutation(int target, String stage) {
        var apply = application(1 - target, null, null);
        var asset = asset();
        stubRead(apply, asset);
        String before = JsonUtils.toJsonString(apply);
        RuntimeException failure = stage.equals("CAS")
                ? new CannotAcquireLockException("test authorization lock failure")
                : new DuplicateKeyException("test authorization process constraint failure");
        if (stage.equals("CAS")) {
            when(applyMapper.update(isNull(), any(Wrapper.class))).thenThrow(failure);
        } else {
            when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
            when(objectGuard.requireUserId()).thenReturn(ACTOR_ID);
            when(processMapper.insert(any(MarketCheckProcessDO.class))).thenThrow(failure);
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.authApply(APPLY_ID, target)));

        assertCas(captureCas(), 1L, 1 - target, null, null, target);
        if (stage.equals("PROCESS")) {
            finish(verifyThroughProcess(apply, asset));
        } else {
            finish(verifyThroughCas(apply, asset));
            verifyNoInteractions(processMapper);
        }
        verifyNoInteractions(deliveryService, notifyHelper);
        assertEquals(before, JsonUtils.toJsonString(apply));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PERMISSION", "ACTOR"})
    void guardFailureDoesNotFallBackToApplicantOrPerformLaterSteps(String stage) {
        var apply = application(0, 0, 2L);
        var asset = asset();
        stubRead(apply, asset);
        String before = JsonUtils.toJsonString(apply);
        RuntimeException failure = new IllegalStateException("test authorization guard unavailable");
        if (stage.equals("PERMISSION")) {
            doThrow(failure).when(objectGuard).assertCanAuthApply(same(apply), same(asset));
        } else {
            when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
            when(objectGuard.requireUserId()).thenThrow(failure);
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.authApply(APPLY_ID, 1)));

        if (stage.equals("PERMISSION")) {
            verifyReadOnly(apply, asset);
        } else {
            assertCas(captureCas(), 1L, 0, 0, 2L, 1);
            InOrder order = verifyThroughCas(apply, asset);
            order.verify(objectGuard).requireUserId();
            finish(order);
            verifyNoInteractions(processMapper, deliveryService, notifyHelper);
        }
        assertEquals(before, JsonUtils.toJsonString(apply));
    }

    @Test
    void missingApplicationDoesNotResolveAssetOrCheckPermission() {
        when(applyMapper.selectById(APPLY_ID)).thenReturn(null);

        assertBusinessFailure(MARKET_APPLY_NOT_EXISTS, () -> service.authApply(APPLY_ID, 1));

        verify(applyMapper).selectById(APPLY_ID);
        verifyNoMoreInteractions(applyMapper);
        verifyNoInteractions(assetMapper, objectGuard, processMapper, deliveryService, notifyHelper);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void deliveryFailurePreventsNotificationAndNeverFallsBackToRevokeOrApproval(int target) {
        var apply = application(1 - target, 0, 3L);
        var asset = asset();
        stubRead(apply, asset);
        String before = JsonUtils.toJsonString(apply);
        stubSuccessfulWrites(apply, target, before);
        RuntimeException failure = new IllegalStateException("test authorization delivery failure");
        if (target == 1) {
            doThrow(failure).when(deliveryService).enqueueGrant(same(apply));
        } else {
            doThrow(failure).when(deliveryService).enqueueSuspend(same(apply));
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.authApply(APPLY_ID, target)));

        InOrder order = verifyThroughProcess(apply, asset);
        verifyDelivery(order, apply, target);
        finish(order);
        verifyNoInteractions(notifyHelper);
        assertOnlyAuthChanged(before, apply, target); // 内存赋值已发生；不能据此推断数据库提交。
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void mockedNotificationFailureComesAfterProcessAndDeliveryWithoutAnotherAttempt(int target) {
        var apply = application(1 - target, 0, 3L);
        var asset = asset();
        stubRead(apply, asset);
        String before = JsonUtils.toJsonString(apply);
        stubSuccessfulWrites(apply, target, before);
        RuntimeException failure = new IllegalStateException("test notification collaborator failure");
        // 这是服务编排边界测试，不代表真实通知 helper 的提交后/尽力发送异常语义。
        doThrow(failure).when(notifyHelper).notifyApplyChecked(same(apply), same(asset), eq(note(target)));

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.authApply(APPLY_ID, target)));

        verifySuccess(apply, asset, target);
        assertOnlyAuthChanged(before, apply, target);
    }

    private void stubRead(MarketResourceApplyDO apply, DataAssetDO asset) {
        when(applyMapper.selectById(APPLY_ID)).thenReturn(apply);
        if (apply.getAssetId() != null) {
            when(assetMapper.selectById(apply.getAssetId())).thenReturn(asset);
        }
    }

    private void stubSuccessfulWrites(MarketResourceApplyDO apply, int target, String before) {
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(objectGuard.requireUserId()).thenReturn(ACTOR_ID);
        when(processMapper.insert(any(MarketCheckProcessDO.class))).thenAnswer(invocation -> {
            assertEquals(before, JsonUtils.toJsonString(apply), "过程持久化成功之前不能修改内存授权状态");
            assertProcess(invocation.getArgument(0), target);
            return 1;
        });
    }

    private InOrder verifyRead(MarketResourceApplyDO apply, DataAssetDO asset) {
        InOrder order = inOrder(applyMapper, assetMapper, objectGuard, processMapper, deliveryService, notifyHelper);
        order.verify(applyMapper).selectById(APPLY_ID);
        if (apply.getAssetId() != null) {
            order.verify(assetMapper).selectById(apply.getAssetId());
        }
        order.verify(objectGuard).assertCanAuthApply(same(apply), same(asset));
        return order;
    }

    private void verifyReadOnly(MarketResourceApplyDO apply, DataAssetDO asset) {
        finish(verifyRead(apply, asset));
        verifyNoInteractions(processMapper, deliveryService, notifyHelper);
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

    private void verifySuccess(MarketResourceApplyDO apply, DataAssetDO asset, int target) {
        InOrder order = verifyThroughProcess(apply, asset);
        verifyDelivery(order, apply, target);
        order.verify(notifyHelper).notifyApplyChecked(same(apply), same(asset), eq(note(target)));
        finish(order);
    }

    private void verifyDelivery(InOrder order, MarketResourceApplyDO apply, int target) {
        if (target == 1 && !Objects.equals(apply.getPaused(), 1)) {
            order.verify(deliveryService).enqueueGrant(same(apply));
        } else {
            order.verify(deliveryService).enqueueSuspend(same(apply));
        }
    }

    private void finish(InOrder order) {
        order.verifyNoMoreInteractions();
        // 包括不调用 enqueueRevoke / enqueueApprovedGrant、不回读、不重试、不补批准凭证。
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
                                  Integer originalAuth, Integer paused, Long sourceRevision, int target) {
        assertNull(wrapper.getEntity(), "仅通过列 CAS 更新，不能回写整个旧实体");
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
        if (target == 1) {
            assertTrue(predicate.contains("(apply_end_time IS NULL OR apply_end_time > "
                    + "clock_timestamp() AT TIME ZONE 'Asia/Shanghai')"), "到期限制由数据库时间参与同次 CAS");
        } else {
            assertFalse(predicate.contains("apply_end_time"), "停用过期申请不能被启用专属时间条件阻断");
            assertFalse(predicate.contains("clock_timestamp"));
            assertFalse(Pattern.compile("(?i)\\bOR\\b").matcher(predicate).find());
        }
        String set = wrapper.getSqlSet();
        assertEquals(List.of("if_auth"), assignmentColumns(set), "不递增来源/内容版本或改写暂停、快照、审核字段");
        assertBound(wrapper, set, "if_auth", target);
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

    private static void assertOnlyAuthChanged(String before, MarketResourceApplyDO apply, int target) {
        Map<?, ?> original = JsonUtils.parseObject(before, Map.class);
        Map<?, ?> current = JsonUtils.parseObject(JsonUtils.toJsonString(apply), Map.class);
        original.remove("ifAuth");
        current.remove("ifAuth");
        assertEquals(original, current, "授权切换不改写冻结内容、身份、期限、审核意见或来源版本");
        assertEquals(target, apply.getIfAuth());
    }

    private static void assertProcess(MarketCheckProcessDO process, int target) {
        assertNull(process.getId());
        assertEquals(APPLY_ID, process.getResourceApplyId());
        assertEquals(target == 1 ? MarketCheckProcessDO.ACTION_AUTH : MarketCheckProcessDO.ACTION_UNAUTH,
                process.getAction());
        assertEquals(MarketResourceApplyDO.STATUS_APPROVED, process.getFromStatus());
        assertEquals(MarketResourceApplyDO.STATUS_APPROVED, process.getToStatus());
        assertEquals(ACTOR_ID, process.getOperatorUserId(), "操作人不能替换为申请人或原审批人");
        assertEquals(note(target), process.getNote());
    }

    private static String note(int target) {
        return target == 1 ? "开通授权" : "取消授权";
    }

    private static MarketResourceApplyDO application(Integer auth, Integer paused, Long sourceRevision) {
        LocalDateTime start = LocalDateTime.of(2020, 1, 1, 0, 0);
        return MarketResourceApplyDO.builder().id(APPLY_ID).assetId(ASSET_ID).applyUserId(9L).applyDeptId(10L)
                .status(MarketResourceApplyDO.STATUS_APPROVED).ifAuth(auth).paused(paused)
                .sourceRevision(sourceRevision).sourceGrantId("test-authorization-source").revision(3)
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
