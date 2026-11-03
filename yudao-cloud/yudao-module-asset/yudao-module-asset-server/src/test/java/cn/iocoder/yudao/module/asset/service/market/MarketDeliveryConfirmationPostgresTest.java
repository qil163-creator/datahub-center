package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketApprovalEvidenceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryOperationMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO.Action;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

/** 真实 JSONB、租户 Mapper、READ COMMITTED 事务和默认 SESSION 一级缓存。 */
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketDeliveryConfirmationPostgresTest extends SharingPostgresSupport {
    private MarketResourceApplyMapper applies;
    private MarketDeliveryOperationMapper operations;
    private MarketApprovalEvidenceMapper approvals;
    private TransactionTemplate transaction;
    private final MarketDeliveryService service = new MarketDeliveryService();

    @BeforeEach void prepare() throws Exception {
        fixture("market-freeze-fixture.sql");
        migrate("0042_asset_grant-source-delivery.sql");
        fixture("market-approval-fixture.sql");
        migrate("0066_asset_delivery-review-evidence.sql");
        migrate("0067_asset_grant-approval-evidence.sql");
        var source = new UnpooledDataSource("org.postgresql.Driver",
                jdbcUrl + "?currentSchema=datahub-center&socketTimeout=10", "sharing_test", "");
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setIsolationLevelName("ISOLATION_READ_COMMITTED");
        transaction.setTimeout(10);
        var configuration = singleTenantConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("confirmation-test", new SpringManagedTransactionFactory(), source));
        var interceptor = new MybatisPlusInterceptor();

        configuration.addInterceptor(interceptor);
        configuration.addMapper(MarketResourceApplyMapper.class);
        configuration.addMapper(MarketDeliveryOperationMapper.class);
        configuration.addMapper(MarketApprovalEvidenceMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        applies = session.getMapper(MarketResourceApplyMapper.class);
        operations = session.getMapper(MarketDeliveryOperationMapper.class);
        approvals = session.getMapper(MarketApprovalEvidenceMapper.class);
        ReflectionTestUtils.setField(service, "operationMapper", operations);
        ReflectionTestUtils.setField(service, "approvalEvidenceMapper", approvals);
        TenantContextHolder.clear();
        sql("DELETE FROM dh_market_resource_apply WHERE id=7");
        var apply = MarketDeliveryConfirmationFixture.apply();
        applies.insert(apply);
        operations.insertFrozen( MarketDeliveryConfirmationFixture.operation(service, apply));
        sql("UPDATE dh_market_delivery_operation SET status='SUCCESS',resolution_status='CONFIRMED' WHERE id=101");
    }

    private void fixture(String file) throws Exception {
        try (var input = getClass().getResourceAsStream("/sql/" + file)) {
            assertThat(input).isNotNull();
            sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @AfterEach void cleanup() { TenantContextHolder.clear(); }

    @Test void canonicalJsonbRoundTripStillConfirmsCurrentGrant() {
        var apply = applies.selectById(7L);
        var row = operations.selectLatestByApply( 7);
        assertThat(row.getPayloadJson()).isNotEqualTo(MarketDeliveryConfirmationFixture.operation(service, apply).getPayloadJson());
        assertThat(service.isConfirmedApply(apply)).isTrue();
        var command = MarketDeliveryPayload.fromJson(row.getPayloadJson());
        assertThat(approvals.insertApproval( 7, 8, "apply:7:r1",
                MarketDeliveryPayload.approvalHash( 8L, command), 11, null, "test approval")).isEqualTo(1);
        assertThat(service.isConfirmedApply(apply)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = Action.class, names = {"SUSPEND", "REVOKE"})
    void jsonbWithdrawalRetainsItsRealActionWithoutChangingStoredTechnicalType(Action action) throws Exception {
        var apply = applies.selectById(7L);
        apply.setSourceRevision(3L);
        var operation = MarketDeliveryConfirmationFixture.operation(service, apply, action);
        operation.setId(102L);
        operations.insertFrozen( operation);
        sql("UPDATE dh_market_delivery_operation SET status='SUCCESS',resolution_status='CONFIRMED' WHERE id=102");
        var before = operations.selectLatestByApply( 7);
        assertThat(before.getPayloadJson()).isNotEqualTo(operation.getPayloadJson());
        assertThat(before.getOpType()).isEqualTo("REVOKE");
        assertThat(MarketDeliveryPayload.operationAction(before)).isEqualTo(action.name());
        assertThat(service.currentDeliveryResolution( apply, before))
                .isEqualTo(action == Action.SUSPEND ? "SUSPENDED" : "REVOKED");
        assertThat(service.isConfirmedApply(apply)).isFalse();
        var after = operations.selectLatestByApply( 7);
        assertThat(after.getPayloadJson()).isEqualTo(before.getPayloadJson());
        assertThat(after.getPayloadHash()).isEqualTo(before.getPayloadHash());
        assertThat(after.getRowVersion()).isEqualTo(before.getRowVersion());
        assertThat(after.getOpType()).isEqualTo("REVOKE");
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation")).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"payload_json=jsonb_set(payload_json,'{action}','\"REVOKE\"')",
            "payload_hash=repeat('0',64)", "payload_json='{}'::jsonb", "protocol_version=1"})
    void invalidOrLegacyStoredWithdrawalRemainsUnknown(String mutation) throws Exception {
        var apply = applies.selectById(7L);
        apply.setSourceRevision(3L);
        var operation = MarketDeliveryConfirmationFixture.operation(service, apply, Action.SUSPEND);
        operation.setId(102L);
        operations.insertFrozen( operation);
        sql("UPDATE dh_market_delivery_operation SET status='SUCCESS',resolution_status='CONFIRMED',"
                + mutation + " WHERE id=102");
        var row = operations.selectLatestByApply( 7);
        assertThat(MarketDeliveryPayload.operationAction(row)).isEqualTo("UNKNOWN");
        assertThat(service.currentDeliveryResolution( apply, row)).isEqualTo("UNKNOWN");
        assertThat(service.isConfirmedApply(apply)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"revision=2", "source_revision=3", "app_id=4", "api_id=4", "api_id=NULL",
            "api_id=NULL,app_id=NULL", "source_grant_id='different'", "apply_user_id=12", "asset_id=9",
            "purpose='changed'", "duty_matter='changed'", "apply_end_time='2040-01-01'",
            "snapshot=jsonb_set(snapshot::jsonb,'{frozenColumns}','[\"secret\"]')::text",
            "snapshot=jsonb_set(snapshot::jsonb,'{databaseId}','\"21\"')::text",
            "snapshot=jsonb_set(snapshot::jsonb,'{schemaName}','\"other\"')::text",
            "snapshot=jsonb_set(snapshot::jsonb,'{tableName}','\"other\"')::text",
            "snapshot=jsonb_set(snapshot::jsonb,'{approvedContractHash}','\"other\"')::text",
            "snapshot=jsonb_set(snapshot::jsonb,'{apiVersion}','\"v2\"')::text"})
    void changedCurrentApplicationCannotBorrowConfirmedJsonb(String assignment) throws Exception {
        sql("UPDATE dh_market_resource_apply SET " + assignment + " WHERE id=7");
        assertThat(service.isConfirmedApply(applies.selectById(7L))).isFalse();
    }

    @Test void mismatchedFormalApprovalOverridesOtherwiseMatchingLedger() {
        approvals.insertApproval( 7, 8, "apply:7:r1", "0".repeat(64), 11, null, "test mismatch");
        assertThat(service.isConfirmedApply(applies.selectById(7L))).isFalse();
    }

    @Test void anotherApplicationCannotBorrowLedgerOrLegacySuccess() {
        var apply = applies.selectById(7L);
        TenantContextHolder.clear();
        apply.setId(9999L);
        assertThat(operations.selectLatestByApply(9999)).isNull();
        assertThat(service.isConfirmedApply(apply)).isFalse();
    }

    @Test void missingLedgerWithPublishedVersionIsUnknown() throws Exception {
        sql("UPDATE dh_market_resource_apply SET id=8 WHERE id=7");
        assertThat(service.isConfirmedApply(applies.selectById(8L))).isFalse();
    }

    @Test void explicitZeroVersionLegacyWithoutLedgerRemainsLimitedCompatibility() throws Exception {
        sql("UPDATE dh_market_resource_apply SET id=8,source_revision=0 WHERE id=7");
        assertThat(service.isConfirmedApply(applies.selectById(8L))).isTrue();
    }

    @Test void laterPendingOperationIsVisibleInsideSameReadCommittedTransaction() {
        var apply = applies.selectById(7L);
        transaction.executeWithoutResult(ignored -> {
            assertThat(service.isConfirmedApply(apply)).isTrue();
            // 独立连接提交另一节点的新来源操作，不能通过同一 SqlSession 的写入碰巧清缓存。
            assertThatCode(() -> sql("""
                    INSERT INTO dh_market_delivery_operation
                      (id,operation_id,apply_id,source_grant_id,op_type,app_id,api_id,
                       expected_revision,status,protocol_version,source_revision,payload_json,payload_hash,
                       row_version,attempt_count,resolution_status)
                    SELECT 102,operation_id||':next',apply_id,source_grant_id,op_type,app_id,api_id,
                       expected_revision,'PENDING',protocol_version,source_revision+1,payload_json,payload_hash,
                       0,0,'UNCONFIRMED' FROM dh_market_delivery_operation WHERE id=101
                    """)).doesNotThrowAnyException();
            assertThat(service.isConfirmedApply(apply)).isFalse();
        });
    }

    @Test void latestQueryIndependentlySeesAnotherNodeResultChange() {
        transaction.executeWithoutResult(ignored -> {
            assertThat(operations.selectLatestByApply( 7).getResolutionStatus()).isEqualTo("CONFIRMED");
            assertThatCode(() -> sql("UPDATE dh_market_delivery_operation SET resolution_status='UNKNOWN' WHERE id=101"))
                    .doesNotThrowAnyException();
            assertThat(operations.selectLatestByApply( 7).getResolutionStatus()).isEqualTo("UNKNOWN");
        });
    }

    @Test void approvalQueryDoesNotCacheAbsenceAcrossAnotherNodeCommit() {
        transaction.executeWithoutResult(ignored -> {
            assertThat(approvals.selectByApply( 7)).isNull();
            assertThatCode(() -> sql("""
                    INSERT INTO dh_market_audit_event
                      (event_id,event_type,actor_user_id,object_type,object_id,approval_ref,approval_asset_id,
                       approval_scope_hash,from_status,to_status,result)
                    VALUES ('apply:7:r1','GRANT_APPROVAL_EVIDENCE',11,'APPLY',7,'apply:7:r1',8,repeat('0',64),'1','2','APPROVED')
                    """)).doesNotThrowAnyException();
            assertThat(approvals.selectByApply( 7)).isNotNull();
        });
    }
}
