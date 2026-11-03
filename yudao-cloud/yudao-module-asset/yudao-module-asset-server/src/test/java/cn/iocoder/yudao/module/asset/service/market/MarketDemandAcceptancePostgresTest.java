package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDemandCloseReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDemandDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDemandMatchDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDemandMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDemandMatchMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.mockito.InjectMocks;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Future;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实单租户 Mapper/事务/双连接 CAS；交付账本读取边界使用受控替身，不冒充跨服务验收。 */
@Timeout(30)
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketDemandAcceptancePostgresTest extends SharingPostgresSupport {
    @InjectMocks
    private final MarketDemandServiceImpl target = new MarketDemandServiceImpl();
    @Spy
    private final MarketAccessServiceImpl access = new MarketAccessServiceImpl();
    private AutoCloseable injectedMocks;
    private MarketDemandService service;
    private MarketDeliveryService delivery;
    private MarketObjectGuard guard;
    private MarketResourceApplyDO apply;
    private MarketDemandMapper demandMapper;
    private MarketDemandMatchMapper matches;
    private MarketResourceApplyMapper applies;
    private MarketResourceApplyMapper databaseClock;

    @BeforeEach
    void setupDemand() throws Exception {
        injectedMocks = MockitoAnnotations.openMocks(this);
        sql("""
                CREATE TABLE dh_market_demand (
                  id bigint PRIMARY KEY, tenant_id bigint NOT NULL DEFAULT 1, demand_no varchar, title varchar,
                  demand_type int, description varchar, purpose varchar, expected_fields varchar,
                  expected_update_freq varchar, expected_delivery int, expect_finish_time timestamp,
                  urgency int, apply_dept_id bigint, apply_user_id bigint, phone varchar, email varchar,
                  status int NOT NULL, publish_time timestamp, close_reason varchar,
                  acceptance_status int NOT NULL, accepted_apply_id bigint, accepted_time timestamp,
                  remark varchar, creator varchar, updater varchar, create_time timestamp,
                  update_time timestamp, deleted smallint NOT NULL DEFAULT 0);
                INSERT INTO dh_market_demand(id,apply_user_id,status,acceptance_status)
                  VALUES (1,11,3,0), (2,11,3,0);
                CREATE TABLE dh_market_demand_match (
                  id bigint PRIMARY KEY, tenant_id bigint NOT NULL DEFAULT 1, demand_id bigint, asset_id bigint,
                  match_type int, match_score numeric, match_note varchar, status int, apply_id bigint,
                  matcher_user_id bigint, confirm_user_id bigint, confirm_time timestamp, confirm_note varchar,
                  creator varchar, updater varchar, create_time timestamp, update_time timestamp,
                  deleted smallint NOT NULL DEFAULT 0);
                INSERT INTO dh_market_demand_match(id,demand_id,asset_id,apply_id,status)
                  VALUES (5,1,8,9,1),(6,2,8,9,1);
                """);
        var source = new UnpooledDataSource("org.postgresql.Driver",
                jdbcUrl + "?currentSchema=datahub-center&socketTimeout=10", "sharing_test", "");
        var configuration = singleTenantConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        var interceptor = new MybatisPlusInterceptor();

        configuration.addInterceptor(interceptor);
        configuration.setEnvironment(new Environment("demand-acceptance", new SpringManagedTransactionFactory(), source));
        configuration.addMapper(MarketDemandMapper.class);
        configuration.addMapper(MarketDemandMatchMapper.class);
        configuration.addMapper(MarketResourceApplyMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        demandMapper = session.getMapper(MarketDemandMapper.class);
        databaseClock = session.getMapper(MarketResourceApplyMapper.class);
        ReflectionTestUtils.setField(target, "demandMapper", demandMapper);
        matches = session.getMapper(MarketDemandMatchMapper.class);
        ReflectionTestUtils.setField(target, "matchMapper", matches);
        applies = mock(MarketResourceApplyMapper.class);
        apply = new MarketResourceApplyDO();
        apply.setId(9L);
        apply.setAssetId(8L);
        apply.setApplyUserId(11L);
        apply.setStatus(MarketResourceApplyDO.STATUS_APPROVED);
        apply.setIfAuth(MarketResourceApplyDO.AUTH_YES);
        when(applies.selectById(9L)).thenReturn(apply);
        when(applies.selectOwnedForRenewal( 9L, 11L)).thenReturn(apply);
        // 权威时间经过实际 Mapper/SqlSession，能检出同一事务中一级缓存复用旧时钟。
        when(applies.databaseNow()).thenAnswer(call -> databaseClock.databaseNow());
        ReflectionTestUtils.setField(access, "applyMapper", applies);
        ReflectionTestUtils.setField(target, "applyMapper", applies);
        delivery = mock(MarketDeliveryService.class);
        when(delivery.isConfirmedApply(any())).thenReturn(true);
        ReflectionTestUtils.setField(target, "deliveryService", delivery);
        ReflectionTestUtils.setField(target, "displayHelper", new MarketDisplayHelper());
        guard = mock(MarketObjectGuard.class);
        when(guard.requireUserId()).thenReturn(11L);
        when(guard.hasPermission(MarketObjectGuard.PERM_DEMAND_MATCH)).thenReturn(true);
        ReflectionTestUtils.setField(target, "objectGuard", guard);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        service = (MarketDemandService) proxy.getProxy();
        TenantContextHolder.clear();
    }

    @AfterEach void clearTenant() throws Exception {
        TenantContextHolder.clear();
        if (injectedMocks != null) injectedMocks.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"status=0", "status=2", "status=NULL", "asset_id=99", "asset_id=NULL"})
    void invalidMatchCannotSupplyAcceptanceEvidence(String change) throws Exception {
        sql("UPDATE dh_market_demand_match SET " + change + " WHERE id=5");
        assertThatThrownBy(() -> service.acceptDemand(1L)).isInstanceOfSatisfying(ServiceException.class,
                error -> assertThat(error.getCode()).isEqualTo(MARKET_DEMAND_ACCEPT_DENIED.getCode()));
        verifyNoInteractions(delivery);
        assertNotAccepted();
    }

    @ParameterizedTest
    @ValueSource(strings = {"status=0", "status=2", "status=NULL", "demand_id=99", "asset_id=99",
            "apply_id=99", "apply_id=NULL", "deleted=1"})
    void matchChangedDuringDeliveryCannotBeAccepted(String change) throws Exception {
        assertGrantConflict(race(() -> sql("UPDATE dh_market_demand_match SET " + change + " WHERE id=5")));
        assertNotAccepted();
    }

    @Test
    void physicallyDeletedMatchCannotBeAccepted() throws Exception {
        assertGrantConflict(race(() -> sql("DELETE FROM dh_market_demand_match WHERE id=5")));
        assertNotAccepted();
    }

    @ParameterizedTest
    @ValueSource(strings = {"status=2", "asset_id=99", "apply_id=99"})
    void matchChangeCommittedAfterLockWaitIsRechecked(String change) throws Exception {
        try (var executor = Executors.newSingleThreadExecutor(); var blocker = open()) {
            blocker.setAutoCommit(false);
            long blockerPid;
            try (var statement = blocker.createStatement(); var result = statement.executeQuery("SELECT pg_backend_pid()")) {
                result.next();
                blockerPid = result.getLong(1);
            }
            try (var statement = blocker.createStatement()) {
                statement.executeUpdate("UPDATE dh_market_demand_match SET " + change + " WHERE id=5");
            }
            var outcome = executor.submit(this::acceptAsOwner);
            try {
                awaitBlockOrCompletion(outcome, blockerPid);
            } finally {
                blocker.commit();
            }
            assertGrantConflict(outcome.get(10, TimeUnit.SECONDS));
        }
        assertNotAccepted();
    }

    @Test
    void acceptedMatchRemainsLockedUntilAcceptanceCommit() throws Exception {
        var calls = new AtomicInteger();
        when(applies.databaseNow()).thenAnswer(call -> {
            LocalDateTime now = databaseClock.databaseNow();
            if (calls.incrementAndGet() == 3) {
                assertThat(demandMapper.selectById(1L).getAcceptanceStatus()).isEqualTo(MarketDemandDO.ACCEPTANCE_DONE);
                try (var other = open(); var statement = other.createStatement()) {
                    statement.execute("SET lock_timeout='100ms'");
                    assertThatThrownBy(() -> statement.executeUpdate("UPDATE dh_market_demand_match SET status=2 WHERE id=5"))
                            .isInstanceOfSatisfying(java.sql.SQLException.class,
                                    failure -> assertThat(failure.getSQLState()).isEqualTo("55P03"));
                }
            }
            return now;
        });
        service.acceptDemand(1L);
        assertThat(scalar("SELECT accepted_apply_id FROM dh_market_demand WHERE id=1")).isEqualTo(9);
        assertThat(scalar("SELECT status FROM dh_market_demand_match WHERE id=5")).isEqualTo(1);
    }



    @Test
    void acceptedMatchCanReuseSameOwnerAndAssetGrantFromAnotherDemand() throws Exception {
        useRealGrant();
        sql("UPDATE dh_market_resource_apply SET demand_id=71 WHERE id=9");
        service.acceptDemand(1L);
        assertThat(scalar("SELECT accepted_apply_id FROM dh_market_demand WHERE id=1")).isEqualTo(9);
        assertThat(scalar("SELECT demand_id FROM dh_market_resource_apply WHERE id=9")).isEqualTo(71);
    }

    @Test
    void matchNotesDoNotInvalidateAcceptanceEvidence() throws Exception {
        assertThat(race(() -> sql("UPDATE dh_market_demand_match SET match_note='核查备注',"
                + "confirm_note='补充说明',update_time=clock_timestamp() WHERE id=5"))).isEqualTo("unexpected-success");
        assertThat(scalar("SELECT accepted_apply_id FROM dh_market_demand WHERE id=1")).isEqualTo(9);
    }

    @ParameterizedTest
    @ValueSource(ints = {4,5,6})
    void concurrentCloseCancelOrRejectCannotBeResurrected(int terminalStatus) throws Exception {
        // 第二条真实数据库连接通过业务关闭入口先提交，而不是只修改内存 mock。
        Object result = race(() -> close(terminalStatus));
        assertConflict(result);
        assertThat(scalar("SELECT status FROM dh_market_demand WHERE id=1")).isEqualTo(terminalStatus);
        assertThat(scalar("SELECT acceptance_status FROM dh_market_demand WHERE id=1")).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_market_demand WHERE accepted_apply_id IS NOT NULL")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"apply_user_id=12", "status=7", "deleted=1", "acceptance_status=1,accepted_apply_id=99"})
    void changedOwnerStatusDeletionOrCompetingAcceptanceCannotReturnFalseSuccess(String change) throws Exception {
        assertConflict(race(() -> sql("UPDATE dh_market_demand SET " + change + " WHERE id=1")));
        assertThat(scalar("SELECT count(*) FROM dh_market_demand WHERE id=1 AND accepted_apply_id=9")).isZero();
    }

    @Test void acceptanceThenClosureAndRetryPreservesTerminalState() throws Exception {
        service.acceptDemand(1L);
        assertThat(scalar("SELECT accepted_apply_id FROM dh_market_demand WHERE id=1")).isEqualTo(9);
        close(MarketDemandDO.STATUS_CLOSED);
        service.acceptDemand(1L);
        assertThat(scalar("SELECT status FROM dh_market_demand WHERE id=1")).isEqualTo(MarketDemandDO.STATUS_CLOSED);
        assertThat(scalar("SELECT acceptance_status FROM dh_market_demand WHERE id=1")).isEqualTo(MarketDemandDO.ACCEPTANCE_DONE);
        verify(delivery, times(1)).isConfirmedApply(any());
    }

    @Test void unconfirmedDeliveryCannotBeAccepted() throws Exception {
        when(delivery.isConfirmedApply(any())).thenReturn(false);
        assertThatThrownBy(() -> service.acceptDemand(1L)).isInstanceOfSatisfying(ServiceException.class,
                error -> assertThat(error.getCode()).isEqualTo(MARKET_DEMAND_ACCEPT_DENIED.getCode()));
        assertThat(scalar("SELECT acceptance_status FROM dh_market_demand WHERE id=1")).isZero();
    }

    @Test void existingObjectAndOwnerAreRequiredBeforeCheckingDelivery() {
        assertThatThrownBy(() -> service.acceptDemand(9999L)).isInstanceOfSatisfying(ServiceException.class,
                error -> assertThat(error.getCode()).isEqualTo(MARKET_DEMAND_NOT_EXISTS.getCode()));
        when(guard.requireUserId()).thenReturn(12L);
        assertThatThrownBy(() -> service.acceptDemand(1L)).isInstanceOfSatisfying(ServiceException.class,
                error -> assertThat(error.getCode()).isEqualTo(MARKET_DEMAND_FORBIDDEN.getCode()));
        verifyNoInteractions(delivery);
    }

    @Test void expiryDuringDeliveryReadCannotBeAccepted() throws Exception {
        LocalDateTime initial = databaseNow();
        apply.setApplyEndTime(initial.plusSeconds(1));
        when(delivery.isConfirmedApply(any())).thenAnswer(call -> {
            awaitDatabaseTime(apply.getApplyEndTime());
            return true;
        });
        try (var jvmClock = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            jvmClock.when(LocalDateTime::now).thenReturn(initial);
            assertThatThrownBy(() -> service.acceptDemand(1L)).isInstanceOfSatisfying(ServiceException.class,
                    error -> assertThat(error.getCode()).isEqualTo(MARKET_DEMAND_ACCEPT_DENIED.getCode()));
        }
        verify(delivery).isConfirmedApply(apply);
        assertNotAccepted();
    }

    @Test void acceptedTimeUsesDatabaseClockInsteadOfJvmTime() throws Exception {
        LocalDateTime before = databaseNow();
        LocalDateTime skewedJvm = before.minusYears(1);
        try (var jvmClock = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            jvmClock.when(LocalDateTime::now).thenReturn(skewedJvm);
            service.acceptDemand(1L);
        }
        LocalDateTime after = databaseNow();
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT accepted_time FROM dh_market_demand WHERE id=1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getObject(1, LocalDateTime.class)).isBetween(before, after);
        }
        assertThat(scalar("SELECT accepted_apply_id FROM dh_market_demand WHERE id=1")).isEqualTo(9);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void expiryWhileWaitingForDemandLockCannotBeAccepted(boolean updateTuple) throws Exception {
        // 纯行锁不会产生新 tuple；只给 UPDATE 的 WHERE 加时钟不足以保证等待后的复核。
        // 第二种情况再覆盖相同业务状态但更新了 tuple 的竞争事务。
        try (var executor = Executors.newSingleThreadExecutor(); var blocker = open()) {
            blocker.setAutoCommit(false);
            long blockerPid;
            try (var statement = blocker.createStatement(); var result = statement.executeQuery("SELECT pg_backend_pid()")) {
                result.next();
                blockerPid = result.getLong(1);
            }
            try (var statement = blocker.createStatement()) {
                if (updateTuple) {
                    statement.executeUpdate("UPDATE dh_market_demand SET remark='lock contender' WHERE id=1");
                } else {
                    try (var result = statement.executeQuery("SELECT id FROM dh_market_demand WHERE id=1 FOR UPDATE")) {
                        assertThat(result.next()).isTrue();
                    }
                }
            }
            LocalDateTime initial = databaseNow();
            apply.setApplyEndTime(initial.plusSeconds(3));
            var outcome = executor.submit(() -> {
                TenantContextHolder.clear();
                try (var jvmClock = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
                    jvmClock.when(LocalDateTime::now).thenReturn(initial);
                    service.acceptDemand(1L);
                    return "unexpected-success";
                } catch (ServiceException failure) {
                    return failure;
                } finally {
                    TenantContextHolder.clear();
                }
            });
            try {
                awaitSql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()"
                        + " AND pid<>pg_backend_pid() AND " + blockerPid + "=ANY(pg_blocking_pids(pid))");
                assertThat(databaseNow()).as("must observe the lock wait before expiry")
                        .isBefore(apply.getApplyEndTime());
                awaitDatabaseTime(apply.getApplyEndTime());
            } finally {
                // 在读取结果/关闭 executor 前释放锁，失败路径也不会挂住工作线程。
                blocker.commit();
            }
            assertThat(outcome.get(10, TimeUnit.SECONDS)).isInstanceOfSatisfying(ServiceException.class,
                    error -> assertThat(error.getCode()).isEqualTo(MARKET_DEMAND_ACCEPT_DENIED.getCode()));
        }
        assertNotAccepted();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void clockNullOrFailureAfterCasRollsBackActualAcceptance(boolean throwsException) throws Exception {
        var calls = new AtomicInteger();
        var failure = new IllegalStateException("test clock unavailable after persisted CAS");
        when(applies.databaseNow()).thenAnswer(call -> {
            if (calls.incrementAndGet() != 3) return databaseClock.databaseNow();
            // 经事务绑定的真实 Mapper 读取自己已写入的行，证明失败发生在成功 CAS 之后。
            MarketDemandDO written = demandMapper.selectById(1L);
            assertThat(written.getAcceptanceStatus()).isEqualTo(MarketDemandDO.ACCEPTANCE_DONE);
            assertThat(written.getAcceptedApplyId()).isEqualTo(9L);
            assertThat(written.getAcceptedTime()).isNotNull();
            if (throwsException) throw failure;
            return null;
        });
        if (throwsException) {
            assertThatThrownBy(() -> service.acceptDemand(1L)).isSameAs(failure);
        } else {
            assertThatThrownBy(() -> service.acceptDemand(1L)).isInstanceOfSatisfying(ServiceException.class,
                    error -> assertThat(error.getCode()).isEqualTo(MARKET_APPLY_NOT_AUTHORIZED.getCode()));
        }
        assertThat(calls.get()).isEqualTo(3);
        verify(delivery).isConfirmedApply(apply);
        // 独立连接只可见回滚后的原始需求，不得保留验收人关联或验收时间。
        assertNotAccepted();
    }

    private void assertNotAccepted() throws Exception {
        assertThat(scalar("SELECT status FROM dh_market_demand WHERE id=1")).isEqualTo(MarketDemandDO.STATUS_MATCHED);
        assertThat(scalar("SELECT acceptance_status FROM dh_market_demand WHERE id=1")).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_market_demand WHERE id=1"
                + " AND (accepted_time IS NOT NULL OR accepted_apply_id IS NOT NULL)")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"status=6", "if_auth=0", "paused=1", "source_revision=3", "revision=2",
            "apply_end_time='2041-01-01'", "apply_start_time='2001-01-01'", "app_id=21", "api_id=31",
            "api_id=NULL", "field_scope='secret'", "snapshot='changed'", "asset_id=19",
            "apply_user_id=12", "demand_id=2", "deleted=1"})
    void grantChangedDuringDeliveryCannotBeAccepted(String change) throws Exception {
        useRealGrant();
        Object outcome = race(() -> sql("UPDATE dh_market_resource_apply SET " + change + " WHERE id=9"));
        assertGrantConflict(outcome);
        assertNotAccepted();
    }

    @Test
    void physicallyDeletedGrantCannotBeAccepted() throws Exception {
        useRealGrant();
        assertGrantConflict(race(() -> sql("DELETE FROM dh_market_resource_apply WHERE id=9")));
        assertNotAccepted();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void grantChangeCommittedAfterLockWaitIsRechecked(boolean bindingOnly) throws Exception {
        useRealGrant();
        try (var executor = Executors.newSingleThreadExecutor(); var blocker = open()) {
            blocker.setAutoCommit(false);
            long blockerPid;
            try (var statement = blocker.createStatement(); var result = statement.executeQuery("SELECT pg_backend_pid()")) {
                result.next();
                blockerPid = result.getLong(1);
            }
            try (var statement = blocker.createStatement()) {
                statement.executeUpdate("UPDATE dh_market_resource_apply SET "
                        + (bindingOnly ? "api_id=31" : "status=6,if_auth=0,source_revision=3") + " WHERE id=9");
            }
            var outcome = executor.submit(this::acceptAsOwner);
            try {
                awaitBlockOrCompletion(outcome, blockerPid);
            } finally {
                blocker.commit();
            }
            assertGrantConflict(outcome.get(10, TimeUnit.SECONDS));
        }
        assertNotAccepted();
    }

    @Test
    void acceptedGrantIsLockedUntilAcceptanceTransactionCommits() throws Exception {
        useRealGrant();
        var written = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var acceptancePid = new java.util.concurrent.atomic.AtomicLong();
        // 第三次时钟调用发生在需求 CAS 后，阻塞在事务提交前以检验申请锁的生命周期。
        when(applies.databaseNow()).thenAnswer(call -> {
            LocalDateTime now = databaseClock.databaseNow();
            if (calls.incrementAndGet() == 3) {
                acceptancePid.set(scalar("SELECT pid FROM pg_stat_activity WHERE datname=current_database()"
                        + " AND pid<>pg_backend_pid() AND state='idle in transaction' LIMIT 1"));
                written.countDown();
                if (!finish.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Acceptance release timeout");
            }
            return now;
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var acceptance = executor.submit(this::acceptAsOwner);
            Future<?> revoke = null;
            try {
                assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();
                revoke = executor.submit(() -> {
                    try (var db = open(); var statement = db.createStatement()) {
                        return statement.executeUpdate("UPDATE dh_market_resource_apply SET status=6,if_auth=0,"
                                + "source_revision=3 WHERE id=9");
                    }
                });
                awaitBlockOrCompletion(revoke, acceptancePid.get());
                assertThat(revoke.isDone()).as("revocation must wait for the acceptance grant lock").isFalse();
            } finally {
                finish.countDown();
            }
            assertThat(acceptance.get(10, TimeUnit.SECONDS)).isEqualTo("accepted");
            assertThat(revoke.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(scalar("SELECT accepted_apply_id FROM dh_market_demand WHERE id=1")).isEqualTo(9);
        assertThat(scalar("SELECT status FROM dh_market_resource_apply WHERE id=9")).isEqualTo(6);
        // 已完成验收是历史事实，后续撤销不使相同请求再次验收，也不复活授权。
        service.acceptDemand(1L);
        verify(delivery, times(1)).isConfirmedApply(any());
    }

    @Test
    void unrelatedUsageUpdateDoesNotInvalidateTheConfirmedGrant() throws Exception {
        useRealGrant();
        assertThat(race(() -> sql("UPDATE dh_market_resource_apply SET first_used_time=clock_timestamp(),"
                + "remark='usage observed' WHERE id=9"))).isEqualTo("unexpected-success");
        assertThat(scalar("SELECT accepted_apply_id FROM dh_market_demand WHERE id=1")).isEqualTo(9);
    }

    private void useRealGrant() throws Exception {
        try (var input = getClass().getResourceAsStream("/sql/market-freeze-fixture.sql")) {
            if (input == null) throw new IllegalStateException("Missing grant fixture");
            sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        sql("""
                INSERT INTO dh_market_resource_apply(id,apply_user_id,asset_id,app_id,api_id,
                    demand_id,status,if_auth,paused,revision,source_revision,apply_start_time,apply_end_time,
                    field_scope,snapshot)
                  VALUES(9,11,8,20,30,1,2,1,0,1,2,'2000-01-01','2040-01-01','id','original');
                """);
        when(applies.selectById(9L)).thenAnswer(call -> databaseClock.selectById(9L));
        when(applies.selectOwnedForRenewal( 9L, 11L))
                .thenAnswer(call -> databaseClock.selectOwnedForRenewal( 9L, 11L));
    }

    private Object acceptAsOwner() {
        TenantContextHolder.clear();
        try { service.acceptDemand(1L); return "accepted"; }
        catch (ServiceException failure) { return failure; }
        finally { TenantContextHolder.clear(); }
    }

    private void awaitBlockOrCompletion(Future<?> future, long blockerPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!future.isDone() && scalar("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()"
                + " AND " + blockerPid + "=ANY(pg_blocking_pids(pid))") == 0) {
            assertThat(System.nanoTime()).as("grant lock wait timeout").isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    private static void assertGrantConflict(Object outcome) {
        assertThat(outcome).isInstanceOfSatisfying(ServiceException.class,
                error -> assertThat(error.getCode()).isEqualTo(MARKET_DEMAND_ACCEPT_DENIED.getCode()));
    }

    private LocalDateTime databaseNow() throws Exception {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT clock_timestamp() AT TIME ZONE 'Asia/Shanghai'")) {
            result.next();
            return result.getObject(1, LocalDateTime.class);
        }
    }

    private void awaitDatabaseTime(LocalDateTime threshold) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (databaseNow().isBefore(threshold)) {
            assertThat(System.nanoTime()).as("database expiry wait timeout").isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    private void awaitSql(String condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (scalar(condition) == 0) {
            assertThat(System.nanoTime()).as("database lock wait timeout").isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    private void close(int status) {
        // 驳回要求匹配管理员不是需求本人；关闭/取消由本人操作。
        when(guard.requireUserId()).thenReturn(status == MarketDemandDO.STATUS_REJECTED ? 22L : 11L);
        var request = new MarketDemandCloseReqVO();
        request.setId(1L);
        request.setStatus(status);
        request.setCloseReason("test closure");
        service.closeDemand(request);
        when(guard.requireUserId()).thenReturn(11L);
    }

    private Object race(CheckedRunnable concurrentAction) throws Exception {
        var read = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(delivery.isConfirmedApply(any())).thenAnswer(call -> {
            read.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Race release timeout");
            return true;
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var outcome = executor.submit(() -> {
                TenantContextHolder.clear();
                try { service.acceptDemand(1L); return "unexpected-success"; }
                catch (ServiceException failure) { return failure; }
                finally { TenantContextHolder.clear(); }
            });
            try {
                assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();
                concurrentAction.run();
            } finally { release.countDown(); }
            return outcome.get(10, TimeUnit.SECONDS);
        }
    }

    private static void assertConflict(Object result) {
        assertThat(result).isInstanceOfSatisfying(ServiceException.class,
                error -> assertThat(error.getCode()).isEqualTo(MARKET_DEMAND_STATUS_DENY.getCode()));
    }

    @FunctionalInterface interface CheckedRunnable { void run() throws Exception; }
}
