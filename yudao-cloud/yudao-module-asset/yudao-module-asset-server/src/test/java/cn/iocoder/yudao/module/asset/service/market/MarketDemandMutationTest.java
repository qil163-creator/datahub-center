package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDemandCloseReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDemandSaveReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDemandDO;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

/**
 * 需求单条写入的领域契约测试。只验证状态、权限、wrapper 和调用边界；
 * 不证明 PostgreSQL 的锁、租户拦截、逻辑删除或并发 SQL 执行行为。
 */
class MarketDemandMutationTest extends BaseMockitoUnitTest {

    private static final Long DEMAND_ID = 1L;
    private static final Long USER_ID = 11L;
    private static final Long DEPT_ID = 100L;
    private static final LocalDateTime ACCEPTED_TIME = LocalDateTime.of(2026, 9, 20, 9, 30);
    private static final List<Integer> OPEN_STATUSES = List.of(0, 1, 2, 3, 7);

    @InjectMocks
    private MarketDemandServiceImpl service;
    @Mock
    private MarketDemandMapper demandMapper;
    @Mock
    private MarketObjectGuard objectGuard;
    @Mock
    private MarketDemandMatchMapper matchMapper;
    @Mock
    private MarketDemandReplyMapper replyMapper;
    @Mock
    private DataAssetMapper assetMapper;
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
    @Mock
    private MarketNotifyHelper notifyHelper;
    private MockedStatic<SecurityFrameworkUtils> security;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                MarketDemandDO.class);
    }

    @BeforeEach
    void setup() {
        security = mockStatic(SecurityFrameworkUtils.class);
        // Mockito 的 Long 默认值是 0；明确设置请求对应的登录部门，不能让普通编辑误入部门拒绝分支。
        security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(DEPT_ID);
        // 无效请求可能在身份读取之前拒绝；固定身份不约束各项前置校验的先后顺序。
        lenient().when(objectGuard.requireUserId()).thenReturn(USER_ID);
    }

    @AfterEach
    void cleanup() {
        try {
            verifyNoInteractions(matchMapper, replyMapper, assetMapper, applyService, applyMapper,
                    deliveryService, accessService, resourceService, displayHelper, adminUserApi, notifyHelper);
        } finally {
            if (security != null) {
                security.close();
            }
        }
    }

    @ParameterizedTest(name = "{0} accepts original status {1}")
    @MethodSource("allowedOwnerMutations")
    void ownerMutationUsesOneConditionalWrite(Mutation mutation, int status) {
        MarketDemandDO original = load(status);
        String before = original.toString();
        allowOneWrite(mutation, 1);

        invoke(mutation);

        Write write = onlyWrite(mutation);
        assertSnapshot(write.condition(), original);
        assertMutationPayload(mutation, write, MarketDemandDO.STATUS_CLOSED, "测试关闭");
        assertEquals(before, original.toString(), "不得把读取快照原地改为目标状态");
        verify(objectGuard).requireUserId();
        verifyNoMoreInteractions(objectGuard);
    }

    static Stream<Arguments> allowedOwnerMutations() {
        return Stream.of(Arguments.of(Mutation.EDIT, 0), Arguments.of(Mutation.EDIT, 1),
                Arguments.of(Mutation.EDIT, 2), Arguments.of(Mutation.DELETE, 0),
                Arguments.of(Mutation.DELETE, 5), Arguments.of(Mutation.PUBLISH, 0));
    }

    @ParameterizedTest(name = "{0} rejects original status {1}")
    @MethodSource("deniedOwnerMutations")
    void ownerMutationRejectsOtherStatusesBeforeWriting(Mutation mutation, Integer status) {
        load(status);

        assertError(MARKET_DEMAND_STATUS_DENY, () -> invoke(mutation));

        assertOnlyRead();
    }

    static Stream<Arguments> deniedOwnerMutations() {
        return Stream.of(Mutation.EDIT, Mutation.DELETE, Mutation.PUBLISH).flatMap(mutation ->
                Stream.of(null, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 99)
                        .filter(status -> status == null || !mutation.allowedStatuses.contains(status))
                        .map(status -> Arguments.of(mutation, status)));
    }

    @ParameterizedTest(name = "close {0}->{1}, owner={2}, matchPermission={3}")
    @MethodSource("closePermissionMatrix")
    void closePreservesExistingOwnerAndPermissionRules(
            int status, int target, boolean owner, boolean matchPermission) {
        MarketDemandDO original = load(status);
        original.setApplyUserId(owner ? USER_ID : 22L);
        boolean needsPermission = !owner && target == MarketDemandDO.STATUS_CLOSED;
        if (needsPermission) {
            when(objectGuard.hasPermission(MarketObjectGuard.PERM_DEMAND_MATCH)).thenReturn(matchPermission);
        }
        boolean allowed = owner ? target != MarketDemandDO.STATUS_REJECTED
                : target == MarketDemandDO.STATUS_REJECTED
                || target == MarketDemandDO.STATUS_CLOSED && matchPermission;

        if (allowed) {
            allowOneWrite(Mutation.CLOSE, 1);
            service.closeDemand(closeRequest(target, "权限矩阵"));
            Write write = onlyWrite(Mutation.CLOSE);
            assertSnapshot(write.condition(), original);
            assertMutationPayload(Mutation.CLOSE, write, target, "权限矩阵");
        } else {
            assertError(MARKET_DEMAND_FORBIDDEN,
                    () -> service.closeDemand(closeRequest(target, "权限矩阵")));
            assertOnlyRead();
        }
        verify(objectGuard).requireUserId();
        if (needsPermission) {
            verify(objectGuard).hasPermission(MarketObjectGuard.PERM_DEMAND_MATCH);
        }
        verifyNoMoreInteractions(objectGuard);
    }

    static Stream<Arguments> closePermissionMatrix() {
        return OPEN_STATUSES.stream().flatMap(status -> Stream.of(4, 5, 6).flatMap(target ->
                Stream.of(true, false).flatMap(owner -> Stream.of(true, false)
                        .map(permission -> Arguments.of(status, target, owner, permission)))));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1, 4, 5, 6, 8, 99})
    void closeRejectsTerminalNullAndUnknownOriginalStatuses(Integer status) {
        load(status);

        assertError(MARKET_DEMAND_STATUS_DENY, () -> invoke(Mutation.CLOSE));

        assertOnlyRead();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1, 0, 1, 2, 3, 7, 8, 99})
    void closeRejectsInvalidTargetWithOriginalErrorCode(Integer target) {
        load(MarketDemandDO.STATUS_DRAFT);

        assertError(MARKET_DEMAND_CLOSE_STATUS_INVALID,
                () -> service.closeDemand(closeRequest(target, "无效目标状态")));

        assertOnlyRead();
    }

    @ParameterizedTest(name = "{0} cannot modify owner {1}")
    @MethodSource("nonOwnerMutations")
    void editDeleteAndPublishNeverBypassOwnership(Mutation mutation, Long owner) {
        MarketDemandDO original = load(MarketDemandDO.STATUS_DRAFT);
        original.setApplyUserId(owner);

        assertError(MARKET_DEMAND_FORBIDDEN, () -> invoke(mutation));

        assertOnlyRead();
        verify(objectGuard).requireUserId();
        verifyNoMoreInteractions(objectGuard);
    }

    static Stream<Arguments> nonOwnerMutations() {
        return Stream.of(Mutation.EDIT, Mutation.DELETE, Mutation.PUBLISH)
                .flatMap(mutation -> Stream.of(22L, (Long) null).map(owner -> Arguments.of(mutation, owner)));
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void missingDemandRetainsNotExistsError(Mutation mutation) {
        when(demandMapper.selectById(DEMAND_ID)).thenReturn(null);

        assertError(MARKET_DEMAND_NOT_EXISTS, () -> invoke(mutation));

        assertOnlyRead();
        verifyNoInteractions(objectGuard);
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void readFailureIsPropagatedWithoutWriting(Mutation mutation) {
        var failure = new CannotAcquireLockException("test read unavailable");
        when(demandMapper.selectById(DEMAND_ID)).thenThrow(failure);

        assertSame(failure, assertThrows(CannotAcquireLockException.class, () -> invoke(mutation)));

        assertOnlyRead();
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void identityFailureIsPropagatedWithoutWriting(Mutation mutation) {
        load(MarketDemandDO.STATUS_DRAFT);
        var failure = new IllegalStateException("test identity unavailable");
        when(objectGuard.requireUserId()).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> invoke(mutation)));

        assertOnlyRead();
    }

    @ParameterizedTest(name = "{0} rejects affected rows {1}")
    @MethodSource("invalidRowCounts")
    void conditionalWriteMustAffectExactlyOneRow(Mutation mutation, int rows) {
        MarketDemandDO original = load(MarketDemandDO.STATUS_DRAFT);
        allowOneWrite(mutation, rows);

        assertError(MARKET_DEMAND_STATUS_DENY, () -> invoke(mutation));

        Write write = onlyWrite(mutation);
        assertSnapshot(write.condition(), original);
        assertMutationPayload(mutation, write, MarketDemandDO.STATUS_CLOSED, "测试关闭");
    }

    static Stream<Arguments> invalidRowCounts() {
        return Arrays.stream(Mutation.values()).flatMap(mutation ->
                Stream.of(0, 2).map(rows -> Arguments.of(mutation, rows)));
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void writeFailureIsPropagatedWithoutRetryOrFallback(Mutation mutation) {
        load(MarketDemandDO.STATUS_DRAFT);
        var failure = new CannotAcquireLockException("test conditional write unavailable");
        if (mutation == Mutation.DELETE) {
            when(demandMapper.delete(any(Wrapper.class))).thenThrow(failure);
        } else {
            when(demandMapper.update(nullable(MarketDemandDO.class), any(Wrapper.class))).thenThrow(failure);
        }

        assertSame(failure, assertThrows(CannotAcquireLockException.class, () -> invoke(mutation)));

        onlyWrite(mutation);
    }

    @ParameterizedTest(name = "{0} retains null {1}")
    @MethodSource("nullableSnapshotFields")
    void individualNullSnapshotFieldUsesIsNull(Mutation mutation, NullableField field) {
        MarketDemandDO original = load(MarketDemandDO.STATUS_DRAFT);
        field.clear(original);
        allowOneWrite(mutation, 1);

        invoke(mutation);

        assertSnapshot(onlyWrite(mutation).condition(), original);
    }

    static Stream<Arguments> nullableSnapshotFields() {
        return Arrays.stream(Mutation.values()).flatMap(mutation ->
                Arrays.stream(NullableField.values()).map(field -> Arguments.of(mutation, field)));
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void allNullableAcceptanceAndDepartmentFieldsRemainPredicates(Mutation mutation) {
        MarketDemandDO original = load(MarketDemandDO.STATUS_DRAFT);
        Arrays.stream(NullableField.values()).forEach(field -> field.clear(original));
        allowOneWrite(mutation, 1);

        invoke(mutation);

        assertSnapshot(onlyWrite(mutation).condition(), original);
    }

    @Test
    void authorizedNonOwnerCloseRetainsNullOwnerPredicate() {
        MarketDemandDO original = load(MarketDemandDO.STATUS_DRAFT);
        original.setApplyUserId(null);
        when(objectGuard.hasPermission(MarketObjectGuard.PERM_DEMAND_MATCH)).thenReturn(true);
        allowOneWrite(Mutation.CLOSE, 1);

        invoke(Mutation.CLOSE);

        assertSnapshot(onlyWrite(Mutation.CLOSE).condition(), original);
        verify(objectGuard).hasPermission(MarketObjectGuard.PERM_DEMAND_MATCH);
    }

    @ParameterizedTest(name = "edit dept requested={0}, login={1}, expected={2}")
    @MethodSource("departmentResolution")
    void editPreservesDepartmentValidationAndOriginalDepartmentPredicate(
            Long requested, Long login, Long expected, boolean allowed) {
        MarketDemandDO original = load(MarketDemandDO.STATUS_DRAFT);
        security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(login);
        MarketDemandSaveReqVO req = editRequest(null);
        req.setApplyDeptId(requested);
        if (!allowed) {
            assertError(MARKET_APPLY_DEPT_INVALID, () -> service.updateDemand(req));
            assertOnlyRead();
            return;
        }
        allowOneWrite(Mutation.EDIT, 1);

        service.updateDemand(req);

        Write write = onlyWrite(Mutation.EDIT);
        assertEquals(expected, write.entity().getApplyDeptId());
        assertSnapshot(write.condition(), original);
        assertNoSystemFieldWrite(write);
    }

    static Stream<Arguments> departmentResolution() {
        return Stream.of(Arguments.of(100L, 100L, 100L, true), Arguments.of(null, 100L, 100L, true),
                Arguments.of(200L, 100L, null, false), Arguments.of(200L, null, 200L, true),
                Arguments.of(null, null, null, true), Arguments.of(null, 200L, 200L, true));
    }

    @ParameterizedTest(name = "edit original status {0} ignores publish flag {1}")
    @MethodSource("editPublishFlags")
    void editMapsBusinessFieldsButNeverCopiesSystemFields(int status, Boolean publish) {
        MarketDemandDO original = load(status);
        String before = original.toString();
        MarketDemandSaveReqVO req = editRequest(publish);
        security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(DEPT_ID);
        allowOneWrite(Mutation.EDIT, 1);

        service.updateDemand(req);

        Write write = onlyWrite(Mutation.EDIT);
        assertSnapshot(write.condition(), original);
        assertEditBusinessFields(req, write.entity());
        assertNoSystemFieldWrite(write);
        assertEquals(before, original.toString());
    }

    static Stream<Arguments> editPublishFlags() {
        return Stream.of(0, 1, 2).flatMap(status ->
                Stream.of(null, Boolean.FALSE, Boolean.TRUE).map(publish -> Arguments.of(status, publish)));
    }

    @Test
    void closeBindsNullReasonInsteadOfKeepingOldReason() {
        MarketDemandDO original = load(MarketDemandDO.STATUS_PUBLISHED);
        allowOneWrite(Mutation.CLOSE, 1);

        service.closeDemand(closeRequest(MarketDemandDO.STATUS_CANCELLED, null));

        Write write = onlyWrite(Mutation.CLOSE);
        assertSnapshot(write.condition(), original);
        assertMutationPayload(Mutation.CLOSE, write, MarketDemandDO.STATUS_CANCELLED, null);
    }

    @Test
    void closePermissionFailureIsPropagatedWithoutWriting() {
        MarketDemandDO original = load(MarketDemandDO.STATUS_PUBLISHED);
        original.setApplyUserId(22L);
        var failure = new IllegalStateException("test permission unavailable");
        when(objectGuard.hasPermission(MarketObjectGuard.PERM_DEMAND_MATCH)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> invoke(Mutation.CLOSE)));

        assertOnlyRead();
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void singleSqlMutationDoesNotAddTransactionAnnotation(Mutation mutation) throws NoSuchMethodException {
        assertNull(MarketDemandServiceImpl.class.getAnnotation(Transactional.class));
        assertNull(MarketDemandService.class.getAnnotation(Transactional.class));
        assertNull(MarketDemandServiceImpl.class.getMethod(mutation.method, mutation.requestType)
                .getAnnotation(Transactional.class));
        assertNull(MarketDemandService.class.getMethod(mutation.method, mutation.requestType)
                .getAnnotation(Transactional.class));
    }

    private MarketDemandDO load(Integer status) {
        MarketDemandDO demand = new MarketDemandDO();
        demand.setId(DEMAND_ID);
        demand.setDemandNo("DEM-SNAPSHOT-ONLY");
        demand.setStatus(status);
        demand.setApplyUserId(USER_ID);
        demand.setApplyDeptId(DEPT_ID);
        demand.setAcceptanceStatus(MarketDemandDO.ACCEPTANCE_DONE);
        demand.setAcceptedApplyId(81L);
        demand.setAcceptedTime(ACCEPTED_TIME);
        demand.setPublishTime(ACCEPTED_TIME.minusDays(1));
        demand.setCloseReason("历史关闭说明");
        demand.setTitle("读取时的标题");
        demand.setPurpose("读取时的用途");
        demand.setCreator("snapshot-creator");
        demand.setUpdater("snapshot-updater");
        demand.setCreateTime(ACCEPTED_TIME.minusDays(2));
        demand.setUpdateTime(ACCEPTED_TIME.minusHours(1));
        when(demandMapper.selectById(DEMAND_ID)).thenReturn(demand);
        return demand;
    }

    private void allowOneWrite(Mutation mutation, int rows) {
        if (mutation == Mutation.DELETE) {
            when(demandMapper.delete(any(Wrapper.class))).thenReturn(rows);
        } else {
            when(demandMapper.update(nullable(MarketDemandDO.class), any(Wrapper.class))).thenReturn(rows);
        }
    }

    private void invoke(Mutation mutation) {
        switch (mutation) {
            case EDIT -> service.updateDemand(editRequest(null));
            case DELETE -> service.deleteDemand(DEMAND_ID);
            case PUBLISH -> service.publishDemand(DEMAND_ID);
            case CLOSE -> service.closeDemand(closeRequest(MarketDemandDO.STATUS_CLOSED, "测试关闭"));
        }
    }

    private Write onlyWrite(Mutation mutation) {
        var condition = ArgumentCaptor.<Wrapper<MarketDemandDO>>captor();
        MarketDemandDO entity = null;
        verify(demandMapper).selectById(DEMAND_ID);
        if (mutation == Mutation.DELETE) {
            verify(demandMapper).delete(condition.capture());
        } else {
            var update = ArgumentCaptor.<MarketDemandDO>captor();
            verify(demandMapper).update(update.capture(), condition.capture());
            entity = update.getValue();
        }
        // 同时禁止 updateById/deleteById、额外查询、重投和多条写入。
        verifyNoMoreInteractions(demandMapper);
        return new Write(entity, condition.getValue());
    }

    private void assertOnlyRead() {
        verify(demandMapper).selectById(DEMAND_ID);
        verifyNoMoreInteractions(demandMapper);
    }

    private static void assertSnapshot(Wrapper<MarketDemandDO> condition, MarketDemandDO original) {
        assertNotNull(condition);
        assertNull(condition.getEntity(), "WHERE 条件由显式快照谓词提供，不能夹带旧 DO");
        assertPredicate(condition, "id", original.getId());
        assertPredicate(condition, "status", original.getStatus());
        assertPredicate(condition, "apply_user_id", original.getApplyUserId());
        assertPredicate(condition, "apply_dept_id", original.getApplyDeptId());
        assertPredicate(condition, "acceptance_status", original.getAcceptanceStatus());
        assertPredicate(condition, "accepted_apply_id", original.getAcceptedApplyId());
        assertPredicate(condition, "accepted_time", original.getAcceptedTime());
        assertFalse(Pattern.compile("\\b(?:OR|NOT\\s+IN)\\b", Pattern.CASE_INSENSITIVE)
                .matcher(condition.getSqlSegment()).find(), "不能以 OR/NOT IN 放宽原值比较");
    }

    private static void assertPredicate(Wrapper<?> condition, String column, Object expected) {
        String sql = condition.getSqlSegment();
        String identifier = "(?<![\\w.])" + Pattern.quote(column);
        if (expected != null) {
            assertParameter(condition, sql, column, expected);
            assertFalse(Pattern.compile(identifier + "\\s+IS\\s+NULL", Pattern.CASE_INSENSITIVE)
                    .matcher(sql).find(), column + " 非空快照必须绑定原值");
            return;
        }
        var matcher = Pattern.compile(identifier + "\\s+IS\\s+NULL(?!\\w)", Pattern.CASE_INSENSITIVE)
                .matcher(sql);
        assertTrue(matcher.find(), column + " 的空快照必须保留 IS NULL 条件: " + sql);
        assertFalse(matcher.find(), column + " 的空谓词不得重复");
        assertFalse(Pattern.compile(identifier + "\\s*=").matcher(sql).find(),
                column + " 不得使用 = NULL 或跳过条件");
    }

    private static void assertParameter(Wrapper<?> condition, String sql, String column, Object expected) {
        assertNotNull(sql);
        var matcher = Pattern.compile("(?<![\\w.])" + Pattern.quote(column)
                + "\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}", Pattern.CASE_INSENSITIVE).matcher(sql);
        assertTrue(matcher.find(), column + " 必须以参数绑定: " + sql);
        var parameters = assertInstanceOf(AbstractWrapper.class, condition).getParamNameValuePairs();
        assertTrue(parameters.containsKey(matcher.group(1)), "绑定参数必须存在，不能缺失后读出 null");
        assertEquals(expected, parameters.get(matcher.group(1)), column);
        assertFalse(matcher.find(), column + " 只能出现一次");
    }

    private static void assertMutationPayload(Mutation mutation, Write write, int target, String reason) {
        if (mutation == Mutation.EDIT) {
            assertEditBusinessFields(editRequest(null), write.entity());
            assertNoSystemFieldWrite(write);
            return;
        }
        assertNull(write.entity());
        if (mutation == Mutation.DELETE) {
            return;
        }
        String sqlSet = assertInstanceOf(LambdaUpdateWrapper.class, write.condition()).getSqlSet();
        assertParameter(write.condition(), sqlSet, "status",
                mutation == Mutation.PUBLISH ? MarketDemandDO.STATUS_PUBLISHED : target);
        if (mutation == Mutation.PUBLISH) {
            assertTrue(Pattern.compile("(?i)publish_time\\s*=\\s*clock_timestamp\\(\\)"
                    + "\\s+AT\\s+TIME\\s+ZONE\\s+'Asia/Shanghai'").matcher(sqlSet).find(),
                    "发布时间必须在同条 SQL 中使用数据库时钟: " + sqlSet);
            assertSetColumns(sqlSet, "status", "publish_time");
        } else {
            assertParameter(write.condition(), sqlSet, "close_reason", reason);
            assertSetColumns(sqlSet, "status", "close_reason");
        }
    }

    private static void assertNoSystemFieldWrite(Write write) {
        MarketDemandDO entity = write.entity();
        assertNotNull(entity);
        assertAll(() -> assertNull(entity.getDemandNo()), () -> assertNull(entity.getApplyUserId()),
                () -> assertNull(entity.getStatus()), () -> assertNull(entity.getPublishTime()),
                () -> assertNull(entity.getAcceptanceStatus()), () -> assertNull(entity.getAcceptedApplyId()),
                () -> assertNull(entity.getAcceptedTime()), () -> assertNull(entity.getCloseReason()),
                () -> assertNull(entity.getCreator()), () -> assertNull(entity.getUpdater()),
                () -> assertNull(entity.getCreateTime()), () -> assertNull(entity.getUpdateTime()));
        if (write.condition() instanceof LambdaUpdateWrapper<?> update) {
            String sqlSet = update.getSqlSet();
            assertTrue(sqlSet == null || sqlSet.isBlank(), "编辑的 SET 只能来自请求映射实体");
        }
    }

    private static void assertSetColumns(String sql, String... expected) {
        var matcher = Pattern.compile("(?<![\\w.])([a-z_]+)\\s*=", Pattern.CASE_INSENSITIVE).matcher(sql);
        List<String> actual = matcher.results().map(result -> result.group(1)).sorted().toList();
        assertEquals(Arrays.stream(expected).sorted().toList(), actual,
                "只允许写本次操作明确指定的列，不能回写快照或验收字段");
    }

    private static void assertEditBusinessFields(MarketDemandSaveReqVO req, MarketDemandDO entity) {
        assertNotNull(entity);
        assertAll(() -> assertEquals(req.getId(), entity.getId()),
                () -> assertEquals(req.getTitle(), entity.getTitle()),
                () -> assertEquals(req.getDemandType(), entity.getDemandType()),
                () -> assertEquals(req.getDescription(), entity.getDescription()),
                () -> assertEquals(req.getPurpose(), entity.getPurpose()),
                () -> assertEquals(req.getExpectedFields(), entity.getExpectedFields()),
                () -> assertEquals(req.getExpectedUpdateFreq(), entity.getExpectedUpdateFreq()),
                () -> assertEquals(req.getExpectedDelivery(), entity.getExpectedDelivery()),
                () -> assertEquals(req.getExpectFinishTime(), entity.getExpectFinishTime()),
                () -> assertEquals(req.getUrgency(), entity.getUrgency()),
                () -> assertEquals(req.getApplyDeptId(), entity.getApplyDeptId()),
                () -> assertEquals(req.getPhone(), entity.getPhone()),
                () -> assertEquals(req.getEmail(), entity.getEmail()),
                () -> assertEquals(req.getRemark(), entity.getRemark()));
    }

    private static MarketDemandSaveReqVO editRequest(Boolean publish) {
        MarketDemandSaveReqVO req = new MarketDemandSaveReqVO();
        req.setId(DEMAND_ID);
        req.setTitle("修改后的测试需求");
        req.setDemandType(MarketDemandDO.TYPE_EXISTING_SHARE);
        req.setDescription("单测需求描述");
        req.setPurpose("单测统计用途");
        req.setExpectedFields("id,name");
        req.setExpectedUpdateFreq("每天");
        req.setExpectedDelivery(MarketDemandDO.DELIVERY_API);
        req.setExpectFinishTime(ACCEPTED_TIME.plusDays(10));
        req.setUrgency(MarketDemandDO.URGENCY_HIGH);
        req.setApplyDeptId(DEPT_ID);
        req.setPhone("test-contact");
        req.setEmail("mutation-test@example.invalid");
        req.setPublish(publish);
        req.setRemark("单测备注");
        return req;
    }

    private static MarketDemandCloseReqVO closeRequest(Integer target, String reason) {
        MarketDemandCloseReqVO req = new MarketDemandCloseReqVO();
        req.setId(DEMAND_ID);
        req.setStatus(target);
        req.setCloseReason(reason);
        return req;
    }

    private static void assertError(ErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        ServiceException actual = assertThrows(ServiceException.class, action);
        assertEquals(expected.getCode(), actual.getCode());
    }

    private record Write(MarketDemandDO entity, Wrapper<MarketDemandDO> condition) {
    }

    private enum Mutation {
        EDIT("updateDemand", MarketDemandSaveReqVO.class, List.of(0, 1, 2)),
        DELETE("deleteDemand", Long.class, List.of(0, 5)),
        PUBLISH("publishDemand", Long.class, List.of(0)),
        CLOSE("closeDemand", MarketDemandCloseReqVO.class, OPEN_STATUSES);

        private final String method;
        private final Class<?> requestType;
        private final List<Integer> allowedStatuses;

        Mutation(String method, Class<?> requestType, List<Integer> allowedStatuses) {
            this.method = method;
            this.requestType = requestType;
            this.allowedStatuses = allowedStatuses;
        }
    }

    private enum NullableField {
        DEPARTMENT, ACCEPTANCE_STATUS, ACCEPTED_APPLY, ACCEPTED_TIME;

        void clear(MarketDemandDO demand) {
            switch (this) {
                case DEPARTMENT -> demand.setApplyDeptId(null);
                case ACCEPTANCE_STATUS -> demand.setAcceptanceStatus(null);
                case ACCEPTED_APPLY -> demand.setAcceptedApplyId(null);
                case ACCEPTED_TIME -> demand.setAcceptedTime(null);
            }
        }
    }
}
