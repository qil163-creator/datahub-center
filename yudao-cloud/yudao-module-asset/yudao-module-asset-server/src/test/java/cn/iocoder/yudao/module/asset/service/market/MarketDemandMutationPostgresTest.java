package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDemandCloseReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDemandSaveReqVO;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDemandMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 单租户 Mapper、独立 JDBC 连接与行锁；权限远端用屏障替身控制竞争时序。 */
@Timeout(30)
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketDemandMutationPostgresTest extends SharingPostgresSupport {
    private final MarketDemandServiceImpl service = new MarketDemandServiceImpl();
    private final MarketObjectGuard guard = mock(MarketObjectGuard.class);

    @BeforeEach
    void prepare() throws Exception {
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
                INSERT INTO dh_market_demand(id,demand_no,title,apply_user_id,apply_dept_id,
                    status,acceptance_status,purpose,expected_fields,publish_time)
                  VALUES(1,'DEM-original','original',11,100,2,0,'statistics','id','2020-01-01'),
                        (2,'DEM-other','other demand',11,100,2,0,'statistics','id','2020-01-01');
                """);
        var source = new UnpooledDataSource("org.postgresql.Driver",
                jdbcUrl + "?currentSchema=datahub-center&socketTimeout=10", "sharing_test", "");
        var configuration = singleTenantConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("demand-mutation", new SpringManagedTransactionFactory(), source));
        var plugins = new MybatisPlusInterceptor();

        configuration.addInterceptor(plugins);
        configuration.addMapper(MarketDemandMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        ReflectionTestUtils.setField(service, "demandMapper", session.getMapper(MarketDemandMapper.class));
        ReflectionTestUtils.setField(service, "objectGuard", guard);
        when(guard.requireUserId()).thenReturn(11L);
        when(guard.hasPermission(MarketObjectGuard.PERM_DEMAND_MATCH)).thenReturn(true);
        TenantContextHolder.clear();
    }

    @AfterEach
    void clearTenant() { TenantContextHolder.clear(); }

    static Stream<Arguments> races() {
        return modes().flatMap(mode -> Stream.of("status=7", "apply_user_id=33", "apply_dept_id=101",
                "acceptance_status=1", "accepted_apply_id=9", "accepted_time='2021-01-01'",
                "deleted=1").map(change -> Arguments.of(mode, change)));
    }

    @ParameterizedTest
    @MethodSource("races")
    void changedLifecycleOrOwnershipCannotBeOverwritten(String mode, String change) throws Exception {
        initial(mode);
        deny(race(mode, "UPDATE dh_market_demand SET " + change + " WHERE id=1"));
        assertThat(scalar("SELECT count(*) FROM dh_market_demand WHERE id=1 AND title='original'"
                + " AND close_reason IS NULL AND publish_time='2020-01-01' AND deleted="
                + (change.equals("deleted=1") ? 1 : 0))).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_demand WHERE id=1 AND " + change)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 5, 6})
    void lateEditorCannotReopenMatchedOrTerminalDemand(int status) throws Exception {
        deny(race("UPDATE", "UPDATE dh_market_demand SET status=" + status + " WHERE id=1"));
        assertThat(scalar("SELECT status FROM dh_market_demand WHERE id=1")).isEqualTo(status);
        assertThat(scalar("SELECT count(*) FROM dh_market_demand WHERE title='original' AND id=1")).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("modes")
    void physicallyDeletedDemandIsNeverReportedAsSuccessfullyMutated(String mode) throws Exception {
        initial(mode);
        deny(race(mode, "DELETE FROM dh_market_demand WHERE id=1"));
        assertThat(scalar("SELECT count(*) FROM dh_market_demand WHERE id=1")).isZero();
    }

    @ParameterizedTest
    @MethodSource("modes")
    void unchangedNullableLifecycleFieldsRemainSupported(String mode) throws Exception {
        initial(mode);
        sql("UPDATE dh_market_demand SET apply_dept_id=NULL,acceptance_status=NULL,"
                + "accepted_apply_id=NULL,accepted_time=NULL WHERE id=1");
        assertThat(mutateAsOwner(mode)).isEqualTo("changed");
        assertResult(mode);
    }

    static Stream<Arguments> legalStates() {
        return modes().flatMap(mode -> {
            Stream<Integer> states = switch (mode) {
                case "UPDATE" -> Stream.of(0, 1, 2);
                case "DELETE" -> Stream.of(0, 5);
                case "PUBLISH" -> Stream.of(0);
                default -> Stream.of(0, 1, 2, 3, 7);
            };
            return states.map(state -> Arguments.of(mode, state));
        });
    }

    @ParameterizedTest
    @MethodSource("legalStates")
    void legalTransitionsPreserveCurrentPublicContract(String mode, int state) throws Exception {
        sql("UPDATE dh_market_demand SET status=" + state + " WHERE id=1");
        if (mode.equals("REJECT")) when(guard.requireUserId()).thenReturn(22L);
        assertThat(mutateAsOwner(mode)).isEqualTo("changed");
        assertResult(mode);
        if (mode.equals("UPDATE")) {
            assertThat(scalar("SELECT status FROM dh_market_demand WHERE id=1")).isEqualTo(state);
            assertThat(scalar("SELECT count(*) FROM dh_market_demand WHERE id=1 AND demand_no='DEM-original'"
                    + " AND apply_user_id=11 AND publish_time='2020-01-01' AND acceptance_status=0")).isEqualTo(1);
        }
    }



    @ParameterizedTest
    @MethodSource("modes")
    void updateWaitingForRowLockRechecksTheCommittedOwner(String mode) throws Exception {
        initial(mode);
        try (var worker = Executors.newSingleThreadExecutor(); var blocker = open()) {
            blocker.setAutoCommit(false);
            long pid;
            try (var statement = blocker.createStatement(); var result = statement.executeQuery("SELECT pg_backend_pid()")) {
                result.next();
                pid = result.getLong(1);
            }
            try (var statement = blocker.createStatement();
                 var result = statement.executeQuery("SELECT id FROM dh_market_demand WHERE id=1 FOR UPDATE")) {
                assertThat(result.next()).isTrue();
            }
            var outcome = worker.submit(() -> mutateAsOwner(mode));
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (scalar("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()"
                        + " AND " + pid + "=ANY(pg_blocking_pids(pid))") == 0) {
                    assertThat(outcome.isDone()).as("must observe actual row lock wait").isFalse();
                    assertThat(System.nanoTime()).as("demand lock wait timeout").isLessThan(deadline);
                    Thread.sleep(10);
                }
                try (var statement = blocker.createStatement()) {
                    statement.executeUpdate("UPDATE dh_market_demand SET apply_user_id=33 WHERE id=1");
                }
            } finally {
                blocker.commit();
            }
            deny(outcome.get(10, TimeUnit.SECONDS));
        }
        assertThat(scalar("SELECT count(*) FROM dh_market_demand WHERE id=1 AND apply_user_id=33"
                + " AND deleted=0 AND title='original' AND close_reason IS NULL")).isEqualTo(1);
    }

    @Test
    void publishTimeComesFromDatabaseEvenWhenJvmClockIsSkewed() throws Exception {
        initial("PUBLISH");
        LocalDateTime before = databaseNow();
        LocalDateTime skewed = before.minusYears(1);
        try (var clock = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            clock.when(LocalDateTime::now).thenReturn(skewed);
            service.publishDemand(1L);
        }
        LocalDateTime after = databaseNow();
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT publish_time FROM dh_market_demand WHERE id=1")) {
            result.next();
            assertThat(result.getObject(1, LocalDateTime.class)).isBetween(before, after);
        }
    }

    @Test
    void operatorCloseRechecksChangesDuringPermissionRpc() throws Exception {
        when(guard.requireUserId()).thenReturn(22L);
        when(guard.hasPermission(MarketObjectGuard.PERM_DEMAND_MATCH)).thenAnswer(call -> {
            sql("UPDATE dh_market_demand SET status=3,acceptance_status=1,accepted_apply_id=9 WHERE id=1");
            return true;
        });
        assertThatThrownBy(() -> mutate("CLOSE")).isInstanceOfSatisfying(ServiceException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(MARKET_DEMAND_STATUS_DENY.getCode()));
        assertThat(scalar("SELECT count(*) FROM dh_market_demand WHERE id=1 AND status=3"
                + " AND accepted_apply_id=9 AND close_reason IS NULL")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE", "DELETE"})
    void realPublishWinsOverAnAlreadyReadDraftMutation(String mode) throws Exception {
        sql("UPDATE dh_market_demand SET status=0 WHERE id=1");
        deny(race(mode, () -> service.publishDemand(1L)));
        assertThat(scalar("SELECT count(*) FROM dh_market_demand WHERE id=1 AND status=1"
                + " AND deleted=0 AND title='original' AND publish_time>'2020-01-01'")).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("modes")
    void failedDatabaseWriteLeavesOriginalStateUnchanged(String mode) throws Exception {
        initial(mode);
        String constraint = switch (mode) {
            case "UPDATE" -> "title<>'edited'";
            case "DELETE" -> "deleted=0";
            case "PUBLISH" -> "status<>1";
            case "CANCEL" -> "status<>5";
            case "REJECT" -> "status<>6";
            default -> "status<>4";
        };
        sql("ALTER TABLE dh_market_demand ADD CONSTRAINT test_write_failure CHECK(" + constraint + ")");
        assertThatThrownBy(() -> mutate(mode)).isInstanceOf(RuntimeException.class);
        assertThat(scalar("SELECT count(*) FROM dh_market_demand WHERE id=1 AND deleted=0"
                + " AND title='original' AND close_reason IS NULL AND publish_time='2020-01-01'"
                + " AND status=" + (mode.equals("DELETE") || mode.equals("PUBLISH") ? 0 : 2))).isEqualTo(1);
    }

    private Object race(String mode, String change) throws Exception {
        return race(mode, () -> sql(change));
    }

    private Object race(String mode, CheckedRunnable change) throws Exception {
        var read = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        var calls = new AtomicInteger();
        when(guard.requireUserId()).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) {
                read.countDown();
                if (!proceed.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Owner check wait timeout");
            }
            return mode.equals("REJECT") ? 22L : 11L;
        });
        try (var worker = Executors.newSingleThreadExecutor()) {
            var result = worker.submit(() -> mutateAsOwner(mode));
            try {
                assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();
                change.run();
            } finally {
                proceed.countDown();
            }
            return result.get(15, TimeUnit.SECONDS);
        }
    }

    private void initial(String mode) throws Exception {
        if (mode.equals("DELETE") || mode.equals("PUBLISH")) sql("UPDATE dh_market_demand SET status=0 WHERE id=1");
        if (mode.equals("REJECT")) when(guard.requireUserId()).thenReturn(22L);
    }

    private Object mutateAsOwner(String mode) {
        TenantContextHolder.clear();
        try { mutate(mode); return "changed"; }
        catch (ServiceException failure) { return failure; }
        finally { TenantContextHolder.clear(); }
    }

    private void mutate(String mode) {
        switch (mode) {
            case "UPDATE" -> {
                var request = new MarketDemandSaveReqVO();
                request.setId(1L);
                request.setTitle("edited");
                request.setDescription("edited description");
                request.setDemandType(1);
                request.setApplyDeptId(100L);
                service.updateDemand(request);
            }
            case "DELETE" -> service.deleteDemand(1L);
            case "PUBLISH" -> service.publishDemand(1L);
            default -> {
                var request = new MarketDemandCloseReqVO();
                request.setId(1L);
                request.setStatus(mode.equals("CANCEL") ? 5 : mode.equals("REJECT") ? 6 : 4);
                request.setCloseReason("test closure");
                service.closeDemand(request);
            }
        }
    }

    private void assertResult(String mode) throws Exception {
        String condition = switch (mode) {
            case "UPDATE" -> "title='edited'";
            case "DELETE" -> "deleted=1";
            case "PUBLISH" -> "status=1 AND publish_time IS NOT NULL";
            case "CANCEL" -> "status=5 AND close_reason='test closure'";
            case "REJECT" -> "status=6 AND close_reason='test closure'";
            default -> "status=4 AND close_reason='test closure'";
        };
        assertThat(scalar("SELECT count(*) FROM dh_market_demand WHERE id=1 AND " + condition)).isEqualTo(1);
    }

    private LocalDateTime databaseNow() throws Exception {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT clock_timestamp() AT TIME ZONE 'Asia/Shanghai'")) {
            result.next();
            return result.getObject(1, LocalDateTime.class);
        }
    }

    private static Stream<String> modes() { return Stream.of("UPDATE", "DELETE", "PUBLISH", "CLOSE", "CANCEL", "REJECT"); }

    @FunctionalInterface interface CheckedRunnable { void run() throws Exception; }

    private static void deny(Object result) {
        assertThat(result).isInstanceOfSatisfying(ServiceException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(MARKET_DEMAND_STATUS_DENY.getCode()));
    }
}
