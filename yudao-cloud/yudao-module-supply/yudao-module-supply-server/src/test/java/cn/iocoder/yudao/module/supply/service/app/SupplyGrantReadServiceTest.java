package cn.iocoder.yudao.module.supply.service.app;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.supply.api.app.SupplyGrantCommandCodec;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO;
import cn.iocoder.yudao.module.supply.dal.mysql.app.SupplyAppMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.AppApiMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.SupplyGrantOpMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.SupplyGrantSourceMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiMapper;
import cn.iocoder.yudao.module.supply.service.app.grant.EffectiveGrantSnapshot;
import cn.iocoder.yudao.module.supply.service.app.grant.GrantSourceState;
import cn.iocoder.yudao.module.supply.service.app.grant.SupplyGrantReadService;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class SupplyGrantReadServiceTest extends SharingPostgresSupport {
    private AnnotationConfigApplicationContext context;
    private SupplyGrantReadService reader;
    private AppApiMapper aggregates;
    private SupplyGrantSourceMapper sources;
    private SupplyGrantSourceMapper realSources;
    private AdminUserApi users;
    private AdminUserRespDTO user;
    private SupplyAppService apps;

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfig { }

    @BeforeEach
    void prepareReader() throws Exception {
        try (var input = getClass().getResourceAsStream("/sql/sharing-coordinator-prerequisites.sql")) {
            sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        migrate("0041_supply_grant-source-protocol.sql");
        migrate("0095_supply_etl-batch-consumption.sql");
        migrate("0169_datahub_remove-business-tenancy.sql");
        sql("ALTER TABLE dh_supply_api ADD COLUMN row_version int DEFAULT 1");
        // 补齐旧应用入口 BaseMapper 读取的字段；测试实际 Service，不模拟状态写入。
        sql("ALTER TABLE dh_supply_app ADD COLUMN name varchar(100), ADD COLUMN app_type varchar(32),"
                + " ADD COLUMN icon text, ADD COLUMN app_key varchar(100), ADD COLUMN secret text,"
                + " ADD COLUMN public_flag int, ADD COLUMN description text, ADD COLUMN remark text,"
                + " ADD COLUMN sign_protocol varchar(16), ADD COLUMN owning_dept_id bigint,"
                + " ADD COLUMN system_code varchar(100), ADD COLUMN owner_user_id bigint, ADD COLUMN purpose text,"
                + " ADD COLUMN environment varchar(100), ADD COLUMN credential_revision int, ADD COLUMN row_version int,"
                + " ADD COLUMN create_time timestamp, ADD COLUMN update_time timestamp,"
                + " ADD COLUMN creator varchar(64), ADD COLUMN updater varchar(64)");
        sql("INSERT INTO dh_supply_app_api(app_id,api_id,protocol_version,aggregate_revision,"
                + "migration_status,grant_status) VALUES(1,10,2,3,'CONFIRMED',3)");
        var datasource = new UnpooledDataSource("org.postgresql.Driver",
                jdbcUrl + "?currentSchema=datahub-center&socketTimeout=15&connectTimeout=5", jdbcUser, "");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true); // 保留默认 SESSION 缓存。
        for (var type : List.of(SupplyAppMapper.class, AppApiMapper.class, SupplyGrantSourceMapper.class,
                SupplyGrantOpMapper.class, DataApiMapper.class)) {
            config.addMapper(type);
        }
        config.setEnvironment(new Environment("sharing-read", new SpringManagedTransactionFactory(), datasource));
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        aggregates = mock(AppApiMapper.class, org.mockito.AdditionalAnswers.delegatesTo(session.getMapper(AppApiMapper.class)));
        realSources = session.getMapper(SupplyGrantSourceMapper.class);
        sources = mock(SupplyGrantSourceMapper.class, org.mockito.AdditionalAnswers.delegatesTo(realSources));
        users = mock(AdminUserApi.class);
        user = new AdminUserRespDTO(); user.setId(7L); user.setStatus(0);
        when(users.getUser(7L)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return CommonResult.success(user);
        });
        context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfig.class);
        context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(datasource));
        context.registerBean(SupplyAppMapper.class, () -> session.getMapper(SupplyAppMapper.class));
        context.registerBean(AppApiMapper.class, () -> aggregates);
        context.registerBean(SupplyGrantSourceMapper.class, () -> sources);
        context.registerBean(DataApiMapper.class, () -> session.getMapper(DataApiMapper.class));
        context.registerBean(SupplyGrantOpMapper.class, () -> session.getMapper(SupplyGrantOpMapper.class));
        context.getBeanFactory().registerSingleton("adminUserApi", users);
        context.registerBean(SupplyGrantReadService.class);
        context.registerBean(SupplyAppServiceImpl.class);
        context.getBeanFactory().registerSingleton("etlGrantPreflight", mock(SupplyEtlGrantPreflight.class));
        context.getBeanFactory().registerSingleton("etlGrantAudit", mock(SupplyEtlGrantAuditService.class));
        context.refresh();
        reader = context.getBean(SupplyGrantReadService.class);
        apps = context.getBean(SupplyAppService.class);
        source("A", 1, "ACTIVE", List.of("id","name"), 0, null);
        source("B", 2, "ACTIVE", List.of("id","phone"), 0, null);
    }

    @AfterEach
    void closeContext() {

        if (context != null) context.close();
    }

    @Test
    void snapshotUsesDatabaseTimeAndUnionOfCompatibleSources() throws Exception {
        long before = scalar("SELECT floor(extract(epoch FROM clock_timestamp())*1000)::bigint");
        var snapshot = snapshot();
        long after = scalar("SELECT floor(extract(epoch FROM clock_timestamp())*1000)::bigint");
        assertThat(snapshot.approvedColumns()).containsExactly("id","name","phone");
        assertThat(snapshot.sourceVersions()).containsExactlyInAnyOrderEntriesOf(Map.of(
                new GrantSourceState.Identity("asset","A"),1L,new GrantSourceState.Identity("asset","B"),2L));
        assertThat(snapshot.aggregateRevision()).isEqualTo(3);
        assertThat(snapshot.apiControlVersion()).isEqualTo(1);
        assertThat(snapshot.permissionUserId()).isEqualTo(7);
        assertThat(snapshot.binding()).isEqualTo(new SupplyGrantSourceCommandDTO.Binding(9L,"public","people"));
        assertThat(snapshot.evaluatedAt().toEpochMilli()).isBetween(before, after);
        assertThat(snapshot.fieldScoped()).isTrue();
        assertThat(snapshot.nextBoundary()).isNull();
    }

    @Test
    void revokedSourceDoesNotRetainItsExclusiveFields() throws Exception {
        sql("UPDATE dh_supply_grant_source SET source_status='REVOKED' WHERE source_key='A'");
        assertThat(snapshot().approvedColumns()).containsExactly("id","phone");
    }

    @Test
    void noEffectiveSourceIsDeniedInsteadOfAllColumns() throws Exception {
        sql("UPDATE dh_supply_grant_source SET source_status='SUSPENDED'");
        assertCode(() -> reader.snapshot(1,10), 1_500_042_009);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE dh_supply_app SET app_status=0",
            "UPDATE dh_supply_app SET deleted=1",
            "UPDATE dh_supply_api SET status=0",
            "UPDATE dh_supply_api SET deleted=1",
            "UPDATE dh_supply_app_api SET aggregate_disabled=true",
            "UPDATE dh_supply_app_api SET deleted=1"})
    void inactiveOrDeletedControlsDenyRead(String change) throws Exception {
        sql(change);
        assertCode(() -> reader.snapshot(1,10), 1_500_042_009);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE dh_supply_app_api SET protocol_version=0",
            "UPDATE dh_supply_app_api SET migration_status='REVIEW_REQUIRED'",
            "UPDATE dh_supply_api SET contract_hash='changed'",
            "UPDATE dh_supply_api SET version='v2'",
            "UPDATE dh_supply_api SET database_id=99",
            "UPDATE dh_supply_api SET table_name='other'",
            "UPDATE dh_supply_api SET active_release_id=NULL",
            "UPDATE dh_supply_api SET config_type='SCRIPT'",
            "UPDATE dh_supply_api SET row_version=NULL",
            "UPDATE dh_supply_api SET row_version=-1"})
    void unconfirmedOrChangedContractCannotUseFrozenFields(String change) throws Exception {
        sql(change);
        assertCode(() -> reader.snapshot(1,10), 1_500_042_011);
    }

    @Test
    void malformedScopeFailsClosed() throws Exception {
        sql("UPDATE dh_supply_grant_source SET source_status='SUSPENDED',scope_json='{}'::jsonb WHERE source_key='A'");
        assertCode(() -> reader.snapshot(1,10), 1_500_042_011);
    }

    @Test
    void malformedScopeDoesNotLeakApprovalContentsToLogs() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(JsonUtils.class);
        var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start(); logger.addAppender(captured);
        try {
            sql("UPDATE dh_supply_grant_source SET source_status='SUSPENDED',"
                    + "scope_json='{\"purpose\":\"private-purpose-test-marker\"}'::jsonb WHERE source_key='A'");
            assertCode(() -> reader.snapshot(1,10), 1_500_042_011);
            assertThat(captured.list).noneMatch(event -> event.getFormattedMessage().contains("private-purpose-test-marker"));
        } finally {
            logger.detachAppender(captured); captured.stop();
        }
    }

    @Test
    void incompatibleStoredSourcesAreNotSilentlyMerged() throws Exception {
        sql("UPDATE dh_supply_grant_source SET scope_json=jsonb_set(scope_json,'{purpose}','\"other\"') WHERE source_key='B'");
        assertCode(() -> reader.snapshot(1,10), 1_500_042_011);
    }

    @Test
    void sameLogicalReadDoesNotMixOldAggregateAndNewSources() throws Exception {
        var once = new AtomicBoolean();
        doAnswer(call -> {
            if (once.compareAndSet(false,true)) {
                // 独立 JDBC 连接在读取聚合后、读取来源前提交，不能被权限读取锁阻塞。
                sql("SET statement_timeout='2s'; BEGIN; UPDATE dh_supply_app_api SET aggregate_revision=4;"
                        + "UPDATE dh_supply_grant_source SET source_revision=3,source_status='REVOKED' WHERE source_key='A'; COMMIT");
            }
            return realSources.selectAggregateSources(call.getArgument(0),call.getArgument(1));
        }).when(sources).selectAggregateSources(1,10);
        var frozen = snapshot();
        assertThat(frozen.aggregateRevision()).isEqualTo(3);
        assertThat(frozen.approvedColumns()).containsExactly("id","name","phone");
        assertCode(() -> reader.assertStillCurrent(frozen),1_500_042_010);
    }

    @Test
    void finalCheckAllowsUnchangedScope() {
        var frozen = snapshot();
        assertThatCode(() -> reader.assertStillCurrent(frozen)).doesNotThrowAnyException();
    }

    @Test
    void finalCheckRejectsAppDisabledThenReenabledInSeparateCommits() throws Exception {
        var frozen = snapshot();
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(apps)).isTrue();
        apps.updateAppStatus(1L, 0);
        assertThat(scalar("SELECT app_status FROM dh_supply_app WHERE id=1")).isZero();
        apps.updateAppStatus(1L, 1);
        assertThat(scalar("SELECT app_status FROM dh_supply_app WHERE id=1")).isEqualTo(1);
        assertCode(() -> reader.assertStillCurrent(frozen), 1_500_042_010);
        assertThat(scalar("SELECT aggregate_revision FROM dh_supply_app_api WHERE app_id=1")).isEqualTo(5);
    }

    @Test
    void finalCheckRejectsApiRepublishedWithIdenticalContract() throws Exception {
        var frozen = snapshot();
        // 模拟实际发布流程的两个独立提交：合同/业务版本相同，发布行版本递增。
        sql("UPDATE dh_supply_api SET status=0 WHERE id=10");
        sql("UPDATE dh_supply_api SET status=1,active_release_id=101,row_version=2 WHERE id=10");
        assertCode(() -> reader.assertStillCurrent(frozen), 1_500_042_010);
    }

    @Test
    void finalCheckRejectsApiRolledBackToOriginalRelease() throws Exception {
        var frozen = snapshot();
        sql("UPDATE dh_supply_api SET contract_hash='changed',active_release_id=101,row_version=2 WHERE id=10");
        sql("UPDATE dh_supply_api SET contract_hash='approved-hash',active_release_id=100,row_version=3 WHERE id=10");
        assertCode(() -> reader.assertStillCurrent(frozen), 1_500_042_010);
    }

    @Test
    void failedControlVersionUpdateRollsBackAppStatus() throws Exception {
        sql("ALTER TABLE dh_supply_app_api ADD CONSTRAINT test_reject_revision CHECK (aggregate_revision < 4)");
        assertThatThrownBy(() -> apps.updateAppStatus(1L, 0))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(scalar("SELECT app_status FROM dh_supply_app WHERE id=1")).isEqualTo(1);
        assertThat(scalar("SELECT aggregate_revision FROM dh_supply_app_api WHERE app_id=1")).isEqualTo(3);
    }

    @Test
    void unchangedAppStatusDoesNotInvalidateSnapshots() throws Exception {
        var frozen = snapshot();
        apps.updateAppStatus(1L, 1);
        assertThat(scalar("SELECT aggregate_revision FROM dh_supply_app_api WHERE app_id=1")).isEqualTo(3);
        assertThatCode(() -> reader.assertStillCurrent(frozen)).doesNotThrowAnyException();
    }

    @Test
    void appStatusChangeInvalidatesOnlyItsOwnV2Aggregates() throws Exception {
        sql("INSERT INTO dh_supply_app_api(app_id,api_id,protocol_version,aggregate_revision,"
                + "migration_status,grant_status) VALUES (1,20,2,8,'CONFIRMED',3),"
                + "(2,20,2,8,'CONFIRMED',3),(3,20,2,8,'CONFIRMED',3),"
                + "(1,30,0,0,'REVIEW_REQUIRED',3)");
        apps.updateAppStatus(1L, 0);
        assertThat(scalar("SELECT aggregate_revision FROM dh_supply_app_api WHERE app_id=1 AND api_id=20")).isEqualTo(9);
        assertThat(scalar("SELECT aggregate_revision FROM dh_supply_app_api WHERE app_id=2")).isEqualTo(8);
        assertThat(scalar("SELECT aggregate_revision FROM dh_supply_app_api WHERE app_id=3")).isEqualTo(8);
        assertThat(scalar("SELECT aggregate_revision FROM dh_supply_app_api WHERE app_id=1 AND api_id=30")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE dh_supply_app_api SET aggregate_revision=4",
            "UPDATE dh_supply_grant_source SET source_revision=4 WHERE source_key='A'",
            "UPDATE dh_supply_grant_source SET source_status='REVOKED' WHERE source_key='A'",
            "UPDATE dh_supply_app SET app_status=0",
            "UPDATE dh_supply_api SET contract_hash='changed'"})
    void finalCheckDiscardsResultsWhenAuthorizationChanges(String change) throws Exception {
        var frozen = snapshot();
        sql(change);
        assertCode(() -> reader.assertStillCurrent(frozen),1_500_042_010);
    }

    @Test
    void exactExpiryIsRejectedEvenWhenRemainingFieldsAreIdentical() throws Exception {
        sql("DELETE FROM dh_supply_grant_source");
        source("A",1,"ACTIVE",List.of("id"),0,null);
        source("B",1,"ACTIVE",List.of("id"),0,2000L);
        doReturn(1000L).when(aggregates).databaseTimeMillis();
        var frozen = snapshot();
        assertThat(frozen.nextBoundary()).isEqualTo(Instant.ofEpochMilli(2000));
        doReturn(2000L).when(aggregates).databaseTimeMillis();
        assertCode(() -> reader.assertStillCurrent(frozen),1_500_042_010);
        assertThat(snapshot().approvedColumns()).containsExactly("id");
    }

    @Test
    void futureSourceDoesNotFillPermissionGap() throws Exception {
        sql("DELETE FROM dh_supply_grant_source");
        source("past",1,"ACTIVE",List.of("name"),0,1000L);
        source("future",1,"ACTIVE",List.of("phone"),2000,null);
        doReturn(1500L).when(aggregates).databaseTimeMillis();
        assertCode(() -> reader.snapshot(1,10),1_500_042_009);
        doReturn(2000L).when(aggregates).databaseTimeMillis();
        assertThat(snapshot().approvedColumns()).containsExactly("phone");
    }

    @Test
    void finalCheckRejectsUserDisabledDuringExternalQuery() {
        var frozen = snapshot();
        user.setStatus(1);
        assertCode(() -> reader.assertStillCurrent(frozen),1_500_042_012);
    }

    @Test
    void unavailableUserServiceCannotReuseEarlierApproval() {
        when(users.getUser(7L)).thenThrow(new IllegalStateException("test user service timeout"));
        assertThatThrownBy(() -> reader.snapshot(1,10)).hasMessage("test user service timeout");
    }

    @Test
    void receiptAndSourceCachesFromOuterTransactionCannotStaleFinalRead() throws Exception {
        var frozen = snapshot();
        var outer = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.executeWithoutResult(status -> {
            realSources.selectAggregateSources(1,10); // 外层已建立旧 MVCC 快照和 SESSION 缓存。
            try {
                sql("UPDATE dh_supply_grant_source SET source_revision=3,source_status='REVOKED' WHERE source_key='A'");
            } catch (Exception e) { throw new AssertionError(e); }
            assertCode(() -> reader.assertStillCurrent(frozen),1_500_042_010);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        });
    }

    @Test
    void storageFailureDoesNotReturnPreviouslyReadSnapshot() throws Exception {
        snapshot();
        sql("ALTER TABLE dh_supply_grant_source RENAME TO unavailable_sources");
        assertThatThrownBy(() -> reader.snapshot(1,10)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void unknownApplicationCannotReadAnotherApplicationGrant() {
        assertCode(() -> reader.snapshot(999, 10), 1_500_042_009);
    }

    @Test
    void callerCannotSubstituteMoreFieldsIntoSnapshot() {
        var frozen = snapshot();
        var substituted = new EffectiveGrantSnapshot(1,10,3,7L,frozen.binding(),"approved-hash","v1",
                List.of("id","name","phone","secret"),frozen.sourceVersions(),frozen.evaluatedAt(),null,true,
                frozen.apiControlVersion());
        assertCode(() -> reader.assertStillCurrent(substituted),1_500_042_010);
    }

    private EffectiveGrantSnapshot snapshot() {
        var result = reader.snapshot(1,10);
        assertThat(result).as("authoritative immutable grant snapshot").isNotNull();
        return result;
    }

    private void source(String key, long revision, String status, List<String> fields, long from, Long to) throws Exception {
        var scope = new SupplyGrantSourceCommandDTO.Scope("approval","PERSONAL",7L,
                new SupplyGrantSourceCommandDTO.Binding(9L,"public","people"),
                "approved-hash","v1","purpose","duty","CURRENT_USER_GOVERNANCE",fields,to == null,from,to);
        try (var statement = connection.prepareStatement("INSERT INTO dh_supply_grant_source "
                + "(source_system,source_key,app_id,api_id,source_revision,source_status,scope_json,payload_hash,compatibility_key)"
                + " VALUES('asset',?,1,10,?,?,CAST(? AS jsonb),'fixture',?)")) {
            statement.setString(1,key); statement.setLong(2,revision); statement.setString(3,status);
            statement.setString(4,JsonUtils.toJsonString(scope));
            statement.setString(5,SupplyGrantCommandCodec.compatibilityHash(scope));
            statement.executeUpdate();
        }
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class, failure ->
                assertThat(failure.getCode()).isEqualTo(code));
    }
}
