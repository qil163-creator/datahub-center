package cn.iocoder.yudao.module.supply.service.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO;
import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyApiBindingRespDTO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.dal.mysql.app.SupplyAppMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.*;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiMapper;
import cn.iocoder.yudao.module.supply.service.app.grant.SupplyGrantCoordinator;
import cn.iocoder.yudao.module.supply.service.dataapi.DataApiService;
import cn.iocoder.yudao.module.supply.service.dataapi.SupplyApiBindingService;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO.Action.*;
import static cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceResultDTO.Outcome.*;

@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class SupplyGrantCoordinatorTest extends SharingPostgresSupport {
    private AnnotationConfigApplicationContext context;
    private SupplyGrantCoordinator coordinator;
    private AdminUserApi users;
    private DataApiService apis;
    private SupplyApiBindingService bindings;
    private SupplyGrantOpMapper operations;
    private SupplyGrantOpMapper realOperations;
    private SupplyGrantSourceMapper sources;
    private SupplyGrantSourceMapper realSources;

    @BeforeEach
    void prepareCoordinator() throws Exception {
        try (var input = getClass().getResourceAsStream("/sql/sharing-coordinator-prerequisites.sql")) {
            sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        migrate("0041_supply_grant-source-protocol.sql");
        migrate("0169_datahub_remove-business-tenancy.sql");
        var datasource = new UnpooledDataSource("org.postgresql.Driver",
                jdbcUrl + "?currentSchema=datahub-center&socketTimeout=15&connectTimeout=5", "sharing_test", "");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        // 保留 MyBatis 生产默认 SESSION；不能用 STATEMENT 掩盖挂起会话的陈旧回执缓存。
        for (var type : List.of(SupplyAppMapper.class, AppApiMapper.class, SupplyGrantSourceMapper.class,
                SupplyGrantOpMapper.class, DataApiMapper.class)) config.addMapper(type);
        config.setEnvironment(new Environment("sharing-coordinator", new SpringManagedTransactionFactory(), datasource));
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        realOperations = session.getMapper(SupplyGrantOpMapper.class);
        operations = mock(SupplyGrantOpMapper.class, org.mockito.AdditionalAnswers.delegatesTo(realOperations));
        realSources = session.getMapper(SupplyGrantSourceMapper.class);
        sources = mock(SupplyGrantSourceMapper.class, org.mockito.AdditionalAnswers.delegatesTo(realSources));
        users = mock(AdminUserApi.class);
        apis = mock(DataApiService.class);
        bindings = mock(SupplyApiBindingService.class);
        var user = new AdminUserRespDTO();
        user.setId(7L); user.setStatus(0);
        when(users.getUser(7L)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return CommonResult.success(user);
        });
        when(apis.getPublishedDataApiEntity(anyLong())).thenAnswer(call -> {
            var api = new DataApiDO(); api.setId(call.getArgument(0)); return api;
        });
        when(bindings.resolve(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var binding = new SupplyApiBindingRespDTO();
            binding.setId(((DataApiDO)call.getArgument(0)).getId());
            binding.setStatus(1); binding.setConfigType("TABLE"); binding.setApiType("QUERY");
            binding.setDatabaseId(9L); binding.setSchemaName("public"); binding.setTableName("people");
            binding.setContractHash("approved-hash"); binding.setVersion("v1");
            binding.setActiveReleaseId(binding.getId() == 10L ? 100L : 200L);
            binding.setFieldMappingVerified(true);
            binding.setOutputFieldMapping(Map.of("id","id","name","name","phone","phone"));
            return binding;
        });
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(datasource));
        context.registerBean(SupplyAppMapper.class, () -> session.getMapper(SupplyAppMapper.class));
        context.registerBean(AppApiMapper.class, () -> session.getMapper(AppApiMapper.class));
        context.registerBean(SupplyGrantSourceMapper.class, () -> sources);
        context.registerBean(SupplyGrantOpMapper.class, () -> operations);
        context.registerBean(DataApiMapper.class, () -> session.getMapper(DataApiMapper.class));
        context.getBeanFactory().registerSingleton("adminUserApi", users);
        context.getBeanFactory().registerSingleton("dataApiService", apis);
        context.getBeanFactory().registerSingleton("supplyApiBindingService", bindings);
        context.registerBean(SupplyGrantCoordinator.class);
        context.refresh();
        coordinator = context.getBean(SupplyGrantCoordinator.class);
        }

    @AfterEach
    void closeContext() {

        if (context != null) context.close();
    }

    @Test
    void twoSourcesUseIndependentRevisionsAndKeepDurableReceipts() throws Exception {
        assertThat(coordinator.apply( grant("a", "A", 1)).outcome()).isEqualTo(APPLIED);
        var b = coordinator.apply( grant("b", "B", 1));
        assertThat(b.outcome()).isEqualTo(APPLIED);
        assertThat(b.aggregateRevision()).isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_source WHERE source_status='ACTIVE'")).isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_op WHERE response_json IS NOT NULL")).isEqualTo(2);
        // 尚未接入字段投影的旧网关不能把新的来源聚合当成全字段旧授权。
        assertThat(scalar("SELECT grant_status FROM dh_supply_app_api")).isEqualTo(3);
    }

    @Test
    void replayDoesNotRevalidateChangedContractOrDisabledUser() throws Exception {
        var command = grant("a", "A", 1);
        var original = coordinator.apply( command);
        assertThat(original.outcome()).isEqualTo(APPLIED);
        sql("UPDATE dh_supply_api SET contract_hash='changed',status=0");
        when(users.getUser(7L)).thenThrow(new AssertionError("replay must not contact user service"));
        doThrow(new AssertionError("replay must not revalidate metadata")).when(bindings).resolve(any());
        assertThat(coordinator.apply( command)).isEqualTo(original);
        assertThat(coordinator.query( "asset", "a")).isEqualTo(original);
        assertThat(scalar("SELECT aggregate_revision FROM dh_supply_app_api")).isEqualTo(1);
    }

    @Test
    void conflictingOperationCannotOverwriteOriginalReceipt() {
        var original = coordinator.apply( grant("a", "A", 1));
        assertThat(original.outcome()).isEqualTo(APPLIED);
        assertThat(coordinator.apply( grant("a", "B", 1)).outcome()).isEqualTo(REJECTED);
        assertThat(coordinator.query( "asset", "a")).isEqualTo(original);
    }

    @Test
    void revokeStillWorksAfterAppAndApiDisableWithoutRemoteValidation() throws Exception {
        assertThat(coordinator.apply( grant("a", "A", 1)).outcome()).isEqualTo(APPLIED);
        sql("UPDATE dh_supply_app SET app_status=0; UPDATE dh_supply_api SET status=0,contract_hash='changed'");
        when(users.getUser(anyLong())).thenThrow(new AssertionError("revoke must not query users"));
        doThrow(new AssertionError("revoke must not query metadata")).when(bindings).resolve(any());
        assertThat(coordinator.apply( command("revoke", "A", 2, REVOKE, null)).outcome()).isEqualTo(APPLIED);
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_source WHERE source_status='REVOKED'")).isEqualTo(1);
    }

    @Test
    void revokeFirstCreatesTombstoneAndOldGrantCannotResurrect() {
        assertThat(coordinator.apply( command("stop", "A", 5, REVOKE, null)).outcome()).isEqualTo(APPLIED);
        assertThat(coordinator.apply( grant("late", "A", 4)).outcome()).isEqualTo(STALE);
        assertThat(coordinator.apply( grant("newer", "A", 6)).errorCode()).isEqualTo("TERMINAL_SOURCE");
        assertThat(coordinator.query( "asset", "newer").outcome()).isEqualTo(REJECTED);
    }

    @Test
    void failureSavingReceiptRollsBackSourceAndAggregate() throws Exception {
        sql("ALTER TABLE dh_supply_grant_op ADD CONSTRAINT fail_receipt CHECK(operation_id <> 'fail')");
        assertThatThrownBy(() -> coordinator.apply( grant("fail", "A", 1))).isInstanceOf(RuntimeException.class);
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_source")).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_supply_app_api")).isZero();
        assertThat(coordinator.query( "asset", "fail").outcome()).isEqualTo(NOT_FOUND);
    }

    @Test
    void concurrentFirstAggregateRetainsBothSources() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> inWorker(() -> { await(start); return coordinator.apply( grant("a","A",1)); }));
            var b = pool.submit(() -> inWorker(() -> { await(start); return coordinator.apply( grant("b","B",1)); }));
            start.countDown();
            assertThat(a.get(20, TimeUnit.SECONDS).outcome()).isEqualTo(APPLIED);
            assertThat(b.get(20, TimeUnit.SECONDS).outcome()).isEqualTo(APPLIED);
        }
        assertThat(scalar("SELECT count(*) FROM dh_supply_app_api")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_source")).isEqualTo(2);
        assertThat(scalar("SELECT aggregate_revision FROM dh_supply_app_api")).isEqualTo(2);
    }

    @Test
    void sameOperationAcrossAggregatesRollsBackLosingMutation() throws Exception {
        var start = new CountDownLatch(1);
        var bothMutated = new CountDownLatch(2);
        doAnswer(call -> {
            bothMutated.countDown(); await(bothMutated);
            return realOperations.insertSourceReceipt(call.getArgument(0));
        }).when(operations).insertSourceReceipt(any());
        var first = grant("same","A",1);
        var second = new SupplyGrantSourceCommandDTO(2,"same","asset","B",1,2L,20L,GRANT,scope(),null);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> inWorker(() -> { await(start); return coordinator.apply(first); }));
            var b = pool.submit(() -> inWorker(() -> { await(start); return coordinator.apply(second); }));
            start.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS).outcome(), b.get(20, TimeUnit.SECONDS).outcome()))
                    .containsExactlyInAnyOrder(APPLIED,REJECTED);
        }
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_source")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_op")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_supply_app_api")).isEqualTo(1);
    }

    @Test
    void incompatibleFutureSourceIsRejectedDurably() throws Exception {
        assertThat(coordinator.apply(grant("a","A",1)).outcome()).isEqualTo(APPLIED);
        var other = new SupplyGrantSourceCommandDTO.Scope("approval","PERSONAL",7L,scope().binding(),
                "approved-hash","v1","different-purpose","duty","CURRENT_USER_GOVERNANCE",List.of("id"),
                false,4102444800000L,4102444805000L);
        var result = coordinator.apply(command("b","B",1,GRANT,other));
        assertThat(result.errorCode()).isEqualTo("INCOMPATIBLE_SCOPE");
        assertThat(coordinator.query("asset","b")).isEqualTo(result);
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_source")).isEqualTo(1);
    }

    @Test
    void existingLegacyAggregateIsNotSilentlyUpgraded() throws Exception {
        sql("INSERT INTO dh_supply_app_api(app_id,api_id) VALUES(1,10)");
        assertThat(coordinator.apply(grant("a","A",1)).errorCode()).isEqualTo("REVIEW_REQUIRED");
        assertThat(scalar("SELECT protocol_version FROM dh_supply_app_api")).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_source")).isZero();
    }

    @Test
    void localContractChangeDuringPreflightRejectsGrant() throws Exception {
        when(users.getUser(7L)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            sql("UPDATE dh_supply_api SET contract_hash='changed'");
            var user = new AdminUserRespDTO(); user.setId(7L); user.setStatus(0);
            return CommonResult.success(user);
        });
        assertThat(coordinator.apply(grant("a","A",1)).errorCode()).isEqualTo("CONTRACT_MISMATCH");
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_source")).isZero();
    }

    @Test
    void missingRemoteProofNeverBecomesAllColumns() throws Exception {
        doReturn(new SupplyApiBindingRespDTO()).when(bindings).resolve(any());
        assertThat(coordinator.apply(grant("a","A",1)).outcome()).isEqualTo(REJECTED);
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_source")).isZero();
        assertThat(coordinator.query("asset","a").outcome()).isEqualTo(REJECTED);
    }

    @Test
    void receiptAndCommandIdentityAreGlobal() {
        var command = grant("a", "A", 1);
        var result = coordinator.apply(command);
        assertThat(result.outcome()).isEqualTo(APPLIED);
        assertThat(coordinator.query("asset", "a")).isEqualTo(result);
        assertThat(coordinator.apply(command)).isEqualTo(result);
    }

    @Test
    void sourceIdentityCannotMoveBetweenAggregatesEvenWhenBothInitiallySeeNoSource() throws Exception {
        var bothReady = new CountDownLatch(2);
        doAnswer(call -> {
            bothReady.countDown(); await(bothReady);
            return realSources.insertSource(call.getArgument(0));
        }).when(sources).insertSource(any());
        var first = grant("a", "same-source", 1);
        var second = new SupplyGrantSourceCommandDTO(2,"b","asset","same-source",1,2L,20L,GRANT,scope(),null);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> inWorker(() -> coordinator.apply( first)));
            var b = pool.submit(() -> inWorker(() -> coordinator.apply( second)));
            var results = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertThat(results).extracting(r -> r.outcome()).containsExactlyInAnyOrder(APPLIED, REJECTED);
            assertThat(results.stream().filter(r -> r.outcome() == REJECTED).findFirst().orElseThrow().errorCode())
                    .isEqualTo("IDENTITY_MISMATCH");
        }
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_source")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_supply_app_api")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_op")).isEqualTo(2);
    }

    @Test
    void ambientTransactionIsSuspendedAndReceiptSurvivesItsRollback() throws Exception {
        new TransactionTemplate(context.getBean(DataSourceTransactionManager.class)).executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(coordinator.apply( grant("a","A",1)).outcome()).isEqualTo(APPLIED);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            status.setRollbackOnly();
        });
        assertThat(coordinator.query("asset","a").outcome()).isEqualTo(APPLIED);
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_source")).isEqualTo(1);
    }

    @Test
    void revokeAfterSoftDeletionStillCreatesDurableTerminalReceipt() throws Exception {
        assertThat(coordinator.apply(grant("a","A",1)).outcome()).isEqualTo(APPLIED);
        sql("UPDATE dh_supply_app SET deleted=1; UPDATE dh_supply_api SET deleted=1");
        doThrow(new AssertionError("no remote proof on revoke")).when(bindings).resolve(any());
        assertThat(coordinator.apply(command("stop","A",2,REVOKE,null)).outcome()).isEqualTo(APPLIED);
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_source WHERE source_status='REVOKED'")).isEqualTo(1);
    }

    @Test
    void suspendResumeAndLongRevisionNeverResetAggregateDisable() throws Exception {
        assertThat(coordinator.apply(grant("a","A",2147483648L)).outcome()).isEqualTo(APPLIED);
        sql("UPDATE dh_supply_app_api SET aggregate_disabled=true");
        assertThat(coordinator.apply(command("pause","A",2147483649L,SUSPEND,null)).outcome()).isEqualTo(APPLIED);
        assertThat(coordinator.apply(grant("resume","A",2147483650L)).outcome()).isEqualTo(APPLIED);
        assertThat(scalar("SELECT source_revision FROM dh_supply_grant_source")).isEqualTo(2147483650L);
        assertThat(scalar("SELECT count(*) FROM dh_supply_app_api WHERE aggregate_disabled")).isEqualTo(1);
        assertThat(scalar("SELECT aggregate_revision FROM dh_supply_app_api")).isEqualTo(3);
    }

    @Test
    void transportFailureLeavesNoFalseRejectionAndCanBeRetried() throws Exception {
        when(users.getUser(7L)).thenThrow(new IllegalStateException("test transport timeout"));
        assertThatThrownBy(() -> coordinator.apply(grant("a","A",1))).hasMessage("test transport timeout");
        assertThat(coordinator.query("asset","a").outcome()).isEqualTo(NOT_FOUND);
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_source")).isZero();
        var user = new AdminUserRespDTO(); user.setId(7L); user.setStatus(0);
        doReturn(CommonResult.success(user)).when(users).getUser(7L);
        assertThat(coordinator.apply(grant("a","A",1)).outcome()).isEqualTo(APPLIED);
    }

    @Test
    void receiptStorageFailureCannotBeReportedAsNotFound() throws Exception {
        sql("ALTER TABLE dh_supply_grant_op RENAME TO unavailable_receipts");
        assertThatThrownBy(() -> coordinator.query("asset","a")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void grantWithDeletedAppIsRejectedWithoutSourceMutation() throws Exception {
        sql("UPDATE dh_supply_app SET deleted=1");
        var result = coordinator.apply(grant("a","A",1));
        assertThat(result.errorCode()).isEqualTo("APP_DISABLED");
        assertThat(coordinator.query("asset","a")).isEqualTo(result);
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_source")).isZero();
    }

    @Test
    void platformCommandsNeedNoTenantContext() {
        var result = coordinator.apply(grant("a", "A", 1));
        assertThat(result.outcome()).isEqualTo(APPLIED);
        assertThat(coordinator.query("asset", "a")).isEqualTo(result);
    }

    @Test
    void legacyReceiptDoesNotGetReconstructedFromCurrentAggregate() throws Exception {
        sql("INSERT INTO dh_supply_grant_op(source_system,operation_id,payload_hash,aggregate_key,"
                + "target_status,result_status) VALUES('asset','legacy','legacy','1:10',1,'UNKNOWN')");
        assertThat(coordinator.query("asset","legacy").outcome()).isEqualTo(UNKNOWN);
    }

    @Test
    void receiptQueryRefreshesEarlierAbsenceInSuspendedSession() {
        var outside = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        outside.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        outside.executeWithoutResult(status -> {
            assertThat(coordinator.query("asset","a").outcome()).isEqualTo(NOT_FOUND);
            var receipt = coordinator.apply( grant("a","A",1));
            assertThat(receipt.outcome()).isEqualTo(APPLIED);
            assertThat(coordinator.query("asset","a")).isEqualTo(receipt);
        });
    }

    @Test
    void secondUniqueConflictReadsWinnerAfterSourceRaceInsteadOfCachedAbsence() throws Exception {
        // 两次确定交错：首次输掉来源身份，重试持久化拒绝时再输掉 operationId。
        // 嵌套命令的 REQUIRES_NEW 使用独立连接，赢家真正提交后才让输家执行 INSERT。
        doAnswer(call -> {
            cn.iocoder.yudao.module.supply.dal.dataobject.appapi.SupplyGrantSourceDO row = call.getArgument(0);
            if (row.getAppId() == 1L) {
                var winner = new SupplyGrantSourceCommandDTO(2,"source-winner","asset","A",1,2L,20L,GRANT,scope(),null);
                assertThat(coordinator.apply(winner).outcome()).isEqualTo(APPLIED);
            }
            return realSources.insertSource(row);
        }).when(sources).insertSource(any());
        doAnswer(call -> {
            cn.iocoder.yudao.module.supply.dal.dataobject.appapi.SupplyGrantOpDO row = call.getArgument(0);
            if ("victim".equals(row.getOperationId()) && "1:10".equals(row.getAggregateKey())) {
                var winner = new SupplyGrantSourceCommandDTO(2,"victim","asset","B",1,2L,20L,GRANT,scope(),null);
                assertThat(coordinator.apply(winner).outcome()).isEqualTo(APPLIED);
            }
            return realOperations.insertSourceReceipt(row);
        }).when(operations).insertSourceReceipt(any());
        var result = coordinator.apply(grant("victim","A",1));
        assertThat(result.outcome()).isEqualTo(REJECTED);
        assertThat(result.errorCode()).isEqualTo("OPERATION_CONFLICT");
        assertThat(coordinator.query("asset","victim").outcome()).isEqualTo(APPLIED);
        assertThat(scalar("SELECT count(*) FROM dh_supply_app_api")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_source")).isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_op")).isEqualTo(2);
    }

    private static SupplyGrantSourceCommandDTO grant(String op, String source, long revision) {
        return command(op,source,revision,GRANT,scope());
    }
    private static SupplyGrantSourceCommandDTO command(String op, String source, long revision,
            SupplyGrantSourceCommandDTO.Action action, SupplyGrantSourceCommandDTO.Scope scope) {
        return new SupplyGrantSourceCommandDTO(2,op,"asset",source,revision,1L,10L,action,scope,null);
    }
    private static SupplyGrantSourceCommandDTO.Scope scope() {
        return new SupplyGrantSourceCommandDTO.Scope("approval","PERSONAL",7L,
                new SupplyGrantSourceCommandDTO.Binding(9L,"public","people"),
                "approved-hash","v1","purpose","duty","CURRENT_USER_GOVERNANCE",List.of("id","name"),
                true,0L,null);
    }
    private static <T> T inWorker(Supplier<T> action) {
        try { return action.get(); } finally { }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5,TimeUnit.SECONDS)) throw new AssertionError("start timeout"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
    }
}
