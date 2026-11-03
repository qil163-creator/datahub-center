package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.db.TenantDatabaseInterceptor;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketCheckProcessMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
import cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 独立提交：真实JSONB、租户过滤、CAS/过程和提交后通知；不把外部服务替身当作多应用部署证明。 */
@Timeout(30)
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketApplySubmissionPostgresTest extends SharingPostgresSupport {
    private MarketApplyService service;
    private MarketResourceApplyMapper applications;
    private DataSourceTransactionManager transactions;
    private final AssetCatalogService catalog = mock(AssetCatalogService.class);
    private final MarketAuditRecorder audit = mock(MarketAuditRecorder.class);
    private final NotifyMessageSendApi notifications = mock(NotifyMessageSendApi.class);

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
                   received_time,reply_deadline,delivery_deadline,catalog_version_id)
                  VALUES (7,8,11,0,0,0,3,'id','draft','{"previous":"snapshot"}',
                    '2020-01-01','2020-01-02','2020-01-03',39);
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
        config.setEnvironment(new Environment("apply-submission", new SpringManagedTransactionFactory(), source));
        var interceptor = new MybatisPlusInterceptor();

        config.addInterceptor(interceptor);
        config.addMapper(MarketResourceApplyMapper.class);
        config.addMapper(MarketCheckProcessMapper.class);
        config.addMapper(DataAssetMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        applications = session.getMapper(MarketResourceApplyMapper.class);
        var target = new MarketApplyServiceImpl();
        ReflectionTestUtils.setField(target, "applyMapper", applications);
        ReflectionTestUtils.setField(target, "processMapper", session.getMapper(MarketCheckProcessMapper.class));
        ReflectionTestUtils.setField(target, "assetMapper", session.getMapper(DataAssetMapper.class));
        var guard = mock(MarketObjectGuard.class);
        when(guard.requireUserId()).thenReturn(11L);
        ReflectionTestUtils.setField(target, "objectGuard", guard);
        var days = mock(MarketWorkingDayService.class);
        when(days.plusWorkingDays(any(), anyInt())).thenAnswer(call ->
                ((LocalDateTime) call.getArgument(0)).plusDays((Integer) call.getArgument(1)));
        ReflectionTestUtils.setField(target, "workingDayService", days);
        var column = new MetadataColumnRespDTO();
        column.setColumnName("id");
        when(catalog.listColumns(8L, 41L)).thenReturn(List.of(column));
        org.mockito.Mockito.when(catalog.requireCompletePublishedAsset(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> call.getArgument(0));
        ReflectionTestUtils.setField(target, "catalogService", catalog);
        ReflectionTestUtils.setField(target, "auditRecorder", audit);
        var notify = new MarketNotifyHelper();
        ReflectionTestUtils.setField(notify, "notifyMessageSendApi", notifications);
        ReflectionTestUtils.setField(target, "notifyHelper", notify);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (MarketApplyService) proxy.getProxy();
        TenantContextHolder.clear();
    }

    @AfterEach
    void clearTenant() { TenantContextHolder.clear(); }

    @ParameterizedTest
    @ValueSource(strings = {"text", "jsonb"})
    void skippedProcessCannotCommitSubmission(String storage) throws Exception {
        if ("text".equals(storage)) {
            sql("ALTER TABLE dh_market_resource_apply ALTER COLUMN snapshot TYPE text USING snapshot::text");
        }
        String before = storedApplication();
        sql("""
                CREATE FUNCTION skip_submission_process() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RETURN NULL; END $$;
                CREATE TRIGGER skip_submission_process BEFORE INSERT ON dh_market_check_process
                FOR EACH ROW EXECUTE FUNCTION skip_submission_process();
                """);
        deny(() -> service.submitApply(7L), MARKET_APPLY_STATUS_DENY.getCode());
        assertNoSubmission(before);
        verifyNoInteractions(audit);
    }

    @Test
    void submissionPersistsJsonbAndCompleteProcessOnlyBeforeCommitNotification() throws Exception {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            service.submitApply(7L);
            assertThat(applications.selectById(7L).getStatus()).isEqualTo(1);
            verifyNoInteractions(notifications);
        });
        var current = applications.selectById(7L);
        assertThat(current.getRevision()).isEqualTo(3);
        assertThat(current.getIfAuth()).isZero();
        assertThat(current.getPaused()).isZero();
        assertThat(current.getCatalogVersionId()).isEqualTo(51);
        assertThat(current.getReceivedTime()).isAfter(LocalDateTime.of(2020, 1, 1, 0, 0));
        assertThat(current.getReplyDeadline()).isAfter(current.getReceivedTime());
        assertThat(current.getDeliveryDeadline()).isAfter(current.getReplyDeadline());
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=7 AND jsonb_typeof(snapshot)='object'"
                + " AND snapshot->>'revision'='3' AND snapshot->>'assetId'='8'"
                + " AND snapshot->'frozenColumns'='[\"id\"]'::jsonb")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process WHERE resource_apply_id=7"
                + " AND action='SUBMIT' AND from_status=0 AND to_status=1 AND operator_user_id=11")).isEqualTo(1);
        verify(notifications, times(2)).sendSingleMessageToAdmin(any());
        verify(audit).record("SUBMIT", "APPLY", 7L, 0, 1, null, "OK");
        deny(() -> service.submitApply(7L), MARKET_APPLY_STATUS_DENY.getCode());
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(1);
        verifyNoMoreInteractions(notifications, audit);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CAS_ZERO", "PROCESS_CHECK", "PROCESS_PRIMARY_KEY", "COMMIT"})
    void failuresRollbackTheWholeOriginalRowAndDoNotNotify(String failure) throws Exception {
        String before = storedApplication();
        switch (failure) {
            case "CAS_ZERO" -> sql("""
                    CREATE FUNCTION skip_submission_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$;
                    CREATE TRIGGER skip_submission_update BEFORE UPDATE ON dh_market_resource_apply
                    FOR EACH ROW EXECUTE FUNCTION skip_submission_update();
                    """);
            case "PROCESS_CHECK" -> sql("ALTER TABLE dh_market_check_process ADD CHECK(action<>'SUBMIT')");
            case "PROCESS_PRIMARY_KEY" -> sql("INSERT INTO dh_market_check_process(id,resource_apply_id,action) VALUES(1,99,'CANCEL')");
            case "COMMIT" -> sql("""
                    CREATE FUNCTION reject_submission_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN RAISE EXCEPTION 'test submission commit failure'; END $$;
                    CREATE CONSTRAINT TRIGGER reject_submission_commit AFTER UPDATE ON dh_market_resource_apply
                    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_submission_commit();
                    """);
            default -> throw new IllegalArgumentException(failure);
        }
        if ("CAS_ZERO".equals(failure)) deny(() -> service.submitApply(7L), MARKET_APPLY_STATUS_DENY.getCode());
        else assertThatThrownBy(() -> service.submitApply(7L)).isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(ServiceException.class);
        assertThat(storedApplication()).isEqualTo(before);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process"))
                .isEqualTo("PROCESS_PRIMARY_KEY".equals(failure) ? 1 : 0);
        verifyNoInteractions(notifications);
        if (!"COMMIT".equals(failure)) verifyNoInteractions(audit);
    }

    @Test
    void outerRollbackRemovesSubmissionAndItsDeferredNotification() throws Exception {
        String before = storedApplication();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            service.submitApply(7L);
            verifyNoInteractions(notifications);
            tx.setRollbackOnly();
        });
        assertNoSubmission(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status=4", "revision=4", "asset_id=9", "apply_user_id=12", "deleted=1"})
    void concurrentChangeAfterMetadataReadRejectsOldSubmission(String change) throws Exception {
        when(catalog.listColumns(8L, 41L)).thenAnswer(call -> {
            sql("UPDATE dh_market_resource_apply SET " + change + " WHERE id=7");
            var column = new MetadataColumnRespDTO();
            column.setColumnName("id");
            return List.of(column);
        });
        deny(() -> service.submitApply(7L), MARKET_APPLY_STATUS_DENY.getCode());
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=7 AND snapshot='{\"previous\":\"snapshot\"}'::jsonb"
                + " AND received_time='2020-01-01'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isZero();
        verifyNoInteractions(notifications, audit);
    }

    @Test
    void simultaneousSubmissionsCommitExactlyOneProcess() throws Exception {
        var bothFrozen = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        when(catalog.listColumns(8L, 41L)).thenAnswer(call -> {
            bothFrozen.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            var column = new MetadataColumnRespDTO();
            column.setColumnName("id");
            return List.of(column);
        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(this::submitOutcome);
            var second = workers.submit(this::submitOutcome);
            try { assertThat(bothFrozen.await(10, TimeUnit.SECONDS)).isTrue(); }
            finally { release.countDown(); }
            var results = List.of(first.get(12, TimeUnit.SECONDS), second.get(12, TimeUnit.SECONDS));
            assertThat(results.stream().filter("OK"::equals).count()).isEqualTo(1);
            var conflict = results.stream().filter(ServiceException.class::isInstance).findFirst().orElseThrow();
            assertThat(((ServiceException) conflict).getCode()).isEqualTo(MARKET_APPLY_STATUS_DENY.getCode());
        }
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(1);
        verify(notifications, times(2)).sendSingleMessageToAdmin(any());
        verify(audit).record("SUBMIT", "APPLY", 7L, 0, 1, null, "OK");
    }

    @Test
    void legacyNullRevisionInitializesWithoutLosingJsonbBinding() throws Exception {
        sql("UPDATE dh_market_resource_apply SET revision=NULL WHERE id=7");
        service.submitApply(7L);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=7 AND revision=1"
                + " AND snapshot->>'revision'='1' AND status=1")).isEqualTo(1);
    }



    @Test
    void quotedMetadataStaysAJsonValueAndCannotAlterTheUpdateSql() throws Exception {
        sql("UPDATE dh_market_resource_apply SET field_scope=NULL WHERE id=7");
        String name = "field'\"; -- {0}";
        var column = new MetadataColumnRespDTO();
        column.setColumnName(name);
        when(catalog.listColumns(8L, 41L)).thenReturn(List.of(column));
        service.submitApply(7L);
        try (var statement = connection.createStatement(); var row = statement.executeQuery(
                "SELECT snapshot->'frozenColumns'->>0 FROM dh_market_resource_apply WHERE id=7")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString(1)).isEqualTo(name);
        }
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 9007199254740993L, Long.MAX_VALUE})
    void largeApplicationIdIsPreserved(long tenantId) throws Exception {
        long id = 9007199254740993L;
        sql("UPDATE dh_market_resource_apply SET id=" + id + " WHERE id=7");

        TenantContextHolder.clear();
        service.submitApply(id);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=" + id
                + " AND status=1")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process WHERE resource_apply_id=" + id
                )).isEqualTo(1);
    }

    private Object submitOutcome() {
        TenantContextHolder.clear();
        try { service.submitApply(7L); return "OK"; }
        catch (RuntimeException ex) { return ex; }
        finally { TenantContextHolder.clear(); }
    }

    private String storedApplication() throws Exception {
        try (var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT row_to_json(t)::text FROM dh_market_resource_apply t WHERE id=7")) {
            assertThat(row.next()).isTrue();
            return row.getString(1);
        }
    }

    private void assertNoSubmission(String before) throws Exception {
        assertThat(storedApplication()).isEqualTo(before);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isZero();
        verifyNoInteractions(notifications);
    }

    private static void deny(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(code));
    }
}
