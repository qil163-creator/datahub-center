package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDemandMatchSaveReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDemandReplySaveReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDemandDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDemandMatchDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDemandReplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDemandMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDemandMatchMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDemandReplyMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

/**
 * 响应/新增匹配的编排契约；只验证入口、字段映射、CAS 谓词和调用次序。
 * Mockito 不证明事务回滚、租户拦截或 PostgreSQL 并发锁行为，须由真实数据库测试补充。
 */
class MarketDemandContributionTest extends BaseMockitoUnitTest {

    private static final Long DEMAND_ID = 1L;
    private static final Long ASSET_ID = 8L;
    private static final Long USER_ID = 11L;
    private static final Long DEPT_ID = 100L;
    private static final Long CHILD_ID = 9_007_199_254_740_993L;
    private static final LocalDateTime ACCEPTED_TIME = LocalDateTime.of(2026, 9, 20, 9, 30);

    @InjectMocks
    private MarketDemandServiceImpl service;
    @Mock
    private MarketDemandMapper demandMapper;
    @Mock
    private MarketDemandReplyMapper replyMapper;
    @Mock
    private MarketDemandMatchMapper matchMapper;
    @Mock
    private DataAssetMapper assetMapper;
    @Mock
    private MarketObjectGuard objectGuard;
    @Mock
    private MarketNotifyHelper notifyHelper;
    @Mock
    private MarketApplyService applyService;
    @Mock
    private MarketResourceApplyMapper applyMapper;
    @Mock
    private MarketDeliveryService deliveryService;
    @Mock
    private MarketAccessService accessService;
    @Mock
    private MarketResourceService resourceService;
    @Mock
    private MarketDisplayHelper displayHelper;
    @Mock
    private AdminUserApi adminUserApi;
    private MockedStatic<SecurityFrameworkUtils> security;
    private DataAssetDO asset;
    private DataAssetDO currentAsset;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                MarketDemandDO.class);
    }

    @BeforeEach
    void setup() {
        TenantContextHolder.clear();
        TenantContextHolder.clear();
        security = mockStatic(SecurityFrameworkUtils.class);
        // Long 的 Mockito 默认值可能是 0，必须明确请求的登录用户及部门。
        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(USER_ID);
        security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(DEPT_ID);
        // 状态/资产前置失败应在读取身份前结束。
        lenient().when(objectGuard.requireUserId()).thenReturn(USER_ID);
    }

    @AfterEach
    void cleanup() {
        try {
            verifyNoInteractions(applyService, applyMapper, deliveryService, accessService,
                    resourceService, displayHelper, adminUserApi);
        } finally {
            try {
                if (security != null) {
                    security.close();
                }
            } finally {
                TenantContextHolder.clear();
            }
        }
    }

    @ParameterizedTest(name = "{0} accepts status {1}")
    @MethodSource("openStates")
    void insertsThenComparesOriginalDemandThenNotifies(Contribution contribution, int status) {
        MarketDemandDO original = prepare(contribution, status);
        String before = original.toString();
        allowInsert(contribution, 1);
        allowDemandUpdate(1);

        assertEquals(CHILD_ID, invoke(contribution));

        assertWriteOrder(contribution, original, true);
        assertEquals(before, original.toString(), "不得把初读需求快照改成目标状态或回写子记录信息");
    }

    static Stream<Arguments> openStates() {
        return Stream.of(Contribution.values()).flatMap(contribution -> Stream.of(1, 2, 3, 7)
                .map(status -> Arguments.of(contribution, status)));
    }

    @ParameterizedTest(name = "{0} rejects status {1}")
    @MethodSource("closedStates")
    void rejectsNullDraftTerminalAndUnknownBeforeAnyContribution(Contribution contribution, Integer status) {
        load(status);

        assertError(MARKET_DEMAND_STATUS_DENY, () -> invoke(contribution));

        verifyDemandReadOnly();
        verifyNoInteractions(objectGuard, assetMapper, matchMapper, replyMapper, notifyHelper);
    }

    static Stream<Arguments> closedStates() {
        return Stream.of(Contribution.values()).flatMap(contribution -> Stream.of(null, -1, 0, 4, 5, 6, 99)
                .map(status -> Arguments.of(contribution, status)));
    }

    @ParameterizedTest
    @EnumSource(Contribution.class)
    void missingDemandKeepsOriginalErrorAndStops(Contribution contribution) {
        assertError(MARKET_DEMAND_NOT_EXISTS, () -> invoke(contribution));

        verifyDemandReadOnly();
        verifyNoInteractions(objectGuard, assetMapper, matchMapper, replyMapper, notifyHelper);
    }

    @ParameterizedTest
    @EnumSource(Contribution.class)
    void demandReadFailurePropagatesWithoutRetry(Contribution contribution) {
        var failure = new IllegalStateException("test demand read unavailable");
        when(demandMapper.selectById(DEMAND_ID)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> invoke(contribution)));

        verifyDemandReadOnly();
        verifyNoInteractions(objectGuard, assetMapper, matchMapper, replyMapper, notifyHelper);
    }

    @ParameterizedTest
    @EnumSource(Contribution.class)
    void identityFailureStopsBeforeChildInsert(Contribution contribution) {
        prepare(contribution, MarketDemandDO.STATUS_MATCHING);
        ServiceException failure = exception(MARKET_APPLY_FORBIDDEN);
        when(objectGuard.requireUserId()).thenThrow(failure);

        assertSame(failure, assertThrows(ServiceException.class, () -> invoke(contribution)));

        verifyPrerequisites(contribution, ordered());
        verifyNoMoreContributionInteractions();
    }

    @ParameterizedTest(name = "{0} guards null snapshot {1}")
    @MethodSource("nullableSnapshots")
    void nullableSnapshotUsesIsNullInsteadOfDroppingPredicate(
            Contribution contribution, NullableField field) {
        MarketDemandDO original = prepare(contribution, MarketDemandDO.STATUS_APPLYING);
        field.clear(original);
        allowInsert(contribution, 1);
        allowDemandUpdate(1);

        assertEquals(CHILD_ID, invoke(contribution));

        assertWriteOrder(contribution, original, true);
    }

    static Stream<Arguments> nullableSnapshots() {
        return Stream.of(Contribution.values()).flatMap(contribution -> Stream.of(NullableField.values())
                .map(field -> Arguments.of(contribution, field)));
    }

    @ParameterizedTest
    @EnumSource(Contribution.class)
    void allNullableSnapshotsRemainGuardedTogether(Contribution contribution) {
        MarketDemandDO original = prepare(contribution, MarketDemandDO.STATUS_PUBLISHED);
        Arrays.stream(NullableField.values()).forEach(field -> field.clear(original));
        allowInsert(contribution, 1);
        allowDemandUpdate(1);

        assertEquals(CHILD_ID, invoke(contribution));

        assertWriteOrder(contribution, original, true);
    }

    @ParameterizedTest(name = "{0}, status={1}, inserted rows={2}")
    @MethodSource("invalidWriteCounts")
    void childInsertMustAffectExactlyOneRowBeforeDemandWrite(Contribution contribution, int status, int rows) {
        prepare(contribution, status);
        allowInsert(contribution, rows);

        ErrorCode expected = contribution == Contribution.MATCH && rows == 0
                ? MARKET_DEMAND_MATCH_DUPLICATE : MARKET_DEMAND_STATUS_DENY;
        assertError(expected, () -> invoke(contribution));

        InOrder order = ordered();
        verifyPrerequisites(contribution, order);
        verifyChildInsert(contribution, order);
        verify(assetMapper, never()).selectForMatching( anyLong());
        verifyNoMoreContributionInteractions();
    }

    @ParameterizedTest(name = "{0}, status={1}, updated rows={2}")
    @MethodSource("invalidWriteCounts")
    void demandCasMustAffectExactlyOneRowBeforeNotification(Contribution contribution, int status, int rows) {
        MarketDemandDO original = prepare(contribution, status);
        allowInsert(contribution, 1);
        allowDemandUpdate(rows);

        assertError(MARKET_DEMAND_STATUS_DENY, () -> invoke(contribution));

        assertWriteOrder(contribution, original, false);
    }

    static Stream<Arguments> invalidWriteCounts() {
        // 分别覆盖原先会晋级的 PUBLISHED，以及原先可能忽略更新行数的 APPLYING。
        return Stream.of(Contribution.values()).flatMap(contribution -> Stream.of(1, 7)
                .flatMap(status -> Stream.of(0, 2).map(rows -> Arguments.of(contribution, status, rows))));
    }

    @ParameterizedTest(name = "{0} child insert propagates {1}")
    @MethodSource("writeFailures")
    void childInsertFailurePropagatesWithoutCasNotificationOrRetry(
            Contribution contribution, WriteFailure kind) {
        prepare(contribution, MarketDemandDO.STATUS_MATCHED);
        RuntimeException failure = kind.create();
        if (contribution == Contribution.REPLY) {
            when(replyMapper.insert(any(MarketDemandReplyDO.class))).thenThrow(failure);
        } else {
            when(matchMapper.insertIfAbsent(any(MarketDemandMatchDO.class))).thenThrow(failure);
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> invoke(contribution)));

        InOrder order = ordered();
        verifyPrerequisites(contribution, order);
        verifyChildInsert(contribution, order);
        verify(assetMapper, never()).selectForMatching( anyLong());
        verifyNoMoreContributionInteractions();
    }

    @ParameterizedTest(name = "{0} demand CAS propagates {1}")
    @MethodSource("writeFailures")
    void demandCasFailurePropagatesWithoutNotificationOrRetry(
            Contribution contribution, WriteFailure kind) {
        MarketDemandDO original = prepare(contribution, MarketDemandDO.STATUS_MATCHING);
        allowInsert(contribution, 1);
        RuntimeException failure = kind.create();
        when(demandMapper.update(nullable(MarketDemandDO.class), any(Wrapper.class))).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, () -> invoke(contribution)));

        assertWriteOrder(contribution, original, false);
    }

    static Stream<Arguments> writeFailures() {
        return Stream.of(Contribution.values()).flatMap(contribution -> Stream.of(WriteFailure.values())
                .map(kind -> Arguments.of(contribution, kind)));
    }

    @Test
    void matchRequiresExistingAssetBeforeDuplicateCheck() {
        load(MarketDemandDO.STATUS_PUBLISHED);

        assertError(DATA_ASSET_NOT_EXISTS, () -> invoke(Contribution.MATCH));

        verifyAssetReadOnly();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1, 0, 2, 99})
    void matchRequiresPublishedAssetBeforeDuplicateCheck(Integer status) {
        prepare(Contribution.MATCH, MarketDemandDO.STATUS_MATCHING);
        asset.setStatus(status);

        assertError(MARKET_APPLY_ASSET_NOT_PUBLISHED, () -> invoke(Contribution.MATCH));

        verifyAssetReadOnly();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void existingMatchKeepsDuplicateErrorWhateverItsStatus(int status) {
        prepare(Contribution.MATCH, MarketDemandDO.STATUS_APPLYING);
        var existing = new MarketDemandMatchDO();
        existing.setId(55L);
        existing.setStatus(status);
        when(matchMapper.selectByDemandAndAsset(DEMAND_ID, ASSET_ID)).thenReturn(existing);

        assertError(MARKET_DEMAND_MATCH_DUPLICATE, () -> invoke(Contribution.MATCH));

        verifyDemandReadOnly();
        verify(assetMapper).selectById(ASSET_ID);
        verify(matchMapper).selectByDemandAndAsset(DEMAND_ID, ASSET_ID);
        verifyNoMoreContributionInteractions();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 7})
    void concurrentDuplicateAfterEmptyPrecheckStopsBeforeDemandUpdateAndNotification(int status) {
        MarketDemandDO original = prepare(Contribution.MATCH, status);
        String before = original.toString();
        when(matchMapper.selectByDemandAndAsset(DEMAND_ID, ASSET_ID)).thenReturn(null);
        // 读取时不存在，但另一个事务已抢先写入；唯一键冲突由 insertIfAbsent 的 0 行返回表达。
        allowInsert(Contribution.MATCH, 0);

        assertError(MARKET_DEMAND_MATCH_DUPLICATE, () -> invoke(Contribution.MATCH));

        InOrder order = ordered();
        verifyPrerequisites(Contribution.MATCH, order);
        verifyChildInsert(Contribution.MATCH, order);
        verify(assetMapper, never()).selectForMatching( anyLong());
        verify(demandMapper, never()).update(nullable(MarketDemandDO.class), any(Wrapper.class));
        verify(matchMapper, never()).insert(any(MarketDemandMatchDO.class));
        verifyNoInteractions(replyMapper, notifyHelper);
        verifyNoMoreContributionInteractions();
        assertEquals(before, original.toString(), "并发重复不得修改初读需求快照");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void assetOrDuplicateLookupFailurePropagatesWithoutInsert(boolean duplicateLookup) {
        var failure = new IllegalStateException("test match prerequisite unavailable");
        if (duplicateLookup) {
            prepare(Contribution.MATCH, MarketDemandDO.STATUS_PUBLISHED);
            when(matchMapper.selectByDemandAndAsset(DEMAND_ID, ASSET_ID)).thenThrow(failure);
        } else {
            load(MarketDemandDO.STATUS_PUBLISHED);
            when(assetMapper.selectById(ASSET_ID)).thenThrow(failure);
        }

        assertSame(failure, assertThrows(IllegalStateException.class, () -> invoke(Contribution.MATCH)));

        verifyDemandReadOnly();
        verify(assetMapper).selectById(ASSET_ID);
        if (duplicateLookup) {
            verify(matchMapper).selectByDemandAndAsset(DEMAND_ID, ASSET_ID);
        }
        verifyNoMoreContributionInteractions();
    }

    @ParameterizedTest(name = "reply dept requested={0}, login={1}, stored={2}")
    @MethodSource("replyDepartments")
    void replyKeepsRequestedDepartmentAndOnlyFallsBackWhenMissing(Long requested, Long login, Long expected) {
        MarketDemandDO original = prepare(Contribution.REPLY, MarketDemandDO.STATUS_MATCHED);
        security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(login);
        MarketDemandReplySaveReqVO req = replyRequest();
        req.setReplyDeptId(requested);
        allowInsert(Contribution.REPLY, 1);
        allowDemandUpdate(1);

        assertEquals(CHILD_ID, service.replyDemand(req));

        assertWriteOrder(Contribution.REPLY, original, true, req, expected, matchRequest());
        assertEquals(requested, req.getReplyDeptId(), "不得修改请求 DTO 来填充登录部门");
        if (requested == null) {
            security.verify(SecurityFrameworkUtils::getLoginUserDeptId);
        } else {
            security.verify(SecurityFrameworkUtils::getLoginUserDeptId, never());
        }
    }

    static Stream<Arguments> replyDepartments() {
        return Stream.of(Arguments.of(null, DEPT_ID, DEPT_ID), Arguments.of(null, null, null),
                Arguments.of(DEPT_ID, DEPT_ID, DEPT_ID), Arguments.of(200L, DEPT_ID, 200L),
                Arguments.of(200L, null, 200L), Arguments.of(0L, DEPT_ID, 0L));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void replyMapsEachExistingReplyTypeWithoutChangingContent(int type) {
        MarketDemandDO original = prepare(Contribution.REPLY, MarketDemandDO.STATUS_MATCHING);
        MarketDemandReplySaveReqVO req = replyRequest();
        req.setReplyType(type);
        req.setContent("  原文响应\n保留格式  ");
        allowInsert(Contribution.REPLY, 1);
        allowDemandUpdate(1);

        assertEquals(CHILD_ID, service.replyDemand(req));

        assertWriteOrder(Contribution.REPLY, original, true, req, DEPT_ID, matchRequest());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void matchPreservesOptionalNote(String note) {
        MarketDemandDO original = prepare(Contribution.MATCH, MarketDemandDO.STATUS_MATCHING);
        MarketDemandMatchSaveReqVO req = matchRequest();
        req.setMatchNote(note);
        allowInsert(Contribution.MATCH, 1);
        allowDemandUpdate(1);

        assertEquals(CHILD_ID, service.matchDemand(req));

        assertWriteOrder(Contribution.MATCH, original, true, replyRequest(), DEPT_ID, req);
        assertEquals(note, req.getMatchNote());
    }

    @ParameterizedTest
    @EnumSource(Contribution.class)
    void ownerCanAlsoContributeWithoutNewPermissionRule(Contribution contribution) {
        MarketDemandDO original = prepare(contribution, MarketDemandDO.STATUS_MATCHING);
        original.setApplyUserId(USER_ID);
        allowInsert(contribution, 1);
        allowDemandUpdate(1);

        assertEquals(CHILD_ID, invoke(contribution));

        // 常规成功用例的 owner != 当前用户；两种场景均仅沿用 requireUserId。
        assertWriteOrder(contribution, original, true);
    }

    @ParameterizedTest
    @EnumSource(Contribution.class)
    void notificationHelperFailureIsNotConvertedToSuccessOrRetried(Contribution contribution) {
        MarketDemandDO original = prepare(contribution, MarketDemandDO.STATUS_MATCHING);
        allowInsert(contribution, 1);
        allowDemandUpdate(1);
        var failure = new IllegalStateException("test notification registration unavailable");
        if (contribution == Contribution.REPLY) {
            doThrow(failure).when(notifyHelper).notifyDemandReplied(original, USER_ID);
        } else {
            doThrow(failure).when(notifyHelper).notifyDemandMatched(original, currentAsset);
        }

        assertSame(failure, assertThrows(IllegalStateException.class, () -> invoke(contribution)));

        assertWriteOrder(contribution, original, true);
    }

    @ParameterizedTest
    @EnumSource(Contribution.class)
    void publicApiRetainsRequiredTransactionWithBoundedTimeout(Contribution contribution)
            throws NoSuchMethodException {
        var method = MarketDemandServiceImpl.class.getMethod(contribution.method, contribution.requestType);
        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertEquals(Long.class, method.getReturnType());
        assertEquals(Long.class, MarketDemandService.class.getMethod(contribution.method, contribution.requestType)
                .getReturnType());
        Transactional transaction = method.getAnnotation(Transactional.class);
        assertNotNull(transaction);
        assertEquals(Propagation.REQUIRED, transaction.propagation());
        assertEquals(10, transaction.timeout());
        assertFalse(transaction.readOnly());
        assertTrue(Arrays.asList(transaction.rollbackFor()).contains(Exception.class));
        assertEquals(0, transaction.noRollbackFor().length);
        assertEquals(0, transaction.noRollbackForClassName().length);
    }

    @ParameterizedTest(name = "asset {0}: {1} -> {2}")
    @MethodSource("matchingAssetChanges")
    void changedMatchingAssetStopsBeforeDemandCasAndNotification(
            MatchingAssetField field, Object originalValue, Object currentValue) {
        MarketDemandDO demand = prepare(Contribution.MATCH, MarketDemandDO.STATUS_MATCHING);
        field.set(asset, originalValue);
        field.set(currentAsset, currentValue);
        String originalSnapshot = asset.toString();
        String currentSnapshot = currentAsset.toString();
        String demandSnapshot = demand.toString();
        allowInsert(Contribution.MATCH, 1);

        assertError(MARKET_DEMAND_STATUS_DENY, () -> invoke(Contribution.MATCH));

        assertStoppedAfterAssetLock();
        assertEquals(originalSnapshot, asset.toString());
        assertEquals(currentSnapshot, currentAsset.toString());
        assertEquals(demandSnapshot, demand.toString());
    }

    static Stream<Arguments> matchingAssetChanges() {
        return Arrays.stream(MatchingAssetField.values()).flatMap(field -> {
            Stream<Arguments> changes = Stream.of(Arguments.of(field, field.original, field.changed),
                    Arguments.of(field, field.original, null));
            // 初读主键用于 primitive long 的查询参数，真实已持久化资产主键不可为 null。
            return field == MatchingAssetField.ID ? changes
                    : Stream.concat(changes, Stream.of(Arguments.of(field, null, field.original)));
        });
    }

    @ParameterizedTest
    @EnumSource(value = MatchingAssetField.class, names = "ID", mode = EnumSource.Mode.EXCLUDE)
    void sameNullableMatchingFieldIsAccepted(MatchingAssetField field) {
        MarketDemandDO demand = prepare(Contribution.MATCH, MarketDemandDO.STATUS_MATCHING);
        field.set(asset, null);
        field.set(currentAsset, null);
        allowInsert(Contribution.MATCH, 1);
        allowDemandUpdate(1);

        assertEquals(CHILD_ID, invoke(Contribution.MATCH));

        assertWriteOrder(Contribution.MATCH, demand, true);
    }

    @Test
    void allNullableMatchingFieldsCanRemainNullWithoutNewCompletenessPolicy() {
        MarketDemandDO demand = prepare(Contribution.MATCH, MarketDemandDO.STATUS_PUBLISHED);
        Arrays.stream(MatchingAssetField.values()).filter(field -> field != MatchingAssetField.ID)
                .forEach(field -> {
                    field.set(asset, null);
                    field.set(currentAsset, null);
                });
        allowInsert(Contribution.MATCH, 1);
        allowDemandUpdate(1);

        assertEquals(CHILD_ID, invoke(Contribution.MATCH));

        assertWriteOrder(Contribution.MATCH, demand, true);
    }

    @ParameterizedTest(name = "non-qualification asset field {0}")
    @MethodSource("nonQualificationAssetChanges")
    void descriptiveAndStatisticalChangesUseCurrentAssetWithoutRejectingMatch(String field, Object value) {
        MarketDemandDO demand = prepare(Contribution.MATCH, MarketDemandDO.STATUS_MATCHING);
        String originalSnapshot = asset.toString();
        ReflectionTestUtils.setField(currentAsset, field, value);
        String currentSnapshot = currentAsset.toString();
        allowInsert(Contribution.MATCH, 1);
        allowDemandUpdate(1);

        assertEquals(CHILD_ID, invoke(Contribution.MATCH));

        assertWriteOrder(Contribution.MATCH, demand, true);
        assertEquals(originalSnapshot, asset.toString(), "不得用重读数据覆盖入口快照");
        assertEquals(currentSnapshot, currentAsset.toString(), "通知接收重读快照，包括最新名称");
    }

    static Stream<Arguments> nonQualificationAssetChanges() {
        return Stream.of(Arguments.of("assetCode", "new-code"), Arguments.of("assetName", "重读后的名称"),
                Arguments.of("categoryId", 401L), Arguments.of("sourceSystemName", "new-source-name"),
                Arguments.of("tableType", "VIEW"), Arguments.of("layer", "new-layer"),
                Arguments.of("subjectDomainId", 402L), Arguments.of("subjectDomainName", "new-domain-name"),
                Arguments.of("rowCount", 403L), Arguments.of("fieldCount", 4),
                Arguments.of("storageSize", 405L), Arguments.of("healthScore", new BigDecimal("99.50")),
                Arguments.of("description", "新描述"), Arguments.of("publishTime", ACCEPTED_TIME),
                Arguments.of("publisher", "new-publisher"), Arguments.of("techLeaderPhone", "test-tech-contact"),
                Arguments.of("businessLeaderPhone", "test-business-contact"), Arguments.of("viewCount", 406L),
                Arguments.of("updateCycle", "new-cycle"), Arguments.of("businessPurpose", "new-purpose"),
                Arguments.of("contactName", "test-contact"), Arguments.of("contactPhone", "test-contact-phone"),
                Arguments.of("remark", "新备注"), Arguments.of("dutyBasis", "new-duty"),
                Arguments.of("updateRule", "new-rule"), Arguments.of("externalCode", "new-external-code"),
                Arguments.of("createTime", ACCEPTED_TIME), Arguments.of("updateTime", ACCEPTED_TIME.plusDays(1)),
                Arguments.of("creator", "test-creator"), Arguments.of("updater", "test-updater"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingOrDeletedLockedAssetRetainsNotExistsError(boolean deleted) {
        prepare(Contribution.MATCH, MarketDemandDO.STATUS_MATCHING);
        allowInsert(Contribution.MATCH, 1);
        if (deleted) {
            currentAsset.setDeleted(true);
            // 删除错误优先于下架或换绑错误。
            currentAsset.setStatus(DataAssetDO.STATUS_OFFLINE);
            currentAsset.setOwnerDeptId(999L);
        } else {
            when(assetMapper.selectForMatching( ASSET_ID)).thenReturn(null);
        }

        assertError(DATA_ASSET_NOT_EXISTS, () -> invoke(Contribution.MATCH));

        assertStoppedAfterAssetLock();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1, 0, 2, 99})
    void nonPublishedLockedAssetRetainsNotPublishedErrorBeforeSnapshotComparison(Integer status) {
        prepare(Contribution.MATCH, MarketDemandDO.STATUS_MATCHING);
        currentAsset.setStatus(status);
        currentAsset.setOwnerDeptId(999L);
        allowInsert(Contribution.MATCH, 1);

        assertError(MARKET_APPLY_ASSET_NOT_PUBLISHED, () -> invoke(Contribution.MATCH));

        assertStoppedAfterAssetLock();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(booleans = {false})
    void onlyExplicitDeletedTrueRejectsLockedAsset(Boolean deleted) {
        MarketDemandDO demand = prepare(Contribution.MATCH, MarketDemandDO.STATUS_MATCHING);
        currentAsset.setDeleted(deleted);
        allowInsert(Contribution.MATCH, 1);
        allowDemandUpdate(1);

        assertEquals(CHILD_ID, invoke(Contribution.MATCH));

        assertWriteOrder(Contribution.MATCH, demand, true);
    }

    @ParameterizedTest
    @EnumSource(WriteFailure.class)
    void assetLockDatabaseFailurePropagatesWithoutRetry(WriteFailure kind) {
        prepare(Contribution.MATCH, MarketDemandDO.STATUS_MATCHING);
        allowInsert(Contribution.MATCH, 1);
        RuntimeException failure = kind.create();
        when(assetMapper.selectForMatching( ASSET_ID)).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, () -> invoke(Contribution.MATCH)));

        assertStoppedAfterAssetLock();
    }

    @Test
    void assetLockUnexpectedFailureIsNotConvertedToMissingAsset() {
        prepare(Contribution.MATCH, MarketDemandDO.STATUS_MATCHING);
        allowInsert(Contribution.MATCH, 1);
        var failure = new IllegalStateException("test asset reread unavailable");
        when(assetMapper.selectForMatching( ASSET_ID)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> invoke(Contribution.MATCH)));

        assertStoppedAfterAssetLock();
    }



    @ParameterizedTest(name = "reply retains original behavior tenant={0}, ignore={1}")
    @MethodSource("untrustedTenants")
    void replyDoesNotAcquireAssetLockOrIntroduceMatchingTenantRule(Long tenantId, boolean ignore) {
        MarketDemandDO demand = prepare(Contribution.REPLY, MarketDemandDO.STATUS_MATCHING);
        TenantContextHolder.clear();
        TenantContextHolder.setIgnore(ignore);
        allowInsert(Contribution.REPLY, 1);
        allowDemandUpdate(1);

        assertEquals(CHILD_ID, invoke(Contribution.REPLY));

        assertWriteOrder(Contribution.REPLY, demand, true);
        verifyNoInteractions(assetMapper, matchMapper);
    }

    static Stream<Arguments> untrustedTenants() {
        return Stream.of(Arguments.of(null, false), Arguments.of(-1L, false), Arguments.of(1L, true));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 2L, 9_007_199_254_740_993L})
    void lockingReadObtainsExactTrustedTenantAtCallSite(long tenantId) {
        MarketDemandDO demand = prepare(Contribution.MATCH, MarketDemandDO.STATUS_MATCHING);
        when(matchMapper.insertIfAbsent(any(MarketDemandMatchDO.class))).thenAnswer(call -> {
            MarketDemandMatchDO match = call.getArgument(0);
            assertNull(match.getId());
            match.setId(CHILD_ID);
            TenantContextHolder.clear();
            return 1;
        });
        when(assetMapper.selectForMatching( ASSET_ID)).thenReturn(currentAsset);
        allowDemandUpdate(1);

        assertEquals(CHILD_ID, invoke(Contribution.MATCH));

        assertWriteOrder(Contribution.MATCH, demand, true);
    }

    @Test
    void mapperDeclaresUncachedObjectShareLock() throws NoSuchMethodException {
        var method = DataAssetMapper.class.getMethod("selectForMatching", long.class);
        assertEquals(DataAssetDO.class, method.getReturnType());
        Options options = method.getAnnotation(Options.class);
        assertNotNull(options);
        assertFalse(options.useCache());
        assertEquals(Options.FlushCachePolicy.TRUE, options.flushCache());
        Select select = method.getAnnotation(Select.class);
        assertNotNull(select);
        String sql = String.join(" ", select.value()).replaceAll("\\s+", " ").trim();
        assertTrue(sql.contains("FROM dh_asset_data_asset"));
        assertFalse(sql.contains("tenant_id"));
        assertTrue(Pattern.compile("(?<![\\w.])id\\s*=\\s*#\\{id}").matcher(sql).find());
        assertTrue(Pattern.compile("deleted\\s*=\\s*0").matcher(sql).find());
        assertTrue(sql.endsWith("FOR SHARE"));
        assertEquals("id", method.getParameters()[0].getAnnotation(Param.class).value());
        // 只证明声明合同；持锁到提交、缓存刷新和并发兼容须由主线程 PostgreSQL 用例证明。
    }

    private void assertStoppedAfterAssetLock() {
        InOrder order = ordered();
        verifyPrerequisites(Contribution.MATCH, order);
        verifyChildInsert(Contribution.MATCH, order);
        order.verify(assetMapper).selectForMatching( ASSET_ID);
        order.verifyNoMoreInteractions();
        verifyNoMoreContributionInteractions();
        verifyNoInteractions(replyMapper, notifyHelper);
    }

    private MarketDemandDO load(Integer status) {
        var demand = new MarketDemandDO();
        demand.setId(DEMAND_ID);
        demand.setStatus(status);
        demand.setApplyUserId(22L);
        demand.setApplyDeptId(220L);
        demand.setAcceptanceStatus(MarketDemandDO.ACCEPTANCE_DONE);
        demand.setAcceptedApplyId(81L);
        demand.setAcceptedTime(ACCEPTED_TIME);
        demand.setTitle("读取时标题 ' 保留原文");
        demand.setPurpose("读取时用途\n原样比较");
        demand.setExpectedFields("id,name");
        demand.setCloseReason("历史备注");
        when(demandMapper.selectById(DEMAND_ID)).thenReturn(demand);
        return demand;
    }

    private MarketDemandDO prepare(Contribution contribution, int status) {
        MarketDemandDO demand = load(status);
        if (contribution == Contribution.MATCH) {
            asset = new DataAssetDO();
            Arrays.stream(MatchingAssetField.values()).forEach(field -> field.set(asset, field.original));
            asset.setStatus(DataAssetDO.STATUS_PUBLISHED);
            asset.setDeleted(false);
            asset.setAssetName("初读资产名称");
            currentAsset = BeanUtils.toBean(asset, DataAssetDO.class);
            assertNotSame(asset, currentAsset, "末次查询应提供独立快照");
            when(assetMapper.selectById(ASSET_ID)).thenReturn(asset);
            // 前置校验、身份或插入失败时不得调用；这些用例仍显式断言没有末次查询。
            lenient().when(assetMapper.selectForMatching( ASSET_ID)).thenAnswer(call -> currentAsset);
        }
        return demand;
    }

    private void allowInsert(Contribution contribution, int rows) {
        if (contribution == Contribution.REPLY) {
            when(replyMapper.insert(any(MarketDemandReplyDO.class))).thenAnswer(call -> {
                MarketDemandReplyDO reply = call.getArgument(0);
                assertNull(reply.getId(), "新回复不得携带历史 ID");
                reply.setId(CHILD_ID);
                return rows;
            });
        } else {
            when(matchMapper.insertIfAbsent(any(MarketDemandMatchDO.class))).thenAnswer(call -> {
                MarketDemandMatchDO match = call.getArgument(0);
                assertNull(match.getId(), "新匹配不得携带历史 ID");
                match.setId(CHILD_ID);
                return rows;
            });
        }
    }

    private void allowDemandUpdate(int rows) {
        when(demandMapper.update(nullable(MarketDemandDO.class), any(Wrapper.class))).thenReturn(rows);
    }

    private Long invoke(Contribution contribution) {
        return contribution == Contribution.REPLY ? service.replyDemand(replyRequest())
                : service.matchDemand(matchRequest());
    }

    private InOrder ordered() {
        return inOrder(demandMapper, assetMapper, matchMapper, replyMapper, objectGuard, notifyHelper);
    }

    private void verifyPrerequisites(Contribution contribution, InOrder order) {
        order.verify(demandMapper).selectById(DEMAND_ID);
        if (contribution == Contribution.MATCH) {
            order.verify(assetMapper).selectById(ASSET_ID);
            order.verify(matchMapper).selectByDemandAndAsset(DEMAND_ID, ASSET_ID);
        }
        order.verify(objectGuard).requireUserId();
    }

    private void verifyChildInsert(Contribution contribution, InOrder order) {
        if (contribution == Contribution.REPLY) {
            order.verify(replyMapper).insert(any(MarketDemandReplyDO.class));
        } else {
            order.verify(matchMapper).insertIfAbsent(any(MarketDemandMatchDO.class));
        }
    }

    private void assertWriteOrder(Contribution contribution, MarketDemandDO original, boolean notified) {
        assertWriteOrder(contribution, original, notified, replyRequest(), DEPT_ID, matchRequest());
    }

    private void assertWriteOrder(Contribution contribution, MarketDemandDO original, boolean notified,
            MarketDemandReplySaveReqVO replyReq, Long replyDeptId, MarketDemandMatchSaveReqVO matchReq) {
        InOrder order = ordered();
        verifyPrerequisites(contribution, order);
        if (contribution == Contribution.REPLY) {
            var inserted = ArgumentCaptor.forClass(MarketDemandReplyDO.class);
            order.verify(replyMapper).insert(inserted.capture());
            MarketDemandReplyDO reply = inserted.getValue();
            assertAll(() -> assertEquals(CHILD_ID, reply.getId()),
                    () -> assertEquals(replyReq.getDemandId(), reply.getDemandId()),
                    () -> assertEquals(replyReq.getReplyType(), reply.getReplyType()),
                    () -> assertEquals(replyReq.getContent(), reply.getContent()),
                    () -> assertEquals(replyDeptId, reply.getReplyDeptId()),
                    () -> assertEquals(USER_ID, reply.getReplyUserId()));
        } else {
            var inserted = ArgumentCaptor.forClass(MarketDemandMatchDO.class);
            order.verify(matchMapper).insertIfAbsent(inserted.capture());
            MarketDemandMatchDO match = inserted.getValue();
            assertAll(() -> assertEquals(CHILD_ID, match.getId()),
                    () -> assertEquals(matchReq.getDemandId(), match.getDemandId()),
                    () -> assertEquals(matchReq.getAssetId(), match.getAssetId()),
                    () -> assertEquals(matchReq.getMatchNote(), match.getMatchNote()),
                    () -> assertEquals(MarketDemandMatchDO.TYPE_MANUAL, match.getMatchType()),
                    () -> assertEquals(MarketDemandMatchDO.STATUS_PENDING, match.getStatus()),
                    () -> assertEquals(USER_ID, match.getMatcherUserId()),
                    () -> assertNull(match.getApplyId()), () -> assertNull(match.getMatchScore()),
                    () -> assertNull(match.getConfirmUserId()), () -> assertNull(match.getConfirmTime()),
                    () -> assertNull(match.getConfirmNote()));
            order.verify(assetMapper).selectForMatching( ASSET_ID);
        }
        var entity = ArgumentCaptor.forClass(MarketDemandDO.class);
        var condition = ArgumentCaptor.<Wrapper<MarketDemandDO>>captor();
        order.verify(demandMapper).update(entity.capture(), condition.capture());
        assertNull(entity.getValue(), "只能通过显式 SET 修改状态，禁止回写需求 DO");
        assertSnapshot(condition.getValue(), original);
        if (notified) {
            if (contribution == Contribution.REPLY) {
                order.verify(notifyHelper).notifyDemandReplied(same(original), eq(USER_ID));
            } else {
                order.verify(notifyHelper).notifyDemandMatched(same(original), same(currentAsset));
            }
        }
        order.verifyNoMoreInteractions();
        verifyNoMoreContributionInteractions();
    }

    private void verifyDemandReadOnly() {
        verify(demandMapper).selectById(DEMAND_ID);
        verifyNoMoreInteractions(demandMapper);
    }

    private void verifyAssetReadOnly() {
        verifyDemandReadOnly();
        verify(assetMapper).selectById(ASSET_ID);
        verifyNoMoreContributionInteractions();
    }

    private void verifyNoMoreContributionInteractions() {
        // 禁止隐式重投、updateById、补写申请/匹配、额外鉴权或失败后发送通知。
        verifyNoMoreInteractions(demandMapper, assetMapper, matchMapper, replyMapper, objectGuard, notifyHelper);
    }

    private static void assertSnapshot(Wrapper<MarketDemandDO> condition, MarketDemandDO original) {
        assertNull(condition.getEntity());
        assertPredicate(condition, "id", original.getId());
        assertPredicate(condition, "status", original.getStatus());
        assertPredicate(condition, "apply_user_id", original.getApplyUserId());
        assertPredicate(condition, "apply_dept_id", original.getApplyDeptId());
        assertPredicate(condition, "acceptance_status", original.getAcceptanceStatus());
        assertPredicate(condition, "accepted_apply_id", original.getAcceptedApplyId());
        assertPredicate(condition, "accepted_time", original.getAcceptedTime());
        assertPredicate(condition, "title", original.getTitle());
        assertPredicate(condition, "purpose", original.getPurpose());
        assertPredicate(condition, "expected_fields", original.getExpectedFields());
        assertFalse(Pattern.compile("\\b(?:OR|NOT\\s+IN)\\b", Pattern.CASE_INSENSITIVE)
                .matcher(condition.getSqlSegment()).find(), "CAS 不得放宽为排除终态或可选快照");
        String sqlSet = assertInstanceOf(LambdaUpdateWrapper.class, condition).getSqlSet();
        assertParameter(condition, sqlSet, "status", original.getStatus() == MarketDemandDO.STATUS_PUBLISHED
                ? MarketDemandDO.STATUS_MATCHING : original.getStatus());
        var assignments = Pattern.compile("(?<![\\w.])([a-z_]+)\\s*=", Pattern.CASE_INSENSITIVE).matcher(sqlSet);
        assertEquals(List.of("status"), assignments.results().map(result -> result.group(1)).toList(),
                "只更新状态，保留归属、验收和需求内容");
    }

    private static void assertPredicate(Wrapper<?> condition, String column, Object expected) {
        String sql = condition.getSqlSegment();
        String identifier = "(?<![\\w.])" + Pattern.quote(column);
        if (expected != null) {
            assertParameter(condition, sql, column, expected);
            assertFalse(Pattern.compile(identifier + "\\s+IS\\s+NULL", Pattern.CASE_INSENSITIVE)
                    .matcher(sql).find(), column + " 应比较原值");
            return;
        }
        var matcher = Pattern.compile(identifier + "\\s+IS\\s+NULL(?!\\w)", Pattern.CASE_INSENSITIVE)
                .matcher(sql);
        assertTrue(matcher.find(), column + " 的 null 快照必须使用 IS NULL: " + sql);
        assertFalse(matcher.find(), column + " 谓词不应重复");
        assertFalse(Pattern.compile(identifier + "\\s*=").matcher(sql).find(), "不能生成 = NULL");
    }

    private static void assertParameter(Wrapper<?> condition, String sql, String column, Object expected) {
        assertNotNull(sql);
        var matcher = Pattern.compile("(?<![\\w.])" + Pattern.quote(column)
                + "\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}", Pattern.CASE_INSENSITIVE).matcher(sql);
        assertTrue(matcher.find(), column + " 必须绑定参数: " + sql);
        var parameters = assertInstanceOf(AbstractWrapper.class, condition).getParamNameValuePairs();
        assertTrue(parameters.containsKey(matcher.group(1)));
        assertEquals(expected, parameters.get(matcher.group(1)), column);
        assertFalse(matcher.find(), column + " 应只比较/赋值一次");
    }

    private static MarketDemandReplySaveReqVO replyRequest() {
        var req = new MarketDemandReplySaveReqVO();
        req.setDemandId(DEMAND_ID);
        req.setReplyType(MarketDemandReplyDO.TYPE_CAN_SUPPLY);
        req.setContent("可提供测试数据，请确认用途");
        return req;
    }

    private static MarketDemandMatchSaveReqVO matchRequest() {
        var req = new MarketDemandMatchSaveReqVO();
        req.setDemandId(DEMAND_ID);
        req.setAssetId(ASSET_ID);
        req.setMatchNote("人工匹配测试资产");
        return req;
    }

    private static void assertError(ErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        ServiceException actual = assertThrows(ServiceException.class, action);
        assertEquals(expected.getCode(), actual.getCode());
    }

    private enum Contribution {
        REPLY("replyDemand", MarketDemandReplySaveReqVO.class),
        MATCH("matchDemand", MarketDemandMatchSaveReqVO.class);

        private final String method;
        private final Class<?> requestType;

        Contribution(String method, Class<?> requestType) {
            this.method = method;
            this.requestType = requestType;
        }
    }

    private enum NullableField {
        OWNER, DEPARTMENT, ACCEPTANCE_STATUS, ACCEPTED_APPLY, ACCEPTED_TIME, TITLE, PURPOSE, EXPECTED_FIELDS;

        void clear(MarketDemandDO demand) {
            switch (this) {
                case OWNER -> demand.setApplyUserId(null);
                case DEPARTMENT -> demand.setApplyDeptId(null);
                case ACCEPTANCE_STATUS -> demand.setAcceptanceStatus(null);
                case ACCEPTED_APPLY -> demand.setAcceptedApplyId(null);
                case ACCEPTED_TIME -> demand.setAcceptedTime(null);
                case TITLE -> demand.setTitle(null);
                case PURPOSE -> demand.setPurpose(null);
                case EXPECTED_FIELDS -> demand.setExpectedFields(null);
            }
        }
    }

    private enum MatchingAssetField {
        ID("id", ASSET_ID, 9_007_199_254_740_995L),
        ASSET_TYPE("assetType", 1, 2),
        CATALOG_ITEM_URN("catalogItemUrn", "urn:catalog:original", "urn:catalog:changed"),
        META_TABLE_ID("metaTableId", 101L, 201L),
        RESOURCE_URN("resourceUrn", "urn:resource:original", "urn:resource:changed"),
        PUBLISHED_SNAPSHOT_ID("publishedSnapshotId", 102L, 202L),
        PUBLISHED_VERSION("publishedVersion", "v1", "v2"),
        PUBLISHED_CATALOG_VERSION_ID("publishedCatalogVersionId", 103L, 203L),
        DATASOURCE_ID("datasourceId", 104L, 204L),
        DB_NAME("dbName", "original_db", "changed_db"),
        SCHEMA_NAME("schemaName", "original_schema", "changed_schema"),
        TABLE_NAME("tableName", "original_table", "changed_table"),
        OWNER_DEPT_ID("ownerDeptId", 105L, 205L),
        TECH_LEADER_USER_ID("techLeaderUserId", 106L, 206L),
        BUSINESS_LEADER_USER_ID("businessLeaderUserId", 107L, 207L),
        PROVIDER_DEPT_ID("providerDeptId", 108L, 208L),
        SOURCE_DEPT_ID("sourceDeptId", 109L, 209L),
        SHARE_ATTR("shareAttr", 1, 2),
        SHARE_ATTRIBUTE("shareAttribute", 1, 2),
        SHARE_CONDITION("shareCondition", "original-condition", "changed-condition"),
        SHARE_DENY_REASON("shareDenyReason", "original-reason", "changed-reason"),
        TECH_AVAILABLE("techAvailable", 1, 0),
        CATALOG_REVISION("catalogRevision", 110L, 210L);

        private final String field;
        private final Object original;
        private final Object changed;

        MatchingAssetField(String field, Object original, Object changed) {
            this.field = field;
            this.original = original;
            this.changed = changed;
        }

        void set(DataAssetDO target, Object value) {
            ReflectionTestUtils.setField(target, field, value);
        }
    }

    private enum WriteFailure {
        LOCK, CONSTRAINT;

        RuntimeException create() {
            return this == LOCK ? new CannotAcquireLockException("test lock unavailable")
                    : new DuplicateKeyException("test constraint conflict");
        }
    }
}
