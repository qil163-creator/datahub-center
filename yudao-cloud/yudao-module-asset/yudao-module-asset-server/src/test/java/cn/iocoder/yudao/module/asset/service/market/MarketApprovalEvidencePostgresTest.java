package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.db.TenantDatabaseInterceptor;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCheckReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.AssetGrantSourceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.*;
import cn.iocoder.yudao.module.asset.service.monitor.AssetMonitorService;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实申请代理、交付/来源/审计 Mapper 和租户拦截器；远端协调器不做外部写入。 */
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketApprovalEvidencePostgresTest extends SharingPostgresSupport {
    private static final String MIGRATION = "0067_asset_grant-approval-evidence.sql";
    private SqlSessionTemplate session;
    private MarketResourceApplyMapper applies;
    private MarketApprovalEvidenceMapper evidence;
    private MarketDeliveryService delivery;
    private MarketDeliveryCoordinator coordinator;
    private DataSourceTransactionManager transactions;
    private MarketApplyService service;

    @BeforeEach
    void prepare() throws Exception {
        fixture("market-freeze-fixture.sql");
        migrate("0042_asset_grant-source-delivery.sql");
        fixture("market-approval-fixture.sql");
        migrate("0066_asset_delivery-review-evidence.sql");
        migrate(MIGRATION);
        var source = new UnpooledDataSource("org.postgresql.Driver", jdbcUrl + "?currentSchema=datahub-center",
                "sharing_test", "");
        transactions = new DataSourceTransactionManager(source);
        var configuration = singleTenantConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("approval-test", new SpringManagedTransactionFactory(), source));
        var interceptor = new MybatisPlusInterceptor();

        configuration.addInterceptor(interceptor);
        configuration.addMapper(MarketResourceApplyMapper.class);
        configuration.addMapper(MarketCheckProcessMapper.class);
        configuration.addMapper(AssetGrantSourceMapper.class);
        configuration.addMapper(MarketDeliveryOperationMapper.class);
        configuration.addMapper(MarketApprovalEvidenceMapper.class);
        session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        applies = session.getMapper(MarketResourceApplyMapper.class);
        evidence = spy(session.getMapper(MarketApprovalEvidenceMapper.class));
        coordinator = mock(MarketDeliveryCoordinator.class);
        when(coordinator.claim( anyLong(), anyString())).thenAnswer(call -> {
            // afterCommit 中 Spring 资源尚未清理，故用独立连接证明数据库已经提交。
            assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation")).isPositive();
            return Optional.empty();
        });
        delivery = new MarketDeliveryService();
        ReflectionTestUtils.setField(delivery, "applyMapper", applies);
        ReflectionTestUtils.setField(delivery, "grantSourceMapper", session.getMapper(AssetGrantSourceMapper.class));
        ReflectionTestUtils.setField(delivery, "operationMapper", session.getMapper(MarketDeliveryOperationMapper.class));
        ReflectionTestUtils.setField(delivery, "approvalEvidenceMapper", evidence);
        ReflectionTestUtils.setField(delivery, "coordinator", coordinator);
        service = service(guard());
        TenantContextHolder.clear();
    }

    private void fixture(String file) throws Exception {
        try (var input = getClass().getResourceAsStream("/sql/" + file)) {
            assertThat(input).isNotNull();
            sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private MarketObjectGuard guard() {
        var guard = mock(MarketObjectGuard.class);
        when(guard.requireUserId()).thenReturn(11L);
        when(guard.currentDeptId()).thenReturn(100L);
        return guard;
    }

    private MarketApplyService service(MarketObjectGuard guard) {
        var target = new MarketApplyServiceImpl();
        ReflectionTestUtils.setField(target, "applyMapper", applies);
        ReflectionTestUtils.setField(target, "processMapper", session.getMapper(MarketCheckProcessMapper.class));
        ReflectionTestUtils.setField(target, "objectGuard", guard);
        ReflectionTestUtils.setField(target, "transactionManager", transactions);
        ReflectionTestUtils.setField(target, "deliveryService", delivery);
        ReflectionTestUtils.setField(target, "notifyHelper", mock(MarketNotifyHelper.class));
        ReflectionTestUtils.setField(target, "auditRecorder", mock(MarketAuditRecorder.class));
        ReflectionTestUtils.setField(target, "assetMonitorService", mock(AssetMonitorService.class));
        var assets = mock(DataAssetMapper.class);
        when(assets.selectById(5L)).thenReturn(DataAssetDO.builder().id(5L)
                .status(DataAssetDO.STATUS_PUBLISHED).assetType(DataAssetDO.TYPE_DATABASE_TABLE)
                .publishedVersion("v1").publishedSnapshotId(11L).publishedCatalogVersionId(12L)
                .datasourceId(4L).schemaName("public").tableName("citizen").build());
        ReflectionTestUtils.setField(target, "assetMapper", assets);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (MarketApplyService) proxy.getProxy();
    }

    private MarketCheckReqVO approval() {
        var request = new MarketCheckReqVO();
        request.setId(7L); request.setStatus(2); request.setCheckNote("同意原范围");
        return request;
    }

    @AfterEach
    void cleanup() { TenantContextHolder.clear(); }

    @Test
    void approvalPersistsOriginalActorScopeAndIntentAtomically() throws Exception {
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
            return call.callRealMethod();
        }).when(evidence).insertApproval( anyLong(), anyLong(), anyString(), anyString(), anyLong(), any(), any());
        service.checkApply(approval());
        var row = evidence.selectByApply( 7);
        var command = delivery.buildCommand(applies.selectById(7L), SupplyGrantSourceCommandDTO.Action.GRANT, "different", 99);
        assertThat(row.getApprovalScopeHash()).isEqualTo(MarketDeliveryPayload.approvalHash( 5L, command));
        assertThat(row.getApprovalRef()).isEqualTo("apply:7:r1");
        assertThat(scalar("SELECT actor_user_id FROM dh_market_audit_event")).isEqualTo(11);
        assertThat(scalar("SELECT dept_id FROM dh_market_audit_event")).isEqualTo(100);
        assertThat(applies.selectById(7L).getStatus()).isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation")).isEqualTo(1);
        verify(coordinator).claim( anyLong(), anyString());
    }

    @Test
    void pauseResumeChangesDeliveryVersionButNeverRewritesApproval() throws Exception {
        service.checkApply(approval());
        var original = evidence.selectByApply( 7);
        service.pauseApply(7L, "暂停");
        service.resumeApply(7L);
        assertThat(evidence.selectByApply( 7)).isEqualTo(original);
        assertThat(applies.selectById(7L).getPaused()).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation")).isEqualTo(3);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "purpose='new purpose'", "duty_matter='new duty'", "apply_user_id=99", "app_id=4", "api_id=4",
            "revision=2", "asset_id=6", "app_id=NULL", "api_id=NULL", "apply_end_time='2099-01-01'",
            "snapshot=jsonb_set(snapshot::jsonb,'{frozenColumns}','[\"Id\",\"secret\"]')::text",
            "snapshot=jsonb_set(snapshot::jsonb,'{approvedContractHash}','\"new\"')::text",
            "snapshot=jsonb_set(snapshot::jsonb,'{apiVersion}','\"v2\"')::text",
            "snapshot=jsonb_set(snapshot::jsonb,'{tableName}','\"different\"')::text"
    })
    void changedApprovedSemanticsCannotCreateAnotherGrant(String assignment) throws Exception {
        service.checkApply(approval());
        long revision = scalar("SELECT revision FROM dh_asset_grant_source");
        sql("UPDATE dh_market_resource_apply SET " + assignment + " WHERE id=7");
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored ->
                delivery.enqueueGrant(applies.selectById(7L)))).isInstanceOf(ServiceException.class);
        assertThat(scalar("SELECT revision FROM dh_asset_grant_source")).isEqualTo(revision);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event")).isEqualTo(1);
    }

    @Test
    void changedScopeDuringResumeRollsBackLifecycleCasAndProcess() throws Exception {
        service.checkApply(approval());
        service.pauseApply(7L, "暂停");
        sql("UPDATE dh_market_resource_apply SET purpose='changed after approval' WHERE id=7");
        assertThatThrownBy(() -> service.resumeApply(7L)).isInstanceOf(ServiceException.class);
        assertThat(applies.selectById(7L).getPaused()).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation")).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"audit", "operation"})
    void durableWriteFailureRollsBackEntireApproval(String failure) throws Exception {
        sql(failure.equals("audit")
                ? "ALTER TABLE dh_market_audit_event ADD CONSTRAINT test_reject_approval CHECK (event_type<>'GRANT_APPROVAL_EVIDENCE')"
                : "ALTER TABLE dh_market_delivery_operation ADD CONSTRAINT test_reject_operation CHECK (op_type<>'GRANT')");
        assertThatThrownBy(() -> service.checkApply(approval())).isInstanceOf(RuntimeException.class);
        assertThat(applies.selectById(7L).getStatus()).isEqualTo(1);
        for (String table : new String[]{"dh_asset_grant_source", "dh_market_check_process", "dh_market_delivery_operation", "dh_market_audit_event"}) {
            assertThat(scalar("SELECT count(*) FROM " + table)).isZero();
        }
        verifyNoInteractions(coordinator);
    }

    @Test
    void legacyResumeDoesNotInventApprovalAndRevokeDoesNotRequireApproval() throws Exception {
        sql("UPDATE dh_market_resource_apply SET status=2,if_auth=1,paused=1 WHERE id=7");
        service.resumeApply(7L);
        assertThat(evidence.selectByApply( 7)).isNull();
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation")).isEqualTo(1);
        sql("UPDATE dh_market_resource_apply SET snapshot='{}' WHERE id=7");
        clearInvocations(evidence);
        service.pauseApply(7L, "stop");
        service.revokeApply(7L, "reapply");
        assertThat(applies.selectById(7L).getStatus()).isEqualTo(MarketResourceApplyDO.STATUS_REVOKED);
        verifyNoInteractions(evidence);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE dh_market_audit_event SET approval_scope_hash=repeat('0',64)",
            "UPDATE dh_market_audit_event SET actor_user_id=99",
            "UPDATE dh_market_audit_event SET event_type='OLD'",
            "UPDATE dh_market_audit_event SET deleted=1",
            "UPDATE dh_market_audit_event SET reason='overwritten'",
            "DELETE FROM dh_market_audit_event", "TRUNCATE dh_market_audit_event"
    })
    void originalApprovalCannotBeReplacedOrDeleted(String mutation) throws Exception {
        service.checkApply(approval());
        var before = evidence.selectByApply( 7);
        assertThatThrownBy(() -> sql(mutation)).isInstanceOf(SQLException.class)
                .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("23514");
        assertThat(evidence.selectByApply( 7)).isEqualTo(before);
    }

    @Test
    void otherAuditTypesRetainLifecycleAndCannotBePromotedToApproval() throws Exception {
        sql("INSERT INTO dh_market_audit_event(event_id,event_type,object_type,object_id) VALUES('old','APPROVE','APPLY',7)");
        sql("UPDATE dh_market_audit_event SET reason='still editable'");
        assertThatThrownBy(() -> sql("UPDATE dh_market_audit_event SET event_type='GRANT_APPROVAL_EVIDENCE'"))
                .isInstanceOf(SQLException.class);
        sql("DELETE FROM dh_market_audit_event");
        sql("TRUNCATE dh_market_audit_event");
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event")).isZero();
    }

    @Test
    void singleTenantMigrationReplayPreservesOriginalApproval() throws Exception {
        service.checkApply(approval());
        var before = evidence.selectByApply(7);
        migrate("0169_datahub_remove-business-tenancy.sql");
        assertThat(evidence.selectByApply(7)).isEqualTo(before);
        assertThat(evidence.insertApproval(7, 5, "apply:7:r1", before.getApprovalScopeHash(), 12, null, "duplicate"))
                .isZero();
        assertThat(evidence.selectByApply(7)).isEqualTo(before);
    }

    @Test
    void duplicateApprovalCannotReplaceFirstActorOrEvidence() throws Exception {
        service.checkApply(approval());
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored ->
                delivery.enqueueApprovedGrant(applies.selectById(7L), 99L, 999L, "second approval")))
                .isInstanceOf(ServiceException.class);
        assertThat(scalar("SELECT actor_user_id FROM dh_market_audit_event")).isEqualTo(11);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation")).isEqualTo(1);
    }

    @Test
    void twoApprovalNodesProduceExactlyOneEvidenceAndOneIntent() throws Exception {
        var bothRead = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var guard = guard();
        doAnswer(call -> {
            bothRead.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(guard).assertCanCheckApply(any(), any());
        var first = service(guard);
        var second = service(guard);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var left = workers.submit(() -> approveInTenant(first));
            var right = workers.submit(() -> approveInTenant(second));
            try { assertThat(bothRead.await(10, TimeUnit.SECONDS)).isTrue(); }
            finally { release.countDown(); }
            assertThat(left.get(10, TimeUnit.SECONDS) + right.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation")).isEqualTo(1);
    }

    private int approveInTenant(MarketApplyService node) {
        TenantContextHolder.clear();
        try { node.checkApply(approval()); return 1; }
        catch (ServiceException conflict) { return 0; }
        finally { TenantContextHolder.clear(); }
    }

    @Test
    void freshBaselineAuditFragmentMatchesMigrationAndSupportsReplay() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("sql/postgresql/migrations"))) root = root.getParent();
        assertThat(root).isNotNull();
        String baseline = Files.readString(root.resolve("sql/postgresql/datahub-center.sql"));
        int start = baseline.indexOf("DROP TABLE IF EXISTS \"datahub-center\".\"dh_market_audit_event\"");
        int end = baseline.indexOf("-- 0067 批准凭证结构结束。", start);
        assertThat(start).isNotNegative(); assertThat(end).isGreaterThan(start);
        sql(baseline.substring(start, end));
        // 片段取自基线的中间阶段，补齐基线末尾的单租户转换；完整基线由迁移验证器独立验证。
        migrate("0169_datahub_remove-business-tenancy.sql");
        service.checkApply(approval());
        assertThat(evidence.selectByApply( 7)).isNotNull();
        assertThatThrownBy(() -> sql("DELETE FROM dh_market_audit_event")).isInstanceOf(SQLException.class);
        migrate("0169_datahub_remove-business-tenancy.sql");
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event")).isEqualTo(1);
    }

    @Test
    void approvalRequiresTransactionAndTrustedActorBeforeAnyWrites() throws Exception {
        var apply = applies.selectById(7L); apply.setStatus(2); apply.setIfAuth(1);
        assertThatThrownBy(() -> delivery.enqueueApprovedGrant(apply, 11L, 100L, "reason"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored ->
                delivery.enqueueApprovedGrant(apply, null, null, "reason"))).isInstanceOf(ServiceException.class);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event")).isZero();
    }
}
