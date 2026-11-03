package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.db.TenantDatabaseInterceptor;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCheckReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.AssetGrantSourceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketApprovalEvidenceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketAuditEventMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketCheckProcessMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryOperationMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.asset.service.monitor.AssetMonitorService;
import cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_FORBIDDEN;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_SCOPE_UNCONFIRMED;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 提供方决定与资产归属的串行化：权限 RPC 期间资产换责任人/部门、删除或换绑，旧判断不能提交；
 * 决定持有资产共享锁到提交，资产归属修改须等待，同一资产上其他申请的决定仍可并行。
 * 真实 PG、现网 INPUT 主键模式、真实 Guard/交付/审计；权限 RPC、站内信、使用统计与交付领取为替身。
 */
@Timeout(30)
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketProviderDecisionSerializationPostgresTest extends SharingPostgresSupport {

    /** 六类提供方入口的八种决定；开通类还须在锁内仍匹配冻结绑定。 */
    enum Decision {
        APPROVE(true), REJECT(false), SUPPLEMENT(false), AUTH_ON(true), AUTH_OFF(false),
        REVOKE(false), PAUSE(false), RESUME(true);

        final boolean activating;

        Decision(boolean activating) {
            this.activating = activating;
        }
    }

    private MarketApplyService service;
    private DataSourceTransactionManager transactions;
    private final PermissionApi permissions = mock(PermissionApi.class);
    private final MarketDeliveryCoordinator coordinator = mock(MarketDeliveryCoordinator.class);
    private final NotifyMessageSendApi notifications = mock(NotifyMessageSendApi.class);

    @BeforeEach
    void prepare() throws Exception {
        fixture("market-freeze-fixture.sql");
        migrate("0042_asset_grant-source-delivery.sql");
        fixture("market-approval-fixture.sql");
        migrate("0066_asset_delivery-review-evidence.sql");
        migrate("0067_asset_grant-approval-evidence.sql");
        fixture("market-demand-asset-fixture.sql");
        migrate("0047_asset_renewal-request-evidence.sql");
        migrate("0050_asset_market-active-application-uniqueness.sql");
        sql("""
                CREATE SEQUENCE dh_asset_grant_source_seq;
                ALTER TABLE dh_market_resource_apply ALTER COLUMN snapshot TYPE jsonb USING snapshot::jsonb;
                -- 期限属于批准语义，须在批准前确定。
                UPDATE dh_market_resource_apply SET apply_start_time='2020-01-01', apply_end_time='2040-01-01'
                  WHERE id=7;
                INSERT INTO dh_asset_data_asset(id,status,asset_name,asset_type,published_version,
                    published_snapshot_id,published_catalog_version_id,datasource_id,schema_name,table_name,
                    tech_leader_user_id,business_leader_user_id,owner_dept_id,description,view_count)
                  VALUES (5,1,'citizen',1,'v1',11,12,4,'public','citizen',11,12,100,'before',0);
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
        config.setEnvironment(new Environment("provider-serialization", new SpringManagedTransactionFactory(), source));
        var interceptor = new MybatisPlusInterceptor();

        config.addInterceptor(interceptor);
        for (Class<?> mapper : List.of(MarketResourceApplyMapper.class, MarketCheckProcessMapper.class,
                AssetGrantSourceMapper.class, MarketDeliveryOperationMapper.class, MarketApprovalEvidenceMapper.class,
                MarketAuditEventMapper.class, DataAssetMapper.class)) {
            config.addMapper(mapper);
        }
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        var applications = session.getMapper(MarketResourceApplyMapper.class);
        var assets = session.getMapper(DataAssetMapper.class);
        var recorder = new MarketAuditRecorder();
        ReflectionTestUtils.setField(recorder, "auditEventMapper", session.getMapper(MarketAuditEventMapper.class));
        var delivery = new MarketDeliveryService();
        ReflectionTestUtils.setField(delivery, "applyMapper", applications);
        ReflectionTestUtils.setField(delivery, "grantSourceMapper", session.getMapper(AssetGrantSourceMapper.class));
        ReflectionTestUtils.setField(delivery, "operationMapper", session.getMapper(MarketDeliveryOperationMapper.class));
        ReflectionTestUtils.setField(delivery, "approvalEvidenceMapper", session.getMapper(MarketApprovalEvidenceMapper.class));
        ReflectionTestUtils.setField(delivery, "coordinator", coordinator);
        when(coordinator.claim( anyLong(), anyString())).thenReturn(Optional.empty());
        when(permissions.hasAnyPermissions(anyLong(), any(String[].class))).thenReturn(CommonResult.success(true));
        var guard = new MarketObjectGuard();
        ReflectionTestUtils.setField(guard, "permissionApi", permissions);
        ReflectionTestUtils.setField(guard, "assetMapper", assets);
        var notify = new MarketNotifyHelper();
        ReflectionTestUtils.setField(notify, "notifyMessageSendApi", notifications);
        var target = new MarketApplyServiceImpl();
        ReflectionTestUtils.setField(target, "applyMapper", applications);
        ReflectionTestUtils.setField(target, "processMapper", session.getMapper(MarketCheckProcessMapper.class));
        ReflectionTestUtils.setField(target, "assetMapper", assets);
        ReflectionTestUtils.setField(target, "objectGuard", guard);
        ReflectionTestUtils.setField(target, "deliveryService", delivery);
        ReflectionTestUtils.setField(target, "assetMonitorService", mock(AssetMonitorService.class));
        ReflectionTestUtils.setField(target, "auditRecorder", recorder);
        ReflectionTestUtils.setField(target, "notifyHelper", notify);
        ReflectionTestUtils.setField(target, "transactionManager", transactions);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (MarketApplyService) proxy.getProxy();
        TenantContextHolder.clear();
    }

    @AfterEach
    void clearContexts() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @EnumSource(Decision.class)
    void technicalLeaderReplacedDuringPermissionCheckCannotCommitTheStaleDecision(Decision decision) throws Exception {
        arrange(decision);
        String before = stored();
        changeAssetDuringPermissionCheck("tech_leader_user_id=99");
        deny(() -> run(decision), MARKET_APPLY_FORBIDDEN);
        assertThat(stored()).isEqualTo(before);
        verifyNoInteractions(coordinator, notifications);
    }

    @ParameterizedTest
    @EnumSource(Decision.class)
    void departmentProviderMovedDuringPermissionCheckCannotCommitTheStaleDecision(Decision decision) throws Exception {
        arrange(decision);
        login(13L, 100L); // 仅凭资产归属部门成为提供方
        String before = stored();
        changeAssetDuringPermissionCheck("owner_dept_id=200");
        deny(() -> run(decision), MARKET_APPLY_FORBIDDEN);
        assertThat(stored()).isEqualTo(before);
        verifyNoInteractions(coordinator, notifications);
    }

    @ParameterizedTest
    @EnumSource(Decision.class)
    void assetDeletedDuringPermissionCheckCannotCommitTheDecision(Decision decision) throws Exception {
        arrange(decision);
        String before = stored();
        changeAssetDuringPermissionCheck("deleted=1");
        deny(() -> run(decision), MARKET_APPLY_FORBIDDEN);
        assertThat(stored()).isEqualTo(before);
        verifyNoInteractions(coordinator, notifications);
    }

    @ParameterizedTest
    @EnumSource(Decision.class)
    void rebindingDuringPermissionCheckOnlyBlocksActivatingDecisions(Decision decision) throws Exception {
        arrange(decision);
        String before = stored();
        changeAssetDuringPermissionCheck("published_version='v2'");
        if (decision.activating) {
            // 开通依据必须仍是批准时冻结的发布版本；停止类决定不因换绑受阻。
            deny(() -> run(decision), MARKET_APPLY_SCOPE_UNCONFIRMED);
            assertThat(stored()).isEqualTo(before);
            verifyNoInteractions(coordinator, notifications);
        } else {
            run(decision);
            assertDecided(decision);
        }
    }

    @ParameterizedTest
    @EnumSource(Decision.class)
    void unrelatedAssetEditDoesNotRejectAStillValidProvider(Decision decision) throws Exception {
        arrange(decision);
        changeAssetDuringPermissionCheck("description='edited', business_leader_user_id=77, view_count=5");
        run(decision);
        assertDecided(decision);
    }

    @ParameterizedTest
    @EnumSource(Decision.class)
    void ownershipUpdateWaitsUntilTheDecisionCommits(Decision decision) throws Exception {
        arrange(decision);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            run(decision);
            try (var other = open(); var statement = other.createStatement()) {
                // 共享锁不串行化同一资产上的其他决定。
                statement.executeQuery("SELECT id FROM dh_asset_data_asset WHERE id=5 FOR SHARE NOWAIT").close();
                statement.execute("SET lock_timeout='300ms'");
                assertThatThrownBy(() -> statement.executeUpdate(
                        "UPDATE dh_asset_data_asset SET tech_leader_user_id=99 WHERE id=5"))
                        .isInstanceOfSatisfying(SQLException.class,
                                failure -> assertThat(failure.getSQLState()).isEqualTo("55P03"));
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
            verifyNoInteractions(notifications);
        });
        assertDecided(decision);
        try (var other = open(); var statement = other.createStatement()) {
            assertThat(statement.executeUpdate("UPDATE dh_asset_data_asset SET tech_leader_user_id=99 WHERE id=5"))
                    .isEqualTo(1);
        }
    }

    @Test
    void decisionsOnDifferentApplicationsOfTheSameAssetAreNotSerialized() throws Exception {
        sql("""
                INSERT INTO dh_market_resource_apply(id,asset_id,apply_user_id,status,if_auth,paused,revision,
                  source_revision,app_id,api_id,snapshot,delivery_status,apply_start_time,apply_end_time)
                SELECT 30,asset_id,10,1,0,0,1,0,app_id,api_id,snapshot,0,apply_start_time,apply_end_time
                  FROM dh_market_resource_apply WHERE id=7
                """);
        login(11L, 300L);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            service.checkApply(check(7L, MarketResourceApplyDO.STATUS_APPROVED));
            try (var worker = Executors.newSingleThreadExecutor()) {
                var second = worker.submit(() -> {
                    TenantContextHolder.clear();
                    login(11L, 300L);
                    try {
                        service.checkApply(check(30L, MarketResourceApplyDO.STATUS_REJECTED));
                    } finally {
                        clearContexts();
                    }
                });
                // 第一笔决定仍未提交并持有共享锁，第二笔仍可完成。
                second.get(8, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=7 AND status=2")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=30 AND status=3")).isEqualTo(1);
    }

    private void arrange(Decision decision) throws Exception {
        login(11L, 300L); // 部门不是归属部门：只凭技术负责人身份成为提供方
        switch (decision) {
            case AUTH_OFF, REVOKE, PAUSE -> service.checkApply(check(7L, MarketResourceApplyDO.STATUS_APPROVED));
            case AUTH_ON -> {
                service.checkApply(check(7L, MarketResourceApplyDO.STATUS_APPROVED));
                service.authApply(7L, 0);
            }
            case RESUME -> {
                service.checkApply(check(7L, MarketResourceApplyDO.STATUS_APPROVED));
                service.pauseApply(7L, "prepared pause");
            }
            default -> { }
        }
        clearInvocations(permissions, coordinator, notifications);
    }

    private void run(Decision decision) {
        switch (decision) {
            case APPROVE -> service.checkApply(check(7L, MarketResourceApplyDO.STATUS_APPROVED));
            case REJECT -> service.checkApply(check(7L, MarketResourceApplyDO.STATUS_REJECTED));
            case SUPPLEMENT -> service.requestSupplement(7L, "decision");
            case AUTH_ON -> service.authApply(7L, 1);
            case AUTH_OFF -> service.authApply(7L, 0);
            case REVOKE -> service.revokeApply(7L, "decision");
            case PAUSE -> service.pauseApply(7L, "decision");
            case RESUME -> service.resumeApply(7L);
        }
    }

    private void assertDecided(Decision decision) throws Exception {
        String condition = switch (decision) {
            case APPROVE, AUTH_ON -> "status=2 AND if_auth=1 AND paused=0";
            case REJECT -> "status=3";
            case SUPPLEMENT -> "status=8";
            case AUTH_OFF -> "status=2 AND if_auth=0";
            case REVOKE -> "status=6 AND if_auth=0";
            case PAUSE -> "status=2 AND paused=1";
            case RESUME -> "status=2 AND paused=0";
        };
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=7 AND " + condition)).isEqualTo(1);
    }

    /** 在入口已读资产、尚未进入申请 CAS 的权限 RPC 期间，由另一连接提交资产变更。 */
    private void changeAssetDuringPermissionCheck(String change) {
        when(permissions.hasAnyPermissions(anyLong(), any(String[].class))).thenAnswer(call -> {
            try (var other = open(); var statement = other.createStatement()) {
                statement.executeUpdate("UPDATE dh_asset_data_asset SET " + change + " WHERE id=5");
            }
            return CommonResult.success(true);
        });
    }

    private static MarketCheckReqVO check(long id, int status) {
        var request = new MarketCheckReqVO();
        request.setId(id);
        request.setStatus(status);
        request.setCheckNote("decision");
        return request;
    }

    private void login(long userId, Long department) {
        var login = new LoginUser();
        login.setId(userId);
        login.setTenantId(1L);
        login.setInfo(department == null ? Map.of() : Map.of(LoginUser.INFO_KEY_DEPT_ID, department.toString()));
        SecurityFrameworkUtils.setLoginUser(login, new MockHttpServletRequest());
    }

    private String stored() throws Exception {
        StringBuilder all = new StringBuilder();
        for (String table : List.of("dh_market_resource_apply", "dh_market_check_process", "dh_asset_grant_source",
                "dh_market_delivery_operation", "dh_market_audit_event")) {
            try (var statement = connection.createStatement(); var row = statement.executeQuery(
                    "SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id),'[]'::jsonb)::text FROM " + table + " t")) {
                assertThat(row.next()).isTrue();
                all.append(table).append('=').append(row.getString(1)).append('\n');
            }
        }
        return all.toString();
    }

    private void fixture(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/sql/" + name)) {
            if (input == null) throw new IllegalStateException("Missing fixture " + name);
            sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static void deny(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ErrorCode code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(code.getCode()));
    }
}
