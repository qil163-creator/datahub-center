package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.db.TenantDatabaseInterceptor;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketApplySaveReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketCheckProcessMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
import cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 普通创建的真实PG事务、部分唯一索引、主键/租户/审计填充及提交后通知；外部读取为受控替身。 */
@Timeout(30)
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketApplyCreationPostgresTest extends SharingPostgresSupport {
    private MarketApplyService service;
    private MarketResourceApplyMapper applications;
    private final NotifyMessageSendApi notifications = mock(NotifyMessageSendApi.class);
    private final MarketAuditRecorder audit = mock(MarketAuditRecorder.class);
    private final AssetCatalogService catalog = mock(AssetCatalogService.class);

    @BeforeEach
    void prepare() throws Exception {
        for (var fixture : List.of("market-freeze-fixture.sql", "market-demand-asset-fixture.sql")) {
            try (var input = getClass().getResourceAsStream("/sql/" + fixture)) {
                if (input == null) throw new IllegalStateException("Missing fixture " + fixture);
                sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        sql("""
                ALTER TABLE dh_market_check_process ADD tenant_id bigint NOT NULL DEFAULT 1;
                ALTER TABLE dh_market_resource_apply ALTER COLUMN id SET DEFAULT nextval('dh_market_resource_apply_seq');
                ALTER TABLE dh_market_resource_apply ALTER COLUMN snapshot TYPE jsonb USING snapshot::jsonb;
                """);
        migrate("0042_asset_grant-source-delivery.sql");
        migrate("0047_asset_renewal-request-evidence.sql");
        migrate("0050_asset_market-active-application-uniqueness.sql");
        var source = new UnpooledDataSource("org.postgresql.Driver",
                jdbcUrl + "?currentSchema=datahub-center&socketTimeout=10", "sharing_test", "");
        var config = singleTenantConfiguration();
        var global = new GlobalConfig().setMetaObjectHandler(new DefaultDBFieldHandler());
        global.setDbConfig(new GlobalConfig.DbConfig().setIdType(IdType.INPUT)
                .setKeyGenerators(List.of(new PostgreKeyGenerator())));
        GlobalConfigUtils.setGlobalConfig(config, global);
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("apply-creation", new SpringManagedTransactionFactory(), source));
        var interceptor = new MybatisPlusInterceptor();

        config.addInterceptor(interceptor);
        config.addMapper(MarketResourceApplyMapper.class);
        config.addMapper(MarketCheckProcessMapper.class);
        config.addMapper(DataAssetMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        applications = spy(session.getMapper(MarketResourceApplyMapper.class));
        var target = new MarketApplyServiceImpl();
        ReflectionTestUtils.setField(target, "applyMapper", applications);
        ReflectionTestUtils.setField(target, "processMapper", session.getMapper(MarketCheckProcessMapper.class));
        ReflectionTestUtils.setField(target, "assetMapper", session.getMapper(DataAssetMapper.class));
        var guard = mock(MarketObjectGuard.class);
        when(guard.requireUserId()).thenReturn(11L);
        ReflectionTestUtils.setField(target, "objectGuard", guard);
        ReflectionTestUtils.setField(target, "displayHelper", new MarketDisplayHelper());
        var users = mock(AdminUserApi.class);
        when(users.getUser(11L)).thenReturn(CommonResult.success(null));
        ReflectionTestUtils.setField(target, "adminUserApi", users);
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
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        service = (MarketApplyService) proxy.getProxy();
        TenantContextHolder.clear();
    }

    @AfterEach
    void clearTenant() { TenantContextHolder.clear(); }

    @ParameterizedTest
    @CsvSource({"dh_market_resource_apply,false", "dh_market_resource_apply,true", "dh_market_check_process,true"})
    void skippedInsertCannotCommitAnIncompleteApplication(String table, boolean submit) throws Exception {
        sql("""
                CREATE FUNCTION skip_test_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$;
                CREATE TRIGGER skip_test_insert BEFORE INSERT ON %s FOR EACH ROW EXECUTE FUNCTION skip_test_insert();
                """.formatted(table));
        deny(() -> service.createApply(request(submit)), MARKET_APPLY_STATUS_DENY.getCode());
        assertNoApplication();
    }

    @Test
    void unrelatedPrimaryKeyViolationIsNotMisreportedAsDuplicateApplication() throws Exception {
        sql("INSERT INTO dh_market_resource_apply(id,asset_id,apply_user_id,status) VALUES(1,8,11,2)");
        assertThatThrownBy(() -> service.createApply(request(false)))
                .isInstanceOf(DataIntegrityViolationException.class).isNotInstanceOf(ServiceException.class);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply")).isEqualTo(1);
        assertThat(scalar("SELECT status FROM dh_market_resource_apply WHERE id=1")).isEqualTo(2);
        verifyNoInteractions(notifications, audit);
    }

    @Test
    void submissionSnapshotIsPresentInTheInitialInsert() throws Exception {
        sql("""
                ALTER TABLE dh_market_resource_apply ADD CONSTRAINT test_complete_submission
                  CHECK(status<>1 OR (snapshot IS NOT NULL AND catalog_version_id IS NOT NULL
                    AND received_time IS NOT NULL AND reply_deadline IS NOT NULL AND delivery_deadline IS NOT NULL));
                """);
        assertThatCode(() -> service.createApply(request(true))).doesNotThrowAnyException();
        long id = scalar("SELECT id FROM dh_market_resource_apply");
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=" + id + " AND status=1")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=" + id
                + " AND jsonb_typeof(snapshot)='object' AND snapshot->>'assetId'='8'"
                + " AND snapshot->'frozenColumns'='[\"id\"]'::jsonb")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process WHERE resource_apply_id=" + id)).isEqualTo(1);
        verify(notifications, times(2)).sendSingleMessageToAdmin(any());
    }

    @ParameterizedTest
    @CsvSource({"false,1", "true,9007199254740993"})
    void generatedIdFieldsAndAuditArePersistedWithoutTrustingClientId(boolean submit, long expectedId) throws Exception {
        sql("SELECT setval('dh_market_resource_apply_seq'," + expectedId + ",false)");
        var req = request(submit);
        req.setId(777L);
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(11L);
            security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(100L);
            assertThat(service.createApply(req)).isEqualTo(expectedId);
        }
        var row = applications.selectById(expectedId);
        assertThat(row.getTitle()).isEqualTo(req.getTitle());
        assertThat(row.getAssetId()).isEqualTo(8L);
        assertThat(row.getAssetType()).isEqualTo(1);
        assertThat(row.getApplyUserId()).isEqualTo(11L);
        assertThat(row.getApplyDeptId()).isEqualTo(100L);
        assertThat(row.getDemandId()).isEqualTo(55L);
        assertThat(row.getPurpose()).isEqualTo(req.getPurpose());
        assertThat(row.getFieldScope()).isEqualTo("id");
        assertThat(row.getDutyMatter()).isEqualTo(req.getDutyMatter());
        assertThat(row.getLegalBasis()).isEqualTo(req.getLegalBasis());
        assertThat(row.getPhone()).isEqualTo(req.getPhone());
        assertThat(row.getEmail()).isEqualTo(req.getEmail());
        assertThat(row.getApplyNote()).isEqualTo(req.getApplyNote());
        assertThat(row.getRemark()).isEqualTo(req.getRemark());
        assertThat(row.getApplyStartTime()).isEqualTo(req.getApplyStartTime());
        assertThat(row.getApplyEndTime()).isEqualTo(req.getApplyEndTime());
        assertThat(row.getApplyUseType()).isEqualTo(req.getApplyUseType());
        assertThat(row.getApplyUseTimes()).isEqualTo(42);
        assertThat(row.getIfAuth()).isZero();
        assertThat(row.getRevision()).isEqualTo(1);
        assertThat(row.getPaused()).isZero();
        assertThat(row.getDeliveryStatus()).isZero();
        assertThat(row.getCreator()).isEqualTo("11");
        assertThat(row.getUpdater()).isEqualTo("11");
        assertThat(row.getCreateTime()).isNotNull();
        assertThat(row.getUpdateTime()).isNotNull();
        assertThat(scalar("SELECT count(*) FROM information_schema.columns WHERE table_schema='datahub-center' AND table_name='dh_market_resource_apply' AND column_name='tenant_id'")).isZero();
        if (submit) {
            assertThat(row.getSnapshot()).contains("frozenColumns", "catalogVersionId", "databaseId");
            assertThat(row.getCatalogVersionId()).isEqualTo(51);
            assertThat(row.getReceivedTime()).isNotNull();
            assertThat(row.getReplyDeadline()).isAfter(row.getReceivedTime());
            assertThat(row.getDeliveryDeadline()).isAfter(row.getReplyDeadline());
        } else {
            assertThat(row.getSnapshot()).isNull();
            assertThat(row.getReceivedTime()).isNull();
            verifyNoInteractions(notifications, audit);
            verify(catalog, never()).listColumns(anyLong(), anyLong());
        }
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(submit ? 1 : 0);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 8})
    void competitorAfterEmptyReadReturnsDomainDuplicate(int status) throws Exception {
        doAnswer(call -> {
            Object empty = call.callRealMethod();
            insertCompetitor(status);
            return empty;
        }).when(applications).selectActiveByUserAndAsset(11L, 8L);
        deny(() -> service.createApply(request(true)), MARKET_APPLY_DUPLICATE.getCode());
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isZero();
        verifyNoInteractions(notifications, audit);
    }

    @Test
    void simultaneousFirstCreationCommitsOneApplicationAndProcess() throws Exception {
        var bothRead = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            Object empty = call.callRealMethod();
            bothRead.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return empty;
        }).when(applications).selectActiveByUserAndAsset(11L, 8L);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var left = executor.submit(this::createOutcome);
            var right = executor.submit(this::createOutcome);
            try { assertThat(bothRead.await(10, TimeUnit.SECONDS)).isTrue(); }
            finally { release.countDown(); }
            var results = List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS));
            assertThat(results.stream().filter(Long.class::isInstance).count()).isEqualTo(1);
            var error = results.stream().filter(ServiceException.class::isInstance).findFirst().orElseThrow();
            assertThat(((ServiceException) error).getCode()).isEqualTo(MARKET_APPLY_DUPLICATE.getCode());
        }
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(1);
        verify(notifications, times(2)).sendSingleMessageToAdmin(any());
        verify(audit).record(eq("SUBMIT"), eq("APPLY"), anyLong(), isNull(), eq(1), anyString(), eq("OK"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_NULL", "APPLY_CHECK", "PROCESS_CHECK", "COMMIT"})
    void constraintAndCommitFailuresLeaveNoPartialCreationOrNotification(String failure) throws Exception {
        switch (failure) {
            case "NOT_NULL" -> sql("ALTER TABLE dh_market_resource_apply ALTER COLUMN api_id SET NOT NULL");
            case "APPLY_CHECK" -> sql("ALTER TABLE dh_market_resource_apply ADD CHECK(status<>1)");
            case "PROCESS_CHECK" -> sql("ALTER TABLE dh_market_check_process ADD CHECK(to_status<>1)");
            case "COMMIT" -> sql("""
                    CREATE FUNCTION reject_test_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN RAISE EXCEPTION 'test commit failure'; END $$;
                    CREATE CONSTRAINT TRIGGER reject_test_commit AFTER INSERT ON dh_market_resource_apply
                    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_test_commit();
                    """);
            default -> throw new IllegalArgumentException(failure);
        }
        assertThatThrownBy(() -> service.createApply(request(true)))
                .isInstanceOf(RuntimeException.class).isNotInstanceOf(ServiceException.class);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply")).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isZero();
        verifyNoInteractions(notifications);
        if (!"COMMIT".equals(failure)) verifyNoInteractions(audit);
    }

    @Test
    void missingUniqueIndexFailsClosed() throws Exception {
        sql("DROP INDEX uk_market_active_pending_apply");
        assertThatThrownBy(() -> service.createApply(request(false))).isInstanceOf(RuntimeException.class);
        assertNoApplication();
    }

    @ParameterizedTest
    @ValueSource(strings = {"apply_user_id=12", "asset_id=9", "deleted=1",
            "status=2", "status=3", "status=4", "status=5", "status=6"})
    void otherIdentityOrTerminalApplicationDoesNotBlockCreation(String change) throws Exception {
        insertCompetitor(1);
        sql("UPDATE dh_market_resource_apply SET " + change + " WHERE id=90");
        long id = service.createApply(request(false));
        assertThat(id).isNotEqualTo(90);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply")).isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=" + id
                + " AND apply_user_id=11 AND asset_id=8 AND status=0 AND deleted=0")).isEqualTo(1);
        verifyNoInteractions(notifications, audit);
            verify(catalog, never()).listColumns(anyLong(), anyLong());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void uniqueWaitResolvesCompetitorCommitOrRollback(boolean commit) throws Exception {
        try (var worker = Executors.newSingleThreadExecutor(); var competitor = open()) {
            competitor.setAutoCommit(false);
            try (var statement = competitor.createStatement()) {
                statement.executeUpdate("INSERT INTO dh_market_resource_apply(id,asset_id,apply_user_id,status)"
                        + " VALUES(90,8,11,1)");
            }
            var outcome = worker.submit(this::createOutcome);
            try {
                org.awaitility.Awaitility.await().atMost(8, TimeUnit.SECONDS).until(() -> {
                    assertThat(outcome.isDone()).as("creator must reach the real unique-index wait").isFalse();
                    return scalar("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()"
                            + " AND pid<>pg_backend_pid() AND cardinality(pg_blocking_pids(pid))>0"
                            + " AND query ILIKE '%INSERT INTO dh_market_resource_apply%'") > 0;
                });
            } finally {
                if (commit) competitor.commit(); else competitor.rollback();
            }
            var result = outcome.get(12, TimeUnit.SECONDS);
            if (commit) {
                assertThat(result).isInstanceOfSatisfying(ServiceException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(MARKET_APPLY_DUPLICATE.getCode()));
                assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isZero();
                verifyNoInteractions(notifications, audit);
            } else {
                assertThat(result).isInstanceOf(Long.class);
                assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(1);
                verify(notifications, times(2)).sendSingleMessageToAdmin(any());
            }
        }
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply")).isEqualTo(1);
    }

    @Test
    void metadataFailureHappensBeforeAnyApplicationWrite() throws Exception {
        when(catalog.listColumns(8L, 41L)).thenThrow(new IllegalStateException("test metadata unavailable"));
        deny(() -> service.createApply(request(true)), MARKET_APPLY_METADATA_UNAVAILABLE.getCode());
        verify(applications, never()).insertInitialApplyIfAbsent(any());
        verify(applications, never()).insert(any(MarketResourceApplyDO.class));
        assertNoApplication();
    }

    @Test
    void sharedInsertRetainsApiBindingFieldsWithoutRenewalOrApprovalState() throws Exception {
        var initial = MarketResourceApplyDO.builder().assetId(8L).applyUserId(11L).status(0)
                .apiId(9007199254740993L).appId(Long.MAX_VALUE).build();
        assertThat(applications.insertInitialApplyIfAbsent(initial)).isEqualTo(1);
        var current = applications.selectById(initial.getId());
        assertThat(current.getApiId()).isEqualTo(9007199254740993L);
        assertThat(current.getAppId()).isEqualTo(Long.MAX_VALUE);
        assertThat(current.getRenewalOfApplyId()).isNull();
        assertThat(current.getRenewalOperationId()).isNull();
        assertThat(current.getCheckUserId()).isNull();
        assertThat(current.getSourceGrantId()).isNull();
    }

    @Test
    void malformedSnapshotCannotBeStoredAsQuotedJsonOrBypassTheTypedColumn() throws Exception {
        var initial = MarketResourceApplyDO.builder().assetId(8L).applyUserId(11L).status(1)
                .snapshot("invalid-test-json").build();
        assertThatThrownBy(() -> applications.insertInitialApplyIfAbsent(initial))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertNoApplication();
    }

    private void insertCompetitor(int status) throws Exception {
        sql("INSERT INTO dh_market_resource_apply(id,asset_id,apply_user_id,status) VALUES(90,8,11," + status + ")");
    }

    private Object createOutcome() {
        TenantContextHolder.clear();
        try { return service.createApply(request(true)); }
        catch (RuntimeException ex) { return ex; }
        finally { TenantContextHolder.clear(); }
    }

    private static MarketApplySaveReqVO request(boolean submit) {
        var req = new MarketApplySaveReqVO();
        req.setTitle("测试申请");
        req.setAssetId(8L);
        req.setApplyDeptId(100L);
        req.setDemandId(55L);
        req.setPurpose("统计");
        req.setFieldScope("id");
        req.setDutyMatter("测试职责");
        req.setLegalBasis("测试依据");
        req.setPhone("test-phone");
        req.setEmail("test@example.invalid");
        req.setApplyNote("测试说明");
        req.setRemark("测试备注");
        req.setApplyStartTime(LocalDateTime.of(2030, 1, 1, 0, 0));
        req.setApplyEndTime(LocalDateTime.of(2030, 2, 1, 0, 0));
        req.setApplyUseType(1);
        req.setApplyUseTimes(42);
        req.setSubmit(submit);
        return req;
    }

    private void assertNoApplication() throws Exception {
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply")).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isZero();
        verifyNoInteractions(notifications, audit);
    }

    private static void deny(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(code));
    }
}
