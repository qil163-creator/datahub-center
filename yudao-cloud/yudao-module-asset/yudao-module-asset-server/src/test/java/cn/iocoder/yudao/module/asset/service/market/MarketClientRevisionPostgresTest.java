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
import cn.iocoder.yudao.module.asset.controller.admin.market.MarketApplyController;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketApplySaveReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCheckReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketLifecycleReqVO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.AssetGrantSourceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.*;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import cn.iocoder.yudao.module.asset.service.monitor.AssetMonitorService;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
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
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_REVISION_CONFLICT;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * #15 客户端所见版本：可选 expectedRevision / expectedSourceRevision，提供时与当前不一致即冲突且不写入；
 * 缺省保持原服务端 CAS。真实 PG、现网 INPUT 主键、真实 Guard/交付/审计；权限、元数据、站内信与领取为替身。
 */
@Timeout(30)
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketClientRevisionPostgresTest extends SharingPostgresSupport {

    /** 以内容版本 revision 保护的申请人/审批人入口。 */
    enum ContentOp { UPDATE, SUBMIT, CANCEL, RESUBMIT, APPROVE, REJECT, SUPPLEMENT }

    /** 以交付来源版本 sourceRevision 保护的授权生命周期入口。 */
    enum LifecycleOp { AUTH_OFF, AUTH_ON, REVOKE, PAUSE, RESUME }

    private MarketApplyService service;
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
                UPDATE dh_market_resource_apply SET apply_start_time='2020-01-01', apply_end_time='2040-01-01',
                  revision=3 WHERE id=7;
                INSERT INTO dh_asset_data_asset(id,status,asset_name,asset_type,published_version,
                    published_snapshot_id,published_catalog_version_id,datasource_id,schema_name,table_name,
                    tech_leader_user_id,business_leader_user_id,owner_dept_id)
                  VALUES (5,1,'citizen',1,'v1',11,12,4,'public','citizen',11,12,100);
                """);
        var source = new UnpooledDataSource("org.postgresql.Driver",
                jdbcUrl + "?currentSchema=datahub-center&socketTimeout=10", "sharing_test", "");
        var transactions = new DataSourceTransactionManager(source);
        var config = singleTenantConfiguration();
        var global = new GlobalConfig().setMetaObjectHandler(new DefaultDBFieldHandler());
        global.setDbConfig(new GlobalConfig.DbConfig().setIdType(IdType.INPUT)
                .setKeyGenerators(List.of(new PostgreKeyGenerator())));
        GlobalConfigUtils.setGlobalConfig(config, global);
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("client-revision", new SpringManagedTransactionFactory(), source));
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
        var catalog = mock(AssetCatalogService.class);
        var column = new MetadataColumnRespDTO();
        column.setColumnName("id");
        when(catalog.listColumns(anyLong(), anyLong())).thenReturn(List.of(column));
        var days = mock(MarketWorkingDayService.class);
        when(days.plusWorkingDays(any(), anyInt())).thenAnswer(call ->
                ((LocalDateTime) call.getArgument(0)).plusDays((Integer) call.getArgument(1)));
        var target = new MarketApplyServiceImpl();
        ReflectionTestUtils.setField(target, "applyMapper", applications);
        ReflectionTestUtils.setField(target, "processMapper", session.getMapper(MarketCheckProcessMapper.class));
        ReflectionTestUtils.setField(target, "assetMapper", assets);
        ReflectionTestUtils.setField(target, "objectGuard", guard);
        ReflectionTestUtils.setField(target, "deliveryService", delivery);
        ReflectionTestUtils.setField(target, "assetMonitorService", mock(AssetMonitorService.class));
        ReflectionTestUtils.setField(target, "auditRecorder", recorder);
        ReflectionTestUtils.setField(target, "notifyHelper", notify);
        ReflectionTestUtils.setField(target, "workingDayService", days);
        org.mockito.Mockito.when(catalog.requireCompletePublishedAsset(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> call.getArgument(0));
        ReflectionTestUtils.setField(target, "catalogService", catalog);
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
    @EnumSource(ContentOp.class)
    void staleContentRevisionIsRejectedBeforeAnyWrite(ContentOp op) throws Exception {
        long id = arrange(op);
        int current = revision(id);
        String before = stored();
        deny(() -> content(op, id, current - 1), MARKET_APPLY_REVISION_CONFLICT);
        deny(() -> content(op, id, current + 1), MARKET_APPLY_REVISION_CONFLICT);
        assertThat(stored()).isEqualTo(before);
        verifyNoInteractions(notifications, coordinator);
    }

    @ParameterizedTest
    @EnumSource(ContentOp.class)
    void matchingOrOmittedContentRevisionKeepsTheOriginalBehaviour(ContentOp op) throws Exception {
        long id = arrange(op);
        content(op, id, revision(id));
        assertDone(op, id);
        long second = arrange(op); // 每次新建独立申请，缺省版本仍走原 CAS
        content(op, second, null);
        assertDone(op, second);
    }

    @ParameterizedTest
    @EnumSource(LifecycleOp.class)
    void staleSourceRevisionIsRejectedBeforeAnyWrite(LifecycleOp op) throws Exception {
        arrangeLifecycle(op);
        long current = sourceRevision();
        String before = stored();
        deny(() -> lifecycle(op, current - 1), MARKET_APPLY_REVISION_CONFLICT);
        deny(() -> lifecycle(op, current + 1), MARKET_APPLY_REVISION_CONFLICT);
        assertThat(stored()).isEqualTo(before);
        verifyNoInteractions(notifications, coordinator);
    }

    @ParameterizedTest
    @EnumSource(LifecycleOp.class)
    void matchingSourceRevisionAdvancesTheLifecycle(LifecycleOp op) throws Exception {
        arrangeLifecycle(op);
        long current = sourceRevision();
        lifecycle(op, current);
        assertThat(sourceRevision()).isEqualTo(current + 1);
    }

    @Test
    void staleResumeAfterAnotherPauseAndResumeIsRejectedAlthoughPausedLooksUnchanged() throws Exception {
        arrangeLifecycle(LifecycleOp.RESUME);
        long seen = sourceRevision(); // 页面B看到：已暂停，来源版本 seen
        service.resumeApply(7L, seen); // 页面A：恢复
        service.pauseApply(7L, "A 再次暂停", seen + 1); // 页面A：再暂停；paused 又回到 1
        String before = stored();
        deny(() -> service.resumeApply(7L, seen), MARKET_APPLY_REVISION_CONFLICT);
        assertThat(stored()).isEqualTo(before);
        // 缺省版本的旧调用方仍按原语义执行，由调用方自行承担旧页面风险。
        service.resumeApply(7L);
        assertThat(scalar("SELECT paused FROM dh_market_resource_apply WHERE id=7")).isZero();
    }

    @Test
    void reviewerCannotApproveContentResubmittedAfterTheirPageLoaded() throws Exception {
        // 本例只验证版本，重提冻结不涉及 API 绑定核验。
        sql("UPDATE dh_market_resource_apply SET api_id=NULL, app_id=NULL, field_scope='id' WHERE id=7");
        login(11L, 100L);
        int seen = revision(7L);
        service.requestSupplement(7L, "请补正", seen);
        login(9L, null);
        service.resubmitApply(7L, seen);
        login(11L, 100L);
        String before = stored();
        deny(() -> service.checkApply(check(7L, 2, seen)), MARKET_APPLY_REVISION_CONFLICT);
        assertThat(stored()).isEqualTo(before);
        service.checkApply(check(7L, 2, revision(7L)));
        assertThat(scalar("SELECT status FROM dh_market_resource_apply WHERE id=7")).isEqualTo(2);
    }

    @Test
    void editThenSubmitCarriesTheIncrementedRevisionAndRejectsAConcurrentEdit() throws Exception {
        draft(20, 8, 9, 0, 4);
        login(9L, null);
        service.updateApply(save(20L, 8L, 4));
        service.submitApply(20L, 5); // 编辑成功后服务端版本确定为所见版本 + 1
        assertThat(scalar("SELECT status FROM dh_market_resource_apply WHERE id=20")).isEqualTo(1);
        draft(22, 9, 9, 0, 10);
        service.updateApply(save(22L, 9L, 10));
        sql("UPDATE dh_market_resource_apply SET revision=revision+1 WHERE id=22"); // 另一窗口插入的编辑
        deny(() -> service.submitApply(22L, 11), MARKET_APPLY_REVISION_CONFLICT);
        assertThat(scalar("SELECT status FROM dh_market_resource_apply WHERE id=22")).isZero();
    }

    @Test
    void controllerExposesOnlyOptionalVersionParameters() throws Exception {
        for (String name : List.of("submitApply", "cancelApply", "resubmitApply", "authApply", "revokeApply", "resumeApply")) {
            var method = Arrays.stream(MarketApplyController.class.getMethods())
                    .filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();
            var versions = Arrays.stream(method.getParameters())
                    .map(parameter -> parameter.getAnnotation(RequestParam.class))
                    .filter(annotation -> annotation != null && annotation.value().startsWith("expected")).toList();
            assertThat(versions).as(name).hasSize(1);
            assertThat(versions.getFirst().required()).as(name).isFalse();
        }
    }

    private long nextId = 30;

    /** 每次新建独立申请与独立申请人，避免在途唯一索引冲突，也不复用已有正式批准。 */
    private long arrange(ContentOp op) throws Exception {
        long id = nextId++;
        long owner = 1000 + id;
        switch (op) {
            case UPDATE, SUBMIT, CANCEL -> {
                draft(id, 8, owner, 0, 5);
                login(owner, null);
            }
            case RESUBMIT -> {
                draft(id, 8, owner, 8, 6);
                login(owner, null);
            }
            default -> {
                sql("INSERT INTO dh_market_resource_apply(id,asset_id,apply_user_id,status,if_auth,paused,"
                        + "revision,source_revision,app_id,api_id,snapshot,delivery_status,apply_start_time,apply_end_time)"
                        + " SELECT " + id + ",asset_id," + owner + ",1,0,0,3,0,app_id,api_id,snapshot,0,"
                        + "apply_start_time,apply_end_time FROM dh_market_resource_apply WHERE id=7");
                login(11L, 100L);
            }
        }
        clearInvocations(notifications, coordinator);
        return id;
    }

    private void draft(long id, long asset, long owner, int status, int revision) throws Exception {
        sql("INSERT INTO dh_market_resource_apply(id,asset_id,apply_user_id,status,if_auth,paused,revision,"
                + "field_scope,title,snapshot,delivery_status) VALUES (" + id + "," + asset + "," + owner + ","
                + status + ",0,0," + revision + ",'id','draft','{\"previous\":\"snapshot\"}',0)");
    }

    private void assertDone(ContentOp op, long id) throws Exception {
        String expected = switch (op) {
            case UPDATE -> "status=0 AND title='edited'";
            case SUBMIT, RESUBMIT -> "status=1";
            case CANCEL -> "status=4";
            case APPROVE -> "status=2";
            case REJECT -> "status=3";
            case SUPPLEMENT -> "status=8";
        };
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=" + id + " AND " + expected))
                .isEqualTo(1);
    }

    private void content(ContentOp op, long id, Integer expected) {
        switch (op) {
            case UPDATE -> service.updateApply(save(id, assetOf(id), expected));
            case SUBMIT -> service.submitApply(id, expected);
            case CANCEL -> service.cancelApply(id, expected);
            case RESUBMIT -> service.resubmitApply(id, expected);
            case APPROVE -> service.checkApply(check(id, 2, expected));
            case REJECT -> service.checkApply(check(id, 3, expected));
            case SUPPLEMENT -> {
                var request = new MarketLifecycleReqVO();
                request.setId(id);
                request.setNote("请补正");
                request.setExpectedRevision(expected);
                service.requestSupplement(request.getId(), request.getNote(), request.getExpectedRevision());
            }
        }
    }

    private void arrangeLifecycle(LifecycleOp op) {
        login(11L, 100L);
        service.checkApply(check(7L, 2, null));
        if (op == LifecycleOp.AUTH_ON) service.authApply(7L, 0);
        if (op == LifecycleOp.RESUME) service.pauseApply(7L, "prepared pause");
        clearInvocations(notifications, coordinator);
    }

    private void lifecycle(LifecycleOp op, Long expected) {
        switch (op) {
            case AUTH_OFF -> service.authApply(7L, 0, expected);
            case AUTH_ON -> service.authApply(7L, 1, expected);
            case REVOKE -> service.revokeApply(7L, "收回", expected);
            case PAUSE -> service.pauseApply(7L, "暂停", expected);
            case RESUME -> service.resumeApply(7L, expected);
        }
    }

    private long assetOf(long id) {
        try {
            return scalar("SELECT asset_id FROM dh_market_resource_apply WHERE id=" + id);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static MarketApplySaveReqVO save(long id, long assetId, Integer expected) {
        var request = new MarketApplySaveReqVO();
        request.setId(id);
        request.setAssetId(assetId);
        request.setTitle("edited");
        request.setFieldScope("id");
        request.setExpectedRevision(expected);
        return request;
    }

    private static MarketCheckReqVO check(long id, int status, Integer expected) {
        var request = new MarketCheckReqVO();
        request.setId(id);
        request.setStatus(status);
        request.setCheckNote("decision");
        request.setExpectedRevision(expected);
        return request;
    }

    private int revision(long id) throws Exception {
        return (int) scalar("SELECT revision FROM dh_market_resource_apply WHERE id=" + id);
    }

    private long sourceRevision() throws Exception {
        return scalar("SELECT source_revision FROM dh_market_resource_apply WHERE id=7");
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
