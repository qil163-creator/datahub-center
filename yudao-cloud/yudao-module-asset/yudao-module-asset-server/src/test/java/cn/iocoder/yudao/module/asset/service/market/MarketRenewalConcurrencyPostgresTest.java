package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketRenewalReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketCheckProcessMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

/** 隔离 PostgreSQL 上的真实事务代理、唯一约束和多连接续期验收。 */
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketRenewalConcurrencyPostgresTest extends SharingPostgresSupport {
    private static final LocalDateTime END = LocalDateTime.of(2041, 1, 1, 0, 0);
    private MarketResourceApplyMapper mapper;
    private MarketApplyService service;
    private DataSourceTransactionManager transactions;
    private SqlSessionTemplate session;

    @BeforeEach
    void configure() throws Exception {
        try (var input = getClass().getResourceAsStream("/sql/market-freeze-fixture.sql")) {
            if (input == null) throw new IllegalStateException("Missing market fixture");
            sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        migrate("0042_asset_grant-source-delivery.sql");
        // 真正验证从旧结构升级，而非因为公共测试夹具已有新列就误判迁移成功。
        sql("ALTER TABLE dh_market_resource_apply DROP COLUMN renewal_requested_end_time");
        migrate("0047_asset_renewal-request-evidence.sql");
        migrate("0050_asset_market-active-application-uniqueness.sql");
        sql("ALTER TABLE dh_market_check_process ADD tenant_id bigint NOT NULL DEFAULT 1");
        sql("ALTER TABLE dh_market_resource_apply ALTER COLUMN id SET DEFAULT nextval('dh_market_resource_apply_seq')");
        sql("SELECT setval('dh_market_resource_apply_seq',100,false)");
        sql("ALTER TABLE dh_market_resource_apply ALTER COLUMN snapshot TYPE jsonb USING snapshot::jsonb");
        sql("""
                INSERT INTO dh_market_resource_apply
                  (id,asset_id,apply_user_id,status,if_auth,paused,revision,apply_end_time,snapshot)
                  VALUES (7,5,9,2,1,0,3,'2040-01-01',
                  '{"assetId":"5","assetVersion":"v1","catalogVersionId":"12","publishedSnapshotId":"11",
                    "databaseId":"4","schemaName":"public","tableName":"citizen","frozenColumns":["Id"]}');
                """);
        var datasource = new UnpooledDataSource("org.postgresql.Driver",
                jdbcUrl + "?currentSchema=datahub-center", "sharing_test", "");
        transactions = new DataSourceTransactionManager(datasource);
        var configuration = singleTenantConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        var interceptor = new MybatisPlusInterceptor();

        configuration.addInterceptor(interceptor);
        configuration.setEnvironment(new Environment("renewal-test", new SpringManagedTransactionFactory(), datasource));
        configuration.addMapper(MarketResourceApplyMapper.class);
        configuration.addMapper(MarketCheckProcessMapper.class);
        session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        mapper = session.getMapper(MarketResourceApplyMapper.class);
        service = service(mapper);
        TenantContextHolder.clear();
    }

    private MarketApplyService service(MarketResourceApplyMapper applies) {
        var target = new MarketApplyServiceImpl();
        ReflectionTestUtils.setField(target, "applyMapper", applies);
        ReflectionTestUtils.setField(target, "transactionManager", transactions);
        ReflectionTestUtils.setField(target, "processMapper", session.getMapper(MarketCheckProcessMapper.class));
        var guard = mock(MarketObjectGuard.class);
        when(guard.requireUserId()).thenReturn(9L);
        ReflectionTestUtils.setField(target, "objectGuard", guard);
        ReflectionTestUtils.setField(target, "auditRecorder", mock(MarketAuditRecorder.class));
        var display = mock(MarketDisplayHelper.class);
        when(display.nextBizNo(anyString(), anyLong())).thenAnswer(call -> "APP-" + call.getArgument(1));
        ReflectionTestUtils.setField(target, "displayHelper", display);
        var assets = mock(DataAssetMapper.class);
        var asset = new DataAssetDO();
        asset.setId(5L);
        asset.setStatus(DataAssetDO.STATUS_PUBLISHED);
        asset.setShareAttr(DataAssetDO.SHARE_UNCONDITIONAL);
        when(assets.selectById(5L)).thenReturn(asset);
        ReflectionTestUtils.setField(target, "assetMapper", assets);
        var calendar = mock(MarketWorkingDayService.class);
        when(calendar.plusWorkingDays(any(LocalDateTime.class), anyInt())).thenAnswer(call ->
                ((LocalDateTime) call.getArgument(0)).plusDays((Integer) call.getArgument(1)));
        ReflectionTestUtils.setField(target, "workingDayService", calendar);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (MarketApplyService) proxy.getProxy();
    }

    private static MarketRenewalReqVO request() {
        var request = new MarketRenewalReqVO();
        request.setId(7L);
        request.setOperationId("renew-1");
        request.setApplyEndTime(END);
        return request;
    }

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
    }

    @Test
    void replayUsesOriginalRequestEvenAfterSupplementEditsItsDeadline() throws Exception {
        Long id = service.createRenewal(request());
        sql("UPDATE dh_market_resource_apply SET apply_end_time='2042-01-01',revision=2 WHERE id=" + id);
        assertThat(service.createRenewal(request())).isEqualTo(id);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(1);
    }

    @Test
    void committedResultCanBeReplayedAfterParentRevocationWithoutCreatingAnotherGrant() throws Exception {
        Long id = service.createRenewal(request());
        sql("UPDATE dh_market_resource_apply SET status=6,if_auth=0 WHERE id=7");
        assertThat(service.createRenewal(request())).isEqualTo(id);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE renewal_of_apply_id=7")).isEqualTo(1);
        assertThat(mapper.selectById(id).getIfAuth()).isZero();
    }

    @Test
    void sameParentConcurrentRetryReturnsExactlyOneApplicationAndProcess() throws Exception {
        Object result = race(false, () -> service.createRenewal(request()));
        assertThat(result).isInstanceOf(Long.class).isEqualTo(service.createRenewal(request()));
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE renewal_of_apply_id=7")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(1);
    }

    @Test
    void sameParentDifferentPayloadCannotReuseAnOperation() throws Exception {
        Object result = race(false, () -> {
            var other = request();
            other.setApplyEndTime(END.plusDays(1));
            return service.createRenewal(other);
        });
        assertConflict(result);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(1);
    }

    @Test
    void differentOperationCannotCreateAnotherActiveRenewalForTheSameParent() throws Exception {
        Long first = service.createRenewal(request());
        var other = request();
        other.setOperationId("renew-2");
        assertThatThrownBy(() -> service.createRenewal(other)).isInstanceOfSatisfying(ServiceException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(MARKET_APPLY_RENEWAL_CONFLICT.getCode()));
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE renewal_of_apply_id=7")).isEqualTo(1);
        assertThat(service.createRenewal(request())).isEqualTo(first);
    }

    @Test
    void concurrentDifferentOperationCannotBypassTheParentLock() throws Exception {
        assertConflict(race(false, () -> {
            var other = request();
            other.setOperationId("renew-2");
            return service.createRenewal(other);
        }));
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE renewal_of_apply_id=7")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(1);
    }

    @Test
    void rejectedRenewalReleasesParentForASeparateNewApplication() throws Exception {
        Long first = service.createRenewal(request());
        sql("UPDATE dh_market_resource_apply SET status=3 WHERE id=" + first);
        var second = request();
        second.setOperationId("renew-2");
        Long secondId = service.createRenewal(second);
        assertThat(secondId).isNotEqualTo(first);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE renewal_of_apply_id=7")).isEqualTo(2);
    }

    @Test
    void activeOrdinaryApplicationIsUniqueAndTerminalStateCanReleaseTheSlot() throws Exception {
        sql("INSERT INTO dh_market_resource_apply (id,asset_id,apply_user_id,status) VALUES (20,5,9,1)");
        assertThatThrownBy(() -> sql("INSERT INTO dh_market_resource_apply "
                + "(id,asset_id,apply_user_id,status) VALUES (21,5,9,0)"))
                .isInstanceOfSatisfying(java.sql.SQLException.class,
                        ex -> assertThat(ex.getSQLState()).isEqualTo("23505"));
        sql("UPDATE dh_market_resource_apply SET status=3 WHERE id=20");
        sql("INSERT INTO dh_market_resource_apply (id,asset_id,apply_user_id,status) VALUES (21,5,9,0)");
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id IN (20,21)")).isEqualTo(2);
    }

    @Test
    void pendingOrdinaryApplicationBlocksRenewalForTheSameUserAndAsset() throws Exception {
        sql("INSERT INTO dh_market_resource_apply (id,asset_id,apply_user_id,status) VALUES (20,5,9,1)");
        assertThatThrownBy(() -> service.createRenewal(request())).isInstanceOfSatisfying(ServiceException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(MARKET_APPLY_RENEWAL_CONFLICT.getCode()));
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE renewal_of_apply_id=7")).isZero();
    }

    @Test
    void ordinaryApplicationCommittedAfterRenewalPrecheckIsReconciledAfterSavepointRollback() throws Exception {
        assertConflict(race(true, () -> {
            sql("INSERT INTO dh_market_resource_apply (id,asset_id,apply_user_id,status) VALUES (20,5,9,1)");
            return null;
        }));
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE renewal_of_apply_id=7")).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=20")).isEqualTo(1);
    }

    @Test
    void uniqueCollisionWithAnotherParentRollsBackSavepointBeforeReadingWinner() throws Exception {
        sql("INSERT INTO dh_market_resource_apply (id,asset_id,apply_user_id,status,if_auth,paused,revision,apply_end_time)"
                + " VALUES (8,5,9,2,1,0,3,'2040-01-01')");
        // 不同原申请不共享行锁，第二个事务确实先提交了平台全局唯一操作身份。
        assertConflict(race(true, () -> {
            var other = request();
            other.setId(8L);
            return service.createRenewal(other);
        }));
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE renewal_operation_id='renew-1'"))
                .isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(1);
    }

    @Test
    void insertCollisionCanReturnMatchingCommittedEvidenceWithoutAbortedTransaction() throws Exception {
        assertThat(race(true, () -> {
            // 模拟不持有原申请锁的并发写入者；插入失败后必须先回滚保存点才能重读。
            sql("""
                    INSERT INTO dh_market_resource_apply
                      (id,apply_user_id,status,if_auth,renewal_of_apply_id,renewal_operation_id,
                       apply_end_time,renewal_requested_end_time)
                    VALUES (333,9,1,0,7,'renew-1','2041-01-01','2041-01-01')
                    """);
            return 333L;
        })).isEqualTo(333L);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isZero();
    }

    @Test
    void revocationThatCommitsFirstPreventsNewRenewal() throws Exception {
        Object result = race(false, () -> {
            sql("UPDATE dh_market_resource_apply SET status=6,if_auth=0 WHERE id=7");
            return null;
        });
        assertThat(result).isInstanceOfSatisfying(ServiceException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(MARKET_APPLY_STATUS_DENY.getCode()));
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply")).isEqualTo(1);
    }

    @Test
    void parentLockIsHeldUntilTheWholeRenewalCommits() throws Exception {
        assertThat(race(true, () -> {
            try (var other = open(); var statement = other.createStatement()) {
                statement.execute("SET lock_timeout='200ms'");
                assertThatThrownBy(() -> statement.execute("UPDATE dh_market_resource_apply SET status=6 WHERE id=7"))
                        .isInstanceOfSatisfying(java.sql.SQLException.class,
                                ex -> assertThat(ex.getSQLState()).isEqualTo("55P03"));
            }
            return null;
        })).isInstanceOf(Long.class);
        assertThat(mapper.selectById(7L).getStatus()).isEqualTo(2);
    }

    @Test
    void processFailureRollsBackRenewalAndAllowsSafeRetry() throws Exception {
        sql("ALTER TABLE dh_market_check_process ADD CONSTRAINT renewal_process_failure CHECK (action <> 'RENEW')");
        assertThatThrownBy(() -> service.createRenewal(request())).isInstanceOf(RuntimeException.class);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isZero();
        sql("ALTER TABLE dh_market_check_process DROP CONSTRAINT renewal_process_failure");
        assertThat(service.createRenewal(request())).isNotNull();
        assertThat(mapper.selectById(7L).getApplyEndTime()).isEqualTo(END.minusYears(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"deleted=1", "renewal_requested_end_time=NULL", "apply_user_id=10", "renewal_of_apply_id=8"})
    void tombstoneOrMismatchedOrMissingEvidenceNeverReturnsOrRecreatesAnApplication(String change) throws Exception {
        Long id = service.createRenewal(request());
        sql("UPDATE dh_market_resource_apply SET " + change + " WHERE id=" + id);
        assertThatThrownBy(() -> service.createRenewal(request())).isInstanceOfSatisfying(ServiceException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(MARKET_APPLY_RENEWAL_CONFLICT.getCode()));
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status=6", "if_auth=0", "paused=1", "apply_start_time='2042-01-01'",
            "apply_end_time='2000-01-01'", "apply_end_time=NULL", "apply_user_id=10", "deleted=1"})
    void onlyCurrentlyEffectiveFiniteApprovalCanBeExtended(String change) throws Exception {
        sql("UPDATE dh_market_resource_apply SET " + change + " WHERE id=7");
        assertThatThrownBy(() -> service.createRenewal(request())).isInstanceOf(ServiceException.class);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply")).isEqualTo(1);
    }

    @Test
    void requestTimestampUsesTheSameDatabasePrecisionForInsertAndReplay() {
        var request = request();
        request.setApplyEndTime(END.withNano(123456789));
        Long id = service.createRenewal(request);
        assertThat(service.createRenewal(request)).isEqualTo(id);
        assertThat(mapper.selectById(id).getRenewalRequestedEndTime()).isEqualTo(END.withNano(123456000));
    }

    @Test
    void normalMapperUpdatesCannotReplaceImmutableRequestEvidence() {
        Long id = service.createRenewal(request());
        var edit = new MarketResourceApplyDO();
        edit.setId(id);
        edit.setApplyEndTime(END.plusYears(1));
        edit.setRenewalRequestedEndTime(END.plusYears(1));
        mapper.updateById(edit);
        assertThat(mapper.selectById(id).getRenewalRequestedEndTime()).isEqualTo(END);
        assertThat(service.createRenewal(request())).isEqualTo(id);
    }

    @Test
    void operationKeysAreGlobalAndCannotBeReusedForAnotherApplication() throws Exception {
        sql("INSERT INTO dh_market_resource_apply (id,asset_id,apply_user_id,status,if_auth,paused,revision,apply_end_time)"
                + " VALUES (8,5,9,2,1,0,3,'2040-01-01')");
        TenantContextHolder.clear();
        Long first = service.createRenewal(request());
        assertThat(service.createRenewal(request())).isEqualTo(first);
        var second = request();
        second.setId(8L);
        assertThatThrownBy(() -> service.createRenewal(second)).isInstanceOf(ServiceException.class);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE renewal_operation_id='renew-1'"))
                .isEqualTo(1);
    }

    @Test
    void repeatableReadCallerCannotHideACommittedResultFromIndependentRenewalTransaction() throws Exception {
        var outer = new TransactionTemplate(transactions);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.executeWithoutResult(status -> {
            assertThat(mapper.selectRenewalByOperation( "renew-1")).isNull();
            try (var executor = Executors.newSingleThreadExecutor()) {
                Long winner = executor.submit(() -> withoutTenantContext(() -> service.createRenewal(request())))
                        .get(10, TimeUnit.SECONDS);
                assertThat(service.createRenewal(request())).isEqualTo(winner);
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
            status.setRollbackOnly();
        });
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE renewal_operation_id='renew-1'"))
                .isEqualTo(1);
    }

    @Test
    void migrationIsRepeatableAndDoesNotFabricateLegacyRequestEvidence() throws Exception {
        sql("UPDATE dh_market_resource_apply SET renewal_of_apply_id=8,renewal_operation_id='legacy' WHERE id=7");
        singleTenantConfiguration();
        singleTenantConfiguration();
        assertThat(mapper.selectById(7L).getRenewalRequestedEndTime()).isNull();
        assertThat(scalar("SELECT count(*) FROM pg_indexes WHERE indexname='uk_market_renewal_operation'"))
                .isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM pg_indexes WHERE indexname='uk_market_active_renewal_parent'"))
                .isEqualTo(1);
    }

    @Test
    void globalUniquenessRejectsDuplicateWithoutDeletingExistingApplication() throws Exception {
        sql("INSERT INTO dh_market_resource_apply (id,asset_id,apply_user_id,status) VALUES (20,5,9,1)");
        assertThatThrownBy(() -> sql("INSERT INTO dh_market_resource_apply (id,asset_id,apply_user_id,status)"
                + " VALUES (21,5,9,0)"))
                .isInstanceOfSatisfying(java.sql.SQLException.class,
                        error -> assertThat(error.getSQLState()).isEqualTo("23505"));
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id IN (20,21)")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM pg_indexes WHERE indexname='uk_market_active_pending_apply'"))
                .isEqualTo(1);
    }

    /** 两节点代理之间用屏障控制读/插入窗口，不以睡眠猜测竞争结果。 */
    private Object race(boolean beforeInsert, Callable<?> competitor) throws Exception {
        var reached = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        var delayed = mock(MarketResourceApplyMapper.class, AdditionalAnswers.delegatesTo(mapper));
        var first = new AtomicBoolean(true);
        if (beforeInsert) {
            doAnswer(call -> {
                reached.countDown();
                if (!proceed.await(10, TimeUnit.SECONDS)) throw new AssertionError("Insert barrier timed out");
                return mapper.insertRenewal((MarketResourceApplyDO) call.getArgument(0));
            }).when(delayed).insertRenewal(any(MarketResourceApplyDO.class));
        } else {
            doAnswer(call -> {
                var value = mapper.selectRenewalByOperation( "renew-1");
                if (first.compareAndSet(true, false)) {
                    reached.countDown();
                    if (!proceed.await(10, TimeUnit.SECONDS)) throw new AssertionError("Read barrier timed out");
                }
                return value;
            }).when(delayed).selectRenewalByOperation( "renew-1");
        }
        MarketApplyService contender = service(delayed);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> withoutTenantContext(() -> {
                try { return contender.createRenewal(request()); }
                catch (RuntimeException ex) { return ex; }
            }));
            try {
                assertThat(reached.await(10, TimeUnit.SECONDS)).isTrue();
                competitor.call();
            } finally {
                proceed.countDown();
            }
            return result.get(15, TimeUnit.SECONDS);
        }
    }

    private static <T> T withoutTenantContext(Callable<T> operation) throws Exception {
        TenantContextHolder.clear();
        try { return operation.call(); }
        finally { TenantContextHolder.clear(); }
    }

    private static void assertConflict(Object result) {
        assertThat(result).isInstanceOfSatisfying(ServiceException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(MARKET_APPLY_RENEWAL_CONFLICT.getCode()));
    }
}
