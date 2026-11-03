package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
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
 * 申请人撤销的隔离编排契约：申请人、草稿/待审状态、原版本 CAS 和过程行数。
 * 复用 BaseMockitoUnitTest 与真实 LambdaUpdateWrapper，不加载 Spring 事务或数据库。
 * Mock Guard 仅证明登录入口未被绕过；SQL/异常断言不证明数据库执行、隔离或真实回滚。
 */
class MarketApplyCancellationTest extends BaseMockitoUnitTest {

    private static final long APPLY_ID = 77L;
    private static final long OWNER_ID = 11L;
    private static final long ASSET_ID = 8L;

    @InjectMocks
    private MarketApplyServiceImpl service;
    @Mock
    private MarketResourceApplyMapper applyMapper;
    @Mock
    private MarketCheckProcessMapper processMapper;
    @Mock
    private MarketObjectGuard objectGuard;
    @Mock
    private DataAssetMapper assetMapper;
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
    private MarketAuditRecorder auditRecorder;
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
    void preserveCancellationBoundaryAndClearTenant() {
        try {
            // 撤销未批准申请不是撤销授权，不应新增交付、通知或元数据读取；普通审计由真实 PG 专测核对。
            verifyNoInteractions(assetMapper, demandMapper, endpointMapper, displayHelper, accessService,
                    adminUserApi, supplyAppApi, supplyApiGuard, assetMonitorService, notifyHelper,
                    deliveryService, deliveryCoordinator, deliveryEvidenceService, deliveryOperationMapper,
                    workingDayService, catalogService, transactionManager);
        } finally {
            TenantContextHolder.clear();
        }
    }

    @ParameterizedTest
    @CsvSource(value = {
            "0,1,77,11,8,1",
            "1,1,77,11,8,1",
            "0,1,77,11,8,NULL",
            "1,1,77,11,8,NULL",
            "0,1,77,11,8,2147483647",
            "1,1,77,11,8,2147483647",
            "0,0,9007199254740993,9007199254740995,9007199254740997,3",
            "1,9223372036854775807,9223372036854775806,9223372036854775805,9223372036854775804,19"
    }, nullValues = "NULL")
    void successMatchesOriginalTenantIdentityStateAndRevisionWithoutRewritingContent(
            int status, long tenantId, long applyId, long ownerId, long assetId, Integer revision) {
        TenantContextHolder.clear();
        MarketResourceApplyDO observed = application(applyId, ownerId, assetId, status, revision);
        stubOwnedRead(observed);
        stubSuccessfulWrites();
        String original = JsonUtils.toJsonString(observed);

        service.cancelApply(applyId);

        assertCas(captureOnlyCas(applyId), observed, tenantId);
        assertProcess(captureOnlyProcess(), observed);
        verifyThroughProcess(applyId);
        assertEquals(original, JsonUtils.toJsonString(observed), "不改变观察对象、版本、快照、期限或授权字段");
        assertNull(TenantContextHolder.getTenantId());
        assertFalse(TenantContextHolder.isIgnore());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void legacyApplicationCanBeCancelledWithoutRefreezingOrValidatingItsSnapshot(int status) {
        MarketResourceApplyDO observed = application(APPLY_ID, OWNER_ID, ASSET_ID, status, null);
        observed.setSnapshot("legacy snapshot without frozen scope");
        observed.setFieldScope(null);
        stubOwnedRead(observed);
        stubSuccessfulWrites();
        String original = JsonUtils.toJsonString(observed);

        service.cancelApply(APPLY_ID);

        assertCas(captureOnlyCas(APPLY_ID), observed, 1L);
        assertProcess(captureOnlyProcess(), observed);
        verifyThroughProcess(APPLY_ID);
        assertEquals(original, JsonUtils.toJsonString(observed));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {2, 3, 4, 5, 6, 8, 7, 9, -1, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void nullTerminalSupplementAndUnknownStatusesAreBusinessFailuresWithoutWrites(Integer status) {
        MarketResourceApplyDO observed = ownedApplication(status);
        String original = JsonUtils.toJsonString(observed);

        assertBusinessFailure(MARKET_APPLY_STATUS_DENY, () -> service.cancelApply(APPLY_ID));

        verifyReadOnlyFailure();
        assertEquals(original, JsonUtils.toJsonString(observed));
    }

    @ParameterizedTest
    @CsvSource({"0,0", "1,0", "0,-1", "1,-1", "0,-2147483648", "1,-2147483648"})
    void nonPositiveRevisionIsRejectedWithoutCasOrProcess(int status, int revision) {
        MarketResourceApplyDO observed = ownedApplication(status);
        observed.setRevision(revision);
        String original = JsonUtils.toJsonString(observed);

        assertBusinessFailure(MARKET_APPLY_STATUS_DENY, () -> service.cancelApply(APPLY_ID));

        verifyReadOnlyFailure();
        assertEquals(original, JsonUtils.toJsonString(observed));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, 1, 3})
    void anotherApplicantIsForbiddenBeforeStatusValidation(Integer status) {
        MarketResourceApplyDO observed = application(APPLY_ID, OWNER_ID, ASSET_ID, status, 3);
        when(applyMapper.selectById(APPLY_ID)).thenReturn(observed);
        when(objectGuard.requireUserId()).thenReturn(OWNER_ID + 1);
        String original = JsonUtils.toJsonString(observed);

        assertBusinessFailure(MARKET_APPLY_FORBIDDEN, () -> service.cancelApply(APPLY_ID));

        verifyReadOnlyFailure();
        assertEquals(original, JsonUtils.toJsonString(observed));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void missingStoredApplicantDoesNotAdoptCurrentUser(int status) {
        MarketResourceApplyDO observed = ownedApplication(status);
        observed.setApplyUserId(null);

        assertBusinessFailure(MARKET_APPLY_FORBIDDEN, () -> service.cancelApply(APPLY_ID));

        verifyReadOnlyFailure();
        assertNull(observed.getApplyUserId());
    }

    @Test
    void missingApplicationIsRejectedBeforeResolvingApplicant() {
        when(applyMapper.selectById(APPLY_ID)).thenReturn(null);

        assertBusinessFailure(MARKET_APPLY_NOT_EXISTS, () -> service.cancelApply(APPLY_ID));

        verify(applyMapper).selectById(APPLY_ID);
        verifyNoMoreInteractions(applyMapper);
        verifyNoInteractions(objectGuard, processMapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DUPLICATE", "LOCK", "OTHER"})
    void readFailureIsPreservedWithoutImplicitRetryOrWrites(String type) {
        RuntimeException failure = databaseFailure(type);
        when(applyMapper.selectById(APPLY_ID)).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.cancelApply(APPLY_ID)));

        verify(applyMapper).selectById(APPLY_ID);
        verifyNoMoreInteractions(applyMapper);
        verifyNoInteractions(objectGuard, processMapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FORBIDDEN", "UNAVAILABLE"})
    void loginGuardFailureIsPreservedWithoutApplicantFallback(String type) {
        when(applyMapper.selectById(APPLY_ID))
                .thenReturn(application(APPLY_ID, OWNER_ID, ASSET_ID, null, 3));
        RuntimeException failure = guardFailure(type);
        when(objectGuard.requireUserId()).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.cancelApply(APPLY_ID)));

        verifyReadOnlyFailure();
    }

    @ParameterizedTest
    @ValueSource(strings = {"FORBIDDEN", "UNAVAILABLE"})
    void processOperatorResolutionFailureIsNotHiddenOrRetried(String type) {
        MarketResourceApplyDO observed = application(APPLY_ID, OWNER_ID, ASSET_ID, 1, 3);
        when(applyMapper.selectById(APPLY_ID)).thenReturn(observed);
        RuntimeException failure = guardFailure(type);
        when(objectGuard.requireUserId()).thenReturn(OWNER_ID).thenThrow(failure);
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.cancelApply(APPLY_ID)));

        assertCas(captureOnlyCas(APPLY_ID), observed, 1L);
        InOrder order = verifyThroughCas(APPLY_ID);
        order.verify(objectGuard).requireUserId();
        verifyFinished(order);
        verifyNoInteractions(processMapper);
    }

    @ParameterizedTest
    @CsvSource({"0,0", "1,0", "0,2", "1,2", "0,-1", "1,-1"})
    void casMustAffectExactlyOneRowBeforeProcessWithoutRetry(int status, int rows) {
        MarketResourceApplyDO observed = ownedApplication(status);
        String original = JsonUtils.toJsonString(observed);
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(rows);

        assertBusinessFailure(MARKET_APPLY_STATUS_DENY, () -> service.cancelApply(APPLY_ID));

        assertCas(captureOnlyCas(APPLY_ID), observed, 1L);
        verifyFinished(verifyThroughCas(APPLY_ID));
        verifyNoInteractions(processMapper);
        assertEquals(original, JsonUtils.toJsonString(observed));
    }

    @ParameterizedTest
    @CsvSource({"0,0", "1,0", "0,2", "1,2", "0,-1", "1,-1"})
    void processMustInsertExactlyOneRowAndNeverRetries(int status, int rows) {
        MarketResourceApplyDO observed = ownedApplication(status);
        String original = JsonUtils.toJsonString(observed);
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(processMapper.insert(any(MarketCheckProcessDO.class))).thenReturn(rows);

        assertBusinessFailure(MARKET_APPLY_STATUS_DENY, () -> service.cancelApply(APPLY_ID));

        assertCas(captureOnlyCas(APPLY_ID), observed, 1L);
        assertProcess(captureOnlyProcess(), observed);
        verifyThroughProcess(APPLY_ID);
        assertEquals(original, JsonUtils.toJsonString(observed));
    }

    @ParameterizedTest
    @CsvSource({"CAS,DUPLICATE", "CAS,LOCK", "CAS,OTHER",
            "PROCESS,DUPLICATE", "PROCESS,LOCK", "PROCESS,OTHER"})
    void writeFailureIsPreservedWithoutFallbackReReadOrRetry(String stage, String type) {
        MarketResourceApplyDO observed = ownedApplication(MarketResourceApplyDO.STATUS_PENDING);
        String original = JsonUtils.toJsonString(observed);
        RuntimeException failure = databaseFailure(type);
        if ("CAS".equals(stage)) {
            when(applyMapper.update(isNull(), any(Wrapper.class))).thenThrow(failure);
        } else {
            when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
            when(processMapper.insert(any(MarketCheckProcessDO.class))).thenThrow(failure);
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.cancelApply(APPLY_ID)));

        assertCas(captureOnlyCas(APPLY_ID), observed, 1L);
        if ("PROCESS".equals(stage)) {
            assertProcess(captureOnlyProcess(), observed);
            verifyThroughProcess(APPLY_ID);
        } else {
            verifyFinished(verifyThroughCas(APPLY_ID));
            verifyNoInteractions(processMapper);
        }
        assertEquals(original, JsonUtils.toJsonString(observed));
    }



    private static Stream<Arguments> untrustedTenants() {
        return Stream.of(Arguments.of(null, false), Arguments.of(-1L, false),
                Arguments.of(Long.MIN_VALUE, false), Arguments.of(1L, true), Arguments.of(0L, true));
    }

    private MarketResourceApplyDO ownedApplication(Integer status) {
        MarketResourceApplyDO observed = application(APPLY_ID, OWNER_ID, ASSET_ID, status, 3);
        stubOwnedRead(observed);
        return observed;
    }

    private void stubOwnedRead(MarketResourceApplyDO observed) {
        when(applyMapper.selectById(observed.getId())).thenReturn(observed);
        when(objectGuard.requireUserId()).thenReturn(observed.getApplyUserId());
    }

    private void stubSuccessfulWrites() {
        when(applyMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(processMapper.insert(any(MarketCheckProcessDO.class))).thenReturn(1);
    }

    private static MarketResourceApplyDO application(long applyId, long ownerId, long assetId,
                                                     Integer status, Integer revision) {
        LocalDateTime received = LocalDateTime.of(2020, 1, 1, 0, 0);
        return MarketResourceApplyDO.builder().id(applyId).applyUserId(ownerId).assetId(assetId)
                .status(status).revision(revision).snapshot("{\"frozenColumns\":[\"Id\"],\"revision\":3}")
                .fieldScope("Id").catalogVersionId(9007199254740995L).supplementNote("原补正说明")
                .receivedTime(received).replyDeadline(received.plusDays(1)).deliveryDeadline(received.plusDays(21))
                .applyStartTime(received).applyEndTime(received.plusYears(1))
                .checkUserId(19L).checkTime(received.minusDays(1)).checkNote("原审核意见")
                .ifAuth(MarketResourceApplyDO.AUTH_NO).sourceRevision(9L).sourceGrantId("test-cancellation-source")
                .deliveryStatus(0).paused(0).build();
    }

    private static RuntimeException databaseFailure(String type) {
        return switch (type) {
            case "DUPLICATE" -> new DuplicateKeyException("test cancellation constraint failure");
            case "LOCK" -> new CannotAcquireLockException("test cancellation lock failure");
            default -> new IllegalStateException("test cancellation database unavailable");
        };
    }

    private static RuntimeException guardFailure(String type) {
        return "FORBIDDEN".equals(type) ? exception(MARKET_APPLY_FORBIDDEN)
                : new IllegalStateException("test cancellation login guard unavailable");
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

    private InOrder verifyOwnedRead(long applyId) {
        InOrder order = inOrder(applyMapper, objectGuard, processMapper);
        order.verify(applyMapper).selectById(applyId);
        order.verify(objectGuard).requireUserId();
        return order;
    }

    private InOrder verifyThroughCas(long applyId) {
        InOrder order = verifyOwnedRead(applyId);
        order.verify(applyMapper).update(isNull(), any(Wrapper.class));
        return order;
    }

    private void verifyThroughProcess(long applyId) {
        InOrder order = verifyThroughCas(applyId);
        order.verify(objectGuard).requireUserId();
        order.verify(processMapper).insert(any(MarketCheckProcessDO.class));
        verifyFinished(order);
    }

    private void verifyReadOnlyFailure() {
        verifyFinished(verifyOwnedRead(APPLY_ID));
        verifyNoInteractions(processMapper);
    }

    private void verifyFinished(InOrder order) {
        order.verifyNoMoreInteractions();
        verifyNoMoreInteractions(applyMapper, objectGuard, processMapper);
    }

    private static void assertProcess(MarketCheckProcessDO process, MarketResourceApplyDO observed) {
        assertNull(process.getId(), "主键交给既有 Mapper/平台机制");
        assertEquals(observed.getId(), process.getResourceApplyId());
        assertEquals(MarketCheckProcessDO.ACTION_CANCEL, process.getAction());
        assertEquals(observed.getStatus(), process.getFromStatus());
        assertEquals(MarketResourceApplyDO.STATUS_CANCELLED, process.getToStatus());
        assertEquals(observed.getApplyUserId(), process.getOperatorUserId());
        assertEquals("申请人撤销", process.getNote());
    }

    private static void assertCas(LambdaUpdateWrapper<MarketResourceApplyDO> cas,
                                  MarketResourceApplyDO observed, long tenantId) {
        assertNull(cas.getEntity(), "不能通过更新实体附带改写申请内容");
        String predicate = cas.getSqlSegment();

        assertFalse(predicate.contains("tenant_id"));
        assertBound(cas, predicate, "id", observed.getId());
        assertBound(cas, predicate, "status", observed.getStatus());
        assertBound(cas, predicate, "apply_user_id", observed.getApplyUserId());
        assertBound(cas, predicate, "asset_id", observed.getAssetId());
        if (observed.getRevision() == null) {
            assertTrue(Pattern.compile("(?i)\\brevision\\s+IS\\s+NULL\\b").matcher(predicate).find());
            assertFalse(Pattern.compile("(?i)\\brevision\\s*=").matcher(predicate).find());
            assertEquals(List.of("apply_user_id", "asset_id", "id", "status"),
                    assignmentColumns(predicate));
        } else {
            assertBound(cas, predicate, "revision", observed.getRevision());
            assertFalse(Pattern.compile("(?i)\\brevision\\s+IS\\s+NULL\\b").matcher(predicate).find());
            assertEquals(List.of("apply_user_id", "asset_id", "id", "revision", "status"),
                    assignmentColumns(predicate));
        }
        assertFalse(Pattern.compile("(?i)\\bOR\\b").matcher(predicate).find(), "不能用 OR 放宽原版本条件");
        String sqlSet = cas.getSqlSet();
        assertBound(cas, sqlSet, "status", MarketResourceApplyDO.STATUS_CANCELLED);
        assertEquals(List.of("status"), assignmentColumns(sqlSet),
                "只更新状态，不递增版本、不刷新快照/期限、不改授权/交付或审核字段");
        assertEquals(observed.getRevision() == null ? 5 : 6, cas.getParamNameValuePairs().size());
    }

    private static List<String> assignmentColumns(String sql) {
        return Pattern.compile("(?i)(?<![\\w.])([a-z_]+)\\s*=").matcher(sql)
                .results().map(result -> result.group(1)).sorted().toList();
    }

    private static void assertBound(LambdaUpdateWrapper<?> wrapper, String sql, String column, Object expected) {
        var binding = Pattern.compile("(?i)(?<![\\w.])" + Pattern.quote(column)
                + "\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}").matcher(sql);
        assertTrue(binding.find(), "缺少参数绑定 " + column + ": " + sql);
        assertTrue(wrapper.getParamNameValuePairs().containsKey(binding.group(1)));
        assertEquals(expected, wrapper.getParamNameValuePairs().get(binding.group(1)), column);
        assertFalse(binding.find(), column + " 不应重复匹配或设置");
    }

    private static void assertBusinessFailure(ErrorCode code, org.junit.jupiter.api.function.Executable action) {
        ServiceException failure = assertThrows(ServiceException.class, action);
        assertEquals(code.getCode(), failure.getCode());
    }

}
