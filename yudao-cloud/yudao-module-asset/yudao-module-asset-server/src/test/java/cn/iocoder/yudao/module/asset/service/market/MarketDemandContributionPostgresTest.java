package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDemandCloseReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDemandMatchSaveReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDemandReplySaveReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDemandMatchDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDemandReplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDemandMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDemandMatchMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDemandReplyMapper;
import cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.incrementer.PostgreKeyGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实资产/需求/响应/匹配 Mapper、租户插件与 Spring 事务；通知远端为受控替身。 */
@Timeout(30)
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketDemandContributionPostgresTest extends SharingPostgresSupport {
    private MarketDemandService service;
    private MarketDemandReplyMapper replies;
    private MarketDemandMatchMapper matches;
    private DataAssetMapper assets;
    private final MarketObjectGuard guard = mock(MarketObjectGuard.class);
    private final NotifyMessageSendApi notifications = mock(NotifyMessageSendApi.class);

    @BeforeEach
    void prepare() throws Exception {
        try (var input = getClass().getResourceAsStream("/sql/market-demand-asset-fixture.sql")) {
            if (input == null) throw new IllegalStateException("Missing test-only asset fixture");
            sql(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        sql("""
                CREATE TABLE dh_market_demand (
                  id bigint PRIMARY KEY, tenant_id bigint NOT NULL DEFAULT 1, demand_no varchar, title varchar,
                  demand_type int, description varchar, purpose varchar, expected_fields varchar,
                  expected_update_freq varchar, expected_delivery int, expect_finish_time timestamp,
                  urgency int, apply_dept_id bigint, apply_user_id bigint, phone varchar, email varchar,
                  status int, publish_time timestamp, close_reason varchar,
                  acceptance_status int, accepted_apply_id bigint, accepted_time timestamp,
                  remark varchar, creator varchar, updater varchar, create_time timestamp,
                  update_time timestamp, deleted smallint NOT NULL DEFAULT 0);
                INSERT INTO dh_market_demand(id,title,apply_user_id,apply_dept_id,
                    status,acceptance_status,purpose,expected_fields)
                  VALUES(1,'original',11,100,1,0,'statistics','id'),
                        (2,'other tenant',11,100,1,0,'statistics','id');
                CREATE SEQUENCE dh_market_demand_match_seq;
                CREATE TABLE dh_market_demand_match (
                  id bigint PRIMARY KEY DEFAULT nextval('dh_market_demand_match_seq'),
                  tenant_id bigint NOT NULL DEFAULT 1, demand_id bigint NOT NULL, asset_id bigint NOT NULL,
                  match_type int, match_score numeric, match_note varchar, status int, apply_id bigint,
                  matcher_user_id bigint, confirm_user_id bigint, confirm_time timestamp, confirm_note varchar,
                  creator varchar, updater varchar, create_time timestamp NOT NULL, update_time timestamp NOT NULL,
                  deleted smallint NOT NULL DEFAULT 0);
                CREATE SEQUENCE dh_market_demand_reply_seq;
                CREATE TABLE dh_market_demand_reply (
                  id bigint PRIMARY KEY, tenant_id bigint NOT NULL DEFAULT 1, demand_id bigint, reply_type int,
                  content varchar, reply_dept_id bigint, reply_user_id bigint,
                  creator varchar, updater varchar, create_time timestamp, update_time timestamp,
                  deleted smallint NOT NULL DEFAULT 0);
                """);
        migrate("0068_asset_demand-match-unique.sql");
        var source = new UnpooledDataSource("org.postgresql.Driver",
                jdbcUrl + "?currentSchema=datahub-center&socketTimeout=10", "sharing_test", "");
        var config = singleTenantConfiguration();
        var global = new GlobalConfig().setMetaObjectHandler(new DefaultDBFieldHandler());
        global.setDbConfig(new GlobalConfig.DbConfig().setIdType(IdType.INPUT)
                .setKeyGenerators(java.util.List.of(new PostgreKeyGenerator())));
        GlobalConfigUtils.setGlobalConfig(config, global);
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("demand-contribution", new SpringManagedTransactionFactory(), source));
        var plugins = new MybatisPlusInterceptor();

        config.addInterceptor(plugins);
        config.addMapper(MarketDemandMapper.class);
        config.addMapper(MarketDemandReplyMapper.class);
        config.addMapper(MarketDemandMatchMapper.class);
        config.addMapper(DataAssetMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        replies = spy(session.getMapper(MarketDemandReplyMapper.class));
        matches = spy(session.getMapper(MarketDemandMatchMapper.class));
        assets = spy(session.getMapper(DataAssetMapper.class));
        var notify = new MarketNotifyHelper();
        ReflectionTestUtils.setField(notify, "notifyMessageSendApi", notifications);
        var target = new MarketDemandServiceImpl();
        ReflectionTestUtils.setField(target, "demandMapper", session.getMapper(MarketDemandMapper.class));
        ReflectionTestUtils.setField(target, "replyMapper", replies);
        ReflectionTestUtils.setField(target, "matchMapper", matches);
        ReflectionTestUtils.setField(target, "assetMapper", assets);
        ReflectionTestUtils.setField(target, "objectGuard", guard);
        ReflectionTestUtils.setField(target, "notifyHelper", notify);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        service = (MarketDemandService) proxy.getProxy();
        when(guard.requireUserId()).thenReturn(22L);
        when(guard.hasPermission(MarketObjectGuard.PERM_DEMAND_MATCH)).thenReturn(true);
        TenantContextHolder.clear();
    }

    @AfterEach
    void clearTenant() { TenantContextHolder.clear(); }

    static Stream<String> modes() { return Stream.of("REPLY", "MATCH"); }

    static Stream<Arguments> changedDemand() {
        return modes().flatMap(mode -> Stream.of("status=4", "status=5", "status=6", "apply_user_id=33",
                "apply_dept_id=101", "acceptance_status=1", "accepted_apply_id=9", "accepted_time='2020-01-01'",
                "title='changed'", "purpose='changed'", "expected_fields='secret'", "deleted=1")
                .map(change -> Arguments.of(mode, change)));
    }

    @ParameterizedTest
    @MethodSource("changedDemand")
    void demandChangeAfterReadRollsBackInsertedChildAndSuppressesNotification(String mode, String change) throws Exception {
        deny(race(mode, () -> sql("UPDATE dh_market_demand SET " + change + " WHERE id=1")));
        assertNoContribution();
        assertThat(scalar("SELECT count(*) FROM dh_market_demand WHERE id=1 AND " + change)).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("modes")
    void physicalDeletionAfterReadCannotLeaveOrphanChild(String mode) throws Exception {
        deny(race(mode, () -> sql("DELETE FROM dh_market_demand WHERE id=1")));
        assertNoContribution();
    }

    static Stream<Arguments> stableStatuses() {
        return modes().flatMap(mode -> Stream.of(1, 2, 3, 7).map(status -> Arguments.of(mode, status)));
    }

    @ParameterizedTest
    @MethodSource("stableStatuses")
    void openDemandKeepsExistingMatchedOrApplyingProgress(String mode, int status) throws Exception {
        sql("UPDATE dh_market_demand SET status=" + status + " WHERE id=1");
        long childId = contribute(mode, 8L);
        assertThat(scalar("SELECT status FROM dh_market_demand WHERE id=1")).isEqualTo(status == 1 ? 2 : status);
        assertThat(scalar("SELECT count(*) FROM " + table(mode) + " WHERE id=" + childId
                + " AND demand_id=1 AND deleted=0")).isEqualTo(1);
        if (mode.equals("REPLY")) {
            assertThat(scalar("SELECT count(*) FROM dh_market_demand_reply WHERE reply_type=2"
                    + " AND content='test contribution' AND reply_user_id=22 AND reply_dept_id=200")).isEqualTo(1);
        } else {
            assertThat(scalar("SELECT count(*) FROM dh_market_demand_match WHERE status=0 AND match_type=2"
                    + " AND asset_id=8 AND matcher_user_id=22 AND apply_id IS NULL")).isEqualTo(1);
        }
        verify(notifications).sendSingleMessageToAdmin(any());
    }

    @ParameterizedTest
    @MethodSource("modes")
    void nullSnapshotValuesRemainExactAndSupported(String mode) throws Exception {
        sql("UPDATE dh_market_demand SET apply_user_id=NULL,apply_dept_id=NULL,acceptance_status=NULL,"
                + "accepted_apply_id=NULL,accepted_time=NULL,title=NULL,purpose=NULL,expected_fields=NULL WHERE id=1");
        assertThat(contribute(mode, 8L)).isPositive();
        assertThat(scalar("SELECT status FROM dh_market_demand WHERE id=1")).isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM " + table(mode))).isEqualTo(1);
        verifyNoInteractions(notifications);
    }

    @ParameterizedTest
    @MethodSource("modes")
    void changeAfterActualInsertRollsBackTheChild(String mode) throws Exception {
        afterInsert(mode, () -> sql("UPDATE dh_market_demand SET status=4 WHERE id=1"));
        assertThatThrownBy(() -> contribute(mode, 8L)).isInstanceOfSatisfying(ServiceException.class,
                error -> assertThat(error.getCode()).isEqualTo(MARKET_DEMAND_STATUS_DENY.getCode()));
        assertNoContribution();
        assertThat(scalar("SELECT status FROM dh_market_demand WHERE id=1")).isEqualTo(4);
    }

    @ParameterizedTest
    @MethodSource("modes")
    void zeroInsertCannotAdvanceDemandOrSendSuccessNotification(String mode) throws Exception {
        if (mode.equals("REPLY")) doReturn(0).when(replies).insert(any(MarketDemandReplyDO.class));
        else doReturn(0).when(matches).insertIfAbsent(any(MarketDemandMatchDO.class));
        assertThatThrownBy(() -> contribute(mode, 8L)).isInstanceOfSatisfying(ServiceException.class,
                error -> assertThat(error.getCode()).isEqualTo((mode.equals("MATCH")
                        ? MARKET_DEMAND_MATCH_DUPLICATE : MARKET_DEMAND_STATUS_DENY).getCode()));
        assertNoContribution();
        assertThat(scalar("SELECT status FROM dh_market_demand WHERE id=1")).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("modes")
    void demandConstraintFailureRollsBackActualInsert(String mode) throws Exception {
        sql("ALTER TABLE dh_market_demand ADD CONSTRAINT test_demand_failure CHECK(status<>2)");
        assertThatThrownBy(() -> contribute(mode, 8L)).isInstanceOf(RuntimeException.class);
        assertNoContribution();
        assertThat(scalar("SELECT status FROM dh_market_demand WHERE id=1")).isEqualTo(1);
    }



    @ParameterizedTest
    @MethodSource("modes")
    void observedDemandLockWaitRechecksConcurrentClosure(String mode) throws Exception {
        try (var worker = Executors.newSingleThreadExecutor(); var blocker = open()) {
            blocker.setAutoCommit(false);
            long blockerPid;
            try (var statement = blocker.createStatement(); var result = statement.executeQuery("SELECT pg_backend_pid()")) {
                result.next();
                blockerPid = result.getLong(1);
            }
            try (var statement = blocker.createStatement();
                 var result = statement.executeQuery("SELECT id FROM dh_market_demand WHERE id=1 FOR UPDATE")) {
                assertThat(result.next()).isTrue();
            }
            var outcome = worker.submit(() -> contributeAsTenant(mode, 8L));
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (scalar("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()"
                        + " AND " + blockerPid + "=ANY(pg_blocking_pids(pid))") == 0) {
                    assertThat(outcome.isDone()).as("must reach actual demand row lock wait").isFalse();
                    assertThat(System.nanoTime()).as("demand lock wait timeout").isLessThan(deadline);
                    Thread.sleep(10);
                }
                try (var statement = blocker.createStatement()) {
                    statement.executeUpdate("UPDATE dh_market_demand SET status=4 WHERE id=1");
                }
            } finally {
                blocker.commit();
            }
            deny(outcome.get(10, TimeUnit.SECONDS));
        }
        assertNoContribution();
    }

    @ParameterizedTest
    @MethodSource("modes")
    void realCloseCommandWinsOverAlreadyReadContribution(String mode) throws Exception {
        deny(race(mode, () -> {
            var close = new MarketDemandCloseReqVO();
            close.setId(1L);
            close.setStatus(4);
            close.setCloseReason("test closure");
            service.closeDemand(close);
        }));
        assertNoContribution();
        assertThat(scalar("SELECT count(*) FROM dh_market_demand WHERE id=1 AND status=4"
                + " AND close_reason='test closure'")).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("modes")
    void notificationReadsCommittedChildAndCanAcquireDemandRow(String mode) throws Exception {
        var observed = new AtomicInteger();
        doAnswer(call -> {
            assertThat(scalar("SELECT count(*) FROM " + table(mode) + " WHERE demand_id=1")).isEqualTo(1);
            assertThat(scalar("SELECT status FROM dh_market_demand WHERE id=1")).isEqualTo(2);
            sql("SET lock_timeout='100ms'");
            try {
                sql("UPDATE dh_market_demand SET remark='observed after commit' WHERE id=1");
            } finally {
                sql("RESET lock_timeout");
            }
            observed.incrementAndGet();
            return null;
        }).when(notifications).sendSingleMessageToAdmin(any());
        assertThat(contribute(mode, 8L)).isPositive();
        assertThat(observed.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("modes")
    void failureAtActualCommitDoesNotPublishScheduledNotification(String mode) throws Exception {
        sql("""
                CREATE FUNCTION test_reject_contribution_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  RAISE EXCEPTION 'test failure at commit';
                  RETURN NULL;
                END $$;
                """);
        sql("CREATE CONSTRAINT TRIGGER test_reject_contribution AFTER INSERT ON " + table(mode)
                + " DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION test_reject_contribution_commit()");
        assertThatThrownBy(() -> contribute(mode, 8L)).isInstanceOf(RuntimeException.class);
        assertNoContribution();
        assertThat(scalar("SELECT status FROM dh_market_demand WHERE id=1")).isEqualTo(1);
    }

    static Stream<Arguments> concurrentModes() {
        return modes().flatMap(mode -> Stream.of(1, 2).map(status -> Arguments.of(mode, status)));
    }

    @ParameterizedTest
    @MethodSource("concurrentModes")
    void concurrentContributionsNeverRewindStateAndDoNotRejectUnchangedMatching(String mode, int status) throws Exception {
        sql("UPDATE dh_market_demand SET status=" + status + " WHERE id=1");
        var reads = new CountDownLatch(2);
        when(guard.requireUserId()).thenAnswer(call -> {
            reads.countDown();
            if (!reads.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent read wait timeout");
            return 22L;
        });
        int expected = status == 1 ? 1 : 2;
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> contributeAsTenant(mode, 8L));
            var second = workers.submit(() -> contributeAsTenant(mode, 9L));
            var outcomes = java.util.List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(Long.class::isInstance).count()).isEqualTo(expected);
            outcomes.stream().filter(result -> !(result instanceof Long)).forEach(MarketDemandContributionPostgresTest::deny);
        }
        assertThat(scalar("SELECT count(*) FROM " + table(mode))).isEqualTo(expected);
        assertThat(scalar("SELECT status FROM dh_market_demand WHERE id=1")).isEqualTo(2);
        verify(notifications, times(expected)).sendSingleMessageToAdmin(any());
    }

    static Stream<Arguments> invalidStatuses() {
        return modes().flatMap(mode -> Stream.of("NULL", "0", "4", "99", "-1")
                .map(status -> Arguments.of(mode, status)));
    }

    @ParameterizedTest
    @MethodSource("invalidStatuses")
    void draftTerminalOrInvalidStateIsRejectedBeforeInsert(String mode, String status) throws Exception {
        sql("UPDATE dh_market_demand SET status=" + status + " WHERE id=1");
        assertThatThrownBy(() -> contribute(mode, 8L)).isInstanceOfSatisfying(ServiceException.class,
                error -> assertThat(error.getCode()).isEqualTo(MARKET_DEMAND_STATUS_DENY.getCode()));
        assertNoContribution();
        verifyNoInteractions(guard);
    }

    private Object race(String mode, CheckedRunnable change) throws Exception {
        var read = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        var calls = new AtomicInteger();
        when(guard.requireUserId()).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) {
                read.countDown();
                if (!proceed.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Contribution read wait timeout");
            }
            return 22L;
        });
        try (var worker = Executors.newSingleThreadExecutor()) {
            var result = worker.submit(() -> contributeAsTenant(mode, 8L));
            try {
                assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();
                change.run();
            } finally {
                proceed.countDown();
            }
            return result.get(15, TimeUnit.SECONDS);
        }
    }

    static Stream<Arguments> changedAsset() {
        return Stream.concat(Stream.of("status=0", "status=2", "status=NULL")
                        .map(change -> Arguments.of(change, MARKET_APPLY_ASSET_NOT_PUBLISHED.getCode())),
                Stream.concat(Stream.of("deleted=1", "id=88")
                                .map(change -> Arguments.of(change, DATA_ASSET_NOT_EXISTS.getCode())),
                        Stream.of("asset_type=2", "catalog_item_urn='other'", "meta_table_id=32", "resource_urn='other'",
                                "published_snapshot_id=42", "published_version='v2'", "published_catalog_version_id=52",
                                "datasource_id=62", "db_name='other'", "schema_name='other'", "table_name='other'",
                                "owner_dept_id=72", "tech_leader_user_id=83", "business_leader_user_id=84",
                                "provider_dept_id=93", "source_dept_id=94", "share_attr=3", "share_attribute=3",
                                "share_condition='other'", "share_deny_reason='other'", "tech_available=0", "catalog_revision=2")
                                .map(change -> Arguments.of(change, MARKET_DEMAND_STATUS_DENY.getCode()))));
    }

    @ParameterizedTest
    @MethodSource("changedAsset")
    void assetChangeAfterInitialReadRejectsOldMatchAndNotification(String change, int errorCode) throws Exception {
        Object result = race("MATCH", () -> sql("UPDATE dh_asset_data_asset SET " + change + " WHERE id=8"));
        assertThat(result).isInstanceOfSatisfying(ServiceException.class,
                error -> assertThat(error.getCode()).isEqualTo(errorCode));
        assertNoContribution();
        assertThat(scalar("SELECT status FROM dh_market_demand WHERE id=1")).isEqualTo(1);
    }

    @Test
    void assetPhysicalDeletionAfterReadCannotLeaveMatch() throws Exception {
        assertThat(race("MATCH", () -> sql("DELETE FROM dh_asset_data_asset WHERE id=8")))
                .isInstanceOfSatisfying(ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(DATA_ASSET_NOT_EXISTS.getCode()));
        assertNoContribution();
    }

    @Test
    void assetOfflineAfterRealInsertStillRollsBackMatch() throws Exception {
        afterInsert("MATCH", () -> sql("UPDATE dh_asset_data_asset SET status=2 WHERE id=8"));
        assertThatThrownBy(() -> contribute("MATCH", 8L)).isInstanceOfSatisfying(ServiceException.class,
                error -> assertThat(error.getCode()).isEqualTo(MARKET_APPLY_ASSET_NOT_PUBLISHED.getCode()));
        assertNoContribution();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"status=2", "table_name='other'", "deleted=1"})
    void assetRowLockWaitReadsCommittedChangeInsteadOfOldCachedAsset(String change) throws Exception {
        try (var workers = Executors.newSingleThreadExecutor(); var blocker = open()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                statement.executeQuery("SELECT id FROM dh_asset_data_asset WHERE id=8 FOR UPDATE").close();
            }
            var result = workers.submit(() -> contributeAsTenant("MATCH", 8L));
            try {
                awaitBlockedSql(result, "%FROM dh_asset_data_asset%", "asset row lock");
                try (var statement = blocker.createStatement()) {
                    statement.executeUpdate("UPDATE dh_asset_data_asset SET " + change + " WHERE id=8");
                }
            } finally { blocker.commit(); }
            int expected = change.startsWith("status") ? MARKET_APPLY_ASSET_NOT_PUBLISHED.getCode()
                    : change.startsWith("table_name") ? MARKET_DEMAND_STATUS_DENY.getCode() : DATA_ASSET_NOT_EXISTS.getCode();
            assertThat(result.get(10, TimeUnit.SECONDS)).isInstanceOfSatisfying(ServiceException.class,
                    error -> assertThat(error.getCode()).isEqualTo(expected));
        }
        assertNoContribution();
    }

    @Test
    void sharedAssetLockProtectsMatchUntilCommitThenAllowsAssetWriter() throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            Object current = call.callRealMethod();
            locked.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Locked asset wait timeout");
            return current;
        }).when(assets).selectForMatching( 8L);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var result = workers.submit(() -> contributeAsTenant("MATCH", 8L));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                var offline = workers.submit(() -> {
                    try (var writer = open(); var statement = writer.createStatement()) {
                        return statement.executeUpdate("UPDATE dh_asset_data_asset SET status=2 WHERE id=8");
                    }
                });
                awaitBlockedSql(offline, "%UPDATE dh_asset_data_asset%", "asset writer");
                release.countDown();
                assertThat(result.get(10, TimeUnit.SECONDS)).isInstanceOf(Long.class);
                assertThat(offline.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            } finally { release.countDown(); }
        }
        assertThat(scalar("SELECT count(*) FROM dh_market_demand_match")).isEqualTo(1);
        assertThat(scalar("SELECT status FROM dh_asset_data_asset WHERE id=8")).isEqualTo(2);
        verify(notifications).sendSingleMessageToAdmin(any());
    }

    @Test
    void differentDemandsCanHoldSharedLocksForTheSameAssetTogether() throws Exception {

        var readers = new CountDownLatch(2);
        doAnswer(call -> {
            Object current = call.callRealMethod();
            readers.countDown();
            if (!readers.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Shared readers were serialized");
            return current;
        }).when(assets).selectForMatching( 8L);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> contributeAsTenant("MATCH", 8L));
            var second = workers.submit(() -> {
                TenantContextHolder.clear();
                try {
                    var req = new MarketDemandMatchSaveReqVO();
                    req.setDemandId(2L);
                    req.setAssetId(8L);
                    return service.matchDemand(req);
                } finally { TenantContextHolder.clear(); }
            });
            assertThat(first.get(15, TimeUnit.SECONDS)).isInstanceOf(Long.class);
            assertThat(second.get(15, TimeUnit.SECONDS)).isPositive();
        }
        assertThat(scalar("SELECT count(*) FROM dh_market_demand_match WHERE asset_id=8")).isEqualTo(2);
        verify(notifications, times(2)).sendSingleMessageToAdmin(any());
    }

    @Test
    void demandClosureAfterAssetLockStillRollsBackMatchAndReleasesAsset() throws Exception {
        doAnswer(call -> {
            Object current = call.callRealMethod();
            sql("UPDATE dh_market_demand SET status=4 WHERE id=1");
            return current;
        }).when(assets).selectForMatching( 8L);
        assertThatThrownBy(() -> contribute("MATCH", 8L)).isInstanceOfSatisfying(ServiceException.class,
                error -> assertThat(error.getCode()).isEqualTo(MARKET_DEMAND_STATUS_DENY.getCode()));
        assertNoContribution();
        sql("SET lock_timeout='100ms'");
        sql("UPDATE dh_asset_data_asset SET status=2 WHERE id=8");
    }

    @Test
    void notificationUsesLatestDisplayNameAndRunsAfterAssetLockRelease() throws Exception {
        var observed = new AtomicInteger();
        doAnswer(call -> {
            var dto = (cn.iocoder.yudao.module.system.api.notify.dto.NotifySendSingleToUserReqDTO) call.getArgument(0);
            assertThat(dto.getTemplateParams()).containsEntry("assetName", "renamed");
            sql("SET lock_timeout='100ms'");
            sql("UPDATE dh_asset_data_asset SET view_count=12 WHERE id=8");
            observed.incrementAndGet();
            return null;
        }).when(notifications).sendSingleMessageToAdmin(any());
        assertThat(race("MATCH", () -> sql("UPDATE dh_asset_data_asset SET asset_name='renamed',view_count=11,"
                + "description='new description',update_time=CURRENT_TIMESTAMP WHERE id=8"))).isInstanceOf(Long.class);
        assertThat(observed.get()).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_demand_match")).isEqualTo(1);
    }

    @Test
    void assetLockDatabaseFailureRollsBackChildAndSuppressesNotification() throws Exception {
        doThrow(new org.springframework.dao.CannotAcquireLockException("test asset lock failure"))
                .when(assets).selectForMatching( 8L);
        assertThatThrownBy(() -> contribute("MATCH", 8L)).isInstanceOf(org.springframework.dao.CannotAcquireLockException.class);
        assertNoContribution();
        assertThat(scalar("SELECT status FROM dh_market_demand WHERE id=1")).isEqualTo(1);
    }

    @Test
    void stableNullableAssetEvidenceDoesNotInventNewRequiredFields() throws Exception {
        sql("UPDATE dh_asset_data_asset SET asset_type=NULL,catalog_item_urn=NULL,meta_table_id=NULL,resource_urn=NULL,"
                + "published_snapshot_id=NULL,published_version=NULL,published_catalog_version_id=NULL,"
                + "datasource_id=NULL,db_name=NULL,schema_name=NULL,table_name=NULL,owner_dept_id=NULL,"
                + "tech_leader_user_id=NULL,business_leader_user_id=NULL,provider_dept_id=NULL,source_dept_id=NULL,"
                + "share_attr=NULL,share_attribute=NULL,share_condition=NULL,share_deny_reason=NULL,"
                + "tech_available=NULL,catalog_revision=NULL WHERE id=8");
        assertThat(contribute("MATCH", 8L)).isPositive();
        verify(notifications).sendSingleMessageToAdmin(any());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 2, 3})
    void matchingStillUsesOriginalPublishedPolicyAndDoesNotGrantAccess(int share) throws Exception {
        sql("UPDATE dh_asset_data_asset SET share_attr=" + share + ",share_attribute=" + share + ",tech_available=0 WHERE id=8");
        assertThat(contribute("MATCH", 8L)).isPositive();
        assertThat(scalar("SELECT count(*) FROM dh_market_demand_match WHERE status=0 AND apply_id IS NULL")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply")).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation")).isZero();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2, 3, 7})
    void concurrentSameAssetCreatesExactlyOneMatchAndReportsDuplicate(int status) throws Exception {
        sql("UPDATE dh_market_demand SET status=" + status + " WHERE id=1");
        var reads = new CountDownLatch(2);
        when(guard.requireUserId()).thenAnswer(call -> {
            reads.countDown();
            if (!reads.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Duplicate read wait timeout");
            return 22L;
        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> contributeAsTenant("MATCH", 8L));
            var second = workers.submit(() -> contributeAsTenant("MATCH", 8L));
            var outcomes = java.util.List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(Long.class::isInstance).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(ServiceException.class::isInstance)).singleElement()
                    .satisfies(result -> assertThat(((ServiceException) result).getCode())
                            .isEqualTo(MARKET_DEMAND_MATCH_DUPLICATE.getCode()));
        }
        assertThat(scalar("SELECT count(*) FROM dh_market_demand_match WHERE demand_id=1 AND asset_id=8 AND deleted=0"))
                .isEqualTo(1);
        assertThat(scalar("SELECT status FROM dh_market_demand WHERE id=1")).isEqualTo(status == 1 ? 2 : status);
        verify(notifications).sendSingleMessageToAdmin(any());
    }

    private void afterInsert(String mode, CheckedRunnable action) {
        org.mockito.stubbing.Answer<Object> answer = call -> {
            Object result = call.callRealMethod();
            assertThat(result).isEqualTo(1);
            action.run();
            return result;
        };
        if (mode.equals("REPLY")) doAnswer(answer).when(replies).insert(any(MarketDemandReplyDO.class));
        else doAnswer(answer).when(matches).insertIfAbsent(any(MarketDemandMatchDO.class));
    }

    @Test
    void waitingDuplicateCanSucceedIfTheFirstTransactionRollsBack() throws Exception {
        sql("UPDATE dh_market_demand SET status=2 WHERE id=1");
        var inserted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var attempts = new AtomicInteger();
        doAnswer(call -> {
            boolean first = attempts.incrementAndGet() == 1;
            Object result = call.callRealMethod();
            if (first) {
                inserted.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Insert wait timeout");
                throw new IllegalStateException("test first transaction rollback");
            }
            return result;
        }).when(matches).insertIfAbsent(any(MarketDemandMatchDO.class));
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> {
                try { return contributeAsTenant("MATCH", 8L); }
                catch (IllegalStateException error) { return error; }
            });
            try {
                assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
                var second = workers.submit(() -> contributeAsTenant("MATCH", 8L));
                awaitBlockedMatch(second);
                release.countDown();
                assertThat(first.get(10, TimeUnit.SECONDS)).isInstanceOf(IllegalStateException.class);
                assertThat(second.get(10, TimeUnit.SECONDS)).isInstanceOf(Long.class);
            } finally {
                release.countDown();
            }
        }
        assertThat(scalar("SELECT count(*) FROM dh_market_demand_match WHERE deleted=0")).isEqualTo(1);
        verify(notifications).sendSingleMessageToAdmin(any());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2})
    void unseenMatchCommittedByAnotherWriterIsReportedAsDuplicate(int status) throws Exception {
        sql("UPDATE dh_market_demand SET status=2 WHERE id=1");
        try (var workers = Executors.newSingleThreadExecutor(); var other = open()) {
            other.setAutoCommit(false);
            try (var statement = other.createStatement()) {
                statement.executeUpdate("INSERT INTO dh_market_demand_match"
                        + "(id,demand_id,asset_id,status,create_time,update_time)"
                        + " VALUES(999,1,8," + status + ",CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            }
            var result = workers.submit(() -> contributeAsTenant("MATCH", 8L));
            try { awaitBlockedMatch(result); }
            finally { other.commit(); }
            assertThat(result.get(10, TimeUnit.SECONDS)).isInstanceOfSatisfying(ServiceException.class,
                    error -> assertThat(error.getCode()).isEqualTo(MARKET_DEMAND_MATCH_DUPLICATE.getCode()));
        }
        assertThat(scalar("SELECT count(*) FROM dh_market_demand_match WHERE id=999 AND status=" + status)).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_demand_match")).isEqualTo(1);
        verifyNoInteractions(notifications);
    }

    @Test
    void sequenceIdAndFrameworkAuditFillArePreserved() throws Exception {
        try (var security = mockStatic(cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.class)) {
            security.when(cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils::getLoginUserId).thenReturn(22L);
            long id = contribute("MATCH", 8L);
            assertThat(id).isEqualTo(scalar("SELECT last_value FROM dh_market_demand_match_seq"));
            assertThat(scalar("SELECT count(*) FROM dh_market_demand_match WHERE id=" + id
                    + " AND creator='22' AND updater='22' AND create_time IS NOT NULL AND update_time IS NOT NULL"
                    + " AND matcher_user_id=22 AND deleted=0 AND match_type=2 AND status=0"))
                    .isEqualTo(1);
        }
    }

    @Test
    void primaryKeyConflictIsNotMislabeledAsDuplicateDemandAsset() throws Exception {
        sql("INSERT INTO dh_market_demand_match(id,demand_id,asset_id,status,create_time,update_time)"
                + " VALUES(1,1,9,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        assertThatThrownBy(() -> contribute("MATCH", 8L)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(scalar("SELECT count(*) FROM dh_market_demand_match")).isEqualTo(1);
        assertThat(scalar("SELECT status FROM dh_market_demand WHERE id=1")).isEqualTo(1);
        verifyNoInteractions(notifications);
    }

    @Test
    void unrelatedCheckFailureIsNotMislabeledAsDuplicateDemandAsset() throws Exception {
        sql("ALTER TABLE dh_market_demand_match ADD CONSTRAINT test_reject_manual CHECK(match_type<>2)");
        assertThatThrownBy(() -> contribute("MATCH", 8L)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertNoContribution();
        assertThat(scalar("SELECT status FROM dh_market_demand WHERE id=1")).isEqualTo(1);
    }

    @Test
    void missingMigrationFailsClosedWithoutFallingBackToUnsafeInsert() throws Exception {
        sql("DROP INDEX uk_market_demand_match_active");
        assertThatThrownBy(() -> contribute("MATCH", 8L)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertNoContribution();
        assertThat(scalar("SELECT status FROM dh_market_demand WHERE id=1")).isEqualTo(1);
    }

    private void awaitBlockedMatch(java.util.concurrent.Future<?> result) throws Exception {
        awaitBlockedSql(result, "%INSERT INTO dh_market_demand_match%", "unique-index");
    }

    private void awaitBlockedSql(java.util.concurrent.Future<?> result, String queryPattern, String reason) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (scalar("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()"
                + " AND query LIKE '" + queryPattern + "'"
                + " AND cardinality(pg_blocking_pids(pid))>0") == 0) {
            assertThat(result.isDone()).as("must reach actual %s wait", reason).isFalse();
            assertThat(System.nanoTime()).as("%s wait timeout", reason).isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    private Object contributeAsTenant(String mode, long assetId) {
        TenantContextHolder.clear();
        try { return contribute(mode, assetId); }
        catch (ServiceException error) { return error; }
        finally { TenantContextHolder.clear(); }
    }

    private long contribute(String mode, long assetId) {
        if (mode.equals("REPLY")) {
            var request = new MarketDemandReplySaveReqVO();
            request.setDemandId(1L);
            request.setReplyType(2);
            request.setReplyDeptId(200L);
            request.setContent("test contribution");
            return service.replyDemand(request);
        }
        var request = new MarketDemandMatchSaveReqVO();
        request.setDemandId(1L);
        request.setAssetId(assetId);
        request.setMatchNote("test contribution");
        return service.matchDemand(request);
    }

    private void assertNoContribution() throws Exception {
        assertThat(scalar("SELECT count(*) FROM dh_market_demand_reply")).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_market_demand_match")).isZero();
        verifyNoInteractions(notifications);
    }

    private static String table(String mode) { return mode.equals("REPLY") ? "dh_market_demand_reply" : "dh_market_demand_match"; }

    private static void deny(Object result) {
        assertThat(result).isInstanceOfSatisfying(ServiceException.class,
                error -> assertThat(error.getCode()).isEqualTo(MARKET_DEMAND_STATUS_DENY.getCode()));
    }

    @FunctionalInterface interface CheckedRunnable { void run() throws Exception; }
}
