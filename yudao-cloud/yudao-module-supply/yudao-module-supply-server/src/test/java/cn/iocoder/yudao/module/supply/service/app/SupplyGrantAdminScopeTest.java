package cn.iocoder.yudao.module.supply.service.app;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.supply.api.app.SupplyGrantCommandCodec;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO;
import cn.iocoder.yudao.module.supply.controller.admin.app.vo.AppApiGrantScopeRespVO;
import cn.iocoder.yudao.module.supply.dal.mysql.app.SupplyAppMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.AppApiMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.SupplyGrantOpMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.SupplyGrantSourceMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiMapper;
import cn.iocoder.yudao.module.supply.service.app.grant.SupplyGrantReadService;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 权威授权状态读取与聚合控制开关。
 * 验证来源生命周期和并发读取边界，以及整体禁用、旧协议授权的不同语义。
 */
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class SupplyGrantAdminScopeTest extends SharingPostgresSupport {

    private AnnotationConfigApplicationContext context;
    private SupplyAppService apps;
    private SupplyGrantReadService grantReads;
    private AppApiMapper aggregates;
    private AppApiMapper realAggregates;
    private SupplyGrantSourceMapper sources;
    private SupplyGrantSourceMapper realSources;
    private AdminUserApi users;
    private AdminUserRespDTO user;

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfig { }

    @BeforeEach
    void prepareAggregate() throws Exception {
        for (String fixture : List.of("sharing-coordinator-prerequisites.sql", "sharing-legacy-entry-prerequisites.sql")) {
            try (var input = getClass().getResourceAsStream("/sql/" + fixture)) {
                sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        migrate("0041_supply_grant-source-protocol.sql");
        migrate("0095_supply_etl-batch-consumption.sql");
        migrate("0169_datahub_remove-business-tenancy.sql");
        // v2 市场聚合：grant_status 固定 3，真实范围只能来自来源账本。
        sql("INSERT INTO dh_supply_app_api(id,app_id,api_id,protocol_version,aggregate_revision,"
                + "migration_status,grant_status) VALUES(501,1,10,2,3,'CONFIRMED',3)");
        var datasource = new UnpooledDataSource("org.postgresql.Driver",
                jdbcUrl + "?currentSchema=datahub-center&socketTimeout=15&connectTimeout=5", jdbcUser, "");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        for (var type : List.of(SupplyAppMapper.class, AppApiMapper.class, SupplyGrantSourceMapper.class,
                SupplyGrantOpMapper.class, DataApiMapper.class)) {
            config.addMapper(type);
        }
        config.setEnvironment(new Environment("sharing-admin", new SpringManagedTransactionFactory(), datasource));
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        realAggregates = session.getMapper(AppApiMapper.class);
        aggregates = mock(AppApiMapper.class, org.mockito.AdditionalAnswers.delegatesTo(realAggregates));
        realSources = session.getMapper(SupplyGrantSourceMapper.class);
        sources = mock(SupplyGrantSourceMapper.class, org.mockito.AdditionalAnswers.delegatesTo(realSources));
        users = mock(AdminUserApi.class);
        user = new AdminUserRespDTO();
        user.setId(7L);
        user.setStatus(0);
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
        apps = context.getBean(SupplyAppService.class);
        grantReads = context.getBean(SupplyGrantReadService.class);
        source("A", 1, "ACTIVE", List.of("id", "name"), 0, null);
        source("B", 2, "ACTIVE", List.of("id", "phone"), 0, null);
    }

    @AfterEach
    void closeContext() {

        if (context != null) {
            context.close();
        }
    }

    @Test
    void effectiveViewReportsTheUnionActuallyUsedByQueries() {
        AppApiGrantScopeRespVO view = grantReads.describe( 501L);
        assertThat(view.getMode()).isEqualTo("ACTIVE");
        assertThat(view.getFieldScoped()).isTrue();
        assertThat(view.getApprovedColumns()).containsExactly("id", "name", "phone");
        assertThat(view.getAggregateRevision()).isEqualTo(3L);
        assertThat(view.getProtocolVersion()).isEqualTo(2);
        assertThat(view.getBlockReason()).isNull();
        assertThat(view.getSources()).hasSize(2);
        var first = view.getSources().get(0);
        assertThat(first.getSourceSystem()).isEqualTo("asset");
        assertThat(first.getSourceKey()).isEqualTo("A");
        assertThat(first.getSourceRevision()).isEqualTo(1L);
        assertThat(first.getStatus()).isEqualTo("ACTIVE");
        assertThat(first.getContributing()).isTrue();
        assertThat(first.getApprovedColumns()).containsExactly("id", "name");
        assertThat(first.getForever()).isTrue();
    }

    @Test
    void revokedSourceStaysVisibleButStopsContributingFields() throws Exception {
        sql("UPDATE dh_supply_grant_source SET source_status='REVOKED' WHERE source_key='A'");
        AppApiGrantScopeRespVO view = grantReads.describe( 501L);
        assertThat(view.getMode()).isEqualTo("ACTIVE");
        assertThat(view.getApprovedColumns()).containsExactly("id", "phone");
        var revoked = view.getSources().stream().filter(item -> "A".equals(item.getSourceKey())).findFirst().orElseThrow();
        assertThat(revoked.getStatus()).isEqualTo("REVOKED");
        assertThat(revoked.getContributing()).isFalse();
    }

    @Test
    void suspendedSourceIsNotPresentedAsGrantedFields() throws Exception {
        sql("UPDATE dh_supply_grant_source SET source_status='SUSPENDED' WHERE source_key='B'");
        AppApiGrantScopeRespVO view = grantReads.describe( 501L);
        assertThat(view.getApprovedColumns()).containsExactly("id", "name");
        var suspended = view.getSources().stream().filter(item -> "B".equals(item.getSourceKey())).findFirst().orElseThrow();
        assertThat(suspended.getStatus()).isEqualTo("SUSPENDED");
        assertThat(suspended.getContributing()).isFalse();
        assertThat(suspended.getApprovedColumns()).containsExactly("id", "phone");
    }

    @Test
    void aggregateDisabledIsNotReportedAsMissingAuthorization() throws Exception {
        sql("UPDATE dh_supply_app_api SET aggregate_disabled=true WHERE id=501");
        AppApiGrantScopeRespVO view = grantReads.describe( 501L);
        assertThat(view.getMode()).isEqualTo("DISABLED");
        assertThat(view.getBlockReason()).isEqualTo("AGGREGATE_DISABLED");
        assertThat(view.getApprovedColumns()).isEmpty();
        // 禁用只是聚合开关；来源账本仍要显示，否则管理员看不到解除禁用后会恢复什么。
        assertThat(view.getSources()).hasSize(2);
        assertRetainedButNonContributing(view);
    }

    @Test
    void expiredSourcesAreReportedAsNoEffectiveScope() throws Exception {
        sql("DELETE FROM dh_supply_grant_source");
        source("A", 1, "ACTIVE", List.of("id"), 0, 1000L);
        doReturn(5000L).when(aggregates).databaseTimeMillis();
        AppApiGrantScopeRespVO view = grantReads.describe( 501L);
        assertThat(view.getMode()).isEqualTo("NONE");
        assertThat(view.getBlockReason()).isEqualTo("NO_EFFECTIVE_SOURCE");
        assertThat(view.getApprovedColumns()).isEmpty();
        assertThat(view.getSources().get(0).getContributing()).isFalse();
    }

    @Test
    void unconfirmedEvidenceIsReportedForReviewInsteadOfActive() throws Exception {
        sql("UPDATE dh_supply_api SET contract_hash='republished' WHERE id=10");
        AppApiGrantScopeRespVO view = grantReads.describe( 501L);
        assertThat(view.getMode()).isEqualTo("REVIEW_REQUIRED");
        assertThat(view.getBlockReason()).isEqualTo("SCOPE_UNCONFIRMED");
        assertThat(view.getApprovedColumns()).isEmpty();
        assertRetainedButNonContributing(view);
    }

    @Test
    void disabledPermissionSubjectBlocksTheAggregate() {
        user.setStatus(1);
        AppApiGrantScopeRespVO view = grantReads.describe( 501L);
        assertThat(view.getMode()).isEqualTo("NONE");
        assertThat(view.getBlockReason()).isEqualTo("SUBJECT_INVALID");
        assertThat(view.getApprovedColumns()).isEmpty();
        assertRetainedButNonContributing(view);
    }

    @Test
    void inactiveAppIsDistinguishedFromRevokedSources() throws Exception {
        sql("UPDATE dh_supply_app SET app_status=0 WHERE id=1");
        AppApiGrantScopeRespVO view = grantReads.describe( 501L);
        assertThat(view.getMode()).isEqualTo("NONE");
        assertThat(view.getBlockReason()).isEqualTo("APP_OR_API_INACTIVE");
        assertRetainedButNonContributing(view);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE dh_supply_app_api SET migration_status='REVIEW_REQUIRED' WHERE id=501",
            "UPDATE dh_supply_grant_source SET source_status='REVIEW_REQUIRED' WHERE source_key='A'"})
    void reviewRequiredDoesNotAdvertiseOtherSourcesAsCurrentlyContributing(String change) throws Exception {
        sql(change);
        AppApiGrantScopeRespVO view = grantReads.describe( 501L);
        assertThat(view.getMode()).isEqualTo("REVIEW_REQUIRED");
        assertThat(view.getApprovedColumns()).isEmpty();
        assertRetainedButNonContributing(view);
    }

    @Test
    void legacyFinalReadRejectsRevocationDuringSourceDisplay() throws Exception {
        sql("UPDATE dh_supply_app_api SET protocol_version=0,source_type='ADMIN',"
                + "grant_status=1,forever_flag=1,permission_user_id=NULL,"
                + "approved_contract_hash='approved-hash' WHERE id=501");
        doAnswer(call -> {
            var rows = realSources.selectAggregateSources( 1, 10);
            sql("UPDATE dh_supply_app_api SET grant_status=3,aggregate_revision=aggregate_revision+1 WHERE id=501");
            return rows;
        }).when(sources).selectAggregateSources( 1, 10);
        assertCode(() -> grantReads.describe( 501L), 1_500_042_010);
        verifyNoInteractions(users);
    }

    @Test
    void legacyInitialContractMismatchMustNotBeAdvertisedAsActive() throws Exception {
        sql("UPDATE dh_supply_app_api SET protocol_version=0,source_type='ADMIN',"
                + "grant_status=1,forever_flag=1,permission_user_id=NULL,"
                + "approved_contract_hash='old-contract' WHERE id=501");
        var view = grantReads.describe( 501L);
        assertThat(view.getMode()).isEqualTo("REVIEW_REQUIRED");
        assertThat(view.getBlockReason()).isEqualTo("SCOPE_UNCONFIRMED");
        assertRetainedButNonContributing(view);
        assertThat(apps.getAppApiList(1L).getFirst().getAuthStatus()).isZero();
        verifyNoInteractions(users);
    }

    @Test
    void legacyAdminAvailabilityDoesNotMakeHistoricalSourceRowsContribute() throws Exception {
        sql("UPDATE dh_supply_app_api SET protocol_version=0,source_type='ADMIN',"
                + "grant_status=1,forever_flag=1,permission_user_id=NULL,"
                + "approved_contract_hash='approved-hash' WHERE id=501");
        AppApiGrantScopeRespVO view = grantReads.describe( 501L);
        assertThat(view.getMode()).isEqualTo("ACTIVE");
        assertThat(view.getFieldScoped()).isFalse();
        assertRetainedButNonContributing(view);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE dh_supply_app_api SET aggregate_disabled=true WHERE id=501",
            "UPDATE dh_supply_app_api SET deleted=1 WHERE id=501",
            "UPDATE dh_supply_app_api SET aggregate_revision=aggregate_revision+1 WHERE id=501",
            "UPDATE dh_supply_app_api SET grant_revision=99 WHERE id=501",
            "UPDATE dh_supply_app_api SET source_type='PERSONAL' WHERE id=501",
            "UPDATE dh_supply_app_api SET permission_user_id=7 WHERE id=501",
            "UPDATE dh_supply_app_api SET protocol_version=2 WHERE id=501",
            "UPDATE dh_supply_app_api SET app_id=2 WHERE id=501",
            "UPDATE dh_supply_app_api SET api_id=20 WHERE id=501",

            "UPDATE dh_supply_app_api SET valid_from=TIMESTAMP '2099-01-01' WHERE id=501",
            "UPDATE dh_supply_app_api SET forever_flag=0,end_date=DATE '2000-01-01' WHERE id=501",
            "UPDATE dh_supply_app_api SET approved_contract_hash='changed' WHERE id=501",
            "UPDATE dh_supply_app SET app_status=0 WHERE id=1",
            "UPDATE dh_supply_app SET deleted=1 WHERE id=1",
            "UPDATE dh_supply_app SET row_version=99 WHERE id=1",
            "UPDATE dh_supply_api SET status=0 WHERE id=10",
            "UPDATE dh_supply_api SET deleted=1 WHERE id=10",
            "UPDATE dh_supply_api SET contract_hash='changed' WHERE id=10",
            "UPDATE dh_supply_api SET row_version=row_version+1 WHERE id=10",
            "UPDATE dh_supply_api SET active_release_id=101 WHERE id=10"})
    void legacyFinalReadRejectsControlChangesDuringSourceDisplay(String change) throws Exception {
        makeLegacyAdminEffective();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var rows = realSources.selectAggregateSources( 1, 10);
            sql("SET statement_timeout='2s'");
            sql(change);
            return rows;
        }).when(sources).selectAggregateSources( 1, 10);
        assertCode(() -> grantReads.describe( 501L), 1_500_042_010);
        verify(sources).selectAggregateSources( 1, 10); // 不重试历史读取，也不写授权。
        verifyNoInteractions(users);
    }

    @Test
    void legacyFinalReadUsesDatabaseExpiryAndRejectsClockRegression() throws Exception {
        makeLegacyAdminEffective();
        long boundary = scalar("SELECT floor(extract(epoch FROM clock_timestamp())*1000)::bigint") - 1000;
        sql("UPDATE dh_supply_app_api SET valid_to=to_timestamp(" + boundary
                + "/1000.0) AT TIME ZONE 'Asia/Shanghai' WHERE id=501");
        doReturn(boundary - 1).doAnswer(call -> realAggregates.databaseTimeMillis())
                .when(aggregates).databaseTimeMillis();
        assertCode(() -> grantReads.describe( 501L), 1_500_042_010);
        sql("UPDATE dh_supply_app_api SET valid_to=NULL WHERE id=501");
        doReturn(2000L, 1999L).when(aggregates).databaseTimeMillis();
        assertCode(() -> grantReads.describe( 501L), 1_500_042_010);
        verifyNoInteractions(users);
    }

    @Test
    void legacyFinalStorageFailureCannotBecomeAnActiveView() throws Exception {
        makeLegacyAdminEffective();
        doAnswer(call -> {
            var rows = realSources.selectAggregateSources( 1, 10);
            doThrow(new IllegalStateException("legacy final read unavailable"))
                    .when(aggregates).selectGrantControlById( 501);
            return rows;
        }).when(sources).selectAggregateSources( 1, 10);
        assertThatThrownBy(() -> grantReads.describe( 501L))
                .isInstanceOf(IllegalStateException.class).hasMessage("legacy final read unavailable");
    }

    @Test
    void legacyFinalReadUsesPlatformScope() throws Exception {
        makeLegacyAdminEffective();
        assertThat(grantReads.describe(501L).getMode()).isEqualTo("ACTIVE");
    }

    @Test
    void legacyControlReadsUseShortReadonlySnapshotsAndRestoreOuterTransaction() throws Exception {
        makeLegacyAdminEffective();
        var reads = new AtomicInteger();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            var row = realAggregates.selectGrantControlById( 501);
            if (reads.incrementAndGet() == 1) {
                sql("UPDATE dh_supply_api SET status=0 WHERE id=10");
            }
            return row;
        }).when(aggregates).selectGrantControlById( 501);
        var outer = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.executeWithoutResult(status -> {
            realAggregates.selectGrantControlById( 501);
            assertCode(() -> grantReads.describe( 501L), 1_500_042_010);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
        });
        assertThat(reads.get()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NULL", "''", "'   '"})
    void legacyMissingCurrentContractKeepsExistingGatewayCompatibility(String contractSql) throws Exception {
        makeLegacyAdminEffective();
        sql("UPDATE dh_supply_api SET contract_hash=" + contractSql + " WHERE id=10");
        sql("UPDATE dh_supply_app_api SET approved_contract_hash=NULL,grant_status=NULL WHERE id=501");
        var view = grantReads.describe( 501L);
        assertThat(view.getMode()).isEqualTo("ACTIVE");
        assertThat(view.getFieldScoped()).isFalse();
        assertRetainedButNonContributing(view);
        verifyNoInteractions(users);
    }

    @Test
    void legacyListAlsoRejectsRevocationDuringItsRead() throws Exception {
        makeLegacyAdminEffective();
        doAnswer(call -> {
            var rows = realSources.selectAggregateSources( 1, 10);
            sql("UPDATE dh_supply_app_api SET grant_status=3 WHERE id=501");
            return rows;
        }).when(sources).selectAggregateSources( 1, 10);
        assertCode(() -> apps.getAppApiList(1L), 1_500_042_010);
    }

    private void makeLegacyAdminEffective() throws Exception {
        sql("UPDATE dh_supply_app_api SET protocol_version=0,source_type='ADMIN',"
                + "grant_status=1,forever_flag=1,permission_user_id=NULL,"
                + "approved_contract_hash='approved-hash' WHERE id=501");
    }

    @Test
    void legacyAdminDoesNotAcquireV2SourceDependenciesDuringFinalValidation() throws Exception {
        makeLegacyAdminEffective();
        doAnswer(call -> {
            sql("UPDATE dh_supply_grant_source SET source_status='REVOKED',source_revision=source_revision+1");
            return realSources.selectAggregateSources( 1, 10);
        }).when(sources).selectAggregateSources( 1, 10);
        var view = grantReads.describe( 501L);
        assertThat(view.getMode()).isEqualTo("ACTIVE");
        assertThat(view.getFieldScoped()).isFalse();
        assertRetainedButNonContributing(view);
        assertThat(view.getSources()).allSatisfy(source -> assertThat(source.getStatus()).isEqualTo("REVOKED"));
        assertThat(scalar("SELECT grant_status FROM dh_supply_app_api WHERE id=501")).isEqualTo(1);
        verifyNoInteractions(users);
    }

    @Test
    void sourceContributionUsesTheSameExpiryInstantAsTheAuthoritativeFieldUnion() throws Exception {
        sql("DELETE FROM dh_supply_grant_source");
        source("A", 1, "ACTIVE", List.of("name"), 0, 2000L);
        source("B", 2, "ACTIVE", List.of("phone"), 0, null);
        // describe 的初始时间早于边界，权威快照读取时 A 已到期；不能再用初始时间标记贡献。
        doReturn(1000L, 2000L).when(aggregates).databaseTimeMillis();
        AppApiGrantScopeRespVO view = grantReads.describe( 501L);
        assertThat(view.getMode()).isEqualTo("ACTIVE");
        assertThat(view.getApprovedColumns()).containsExactly("phone");
        assertThat(view.getSources().get(0).getApprovedColumns()).containsExactly("name");
        assertThat(view.getSources().get(0).getContributing()).isFalse();
        assertThat(view.getSources().get(1).getContributing()).isTrue();
    }

    @Test
    void aLaterSourceRevisionCannotReturnAnActiveEarlierSnapshot() {
        when(users.getUser(7L)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            sql("UPDATE dh_supply_grant_source SET source_revision=3 WHERE source_key='A'");
            sql("UPDATE dh_supply_app_api SET aggregate_revision=4 WHERE id=501");
            return CommonResult.success(user);
        });
        assertCode(() -> grantReads.describe( 501L), 1_500_042_010);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE dh_supply_grant_source SET source_status='REVOKED' WHERE source_key='A'",
            "UPDATE dh_supply_grant_source SET source_status='REVOKED'",
            "UPDATE dh_supply_grant_source SET source_status='SUSPENDED' WHERE source_key='B'",
            "UPDATE dh_supply_grant_source SET source_status='REVIEW_REQUIRED' WHERE source_key='A'",
            "UPDATE dh_supply_grant_source SET source_revision=source_revision+1 WHERE source_key='A'",
            "UPDATE dh_supply_app_api SET aggregate_disabled=true WHERE id=501",
            "UPDATE dh_supply_app_api SET deleted=1 WHERE id=501",
            "UPDATE dh_supply_app SET app_status=0 WHERE id=1",
            "UPDATE dh_supply_api SET status=0 WHERE id=10",
            "UPDATE dh_supply_api SET contract_hash='changed' WHERE id=10",
            "UPDATE dh_supply_api SET row_version=row_version+1 WHERE id=10",
            "UPDATE dh_supply_api SET active_release_id=101,row_version=row_version+1 WHERE id=10",
            "UPDATE dh_supply_app_api SET migration_status='REVIEW_REQUIRED' WHERE id=501"})
    void describeRejectsControlChangesDuringTheInitialSubjectRpc(String change) {
        var calls = new AtomicInteger();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (calls.incrementAndGet() == 1) {
                sql("SET statement_timeout='2s'");
                sql(change); // 独立 JDBC 连接实际提交，不模拟 readLocal 的返回值。
            }
            return CommonResult.success(user);
        }).when(users).getUser(7L);
        assertCode(() -> grantReads.describe( 501L), 1_500_042_010);
        verify(users, times(2)).getUser(7L); // 初读与返回前各一次，不进行重试。
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE dh_supply_app_api SET aggregate_disabled=true WHERE id=501",
            "UPDATE dh_supply_api SET row_version=row_version+1 WHERE id=10",
            "UPDATE dh_supply_grant_source SET source_revision=source_revision+1 WHERE source_key='A'"})
    void finalLocalReadMustFollowTheLastSubjectRpc(String change) {
        var calls = new AtomicInteger();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (calls.incrementAndGet() == 2) sql(change);
            return CommonResult.success(user);
        }).when(users).getUser(7L);
        assertCode(() -> grantReads.describe( 501L), 1_500_042_010);
        verify(users, times(2)).getUser(7L);
    }

    @Test
    void finalSubjectCheckRejectsAUserDisabledAfterTheInitialRpc() {
        var calls = new AtomicInteger();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (calls.incrementAndGet() == 2) user.setStatus(1);
            return CommonResult.success(user);
        }).when(users).getUser(7L);
        assertCode(() -> grantReads.describe( 501L), 1_500_042_012);
        verify(users, times(2)).getUser(7L);
    }

    @Test
    void finalSubjectFailureCannotReturnTheEarlierActiveView() {
        var calls = new AtomicInteger();
        doAnswer(call -> {
            if (calls.incrementAndGet() == 2) throw new IllegalStateException("final subject unavailable");
            return CommonResult.success(user);
        }).when(users).getUser(7L);
        assertThatThrownBy(() -> grantReads.describe( 501L))
                .isInstanceOf(IllegalStateException.class).hasMessage("final subject unavailable");
        verify(users, times(2)).getUser(7L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void expiryDuringTheReadRejectsEvenAnUnchangedFieldUnion(boolean sameFields) throws Exception {
        sql("DELETE FROM dh_supply_grant_source");
        long boundary = scalar("SELECT floor(extract(epoch FROM clock_timestamp())*1000)::bigint") - 1000;
        source("A", 1, "ACTIVE", sameFields ? List.of("id") : List.of("name"), 0, boundary);
        source("B", 2, "ACTIVE", List.of("id"), 0, null);
        // 只把初读固定在边界前；返回前必须经真实 PostgreSQL 时钟检测已跨界，不依赖 sleep。
        doReturn(boundary - 1, boundary - 1)
                .doAnswer(call -> realAggregates.databaseTimeMillis()).when(aggregates).databaseTimeMillis();
        assertCode(() -> grantReads.describe( 501L), 1_500_042_010);
        verify(aggregates, times(3)).databaseTimeMillis();
    }

    @Test
    void sourceCompanionReadCannotBypassTheFinalValidation() {
        var reads = new AtomicInteger();
        doAnswer(call -> {
            var rows = realSources.selectAggregateSources( 1, 10);
            if (reads.incrementAndGet() == 2) {
                // 第二次是历史展示读取；变更发生在初次主体校验之后，必须在返回前被发现。
                sql("UPDATE dh_supply_grant_source SET source_status='REVOKED' WHERE source_key='A'");
            }
            return rows;
        }).when(sources).selectAggregateSources( 1, 10);
        assertCode(() -> grantReads.describe( 501L), 1_500_042_010);
        assertThat(reads.get()).isEqualTo(3);
    }

    @Test
    void finalStorageFailureCannotReturnTheEarlierActiveView() {
        var calls = new AtomicInteger();
        doAnswer(call -> {
            if (calls.incrementAndGet() == 2) {
                doThrow(new IllegalStateException("final source read unavailable"))
                        .when(sources).selectAggregateSources( 1, 10);
            }
            return CommonResult.success(user);
        }).when(users).getUser(7L);
        assertThatThrownBy(() -> grantReads.describe( 501L))
                .isInstanceOf(IllegalStateException.class).hasMessage("final source read unavailable");
    }

    @Test
    void unchangedReadRetainsHistoricalFieldsAndContributionWithoutWritingState() throws Exception {
        sql("UPDATE dh_supply_grant_source SET source_status='SUSPENDED' WHERE source_key='B'");
        AppApiGrantScopeRespVO view = grantReads.describe( 501L);
        assertThat(view.getMode()).isEqualTo("ACTIVE");
        assertThat(view.getAggregateRevision()).isEqualTo(3L);
        assertThat(view.getApprovedColumns()).containsExactly("id", "name");
        assertThat(view.getSources().get(0).getSourceRevision()).isEqualTo(1L);
        assertThat(view.getSources().get(0).getApprovedColumns()).containsExactly("id", "name");
        assertThat(view.getSources().get(0).getContributing()).isTrue();
        assertThat(view.getSources().get(1).getContributing()).isFalse();
        assertThat(view.getSources().get(1).getApprovedColumns()).containsExactly("id", "phone");
        assertThat(scalar("SELECT aggregate_revision FROM dh_supply_app_api WHERE id=501")).isEqualTo(3);
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_source WHERE source_status='SUSPENDED'")).isEqualTo(1);
        verify(users, times(2)).getUser(7L);
    }

    @Test
    void describeCannotReuseACallersStaleControlSnapshot() {
        var outer = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.executeWithoutResult(status -> {
            realAggregates.selectGrantControlById( 501); // 建立外层 MVCC 快照。
            try {
                sql("UPDATE dh_supply_app_api SET aggregate_disabled=true WHERE id=501");
            } catch (Exception failure) { throw new AssertionError(failure); }
            var view = grantReads.describe( 501L);
            assertThat(view.getMode()).isEqualTo("DISABLED");
            assertRetainedButNonContributing(view);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            verifyNoInteractions(users);
        });
    }

    @Test
    void subjectRpcStillValidatesTheCurrentUser() {
        user.setStatus(1);
        var view = grantReads.describe(501L);
        assertThat(view.getMode()).isEqualTo("NONE");
        assertThat(view.getBlockReason()).isEqualTo("SUBJECT_INVALID");
    }

    @Test
    void listCannotReturnAnEffectiveFlagAfterRevocationDuringItsSubjectRpc() {
        doAnswer(call -> {
            sql("UPDATE dh_supply_grant_source SET source_status='REVOKED'");
            return CommonResult.success(user);
        }).when(users).getUser(7L);
        assertCode(() -> apps.getAppApiList(1L), 1_500_042_010);
    }

    @Test
    void legacyAdminGrantIsNeverPresentedAsFieldRestricted() throws Exception {
        sql("INSERT INTO dh_supply_app_api(id,app_id,api_id,protocol_version,aggregate_revision,"
                + "migration_status,grant_status,source_type,forever_flag)"
                + " VALUES(502,2,20,0,0,'REVIEW_REQUIRED',1,'ADMIN',1)");
        sql("UPDATE dh_supply_app_api SET approved_contract_hash='approved-hash' WHERE id=502");
        AppApiGrantScopeRespVO view = grantReads.describe( 502L);
        assertThat(view.getMode()).isEqualTo("ACTIVE");
        // 旧协议没有冻结字段证据：fieldScoped=false 才表示"字段未受限"，
        // 空列表不能被读成"批准了零个字段"，也不能被读成"批准了全部字段"。
        assertThat(view.getFieldScoped()).isFalse();
        assertThat(view.getProtocolVersion()).isZero();
        assertThat(view.getApprovedColumns()).isEmpty();
        assertThat(view.getBlockReason()).isNull();
        assertThat(view.getSources()).isEmpty();
        verifyNoInteractions(users);
    }

    @Test
    void legacyNonAdminAggregateRequiresReview() throws Exception {
        sql("INSERT INTO dh_supply_app_api(id,app_id,api_id,protocol_version,aggregate_revision,"
                + "migration_status,grant_status,source_type,forever_flag)"
                + " VALUES(503,2,20,0,0,'REVIEW_REQUIRED',1,'PERSONAL',1)");
        AppApiGrantScopeRespVO view = grantReads.describe( 503L);
        assertThat(view.getMode()).isEqualTo("REVIEW_REQUIRED");
        assertThat(view.getBlockReason()).isEqualTo("LEGACY_PROTOCOL_UNCONFIRMED");
        assertThat(view.getFieldScoped()).isFalse();
    }

    @Test
    void terminalAdminGrantIsReportedAsNone() throws Exception {
        sql("INSERT INTO dh_supply_app_api(id,app_id,api_id,protocol_version,aggregate_revision,"
                + "migration_status,grant_status,source_type,forever_flag)"
                + " VALUES(504,2,20,0,0,'REVIEW_REQUIRED',3,'ADMIN',1)");
        AppApiGrantScopeRespVO view = grantReads.describe( 504L);
        assertThat(view.getMode()).isEqualTo("NONE");
        assertThat(view.getBlockReason()).isEqualTo("LEGACY_GRANT_NOT_EFFECTIVE");
    }

    @Test
    void platformAggregateIsReadableWithoutTenantContext() {
        assertThat(grantReads.describe(501L).getMode()).isEqualTo("ACTIVE");
    }

    @Test
    void malformedStoredScopeDoesNotDegradeIntoAllColumns() throws Exception {
        // 数据库约束只保证 ACTIVE 行有非空 approvedColumns；缺物理绑定的载荷仍然无法证明批准范围。
        sql("UPDATE dh_supply_grant_source SET scope_json='{\"approvedColumns\":[\"id\",\"secret\"]}'::jsonb"
                + " WHERE source_key='A'");
        AppApiGrantScopeRespVO view = grantReads.describe( 501L);
        assertThat(view.getMode()).isEqualTo("REVIEW_REQUIRED");
        assertThat(view.getApprovedColumns()).isEmpty();
        var broken = view.getSources().stream().filter(item -> "A".equals(item.getSourceKey())).findFirst().orElseThrow();
        assertThat(broken.getApprovedColumns()).isEmpty();
        assertThat(broken.getContributing()).isFalse();
        assertThat(broken.getValidFrom()).isNull();
    }

    @Test
    void controlDisablesTheAggregateAndAdvancesItsRevision() throws Exception {
        apps.setGrantControl(501L, 3L, true);
        assertThat(scalar("SELECT aggregate_revision FROM dh_supply_app_api WHERE id=501")).isEqualTo(4);
        assertThat(grantReads.describe( 501L).getMode()).isEqualTo("DISABLED");
        // 终态幂等：重复禁用不再推进版本，也不报冲突。
        apps.setGrantControl(501L, 4L, true);
        assertThat(scalar("SELECT aggregate_revision FROM dh_supply_app_api WHERE id=501")).isEqualTo(4);
    }

    @Test
    void controlEnablesOnlyWithTheCurrentRevision() throws Exception {
        apps.setGrantControl(501L, 3L, true);
        assertCode(() -> apps.setGrantControl(501L, 3L, false), 1_500_042_014);
        assertThat(scalar("SELECT aggregate_disabled::int FROM dh_supply_app_api WHERE id=501")).isEqualTo(1);
        apps.setGrantControl(501L, 4L, false);
        assertThat(scalar("SELECT aggregate_disabled::int FROM dh_supply_app_api WHERE id=501")).isZero();
        assertThat(grantReads.describe( 501L).getMode()).isEqualTo("ACTIVE");
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    void staleControlRequestCannotOverwriteANewerDecision(boolean disabled) throws Exception {
        sql("UPDATE dh_supply_app_api SET aggregate_revision=9 WHERE id=501");
        assertCode(() -> apps.setGrantControl(501L, 3L, disabled), 1_500_042_014);
        assertThat(scalar("SELECT aggregate_revision FROM dh_supply_app_api WHERE id=501")).isEqualTo(9);
    }

    @Test
    void controlCannotReviveAPermanentlyClosedAdminGrant() throws Exception {
        sql("INSERT INTO dh_supply_app_api(id,app_id,api_id,protocol_version,aggregate_revision,"
                + "migration_status,grant_status,source_type,forever_flag,aggregate_disabled)"
                + " VALUES(505,2,20,0,0,'REVIEW_REQUIRED',3,'ADMIN',1,true)");
        assertCode(() -> apps.setGrantControl(505L, 0L, false), 1_500_042_009);
        assertThat(scalar("SELECT aggregate_disabled::int FROM dh_supply_app_api WHERE id=505")).isEqualTo(1);
    }

    @Test
    void listUsesEffectiveSourcesInsteadOfTheV2FailClosedCompatibilityFlag() {
        var item = apps.getAppApiList(1L).getFirst();
        assertThat(item.getGrantStatus()).isEqualTo(3);
        assertThat(item.getAuthStatus()).isEqualTo(1);
        assertThat(grantReads.describe( item.getId()).getMode()).isEqualTo("ACTIVE");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE dh_supply_grant_source SET source_status='REVOKED'",
            "UPDATE dh_supply_grant_source SET source_status='SUSPENDED'",
            "UPDATE dh_supply_grant_source SET source_status='REVIEW_REQUIRED'",
            "UPDATE dh_supply_grant_source SET scope_json='{\"approvedColumns\":[\"id\"]}'::jsonb",
            "UPDATE dh_supply_app_api SET migration_status='REVIEW_REQUIRED'",
            "UPDATE dh_supply_app_api SET protocol_version=0,source_type='PERSONAL'",
            "UPDATE dh_supply_app_api SET aggregate_disabled=true",
            "UPDATE dh_supply_api SET contract_hash='changed'",
            "UPDATE dh_supply_api SET status=0",
            "UPDATE dh_supply_api SET deleted=1",
            "UPDATE dh_supply_app SET app_status=0"})
    void listNeverCallsBlockedOrUnconfirmedSourcesEffectiveDespiteOldActiveFlag(String change) throws Exception {
        // 旧聚合状态保持 1/永久，复现过去列表误报“有效”的条件。
        sql("UPDATE dh_supply_app_api SET grant_status=1,forever_flag=1 WHERE id=501");
        sql(change);
        var item = apps.getAppApiList(1L).getFirst();
        assertThat(item.getAuthStatus()).isZero();
        assertThat(grantReads.describe( item.getId()).getMode()).isNotEqualTo("ACTIVE");
    }

    @Test
    void listUsesDatabaseExpiryBoundaryAndKeepsOnlyRemainingEffectiveSources() throws Exception {
        sql("DELETE FROM dh_supply_grant_source");
        source("past", 1, "ACTIVE", List.of("name"), 0, 1000L);
        source("future", 1, "ACTIVE", List.of("phone"), 2000, null);
        sql("UPDATE dh_supply_app_api SET grant_status=1,forever_flag=1 WHERE id=501");
        doReturn(999L).when(aggregates).databaseTimeMillis();
        assertThat(apps.getAppApiList(1L).getFirst().getAuthStatus()).isEqualTo(1);
        doReturn(1000L).when(aggregates).databaseTimeMillis();
        assertThat(apps.getAppApiList(1L).getFirst().getAuthStatus()).isZero();
        doReturn(2000L).when(aggregates).databaseTimeMillis();
        assertThat(apps.getAppApiList(1L).getFirst().getAuthStatus()).isEqualTo(1);
        assertThat(grantReads.describe( 501L).getApprovedColumns()).containsExactly("phone");
    }

    @Test
    void listRemainsEffectiveWhenOnlyOneOfSeveralSourcesIsRevoked() throws Exception {
        sql("UPDATE dh_supply_grant_source SET source_status='REVOKED' WHERE source_key='A'");
        assertThat(apps.getAppApiList(1L).getFirst().getAuthStatus()).isEqualTo(1);
        assertThat(grantReads.describe( 501L).getApprovedColumns()).containsExactly("id", "phone");
    }

    @Test
    void listDoesNotPromoteDisabledSubjectOrSwallowAuthoritativeReadFailure() throws Exception {
        sql("UPDATE dh_supply_app_api SET grant_status=1,forever_flag=1 WHERE id=501");
        user.setStatus(1);
        assertThat(apps.getAppApiList(1L).getFirst().getAuthStatus()).isZero();
        user.setStatus(0);
        when(context.getBean(AdminUserApi.class).getUser(7L))
                .thenThrow(new IllegalStateException("test subject service unavailable"));
        assertThatThrownBy(() -> apps.getAppApiList(1L))
                .isInstanceOf(IllegalStateException.class).hasMessage("test subject service unavailable");
    }

    @Test
    void listKeepsLegacyAdminRevocationDistinctWithoutRestoringIt() throws Exception {
        sql("INSERT INTO dh_supply_app_api(id,app_id,api_id,protocol_version,aggregate_revision,"
                + "migration_status,grant_status,source_type,forever_flag)"
                + " VALUES(504,2,20,0,0,'REVIEW_REQUIRED',1,'ADMIN',1)");
        sql("UPDATE dh_supply_app_api SET approved_contract_hash='approved-hash' WHERE id=504");
        assertThat(apps.getAppApiList(2L).getFirst().getAuthStatus()).isEqualTo(1);
        apps.revokeAdminAppApi(504L, 0L);
        assertThat(apps.getAppApiList(2L).getFirst().getAuthStatus()).isEqualTo(3);
        assertThat(grantReads.describe( 504L).getMode()).isEqualTo("NONE");
        assertThat(scalar("SELECT grant_status FROM dh_supply_app_api WHERE id=504")).isEqualTo(3);
    }

    @Test
    void listUsesApplicationScopeAndRejectsUnknownApplication() {
        assertThat(apps.getAppApiList(1L)).hasSize(1);
        assertThatThrownBy(() -> apps.getAppApiList(999L)).isInstanceOf(ServiceException.class);
    }

    private static void assertRetainedButNonContributing(AppApiGrantScopeRespVO view) {
        assertThat(view.getSources()).hasSize(2).allSatisfy(source -> {
            assertThat(source.getContributing()).isFalse();
            assertThat(source.getForever()).isTrue();
            assertThat(source.getValidFrom()).isNotNull();
        });
        assertThat(view.getSources().get(0).getApprovedColumns()).containsExactly("id", "name");
        assertThat(view.getSources().get(1).getApprovedColumns()).containsExactly("id", "phone");
    }

    private void source(String key, long revision, String status, List<String> fields, long from, Long to)
            throws Exception {
        var scope = new SupplyGrantSourceCommandDTO.Scope("approval", "PERSONAL", 7L,
                new SupplyGrantSourceCommandDTO.Binding(9L, "public", "people"),
                "approved-hash", "v1", "purpose", "duty", "CURRENT_USER_GOVERNANCE", fields, to == null, from, to);
        try (var statement = connection.prepareStatement("INSERT INTO dh_supply_grant_source "
                + "(source_system,source_key,app_id,api_id,source_revision,source_status,scope_json,"
                + "payload_hash,compatibility_key) VALUES('asset',?,1,10,?,?,CAST(? AS jsonb),'fixture',?)")) {
            statement.setString(1, key);
            statement.setLong(2, revision);
            statement.setString(3, status);
            statement.setString(4, JsonUtils.toJsonString(scope));
            statement.setString(5, SupplyGrantCommandCodec.compatibilityHash(scope));
            statement.executeUpdate();
        }
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class, failure ->
                assertThat(failure.getCode()).isEqualTo(code));
    }
}
