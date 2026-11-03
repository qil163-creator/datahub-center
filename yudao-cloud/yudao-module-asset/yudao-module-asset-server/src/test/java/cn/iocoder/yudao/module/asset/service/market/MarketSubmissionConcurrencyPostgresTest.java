package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketApplySaveReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCheckReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketCheckProcessMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import cn.iocoder.yudao.module.asset.service.monitor.AssetMonitorService;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.AdditionalAnswers;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_STATUS_DENY;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 两个实际事务代理和数据库连接；只在初次读后设屏障，不用睡眠猜测竞争顺序。 */
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketSubmissionConcurrencyPostgresTest extends SharingPostgresSupport {
    private MarketResourceApplyMapper mapper;
    private MarketResourceApplyMapper delayedMapper;
    private MarketApplyService service;
    private MarketApplyService competitor;
    private MarketDeliveryService delivery;
    private AssetCatalogService catalog;
    private MarketAuditRecorder audit;
    private SqlSessionTemplate session;
    private DataSourceTransactionManager transactions;

    @BeforeEach
    void configure() throws Exception {
        try (var input = getClass().getResourceAsStream("/sql/market-freeze-fixture.sql")) {
            if (input == null) throw new IllegalStateException("Missing market fixture");
            sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        sql("""
                INSERT INTO dh_market_resource_apply
                  (id,asset_id,apply_user_id,status,if_auth,paused,revision,field_scope,title,snapshot)
                  VALUES (7,5,9,0,0,0,1,'Id','original',
                  '{"assetId":"5","assetVersion":"v1","catalogVersionId":"12","publishedSnapshotId":"11",
                    "databaseId":"4","schemaName":"public","tableName":"citizen","frozenColumns":["Id"]}');
                """);
        var datasource = new UnpooledDataSource("org.postgresql.Driver",
                jdbcUrl + "?currentSchema=datahub-center", "sharing_test", "");
        transactions = new DataSourceTransactionManager(datasource);
        var configuration = singleTenantConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("submission-test", new SpringManagedTransactionFactory(), datasource));
        configuration.addMapper(MarketResourceApplyMapper.class);
        configuration.addMapper(MarketCheckProcessMapper.class);
        session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        mapper = session.getMapper(MarketResourceApplyMapper.class);
        delayedMapper = mock(MarketResourceApplyMapper.class, AdditionalAnswers.delegatesTo(mapper));
        delivery = mock(MarketDeliveryService.class);
        audit = mock(MarketAuditRecorder.class);
        catalog = mock(AssetCatalogService.class);
        when(catalog.listColumns(5L, 11L)).thenReturn(List.of(column("Id"), column("Phone")));
        service = service(delayedMapper);
        competitor = service(mapper);
        TenantContextHolder.clear();
    }

    private MarketApplyService service(MarketResourceApplyMapper applies) {
        var target = new MarketApplyServiceImpl();
        ReflectionTestUtils.setField(target, "applyMapper", applies);
        ReflectionTestUtils.setField(target, "processMapper", session.getMapper(MarketCheckProcessMapper.class));
        var assets = mock(DataAssetMapper.class);
        when(assets.selectById(5L)).thenReturn(DataAssetDO.builder().id(5L)
                .status(DataAssetDO.STATUS_PUBLISHED).assetType(DataAssetDO.TYPE_DATABASE_TABLE)
                .publishedVersion("v1").publishedSnapshotId(11L).publishedCatalogVersionId(12L)
                .datasourceId(4L).schemaName("public").tableName("citizen").build());
        when(assets.selectForMatching(5L)).thenAnswer(call -> assets.selectById(5L));
        ReflectionTestUtils.setField(target, "assetMapper", assets);
        var guard = mock(MarketObjectGuard.class);
        when(guard.requireUserId()).thenReturn(9L);
        ReflectionTestUtils.setField(target, "objectGuard", guard);
        org.mockito.Mockito.when(catalog.requireCompletePublishedAsset(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> call.getArgument(0));
        ReflectionTestUtils.setField(target, "catalogService", catalog);
        ReflectionTestUtils.setField(target, "deliveryService", delivery);
        ReflectionTestUtils.setField(target, "auditRecorder", audit);
        ReflectionTestUtils.setField(target, "notifyHelper", mock(MarketNotifyHelper.class));
        ReflectionTestUtils.setField(target, "workingDayService", mock(MarketWorkingDayService.class));
        ReflectionTestUtils.setField(target, "assetMonitorService", mock(AssetMonitorService.class));
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (MarketApplyService) proxy.getProxy();
    }

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
    }

    @Test
    void staleDraftEditCannotOverwriteAnAlreadySubmittedApplication() throws Exception {
        assertConflict(race(() -> service.updateApply(edit("Phone")), () -> competitor.submitApply(7L)));
        var current = mapper.selectById(7L);
        assertThat(current.getStatus()).isEqualTo(MarketResourceApplyDO.STATUS_PENDING);
        assertThat(current.getFieldScope()).isEqualTo("Id");
        assertThat(MarketFieldScope.requireFrozenColumns(current.getSnapshot())).containsExactly("Id");
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(1);
    }

    @Test
    void staleApprovalCannotApproveTheNextSupplementSubmission() throws Exception {
        sql("UPDATE dh_market_resource_apply SET status=1 WHERE id=7");
        assertConflict(race(() -> service.checkApply(approval()), () -> {
            competitor.requestSupplement(7L, "supplement");
            competitor.updateApply(edit("Phone"));
            competitor.resubmitApply(7L);
        }));
        var current = mapper.selectById(7L);
        assertThat(current.getStatus()).isEqualTo(MarketResourceApplyDO.STATUS_PENDING);
        assertThat(current.getIfAuth()).isZero();
        assertThat(MarketFieldScope.requireFrozenColumns(current.getSnapshot())).containsExactly("Phone");
        verifyNoInteractions(delivery);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process WHERE action='APPROVE'")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"submit", "resubmit", "edit", "delete", "cancel", "approve", "reject", "supplement"})
    void sameStatusButNewContentRevisionRejectsEveryOldCommand(String command) throws Exception {
        int status = switch (command) {
            case "submit", "edit", "delete" -> MarketResourceApplyDO.STATUS_DRAFT;
            case "resubmit" -> MarketResourceApplyDO.STATUS_SUPPLEMENT;
            default -> MarketResourceApplyDO.STATUS_PENDING;
        };
        sql("UPDATE dh_market_resource_apply SET status=" + status + " WHERE id=7");
        assertConflict(race(() -> command(service, command), () -> {
            if (status == MarketResourceApplyDO.STATUS_PENDING) {
                competitor.requestSupplement(7L, "new round");
                competitor.updateApply(edit("Phone"));
                competitor.resubmitApply(7L);
            } else {
                competitor.updateApply(edit("Phone"));
            }
        }));
        var row = mapper.selectById(7L);
        assertThat(row).isNotNull();
        assertThat(row.getStatus()).isEqualTo(status);
        assertThat(row.getFieldScope()).isEqualTo("Phone");
        assertThat(row.getIfAuth()).isZero();
        assertThat(row.getRevision()).isEqualTo(status == MarketResourceApplyDO.STATUS_PENDING ? 3 : 2);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process"))
                .isEqualTo(status == MarketResourceApplyDO.STATUS_PENDING ? 2 : 0);
        verifyNoInteractions(delivery);
    }

    @Test
    void staleDeleteCannotRemoveAnAlreadySubmittedApplication() throws Exception {
        assertConflict(race(() -> service.deleteApply(7L), () -> competitor.submitApply(7L)));
        assertThat(mapper.selectById(7L).getStatus()).isEqualTo(MarketResourceApplyDO.STATUS_PENDING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"submit", "approve"})
    void cancellationWinnerPreventsStaleSubmissionOrApproval(String command) throws Exception {
        int initial = "submit".equals(command) ? MarketResourceApplyDO.STATUS_DRAFT : MarketResourceApplyDO.STATUS_PENDING;
        sql("UPDATE dh_market_resource_apply SET status=" + initial + " WHERE id=7");
        assertConflict(race(() -> command(service, command), () -> competitor.cancelApply(7L)));
        var current = mapper.selectById(7L);
        assertThat(current.getStatus()).isEqualTo(MarketResourceApplyDO.STATUS_CANCELLED);
        assertThat(current.getIfAuth()).isZero();
        assertThat(current.getRevision()).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process WHERE action='CANCEL' AND from_status="
                + initial + " AND to_status=4")).isEqualTo(1);
        verifyNoInteractions(delivery);
    }

    @ParameterizedTest
    @ValueSource(strings = {"submit", "approve"})
    void submissionOrApprovalWinnerPreventsStaleCancellation(String command) throws Exception {
        boolean submitted = "submit".equals(command);
        sql("UPDATE dh_market_resource_apply SET status=" + (submitted ? 0 : 1) + " WHERE id=7");
        assertConflict(race(() -> service.cancelApply(7L), () -> command(competitor, command)));
        var current = mapper.selectById(7L);
        assertThat(current.getStatus()).isEqualTo(submitted ? MarketResourceApplyDO.STATUS_PENDING : MarketResourceApplyDO.STATUS_APPROVED);
        assertThat(current.getIfAuth()).isEqualTo(submitted ? 0 : 1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process WHERE action='CANCEL'")).isZero();
        if (submitted) verifyNoInteractions(delivery);
        else verify(delivery).enqueueApprovedGrant(argThat(apply -> apply.getId().equals(7L)
                && apply.getStatus() == MarketResourceApplyDO.STATUS_APPROVED), any(), any(), any());
    }

    @Test
    void staleSupplementEditCannotReopenAResubmittedApplication() throws Exception {
        sql("UPDATE dh_market_resource_apply SET status=8 WHERE id=7");
        assertConflict(race(() -> service.updateApply(edit("Phone")), () -> competitor.resubmitApply(7L)));
        var row = mapper.selectById(7L);
        assertThat(row.getStatus()).isEqualTo(MarketResourceApplyDO.STATUS_PENDING);
        assertThat(row.getFieldScope()).isEqualTo("Id");
    }

    @Test
    void normalEditingAndSupplementingKeepSnapshotAndApprovalOnTheSameRevision() throws Exception {
        competitor.updateApply(edit("Id"));
        competitor.submitApply(7L);
        assertThat(mapper.selectById(7L).getRevision()).isEqualTo(2);
        competitor.requestSupplement(7L, "clarify");
        competitor.updateApply(edit("Phone"));
        competitor.resubmitApply(7L);
        var pending = mapper.selectById(7L);
        assertThat(pending.getRevision()).isEqualTo(4);
        assertThat(cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseObject(pending.getSnapshot(), java.util.Map.class))
                .containsEntry("revision", "4");
        competitor.checkApply(approval());
        var approved = mapper.selectById(7L);
        assertThat(approved.getStatus()).isEqualTo(MarketResourceApplyDO.STATUS_APPROVED);
        assertThat(approved.getSnapshot()).isEqualTo(pending.getSnapshot());
        assertThat(approved.getRevision()).isEqualTo(4);
        verify(delivery).enqueueApprovedGrant(argThat(apply -> apply.getRevision() == 4
                && apply.getSnapshot().equals(pending.getSnapshot())), any(), any(), any());
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(4);
    }

    @Test
    void processInsertFailureRollsBackResubmissionRevisionSnapshotAndProcess() throws Exception {
        sql("UPDATE dh_market_resource_apply SET status=8 WHERE id=7");
        var before = mapper.selectById(7L);
        sql("ALTER TABLE dh_market_check_process ADD CONSTRAINT reject_test_resubmit CHECK (action <> 'RESUBMIT')");
        assertThatThrownBy(() -> competitor.resubmitApply(7L))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var after = mapper.selectById(7L);
        assertThat(after.getStatus()).isEqualTo(before.getStatus());
        assertThat(after.getRevision()).isEqualTo(before.getRevision());
        assertThat(after.getSnapshot()).isEqualTo(before.getSnapshot());
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isZero();
        verifyNoInteractions(delivery);
    }

    @Test
    void contentRevisionCannotWrapOrAcceptAnInvalidNegativeValue() throws Exception {
        for (int revision : List.of(Integer.MAX_VALUE, 0, -1)) {
            sql("UPDATE dh_market_resource_apply SET revision=" + revision + " WHERE id=7");
            assertThatThrownBy(() -> competitor.updateApply(edit("Phone"))).isInstanceOf(ServiceException.class);
            assertThat(mapper.selectById(7L).getRevision()).isEqualTo(revision);
        }
        assertThat(mapper.selectById(7L).getFieldScope()).isEqualTo("Id");
    }

    @Test
    void legacyNullRevisionUsesAnExactNullCompareAndInitializesOnEdit() throws Exception {
        sql("UPDATE dh_market_resource_apply SET revision=NULL WHERE id=7");
        assertConflict(race(() -> service.updateApply(edit("Id")), () -> competitor.updateApply(edit("Phone"))));
        assertThat(mapper.selectById(7L).getRevision()).isEqualTo(1);
        assertThat(mapper.selectById(7L).getFieldScope()).isEqualTo("Phone");
    }



    @Test
    void normalDeleteKeepsTheEstablishedLogicalDeletionContract() throws Exception {
        competitor.deleteApply(7L);
        assertThat(mapper.selectById(7L)).isNull();
        assertThat(scalar("SELECT deleted FROM dh_market_resource_apply WHERE id=7")).isEqualTo(1);
    }

    private static void command(MarketApplyService service, String command) {
        switch (command) {
            case "submit" -> service.submitApply(7L);
            case "resubmit" -> service.resubmitApply(7L);
            case "edit" -> service.updateApply(edit("Id"));
            case "delete" -> service.deleteApply(7L);
            case "cancel" -> service.cancelApply(7L);
            case "approve" -> service.checkApply(approval());
            case "reject" -> {
                var request = approval();
                request.setStatus(MarketResourceApplyDO.STATUS_REJECTED);
                service.checkApply(request);
            }
            case "supplement" -> service.requestSupplement(7L, "stale");
            default -> throw new IllegalArgumentException(command);
        }
    }

    private Throwable race(Runnable delayed, Runnable winner) throws Exception {
        var read = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        doAnswer(call -> {
            MarketResourceApplyDO observed = mapper.selectById((Long) call.getArgument(0));
            if (first.compareAndSet(true, false)) {
                read.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Read barrier timed out");
            }
            return observed;
        }).when(delayedMapper).selectById(7L);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> {
                TenantContextHolder.clear();
                try { delayed.run(); return (Throwable) null; }
                catch (Throwable failure) { return failure; }
                finally { TenantContextHolder.clear(); }
            });
            try {
                assertThat(read.await(10, TimeUnit.SECONDS)).as("initial read reached").isTrue();
                winner.run();
            } finally {
                release.countDown();
            }
            return pending.get(15, TimeUnit.SECONDS);
        }
    }

    private static void assertConflict(Throwable failure) {
        assertThat(failure).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) failure).getCode()).isEqualTo(MARKET_APPLY_STATUS_DENY.getCode());
    }

    private static MarketApplySaveReqVO edit(String scope) {
        var request = new MarketApplySaveReqVO();
        request.setId(7L);
        request.setAssetId(5L);
        request.setTitle("edited");
        request.setFieldScope(scope);
        return request;
    }

    private static MarketCheckReqVO approval() {
        var request = new MarketCheckReqVO();
        request.setId(7L);
        request.setStatus(MarketResourceApplyDO.STATUS_APPROVED);
        return request;
    }

    private static MetadataColumnRespDTO column(String name) {
        var column = new MetadataColumnRespDTO();
        column.setColumnName(name);
        return column;
    }
}
