package cn.iocoder.yudao.module.asset.service.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.SQLException;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryOperationMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketAuditEventMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryEvidenceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryReviewMapper;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDeliveryOperationDO;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceResultDTO;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.session.SqlSession;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import cn.iocoder.yudao.module.supply.api.app.SupplyGrantSourceApi;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketDeliveryLeasePostgresTest extends SharingPostgresSupport {
    private static final String MIGRATION = "0042_asset_grant-source-delivery.sql";

    @ParameterizedTest
    @ValueSource(strings = {"claim", "complete"})
    void failedDeliveryDoesNotBlockLaterRowsAndRecoveryQueriesBeforeResending(String failureStage) throws Exception {
        migrate(MIGRATION);
        var dataSource = new UnpooledDataSource("org.postgresql.Driver", jdbcUrl + "?currentSchema=datahub-center",
                "sharing_test", "");
        var manager = new DataSourceTransactionManager(dataSource);
        var config = singleTenantConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("delivery-isolation-test", new SpringManagedTransactionFactory(), dataSource));
        config.addMapper(MarketDeliveryOperationMapper.class);
        var mapper = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config))
                .getMapper(MarketDeliveryOperationMapper.class);
        var provider = mock(SupplyGrantSourceApi.class);
        var coordinator = new MarketDeliveryCoordinator(mapper, mock(MarketAuditEventMapper.class), provider, manager,
                mock(MarketDeliveryEvidenceService.class), mock(MarketDeliveryReviewMapper.class),
                mock(MarketDeliveryEvidenceMapper.class));
        var commands = new java.util.ArrayList<SupplyGrantSourceCommandDTO>();
        for (long id = 1; id <= 2; id++) {
            var command = new SupplyGrantSourceCommandDTO(2, "asset:asset:apply:" + id + ":r1:REVOKE", "asset",
                    "asset:apply:" + id, 1, 1L, 2L, SupplyGrantSourceCommandDTO.Action.REVOKE, null, "撤销");
            commands.add(command);
            mapper.insertFrozen( MarketDeliveryOperationDO.builder().id(id).operationId(command.operationId())
                    .applyId(id).sourceGrantId("apply:" + id + ":r1").expectedRevision(1).opType("REVOKE")
                    .appId(1L).apiId(2L).sourceRevision(1L).payloadJson(MarketDeliveryPayload.toJson(command))
                    .payloadHash(MarketDeliveryPayload.hash( command)).build());
            var receipt = new SupplyGrantSourceResultDTO(command.operationId(), SupplyGrantSourceResultDTO.Outcome.APPLIED,
                    null, 1, id, MarketDeliveryPayload.hash( command));
            when(provider.applySource(command)).thenAnswer(ignored -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE status='RUNNING'"))
                        .isPositive();
                return CommonResult.success(receipt);
            });
            when(provider.querySourceOperation("asset", command.operationId())).thenAnswer(ignored -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return CommonResult.success(receipt);
            });
        }
        String rejectedStatus = "claim".equals(failureStage) ? "RUNNING" : "SUCCESS";
        sql("ALTER TABLE dh_market_delivery_operation ADD CONSTRAINT test_delivery_failure CHECK (id<>1 OR status<>'"
                + rejectedStatus + "')");
        assertThatThrownBy(() -> coordinator.drainDue( "node-a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("交付重试部分失败：已回写 1 条，失败 1 条").hasNoCause();
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE id=2 AND status='SUCCESS'"
                + " AND resolution_status='CONFIRMED' AND attempt_count=1")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE id=1 AND status='"
                + ("claim".equals(failureStage) ? "PENDING" : "RUNNING") + "'")).isEqualTo(1);
        // 回写失败不能推断远端失败、不能立即重投。只有租约到期，其他节点才按原回执恢复。
        sql("ALTER TABLE dh_market_delivery_operation DROP CONSTRAINT test_delivery_failure");
        if ("complete".equals(failureStage)) {
            assertThat(coordinator.drainDue( "node-b")).isZero();
            sql("UPDATE dh_market_delivery_operation SET lease_until=(clock_timestamp() AT TIME ZONE 'Asia/Shanghai')"
                    + "-interval '1 second' WHERE id=1");
        }
        assertThat(coordinator.drainDue( "node-b")).isEqualTo(1);
        assertThat(coordinator.drainDue( "node-b")).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE status='SUCCESS'"
                + " AND resolution_status='CONFIRMED'")).isEqualTo(2);
        for (var command : commands) {
            org.mockito.Mockito.verify(provider).applySource(command);
        }
        org.mockito.Mockito.verify(provider, org.mockito.Mockito.times("complete".equals(failureStage) ? 1 : 0))
                .querySourceOperation("asset", commands.getFirst().operationId());
    }

    @Test
    void staleTokenAndExpiredLeaseCannotComplete() throws Exception {
        migrate(MIGRATION);
        try (var session = session()) {
            var mapper = session.getMapper(MarketDeliveryOperationMapper.class);
            var row = MarketDeliveryOperationDO.builder().id(1L).operationId("op1").applyId(9L)
                    .sourceGrantId("apply:9").opType("GRANT").appId(1L).apiId(2L)
                    .sourceRevision(1L).payloadJson("{\"protocolVersion\":2}").payloadHash("hash").build();
            assertThat(mapper.insertFrozen( row)).isEqualTo(1);
            var a = mapper.claim( 1, "node-a");
            assertThat(a.getRowVersion()).isEqualTo(1);
            assertThat(a.getPayloadJson()).contains("protocolVersion");
            assertThat(mapper.claim( 1, "node-b")).isNull();
            assertThat(mapper.completeClaim( 999, "node-a", 1, "SUCCESS", "CONFIRMED", "ok", null)).isZero();
            assertThat(mapper.completeClaim( 1, "node-a", 0, "SUCCESS", "CONFIRMED", "ok", null)).isZero();
            sql("UPDATE dh_market_delivery_operation SET lease_until=(clock_timestamp() AT TIME ZONE 'Asia/Shanghai')-interval '1 second' WHERE id=1");
            assertThat(mapper.completeClaim( 1, "node-a", 1, "SUCCESS", "CONFIRMED", "ok", null)).isZero();
            var b = mapper.claim( 1, "node-b");
            assertThat(b.getRowVersion()).isEqualTo(2);
            assertThat(b.getResolutionStatus()).isEqualTo("UNKNOWN");
            assertThat(mapper.completeClaim( 1, "node-a", 1, "SUCCESS", "CONFIRMED", "old", null)).isZero();
            assertThat(mapper.completeClaim( 1, "node-b", 2, "RETRY", "UNKNOWN", null, "response lost")).isEqualTo(1);
            assertThat(mapper.claim( 1, "too-early")).isNull();
            assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE resolution_status='UNKNOWN' AND row_version=3")).isEqualTo(1);
        }
    }

    @Test
    void frozenInsertPopulatesRowIdForImmediateClaim() throws Exception {
        // 公共夹具已与现网基线一致使用序列默认主键；未预置主键时必须由数据库生成并回填。
        migrate(MIGRATION);
        try (var session = session()) {
            var mapper = session.getMapper(MarketDeliveryOperationMapper.class);
            var row = MarketDeliveryOperationDO.builder().operationId("generated-op").applyId(9L)
                    .sourceGrantId("apply:9").opType("REVOKE").appId(1L).apiId(2L)
                    .sourceRevision(1L).payloadJson("{\"protocolVersion\":2}").payloadHash("hash").build();
            assertThat(mapper.insertFrozen( row)).isEqualTo(1);
            assertThat(row.getId()).isNotNull().isPositive();
            assertThat(scalar("SELECT id FROM dh_market_delivery_operation WHERE operation_id='generated-op'"))
                    .isEqualTo(row.getId());
            assertThat(mapper.claim( row.getId(), "node-a")).isNotNull();
        }
    }

    @Test
    void afterCommitClaimAndCompletionAreVisibleFromAnotherConnection() throws Exception {
        migrate(MIGRATION);
        var dataSource = new UnpooledDataSource("org.postgresql.Driver", jdbcUrl + "?currentSchema=datahub-center",
                "sharing_test", "");
        var transactionManager = new DataSourceTransactionManager(dataSource);
        var config = singleTenantConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("sharing-spring-test", new SpringManagedTransactionFactory(), dataSource));
        config.addMapper(MarketDeliveryOperationMapper.class);
        var mapper = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config))
                .getMapper(MarketDeliveryOperationMapper.class);
        var coordinator = new MarketDeliveryCoordinator(mapper, mock(cn.iocoder.yudao.module.asset.dal.mysql.market.MarketAuditEventMapper.class),
                mock(SupplyGrantSourceApi.class), transactionManager, mock(MarketDeliveryEvidenceService.class),
                mock(MarketDeliveryReviewMapper.class), mock(MarketDeliveryEvidenceMapper.class));
        var row = MarketDeliveryOperationDO.builder().operationId("after-commit-op").applyId(9L)
                .sourceGrantId("apply:9").opType("REVOKE").appId(1L).apiId(2L)
                .sourceRevision(1L).payloadJson("{\"protocolVersion\":2}").payloadHash("hash").build();
        AtomicReference<MarketDeliveryOperationDO> claimed = new AtomicReference<>();
        new TransactionTemplate(transactionManager).execute(ignored -> {
            assertThat(mapper.insertFrozen( row)).isEqualTo(1);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    claimed.set(coordinator.claim( row.getId(), "node-a").orElseThrow());
                }
            });
            return null;
        });
        assertThat(claimed.get()).isNotNull();
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE operation_id='after-commit-op' AND status='RUNNING'"))
                .isEqualTo(1);
        assertThat(coordinator.complete( claimed.get(), "SUCCESS", "CONFIRMED", "8", null)).isTrue();
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE operation_id='after-commit-op' AND status='SUCCESS'"))
                .isEqualTo(1);
    }

    @Test
    void dueScanUsesDatabaseTimeAndDoesNotClaimUnfrozenLegacy() throws Exception {
        migrate(MIGRATION);
        sql("INSERT INTO dh_market_delivery_operation(id,operation_id,status) VALUES(1,'legacy','PENDING')");
        try (var session = session()) {
            var mapper = session.getMapper(MarketDeliveryOperationMapper.class);
            assertThat(mapper.selectDueV2()).isEmpty();
            assertThat(mapper.claim( 1, "node")).isNull();
        }
    }

    @Test
    void manualRecheckCasIsObjectScopedVersionFencedAndNeverRequeued() throws Exception {
        migrate(MIGRATION);
        try (var session = session()) {
            var mapper = session.getMapper(MarketDeliveryOperationMapper.class);
            var row = MarketDeliveryOperationDO.builder().id(1L).operationId("manual-original").applyId(9L)
                    .sourceGrantId("apply:9").opType("REVOKE").appId(1L).apiId(2L)
                    .sourceRevision(1L).payloadJson("{\"protocolVersion\":2}").payloadHash("hash").build();
            assertThat(mapper.insertFrozen( row)).isEqualTo(1);
            sql("UPDATE dh_market_delivery_operation SET status='MANUAL',resolution_status='UNKNOWN' WHERE id=1");

            var before = mapper.selectManualUnknown( 1);
            assertThat(before).isNotNull();
            assertThat(mapper.selectManualUnknown(999)).isNull();
            assertThat(mapper.selectByOperationId("missing-operation")).isNull();
            assertThat(mapper.reconcileManualUnknown( before, "SUCCESS", "CONFIRMED", "8", null)).isEqualTo(1);
            assertThat(mapper.reconcileManualUnknown( before, "SUCCESS", "CONFIRMED", "8", null)).isZero();
            assertThat(mapper.selectManualUnknown( 1)).isNull();
            assertThat(mapper.selectDueV2()).isEmpty();
            assertThat(mapper.selectByOperationId( "manual-original").getResultRef()).isEqualTo("8");
        }
    }

    @Test
    void manualRecheckCanQuarantineAnUnusableNullPayloadWithoutSending() throws Exception {
        migrate(MIGRATION);
        try (var session = session()) {
            var mapper = session.getMapper(MarketDeliveryOperationMapper.class);
            var row = MarketDeliveryOperationDO.builder().id(1L).operationId("broken-original").applyId(9L)
                    .sourceGrantId("apply:9").opType("GRANT").appId(1L).apiId(2L)
                    .sourceRevision(1L).payloadJson("{\"protocolVersion\":2}").payloadHash("hash").build();
            assertThat(mapper.insertFrozen( row)).isEqualTo(1);
            sql("UPDATE dh_market_delivery_operation SET status='MANUAL',resolution_status='UNKNOWN',"
                    + "payload_json=NULL,payload_hash=NULL,source_revision=NULL WHERE id=1");

            var broken = mapper.selectManualUnknown( 1);
            assertThat(mapper.reconcileManualUnknown( broken, "MANUAL", "REVIEW_REQUIRED", null,
                    "冻结载荷不可用")).isEqualTo(1);
            assertThat(mapper.selectManualUnknown( 1)).isNull();
            assertThat(mapper.selectByOperationId( "broken-original").getResolutionStatus())
                    .isEqualTo("REVIEW_REQUIRED");
        }
    }

    @Test
    void manualRecheckAndAuditCommitTogetherOrBothRollBack() throws Exception {
        migrate(MIGRATION);
        sql("CREATE SEQUENCE dh_market_audit_event_seq");
        sql("""
                CREATE TABLE dh_market_audit_event (
                  id bigint PRIMARY KEY DEFAULT nextval('dh_market_audit_event_seq'),
                  event_id varchar(64) NOT NULL, event_type varchar(32) NOT NULL,
                  actor_user_id bigint, dept_id bigint, object_type varchar(32) NOT NULL,
                  object_id bigint, from_status varchar(32), to_status varchar(32),
                  reason varchar(500), result varchar(32), occurred_at timestamp(6) NOT NULL,
                  correlation_id varchar(64), tenant_id bigint NOT NULL DEFAULT 1,
                  creator varchar(64), updater varchar(64), create_time timestamp(6),
                  update_time timestamp(6), deleted smallint DEFAULT 0
                )
                """);
        var command = new SupplyGrantSourceCommandDTO(2, "manual-audit-op", "asset", "asset:apply:9",
                1, 1L, 2L, SupplyGrantSourceCommandDTO.Action.REVOKE, null, "撤销");
        var frozen = MarketDeliveryOperationDO.builder().id(1L).operationId(command.operationId()).applyId(9L)
                .sourceGrantId("apply:9").opType("REVOKE").appId(1L).apiId(2L)
                .sourceRevision(1L).payloadJson(MarketDeliveryPayload.toJson(command))
                .payloadHash(MarketDeliveryPayload.hash( command)).build();
        var dataSource = new UnpooledDataSource("org.postgresql.Driver", jdbcUrl + "?currentSchema=datahub-center",
                "sharing_test", "");
        var transactionManager = new DataSourceTransactionManager(dataSource);
        var config = singleTenantConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("sharing-review-test", new SpringManagedTransactionFactory(), dataSource));
        config.addMapper(MarketDeliveryOperationMapper.class);
        config.addMapper(MarketAuditEventMapper.class);
        var sqlSession = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        var mapper = sqlSession.getMapper(MarketDeliveryOperationMapper.class);
        var auditMapper = sqlSession.getMapper(MarketAuditEventMapper.class);
        var supplyApi = mock(SupplyGrantSourceApi.class);
        when(supplyApi.querySourceOperation("asset", command.operationId())).thenReturn(CommonResult.success(
                new SupplyGrantSourceResultDTO(command.operationId(), SupplyGrantSourceResultDTO.Outcome.APPLIED,
                        null, 1, 8, frozen.getPayloadHash())));
        // 本例只验证真实 CAS/审计事务；完整授权、锁及身份竞争由 MarketDeliveryRecheckPostgresTest 覆盖。
        var evidence = mock(MarketDeliveryEvidenceService.class);
        when(evidence.authorizeCurrent( org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> new MarketDeliveryEvidenceService.Snapshot(
                        mapper.selectByOperationId( invocation.getArgument(0)), null, null));
        var coordinator = new MarketDeliveryCoordinator(mapper, auditMapper, supplyApi, transactionManager, evidence,
                mock(MarketDeliveryReviewMapper.class), mock(MarketDeliveryEvidenceMapper.class));
        assertThat(mapper.insertFrozen( frozen)).isEqualTo(1);
        sql("UPDATE dh_market_delivery_operation SET status='MANUAL',resolution_status='UNKNOWN' WHERE id=1");

        assertThat(coordinator.reconcileManualUnknown( mapper.selectByOperationId( command.operationId()),
                91, 7L, "原回执核查")).isTrue();
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE id=1 AND status='SUCCESS' "
                + "AND resolution_status='CONFIRMED'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE object_id=1 "
                + "AND actor_user_id=91 AND event_type='DELIVERY_RECHECK'")).isEqualTo(1);

        var otherCommand = new SupplyGrantSourceCommandDTO(2, "manual-audit-op-2", "asset", "asset:apply:9",
                1, 1L, 2L, SupplyGrantSourceCommandDTO.Action.REVOKE, null, "撤销");
        var otherFrozen = MarketDeliveryOperationDO.builder().id(2L).operationId(otherCommand.operationId()).applyId(9L)
                .sourceGrantId("apply:9").opType("REVOKE").appId(1L).apiId(2L)
                .sourceRevision(1L).payloadJson(MarketDeliveryPayload.toJson(otherCommand))
                .payloadHash(MarketDeliveryPayload.hash( otherCommand)).build();
        when(supplyApi.querySourceOperation("asset", otherCommand.operationId())).thenReturn(CommonResult.success(
                new SupplyGrantSourceResultDTO(otherCommand.operationId(), SupplyGrantSourceResultDTO.Outcome.APPLIED,
                        null, 1, 9, otherFrozen.getPayloadHash())));
        assertThat(mapper.insertFrozen( otherFrozen)).isEqualTo(1);
        sql("UPDATE dh_market_delivery_operation SET status='MANUAL',resolution_status='UNKNOWN' WHERE id=2");
        assertThat(coordinator.reconcileManualUnknown( mapper.selectByOperationId( otherCommand.operationId()),
                92, 8L, "另一操作原回执核查")).isTrue();
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE object_id=2 "
                + "AND actor_user_id=92 AND event_type='DELIVERY_RECHECK'")).isEqualTo(1);

        sql("UPDATE dh_market_delivery_operation SET status='MANUAL',resolution_status='UNKNOWN' WHERE id=1");
        sql("DROP TABLE dh_market_audit_event");
        assertThatThrownBy(() -> coordinator.reconcileManualUnknown(
                mapper.selectByOperationId( command.operationId()), 91, 7L, "审计失败须回滚"))
                .isInstanceOf(RuntimeException.class);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE id=1 "
                + "AND status='MANUAL' AND resolution_status='UNKNOWN'")).isEqualTo(1);
    }

    private SqlSession session() {
        var dataSource = new UnpooledDataSource("org.postgresql.Driver", jdbcUrl + "?currentSchema=datahub-center", "sharing_test", "");
        var config = singleTenantConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        config.setEnvironment(new Environment("sharing-test", new JdbcTransactionFactory(), dataSource));
        config.addMapper(MarketDeliveryOperationMapper.class);
        return new MybatisSqlSessionFactoryBuilder().build(config).openSession(true);
    }

    @Test
    void replayAndRenewalRequestUniqueness() throws Exception {
        migrate(MIGRATION);
        migrate(MIGRATION);
        sql("INSERT INTO dh_market_resource_apply(id,renewal_of_apply_id,renewal_operation_id) VALUES(1,8,'renew-1')");
        assertThatThrownBy(() -> sql("INSERT INTO dh_market_resource_apply(id,renewal_of_apply_id,renewal_operation_id) VALUES(2,8,'renew-1')"))
                .isInstanceOf(SQLException.class);
        singleTenantConfiguration();
        assertThatThrownBy(() -> sql("INSERT INTO dh_market_resource_apply(id,renewal_of_apply_id,renewal_operation_id) VALUES(3,8,'renew-1')"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void oldUnfrozenPayloadIsMarkedForReviewNotReconstructed() throws Exception {
        sql("INSERT INTO dh_market_delivery_operation(id,operation_id,status) VALUES(1,'old','RUNNING')");
        migrate(MIGRATION);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE id=1 AND resolution_status='REVIEW_REQUIRED' AND payload_json IS NULL")).isEqualTo(1);
    }

    @Test
    void missingPrerequisitesAndDuplicateOperationIdentityAbort() throws Exception {
        sql("INSERT INTO dh_market_delivery_operation(id,operation_id,status) VALUES(1,'same','SUCCESS'),(2,'same','SUCCESS')");
        assertThatThrownBy(() -> migrate(MIGRATION)).isInstanceOf(SQLException.class).hasMessageContaining("重复");
        sql("DROP TABLE dh_asset_grant_source");
        assertThatThrownBy(() -> migrate(MIGRATION)).isInstanceOf(SQLException.class).hasMessageContaining("前置");
    }
}
