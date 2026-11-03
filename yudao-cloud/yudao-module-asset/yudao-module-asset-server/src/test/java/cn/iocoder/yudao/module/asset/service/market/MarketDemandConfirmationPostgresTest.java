package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDemandMatchConfirmReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDemandMatchDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.*;
import cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 PG/租户/资产及需求、匹配、申请、过程记录同事务；通知 RPC 为受控替身。 */
@Timeout(30)
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketDemandConfirmationPostgresTest extends SharingPostgresSupport {
    private MarketDemandService service;
    private MarketApplyServiceImpl applyTarget;
    private MarketDemandMatchMapper matches;
    private MarketResourceApplyMapper applications;
    private DataAssetMapper assets;
    private final MarketObjectGuard guard = mock(MarketObjectGuard.class);
    private final NotifyMessageSendApi notifications = mock(NotifyMessageSendApi.class);

    @BeforeEach
    void prepare() throws Exception {
        try (var input = getClass().getResourceAsStream("/sql/market-freeze-fixture.sql")) {
            sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        try (var input = getClass().getResourceAsStream("/sql/market-demand-asset-fixture.sql")) {
            if (input == null) throw new IllegalStateException("Missing test-only asset fixture");
            sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        sql("""
                ALTER TABLE dh_market_check_process ADD tenant_id bigint NOT NULL DEFAULT 1;
                ALTER TABLE dh_market_resource_apply ALTER COLUMN id SET DEFAULT nextval('dh_market_resource_apply_seq');
                CREATE TABLE dh_market_demand (
                  id bigint PRIMARY KEY, tenant_id bigint NOT NULL DEFAULT 1, demand_no varchar, title varchar,
                  demand_type int, description varchar, purpose varchar, expected_fields varchar,
                  expected_update_freq varchar, expected_delivery int, expect_finish_time timestamp,
                  urgency int, apply_dept_id bigint, apply_user_id bigint, phone varchar, email varchar,
                  status int NOT NULL, publish_time timestamp, close_reason varchar,
                  acceptance_status int, accepted_apply_id bigint, accepted_time timestamp,
                  remark varchar, creator varchar, updater varchar, create_time timestamp,
                  update_time timestamp, deleted smallint NOT NULL DEFAULT 0);
                INSERT INTO dh_market_demand(id,apply_user_id,apply_dept_id,status,acceptance_status,
                    title,purpose,expected_fields) VALUES(1,11,100,2,0,'原需求','统计','id'),(2,11,100,2,0,'其他租户','统计','id');
                CREATE TABLE dh_market_demand_match (
                  id bigint PRIMARY KEY, tenant_id bigint NOT NULL DEFAULT 1, demand_id bigint, asset_id bigint,
                  match_type int, match_score numeric, match_note varchar, status int, apply_id bigint,
                  matcher_user_id bigint, confirm_user_id bigint, confirm_time timestamp, confirm_note varchar,
                  creator varchar, updater varchar, create_time timestamp, update_time timestamp,
                  deleted smallint NOT NULL DEFAULT 0);
                INSERT INTO dh_market_demand_match(id,demand_id,asset_id,status,matcher_user_id)
                  VALUES(5,1,8,0,22),(6,2,8,0,22);
                """);
        migrate("0042_asset_grant-source-delivery.sql");
        migrate("0047_asset_renewal-request-evidence.sql");
        migrate("0050_asset_market-active-application-uniqueness.sql");
        var source = new UnpooledDataSource("org.postgresql.Driver",
                jdbcUrl + "?currentSchema=datahub-center&socketTimeout=10", "sharing_test", "");
        var config = singleTenantConfiguration();
        var global = new GlobalConfig().setMetaObjectHandler(new DefaultDBFieldHandler());
        global.setDbConfig(new GlobalConfig.DbConfig().setIdType(IdType.INPUT)
                .setKeyGenerators(java.util.List.of(new PostgreKeyGenerator())));
        GlobalConfigUtils.setGlobalConfig(config, global);
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("match-confirmation", new SpringManagedTransactionFactory(), source));
        var interceptor = new MybatisPlusInterceptor();

        config.addInterceptor(interceptor);
        config.addMapper(MarketDemandMapper.class);
        config.addMapper(MarketDemandMatchMapper.class);
        config.addMapper(MarketResourceApplyMapper.class);
        config.addMapper(MarketCheckProcessMapper.class);
        config.addMapper(DataAssetMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        var transactions = new DataSourceTransactionManager(source);
        var demands = session.getMapper(MarketDemandMapper.class);
        matches = spy(session.getMapper(MarketDemandMatchMapper.class));
        assets = spy(session.getMapper(DataAssetMapper.class));
        applications = spy(session.getMapper(MarketResourceApplyMapper.class));
        applyTarget = spy(new MarketApplyServiceImpl());
        ReflectionTestUtils.setField(applyTarget, "applyMapper", applications);
        ReflectionTestUtils.setField(applyTarget, "processMapper", session.getMapper(MarketCheckProcessMapper.class));
        ReflectionTestUtils.setField(applyTarget, "demandMapper", demands);
        ReflectionTestUtils.setField(applyTarget, "assetMapper", assets);
        var catalog = mock(cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService.class);
        when(catalog.requireCompletePublishedAsset(any())).thenAnswer(call -> call.getArgument(0));
        ReflectionTestUtils.setField(applyTarget, "catalogService", catalog);
        ReflectionTestUtils.setField(applyTarget, "displayHelper", new MarketDisplayHelper());
        var applyProxy = new ProxyFactory(applyTarget);
        applyProxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        var applyService = (MarketApplyService) applyProxy.getProxy();
        var notify = new MarketNotifyHelper();
        ReflectionTestUtils.setField(notify, "notifyMessageSendApi", notifications);
        var target = new MarketDemandServiceImpl();
        ReflectionTestUtils.setField(target, "demandMapper", demands);
        ReflectionTestUtils.setField(target, "matchMapper", matches);
        ReflectionTestUtils.setField(target, "assetMapper", assets);
        ReflectionTestUtils.setField(target, "applyService", applyService);
        ReflectionTestUtils.setField(target, "objectGuard", guard);
        ReflectionTestUtils.setField(target, "notifyHelper", notify);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (MarketDemandService) proxy.getProxy();
        when(guard.requireUserId()).thenReturn(11L);
        TenantContextHolder.clear();
    }

    @AfterEach
    void clearTenant() { TenantContextHolder.clear(); }

    static Stream<Arguments> changedDemand() {
        return Stream.of("CREATE", "ACCEPT", "REJECT").flatMap(mode -> Stream.of(
                "status=4", "status=5", "status=6", "apply_user_id=12", "apply_dept_id=101",
                "title='新需求'", "purpose='其他用途'", "expected_fields='secret'", "acceptance_status=1",
                "deleted=1").map(change -> Arguments.of(mode, change)));
    }

    @ParameterizedTest
    @MethodSource("changedDemand")
    void changedDemandAfterOwnerCheckRollsBackEveryConfirmationMode(String mode, String change) throws Exception {
        deny(race(mode, () -> sql("UPDATE dh_market_demand SET " + change + " WHERE id=1")));
        assertNoConfirmation();
    }

    @ParameterizedTest
    @ValueSource(strings = {"demand_id=99", "asset_id=99", "apply_id=99", "matcher_user_id=33"})
    void changedMatchBindingAfterOwnerCheckCannotConfirmAnOldAssociation(String change) throws Exception {
        deny(race("CREATE", () -> sql("UPDATE dh_market_demand_match SET " + change + " WHERE id=5")));
        assertThat(scalar("SELECT status FROM dh_market_demand_match WHERE id=5")).isZero();
        assertNoDraftOrNotification();
    }

    @Test
    void demandChangeAfterRealDraftAndProcessInsertRollsThemBack() throws Exception {
        doAnswer(call -> {
            var draft = call.callRealMethod();
            sql("UPDATE dh_market_demand SET status=4 WHERE id=1");
            return draft;
        }).when(applyTarget).createApplyFromDemand(any(), any(), any(), any(), any());
        assertThatThrownBy(() -> service.confirmMatch(request("CREATE"))).isInstanceOf(ServiceException.class);
        assertNoConfirmation();
        assertThat(scalar("SELECT status FROM dh_market_demand WHERE id=1")).isEqualTo(4);
    }

    @Test
    void missingApplyBackfillCannotCommitAnUnlinkedDraft() throws Exception {
        var writes = new AtomicInteger();
        doAnswer(call -> writes.incrementAndGet() == 2 ? 0 : call.callRealMethod())
                .when(matches).update(isNull(), any(LambdaUpdateWrapper.class));
        assertThatThrownBy(() -> service.confirmMatch(request("CREATE"))).isInstanceOf(ServiceException.class);
        assertNoConfirmation();
    }

    static Stream<Arguments> stableModes() {
        return Stream.of("CREATE", "ACCEPT", "REJECT").flatMap(mode -> Stream.of(1, 2, 3, 7)
                .map(status -> Arguments.of(mode, status)));
    }

    @ParameterizedTest
    @MethodSource("stableModes")
    void validConfirmationPreservesExistingApplyingStateAndDraftSemantics(String mode, int oldStatus) throws Exception {
        sql("UPDATE dh_market_demand SET status=" + oldStatus + " WHERE id=1");
        service.confirmMatch(request(mode));
        int expected = mode.equals("CREATE") ? 7 : mode.equals("ACCEPT") && oldStatus < 3 ? 3 : oldStatus;
        assertThat(scalar("SELECT status FROM dh_market_demand WHERE id=1")).isEqualTo(expected);
        assertThat(scalar("SELECT status FROM dh_market_demand_match WHERE id=5"))
                .isEqualTo(mode.equals("REJECT") ? 2 : 1);
        if (mode.equals("CREATE")) {
            assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE apply_user_id=11 AND asset_id=8"
                    + " AND status=0 AND if_auth=0 AND purpose='统计' AND field_scope='id'")).isEqualTo(1);
            assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(1);
            assertThat(scalar("SELECT count(*) FROM dh_market_demand_match m JOIN dh_market_resource_apply a"
                    + " ON a.id=m.apply_id WHERE m.id=5 AND a.demand_id=1")).isEqualTo(1);
        } else {
            assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply")).isZero();
        }
        verify(notifications).sendSingleMessageToAdmin(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE", "ACCEPT", "REJECT"})
    void unchangedNullableSnapshotFieldsCanStillBeConfirmed(String mode) throws Exception {
        sql("UPDATE dh_market_demand SET apply_dept_id=NULL,title=NULL,purpose=NULL,"
                + "expected_fields=NULL,acceptance_status=NULL WHERE id=1");
        sql("UPDATE dh_market_demand_match SET matcher_user_id=NULL WHERE id=5");
        service.confirmMatch(request(mode));
        assertThat(scalar("SELECT status FROM dh_market_demand_match WHERE id=5"))
                .isEqualTo(mode.equals("REJECT") ? 2 : 1);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply"))
                .isEqualTo(mode.equals("CREATE") ? 1 : 0);
        verifyNoInteractions(notifications); // 历史记录没有匹配人，沿用不通知语义。
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE", "ACCEPT", "REJECT"})
    void physicallyDeletedDemandCannotLeaveConfirmedMatchOrDraft(String mode) throws Exception {
        deny(race(mode, () -> sql("DELETE FROM dh_market_demand WHERE id=1")));
        assertNoConfirmation();
    }

    @Test
    void notificationObservesCommittedDraftAndNoLongerHoldsMatchLock() throws Exception {
        var observed = new AtomicInteger();
        doAnswer(call -> {
            // 独立 JDBC 连接只可读到已提交结果，且应能立即写入原匹配行。
            assertThat(scalar("SELECT status FROM dh_market_demand WHERE id=1")).isEqualTo(7);
            assertThat(scalar("SELECT count(*) FROM dh_market_demand_match m JOIN dh_market_resource_apply a"
                    + " ON a.id=m.apply_id WHERE m.id=5 AND m.status=1 AND a.status=0")).isEqualTo(1);
            assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(1);
            sql("SET lock_timeout='100ms'");
            try {
                sql("UPDATE dh_market_demand_match SET match_note='observed after commit' WHERE id=5");
            } finally {
                sql("RESET lock_timeout");
            }
            observed.incrementAndGet();
            return null;
        }).when(notifications).sendSingleMessageToAdmin(any());
        service.confirmMatch(request("CREATE"));
        assertThat(observed.get()).isEqualTo(1);
    }

    @Test
    void confirmationTimeUsesDatabaseClockInsteadOfSkewedJvmTime() throws Exception {
        LocalDateTime before = databaseNow();
        LocalDateTime skewed = before.minusYears(1);
        try (var clock = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            clock.when(LocalDateTime::now).thenReturn(skewed);
            service.confirmMatch(request("ACCEPT"));
        }
        LocalDateTime after = databaseNow();
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT confirm_time FROM dh_market_demand_match WHERE id=5")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getObject(1, LocalDateTime.class)).isBetween(before, after);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE", "ACCEPT", "REJECT"})
    void demandChangedWhileCasWaitsForLockRollsBackAllLocalWrites(String mode) throws Exception {
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
            var outcome = worker.submit(() -> confirmAsOwner(mode));
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (scalar("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()"
                        + " AND " + blockerPid + "=ANY(pg_blocking_pids(pid))") == 0) {
                    assertThat(outcome.isDone()).as("must reach actual demand lock wait").isFalse();
                    assertThat(System.nanoTime()).as("demand lock wait timeout").isLessThan(deadline);
                    Thread.sleep(10);
                }
                try (var statement = blocker.createStatement()) {
                    statement.executeUpdate("UPDATE dh_market_demand SET status=4 WHERE id=1");
                }
            } finally {
                blocker.commit(); // 读取工作线程结果之前释放锁，失败路径也不挂起。
            }
            deny(outcome.get(10, TimeUnit.SECONDS));
        }
        assertNoConfirmation();
        assertThat(scalar("SELECT status FROM dh_market_demand WHERE id=1")).isEqualTo(4);
    }

    @Test
    void demandWriteFailureRollsBackMatchDraftAndProcessWithoutNotification() throws Exception {
        sql("ALTER TABLE dh_market_demand ADD CONSTRAINT test_demand_write_failure CHECK(status<>7)");
        assertThatThrownBy(() -> service.confirmMatch(request("CREATE"))).isInstanceOf(RuntimeException.class);
        assertNoConfirmation();
    }



    @Test
    void concurrentConfirmationsCommitOnlyOneDraftProcessAndNotification() throws Exception {
        var readers = new CountDownLatch(2);
        var calls = new AtomicInteger();
        when(guard.requireUserId()).thenAnswer(call -> {
            if (calls.incrementAndGet() <= 2) {
                readers.countDown();
                assertThat(readers.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return 11L;
        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> confirmAsOwner("CREATE"));
            var second = workers.submit(() -> confirmAsOwner("CREATE"));
            var outcomes = java.util.List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter("confirmed"::equals).count()).isEqualTo(1);
            deny(outcomes.stream().filter(result -> !"confirmed".equals(result)).findFirst().orElseThrow());
        }
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(1);
        verify(notifications).sendSingleMessageToAdmin(any());
    }

    private Object race(String mode, CheckedRunnable update) throws Exception {
        var read = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        var calls = new AtomicInteger();
        when(guard.requireUserId()).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) {
                read.countDown();
                if (!proceed.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Owner check wait timeout");
            }
            return 11L;
        });
        try (var worker = Executors.newSingleThreadExecutor()) {
            var outcome = worker.submit(() -> confirmAsOwner(mode));
            try {
                assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();
                update.run();
            } finally {
                proceed.countDown();
            }
            return outcome.get(15, TimeUnit.SECONDS);
        }
    }

    static Stream<Arguments> assetChanges() {
        return Stream.of("CREATE", "ACCEPT").flatMap(mode -> Stream.concat(
                Stream.of("status=0", "status=2", "status=NULL")
                        .map(change -> Arguments.of(mode, change, MARKET_APPLY_ASSET_NOT_PUBLISHED.getCode())),
                Stream.concat(Stream.of("deleted=1", "id=88")
                                .map(change -> Arguments.of(mode, change, DATA_ASSET_NOT_EXISTS.getCode())),
                        Stream.of("asset_type=2", "catalog_item_urn='other'", "meta_table_id=32", "resource_urn='other'",
                                "published_snapshot_id=42", "published_version='v2'", "published_catalog_version_id=52",
                                "datasource_id=62", "db_name='other'", "schema_name='other'", "table_name='other'",
                                "owner_dept_id=72", "tech_leader_user_id=83", "business_leader_user_id=84",
                                "provider_dept_id=93", "source_dept_id=94", "share_attr=3", "share_attribute=3",
                                "share_condition='other'", "share_deny_reason='other'", "tech_available=0", "catalog_revision=2")
                                .map(change -> Arguments.of(mode, change, MARKET_DEMAND_STATUS_DENY.getCode())))));
    }

    @ParameterizedTest
    @MethodSource("assetChanges")
    void acceptedAssetChangeAfterReadRollsBackConfirmationAndDraft(String mode, String change, int expected) throws Exception {
        afterAssetRead(() -> sql("UPDATE dh_asset_data_asset SET " + change + " WHERE id=8"));
        assertThatThrownBy(() -> service.confirmMatch(request(mode))).isInstanceOfSatisfying(ServiceException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(expected));
        assertNoConfirmation();
        assertThat(scalar("SELECT status FROM dh_market_demand WHERE id=1")).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE", "ACCEPT"})
    void acceptedAssetDeletionAfterReadCannotLeaveConfirmation(String mode) throws Exception {
        afterAssetRead(() -> sql("DELETE FROM dh_asset_data_asset WHERE id=8"));
        assertThatThrownBy(() -> service.confirmMatch(request(mode))).isInstanceOfSatisfying(ServiceException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(DATA_ASSET_NOT_EXISTS.getCode()));
        assertNoConfirmation();
    }

    @Test
    void assetChangeAfterActualDraftInsertRollsBackDraftProcessAndConfirmation() throws Exception {
        doAnswer(call -> {
            var draft = call.callRealMethod();
            sql("UPDATE dh_asset_data_asset SET table_name='other' WHERE id=8");
            return draft;
        }).when(applyTarget).createApplyFromDemand(any(), any(), any(), any(), any());
        assertThatThrownBy(() -> service.confirmMatch(request("CREATE"))).isInstanceOfSatisfying(ServiceException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(MARKET_DEMAND_STATUS_DENY.getCode()));
        assertNoConfirmation();
    }

    private void afterAssetRead(CheckedRunnable change) {
        var reads = new AtomicInteger();
        doAnswer(call -> {
            Object asset = call.callRealMethod();
            if (reads.incrementAndGet() == 1) change.run();
            return asset;
        }).when(assets).selectById(8L);
    }

    static Stream<Arguments> unavailableAssetAtEntry() {
        return Stream.of("CREATE", "ACCEPT").flatMap(mode -> Stream.of("status=0", "status=2", "status=NULL",
                "deleted=1").map(change -> Arguments.of(mode, change)));
    }

    @ParameterizedTest
    @MethodSource("unavailableAssetAtEntry")
    void acceptanceRequiresExistingPublishedAssetBeforeMatchWrite(String mode, String change) throws Exception {
        sql("UPDATE dh_asset_data_asset SET " + change + " WHERE id=8");
        int expected = change.startsWith("status") ? MARKET_APPLY_ASSET_NOT_PUBLISHED.getCode() : DATA_ASSET_NOT_EXISTS.getCode();
        assertThatThrownBy(() -> service.confirmMatch(request(mode))).isInstanceOfSatisfying(ServiceException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(expected));
        verify(matches, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(assets, never()).selectForMatching( anyLong());
        assertNoConfirmation();
    }

    @ParameterizedTest
    @ValueSource(strings = {"status=0", "status=2", "status=NULL", "deleted=1"})
    void rejectedMatchCanBeClosedEvenWhenAssetIsNoLongerAvailable(String change) throws Exception {
        sql("UPDATE dh_asset_data_asset SET " + change + " WHERE id=8");
        var req = request("REJECT");
        req.setCreateApply(true);
        service.confirmMatch(req);
        assertThat(scalar("SELECT status FROM dh_market_demand_match WHERE id=5")).isEqualTo(2);
        assertThat(scalar("SELECT status FROM dh_market_demand WHERE id=1")).isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply")).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isZero();
        verify(assets, never()).selectForMatching( anyLong());
        verify(notifications).sendSingleMessageToAdmin(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectionAlsoSupportsMissingAssetOrMissingLegacyAssetId(boolean nullId) throws Exception {
        // 标准平台表的 asset_id 为 NOT NULL；空关联仅模拟结构漂移下的防御性拒绝，不放宽迁移。
        if (nullId) sql("UPDATE dh_market_demand_match SET asset_id=NULL WHERE id=5");
        else sql("DELETE FROM dh_asset_data_asset WHERE id=8");
        service.confirmMatch(request("REJECT"));
        assertThat(scalar("SELECT status FROM dh_market_demand_match WHERE id=5")).isEqualTo(2);
        verify(assets, never()).selectForMatching( anyLong());
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply")).isZero();
        verify(notifications).sendSingleMessageToAdmin(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectionCannotOverwriteAnAssetAssociationChangedToOrFromNull(boolean initiallyNull) throws Exception {
        if (initiallyNull) sql("UPDATE dh_market_demand_match SET asset_id=NULL WHERE id=5");
        String changedId = initiallyNull ? "8" : "NULL";
        deny(race("REJECT", () -> sql("UPDATE dh_market_demand_match SET asset_id=" + changedId + " WHERE id=5")));
        assertNoConfirmation();
        assertThat(scalar("SELECT count(*) FROM dh_market_demand_match WHERE id=5 AND asset_id "
                + (initiallyNull ? "=8" : "IS NULL"))).isEqualTo(1);
        assertThat(scalar("SELECT status FROM dh_market_demand WHERE id=1")).isEqualTo(2);
        verify(assets, never()).selectForMatching( anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE", "ACCEPT"})
    void missingLegacyAssetIdStillCannotBeAccepted(String mode) throws Exception {
        sql("UPDATE dh_market_demand_match SET asset_id=NULL WHERE id=5");
        assertThatThrownBy(() -> service.confirmMatch(request(mode))).isInstanceOfSatisfying(ServiceException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(DATA_ASSET_NOT_EXISTS.getCode()));
        verify(matches, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        verifyNoInteractions(assets);
        assertNoConfirmation();
    }

    static Stream<Arguments> assetLockChanges() {
        return Stream.of("CREATE", "ACCEPT").flatMap(mode -> Stream.of("status=2", "table_name='other'", "deleted=1")
                .map(change -> Arguments.of(mode, change)));
    }

    @ParameterizedTest
    @MethodSource("assetLockChanges")
    void waitingForAssetLockRechecksChangedRowAndRollsBack(String mode, String change) throws Exception {
        try (var workers = Executors.newSingleThreadExecutor(); var blocker = open()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                statement.executeQuery("SELECT id FROM dh_asset_data_asset WHERE id=8 FOR UPDATE").close();
            }
            var result = workers.submit(() -> confirmAsOwner(mode));
            try {
                awaitBlockedSql(result, "%FROM dh_asset_data_asset%", "asset lock");
                try (var statement = blocker.createStatement()) {
                    statement.executeUpdate("UPDATE dh_asset_data_asset SET " + change + " WHERE id=8");
                }
            } finally { blocker.commit(); }
            int expected = change.startsWith("status") ? MARKET_APPLY_ASSET_NOT_PUBLISHED.getCode()
                    : change.startsWith("table_name") ? MARKET_DEMAND_STATUS_DENY.getCode() : DATA_ASSET_NOT_EXISTS.getCode();
            assertThat(result.get(10, TimeUnit.SECONDS)).isInstanceOfSatisfying(ServiceException.class,
                    failure -> assertThat(failure.getCode()).isEqualTo(expected));
        }
        assertNoConfirmation();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE", "ACCEPT"})
    void assetWriterWaitsForAcceptedMatchCommit(String mode) throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            Object current = call.callRealMethod();
            locked.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Asset lock wait timeout");
            return current;
        }).when(assets).selectForMatching( 8L);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var result = workers.submit(() -> confirmAsOwner(mode));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                var writer = workers.submit(() -> {
                    try (var other = open(); var statement = other.createStatement()) {
                        return statement.executeUpdate("UPDATE dh_asset_data_asset SET status=2 WHERE id=8");
                    }
                });
                awaitBlockedSql(writer, "%UPDATE dh_asset_data_asset%", "asset writer");
                release.countDown();
                assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo("confirmed");
                assertThat(writer.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            } finally { release.countDown(); }
        }
        assertThat(scalar("SELECT status FROM dh_market_demand_match WHERE id=5")).isEqualTo(1);
        assertThat(scalar("SELECT status FROM dh_asset_data_asset WHERE id=8")).isEqualTo(2);
        verify(notifications).sendSingleMessageToAdmin(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE", "ACCEPT"})
    void notificationUsesCurrentAssetNameAfterCommitAndLockRelease(String mode) throws Exception {
        var observed = new AtomicInteger();
        afterAssetRead(() -> sql("UPDATE dh_asset_data_asset SET asset_name='renamed',view_count=10,"
                + "description='new description',update_time=CURRENT_TIMESTAMP WHERE id=8"));
        doAnswer(call -> {
            var dto = (cn.iocoder.yudao.module.system.api.notify.dto.NotifySendSingleToUserReqDTO) call.getArgument(0);
            assertThat(dto.getTemplateParams()).containsEntry("assetName", "renamed");
            assertThat(scalar("SELECT status FROM dh_market_demand_match WHERE id=5")).isEqualTo(1);
            sql("SET lock_timeout='100ms'");
            sql("UPDATE dh_asset_data_asset SET view_count=11 WHERE id=8");
            observed.incrementAndGet();
            return null;
        }).when(notifications).sendSingleMessageToAdmin(any());
        service.confirmMatch(request(mode));
        assertThat(observed.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE", "ACCEPT"})
    void failureAfterAssetLockRollsBackAndReleasesAllRows(String mode) throws Exception {
        doAnswer(call -> {
            Object current = call.callRealMethod();
            sql("UPDATE dh_market_demand SET status=4 WHERE id=1");
            return current;
        }).when(assets).selectForMatching( 8L);
        assertThatThrownBy(() -> service.confirmMatch(request(mode))).isInstanceOfSatisfying(ServiceException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(MARKET_DEMAND_STATUS_DENY.getCode()));
        assertNoConfirmation();
        sql("SET lock_timeout='100ms'");
        sql("UPDATE dh_asset_data_asset SET status=2 WHERE id=8");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void existingApplicationReuseStillChecksAssetWithoutRewritingOldApplication(boolean changed) throws Exception {
        sql("INSERT INTO dh_market_resource_apply(id,revision,asset_id,asset_type,apply_user_id,status,if_auth,"
                + "demand_id,purpose,title) VALUES(90,1,8,1,11,1,0,99,'existing purpose','existing title')");
        if (changed) {
            doAnswer(call -> {
                Object existing = call.callRealMethod();
                sql("UPDATE dh_asset_data_asset SET published_version='v2' WHERE id=8");
                return existing;
            }).when(applyTarget).createApplyFromDemand(any(), any(), any(), any(), any());
            assertThatThrownBy(() -> service.confirmMatch(request("CREATE"))).isInstanceOfSatisfying(ServiceException.class,
                    failure -> assertThat(failure.getCode()).isEqualTo(MARKET_DEMAND_STATUS_DENY.getCode()));
            assertThat(scalar("SELECT count(*) FROM dh_market_demand_match WHERE id=5 AND status=0 AND apply_id IS NULL"))
                    .isEqualTo(1);
            assertThat(scalar("SELECT status FROM dh_market_demand WHERE id=1")).isEqualTo(2);
            verifyNoInteractions(notifications);
        } else {
            service.confirmMatch(request("CREATE"));
            assertThat(scalar("SELECT count(*) FROM dh_market_demand_match WHERE id=5 AND status=1 AND apply_id=90"))
                    .isEqualTo(1);
            verify(notifications).sendSingleMessageToAdmin(any());
        }
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=90 AND demand_id=99 AND status=1"
                + " AND purpose='existing purpose' AND title='existing title'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE", "ACCEPT"})
    void actualCommitFailureRollsBackEveryWriteAndDoesNotNotify(String mode) throws Exception {
        sql("""
                CREATE FUNCTION test_reject_confirmation_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  RAISE EXCEPTION 'test confirmation commit failure';
                  RETURN NULL;
                END $$;
                CREATE CONSTRAINT TRIGGER test_reject_confirmation AFTER UPDATE ON dh_market_demand_match
                  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION test_reject_confirmation_commit();
                """);
        assertThatThrownBy(() -> service.confirmMatch(request(mode))).isInstanceOf(RuntimeException.class);
        assertNoConfirmation();
        assertThat(scalar("SELECT status FROM dh_market_demand WHERE id=1")).isEqualTo(2);
        sql("SET lock_timeout='100ms'");
        sql("UPDATE dh_asset_data_asset SET status=2 WHERE id=8");
    }

    @ParameterizedTest
    @ValueSource(strings = {"status=2", "status=3", "status=4", "status=5", "status=6", "status=7",
            "status=99", "status=NULL", "asset_id=9", "apply_user_id=12", "deleted=1", "id=91"})
    void changedReusableApplicationCannotCommitOldAssociation(String change) throws Exception {
        seedReusableApplication();
        afterReusableApplicationRead(() -> sql("UPDATE dh_market_resource_apply SET " + change + " WHERE id=90"));
        assertThatThrownBy(() -> service.confirmMatch(request("CREATE"))).isInstanceOfSatisfying(ServiceException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(MARKET_APPLY_STATUS_DENY.getCode()));
        assertReuseNotConfirmed();
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply")).isEqualTo(1);
    }

    @Test
    void physicallyDeletedReusableApplicationCannotCommitOldAssociation() throws Exception {
        seedReusableApplication();
        afterReusableApplicationRead(() -> sql("DELETE FROM dh_market_resource_apply WHERE id=90"));
        assertThatThrownBy(() -> service.confirmMatch(request("CREATE"))).isInstanceOfSatisfying(ServiceException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(MARKET_APPLY_STATUS_DENY.getCode()));
        assertReuseNotConfirmed();
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply")).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 8})
    void reusableApplicationReturnsCurrentEligibleRowInsteadOfCachedCandidate(int status) throws Exception {
        seedReusableApplication();
        afterReusableApplicationRead(() -> sql("UPDATE dh_market_resource_apply SET status=" + status
                + ",revision=2,apply_dept_id=102,purpose='current purpose',title='current title',field_scope='name' WHERE id=90"));
        var returned = new AtomicReference<MarketResourceApplyDO>();
        doAnswer(call -> {
            var current = (MarketResourceApplyDO) call.callRealMethod();
            returned.set(current);
            return current;
        }).when(applyTarget).createApplyFromDemand(any(), any(), any(), any(), any());
        service.confirmMatch(request("CREATE"));
        assertThat(returned.get().getStatus()).isEqualTo(status);
        assertThat(returned.get().getRevision()).isEqualTo(2);
        assertThat(returned.get().getApplyDeptId()).isEqualTo(102L);
        assertThat(returned.get().getPurpose()).isEqualTo("current purpose");
        assertThat(returned.get().getFieldScope()).isEqualTo("name");
        assertThat(returned.get().getDemandId()).isEqualTo(99L); // 不重写原跨需求申请，也不扩大授权。
        assertThat(scalar("SELECT count(*) FROM dh_market_demand_match WHERE id=5 AND status=1 AND apply_id=90"))
                .isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isZero();
        verify(notifications).sendSingleMessageToAdmin(any());
    }

    private void seedReusableApplication() throws Exception {
        sql("INSERT INTO dh_market_resource_apply(id,revision,asset_id,asset_type,apply_user_id,status,if_auth,"
                + "demand_id,apply_dept_id,purpose,title) VALUES(90,1,8,1,11,1,0,99,100,'existing purpose','existing title')");
    }

    @ParameterizedTest
    @ValueSource(strings = {"dh_market_resource_apply", "dh_market_check_process"})
    void skippedDraftOrProcessInsertCannotCommitConfirmation(String table) throws Exception {
        sql("CREATE FUNCTION test_skip_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$");
        sql("CREATE TRIGGER test_skip_insert BEFORE INSERT ON " + table
                + " FOR EACH ROW EXECUTE FUNCTION test_skip_insert()");
        assertThatThrownBy(() -> service.confirmMatch(request("CREATE"))).isInstanceOfSatisfying(ServiceException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(MARKET_APPLY_STATUS_DENY.getCode()));
        assertNoConfirmation();
        assertThat(scalar("SELECT status FROM dh_market_demand WHERE id=1")).isEqualTo(2);
    }

    @Test
    void concurrentFirstDraftCreationReturnsDomainConflictWithoutOrphanedWrites() throws Exception {


        var emptyReads = new CountDownLatch(2);
        doAnswer(call -> {
            Object candidate = call.callRealMethod();
            assertThat(candidate).isNull();
            emptyReads.countDown();
            if (!emptyReads.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Empty candidate reads timed out");
            return null;
        }).when(applications).selectActiveByUserAndAsset(11L, 8L);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> confirmCreateOutcome(5L));
            var second = workers.submit(() -> confirmCreateOutcome(6L));
            var outcomes = java.util.List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter("confirmed"::equals).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(result -> !"confirmed".equals(result)).findFirst().orElseThrow())
                    .isInstanceOfSatisfying(ServiceException.class,
                            failure -> assertThat(failure.getCode()).isEqualTo(MARKET_APPLY_DUPLICATE.getCode()));
        }
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_demand WHERE status=7")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_demand WHERE status=2")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_demand_match WHERE status=0 AND apply_id IS NULL"
                + " AND confirm_time IS NULL AND confirm_user_id IS NULL")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_demand_match m JOIN dh_market_resource_apply a"
                + " ON m.apply_id=a.id AND m.demand_id=a.demand_id WHERE m.status=1")).isEqualTo(1);
        verify(notifications).sendSingleMessageToAdmin(any());
    }

    private Object confirmCreateOutcome(long matchId) {
        TenantContextHolder.clear();
        try {
            var req = request("CREATE");
            req.setId(matchId);
            service.confirmMatch(req);
            return "confirmed";
        } catch (RuntimeException failure) { return failure; }
        finally { TenantContextHolder.clear(); }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 8})
    void activeApplicationInsertedAfterEmptyReadIsReportedAsDuplicate(int status) throws Exception {
        afterReusableApplicationRead(() -> {
            seedReusableApplication();
            sql("UPDATE dh_market_resource_apply SET status=" + status + " WHERE id=90");
        });
        assertThatThrownBy(() -> service.confirmMatch(request("CREATE"))).isInstanceOfSatisfying(ServiceException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(MARKET_APPLY_DUPLICATE.getCode()));
        assertReuseNotConfirmed();
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=90 AND status=" + status
                + " AND demand_id=99 AND purpose='existing purpose'")).isEqualTo(1);
        verify(applications).insertInitialApplyIfAbsent(any());
        verify(applications).hasActivePendingApply( 11L, 8L);
        verify(applications, never()).selectOwnedForRenewal( anyLong(), anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"apply_user_id=12", "asset_id=9", "deleted=1",
            "status=2", "status=3", "status=4", "status=5", "status=6"})
    void unrelatedOrTerminalApplicationDoesNotBlockNewDraft(String change) throws Exception {
        seedReusableApplication();
        sql("UPDATE dh_market_resource_apply SET " + change + " WHERE id=90");
        service.confirmMatch(request("CREATE"));
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply")).isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM dh_market_demand_match m JOIN dh_market_resource_apply a"
                + " ON a.id=m.apply_id WHERE m.id=5 AND m.status=1 AND a.id<>90 AND a.demand_id=1"
                + " AND a.apply_user_id=11 AND a.asset_id=8 AND a.status=0")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(1);
        verify(applications, never()).hasActivePendingApply( anyLong(), anyLong());
        verify(notifications).sendSingleMessageToAdmin(any());
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 9_007_199_254_740_993L})
    void demandDraftInsertPreservesGeneratedIdAndComponentAuditFill(long id) throws Exception {
        sql("SELECT setval('dh_market_resource_apply_seq'," + id + ",false)");
        try (var security = mockStatic(cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.class)) {
            security.when(cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils::getLoginUserId)
                    .thenReturn(11L);
            service.confirmMatch(request("CREATE"));
        }
        assertThat(scalar("SELECT apply_id FROM dh_market_demand_match WHERE id=5")).isEqualTo(id);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=" + id
                + " AND apply_no LIKE 'APP%' AND title='需求对接申请：test asset'"
                + " AND asset_id=8 AND asset_type=1 AND demand_id=1 AND apply_dept_id=100 AND apply_user_id=11"
                + " AND purpose='统计' AND field_scope='id' AND duty_matter='原需求' AND legal_basis='统计'"
                + " AND status=0 AND if_auth=0 AND apply_use_type=0 AND revision=1 AND paused=0 AND delivery_status=0"
                + " AND deleted=0 AND creator='11' AND updater='11'"
                + " AND create_time IS NOT NULL AND update_time IS NOT NULL")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process WHERE resource_apply_id=" + id
                + " AND creator='11' AND updater='11' AND create_time IS NOT NULL"
                + " AND action='SUBMIT' AND from_status IS NULL AND to_status=0 AND operator_user_id=11")).isEqualTo(1);
        verify(applications, never()).insert(any(MarketResourceApplyDO.class));
        verify(notifications).sendSingleMessageToAdmin(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRIMARY_KEY", "NOT_NULL", "APPLY_CHECK", "PROCESS_CHECK"})
    void otherConstraintFailuresAreNotMisclassifiedAsActiveApplicationDuplicates(String failure) throws Exception {
        switch (failure) {
            case "PRIMARY_KEY" -> {
                seedReusableApplication();
                sql("UPDATE dh_market_resource_apply SET id=1,status=4 WHERE id=90");
            }
            case "NOT_NULL" -> {
                sql("ALTER TABLE dh_market_resource_apply ALTER COLUMN title SET NOT NULL");
                sql("CREATE FUNCTION test_clear_title() RETURNS trigger LANGUAGE plpgsql AS $$"
                        + " BEGIN NEW.title=NULL; RETURN NEW; END $$");
                sql("CREATE TRIGGER test_clear_title BEFORE INSERT ON dh_market_resource_apply"
                        + " FOR EACH ROW EXECUTE FUNCTION test_clear_title()");
            }
            case "APPLY_CHECK" -> sql("ALTER TABLE dh_market_resource_apply ADD CONSTRAINT test_apply_failure CHECK(status<>0)");
            case "PROCESS_CHECK" -> sql("ALTER TABLE dh_market_check_process ADD CONSTRAINT test_process_failure CHECK(to_status<>0)");
            default -> throw new AssertionError(failure);
        }
        assertThatThrownBy(() -> service.confirmMatch(request("CREATE")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .isNotInstanceOf(ServiceException.class);
        assertThat(scalar("SELECT count(*) FROM dh_market_demand_match WHERE id=5 AND status=0 AND apply_id IS NULL"
                + " AND confirm_time IS NULL")).isEqualTo(1);
        assertThat(scalar("SELECT status FROM dh_market_demand WHERE id=1")).isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply")).isEqualTo(failure.equals("PRIMARY_KEY") ? 1 : 0);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isZero();
        verify(applications, never()).hasActivePendingApply( anyLong(), anyLong());
        verifyNoInteractions(notifications);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void waitingForFirstDraftUniqueConflictHandlesCompetitorRollbackOrCommit(boolean commit) throws Exception {
        try (var workers = Executors.newSingleThreadExecutor(); var competitor = open()) {
            competitor.setAutoCommit(false);
            try (var s = competitor.createStatement()) {
                s.executeUpdate("INSERT INTO dh_market_resource_apply(id,asset_id,apply_user_id,status,if_auth)"
                        + " VALUES(90,8,11,1,0)");
            }
            var confirmation = workers.submit(() -> confirmCreateOutcome(5L));
            try {
                awaitBlockedSql(confirmation, "%INSERT INTO dh_market_resource_apply%", "first draft unique conflict");
            } finally {
                if (commit) competitor.commit(); else competitor.rollback();
            }
            Object result = confirmation.get(12, TimeUnit.SECONDS);
            if (commit) {
                assertThat(result).isInstanceOfSatisfying(ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(MARKET_APPLY_DUPLICATE.getCode()));
                assertReuseNotConfirmed();
            } else {
                assertThat(result).isEqualTo("confirmed");
                assertThat(scalar("SELECT count(*) FROM dh_market_demand_match WHERE id=5 AND status=1 AND apply_id<>90"))
                        .isEqualTo(1);
                assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(1);
                verify(notifications).sendSingleMessageToAdmin(any());
            }
        }
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply")).isEqualTo(1);
    }

    @Test
    void missingActiveApplicationIndexFailsClosedInsteadOfFallingBackToPlainInsert() throws Exception {
        sql("DROP INDEX uk_market_active_pending_apply");
        assertThatThrownBy(() -> service.confirmMatch(request("CREATE")))
                .isInstanceOf(RuntimeException.class).isNotInstanceOf(ServiceException.class);
        assertNoConfirmation();
        verify(applications, never()).insert(any(MarketResourceApplyDO.class));
    }

    @Test
    void reusableApplicationWriterWaitsUntilOuterConfirmationCommits() throws Exception {
        seedReusableApplication();
        var reused = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            Object current = call.callRealMethod();
            reused.countDown();
            if (!release.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Reuse continuation timed out");
            return current;
        }).when(applyTarget).createApplyFromDemand(any(), any(), any(), any(), any());
        try (var workers = Executors.newFixedThreadPool(2)) {
            var confirmation = workers.submit(() -> confirmAsOwner("CREATE"));
            try {
                assertThat(reused.await(5, TimeUnit.SECONDS)).isTrue();
                var writer = workers.submit(() -> {
                    try (var c = open(); var s = c.createStatement()) {
                        s.execute("SET statement_timeout='8s'");
                        return s.executeUpdate("UPDATE dh_market_resource_apply SET status=4 WHERE id=90");
                    }
                });
                try {
                    awaitBlockedSql(writer, "%UPDATE dh_market_resource_apply%", "reused application writer");
                    assertThat(confirmation.isDone()).isFalse();
                } finally { release.countDown(); }
                assertThat(confirmation.get(12, TimeUnit.SECONDS)).isEqualTo("confirmed");
                assertThat(writer.get(12, TimeUnit.SECONDS)).isEqualTo(1);
            } finally { release.countDown(); }
        }
        assertThat(scalar("SELECT count(*) FROM dh_market_demand_match WHERE id=5 AND status=1 AND apply_id=90"))
                .isEqualTo(1);
        assertThat(scalar("SELECT status FROM dh_market_resource_apply WHERE id=90")).isEqualTo(4);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isZero();
        verify(notifications).sendSingleMessageToAdmin(any());
    }

    private void afterReusableApplicationRead(CheckedRunnable change) {
        var reads = new AtomicInteger();
        doAnswer(call -> {
            Object candidate = call.callRealMethod();
            if (reads.incrementAndGet() == 1) change.run();
            return candidate;
        }).when(applications).selectActiveByUserAndAsset(11L, 8L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status=4", "apply_user_id=12", "asset_id=9", "deleted=1", "id=91", "DELETE"})
    void waitingForReusableApplicationLockRechecksItsCurrentEligibility(String change) throws Exception {
        seedReusableApplication();
        try (var workers = Executors.newSingleThreadExecutor(); var blocker = open()) {
            blocker.setAutoCommit(false);
            try (var s = blocker.createStatement()) {
                s.executeQuery("SELECT id FROM dh_market_resource_apply WHERE id=90 FOR UPDATE").close();
            }
            var confirmation = workers.submit(() -> confirmAsOwner("CREATE"));
            try {
                awaitBlockedSql(confirmation, "%FROM dh_market_resource_apply%FOR UPDATE%", "reuse qualification");
                try (var s = blocker.createStatement()) {
                    s.executeUpdate(change.equals("DELETE") ? "DELETE FROM dh_market_resource_apply WHERE id=90"
                            : "UPDATE dh_market_resource_apply SET " + change + " WHERE id=90");
                }
            } finally { blocker.commit(); }
            assertThat(confirmation.get(12, TimeUnit.SECONDS)).isInstanceOfSatisfying(ServiceException.class,
                    failure -> assertThat(failure.getCode()).isEqualTo(MARKET_APPLY_STATUS_DENY.getCode()));
        }
        assertReuseNotConfirmed();
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply")).isEqualTo(change.equals("DELETE") ? 0 : 1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 8})
    void waitingForReusableApplicationLockAllowsCurrentEligibleState(int status) throws Exception {
        seedReusableApplication();
        try (var workers = Executors.newSingleThreadExecutor(); var blocker = open()) {
            blocker.setAutoCommit(false);
            try (var s = blocker.createStatement()) {
                s.executeQuery("SELECT id FROM dh_market_resource_apply WHERE id=90 FOR UPDATE").close();
            }
            var confirmation = workers.submit(() -> confirmAsOwner("CREATE"));
            try {
                awaitBlockedSql(confirmation, "%FROM dh_market_resource_apply%FOR UPDATE%", "eligible reuse");
                try (var s = blocker.createStatement()) {
                    s.executeUpdate("UPDATE dh_market_resource_apply SET status=" + status + ",revision=2 WHERE id=90");
                }
            } finally { blocker.commit(); }
            assertThat(confirmation.get(12, TimeUnit.SECONDS)).isEqualTo("confirmed");
        }
        assertThat(scalar("SELECT count(*) FROM dh_market_demand_match WHERE id=5 AND status=1 AND apply_id=90"))
                .isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=90 AND status=" + status + " AND revision=2"))
                .isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isZero();
        verify(notifications).sendSingleMessageToAdmin(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEMAND", "ASSET", "COMMIT"})
    void outerConfirmationFailureReleasesReusableApplicationLockWithoutChangingApplication(String stage) throws Exception {
        seedReusableApplication();
        if (stage.equals("COMMIT")) {
            sql("""
                    CREATE FUNCTION test_fail_reuse_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                      RAISE EXCEPTION 'test reuse commit failure';
                      RETURN NULL;
                    END $$;
                    CREATE CONSTRAINT TRIGGER test_fail_reuse AFTER UPDATE ON dh_market_demand_match
                      DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION test_fail_reuse_commit();
                    """);
        } else {
            doAnswer(call -> {
                Object current = call.callRealMethod();
                sql(stage.equals("DEMAND") ? "UPDATE dh_market_demand SET status=4 WHERE id=1"
                        : "UPDATE dh_asset_data_asset SET published_version='v2' WHERE id=8");
                return current;
            }).when(applyTarget).createApplyFromDemand(any(), any(), any(), any(), any());
        }
        assertThatThrownBy(() -> service.confirmMatch(request("CREATE"))).isInstanceOf(RuntimeException.class);
        assertThat(scalar("SELECT count(*) FROM dh_market_demand_match WHERE id=5 AND status=0 AND apply_id IS NULL"))
                .isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=90 AND status=1 AND revision=1"
                + " AND purpose='existing purpose' AND demand_id=99")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isZero();
        verifyNoInteractions(notifications);
        sql("SET lock_timeout='100ms'");
        try { sql("UPDATE dh_market_resource_apply SET remark='lock released' WHERE id=90"); }
        finally { sql("RESET lock_timeout"); }
    }

    @Test
    void differentDemandsCanSerializeReuseOfOneApplicationWithoutCreatingAnother() throws Exception {
        seedReusableApplication();


        var reused = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        doAnswer(call -> {
            Object current = call.callRealMethod();
            if (calls.incrementAndGet() == 1) {
                reused.countDown();
                if (!release.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("First reuse continuation timed out");
            }
            return current;
        }).when(applyTarget).createApplyFromDemand(any(), any(), any(), any(), any());
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> confirmAsOwner("CREATE"));
            try {
                assertThat(reused.await(5, TimeUnit.SECONDS)).isTrue();
                var second = workers.submit(() -> {
                    TenantContextHolder.clear();
                    try {
                        var req = request("CREATE");
                        req.setId(6L);
                        service.confirmMatch(req);
                        return "confirmed";
                    } finally { TenantContextHolder.clear(); }
                });
                try { awaitBlockedSql(second, "%FROM dh_market_resource_apply%FOR UPDATE%", "second demand reuse"); }
                finally { release.countDown(); }
                assertThat(first.get(12, TimeUnit.SECONDS)).isEqualTo("confirmed");
                assertThat(second.get(12, TimeUnit.SECONDS)).isEqualTo("confirmed");
            } finally { release.countDown(); }
        }
        assertThat(scalar("SELECT count(*) FROM dh_market_demand_match WHERE status=1 AND apply_id=90")).isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM dh_market_demand WHERE status=7")).isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isZero();
        verify(notifications, times(2)).sendSingleMessageToAdmin(any());
    }

    private void assertReuseNotConfirmed() throws Exception {
        assertThat(scalar("SELECT count(*) FROM dh_market_demand_match WHERE id=5 AND status=0"
                + " AND apply_id IS NULL AND confirm_user_id IS NULL AND confirm_time IS NULL")).isEqualTo(1);
        assertThat(scalar("SELECT status FROM dh_market_demand WHERE id=1")).isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isZero();
        verify(applications, never()).insert(any(MarketResourceApplyDO.class));
        verifyNoInteractions(notifications);
    }

    @Test
    void acceptingDifferentMatchesCanShareTheSameAssetLock() throws Exception {


        var readers = new CountDownLatch(2);
        doAnswer(call -> {
            Object current = call.callRealMethod();
            readers.countDown();
            if (!readers.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Shared confirmations were serialized");
            return current;
        }).when(assets).selectForMatching( 8L);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> confirmAsOwner("ACCEPT"));
            var second = workers.submit(() -> {
                TenantContextHolder.clear();
                try {
                    var req = request("ACCEPT");
                    req.setId(6L);
                    service.confirmMatch(req);
                    return "confirmed";
                } finally { TenantContextHolder.clear(); }
            });
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo("confirmed");
            assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo("confirmed");
        }
        assertThat(scalar("SELECT count(*) FROM dh_market_demand_match WHERE status=1")).isEqualTo(2);
        verify(notifications, times(2)).sendSingleMessageToAdmin(any());
    }

    private void awaitBlockedSql(java.util.concurrent.Future<?> result, String queryPattern, String reason) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (scalar("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()"
                + " AND query LIKE '" + queryPattern + "' AND cardinality(pg_blocking_pids(pid))>0") == 0) {
            assertThat(result.isDone()).as("must reach actual %s wait", reason).isFalse();
            assertThat(System.nanoTime()).as("%s wait timeout", reason).isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    private Object confirmAsOwner(String mode) {
        TenantContextHolder.clear();
        try { service.confirmMatch(request(mode)); return "confirmed"; }
        catch (ServiceException failure) { return failure; }
        finally { TenantContextHolder.clear(); }
    }

    private static MarketDemandMatchConfirmReqVO request(String mode) {
        var request = new MarketDemandMatchConfirmReqVO();
        request.setId(5L);
        request.setStatus(mode.equals("REJECT") ? MarketDemandMatchDO.STATUS_REJECTED : MarketDemandMatchDO.STATUS_ACCEPTED);
        request.setCreateApply(mode.equals("CREATE"));
        request.setConfirmNote("核实匹配");
        return request;
    }

    private static void deny(Object result) {
        assertThat(result).isInstanceOfSatisfying(ServiceException.class,
                error -> assertThat(error.getCode()).isEqualTo(MARKET_DEMAND_STATUS_DENY.getCode()));
    }

    private LocalDateTime databaseNow() throws Exception {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT clock_timestamp() AT TIME ZONE 'Asia/Shanghai'")) {
            result.next();
            return result.getObject(1, LocalDateTime.class);
        }
    }

    private void assertNoConfirmation() throws Exception {
        assertThat(scalar("SELECT count(*) FROM dh_market_demand_match WHERE id=5 AND status=0"
                + " AND apply_id IS NULL AND confirm_user_id IS NULL AND confirm_time IS NULL")).isEqualTo(1);
        assertNoDraftOrNotification();
    }

    private void assertNoDraftOrNotification() throws Exception {
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply")).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isZero();
        verifyNoInteractions(notifications);
    }

    @FunctionalInterface interface CheckedRunnable { void run() throws Exception; }
}
