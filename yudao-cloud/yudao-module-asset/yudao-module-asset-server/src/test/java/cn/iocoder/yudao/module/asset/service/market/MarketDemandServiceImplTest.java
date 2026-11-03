package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDemandCloseReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDemandMatchConfirmReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDemandPageReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDemandDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDemandMatchDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDemandMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDemandMatchMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDemandReplyMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
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
import org.mockito.MockedStatic;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class MarketDemandServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, MarketDemandDO.class);
        TableInfoHelper.initTableInfo(assistant, MarketDemandMatchDO.class);
    }

    @InjectMocks
    private final MarketDemandServiceImpl service = new MarketDemandServiceImpl();
    @Spy
    private final MarketAccessServiceImpl accessService = new MarketAccessServiceImpl();
    private final MarketDemandMapper demandMapper = mock(MarketDemandMapper.class);
    private final MarketDemandMatchMapper matchMapper = mock(MarketDemandMatchMapper.class);
    private final MarketDemandReplyMapper replyMapper = mock(MarketDemandReplyMapper.class);
    private final DataAssetMapper assetMapper = mock(DataAssetMapper.class);
    private final MarketApplyService applyService = mock(MarketApplyService.class);
    private final MarketResourceApplyMapper applyMapper = mock(MarketResourceApplyMapper.class);
    private final MarketDeliveryService deliveryService = mock(MarketDeliveryService.class);
    private final MarketDisplayHelper displayHelper = new MarketDisplayHelper();
    private final MarketObjectGuard objectGuard = mock(MarketObjectGuard.class);
    private final AdminUserApi adminUserApi = mock(AdminUserApi.class);
    private final MarketNotifyHelper notifyHelper = mock(MarketNotifyHelper.class);
    private MockedStatic<SecurityFrameworkUtils> security;
    private AutoCloseable injectedMocks;

    @BeforeEach
    void setup() {
        TenantContextHolder.clear();
        // 注入现有授权服务；修复前需求服务尚未引用它，反例仍须失败于业务断言而非缺失字段。
        injectedMocks = MockitoAnnotations.openMocks(this);
        ReflectionTestUtils.setField(accessService, "applyMapper", applyMapper);
        when(applyMapper.databaseNow()).thenAnswer(call -> LocalDateTime.now());
        security = mockStatic(SecurityFrameworkUtils.class);
        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(11L);
        security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(100L);
        when(objectGuard.requireUserId()).thenReturn(11L);
        ReflectionTestUtils.setField(service, "demandMapper", demandMapper);
        ReflectionTestUtils.setField(service, "matchMapper", matchMapper);
        ReflectionTestUtils.setField(service, "replyMapper", replyMapper);
        ReflectionTestUtils.setField(service, "assetMapper", assetMapper);
        ReflectionTestUtils.setField(service, "applyService", applyService);
        ReflectionTestUtils.setField(service, "applyMapper", applyMapper);
        ReflectionTestUtils.setField(service, "deliveryService", deliveryService);
        ReflectionTestUtils.setField(service, "resourceService", mock(MarketResourceService.class));
        ReflectionTestUtils.setField(service, "displayHelper", displayHelper);
        ReflectionTestUtils.setField(service, "objectGuard", objectGuard);
        ReflectionTestUtils.setField(service, "adminUserApi", adminUserApi);
        ReflectionTestUtils.setField(service, "notifyHelper", notifyHelper);
    }

    @AfterEach
    void close() throws Exception {
        try {
            security.close();
            injectedMocks.close();
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void hallIgnoresDraftStatusFilter() {
        MarketDemandPageReqVO req = new MarketDemandPageReqVO();
        req.setIfSelf(0);
        req.setStatus(MarketDemandDO.STATUS_DRAFT);
        var page = service.getDemandPage(req);
        assertTrue(page.getList() == null || page.getList().isEmpty());
        verifyNoInteractions(demandMapper);
    }

    @Test
    void closeRejectsUnknownStatus() {
        MarketDemandDO demand = new MarketDemandDO();
        demand.setId(1L);
        demand.setApplyUserId(11L);
        demand.setStatus(MarketDemandDO.STATUS_PUBLISHED);
        when(demandMapper.selectById(1L)).thenReturn(demand);
        MarketDemandCloseReqVO req = new MarketDemandCloseReqVO();
        req.setId(1L);
        req.setStatus(99);
        assertThrows(ServiceException.class, () -> service.closeDemand(req));
    }

    @Test
    void lateConfirmOnClosedDemandIsRejected() {
        MarketDemandMatchDO match = new MarketDemandMatchDO();
        match.setId(5L);
        match.setDemandId(1L);
        match.setStatus(MarketDemandMatchDO.STATUS_PENDING);
        when(matchMapper.selectById(5L)).thenReturn(match);
        MarketDemandDO demand = new MarketDemandDO();
        demand.setId(1L);
        demand.setApplyUserId(11L);
        demand.setStatus(MarketDemandDO.STATUS_CLOSED);
        when(demandMapper.selectById(1L)).thenReturn(demand);
        MarketDemandMatchConfirmReqVO req = new MarketDemandMatchConfirmReqVO();
        req.setId(5L);
        req.setStatus(MarketDemandMatchDO.STATUS_ACCEPTED);
        assertThrows(ServiceException.class, () -> service.confirmMatch(req));
        verify(matchMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(applyService, never()).createApplyFromDemand(any(), any(), any(), any(), any());
    }

    @Test
    void confirmMatchCreatesApplyDraftNotPending() {
        MarketDemandMatchDO match = new MarketDemandMatchDO();
        match.setId(5L);
        match.setDemandId(1L);
        match.setAssetId(8L);
        match.setStatus(MarketDemandMatchDO.STATUS_PENDING);
        when(matchMapper.selectById(5L)).thenReturn(match);
        MarketDemandDO demand = new MarketDemandDO();
        demand.setId(1L);
        demand.setApplyUserId(11L);
        demand.setApplyDeptId(100L);
        demand.setPurpose("统计");
        demand.setStatus(MarketDemandDO.STATUS_MATCHING);
        when(demandMapper.selectById(1L)).thenReturn(demand);
        when(matchMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(demandMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        MarketResourceApplyDO draft = new MarketResourceApplyDO();
        draft.setId(90L);
        draft.setStatus(MarketResourceApplyDO.STATUS_DRAFT);
        when(applyService.createApplyFromDemand(1L, 8L, 11L, 100L, "统计")).thenReturn(draft);
        var asset = confirmationAsset();
        when(assetMapper.selectById(8L)).thenReturn(asset);
        when(assetMapper.selectForMatching( 8L)).thenReturn(asset);
        MarketDemandMatchConfirmReqVO req = new MarketDemandMatchConfirmReqVO();
        req.setId(5L);
        req.setStatus(MarketDemandMatchDO.STATUS_ACCEPTED);
        req.setCreateApply(true);
        service.confirmMatch(req);
        verify(applyService).createApplyFromDemand(1L, 8L, 11L, 100L, "统计");
    }

    @ParameterizedTest(name = "confirm {0} from status {1} -> {2}")
    @MethodSource("confirmModesAndHallStatuses")
    void confirmMatchChecksOriginalBindingsAndDemandSnapshotInWriteOrder(
            ConfirmMode mode, int originalStatus, int expectedStatus) {
        var fixture = readyConfirmation(originalStatus, mode);
        var firstWrite = ArgumentCaptor.<LambdaUpdateWrapper<MarketDemandMatchDO>>captor();
        var backfill = ArgumentCaptor.<LambdaUpdateWrapper<MarketDemandMatchDO>>captor();
        var demandCas = ArgumentCaptor.<LambdaUpdateWrapper<MarketDemandDO>>captor();

        service.confirmMatch(fixture.request());

        var calls = inOrder(matchMapper, demandMapper, applyService, assetMapper, notifyHelper);
        calls.verify(matchMapper).selectById(5L);
        calls.verify(demandMapper).selectById(1L);
        if (mode != ConfirmMode.REJECT) {
            calls.verify(assetMapper).selectById(8L);
        }
        calls.verify(matchMapper).update(isNull(), firstWrite.capture());
        if (mode == ConfirmMode.ACCEPT_AND_CREATE) {
            calls.verify(applyService).createApplyFromDemand(1L, 8L, 11L, 100L, "统计");
            calls.verify(matchMapper).update(isNull(), backfill.capture());
            assertSqlEquals(backfill.getValue(), "id", 5L);
            assertSqlEquals(backfill.getValue(), "demand_id", 1L);
            assertSqlEquals(backfill.getValue(), "asset_id", 8L);
            assertSqlEquals(backfill.getValue(), "apply_id", 9L);
            assertSqlEquals(backfill.getValue(), "matcher_user_id", 31L);
            assertSqlEquals(backfill.getValue(), "status", MarketDemandMatchDO.STATUS_ACCEPTED);
            assertSqlAssignment(backfill.getValue(), "apply_id", 90L);
        } else {
            verifyNoInteractions(applyService);
        }
        if (mode != ConfirmMode.REJECT) {
            calls.verify(assetMapper).selectForMatching( 8L);
        }
        calls.verify(demandMapper).update(isNull(), demandCas.capture());
        if (mode == ConfirmMode.REJECT) {
            calls.verify(assetMapper).selectById(8L);
        }
        calls.verify(notifyHelper).notifyDemandConfirmed(same(fixture.demand()), same(fixture.match()),
                same(fixture.asset()), eq(mode == ConfirmMode.REJECT ? "拒绝" : "接受"));
        calls.verifyNoMoreInteractions();

        assertSqlEquals(firstWrite.getValue(), "id", 5L);
        assertSqlEquals(firstWrite.getValue(), "status", MarketDemandMatchDO.STATUS_PENDING);
        assertSqlEquals(firstWrite.getValue(), "demand_id", 1L);
        assertSqlEquals(firstWrite.getValue(), "asset_id", 8L);
        assertSqlEquals(firstWrite.getValue(), "apply_id", 9L);
        assertSqlEquals(firstWrite.getValue(), "matcher_user_id", 31L);
        assertSqlAssignment(firstWrite.getValue(), "status", mode.status);
        assertSqlAssignment(firstWrite.getValue(), "confirm_user_id", 11L);
        assertSqlAssignment(firstWrite.getValue(), "confirm_note", "确认测试");
        assertTrue(Pattern.compile("confirm_time\\s*=\\s*clock_timestamp\\(\\)", Pattern.CASE_INSENSITIVE)
                .matcher(firstWrite.getValue().getSqlSet()).find(), "confirmation time must use the database clock");

        assertSqlEquals(demandCas.getValue(), "id", 1L);
        assertSqlEquals(demandCas.getValue(), "status", originalStatus);
        assertSqlEquals(demandCas.getValue(), "apply_user_id", 11L);
        assertSqlEquals(demandCas.getValue(), "apply_dept_id", 100L);
        assertSqlEquals(demandCas.getValue(), "title", "测试需求");
        assertSqlEquals(demandCas.getValue(), "purpose", "统计");
        assertSqlEquals(demandCas.getValue(), "expected_fields", "id,name");
        assertSqlEquals(demandCas.getValue(), "acceptance_status", MarketDemandDO.ACCEPTANCE_NONE);
        assertSqlAssignment(demandCas.getValue(), "status", expectedStatus);
    }

    private static Stream<Arguments> confirmModesAndHallStatuses() {
        return Arrays.stream(ConfirmMode.values()).flatMap(mode -> Stream.of(
                MarketDemandDO.STATUS_PUBLISHED, MarketDemandDO.STATUS_MATCHING,
                MarketDemandDO.STATUS_MATCHED, MarketDemandDO.STATUS_APPLYING).map(status -> {
                    int target = mode == ConfirmMode.ACCEPT_AND_CREATE ? MarketDemandDO.STATUS_APPLYING
                            : mode == ConfirmMode.ACCEPT_ONLY && (status == MarketDemandDO.STATUS_PUBLISHED
                            || status == MarketDemandDO.STATUS_MATCHING) ? MarketDemandDO.STATUS_MATCHED : status;
                    return Arguments.of(mode, status, target);
                }));
    }

    @ParameterizedTest(name = "confirm {0} rejects non-hall status {1}")
    @MethodSource("confirmModesAndNonHallStatuses")
    void confirmMatchRejectsNonHallDemandBeforeAnyWrite(ConfirmMode mode, int status) {
        var fixture = readyConfirmation(status, mode);

        assertEquals(MARKET_DEMAND_STATUS_DENY.getCode(),
                assertThrows(ServiceException.class, () -> service.confirmMatch(fixture.request())).getCode());

        verify(matchMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(demandMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        verifyNoInteractions(applyService, assetMapper, notifyHelper);
    }

    private static Stream<Arguments> confirmModesAndNonHallStatuses() {
        return Arrays.stream(ConfirmMode.values()).flatMap(mode -> Stream.of(
                MarketDemandDO.STATUS_DRAFT, MarketDemandDO.STATUS_CLOSED, MarketDemandDO.STATUS_CANCELLED,
                MarketDemandDO.STATUS_REJECTED, 99).map(status -> Arguments.of(mode, status)));
    }

    @ParameterizedTest(name = "confirm {0} rejects null demand status")
    @EnumSource(ConfirmMode.class)
    void confirmMatchRejectsNullDemandStatusBeforeAnyWrite(ConfirmMode mode) {
        var fixture = readyConfirmation(MarketDemandDO.STATUS_MATCHING, mode);
        fixture.demand().setStatus(null);

        assertEquals(MARKET_DEMAND_STATUS_DENY.getCode(),
                assertThrows(ServiceException.class, () -> service.confirmMatch(fixture.request())).getCode());

        verify(matchMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(demandMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        verifyNoInteractions(applyService, assetMapper, notifyHelper);
    }

    @ParameterizedTest(name = "confirm rejects match initial status {0}")
    @NullSource
    @ValueSource(ints = {MarketDemandMatchDO.STATUS_ACCEPTED, MarketDemandMatchDO.STATUS_REJECTED, 99})
    void confirmMatchRejectsNonPendingMatchBeforeAnyWrite(Integer status) {
        var fixture = readyConfirmation(MarketDemandDO.STATUS_MATCHING, ConfirmMode.ACCEPT_AND_CREATE);
        fixture.match().setStatus(status);

        assertEquals(MARKET_DEMAND_STATUS_DENY.getCode(),
                assertThrows(ServiceException.class, () -> service.confirmMatch(fixture.request())).getCode());

        verify(matchMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(demandMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        verifyNoInteractions(applyService, assetMapper, notifyHelper);
    }

    @ParameterizedTest(name = "confirm {0}: {1} affects {2} rows")
    @MethodSource("confirmUnexpectedAffectedRows")
    void confirmMatchRejectsAnyNonSingleRowWriteWithoutNotification(
            ConfirmMode mode, ConfirmStage stage, int affectedRows) {
        var fixture = readyConfirmation(MarketDemandDO.STATUS_APPLYING, mode);
        if (stage == ConfirmStage.FIRST_WRITE) {
            when(matchMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(affectedRows);
        } else if (stage == ConfirmStage.BACKFILL) {
            when(matchMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1, affectedRows);
        } else {
            when(demandMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(affectedRows);
        }

        assertEquals(MARKET_DEMAND_STATUS_DENY.getCode(),
                assertThrows(ServiceException.class, () -> service.confirmMatch(fixture.request())).getCode());

        assertConfirmationStoppedAt(mode, stage);
        // Mockito 只证明调用边界；数据库写入的整笔回滚由真实 PostgreSQL 专测验证。
    }

    private static Stream<Arguments> confirmUnexpectedAffectedRows() {
        return Stream.of(0, 2).flatMap(rows -> Stream.concat(
                Stream.of(Arguments.of(ConfirmMode.ACCEPT_AND_CREATE, ConfirmStage.BACKFILL, rows)),
                Arrays.stream(ConfirmMode.values()).flatMap(mode ->
                        Stream.of(ConfirmStage.FIRST_WRITE, ConfirmStage.DEMAND_CAS)
                                .map(stage -> Arguments.of(mode, stage, rows)))));
    }

    @ParameterizedTest(name = "confirm {0} keeps nullable snapshot predicates")
    @EnumSource(ConfirmMode.class)
    void confirmMatchUsesIsNullRatherThanOmittingNullableOriginalFields(ConfirmMode mode) {
        var fixture = readyConfirmation(MarketDemandDO.STATUS_MATCHING, mode);
        fixture.match().setApplyId(null);
        fixture.match().setMatcherUserId(null);
        fixture.demand().setApplyDeptId(null);
        fixture.demand().setTitle(null);
        fixture.demand().setPurpose(null);
        fixture.demand().setExpectedFields(null);
        fixture.demand().setAcceptanceStatus(null);
        var matchWrites = ArgumentCaptor.<LambdaUpdateWrapper<MarketDemandMatchDO>>captor();
        var demandCas = ArgumentCaptor.<LambdaUpdateWrapper<MarketDemandDO>>captor();

        service.confirmMatch(fixture.request());

        verify(matchMapper, times(mode == ConfirmMode.ACCEPT_AND_CREATE ? 2 : 1))
                .update(isNull(), matchWrites.capture());
        var firstWrite = matchWrites.getAllValues().getFirst();
        assertSqlEquals(firstWrite, "id", 5L);
        assertSqlEquals(firstWrite, "demand_id", 1L);
        assertSqlEquals(firstWrite, "asset_id", 8L);
        assertSqlEquals(firstWrite, "status", MarketDemandMatchDO.STATUS_PENDING);
        assertSqlIsNull(firstWrite, "apply_id");
        assertSqlIsNull(firstWrite, "matcher_user_id");
        verify(demandMapper).update(isNull(), demandCas.capture());
        assertSqlEquals(demandCas.getValue(), "id", 1L);
        assertSqlEquals(demandCas.getValue(), "status", MarketDemandDO.STATUS_MATCHING);
        assertSqlEquals(demandCas.getValue(), "apply_user_id", 11L);
        for (String column : List.of("apply_dept_id", "title", "purpose", "expected_fields", "acceptance_status")) {
            assertSqlIsNull(demandCas.getValue(), column);
        }
        if (mode == ConfirmMode.ACCEPT_AND_CREATE) {
            var backfill = matchWrites.getAllValues().get(1);
            assertSqlEquals(backfill, "id", 5L);
            assertSqlEquals(backfill, "demand_id", 1L);
            assertSqlEquals(backfill, "asset_id", 8L);
            assertSqlEquals(backfill, "status", MarketDemandMatchDO.STATUS_ACCEPTED);
            assertSqlIsNull(backfill, "apply_id");
            assertSqlIsNull(backfill, "matcher_user_id");
            assertSqlAssignment(backfill, "apply_id", 90L);
            verify(applyService).createApplyFromDemand(1L, 8L, 11L, null, null);
        } else {
            verifyNoInteractions(applyService);
        }
        verify(notifyHelper).notifyDemandConfirmed(same(fixture.demand()), same(fixture.match()),
                same(fixture.asset()), eq(mode == ConfirmMode.REJECT ? "拒绝" : "接受"));
    }

    @ParameterizedTest(name = "confirm propagates failure at {0}")
    @MethodSource("confirmationFailures")
    void confirmMatchPropagatesOriginalFailureAndStopsBeforeNotification(ConfirmStage stage, Throwable failure) {
        var fixture = readyConfirmation(MarketDemandDO.STATUS_MATCHED, ConfirmMode.ACCEPT_AND_CREATE);
        switch (stage) {
            case FIRST_WRITE -> when(matchMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenThrow(failure);
            case CREATE_APPLY -> when(applyService.createApplyFromDemand(any(), any(), any(), any(), any()))
                    .thenThrow(failure);
            case BACKFILL -> when(matchMapper.update(isNull(), any(LambdaUpdateWrapper.class)))
                    .thenReturn(1).thenThrow(failure);
            case DEMAND_CAS -> when(demandMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenThrow(failure);
        }

        assertSame(failure, assertThrows(failure.getClass(), () -> service.confirmMatch(fixture.request())));

        assertConfirmationStoppedAt(ConfirmMode.ACCEPT_AND_CREATE, stage);
    }

    private static Stream<Arguments> confirmationFailures() {
        return Stream.of(
                Arguments.of(ConfirmStage.FIRST_WRITE, new CannotAcquireLockException("test match write failed")),
                Arguments.of(ConfirmStage.CREATE_APPLY,
                        new ServiceException(MARKET_APPLY_NOT_AUTHORIZED.getCode(), "test apply denied")),
                Arguments.of(ConfirmStage.BACKFILL, new IllegalStateException("test apply backfill failed")),
                Arguments.of(ConfirmStage.DEMAND_CAS, new AssertionError("test fatal demand CAS failure")));
    }

    @Test
    void confirmMatchRetainsSingleRequiredTransactionWithBoundedTimeout() throws Exception {
        var transaction = MarketDemandServiceImpl.class
                .getMethod("confirmMatch", MarketDemandMatchConfirmReqVO.class).getAnnotation(Transactional.class);

        assertNotNull(transaction);
        assertEquals(10, transaction.timeout());
        assertEquals(Propagation.REQUIRED, transaction.propagation());
        assertTrue(Arrays.asList(transaction.rollbackFor()).contains(Exception.class));
        // 注解契约不能替代真实代理事务与回滚验证。
    }

    @ParameterizedTest(name = "confirm {0} asset locked={1} state={2}")
    @MethodSource("confirmationAssetAdmissionCases")
    void confirmationRejectsUnavailableAssetAtEachRead(
            ConfirmMode mode, boolean lockedRead, ConfirmationAssetState state) {
        var fixture = readyConfirmation(MarketDemandDO.STATUS_MATCHING, mode);
        var observed = copyConfirmationAsset(fixture.asset());
        switch (state) {
            case MISSING -> observed = null;
            case DELETED -> {
                observed.setDeleted(true);
                observed.setStatus(DataAssetDO.STATUS_OFFLINE);
                observed.setOwnerDeptId(999L);
            }
            case UNPUBLISHED -> observed.setStatus(DataAssetDO.STATUS_UNPUBLISHED);
            case OFFLINE -> observed.setStatus(DataAssetDO.STATUS_OFFLINE);
            case NULL_STATUS -> observed.setStatus(null);
            case UNKNOWN_STATUS -> observed.setStatus(99);
        }
        if (observed != null && state != ConfirmationAssetState.DELETED) {
            // 发布错误优先于快照变化错误。
            observed.setOwnerDeptId(999L);
        }
        if (lockedRead) {
            when(assetMapper.selectForMatching( 8L)).thenReturn(observed);
        } else {
            when(assetMapper.selectById(8L)).thenReturn(observed);
        }

        int expectedCode = state == ConfirmationAssetState.MISSING || state == ConfirmationAssetState.DELETED
                ? DATA_ASSET_NOT_EXISTS.getCode() : MARKET_APPLY_ASSET_NOT_PUBLISHED.getCode();
        assertEquals(expectedCode,
                assertThrows(ServiceException.class, () -> service.confirmMatch(fixture.request())).getCode());

        assertConfirmationAssetBoundary(mode, lockedRead, lockedRead);
    }

    private static Stream<Arguments> confirmationAssetAdmissionCases() {
        return acceptedConfirmationModes().flatMap(mode -> Stream.of(false, true).flatMap(locked ->
                Arrays.stream(ConfirmationAssetState.values()).map(state -> Arguments.of(mode, locked, state))));
    }

    @ParameterizedTest(name = "confirm {0} rejects null asset association")
    @EnumSource(value = ConfirmMode.class, names = {"ACCEPT_AND_CREATE", "ACCEPT_ONLY"})
    void confirmationRejectsNullAssetBindingWithoutReadingOrWritingAsset(ConfirmMode mode) {
        var fixture = readyConfirmation(MarketDemandDO.STATUS_MATCHING, mode);
        fixture.match().setAssetId(null);

        assertEquals(DATA_ASSET_NOT_EXISTS.getCode(),
                assertThrows(ServiceException.class, () -> service.confirmMatch(fixture.request())).getCode());

        verify(matchMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(demandMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        verifyNoInteractions(assetMapper, applyService, notifyHelper);
    }

    @ParameterizedTest(name = "confirm {0} rejects changed {1}, null transition={2}")
    @MethodSource("confirmationAssetChanges")
    void confirmationRejectsChangedBindingOwnershipOrVersion(
            ConfirmMode mode, ConfirmationAssetField field, int nullTransition) {
        var fixture = readyConfirmation(MarketDemandDO.STATUS_MATCHING, mode);
        ReflectionTestUtils.setField(fixture.asset(), field.name(), nullTransition == 2 ? null : field.original());
        var current = copyConfirmationAsset(fixture.asset());
        ReflectionTestUtils.setField(current, field.name(), nullTransition == 1 ? null : field.changed());
        when(assetMapper.selectForMatching( 8L)).thenReturn(current);

        assertEquals(MARKET_DEMAND_STATUS_DENY.getCode(),
                assertThrows(ServiceException.class, () -> service.confirmMatch(fixture.request())).getCode());

        assertConfirmationAssetBoundary(mode, true, true);
    }

    private static Stream<Arguments> confirmationAssetChanges() {
        // 完整 23 字段矩阵由 MarketDemandContributionTest 覆盖；这里证明两个确认分支都复用同一比较器。
        return acceptedConfirmationModes().flatMap(mode -> confirmationAssetFields().flatMap(field ->
                Stream.of(0, 1, 2).map(nullTransition -> Arguments.of(mode, field, nullTransition))));
    }

    @ParameterizedTest(name = "confirm {0} accepts unchanged nullable asset snapshot")
    @EnumSource(value = ConfirmMode.class, names = {"ACCEPT_AND_CREATE", "ACCEPT_ONLY"})
    void confirmationAcceptsUnchangedNullSnapshotAndDeletedFlag(ConfirmMode mode) {
        var fixture = readyConfirmation(MarketDemandDO.STATUS_MATCHING, mode);
        confirmationAssetFields().forEach(field -> ReflectionTestUtils.setField(fixture.asset(), field.name(), null));
        var current = copyConfirmationAsset(fixture.asset());
        current.setDeleted(null);
        when(assetMapper.selectForMatching( 8L)).thenReturn(current);

        service.confirmMatch(fixture.request());

        verify(assetMapper).selectById(8L);
        verify(assetMapper).selectForMatching( 8L);
        verifyNoMoreInteractions(assetMapper);
        verify(notifyHelper).notifyDemandConfirmed(same(fixture.demand()), same(fixture.match()),
                same(current), eq("接受"));
    }

    @ParameterizedTest(name = "confirm {0} notifies using locked current asset")
    @EnumSource(value = ConfirmMode.class, names = {"ACCEPT_AND_CREATE", "ACCEPT_ONLY"})
    void confirmationUsesLockedAssetForNotificationAndAllowsInformationalChanges(ConfirmMode mode) {
        var fixture = readyConfirmation(MarketDemandDO.STATUS_MATCHING, mode);
        var current = copyConfirmationAsset(fixture.asset());
        current.setAssetName("锁内最新资产名称");
        current.setDescription("仅展示描述发生变化");
        current.setViewCount(900L);
        when(assetMapper.selectForMatching( 8L)).thenReturn(current);

        service.confirmMatch(fixture.request());

        var order = inOrder(assetMapper, demandMapper, notifyHelper);
        order.verify(demandMapper).selectById(1L);
        order.verify(assetMapper).selectById(8L);
        order.verify(assetMapper).selectForMatching( 8L);
        order.verify(demandMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        order.verify(notifyHelper).notifyDemandConfirmed(same(fixture.demand()), same(fixture.match()),
                same(current), eq("接受"));
        order.verifyNoMoreInteractions();
        verifyNoMoreInteractions(assetMapper);
        assertNotSame(fixture.asset(), current);
        assertEquals("锁内最新资产名称", current.getAssetName());
        verifyNoInteractions(adminUserApi, deliveryService, applyMapper, replyMapper);
    }

    @ParameterizedTest(name = "confirm {0} propagates asset failure locked={1}: {2}")
    @MethodSource("confirmationAssetReadFailures")
    void confirmationPropagatesAssetReadFailureWithoutRetry(
            ConfirmMode mode, boolean lockedRead, Throwable failure) {
        var fixture = readyConfirmation(MarketDemandDO.STATUS_MATCHING, mode);
        if (lockedRead) {
            when(assetMapper.selectForMatching( 8L)).thenThrow(failure);
        } else {
            when(assetMapper.selectById(8L)).thenThrow(failure);
        }

        assertSame(failure, assertThrows(failure.getClass(), () -> service.confirmMatch(fixture.request())));

        assertConfirmationAssetBoundary(mode, lockedRead, lockedRead);
    }

    private static Stream<Arguments> confirmationAssetReadFailures() {
        return acceptedConfirmationModes().flatMap(mode -> Stream.of(false, true).flatMap(locked ->
                Stream.of(new CannotAcquireLockException("test asset read failed"),
                        new AssertionError("test fatal asset read failure"))
                        .map(failure -> Arguments.of(mode, locked, failure))));
    }

    @ParameterizedTest(name = "reject keeps unavailable asset handling: {0}")
    @ValueSource(strings = {"MISSING", "DELETED", "OFFLINE", "NULL_STATUS", "NULL_BINDING"})
    void rejectDoesNotRequireAssetAdmissionOrAssetLock(String state) {
        var fixture = readyConfirmation(MarketDemandDO.STATUS_MATCHING, ConfirmMode.REJECT);
        var observed = copyConfirmationAsset(fixture.asset());
        switch (state) {
            case "MISSING" -> observed = null;
            case "DELETED" -> observed.setDeleted(true);
            case "OFFLINE" -> observed.setStatus(DataAssetDO.STATUS_OFFLINE);
            case "NULL_STATUS" -> observed.setStatus(null);
            case "NULL_BINDING" -> {
                fixture.match().setAssetId(null);
                observed = null;
            }
            default -> throw new AssertionError(state);
        }
        when(assetMapper.selectById(8L)).thenReturn(observed);

        service.confirmMatch(fixture.request());

        var order = inOrder(matchMapper, demandMapper, assetMapper, notifyHelper);
        order.verify(matchMapper).selectById(5L);
        order.verify(demandMapper).selectById(1L);
        order.verify(matchMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        order.verify(demandMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        if (!state.equals("NULL_BINDING")) {
            order.verify(assetMapper).selectById(8L);
        } else {
            verifyNoInteractions(assetMapper);
        }
        order.verify(notifyHelper).notifyDemandConfirmed(same(fixture.demand()), same(fixture.match()),
                same(observed), eq("拒绝"));
        order.verifyNoMoreInteractions();
        verify(assetMapper, never()).selectForMatching( anyLong());
        verifyNoMoreInteractions(assetMapper);
        verifyNoInteractions(applyService);
    }

    @ParameterizedTest(name = "confirm {0} locks only trusted tenant {1}")
    @MethodSource("confirmationTrustedTenants")
    void confirmationUsesExactTrustedTenantForAssetLock(ConfirmMode mode, long tenantId) {
        var fixture = readyConfirmation(MarketDemandDO.STATUS_MATCHING, mode);
        TenantContextHolder.clear();
        when(assetMapper.selectForMatching( 8L)).thenReturn(fixture.asset());

        service.confirmMatch(fixture.request());

        verify(assetMapper).selectById(8L);
        verify(assetMapper).selectForMatching( 8L);
        verifyNoMoreInteractions(assetMapper);
        verify(notifyHelper).notifyDemandConfirmed(same(fixture.demand()), same(fixture.match()),
                same(fixture.asset()), eq("接受"));
    }

    private static Stream<Arguments> confirmationTrustedTenants() {
        return acceptedConfirmationModes().flatMap(mode ->
                Stream.of(0L, 9_007_199_254_740_993L).map(tenant -> Arguments.of(mode, tenant)));
    }



    private static Stream<Arguments> confirmationUntrustedTenants() {
        return acceptedConfirmationModes().flatMap(mode -> Stream.of(
                Arguments.of(mode, null, false), Arguments.of(mode, -1L, false), Arguments.of(mode, 1L, true)));
    }

    @ParameterizedTest
    @EnumSource(ConfirmMode.class)
    void confirmationChecksOwnerBeforeAssetAdmission(ConfirmMode mode) {
        var fixture = readyConfirmation(MarketDemandDO.STATUS_MATCHING, mode);
        fixture.demand().setApplyUserId(22L);

        assertEquals(MARKET_DEMAND_FORBIDDEN.getCode(),
                assertThrows(ServiceException.class, () -> service.confirmMatch(fixture.request())).getCode());

        verify(matchMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(demandMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        verifyNoInteractions(assetMapper, applyService, notifyHelper);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {MarketDemandMatchDO.STATUS_PENDING, 99})
    void confirmationChecksRequestedStatusBeforeAssetAdmission(Integer status) {
        var fixture = readyConfirmation(MarketDemandDO.STATUS_MATCHING, ConfirmMode.ACCEPT_AND_CREATE);
        fixture.request().setStatus(status);

        assertEquals(MARKET_DEMAND_STATUS_DENY.getCode(),
                assertThrows(ServiceException.class, () -> service.confirmMatch(fixture.request())).getCode());

        verify(matchMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(demandMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        verifyNoInteractions(assetMapper, applyService, notifyHelper);
    }

    @Test
    void confirmationMissingMatchDoesNotReadDemandOrAsset() {
        var request = new MarketDemandMatchConfirmReqVO();
        request.setId(5L);
        request.setStatus(MarketDemandMatchDO.STATUS_ACCEPTED);
        request.setCreateApply(true);

        assertEquals(MARKET_DEMAND_MATCH_NOT_EXISTS.getCode(),
                assertThrows(ServiceException.class, () -> service.confirmMatch(request)).getCode());

        verify(matchMapper).selectById(5L);
        verifyNoMoreInteractions(matchMapper);
        verifyNoInteractions(demandMapper, assetMapper, applyService, notifyHelper);
    }

    private void assertConfirmationAssetBoundary(ConfirmMode mode, boolean wroteMatch, boolean lockedRead) {
        verify(matchMapper).selectById(5L);
        verify(demandMapper).selectById(1L);
        verify(assetMapper).selectById(8L);
        int writes = wroteMatch ? mode == ConfirmMode.ACCEPT_AND_CREATE ? 2 : 1 : 0;
        verify(matchMapper, times(writes)).update(isNull(), any(LambdaUpdateWrapper.class));
        if (wroteMatch && mode == ConfirmMode.ACCEPT_AND_CREATE) {
            verify(applyService).createApplyFromDemand(1L, 8L, 11L, 100L, "统计");
            verifyNoMoreInteractions(applyService);
        } else {
            verifyNoInteractions(applyService);
        }
        verify(assetMapper, times(lockedRead ? 1 : 0)).selectForMatching( 8L);
        verify(demandMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        verifyNoMoreInteractions(matchMapper, demandMapper, assetMapper);
        verifyNoInteractions(notifyHelper, adminUserApi, deliveryService, applyMapper, replyMapper);
        // Mockito 不证明持久写入回滚，真实事务回滚由主线程 PostgreSQL 专测验证。
    }

    private static Stream<ConfirmMode> acceptedConfirmationModes() {
        return Stream.of(ConfirmMode.ACCEPT_AND_CREATE, ConfirmMode.ACCEPT_ONLY);
    }

    private static Stream<ConfirmationAssetField> confirmationAssetFields() {
        return Stream.of(
                new ConfirmationAssetField("resourceUrn", "urn:test:original", "urn:test:replacement"),
                new ConfirmationAssetField("datasourceId", 21L, 22L),
                new ConfirmationAssetField("ownerDeptId", 100L, 200L),
                new ConfirmationAssetField("publishedVersion", "v1", "v2"),
                new ConfirmationAssetField("catalogRevision", 5L, 6L));
    }

    private static DataAssetDO confirmationAsset() {
        var asset = new DataAssetDO();
        asset.setId(8L);
        asset.setStatus(DataAssetDO.STATUS_PUBLISHED);
        asset.setDeleted(false);
        asset.setAssetName("入口资产名称");
        asset.setAssetType(DataAssetDO.TYPE_DATABASE_TABLE);
        confirmationAssetFields().forEach(field -> ReflectionTestUtils.setField(asset, field.name(), field.original()));
        return asset;
    }

    private static DataAssetDO copyConfirmationAsset(DataAssetDO asset) {
        var copy = new DataAssetDO();
        BeanUtils.copyProperties(asset, copy);
        return copy;
    }

    private record ConfirmationAssetField(String name, Object original, Object changed) {
    }

    private enum ConfirmationAssetState {
        MISSING, DELETED, UNPUBLISHED, OFFLINE, NULL_STATUS, UNKNOWN_STATUS
    }

    private void assertConfirmationStoppedAt(ConfirmMode mode, ConfirmStage stage) {
        boolean createdApply = mode == ConfirmMode.ACCEPT_AND_CREATE && stage != ConfirmStage.FIRST_WRITE;
        int matchWrites = createdApply && stage != ConfirmStage.CREATE_APPLY ? 2 : 1;
        verify(matchMapper, times(matchWrites)).update(isNull(), any(LambdaUpdateWrapper.class));
        if (createdApply) {
            verify(applyService).createApplyFromDemand(1L, 8L, 11L, 100L, "统计");
        } else {
            verifyNoInteractions(applyService);
        }
        verify(demandMapper, times(stage == ConfirmStage.DEMAND_CAS ? 1 : 0))
                .update(isNull(), any(LambdaUpdateWrapper.class));
        if (mode == ConfirmMode.REJECT) {
            verifyNoInteractions(assetMapper);
        } else {
            verify(assetMapper).selectById(8L);
            verify(assetMapper, times(stage == ConfirmStage.DEMAND_CAS ? 1 : 0))
                    .selectForMatching( 8L);
            verifyNoMoreInteractions(assetMapper);
        }
        verifyNoInteractions(notifyHelper);
    }

    private ConfirmFixture readyConfirmation(int status, ConfirmMode mode) {
        var demand = new MarketDemandDO();
        demand.setId(1L);
        demand.setApplyUserId(11L);
        demand.setApplyDeptId(100L);
        demand.setTitle("测试需求");
        demand.setPurpose("统计");
        demand.setExpectedFields("id,name");
        demand.setAcceptanceStatus(MarketDemandDO.ACCEPTANCE_NONE);
        demand.setStatus(status);
        var match = new MarketDemandMatchDO();
        match.setId(5L);
        match.setDemandId(1L);
        match.setAssetId(8L);
        match.setApplyId(9L);
        match.setMatcherUserId(31L);
        match.setStatus(MarketDemandMatchDO.STATUS_PENDING);
        var request = new MarketDemandMatchConfirmReqVO();
        request.setId(5L);
        request.setStatus(mode.status);
        // 拒绝时即使客户端传 true，也不能生成申请。
        request.setCreateApply(mode != ConfirmMode.ACCEPT_ONLY);
        request.setConfirmNote("确认测试");
        var asset = confirmationAsset();
        var draft = new MarketResourceApplyDO();
        draft.setId(90L);
        draft.setStatus(MarketResourceApplyDO.STATUS_DRAFT);
        when(matchMapper.selectById(5L)).thenReturn(match);
        when(demandMapper.selectById(1L)).thenReturn(demand);
        when(matchMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(demandMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(applyService.createApplyFromDemand(any(), any(), any(), any(), any())).thenReturn(draft);
        when(assetMapper.selectById(8L)).thenReturn(asset);
        when(assetMapper.selectForMatching( 8L)).thenReturn(asset);
        return new ConfirmFixture(demand, match, request, asset);
    }

    private record ConfirmFixture(MarketDemandDO demand, MarketDemandMatchDO match,
                                  MarketDemandMatchConfirmReqVO request, DataAssetDO asset) {
    }

    private enum ConfirmMode {
        ACCEPT_AND_CREATE(MarketDemandMatchDO.STATUS_ACCEPTED),
        ACCEPT_ONLY(MarketDemandMatchDO.STATUS_ACCEPTED),
        REJECT(MarketDemandMatchDO.STATUS_REJECTED);

        private final int status;

        ConfirmMode(int status) {
            this.status = status;
        }
    }

    private enum ConfirmStage {
        FIRST_WRITE, CREATE_APPLY, BACKFILL, DEMAND_CAS
    }

    private static void assertSqlEquals(LambdaUpdateWrapper<?> update, String column, Object expected) {
        assertSqlParameter(update, update.getSqlSegment(), column, expected);
    }

    private static void assertSqlAssignment(LambdaUpdateWrapper<?> update, String column, Object expected) {
        assertSqlParameter(update, update.getSqlSet(), column, expected);
    }

    private static void assertSqlParameter(LambdaUpdateWrapper<?> update, String sql, String column, Object expected) {
        var matcher = Pattern.compile("(?<![\\w.])" + Pattern.quote(column)
                + "\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}", Pattern.CASE_INSENSITIVE).matcher(sql);
        assertTrue(matcher.find(), column + " must be a bound equality in " + sql);
        assertEquals(expected, update.getParamNameValuePairs().get(matcher.group(1)), column);
        assertFalse(matcher.find(), column + " must occur exactly once in " + sql);
    }

    private static void assertSqlIsNull(LambdaUpdateWrapper<?> update, String column) {
        String sql = update.getSqlSegment();
        String identifier = "(?<![\\w.])" + Pattern.quote(column);
        assertTrue(Pattern.compile(identifier + "\\s+IS\\s+NULL(?!\\w)", Pattern.CASE_INSENSITIVE)
                .matcher(sql).find(), column + " must retain its null snapshot predicate in " + sql);
        assertFalse(Pattern.compile(identifier + "\\s*=").matcher(sql).find(),
                column + " must not use equality for null");
    }

    @Test
    void acceptDemandRequiresDeliveredApply() {
        MarketDemandDO demand = new MarketDemandDO();
        demand.setId(1L);
        demand.setApplyUserId(11L);
        demand.setStatus(MarketDemandDO.STATUS_MATCHED);
        demand.setAcceptanceStatus(MarketDemandDO.ACCEPTANCE_NONE);
        when(demandMapper.selectById(1L)).thenReturn(demand);
        when(matchMapper.selectListByDemandId(1L)).thenReturn(java.util.List.of());
        assertThrows(ServiceException.class, () -> service.acceptDemand(1L));
        verifyNoInteractions(deliveryService);
        verify(matchMapper, never()).selectForAcceptance( anyLong());
        verify(applyMapper, never()).selectOwnedForRenewal( anyLong(), anyLong());
    }

    @Test
    void acceptDemandCannotTreatLegacyDeliveryIntentAsConfirmed() {
        MarketDemandDO demand = new MarketDemandDO();
        demand.setId(1L);
        demand.setApplyUserId(11L);
        demand.setStatus(MarketDemandDO.STATUS_MATCHED);
        demand.setAcceptanceStatus(MarketDemandDO.ACCEPTANCE_NONE);
        MarketDemandMatchDO match = acceptedMatch();
        MarketResourceApplyDO apply = new MarketResourceApplyDO();
        apply.setId(9L);
        apply.setAssetId(8L);
        apply.setApplyUserId(11L);
        apply.setStatus(MarketResourceApplyDO.STATUS_APPROVED);
        apply.setIfAuth(MarketResourceApplyDO.AUTH_YES);
        apply.setApiId(2L);
        apply.setDeliveryStatus(MarketDeliveryService.DELIVERY_SUCCESS);
        when(demandMapper.selectById(1L)).thenReturn(demand);
        when(matchMapper.selectListByDemandId(1L)).thenReturn(java.util.List.of(match));
        when(applyMapper.selectById(9L)).thenReturn(apply);

        assertThrows(ServiceException.class, () -> service.acceptDemand(1L));
        verify(deliveryService).isConfirmedApply(apply);
        verify(matchMapper, never()).selectForAcceptance( anyLong());
        verify(applyMapper, never()).selectOwnedForRenewal( anyLong(), anyLong());
        verify(demandMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void acceptDemandRequiresConfirmedLedgerAndSuccessfulCas(int affectedRows) {
        MarketDemandDO demand = new MarketDemandDO();
        demand.setId(1L);
        demand.setApplyUserId(11L);
        demand.setStatus(MarketDemandDO.STATUS_MATCHED);
        demand.setAcceptanceStatus(MarketDemandDO.ACCEPTANCE_NONE);
        MarketDemandMatchDO match = acceptedMatch();
        MarketResourceApplyDO apply = new MarketResourceApplyDO();
        apply.setId(9L);
        apply.setAssetId(8L);
        apply.setApplyUserId(11L);
        apply.setStatus(MarketResourceApplyDO.STATUS_APPROVED);
        apply.setIfAuth(MarketResourceApplyDO.AUTH_YES);
        apply.setApiId(2L);
        when(demandMapper.selectById(1L)).thenReturn(demand);
        when(matchMapper.selectListByDemandId(1L)).thenReturn(java.util.List.of(match));
        when(matchMapper.selectForAcceptance( 5L)).thenAnswer(call -> copyMatch(match));
        when(applyMapper.selectById(9L)).thenReturn(apply);
        when(applyMapper.selectOwnedForRenewal( 9L, 11L))
                .thenAnswer(call -> copyApply(apply));
        when(deliveryService.isConfirmedApply(apply)).thenReturn(true);
        when(demandMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(affectedRows);

        if (affectedRows == 1) {
            service.acceptDemand(1L);
        } else {
            assertThrows(ServiceException.class, () -> service.acceptDemand(1L));
        }
        verify(demandMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void acceptanceDoneRetainsHistoricalEarlyReturnWithoutInspectingMatches() {
        var demand = new MarketDemandDO();
        demand.setId(1L);
        demand.setApplyUserId(11L);
        demand.setStatus(MarketDemandDO.STATUS_CLOSED);
        demand.setAcceptanceStatus(MarketDemandDO.ACCEPTANCE_DONE);
        when(demandMapper.selectById(1L)).thenReturn(demand);

        service.acceptDemand(1L);

        verifyNoInteractions(matchMapper, applyMapper, accessService, deliveryService);
        verify(demandMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void acceptanceRejectsNullMatchBeforeReadingApplyOrDelivery() {
        readyApply();
        when(matchMapper.selectListByDemandId(1L)).thenReturn(Arrays.asList((MarketDemandMatchDO) null));

        assertEquals(MARKET_DEMAND_ACCEPT_DENIED.getCode(),
                assertThrows(ServiceException.class, () -> service.acceptDemand(1L)).getCode());

        verifyNoInteractions(applyMapper, accessService, deliveryService);
        verify(matchMapper, never()).selectForAcceptance( anyLong());
        verify(demandMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @ParameterizedTest(name = "invalid match: {0}")
    @MethodSource("invalidAcceptanceMatches")
    void acceptanceRejectsInvalidMatchBeforeReadingApplyOrDelivery(String field,
                                                                  Consumer<MarketDemandMatchDO> change) {
        readyApply();
        var match = acceptedMatch();
        change.accept(match);
        when(matchMapper.selectListByDemandId(1L)).thenReturn(List.of(match));

        assertEquals(MARKET_DEMAND_ACCEPT_DENIED.getCode(),
                assertThrows(ServiceException.class, () -> service.acceptDemand(1L), field).getCode());

        verifyNoInteractions(applyMapper, accessService, deliveryService);
        verify(matchMapper, never()).selectForAcceptance( anyLong());
        verify(demandMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    private static Stream<Arguments> invalidAcceptanceMatches() {
        return Stream.of(
                changedMatch("missing id", match -> match.setId(null)),
                changedMatch("zero id", match -> match.setId(0L)),
                changedMatch("negative id", match -> match.setId(-1L)),
                changedMatch("missing demand", match -> match.setDemandId(null)),
                changedMatch("different demand", match -> match.setDemandId(2L)),
                changedMatch("missing asset", match -> match.setAssetId(null)),
                changedMatch("zero asset", match -> match.setAssetId(0L)),
                changedMatch("negative asset", match -> match.setAssetId(-1L)),
                changedMatch("missing apply", match -> match.setApplyId(null)),
                changedMatch("zero apply", match -> match.setApplyId(0L)),
                changedMatch("negative apply", match -> match.setApplyId(-1L)),
                changedMatch("missing status", match -> match.setStatus(null)),
                changedMatch("pending", match -> match.setStatus(MarketDemandMatchDO.STATUS_PENDING)),
                changedMatch("rejected", match -> match.setStatus(MarketDemandMatchDO.STATUS_REJECTED)),
                changedMatch("unknown status", match -> match.setStatus(99)),
                changedMatch("deleted", match -> match.setDeleted(true)));
    }

    private static Arguments changedMatch(String field, Consumer<MarketDemandMatchDO> change) {
        return Arguments.of(field, change);
    }

    @ParameterizedTest(name = "unrelated apply: {0}")
    @MethodSource("unrelatedAcceptanceApplies")
    void acceptanceRejectsUnrelatedApplyBeforeDeliveryOrLocks(String field,
                                                             Consumer<MarketResourceApplyDO> change) {
        var apply = readyApply();
        change.accept(apply);

        assertEquals(MARKET_DEMAND_ACCEPT_DENIED.getCode(),
                assertThrows(ServiceException.class, () -> service.acceptDemand(1L), field).getCode());

        verify(applyMapper).selectById(9L);
        verifyNoInteractions(accessService, deliveryService);
        verify(matchMapper, never()).selectForAcceptance( anyLong());
        verify(applyMapper, never()).selectOwnedForRenewal( anyLong(), anyLong());
        verify(demandMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    private static Stream<Arguments> unrelatedAcceptanceApplies() {
        return Stream.of(
                changed("missing id", apply -> apply.setId(null)),
                changed("different id", apply -> apply.setId(10L)),
                changed("missing asset", apply -> apply.setAssetId(null)),
                changed("different asset", apply -> apply.setAssetId(18L)),
                changed("missing owner", apply -> apply.setApplyUserId(null)),
                changed("different owner", apply -> apply.setApplyUserId(12L)));
    }

    @Test
    void acceptanceRejectsMissingApplyBeforeDeliveryOrLocks() {
        readyApply();
        when(applyMapper.selectById(9L)).thenReturn(null);

        assertEquals(MARKET_DEMAND_ACCEPT_DENIED.getCode(),
                assertThrows(ServiceException.class, () -> service.acceptDemand(1L)).getCode());

        verifyNoInteractions(accessService, deliveryService);
        verify(matchMapper, never()).selectForAcceptance( anyLong());
        verify(applyMapper, never()).selectOwnedForRenewal( anyLong(), anyLong());
        verify(demandMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void acceptanceSkipsInvalidUnrelatedAndUndeliveredCandidatesBeforeSelectingValidMatch() {
        var ready = readyApply();
        var pending = acceptedMatch();
        pending.setId(20L);
        pending.setStatus(MarketDemandMatchDO.STATUS_PENDING);
        var differentDemand = acceptedMatch();
        differentDemand.setId(21L);
        differentDemand.setDemandId(2L);
        var deleted = acceptedMatch();
        deleted.setId(22L);
        deleted.setDeleted(true);
        var wrongAsset = acceptedMatch();
        wrongAsset.setId(23L);
        wrongAsset.setAssetId(18L);
        wrongAsset.setApplyId(30L);
        var wrongAssetApply = copyApply(ready);
        wrongAssetApply.setId(30L);
        when(applyMapper.selectById(30L)).thenReturn(wrongAssetApply);
        var wrongOwner = acceptedMatch();
        wrongOwner.setId(24L);
        wrongOwner.setApplyId(31L);
        var wrongOwnerApply = copyApply(ready);
        wrongOwnerApply.setId(31L);
        wrongOwnerApply.setApplyUserId(12L);
        when(applyMapper.selectById(31L)).thenReturn(wrongOwnerApply);
        var missingApply = acceptedMatch();
        missingApply.setId(25L);
        missingApply.setApplyId(32L);
        var ineffective = acceptedMatch();
        ineffective.setId(26L);
        ineffective.setApplyId(33L);
        var revoked = copyApply(ready);
        revoked.setId(33L);
        revoked.setStatus(MarketResourceApplyDO.STATUS_REVOKED);
        when(applyMapper.selectById(33L)).thenReturn(revoked);
        var unconfirmed = acceptedMatch();
        unconfirmed.setId(27L);
        unconfirmed.setApplyId(34L);
        var unconfirmedApply = copyApply(ready);
        unconfirmedApply.setId(34L);
        when(applyMapper.selectById(34L)).thenReturn(unconfirmedApply);
        when(matchMapper.selectListByDemandId(1L)).thenReturn(Arrays.asList(
                null, pending, differentDemand, deleted, wrongAsset, wrongOwner, missingApply,
                ineffective, unconfirmed, acceptedMatch()));

        service.acceptDemand(1L);

        verify(deliveryService).isConfirmedApply(same(unconfirmedApply));
        verify(deliveryService).isConfirmedApply(same(ready));
        verifyNoMoreInteractions(deliveryService);
        var calls = inOrder(matchMapper, applyMapper, demandMapper);
        calls.verify(matchMapper).selectForAcceptance( 5L);
        calls.verify(applyMapper).selectOwnedForRenewal( 9L, 11L);
        calls.verify(demandMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(matchMapper, times(1)).selectForAcceptance( anyLong());
        verify(applyMapper, times(1)).selectOwnedForRenewal( anyLong(), anyLong());
    }

    @ParameterizedTest(name = "locked match changed: {0}")
    @MethodSource("acceptanceMatchChanges")
    void acceptanceRejectsChangedLockedMatchBeforeApplyLockAndCas(String field,
                                                                Consumer<MarketDemandMatchDO> change) {
        var ready = readyApply();
        var observed = acceptedMatch();
        var before = copyMatch(observed);
        var current = copyMatch(observed);
        change.accept(current);
        assertNotSame(observed, current);
        assertEquals(before, observed, field + " must change only the locked row");
        when(matchMapper.selectListByDemandId(1L)).thenReturn(List.of(observed));
        when(matchMapper.selectForAcceptance( 5L)).thenReturn(current);

        assertEquals(MARKET_DEMAND_ACCEPT_DENIED.getCode(),
                assertThrows(ServiceException.class, () -> service.acceptDemand(1L), field).getCode());

        verify(deliveryService).isConfirmedApply(same(ready));
        verify(matchMapper).selectForAcceptance( 5L);
        verify(applyMapper, never()).selectOwnedForRenewal( anyLong(), anyLong());
        verify(demandMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        verifyNoMoreInteractions(deliveryService);
    }

    private static Stream<Arguments> acceptanceMatchChanges() {
        return Stream.of(
                changedMatch("missing id", match -> match.setId(null)),
                changedMatch("different id", match -> match.setId(6L)),
                changedMatch("missing demand", match -> match.setDemandId(null)),
                changedMatch("different demand", match -> match.setDemandId(2L)),
                changedMatch("missing asset", match -> match.setAssetId(null)),
                changedMatch("different asset", match -> match.setAssetId(18L)),
                changedMatch("missing apply", match -> match.setApplyId(null)),
                changedMatch("different apply", match -> match.setApplyId(19L)),
                changedMatch("missing status", match -> match.setStatus(null)),
                changedMatch("pending", match -> match.setStatus(MarketDemandMatchDO.STATUS_PENDING)),
                changedMatch("rejected", match -> match.setStatus(MarketDemandMatchDO.STATUS_REJECTED)),
                changedMatch("deleted", match -> match.setDeleted(true)));
    }

    @Test
    void acceptanceRejectsMissingLockedMatchBeforeApplyLockAndCas() {
        var ready = readyApply();
        when(matchMapper.selectForAcceptance( 5L)).thenReturn(null);

        assertEquals(MARKET_DEMAND_ACCEPT_DENIED.getCode(),
                assertThrows(ServiceException.class, () -> service.acceptDemand(1L)).getCode());

        verify(deliveryService).isConfirmedApply(same(ready));
        verify(matchMapper).selectForAcceptance( 5L);
        verify(applyMapper, never()).selectOwnedForRenewal( anyLong(), anyLong());
        verify(demandMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void acceptancePropagatesMatchLockFailureWithoutApplyLockOrCas() {
        var ready = readyApply();
        var failure = new CannotAcquireLockException("test match lock unavailable");
        when(matchMapper.selectForAcceptance( 5L)).thenThrow(failure);

        assertSame(failure, assertThrows(CannotAcquireLockException.class, () -> service.acceptDemand(1L)));

        verify(deliveryService).isConfirmedApply(same(ready));
        verify(matchMapper).selectForAcceptance( 5L);
        verify(applyMapper, never()).selectOwnedForRenewal( anyLong(), anyLong());
        verify(demandMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @ParameterizedTest(name = "informational match change: {0}")
    @MethodSource("acceptanceMatchInformationChanges")
    void acceptanceAllowsInformationalMatchChangesAndCrossDemandApplyReuse(String field,
                                                                           Consumer<MarketDemandMatchDO> change) {
        var ready = readyApply();
        var observed = acceptedMatch();
        var before = copyMatch(observed);
        var current = copyMatch(observed);
        change.accept(current);
        assertNotSame(observed, current);
        assertEquals(before, observed, field + " must change only the locked row");
        assertEquals(71L, ready.getDemandId()); // 申请原需求不同，现有同用户同资产复用仍然有效。
        when(matchMapper.selectListByDemandId(1L)).thenReturn(List.of(observed));
        when(matchMapper.selectForAcceptance( 5L)).thenReturn(current);

        service.acceptDemand(1L);

        var calls = inOrder(deliveryService, matchMapper, applyMapper, demandMapper);
        calls.verify(deliveryService).isConfirmedApply(same(ready));
        calls.verify(matchMapper).selectForAcceptance( 5L);
        calls.verify(applyMapper).selectOwnedForRenewal( 9L, 11L);
        calls.verify(demandMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(deliveryService, times(1)).isConfirmedApply(any());
    }

    private static Stream<Arguments> acceptanceMatchInformationChanges() {
        return Stream.of(
                changedMatch("match note", match -> match.setMatchNote("补充匹配说明")),
                changedMatch("confirm note", match -> match.setConfirmNote("补充确认说明")),
                changedMatch("match type", match -> match.setMatchType(MarketDemandMatchDO.TYPE_MANUAL)),
                changedMatch("match score", match -> match.setMatchScore(java.math.BigDecimal.ONE)),
                changedMatch("matcher user", match -> match.setMatcherUserId(31L)),
                changedMatch("confirm user", match -> match.setConfirmUserId(32L)),
                changedMatch("confirm time", match -> match.setConfirmTime(LocalDateTime.of(2040, 1, 1, 0, 0))),
                changedMatch("update time", match -> match.setUpdateTime(LocalDateTime.of(2040, 1, 1, 0, 0))));
    }

    @ParameterizedTest(name = "locked apply changed: {0}")
    @MethodSource("authorizationChanges")
    void acceptanceRejectsChangedLockedApplyBeforeCas(String field,
                                                       Consumer<MarketResourceApplyDO> change) {
        MarketResourceApplyDO ready = readyApply();
        LocalDateTime now = LocalDateTime.of(2040, 1, 1, 0, 0);
        ready.setApplyStartTime(now.minusDays(1));
        ready.setApplyEndTime(now.plusDays(1));
        when(applyMapper.databaseNow()).thenReturn(now);
        MarketResourceApplyDO before = copyApply(ready);
        // 模拟 FOR UPDATE 等待结束后读到新行，不能原地改旧对象掩盖陈旧快照问题。
        MarketResourceApplyDO current = copyApply(ready);
        change.accept(current);
        assertNotSame(ready, current);
        assertEquals(before, ready, field + " must change only the locked row");
        when(applyMapper.selectOwnedForRenewal( 9L, 11L)).thenReturn(current);

        assertEquals(MARKET_DEMAND_ACCEPT_DENIED.getCode(),
                assertThrows(ServiceException.class, () -> service.acceptDemand(1L), field).getCode());

        verify(deliveryService).isConfirmedApply(same(ready));
        verify(applyMapper).selectOwnedForRenewal( 9L, 11L);
        verify(demandMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        verifyNoMoreInteractions(deliveryService);
    }

    private static Stream<Arguments> authorizationChanges() {
        // 每例只改一个字段；除两个版本用例外版本保持不变，防止仅比版本漏掉旧写入路径。
        return Stream.of(
                changed("revision", apply -> apply.setRevision(5)),
                changed("sourceRevision", apply -> apply.setSourceRevision(8L)),
                changed("id", apply -> apply.setId(10L)),
                changed("assetId", apply -> apply.setAssetId(18L)),
                changed("assetType", apply -> apply.setAssetType(2)),
                changed("apiId", apply -> apply.setApiId(12L)),
                changed("appId", apply -> apply.setAppId(13L)),
                changed("demandId", apply -> apply.setDemandId(72L)),
                changed("applyUserId", apply -> apply.setApplyUserId(12L)),
                changed("applyDeptId", apply -> apply.setApplyDeptId(101L)),
                changed("fieldScope", apply -> apply.setFieldScope("id")),
                changed("snapshot", apply -> apply.setSnapshot("{\"frozenColumns\":[\"id\"]}")),
                changed("catalogVersionId", apply -> apply.setCatalogVersionId(15L)),
                changed("sourceGrantId", apply -> apply.setSourceGrantId("test-source-grant-new")),
                changed("purpose", apply -> apply.setPurpose("其他统计用途")),
                changed("dutyMatter", apply -> apply.setDutyMatter("其他履职事项")),
                changed("legalBasis", apply -> apply.setLegalBasis("其他测试依据")),
                changed("applyStartTime", apply -> apply.setApplyStartTime(apply.getApplyStartTime().plusHours(1))),
                changed("applyEndTime", apply -> apply.setApplyEndTime(apply.getApplyEndTime().minusHours(1))),
                changed("applyUseType", apply -> apply.setApplyUseType(MarketResourceApplyDO.USE_UNLIMITED)),
                changed("applyUseTimes", apply -> apply.setApplyUseTimes(101)),
                changed("status", apply -> apply.setStatus(MarketResourceApplyDO.STATUS_REVOKED)),
                changed("ifAuth", apply -> apply.setIfAuth(MarketResourceApplyDO.AUTH_NO)),
                changed("paused", apply -> apply.setPaused(1)));
    }

    private static Arguments changed(String field, Consumer<MarketResourceApplyDO> change) {
        return Arguments.of(field, change);
    }

    @Test
    void acceptanceRejectsMissingOwnedLockedApplyBeforeCas() {
        MarketResourceApplyDO ready = readyApply();
        // 删除、跨租户或归属不再匹配时，带可信 tenant/owner 的锁查询均返回空行。
        when(applyMapper.selectOwnedForRenewal( 9L, 11L)).thenReturn(null);

        assertEquals(MARKET_DEMAND_ACCEPT_DENIED.getCode(),
                assertThrows(ServiceException.class, () -> service.acceptDemand(1L)).getCode());

        verify(deliveryService).isConfirmedApply(same(ready));
        verify(applyMapper).selectOwnedForRenewal( 9L, 11L);
        verify(demandMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        verifyNoMoreInteractions(deliveryService);
    }

    @Test
    void acceptancePropagatesApplyLockFailureWithoutCas() {
        MarketResourceApplyDO ready = readyApply();
        var failure = new CannotAcquireLockException("test apply lock unavailable");
        when(applyMapper.selectOwnedForRenewal( 9L, 11L)).thenThrow(failure);

        assertSame(failure, assertThrows(CannotAcquireLockException.class, () -> service.acceptDemand(1L)));

        verify(deliveryService).isConfirmedApply(same(ready));
        verify(applyMapper).selectOwnedForRenewal( 9L, 11L);
        verify(demandMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        verifyNoMoreInteractions(deliveryService);
    }

    @Test
    void acceptanceLocksAfterDeliveryAndRechecksLockedApplyOnlyAfterCas() {
        MarketResourceApplyDO ready = readyApply();
        MarketResourceApplyDO current = copyApply(ready);
        assertNotSame(ready, current);
        // 已有匹配允许复用别的需求创建的申请，不引入 demandId 必须等于当前需求的新策略。
        assertEquals(71L, current.getDemandId());
        when(applyMapper.selectOwnedForRenewal( 9L, 11L)).thenReturn(current);

        service.acceptDemand(1L);

        var calls = inOrder(accessService, deliveryService, matchMapper, applyMapper, demandMapper);
        calls.verify(accessService).isEffective(same(ready));
        calls.verify(applyMapper).databaseNow();
        calls.verify(deliveryService).isConfirmedApply(same(ready));
        calls.verify(accessService).isEffective(same(ready));
        calls.verify(applyMapper).databaseNow();
        calls.verify(matchMapper).selectForAcceptance( 5L);
        calls.verify(applyMapper).selectOwnedForRenewal( 9L, 11L);
        calls.verify(demandMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        calls.verify(accessService).isEffective(same(current));
        calls.verify(applyMapper).databaseNow();
        calls.verifyNoMoreInteractions();
        verify(applyMapper, times(3)).databaseNow();
        verify(deliveryService, times(1)).isConfirmedApply(any());
        verifyNoInteractions(applyService, adminUserApi, notifyHelper);
    }

    @Test
    void acceptanceRejectsDatabaseExpiredApplyEvenWhenJvmIsBehind() {
        MarketResourceApplyDO apply = readyApply();
        LocalDateTime expiry = LocalDateTime.of(2040, 1, 1, 0, 0);
        LocalDateTime jvmNow = expiry.minusYears(1);
        apply.setApplyEndTime(expiry);
        when(applyMapper.databaseNow()).thenReturn(expiry);
        try (var clock = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            clock.when(LocalDateTime::now).thenReturn(jvmNow);
            assertEquals(MARKET_DEMAND_ACCEPT_DENIED.getCode(),
                    assertThrows(ServiceException.class, () -> service.acceptDemand(1L)).getCode());
        }
        verifyNoInteractions(deliveryService);
        verify(demandMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void acceptanceAllowsInclusiveDatabaseStartEvenWhenJvmIsBehind() {
        MarketResourceApplyDO apply = readyApply();
        LocalDateTime start = LocalDateTime.of(2040, 1, 1, 0, 0);
        LocalDateTime jvmNow = start.minusYears(1);
        apply.setApplyStartTime(start);
        apply.setApplyEndTime(start.plusDays(1));
        when(applyMapper.databaseNow()).thenReturn(start);
        try (var clock = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            clock.when(LocalDateTime::now).thenReturn(jvmNow);
            service.acceptDemand(1L);
        }
        verify(deliveryService).isConfirmedApply(apply);
        verify(demandMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void acceptanceRechecksDatabaseTimeAfterDeliveryRead() {
        MarketResourceApplyDO apply = readyApply();
        LocalDateTime expiry = LocalDateTime.of(2040, 1, 1, 0, 0);
        LocalDateTime jvmNow = expiry.minusYears(1);
        apply.setApplyEndTime(expiry);
        when(applyMapper.databaseNow()).thenReturn(expiry.minusNanos(1), expiry);
        try (var clock = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            clock.when(LocalDateTime::now).thenReturn(jvmNow);
            assertEquals(MARKET_DEMAND_ACCEPT_DENIED.getCode(),
                    assertThrows(ServiceException.class, () -> service.acceptDemand(1L)).getCode());
        }
        verify(deliveryService).isConfirmedApply(apply);
        verify(demandMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unavailableDatabaseClockNeverFallsBackToJvm(boolean afterDelivery) {
        MarketResourceApplyDO apply = readyApply();
        LocalDateTime now = LocalDateTime.of(2040, 1, 1, 0, 0);
        if (afterDelivery) {
            when(applyMapper.databaseNow()).thenReturn(now, (LocalDateTime) null);
        } else {
            when(applyMapper.databaseNow()).thenReturn(null);
        }
        assertEquals(MARKET_APPLY_NOT_AUTHORIZED.getCode(),
                assertThrows(ServiceException.class, () -> service.acceptDemand(1L)).getCode());
        if (afterDelivery) verify(deliveryService).isConfirmedApply(apply);
        else verifyNoInteractions(deliveryService);
        verify(demandMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedDatabaseClockNeverAllowsAcceptance(boolean afterDelivery) {
        MarketResourceApplyDO apply = readyApply();
        var failure = new IllegalStateException("test database clock unavailable");
        if (afterDelivery) {
            when(applyMapper.databaseNow()).thenReturn(LocalDateTime.of(2040, 1, 1, 0, 0)).thenThrow(failure);
        } else {
            when(applyMapper.databaseNow()).thenThrow(failure);
        }
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.acceptDemand(1L)));
        if (afterDelivery) verify(deliveryService).isConfirmedApply(apply);
        else verifyNoInteractions(deliveryService);
        verify(demandMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "null", "exception"})
    void acceptanceRechecksClockAfterSuccessfulCas(String outcome) {
        MarketResourceApplyDO apply = readyApply();
        LocalDateTime expiry = LocalDateTime.of(2040, 1, 1, 0, 0);
        apply.setApplyEndTime(expiry);
        var clock = when(applyMapper.databaseNow()).thenReturn(expiry.minusSeconds(1), expiry.minusSeconds(1));
        var failure = new IllegalStateException("test clock failed after CAS");
        if ("exception".equals(outcome)) {
            clock.thenThrow(failure);
            assertSame(failure, assertThrows(IllegalStateException.class, () -> service.acceptDemand(1L)));
        } else {
            clock.thenReturn("null".equals(outcome) ? null : expiry);
            int expectedCode = "null".equals(outcome)
                    ? MARKET_APPLY_NOT_AUTHORIZED.getCode() : MARKET_DEMAND_ACCEPT_DENIED.getCode();
            assertEquals(expectedCode, assertThrows(ServiceException.class, () -> service.acceptDemand(1L)).getCode());
        }
        verify(applyMapper, times(3)).databaseNow();
        verify(deliveryService).isConfirmedApply(apply);
        // 单测只证明写后校验；真实事务回滚另由 PostgreSQL 用例验证。
        verify(demandMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    private static MarketResourceApplyDO copyApply(MarketResourceApplyDO apply) {
        var copy = new MarketResourceApplyDO();
        BeanUtils.copyProperties(apply, copy);
        return copy;
    }

    private static MarketDemandMatchDO acceptedMatch() {
        var match = new MarketDemandMatchDO();
        match.setId(5L);
        match.setDemandId(1L);
        match.setAssetId(8L);
        match.setApplyId(9L);
        match.setStatus(MarketDemandMatchDO.STATUS_ACCEPTED);
        match.setDeleted(false);
        return match;
    }

    private static MarketDemandMatchDO copyMatch(MarketDemandMatchDO match) {
        var copy = new MarketDemandMatchDO();
        BeanUtils.copyProperties(match, copy);
        return copy;
    }

    private MarketResourceApplyDO readyApply() {
        var demand = new MarketDemandDO();
        demand.setId(1L);
        demand.setApplyUserId(11L);
        demand.setStatus(MarketDemandDO.STATUS_MATCHED);
        demand.setAcceptanceStatus(MarketDemandDO.ACCEPTANCE_NONE);
        var match = acceptedMatch();
        var apply = new MarketResourceApplyDO();
        apply.setId(9L);
        apply.setAssetId(8L);
        apply.setAssetType(1);
        apply.setApiId(2L);
        apply.setAppId(3L);
        apply.setDemandId(71L);
        apply.setApplyUserId(11L);
        apply.setApplyDeptId(100L);
        apply.setRevision(4);
        apply.setSourceRevision(7L);
        apply.setFieldScope("id,name");
        apply.setSnapshot("{\"frozenColumns\":[\"id\",\"name\"]}");
        apply.setCatalogVersionId(14L);
        apply.setSourceGrantId("test-source-grant-original");
        apply.setPurpose("统计");
        apply.setDutyMatter("数据汇总");
        apply.setLegalBasis("测试审批依据");
        apply.setApplyUseType(MarketResourceApplyDO.USE_LIMITED);
        apply.setApplyUseTimes(100);
        apply.setStatus(MarketResourceApplyDO.STATUS_APPROVED);
        apply.setIfAuth(MarketResourceApplyDO.AUTH_YES);
        apply.setPaused(0);
        when(demandMapper.selectById(1L)).thenReturn(demand);
        when(matchMapper.selectListByDemandId(1L)).thenReturn(java.util.List.of(match));
        when(matchMapper.selectForAcceptance( 5L)).thenAnswer(call -> copyMatch(match));
        when(applyMapper.selectById(9L)).thenReturn(apply);
        // 到真正加锁时才复制，保留各时钟用例随后设置的期限；始终返回独立 DO。
        when(applyMapper.selectOwnedForRenewal( 9L, 11L))
                .thenAnswer(call -> copyApply(apply));
        when(deliveryService.isConfirmedApply(apply)).thenReturn(true);
        when(demandMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        return apply;
    }
}
