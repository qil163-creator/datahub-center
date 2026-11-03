package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.db.TenantDatabaseInterceptor;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketCheckProcessMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.incrementer.PostgreKeyGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 撤销：真实租户插件、登录Guard、Mapper和Spring事务；故障只注入一次性测试库。 */
@Timeout(30)
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketApplyCancellationPostgresTest extends SharingPostgresSupport {
    private MarketApplyService service;
    private MarketResourceApplyMapper applications;
    private DataSourceTransactionManager transactions;
    private final MarketObjectGuard guard = spy(new MarketObjectGuard());
    private final MarketAuditRecorder audit = mock(MarketAuditRecorder.class);
    private final MarketNotifyHelper notifications = mock(MarketNotifyHelper.class);
    private final MarketDeliveryService delivery = mock(MarketDeliveryService.class);

    @BeforeEach
    void prepare() throws Exception {
        try (var input = getClass().getResourceAsStream("/sql/market-freeze-fixture.sql")) {
            if (input == null) throw new IllegalStateException("Missing market fixture");
            sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        sql("""
                ALTER TABLE dh_market_check_process ADD tenant_id bigint NOT NULL DEFAULT 1;
                ALTER TABLE dh_market_resource_apply ALTER COLUMN snapshot TYPE jsonb USING snapshot::jsonb;
                INSERT INTO dh_market_resource_apply
                  (id,asset_id,apply_user_id,status,if_auth,paused,revision,source_revision,
                   field_scope,title,snapshot,received_time,reply_deadline,delivery_deadline,
                   catalog_version_id,supplement_note,check_user_id,check_note)
                VALUES (7,8,11,1,0,0,3,5,'id','pending','{"previous":"snapshot"}',
                  '2020-01-01','2020-01-02','2020-01-03',39,'old note',81,'old opinion');
                """);
        var source = new UnpooledDataSource("org.postgresql.Driver",
                jdbcUrl + "?currentSchema=datahub-center&socketTimeout=10", "sharing_test", "");
        transactions = new DataSourceTransactionManager(source);
        var config = singleTenantConfiguration();
        var global = new GlobalConfig().setMetaObjectHandler(new DefaultDBFieldHandler());
        global.setDbConfig(new GlobalConfig.DbConfig().setIdType(IdType.INPUT)
                .setKeyGenerators(List.of(new PostgreKeyGenerator())));
        GlobalConfigUtils.setGlobalConfig(config, global);
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("apply-cancel", new SpringManagedTransactionFactory(), source));
        var interceptor = new MybatisPlusInterceptor();

        config.addInterceptor(interceptor);
        config.addMapper(MarketResourceApplyMapper.class);
        config.addMapper(MarketCheckProcessMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        applications = session.getMapper(MarketResourceApplyMapper.class);
        var target = new MarketApplyServiceImpl();
        ReflectionTestUtils.setField(target, "applyMapper", applications);
        ReflectionTestUtils.setField(target, "processMapper", session.getMapper(MarketCheckProcessMapper.class));
        ReflectionTestUtils.setField(target, "objectGuard", guard);
        ReflectionTestUtils.setField(target, "auditRecorder", audit);
        ReflectionTestUtils.setField(target, "notifyHelper", notifications);
        ReflectionTestUtils.setField(target, "deliveryService", delivery);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (MarketApplyService) proxy.getProxy();
        TenantContextHolder.clear();
        authenticate(11L);
    }

    @AfterEach
    void clearContextsAndCheckNoNewSideEffects() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
        // 申请人撤销新增的普通审计已在成功用例中精确核对，其余替身必须零交互。
        verifyNoInteractions(notifications, delivery);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void skippedProcessMustRollBackCancellation(int from) throws Exception {
        sql("UPDATE dh_market_resource_apply SET status=" + from + " WHERE id=7");
        String before = storedApplication();
        sql("""
                CREATE FUNCTION skip_cancel_process() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$;
                CREATE TRIGGER skip_cancel_process BEFORE INSERT ON dh_market_check_process
                FOR EACH ROW EXECUTE FUNCTION skip_cancel_process();
                """);
        deny(() -> service.cancelApply(7L), MARKET_APPLY_STATUS_DENY.getCode());
        assertUnchanged(before);
    }

    @ParameterizedTest
    @CsvSource({"0,NULL", "1,NULL", "0,1", "1,3", "0,2147483647", "1,2147483647"})
    void ownerCancelsOnceWithoutChangingContentOrRevision(int from, String revision) throws Exception {
        sql("UPDATE dh_market_resource_apply SET status=" + from + ",revision=" + revision + " WHERE id=7");
        String content = unchangedContent();
        service.cancelApply(7L);
        assertThat(applications.selectById(7L).getStatus()).isEqualTo(4);
        assertThat(unchangedContent()).isEqualTo(content);
        assertProcess(7L, 1L, 11L, from);
        String cancelled = storedApplication();
        deny(() -> service.cancelApply(7L), MARKET_APPLY_STATUS_DENY.getCode());
        assertThat(storedApplication()).isEqualTo(cancelled);
        assertProcess(7L, 1L, 11L, from);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CAS_ZERO", "PROCESS_CHECK", "PROCESS_PRIMARY_KEY", "COMMIT"})
    void writeOrCommitFailureRollsBackTheWholeRowAndProcess(String failure) throws Exception {
        String before = storedApplication();
        switch (failure) {
            case "CAS_ZERO" -> sql("""
                    CREATE FUNCTION skip_cancel_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$;
                    CREATE TRIGGER skip_cancel_update BEFORE UPDATE ON dh_market_resource_apply
                    FOR EACH ROW EXECUTE FUNCTION skip_cancel_update();
                    """);
            case "PROCESS_CHECK" -> sql("ALTER TABLE dh_market_check_process ADD CHECK(action<>'CANCEL')");
            case "PROCESS_PRIMARY_KEY" -> sql("INSERT INTO dh_market_check_process(id,resource_apply_id,action) VALUES(1,99,'SUBMIT')");
            case "COMMIT" -> sql("""
                    CREATE FUNCTION reject_cancel_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN RAISE EXCEPTION 'test cancellation commit failure'; END $$;
                    CREATE CONSTRAINT TRIGGER reject_cancel_commit AFTER UPDATE ON dh_market_resource_apply
                    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_cancel_commit();
                    """);
            default -> throw new IllegalArgumentException(failure);
        }
        if ("CAS_ZERO".equals(failure)) deny(() -> service.cancelApply(7L), MARKET_APPLY_STATUS_DENY.getCode());
        else assertThatThrownBy(() -> service.cancelApply(7L)).isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(ServiceException.class);
        assertThat(storedApplication()).isEqualTo(before);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process"))
                .isEqualTo("PROCESS_PRIMARY_KEY".equals(failure) ? 1 : 0);
    }

    @Test
    void outerRollbackRestoresCancellationAndProcess() throws Exception {
        String before = storedApplication();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            service.cancelApply(7L);
            assertThat(applications.selectById(7L).getStatus()).isEqualTo(4);
            assertThatCode(() -> {
                assertThat(storedApplication()).isEqualTo(before); // 独立连接尚未看到未提交撤销
                assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isZero();
            }).doesNotThrowAnyException();
            tx.setRollbackOnly();
        });
        assertUnchanged(before);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1, 2, 3, 4, 5, 6, 7, 8, 99})
    void invalidStateIsABusinessDenialNotANullPointer(Integer state) throws Exception {
        sql("UPDATE dh_market_resource_apply SET status=" + (state == null ? "NULL" : state) + " WHERE id=7");
        String before = storedApplication();
        deny(() -> service.cancelApply(7L), MARKET_APPLY_STATUS_DENY.getCode());
        assertUnchanged(before);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void invalidContentRevisionIsNotSilentlyRepaired(int revision) throws Exception {
        sql("UPDATE dh_market_resource_apply SET revision=" + revision + " WHERE id=7");
        String before = storedApplication();
        deny(() -> service.cancelApply(7L), MARKET_APPLY_STATUS_DENY.getCode());
        assertUnchanged(before);
    }



    @ParameterizedTest
    @ValueSource(strings = {"LOGIN", "OTHER_USER", "NULL_OWNER", "DELETED", "MISSING"})
    void realLoginGuardRejectsMissingIdentityOrAnotherApplicant(String context) throws Exception {
        switch (context) {
            case "LOGIN" -> SecurityContextHolder.clearContext();
            case "OTHER_USER" -> authenticate(81L); // 提供方/审核人不能代替申请人撤销
            case "NULL_OWNER" -> sql("UPDATE dh_market_resource_apply SET apply_user_id=NULL WHERE id=7");
            case "DELETED" -> sql("UPDATE dh_market_resource_apply SET deleted=1 WHERE id=7");
            case "MISSING" -> { }
            default -> throw new IllegalArgumentException(context);
        }
        String before = storedApplication();
        boolean absent = "DELETED".equals(context) || "MISSING".equals(context);
        deny(() -> service.cancelApply("MISSING".equals(context) ? 99L : 7L),
                (absent ? MARKET_APPLY_NOT_EXISTS : MARKET_APPLY_FORBIDDEN).getCode());
        assertUnchanged(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status=2", "revision=4", "asset_id=9", "apply_user_id=12", "deleted=1"})
    void changeAfterInitialReadCannotBeOverwritten(String change) throws Exception {
        var winner = new AtomicReference<String>();
        doAnswer(call -> {
            Long actor = (Long) call.callRealMethod();
            sql("UPDATE dh_market_resource_apply SET " + change + " WHERE id=7");
            winner.set(storedApplication());
            return actor;
        }).when(guard).requireUserId();
        deny(() -> service.cancelApply(7L), MARKET_APPLY_STATUS_DENY.getCode());
        assertUnchanged(winner.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void simultaneousCancellationsCommitExactlyOneProcess(int from) throws Exception {
        sql("UPDATE dh_market_resource_apply SET status=" + from + " WHERE id=7");
        var bothRead = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        doAnswer(call -> {
            Long actor = (Long) call.callRealMethod();
            if (calls.incrementAndGet() <= 2) {
                bothRead.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return actor;
        }).when(guard).requireUserId();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(this::cancelOutcome);
            var second = workers.submit(this::cancelOutcome);
            try { assertThat(bothRead.await(10, TimeUnit.SECONDS)).isTrue(); }
            finally { release.countDown(); }
            var outcomes = List.of(first.get(12, TimeUnit.SECONDS), second.get(12, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter("OK"::equals).count()).isEqualTo(1);
            var conflict = outcomes.stream().filter(ServiceException.class::isInstance).findFirst().orElseThrow();
            assertThat(((ServiceException) conflict).getCode()).isEqualTo(MARKET_APPLY_STATUS_DENY.getCode());
        }
        assertThat(applications.selectById(7L).getStatus()).isEqualTo(4);
        assertProcess(7L, 1L, 11L, from);
    }

    @ParameterizedTest
    @CsvSource({"NULL,1", "3,NULL"})
    void nullRevisionIsAnExactComparisonNotAWildcard(String beforeRevision, String winnerRevision) throws Exception {
        sql("UPDATE dh_market_resource_apply SET revision=" + beforeRevision + " WHERE id=7");
        var winner = new AtomicReference<String>();
        doAnswer(call -> {
            Long actor = (Long) call.callRealMethod();
            sql("UPDATE dh_market_resource_apply SET revision=" + winnerRevision + " WHERE id=7");
            winner.set(storedApplication());
            return actor;
        }).when(guard).requireUserId();
        deny(() -> service.cancelApply(7L), MARKET_APPLY_STATUS_DENY.getCode());
        assertUnchanged(winner.get());
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 9007199254740993L, Long.MAX_VALUE})
    void largeApplicantAndApplicationIdsRemainExact(long tenantId) throws Exception {
        long id = 9007199254740993L;
        long actor = Long.MAX_VALUE;
        sql("UPDATE dh_market_resource_apply SET id=" + id
                + ",apply_user_id=" + actor + " WHERE id=7");
        TenantContextHolder.clear();
        authenticate(actor);
        service.cancelApply(id);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=" + id
                + " AND status=4 AND revision=3")).isEqualTo(1);
        assertProcess(id, tenantId, actor, 1);
    }

    private Object cancelOutcome() {
        TenantContextHolder.clear();
        authenticate(11L);
        try { service.cancelApply(7L); return "OK"; }
        catch (RuntimeException ex) { return ex; }
        finally { SecurityContextHolder.clearContext(); TenantContextHolder.clear(); }
    }

    private void assertProcess(long id, long tenant, long actor, int from) throws Exception {
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process WHERE resource_apply_id=" + id
                + " AND operator_user_id=" + actor
                + " AND action='CANCEL' AND from_status=" + from + " AND to_status=4 AND note='申请人撤销'"
                + " AND creator='" + actor + "' AND deleted=0")).isEqualTo(1);
        // 申请人撤销补普通审计事件：与状态变更同事务。
        verify(audit).record("APPLY_CANCEL", "APPLY", id, from, 4, "申请人撤销", "OK");
    }

    private void authenticate(long userId) {
        var user = new LoginUser();
        user.setId(userId);
        user.setTenantId(TenantContextHolder.getTenantId());
        user.setInfo(Map.of());
        SecurityFrameworkUtils.setLoginUser(user, new MockHttpServletRequest());
    }

    private String unchangedContent() throws Exception {
        return row("SELECT (to_jsonb(t)-ARRAY['status','updater','update_time'])::text FROM dh_market_resource_apply t WHERE id=7");
    }

    private String storedApplication() throws Exception {
        return row("SELECT row_to_json(t)::text FROM dh_market_resource_apply t WHERE id=7");
    }

    private String row(String query) throws Exception {
        try (var statement = connection.createStatement(); var row = statement.executeQuery(query)) {
            assertThat(row.next()).isTrue();
            return row.getString(1);
        }
    }

    private void assertUnchanged(String before) throws Exception {
        assertThat(storedApplication()).isEqualTo(before);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isZero();
    }

    private static void deny(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(code));
    }
}
