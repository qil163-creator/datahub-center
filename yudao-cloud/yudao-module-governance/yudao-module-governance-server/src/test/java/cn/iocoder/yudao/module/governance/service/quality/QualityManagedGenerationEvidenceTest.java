package cn.iocoder.yudao.module.governance.service.quality;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredSourceGenerations;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderCommandReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.*;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Types;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Pure domain/confirmation tests. Native MariaDB identity and deployment fencing need separate evidence. */
class QualityManagedGenerationEvidenceTest {
    private static final String URL = "jdbc:mariadb://fixture.invalid:43322/orders";
    private static final String USER = "fixture_reader";
    private static final String TARGET = ConfiguredSourceGenerations.targetFingerprint(ProductTypeEnum.MARIADB, URL, USER);
    private static final SourceGenerationPin G1 = new SourceGenerationPin("fixture_source_metadata",
            "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222");
    private static final SourceGenerationPin G2 = new SourceGenerationPin(G1.schema(), G1.instanceId(),
            "33333333-3333-4333-8333-333333333333");
    private static final LocalDateTime SUBMITTED = LocalDateTime.of(2026, 9, 25, 10, 0);

    @Test void capturePersistsOnlyDigestsAndPreservesJsonTimes() {
        var connection = connection(registry(G1, TARGET));
        var start = SUBMITTED.withNano(123456789);
        var read = QualityReadEvidence.capture(connection, object(), "SELECT private_column FROM private_table",
                true, start, start.plusNanos(1));
        String json = JsonUtils.toJsonString(read);
        var restored = JsonUtils.parseObject(json, QualityReadEvidence.class);
        assertThat(restored.getManagedSourceTarget()).isEqualTo(TARGET).matches("[0-9a-f]{64}");
        assertThat(restored.getManagedSourceGeneration()).isEqualTo(G1.fingerprint()).matches("[0-9a-f]{64}");
        assertThat(restored.getConfigurationRevision()).startsWith("source-v1:");
        assertThat(restored.startedTime()).isEqualTo(start);
        assertThat(restored.finishedTime()).isEqualTo(start.plusNanos(1));
        assertThat(json).doesNotContain(URL, USER, G1.schema(), G1.instanceId(), G1.generation(),
                "fixture_password", "private_column", "private_table");
    }

    @Test void missingPinIsNotSynthesizedAndForeignProductCannotCaptureIt() {
        var connection = connection(ConfiguredSourceGenerations.empty());
        var read = QualityReadEvidence.capture(connection, object(), "SELECT 1", true, SUBMITTED, SUBMITTED);
        assertThat(read.getManagedSourceTarget()).isNull();
        assertThat(read.getManagedSourceGeneration()).isNull();
        connection = connection(registry(G1, TARGET));
        connection.setProductType(ProductTypeEnum.MYSQL);
        var foreign = connection;
        assertThatThrownBy(() -> QualityReadEvidence.capture(foreign, object(), "SELECT 1", true, SUBMITTED, SUBMITTED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void currentIntegerSingleCompositeAndOrgEvidenceCanQualify(boolean composite, boolean org) {
        var fixture = new Fixture(composite, org);
        var compared = fixture.compare();
        assertThat(compared.comparable()).isTrue();
        assertThat(compared.evidence()).containsEntry("productType", "MARIADB")
                .containsEntry("managedSourceTarget", TARGET).containsEntry("managedSourceGeneration", G1.fingerprint());
        assertThat(fixture.qualifies(compared.evidence(), registry(G1, TARGET))).isTrue();
        assertThat(QualityRecheckEvidence.qualifies(fixture.attempt(compared.evidence()), fixture.order, fixture.submission))
                .isFalse();
    }

    @Test void persistedG1PassCannotQualifyWithG2EmptyOrAnotherTarget() {
        var fixture = new Fixture(false, false);
        var facts = fixture.compare().evidence();
        assertThat(fixture.qualifies(facts, registry(G1, TARGET))).isTrue();
        assertThat(fixture.qualifies(facts, registry(G2, TARGET))).isFalse();
        assertThat(fixture.qualifies(facts, ConfiguredSourceGenerations.empty())).isFalse();
        assertThat(fixture.qualifies(facts, registry(G1, DigestUtil.sha256Hex("another-target")))).isFalse();
        assertThat(fixture.qualifies(facts, null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"productType", "managedSourceTarget", "managedSourceGeneration"})
    void oldOrMalformedDecisionCannotDowngradeToUnmanaged(String field) {
        var fixture = new Fixture(false, false);
        var facts = new LinkedHashMap<>(fixture.compare().evidence());
        facts.remove(field);
        assertThat(fixture.qualifies(facts, registry(G1, TARGET))).isFalse();
        for (Object malformed : List.of("", "not-a-digest", "A".repeat(64), 123, List.of("value"))) {
            facts.put(field, malformed);
            assertThat(fixture.qualifies(facts, registry(G1, TARGET))).as("%s=%s", field, malformed).isFalse();
        }
        facts.put(field, null);
        assertThat(fixture.qualifies(facts, registry(G1, TARGET))).isFalse();
    }

    @Test void mariaDbLegacyDecisionWithoutEitherPinFieldCannotQualify() {
        var fixture = new Fixture(false, false);
        var facts = new LinkedHashMap<>(fixture.compare().evidence());
        facts.remove("managedSourceTarget"); facts.remove("managedSourceGeneration");
        assertThat(fixture.qualifies(facts, registry(G1, TARGET))).isFalse();
        facts.remove("productType");
        assertThat(fixture.qualifies(facts, registry(G1, TARGET))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POSTGRESQL", "MYSQL", "DORIS", "TIDB"})
    void otherProductsCannotBorrowManagedDecisionOrRead(String product) {
        var fixture = new Fixture(false, false);
        var facts = new LinkedHashMap<>(fixture.compare().evidence());
        facts.put("productType", product);
        assertThat(fixture.qualifies(facts, registry(G1, TARGET))).isFalse();
        fixture.oldRead.setProductType(product); fixture.newRead.setProductType(product);
        assertThat(fixture.compare().comparable()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POSTGRESQL", "MYSQL"})
    void nonManagedComparisonsAndLegacyOverloadRemainCompatible(String product) {
        var fixture = new Fixture(false, false);
        for (var read : List.of(fixture.oldRead, fixture.newRead)) {
            read.setProductType(product); read.setManagedSourceTarget(null); read.setManagedSourceGeneration(null);
            read.setConfigurationRevision("provider-revision");
        }
        var compared = fixture.compare();
        assertThat(compared.comparable()).isTrue();
        assertThat(compared.evidence()).containsEntry("productType", product)
                .doesNotContainKeys("managedSourceTarget", "managedSourceGeneration");
        assertThat(QualityRecheckEvidence.qualifies(fixture.attempt(compared.evidence()), fixture.order, fixture.submission))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothReadsRequireCompletePinAndSourceBoundRevision(boolean baseline) {
        List<Consumer<QualityReadEvidence>> invalid = List.of(
                read -> read.setManagedSourceTarget(null), read -> read.setManagedSourceGeneration(null),
                read -> read.setManagedSourceTarget("bad"), read -> read.setManagedSourceGeneration("bad"),
                read -> read.setConfigurationRevision("provider-revision"), read -> read.setConfigurationRevision(null),
                read -> read.setSchemaVersion(1), read -> read.setRelationFingerprint(null));
        for (var mutate : invalid) {
            var fixture = new Fixture(false, false);
            mutate.accept(baseline ? fixture.oldRead : fixture.newRead);
            assertThat(fixture.compare().evidence()).containsEntry("reasonCode", "READ_EVIDENCE_MISSING");
        }
    }

    @Test void mismatchedPinTargetOrRevisionCannotReuseBaseline() {
        List<Consumer<QualityReadEvidence>> changed = List.of(
                read -> read.setManagedSourceGeneration(G2.fingerprint()),
                read -> read.setManagedSourceTarget(DigestUtil.sha256Hex("other-target")),
                read -> read.setConfigurationRevision("source-v1:" + "b".repeat(64)));
        for (var mutate : changed) {
            var fixture = new Fixture(false, false);
            mutate.accept(fixture.newRead);
            assertThat(fixture.compare().evidence()).containsEntry("reasonCode", "DATASOURCE_REVISION_CHANGED");
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void mariaDbRejectsForeignKeyProtocolsAndChangedPopulation(boolean composite, boolean org) {
        var fixture = new Fixture(composite, org);
        var scope = org ? fixture.newRead.getOrgScope().getGroups().getFirst().keyScope() : fixture.newRead.getKeyScope();
        scope.setKeyHash("c".repeat(64));
        assertThat(fixture.compare().evidence()).containsEntry("reasonCode", "ROW_POPULATION_CHANGED");
        scope.setKeyHash("a".repeat(64));
        scope.setAlgorithm(composite ? AuditKeySnapshot.MYSQL_COMPOSITE_ALGORITHM : AuditKeySnapshot.MYSQL_TEXT_ALGORITHM);
        scope.setJdbcType(composite ? Types.STRUCT : Types.VARCHAR);
        assertThat(fixture.compare().evidence()).containsEntry("reasonCode", "ROW_COVERAGE_UNPROVEN");
    }

    @Test void mariaDbRejectsUnverifiedRulesCountsOnlyAndForeignOrgEncoding() {
        var fixture = new Fixture(false, false);
        fixture.definition.getRules().getFirst().setStrategyKey("RANGE_VALIDATION");
        assertThat(fixture.compare().evidence()).containsEntry("reasonCode", "RULE_DIALECT_UNVERIFIED");
        fixture.definition.getRules().getFirst().setStrategyKey("NOT_NULL_VALIDATION");
        fixture.definition.getRules().getFirst().setParamSchema("{\"column\":\"amount\"}");
        assertThat(fixture.compare().evidence()).containsEntry("reasonCode", "ROW_COVERAGE_UNPROVEN");
        fixture = new Fixture(false, true);
        fixture.newRead.getOrgScope().setGroupEncoding(AuditKeySnapshot.MYSQL_INTEGER_GROUP_ENCODING);
        assertThat(fixture.compare().evidence()).containsEntry("reasonCode", "ORG_EVIDENCE_INCOMPLETE");
        fixture.newRead.getOrgScope().setGroupEncoding(AuditKeySnapshot.MARIADB_INTEGER_GROUP_ENCODING);
        fixture.newRead.getOrgScope().setGroupJdbcType(Types.VARCHAR);
        assertThat(fixture.compare().evidence()).containsEntry("reasonCode", "ORG_EVIDENCE_INCOMPLETE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"current", "rotated", "empty", "wrong-target", "default"})
    void confirmationUsesLocalRegistryToRejectOldPass(String configuration) {
        var fixture = new Fixture(false, false);
        var service = new QualityWorkOrderServiceImpl();
        var orders = mock(QualityWorkOrderMapper.class);
        var submissions = mock(QualityWorkOrderSubmissionMapper.class);
        var attempts = mock(QualityWorkOrderRecheckMapper.class);
        var actions = mock(QualityWorkOrderActionMapper.class);
        var guard = mock(QualityWorkOrderGuard.class);
        ReflectionTestUtils.setField(service, "workOrderMapper", orders);
        ReflectionTestUtils.setField(service, "submissionMapper", submissions);
        ReflectionTestUtils.setField(service, "recheckMapper", attempts);
        ReflectionTestUtils.setField(service, "actionMapper", actions);
        ReflectionTestUtils.setField(service, "guard", guard);
        var relations = mock(QualityWorkOrderIssueMapper.class);
        when(relations.selectListByWorkOrderId(8L)).thenReturn(List.of(
                QualityWorkOrderIssueDO.builder().issueId(30L).build()));
        ReflectionTestUtils.setField(service, "workOrderIssueMapper", relations);
        var issues = mock(QualityIssueDataMapper.class);
        when(issues.selectById(30L)).thenReturn(fixture.issue);
        ReflectionTestUtils.setField(service, "issueDataMapper", issues);
        if (!"default".equals(configuration)) {
            ReflectionTestUtils.setField(service, "configuredSourceGenerations", switch (configuration) {
                case "current" -> registry(G1, TARGET);
                case "rotated" -> registry(G2, TARGET);
                case "wrong-target" -> registry(G1, DigestUtil.sha256Hex("another-target"));
                default -> ConfiguredSourceGenerations.empty();
            });
        }
        fixture.order.setOrderStatus(QualityWorkOrderDO.STATUS_PENDING_CONFIRMATION);
        fixture.order.setConfirmationPendingType(QualityWorkOrderDO.CONFIRM_RECHECK);
        fixture.order.setLockVersion(1);
        fixture.submission.setStatus(QualityWorkOrderSubmissionDO.STATUS_ACTIVE);
        fixture.submission.setSubmittedBy(9L);
        fixture.submission.setRecheckStatus("PASS"); fixture.submission.setComparableFlag(1);
        when(guard.requireUserId()).thenReturn(4L);
        when(guard.has(4L, QualityWorkOrderGuard.PERM_CONFIRM)).thenReturn(true);
        when(orders.selectById(8L)).thenReturn(fixture.order);
        when(orders.selectByIdForUpdate(8L)).thenReturn(fixture.order);
        when(submissions.selectById(40L)).thenReturn(fixture.submission);
        when(attempts.selectByInstance(20L)).thenReturn(fixture.attempt(fixture.compare().evidence()));
        var request = new QualityWorkOrderCommandReqVO();
        request.setId(8L); request.setSubmissionId(40L); request.setRequestId("managed-confirm");
        request.setExpectedVersion(1); request.setConfirmAction("VERIFY_PASS");
        TenantContextHolder.clear();
        try {
            if ("current".equals(configuration)) {
                var instances = mock(QualityTaskInstanceMapper.class);
                var audits = mock(QualityAuditLogMapper.class);
                ReflectionTestUtils.setField(service, "instanceMapper", instances);
                ReflectionTestUtils.setField(service, "auditLogMapper", audits);
                when(instances.selectById(20L)).thenReturn(fixture.recheck);
                when(instances.selectById(10L)).thenReturn(fixture.original);
                when(audits.selectById(100L)).thenReturn(fixture.oldAudit);
                when(audits.selectList(any())).thenReturn(List.of(fixture.newAudit));
                com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                        new org.apache.ibatis.builder.MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(),
                                "managed-generation-audit"), QualityAuditLogDO.class);
                com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                        new org.apache.ibatis.builder.MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(),
                                "managed-generation"), QualityWorkOrderDO.class);
                when(orders.update(any(), any())).thenReturn(1);
                assertThat(service.confirm(request).getCloseType()).isEqualTo(QualityWorkOrderDO.CLOSE_VERIFIED_PASS);
                verify(orders).update(any(), any());
            } else {
                assertThatThrownBy(() -> service.confirm(request)).isInstanceOf(ServiceException.class)
                        .extracting("code").isEqualTo(QUALITY_WORK_ORDER_RECHECK_NOT_PASS.getCode());
                assertThat(fixture.order.getOrderStatus()).isEqualTo(QualityWorkOrderDO.STATUS_PENDING_CONFIRMATION);
                verify(orders, never()).update(any(), any());
                verify(actions, never()).insert(any(QualityWorkOrderActionDO.class));
            }
        } finally {
            TenantContextHolder.clear();
        }
    }

    private static ConfiguredSourceGenerations registry(SourceGenerationPin pin, String target) {
        var config = new DbSwitchProperties.SourceGeneration();
        config.setProduct(ProductTypeEnum.MARIADB); config.setTargetFingerprint(target);
        config.setSchema(pin.schema()); config.setInstanceId(pin.instanceId()); config.setGeneration(pin.generation());
        config.setRestoreRotationRequired(true);
        return new ConfiguredSourceGenerations(Map.of("fixture", config));
    }

    private static MasterDatabaseResolver.ConnectionInfo connection(ConfiguredSourceGenerations registry) {
        var api = mock(DatabaseApi.class);
        var resolver = new MasterDatabaseResolver();
        ReflectionTestUtils.setField(resolver, "databaseApi", api);
        ReflectionTestUtils.setField(resolver, "configuredSourceGenerations", registry);
        var dto = new DatabaseConnectionRespDTO();
        dto.setId(4L); dto.setProductType("MARIADB"); dto.setJdbcUrl(URL); dto.setUsername(USER);
        dto.setPassword("fixture_password"); dto.setSchema("orders"); dto.setUsageType(1);
        dto.setConfigurationRevision("provider-revision");
        when(api.getDatabaseConnection(4L)).thenReturn(success(dto));
        return resolver.resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS);
    }

    private static QualityCheckObjectDO object() {
        return QualityCheckObjectDO.builder().id(3L).datasourceConnId(4L).datasourceType("THIRD_PARTY")
                .tableName("orders").build();
    }

    private static class Fixture {
        final QualityWorkOrderDO order = QualityWorkOrderDO.builder().id(8L).taskId(1L).currentSubmissionId(40L).build();
        final QualityWorkOrderSubmissionDO submission = QualityWorkOrderSubmissionDO.builder().id(40L)
                .workOrderId(8L).instanceId(20L).revision(1).submittedAt(SUBMITTED)
                .payloadHash("b".repeat(64)).issueIds("[30]").build();
        final QualityTaskExecutor.GateDefinition definition = new QualityTaskExecutor.GateDefinition();
        final QualityTaskInstanceDO original = run(10L, SUBMITTED.minusHours(2));
        final QualityTaskInstanceDO recheck = run(20L, SUBMITTED.plusMinutes(1));
        final QualityAuditLogDO oldAudit = audit(100L, original, 2L);
        final QualityAuditLogDO newAudit = audit(200L, recheck, 0L);
        final QualityIssueDataDO issue = QualityIssueDataDO.builder().id(30L).taskId(1L).instanceId(10L)
                .auditLogId(100L).ruleId(2L).objectId(3L).tableName("orders").checkField("amount")
                .statScope("ALL").issueCount(2L).build();
        final QualityReadEvidence oldRead;
        final QualityReadEvidence newRead;

        Fixture(boolean composite, boolean org) {
            definition.setTask(QualityTaskDO.builder().id(1L).orgField(org ? "org_id" : null).build());
            definition.setObjects(List.of(object()));
            definition.setRules(List.of(QualityRuleDO.builder().id(2L).ruleType("NOT_NULL")
                    .strategyKey("NOT_NULL_VALIDATION").checkLevel("FIELD").status(0)
                    .paramSchema(composite ? "{\"column\":\"amount\",\"pkColumns\":[\"id\",\"part\"]}"
                            : "{\"column\":\"amount\",\"pkColumn\":\"id\"}").build()));
            if (org) { issue.setStatScope("ORG"); issue.setRegionCode("7"); issue.setOrgId(7L); }
            oldRead = read(oldAudit, composite, org);
            newRead = read(newAudit, composite, org);
        }

        QualityRecheckEvidence.Comparison compare() {
            original.setDefinitionSnapshot(JsonUtils.toJsonString(definition));
            recheck.setDefinitionSnapshot(JsonUtils.toJsonString(definition));
            oldAudit.setReadEvidence(JsonUtils.toJsonString(oldRead));
            newAudit.setReadEvidence(JsonUtils.toJsonString(newRead));
            return QualityRecheckEvidence.compare(order, issue, original, oldAudit, recheck, newAudit, SUBMITTED);
        }

        QualityWorkOrderRecheckDO attempt(Map<String, Object> facts) {
            var attempt = QualityWorkOrderRecheckDO.builder().id(50L).attemptNo(1).requestKey("recheck:50")
                    .requestedBy(60L).workOrderId(8L).submissionId(40L).instanceId(20L)
                    .status("PASS").comparableFlag(1).build();
            attempt.setEvidenceJson(JsonUtils.toJsonString(QualityRecheckEvidence.decision(order, submission, attempt,
                    "PASS", true, List.of(facts))));
            return attempt;
        }

        boolean qualifies(Map<String, Object> facts, ConfiguredSourceGenerations registry) {
            return QualityRecheckEvidence.qualifies(attempt(facts), order, submission, registry);
        }

        private static QualityTaskInstanceDO run(long id, LocalDateTime start) {
            return QualityTaskInstanceDO.builder().id(id).taskId(1L).instanceStatus("SUCCESS")
                    .startTime(start).endTime(start.plusSeconds(30)).build();
        }

        private static QualityAuditLogDO audit(long id, QualityTaskInstanceDO run, long fail) {
            return QualityAuditLogDO.builder().id(id).taskId(1L).instanceId(run.getId()).ruleId(2L).checkObjectId(3L)
                    .tableName("orders").checkField("amount").resultType(fail == 0 ? "PASS" : "FAIL")
                    .totalCount(10L).failCount(fail).checkTime(run.getStartTime().plusSeconds(1))
                    .resultSql("SELECT COUNT(*), SUM(amount IS NULL) FROM orders").build();
        }

        private static QualityReadEvidence read(QualityAuditLogDO audit, boolean composite, boolean org) {
            var read = QualityReadEvidence.capture(connection(registry(G1, TARGET)), object(), audit.getResultSql(),
                    true, audit.getCheckTime(), audit.getCheckTime().plusSeconds(1));
            read.setSchemaVersion(composite ? QualityReadEvidence.COMPOSITE_SCHEMA_VERSION : QualityReadEvidence.RELATION_SCHEMA_VERSION);
            read.setRelationFingerprint("f".repeat(64));
            var keys = new QualityReadEvidence.KeyScope();
            keys.setSchemaVersion(1); keys.setComplete(true);
            keys.setColumn(composite ? null : "id"); keys.setColumns(composite ? List.of("id", "part") : null);
            keys.setAlgorithm(composite ? AuditKeySnapshot.MARIADB_COMPOSITE_ALGORITHM : AuditKeySnapshot.ALGORITHM);
            keys.setJdbcType(composite ? Types.STRUCT : Types.BIGINT); keys.setKeyHash("a".repeat(64));
            keys.setKeyCount(10L); keys.setTotal(10L); keys.setFail(audit.getFailCount()); keys.setSqlHash(read.getSqlHash());
            if (org) {
                var scope = new QualityReadEvidence.OrgScope();
                scope.setSchemaVersion(2); scope.setComplete(true); scope.setField("org_id");
                scope.setGroupEncoding(AuditKeySnapshot.MARIADB_INTEGER_GROUP_ENCODING); scope.setGroupJdbcType(Types.BIGINT);
                scope.setSqlHash(DigestUtil.sha256Hex("fixture grouped SQL")); keys.setSqlHash(scope.getSqlHash());
                scope.setStartedAt(read.finishedTime().plusSeconds(1).toString());
                scope.setFinishedAt(read.finishedTime().plusSeconds(2).toString());
                keys.setGroupKeyHash(DigestUtil.sha256Hex("7"));
                scope.setGroups(List.of(new QualityReadEvidence.OrgCount(keys.getGroupKeyHash(), 10L,
                        audit.getFailCount(), audit.getResultType(), keys)));
                read.setOrgScope(scope);
            } else {
                read.setKeyScope(keys);
            }
            return read;
        }
    }
}
