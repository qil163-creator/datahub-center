package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.db.TenantDatabaseInterceptor;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketCheckProcessMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
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
import java.util.concurrent.atomic.AtomicReference;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 要求补正：真实租户/对象Guard、状态与过程原子性；权限RPC和审计为替身。 */
@Timeout(30)
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketApplySupplementPostgresTest extends SharingPostgresSupport {
    private MarketApplyService service;
    private MarketResourceApplyMapper applications;
    private DataSourceTransactionManager transactions;
    private final PermissionApi permissions = mock(PermissionApi.class);
    private final MarketAuditRecorder audit = mock(MarketAuditRecorder.class);
    private final MarketNotifyHelper notifications = mock(MarketNotifyHelper.class);
    private static final String NOTE = "请补充依据 ' ; -- {0}\n\"用途\"";

    @BeforeEach
    void prepare() throws Exception {
        for (String fixture : List.of("market-freeze-fixture.sql", "market-demand-asset-fixture.sql")) {
            try (var input = getClass().getResourceAsStream("/sql/" + fixture)) {
                if (input == null) throw new IllegalStateException("Missing fixture " + fixture);
                sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        sql("""
                ALTER TABLE dh_market_check_process ADD tenant_id bigint NOT NULL DEFAULT 1;
                ALTER TABLE dh_market_resource_apply ALTER COLUMN snapshot TYPE jsonb USING snapshot::jsonb;
                INSERT INTO dh_market_resource_apply
                  (id,asset_id,apply_user_id,status,if_auth,paused,revision,field_scope,title,snapshot,
                   received_time,reply_deadline,delivery_deadline,catalog_version_id,supplement_note)
                  VALUES (7,8,11,1,0,0,3,'id','pending','{"previous":"snapshot"}',
                    '2020-01-01','2020-01-02','2020-01-03',39,'old note');
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
        config.setEnvironment(new Environment("apply-supplement", new SpringManagedTransactionFactory(), source));
        var interceptor = new MybatisPlusInterceptor();

        config.addInterceptor(interceptor);
        config.addMapper(MarketResourceApplyMapper.class);
        config.addMapper(MarketCheckProcessMapper.class);
        config.addMapper(DataAssetMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        applications = session.getMapper(MarketResourceApplyMapper.class);
        var assets = session.getMapper(DataAssetMapper.class);
        var target = new MarketApplyServiceImpl();
        ReflectionTestUtils.setField(target, "applyMapper", applications);
        ReflectionTestUtils.setField(target, "processMapper", session.getMapper(MarketCheckProcessMapper.class));
        ReflectionTestUtils.setField(target, "assetMapper", assets);
        var guard = new MarketObjectGuard();
        when(permissions.hasAnyPermissions(anyLong(), eq(MarketObjectGuard.PERM_APPLY_CHECK)))
                .thenReturn(CommonResult.success(true));
        ReflectionTestUtils.setField(guard, "permissionApi", permissions);
        ReflectionTestUtils.setField(guard, "assetMapper", assets);
        ReflectionTestUtils.setField(target, "objectGuard", guard);
        ReflectionTestUtils.setField(target, "auditRecorder", audit);
        ReflectionTestUtils.setField(target, "notifyHelper", notifications);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (MarketApplyService) proxy.getProxy();
        TenantContextHolder.clear();
        authenticate(81L, null);
    }

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    @Test
    void skippedProcessCannotCommitSupplementStatusOrNote() throws Exception {
        String before = storedApplication();
        sql("""
                CREATE FUNCTION skip_supplement_process() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RETURN NULL; END $$;
                CREATE TRIGGER skip_supplement_process BEFORE INSERT ON dh_market_check_process
                FOR EACH ROW EXECUTE FUNCTION skip_supplement_process();
                """);
        deny(() -> service.requestSupplement(7L, NOTE), MARKET_APPLY_STATUS_DENY.getCode());
        assertUnchanged(before);
        verifyNoInteractions(audit);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", NOTE})
    void successPreservesFrozenContentAndWritesOneMatchingProcess(String note) throws Exception {
        String originalContent = unchangedContent();
        service.requestSupplement(7L, note);
        var current = applications.selectById(7L);
        assertThat(current.getStatus()).isEqualTo(8);
        assertThat(current.getSupplementNote()).isEqualTo(note);
        assertThat(unchangedContent()).isEqualTo(originalContent);
        assertProcess(7L, 1L, 81L, note);
        verify(audit).record("SUPPLEMENT", "APPLY", 7L, 1, 8, note, "OK");
        deny(() -> service.requestSupplement(7L, "repeat"), MARKET_APPLY_STATUS_DENY.getCode());
        assertProcess(7L, 1L, 81L, note);
        verifyNoMoreInteractions(audit);
        verifyNoInteractions(notifications);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CAS_ZERO", "PROCESS_CHECK", "PROCESS_PRIMARY_KEY", "COMMIT"})
    void writeOrCommitFailureRollsBackTheWholeRowAndProcess(String failure) throws Exception {
        String before = storedApplication();
        switch (failure) {
            case "CAS_ZERO" -> sql("""
                    CREATE FUNCTION skip_supplement_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$;
                    CREATE TRIGGER skip_supplement_update BEFORE UPDATE ON dh_market_resource_apply
                    FOR EACH ROW EXECUTE FUNCTION skip_supplement_update();
                    """);
            case "PROCESS_CHECK" -> sql("ALTER TABLE dh_market_check_process ADD CHECK(action<>'SUPPLEMENT')");
            case "PROCESS_PRIMARY_KEY" -> sql("INSERT INTO dh_market_check_process(id,resource_apply_id,action) VALUES(1,99,'CANCEL')");
            case "COMMIT" -> sql("""
                    CREATE FUNCTION reject_supplement_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN RAISE EXCEPTION 'test supplement commit failure'; END $$;
                    CREATE CONSTRAINT TRIGGER reject_supplement_commit AFTER UPDATE ON dh_market_resource_apply
                    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_supplement_commit();
                    """);
            default -> throw new IllegalArgumentException(failure);
        }
        if ("CAS_ZERO".equals(failure)) deny(() -> service.requestSupplement(7L, NOTE), MARKET_APPLY_STATUS_DENY.getCode());
        else assertThatThrownBy(() -> service.requestSupplement(7L, NOTE)).isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(ServiceException.class);
        assertThat(storedApplication()).isEqualTo(before);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process"))
                .isEqualTo("PROCESS_PRIMARY_KEY".equals(failure) ? 1 : 0);
        verifyNoInteractions(notifications);
        if (!"COMMIT".equals(failure)) verifyNoInteractions(audit);
    }

    @Test
    void outerRollbackRestoresNoteStatusAndRemovesProcess() throws Exception {
        String before = storedApplication();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            service.requestSupplement(7L, NOTE);
            assertThat(applications.selectById(7L).getStatus()).isEqualTo(8);
            tx.setRollbackOnly();
        });
        assertUnchanged(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status=4", "revision=4", "asset_id=9", "apply_user_id=12", "deleted=1"})
    void concurrentChangeDuringPermissionReadCannotBeOverwritten(String change) throws Exception {
        var winner = new AtomicReference<String>();
        when(permissions.hasAnyPermissions(81L, MarketObjectGuard.PERM_APPLY_CHECK)).thenAnswer(call -> {
            sql("UPDATE dh_market_resource_apply SET " + change + " WHERE id=7");
            winner.set(storedApplication());
            return CommonResult.success(true);
        });
        deny(() -> service.requestSupplement(7L, NOTE), MARKET_APPLY_STATUS_DENY.getCode());
        assertUnchanged(winner.get());
        verifyNoInteractions(audit);
    }

    @Test
    void simultaneousCheckersWriteOnlyTheWinningNoteAndProcess() throws Exception {
        var bothRead = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        when(permissions.hasAnyPermissions(81L, MarketObjectGuard.PERM_APPLY_CHECK)).thenAnswer(call -> {
            bothRead.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return CommonResult.success(true);
        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> supplementOutcome("first"));
            var second = workers.submit(() -> supplementOutcome("second"));
            try { assertThat(bothRead.await(10, TimeUnit.SECONDS)).isTrue(); }
            finally { release.countDown(); }
            var outcomes = List.of(first.get(12, TimeUnit.SECONDS), second.get(12, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter("OK"::equals).count()).isEqualTo(1);
            var conflict = outcomes.stream().filter(ServiceException.class::isInstance).findFirst().orElseThrow();
            assertThat(((ServiceException) conflict).getCode()).isEqualTo(MARKET_APPLY_STATUS_DENY.getCode());
        }
        var current = applications.selectById(7L);
        assertThat(current.getStatus()).isEqualTo(8);
        assertThat(current.getRevision()).isEqualTo(3);
        assertThat(current.getSupplementNote()).isIn("first", "second");
        assertProcess(7L, 1L, 81L, current.getSupplementNote());
        verify(audit).record("SUPPLEMENT", "APPLY", 7L, 1, 8, current.getSupplementNote(), "OK");
        verifyNoInteractions(notifications);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NULL", "2147483647"})
    void supplementDoesNotInitializeOrIncrementTheContentRevision(String revision) throws Exception {
        sql("UPDATE dh_market_resource_apply SET revision=" + revision + " WHERE id=7");
        String originalContent = unchangedContent();
        service.requestSupplement(7L, NOTE);
        assertThat(unchangedContent()).isEqualTo(originalContent);
        assertProcess(7L, 1L, 81L, NOTE);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void invalidRevisionCannotChangeStatusOrNote(int revision) throws Exception {
        sql("UPDATE dh_market_resource_apply SET revision=" + revision + " WHERE id=7");
        String before = storedApplication();
        deny(() -> service.requestSupplement(7L, NOTE), MARKET_APPLY_STATUS_DENY.getCode());
        assertUnchanged(before);
        verifyNoInteractions(audit);
    }



    @ParameterizedTest
    @ValueSource(strings = {"LOGIN", "PERMISSION", "PROVIDER", "MISSING_ASSET", "DELETED_ASSET"})
    void realObjectGuardRejectsMissingIdentityPermissionOrProviderRelation(String missing) throws Exception {
        switch (missing) {
            case "LOGIN" -> SecurityContextHolder.clearContext();
            case "PERMISSION" -> when(permissions.hasAnyPermissions(81L, MarketObjectGuard.PERM_APPLY_CHECK))
                    .thenReturn(CommonResult.success(false));
            case "PROVIDER" -> authenticate(11L, null); // 即使是申请人也不能冒充审核方
            case "MISSING_ASSET" -> sql("UPDATE dh_market_resource_apply SET asset_id=NULL WHERE id=7");
            case "DELETED_ASSET" -> sql("UPDATE dh_asset_data_asset SET deleted=1 WHERE id=8");
            default -> throw new IllegalArgumentException(missing);
        }
        String before = storedApplication();
        deny(() -> service.requestSupplement(7L, NOTE), MARKET_APPLY_FORBIDDEN.getCode());
        assertUnchanged(before);
        verifyNoInteractions(audit);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TECH", "BUSINESS", "DEPARTMENT"})
    void permittedProvidersKeepTheExistingRolePolicy(String role) throws Exception {
        long userId = "TECH".equals(role) ? 81L : "BUSINESS".equals(role) ? 82L : 93L;
        authenticate(userId, "DEPARTMENT".equals(role) ? 71L : null);
        service.requestSupplement(7L, NOTE);
        assertProcess(7L, 1L, userId, NOTE);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 9007199254740993L, Long.MAX_VALUE})
    void largeApplicationIdIsPreserved(long tenantId) throws Exception {
        long id = 9007199254740993L;
        sql("UPDATE dh_market_resource_apply SET id=" + id + " WHERE id=7");

        TenantContextHolder.clear();
        authenticate(81L, null);
        service.requestSupplement(id, NOTE);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=" + id
                + " AND status=8 AND revision=3")).isEqualTo(1);
        assertProcess(id, tenantId, 81L, NOTE);
    }

    private Object supplementOutcome(String note) {
        TenantContextHolder.clear();
        authenticate(81L, null);
        try { service.requestSupplement(7L, note); return "OK"; }
        catch (RuntimeException ex) { return ex; }
        finally { clearContexts(); }
    }

    private void assertProcess(long id, long tenant, long operator, String note) throws Exception {
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process WHERE resource_apply_id=" + id
                + " AND operator_user_id=" + operator
                + " AND action='SUPPLEMENT' AND from_status=1 AND to_status=8")).isEqualTo(1);
        try (var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT note FROM dh_market_check_process")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString(1)).isEqualTo(note);
        }
    }

    private String unchangedContent() throws Exception {
        try (var statement = connection.createStatement(); var row = statement.executeQuery(
                "SELECT (to_jsonb(t)-ARRAY['status','supplement_note','updater','update_time'])::text"
                        + " FROM dh_market_resource_apply t WHERE id=7")) {
            assertThat(row.next()).isTrue();
            return row.getString(1);
        }
    }

    private void authenticate(long userId, Long deptId) {
        var user = new LoginUser();
        user.setId(userId);
        user.setTenantId(TenantContextHolder.getTenantId());
        user.setInfo(deptId == null ? Map.of() : Map.of(LoginUser.INFO_KEY_DEPT_ID, String.valueOf(deptId)));
        SecurityFrameworkUtils.setLoginUser(user, new MockHttpServletRequest());
    }

    private String storedApplication() throws Exception {
        try (var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT row_to_json(t)::text FROM dh_market_resource_apply t WHERE id=7")) {
            assertThat(row.next()).isTrue();
            return row.getString(1);
        }
    }

    private void assertUnchanged(String before) throws Exception {
        assertThat(storedApplication()).isEqualTo(before);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isZero();
        verifyNoInteractions(notifications);
    }

    private static void deny(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(code));
    }
}
