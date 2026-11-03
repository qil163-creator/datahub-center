package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.db.TenantDatabaseInterceptor;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketApplySaveReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCheckReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCommentSaveReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketRenewalReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.monitor.AssetUsageLogDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.AssetGrantSourceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketApprovalEvidenceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketAuditEventMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketCheckProcessMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryOperationMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceCommentMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.monitor.AssetUsageLogMapper;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import cn.iocoder.yudao.module.asset.service.monitor.AssetMonitorServiceImpl;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
import cn.iocoder.yudao.module.governance.api.quality.QualityWorkOrderApi;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityWorkOrderStatusRespDTO;
import cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_OPERATION_NOT_PERSISTED;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 普通市场审计与业务写入同一真实PG事务：申请/过程/来源/交付/正式批准/评价/使用日志均为真实表，
 * 真实审计记录器、租户拦截器、Guard及事务代理；权限、元数据、站内信、治理工单与交付领取为替身。
 */
@Timeout(30)
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketAuditDurabilityPostgresTest extends SharingPostgresSupport {
    /** 每个仍记录普通审计的入口；动作、对象、操作人与状态口径沿用原调用参数。 */
    enum Op {
        CREATE_SUBMIT("SUBMIT", "APPLY", 11L, null, "1", "audit purpose"),
        SUBMIT_DRAFT("SUBMIT", "APPLY", 11L, "0", "1", null),
        APPROVE("APPROVE", "APPLY", 11L, "1", "2", "audit approval"),
        REJECT("REJECT", "APPLY", 11L, "1", "3", "audit rejection"),
        SUPPLEMENT("SUPPLEMENT", "APPLY", 11L, "1", "8", "audit supplement"),
        RESUBMIT("RESUBMIT", "APPLY", 11L, "8", "1", null),
        RENEW("RENEW", "APPLY", 9L, null, "1", "续期申请"),
        PAUSE("PAUSE", "APPLY", 11L, "2", "2", "audit pause"),
        RESUME("RESUME", "APPLY", 11L, "2", "2", null),
        COMMENT("COMMENT", "COMMENT", 11L, null, "1", "audit comment"),
        CHECK_COMMENT("CHECK", "COMMENT", 11L, null, "2", "audit check comment");

        final String event;
        final String object;
        final long actor;
        final String from;
        final String to;
        final String reason;

        Op(String event, String object, long actor, String from, String to, String reason) {
            this.event = event;
            this.object = object;
            this.actor = actor;
            this.from = from;
            this.to = to;
            this.reason = reason;
        }

        Long department() {
            return actor == 11L ? 100L : null;
        }
    }

    private MarketApplyService service;
    private MarketCommentService comments;
    private MarketAuditRecorder recorder;
    private AssetMonitorServiceImpl monitor;
    private DataSourceTransactionManager transactions;
    private final PermissionApi permissions = mock(PermissionApi.class);
    private final MarketDeliveryCoordinator coordinator = mock(MarketDeliveryCoordinator.class);
    private final NotifyMessageSendApi notifications = mock(NotifyMessageSendApi.class);
    private final QualityWorkOrderApi workOrders = mock(QualityWorkOrderApi.class);

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
                ALTER TABLE dh_market_resource_apply ALTER COLUMN id SET DEFAULT nextval('dh_market_resource_apply_seq');
                SELECT setval('dh_market_resource_apply_seq',100,false);
                CREATE SEQUENCE dh_asset_grant_source_seq;
                ALTER TABLE dh_market_resource_apply ALTER COLUMN snapshot TYPE jsonb USING snapshot::jsonb;
                INSERT INTO dh_asset_data_asset(id,status,asset_name,asset_type,published_version,
                    published_snapshot_id,published_catalog_version_id,datasource_id,schema_name,table_name,
                    tech_leader_user_id,business_leader_user_id,owner_dept_id,source_system_name)
                  VALUES (5,1,'citizen',1,'v1',11,12,4,'public','citizen',11,12,100,'source');
                -- 仅测试：评价与使用日志的最小结构，与基线同名列、同长度，不是平台迁移。
                CREATE SEQUENCE dh_market_resource_comment_seq;
                CREATE TABLE dh_market_resource_comment (
                  id bigint PRIMARY KEY DEFAULT nextval('dh_market_resource_comment_seq'), asset_id bigint NOT NULL,
                  apply_id bigint, comment_str varchar(500) NOT NULL, level smallint NOT NULL DEFAULT 5,
                  reply_str varchar(500), reply_user_id bigint, reply_time timestamp(6),
                  comment_kind smallint NOT NULL DEFAULT 1, problem_type varchar(64), impact_scope varchar(256),
                  found_time timestamp(6), work_order_id bigint, work_order_status varchar(32),
                  idempotent_key varchar(64), creator varchar(64) DEFAULT '',
                  create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP, updater varchar(64) DEFAULT '',
                  update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP, deleted smallint NOT NULL DEFAULT 0,
                  tenant_id bigint NOT NULL DEFAULT 1);
                CREATE SEQUENCE dh_asset_usage_log_seq;
                CREATE TABLE dh_asset_usage_log (
                  id bigint PRIMARY KEY DEFAULT nextval('dh_asset_usage_log_seq'), asset_id bigint NOT NULL,
                  asset_name varchar(256), provider_name varchar(256), action smallint NOT NULL, user_id bigint,
                  creator varchar(64) DEFAULT '', create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updater varchar(64) DEFAULT '', update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  deleted smallint NOT NULL DEFAULT 0, tenant_id bigint NOT NULL DEFAULT 1);
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
        config.setEnvironment(new Environment("audit-durability", new SpringManagedTransactionFactory(), source));
        var interceptor = new MybatisPlusInterceptor();

        config.addInterceptor(interceptor);
        for (Class<?> mapper : List.of(MarketResourceApplyMapper.class, MarketCheckProcessMapper.class,
                AssetGrantSourceMapper.class, MarketDeliveryOperationMapper.class, MarketApprovalEvidenceMapper.class,
                MarketAuditEventMapper.class, DataAssetMapper.class, AssetUsageLogMapper.class,
                MarketResourceCommentMapper.class)) {
            config.addMapper(mapper);
        }
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        var applications = session.getMapper(MarketResourceApplyMapper.class);
        var assets = session.getMapper(DataAssetMapper.class);

        recorder = new MarketAuditRecorder();
        ReflectionTestUtils.setField(recorder, "auditEventMapper", session.getMapper(MarketAuditEventMapper.class));
        monitor = new AssetMonitorServiceImpl();
        ReflectionTestUtils.setField(monitor, "usageLogMapper", session.getMapper(AssetUsageLogMapper.class));
        ReflectionTestUtils.setField(monitor, "transactionManager", transactions);
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
        when(catalog.listColumns(8L, 41L)).thenReturn(List.of(column));
        var days = mock(MarketWorkingDayService.class);
        when(days.plusWorkingDays(any(), anyInt())).thenAnswer(call ->
                ((LocalDateTime) call.getArgument(0)).plusDays((Integer) call.getArgument(1)));
        var users = mock(AdminUserApi.class);
        when(users.getUser(anyLong())).thenReturn(CommonResult.success(null));

        var target = new MarketApplyServiceImpl();
        ReflectionTestUtils.setField(target, "applyMapper", applications);
        ReflectionTestUtils.setField(target, "processMapper", session.getMapper(MarketCheckProcessMapper.class));
        ReflectionTestUtils.setField(target, "assetMapper", assets);
        ReflectionTestUtils.setField(target, "objectGuard", guard);
        ReflectionTestUtils.setField(target, "deliveryService", delivery);
        ReflectionTestUtils.setField(target, "assetMonitorService", monitor);
        ReflectionTestUtils.setField(target, "auditRecorder", recorder);
        ReflectionTestUtils.setField(target, "notifyHelper", notify);
        ReflectionTestUtils.setField(target, "displayHelper", new MarketDisplayHelper());
        ReflectionTestUtils.setField(target, "adminUserApi", users);
        ReflectionTestUtils.setField(target, "workingDayService", days);
        org.mockito.Mockito.when(catalog.requireCompletePublishedAsset(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> call.getArgument(0));
        ReflectionTestUtils.setField(target, "catalogService", catalog);
        ReflectionTestUtils.setField(target, "transactionManager", transactions);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (MarketApplyService) proxy.getProxy();

        var commentTarget = new MarketCommentServiceImpl();
        ReflectionTestUtils.setField(commentTarget, "commentMapper", session.getMapper(MarketResourceCommentMapper.class));
        ReflectionTestUtils.setField(commentTarget, "displayHelper", new MarketDisplayHelper());
        ReflectionTestUtils.setField(commentTarget, "assetMapper", assets);
        ReflectionTestUtils.setField(commentTarget, "qualityWorkOrderApi", workOrders);
        ReflectionTestUtils.setField(commentTarget, "auditRecorder", recorder);
        ReflectionTestUtils.setField(commentTarget, "transactionManager", transactions);
        var commentProxy = new ProxyFactory(commentTarget);
        commentProxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        comments = (MarketCommentService) commentProxy.getProxy();
        var status = new QualityWorkOrderStatusRespDTO();
        status.setId(77L);
        status.setOrderStatus("OPEN");
        when(workOrders.createFromMarket(any())).thenReturn(CommonResult.success(status));
        TenantContextHolder.clear();
    }

    @AfterEach
    void clearContexts() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @EnumSource(Op.class)
    void successfulOperationCommitsExactlyOneAuditWithTrustedIdentity(Op op) throws Exception {
        arrange(op);
        String before = databaseNow();
        long objectId = run(op);
        String after = databaseNow();
        assertBusinessCommitted(op, objectId);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='" + op.event + "'"))
                .isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='" + op.event
                + "' AND object_type='" + op.object + "' AND object_id=" + objectId
                + " AND from_status IS NOT DISTINCT FROM " + literal(op.from)
                + " AND to_status IS NOT DISTINCT FROM " + literal(op.to)
                + " AND reason IS NOT DISTINCT FROM " + literal(op.reason)
                + " AND result='OK' AND actor_user_id=" + op.actor
                + " AND dept_id IS NOT DISTINCT FROM " + (op.department() == null ? "NULL" : op.department())
                + " AND creator='" + op.actor + "' AND updater='" + op.actor + "' AND deleted=0"
                + " AND correlation_id IS NULL AND event_id ~ '^[0-9a-f-]{36}$'"
                + " AND occurred_at BETWEEN '" + before + "' AND '" + after + "'")).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Op.class)
    void skippedAuditWriteRollsBackTheWholeOperation(Op op) throws Exception {
        arrange(op);
        String before = stored();
        sql("""
                CREATE FUNCTION skip_market_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$;
                CREATE TRIGGER skip_market_audit BEFORE INSERT ON dh_market_audit_event FOR EACH ROW
                  WHEN (NEW.event_type = '%s') EXECUTE FUNCTION skip_market_audit();
                """.formatted(op.event));
        assertThatThrownBy(() -> run(op)).isInstanceOfSatisfying(ServiceException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(MARKET_OPERATION_NOT_PERSISTED.getCode()));
        assertThat(stored()).isEqualTo(before);
        verifyNoInteractions(notifications, coordinator, workOrders);
    }

    @ParameterizedTest
    @EnumSource(Op.class)
    void failingAuditStatementSurfacesImmediatelyAndRollsBackEverything(Op op) throws Exception {
        arrange(op);
        String before = stored();
        sql("""
                CREATE FUNCTION reject_market_audit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'test audit failure'; END $$;
                CREATE TRIGGER reject_market_audit BEFORE INSERT ON dh_market_audit_event FOR EACH ROW
                  WHEN (NEW.event_type = '%s') EXECUTE FUNCTION reject_market_audit();
                """.formatted(op.event));
        // 失败必须在审计语句处原样抛出，不能吞掉后留下已中止事务，再在提交时变成含糊错误或静默回滚。
        assertThatThrownBy(() -> run(op)).isInstanceOf(DataAccessException.class)
                .isNotInstanceOf(ServiceException.class).hasStackTraceContaining("test audit failure");
        assertThat(stored()).isEqualTo(before);
        verifyNoInteractions(notifications, coordinator, workOrders);
    }

    @ParameterizedTest
    @EnumSource(Op.class)
    void auditFailureAtCommitPublishesNoAfterCommitEffect(Op op) throws Exception {
        arrange(op);
        String before = stored();
        sql("""
                CREATE FUNCTION reject_audit_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'test audit commit failure'; END $$;
                CREATE CONSTRAINT TRIGGER reject_audit_commit AFTER INSERT ON dh_market_audit_event
                  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
                  WHEN (NEW.event_type = '%s') EXECUTE FUNCTION reject_audit_commit();
                """.formatted(op.event));
        assertThatThrownBy(() -> run(op)).isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(ServiceException.class).hasStackTraceContaining("test audit commit failure");
        assertThat(stored()).isEqualTo(before);
        verifyNoInteractions(notifications, coordinator, workOrders);
    }

    @ParameterizedTest
    @EnumSource(value = Op.class, names = {"CREATE_SUBMIT", "SUBMIT_DRAFT", "APPROVE", "REJECT", "SUPPLEMENT",
            "RESUBMIT", "PAUSE", "RESUME"})
    void auditBecomesVisibleOnlyWithTheCallerCommit(Op op) throws Exception {
        arrange(op);
        String before = stored();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            long objectId = run(op);
            assertThat(objectId).isPositive();
            try {
                assertThat(stored()).isEqualTo(before); // 独立连接：提交前既看不到业务变化，也看不到审计。
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            verifyNoInteractions(notifications, coordinator);
        });
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='" + op.event + "'"))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = Op.class, names = {"CREATE_SUBMIT", "SUBMIT_DRAFT", "APPROVE", "REJECT", "SUPPLEMENT",
            "RESUBMIT", "PAUSE", "RESUME"})
    void callerRollbackDiscardsTheAuditWithTheBusinessChange(Op op) throws Exception {
        arrange(op);
        String before = stored();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            run(op);
            tx.setRollbackOnly();
        });
        assertThat(stored()).isEqualTo(before);
        verifyNoInteractions(notifications, coordinator);
    }

    @Test
    void failingBestEffortUsageLogCannotPoisonTheApprovalTransaction() throws Exception {
        arrange(Op.APPROVE);
        sql("""
                CREATE FUNCTION reject_usage_log() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'test usage log failure'; END $$;
                CREATE TRIGGER reject_usage_log BEFORE INSERT ON dh_asset_usage_log
                  FOR EACH ROW EXECUTE FUNCTION reject_usage_log();
                """);
        run(Op.APPROVE);
        assertBusinessCommitted(Op.APPROVE, 7L);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='APPROVE' AND object_id=7"))
                .isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_asset_usage_log")).isZero();
        verify(notifications).sendSingleMessageToAdmin(any());
        verify(coordinator).claim( anyLong(), anyString());
    }

    @Test
    void usageLogCommitsAndRollsBackWithTheApproval() throws Exception {
        arrange(Op.APPROVE);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            run(Op.APPROVE);
            tx.setRollbackOnly();
        });
        assertThat(scalar("SELECT count(*) FROM dh_asset_usage_log")).isZero();
        run(Op.APPROVE);
        assertThat(scalar("SELECT count(*) FROM dh_asset_usage_log WHERE asset_id=5 AND action="
                + AssetUsageLogDO.ACTION_APPLY + " AND user_id=11")).isEqualTo(1);
    }

    @Test
    void usageLogOutsideATransactionRemainsIndependentAndBestEffort() throws Exception {
        login(11L, 100L);
        var asset = DataAssetDO.builder().id(8L).assetName("test asset").sourceSystemName("source").build();
        monitor.recordUsageLog(asset, AssetUsageLogDO.ACTION_USAGE);
        assertThat(scalar("SELECT count(*) FROM dh_asset_usage_log WHERE asset_id=8 AND action=3")).isEqualTo(1);
        sql("""
                CREATE FUNCTION reject_usage_log() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'test usage log failure'; END $$;
                CREATE TRIGGER reject_usage_log BEFORE INSERT ON dh_asset_usage_log
                  FOR EACH ROW EXECUTE FUNCTION reject_usage_log();
                """);
        assertThatCode(() -> monitor.recordUsageLog(asset, AssetUsageLogDO.ACTION_USAGE)).doesNotThrowAnyException();
        assertThat(scalar("SELECT count(*) FROM dh_asset_usage_log")).isEqualTo(1);
    }

    @Test
    void productionInputKeyModeUsesTheOperationSequenceForTheClaimedIntent() throws Exception {
        arrange(Op.APPROVE);
        sql("SELECT setval('dh_market_delivery_operation_seq',5000,false)");
        run(Op.APPROVE);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE id=5000 AND apply_id=7"
                + " AND op_type='GRANT' AND status='PENDING'")).isEqualTo(1);
        verify(coordinator).claim( eq(5000L), anyString());
    }

    @Test
    void recorderRefusesToWriteOutsideTheBusinessTransaction() throws Exception {
        login(11L, 100L);
        assertThatThrownBy(() -> recorder.record("PAUSE", "APPLY", 7L, 2, 2, null, "OK"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymous"})
    void recorderRequiresAuthenticatedActor(String context) throws Exception {
        login(11L, 100L);
        switch (context) {
            case "missing" -> TenantContextHolder.clear();
            case "ignored" -> TenantContextHolder.setIgnore(true);
            case "negative" -> TenantContextHolder.setTenantId(-1L);
            case "anonymous" -> SecurityContextHolder.clearContext();
            default -> throw new IllegalArgumentException(context);
        }
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx ->
                recorder.record("PAUSE", "APPLY", 7L, 2, 2, null, "OK"))).isInstanceOf(IllegalStateException.class);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event")).isZero();
    }

    @Test
    void recorderWritesActorWithoutTenantContext() throws Exception {
        TenantContextHolder.clear();
        login(11L, null);
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                recorder.record("PAUSE", "APPLY", 7L, 2, 2, "platform action", "OK"));
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='PAUSE'"
                + " AND actor_user_id=11 AND dept_id IS NULL AND reason='platform action'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event")).isEqualTo(1);
    }

    @Test
    void checkCommentAndItsAuditAreCommittedBeforeTheCrossServiceWorkOrder() throws Exception {
        arrange(Op.CHECK_COMMENT);
        var status = new QualityWorkOrderStatusRespDTO();
        status.setId(77L);
        status.setOrderStatus("OPEN");
        when(workOrders.createFromMarket(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(scalar("SELECT count(*) FROM dh_market_resource_comment WHERE comment_kind=2")).isEqualTo(1);
            assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='CHECK'")).isEqualTo(1);
            return CommonResult.success(status);
        });
        long id = run(Op.CHECK_COMMENT);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_comment WHERE id=" + id
                + " AND work_order_id=77 AND work_order_status='OPEN'")).isEqualTo(1);
    }

    @Test
    void failedWorkOrderKeepsTheCommittedCheckCommentAndAudit() throws Exception {
        arrange(Op.CHECK_COMMENT);
        when(workOrders.createFromMarket(any())).thenThrow(new IllegalStateException("governance offline"));
        long id = run(Op.CHECK_COMMENT);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_comment WHERE id=" + id
                + " AND work_order_id IS NULL AND work_order_status='CREATE_FAILED'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='CHECK' AND object_id=" + id))
                .isEqualTo(1);
    }

    @Test
    void skippedCommentInsertCannotLeaveAnAuditForAMissingComment() throws Exception {
        arrange(Op.COMMENT);
        String before = stored();
        sql("""
                CREATE FUNCTION skip_comment() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$;
                CREATE TRIGGER skip_comment BEFORE INSERT ON dh_market_resource_comment
                  FOR EACH ROW EXECUTE FUNCTION skip_comment();
                """);
        assertThatThrownBy(() -> run(Op.CHECK_COMMENT)).isInstanceOfSatisfying(ServiceException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(MARKET_OPERATION_NOT_PERSISTED.getCode()));
        assertThat(stored()).isEqualTo(before);
        verifyNoInteractions(workOrders);
    }


    private void arrange(Op op) throws Exception {
        switch (op) {
            case SUBMIT_DRAFT -> sql("""
                    INSERT INTO dh_market_resource_apply(id,asset_id,apply_user_id,status,if_auth,paused,
                      revision,field_scope,title,delivery_status) VALUES (20,8,11,0,0,0,1,'id','draft',0)
                    """);
            case RESUBMIT -> sql("""
                    INSERT INTO dh_market_resource_apply(id,asset_id,apply_user_id,status,if_auth,paused,
                      revision,field_scope,title,snapshot,supplement_note,delivery_status)
                      VALUES (21,8,11,8,0,0,3,'id','supplement','{"previous":"snapshot"}','need more',0)
                    """);
            case RENEW, PAUSE, RESUME -> {
                // 期限属于批准语义，必须在批准前确定；批准后改期限会使原批准凭证不再匹配。
                sql("UPDATE dh_market_resource_apply SET apply_start_time='2020-01-01', apply_end_time='2040-01-01'"
                        + " WHERE id=7");
                login(11L, 100L);
                service.checkApply(check(2, "prepared approval"));
                if (op == Op.RESUME) service.pauseApply(7L, "prepared pause");
                sql("DELETE FROM dh_market_audit_event WHERE event_type IN ('APPROVE','PAUSE')");
            }
            default -> { }
        }
        login(op.actor, op.department());
        clearInvocations(notifications, coordinator, workOrders);
    }

    private long run(Op op) {
        return switch (op) {
            case CREATE_SUBMIT -> service.createApply(createRequest());
            case SUBMIT_DRAFT -> { service.submitApply(20L); yield 20L; }
            case APPROVE -> { service.checkApply(check(2, op.reason)); yield 7L; }
            case REJECT -> { service.checkApply(check(3, op.reason)); yield 7L; }
            case SUPPLEMENT -> { service.requestSupplement(7L, op.reason); yield 7L; }
            case RESUBMIT -> { service.resubmitApply(21L); yield 21L; }
            case RENEW -> service.createRenewal(renewal());
            case PAUSE -> { service.pauseApply(7L, op.reason); yield 7L; }
            case RESUME -> { service.resumeApply(7L); yield 7L; }
            case COMMENT -> comments.createComment(comment(1, op.reason));
            case CHECK_COMMENT -> comments.createComment(comment(2, op.reason));
        };
    }

    private void assertBusinessCommitted(Op op, long objectId) throws Exception {
        String condition = switch (op) {
            case CREATE_SUBMIT -> "SELECT count(*) FROM dh_market_resource_apply WHERE id=" + objectId
                    + " AND status=1 AND apply_user_id=11 AND asset_id=8";
            case SUBMIT_DRAFT -> "SELECT count(*) FROM dh_market_resource_apply WHERE id=20 AND status=1";
            case APPROVE -> "SELECT count(*) FROM dh_market_resource_apply a JOIN dh_market_audit_event e"
                    + " ON e.object_id=a.id AND e.event_type='GRANT_APPROVAL_EVIDENCE' WHERE a.id=7 AND a.status=2";
            case REJECT -> "SELECT count(*) FROM dh_market_resource_apply WHERE id=7 AND status=3";
            case SUPPLEMENT -> "SELECT count(*) FROM dh_market_resource_apply WHERE id=7 AND status=8";
            case RESUBMIT -> "SELECT count(*) FROM dh_market_resource_apply WHERE id=21 AND status=1";
            case RENEW -> "SELECT count(*) FROM dh_market_resource_apply WHERE id=" + objectId
                    + " AND status=1 AND renewal_of_apply_id=7 AND apply_user_id=9";
            case PAUSE -> "SELECT count(*) FROM dh_market_resource_apply WHERE id=7 AND status=2 AND paused=1";
            case RESUME -> "SELECT count(*) FROM dh_market_resource_apply WHERE id=7 AND status=2 AND paused=0";
            case COMMENT, CHECK_COMMENT -> "SELECT count(*) FROM dh_market_resource_comment WHERE id=" + objectId
                    + " AND comment_str='" + op.reason + "'";
        };
        assertThat(scalar(condition)).isEqualTo(1);
    }

    private static MarketApplySaveReqVO createRequest() {
        var request = new MarketApplySaveReqVO();
        request.setTitle("audit create");
        request.setAssetId(8L);
        request.setApplyDeptId(100L);
        request.setPurpose(Op.CREATE_SUBMIT.reason);
        request.setFieldScope("id");
        request.setSubmit(true);
        return request;
    }

    private static MarketCheckReqVO check(int status, String note) {
        var request = new MarketCheckReqVO();
        request.setId(7L);
        request.setStatus(status);
        request.setCheckNote(note);
        return request;
    }

    private static MarketRenewalReqVO renewal() {
        var request = new MarketRenewalReqVO();
        request.setId(7L);
        request.setOperationId("renew-audit");
        request.setApplyEndTime(LocalDateTime.of(2041, 1, 1, 0, 0));
        return request;
    }

    private static MarketCommentSaveReqVO comment(int kind, String text) {
        var request = new MarketCommentSaveReqVO();
        request.setAssetId(8L);
        request.setCommentStr(text);
        request.setCommentKind(kind);
        request.setLevel(4);
        request.setProblemType(kind == 2 ? "口径" : null);
        return request;
    }

    private void login(long userId, Long department) {
        var login = new LoginUser();
        login.setId(userId);
        login.setTenantId(TenantContextHolder.getTenantId());
        login.setInfo(department == null ? Map.of() : Map.of(LoginUser.INFO_KEY_DEPT_ID, department.toString()));
        SecurityFrameworkUtils.setLoginUser(login, new MockHttpServletRequest());
    }

    private String stored() throws Exception {
        StringBuilder all = new StringBuilder();
        for (String table : List.of("dh_market_resource_apply", "dh_market_check_process", "dh_asset_grant_source",
                "dh_market_delivery_operation", "dh_market_audit_event", "dh_asset_usage_log",
                "dh_market_resource_comment")) {
            all.append(table).append('=').append(row("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id),'[]'::jsonb)::text"
                    + " FROM " + table + " t")).append('\n');
        }
        return all.toString();
    }

    private String databaseNow() throws Exception {
        return row("SELECT (clock_timestamp() AT TIME ZONE 'Asia/Shanghai')::text");
    }

    private String row(String query) throws Exception {
        try (var statement = connection.createStatement(); var row = statement.executeQuery(query)) {
            assertThat(row.next()).isTrue();
            return row.getString(1);
        }
    }

    private static String literal(String value) {
        return value == null ? "NULL" : "'" + value.replace("'", "''") + "'";
    }

    private void fixture(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/sql/" + name)) {
            if (input == null) throw new IllegalStateException("Missing fixture " + name);
            sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
