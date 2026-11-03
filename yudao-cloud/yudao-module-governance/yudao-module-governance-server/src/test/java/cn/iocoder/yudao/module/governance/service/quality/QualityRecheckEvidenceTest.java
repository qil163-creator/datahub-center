package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class QualityRecheckEvidenceTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path driverDirectory;
    private final QualityWorkOrderServiceImpl service = new QualityWorkOrderServiceImpl();
    private final QualityTaskInstanceMapper instances = mock(QualityTaskInstanceMapper.class);
    private final QualityAuditLogMapper audits = mock(QualityAuditLogMapper.class);
    private final QualityIssueDataMapper issues = mock(QualityIssueDataMapper.class);
    private final QualityWorkOrderIssueMapper relations = mock(QualityWorkOrderIssueMapper.class);
    private final QualityTaskExecutor.GateDefinition baseline = definition();
    private final QualityTaskExecutor.GateDefinition current = definition();
    private QualityTaskInstanceDO original;
    private QualityTaskInstanceDO recheck;
    private QualityAuditLogDO originalAudit;
    private QualityAuditLogDO recheckAudit;
    private QualityIssueDataDO issue;
    private final LocalDateTime submittedAt = LocalDateTime.of(2026, 9, 23, 10, 0);

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "instanceMapper", instances);
        ReflectionTestUtils.setField(service, "auditLogMapper", audits);
        ReflectionTestUtils.setField(service, "issueDataMapper", issues);
        ReflectionTestUtils.setField(service, "workOrderIssueMapper", relations);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), "evidence"),
                QualityAuditLogDO.class);
        original = QualityTaskInstanceDO.builder().id(10L).taskId(1L).instanceStatus("SUCCESS")
                .startTime(submittedAt.minusHours(2)).endTime(submittedAt.minusHours(1)).build();
        recheck = QualityTaskInstanceDO.builder().id(20L).taskId(1L).instanceStatus("SUCCESS")
                .startTime(submittedAt.plusMinutes(1)).endTime(submittedAt.plusMinutes(2)).build();
        originalAudit = audit(100L, 10L, "FAIL", 10L);
        originalAudit.setCheckTime(submittedAt.minusHours(2).plusSeconds(1));
        recheckAudit = audit(200L, 20L, "PASS", 0L);
        issue = QualityIssueDataDO.builder().id(30L).instanceId(10L).auditLogId(100L).taskId(1L)
                .ruleId(2L).objectId(3L).tableName("orders").checkField("amount")
                .statScope("ALL").issueCount(10L).checkTime(originalAudit.getCheckTime()).build();
        when(relations.selectListByWorkOrderId(8L)).thenReturn(List.of(QualityWorkOrderIssueDO.builder().issueId(30L).build()));
        when(issues.selectById(30L)).thenReturn(issue);
        when(instances.selectById(10L)).thenReturn(original);
        when(audits.selectById(100L)).thenReturn(originalAudit);
        when(audits.selectList(any())).thenReturn(List.of(recheckAudit));
        originalAudit.setReadEvidence(readEvidence(originalAudit));
        recheckAudit.setReadEvidence(readEvidence(recheckAudit));
    }

    @Test void changedThresholdUnderSameRuleIdIsNotComparable() {
        current.getRules().getFirst().setParamSchema("{\"column\":\"amount\",\"min\":0}");
        assertWaiting();
    }

    @Test void changedFieldUnderSameRuleIdIsNotComparable() {
        current.getRules().getFirst().setParamSchema("{\"column\":\"other_amount\",\"min\":10}");
        assertWaiting();
    }

    @Test void smallerFilterWithEnoughRowsIsNotComparable() {
        current.getRules().getFirst().setParamSchema("{\"column\":\"amount\",\"min\":10,\"where\":\"region=1\"}");
        assertWaiting();
    }

    @Test void changedDatasourceUnderSameObjectIdIsNotComparable() {
        current.getObjects().getFirst().setDatasourceConnId(999L);
        assertWaiting();
    }

    @Test void configuredDriverRevisionChangesRequireNewBaselineEvenWithPassingRows() throws Exception {
        var jar = cn.iocoder.yudao.module.governance.service.master.ConfiguredDriverTestSupport
                .copyDriver(driverDirectory, "node-a.jar");
        var other = java.nio.file.Files.copy(jar, driverDirectory.resolve("node-b.jar"));
        String revision = cn.iocoder.yudao.module.governance.service.master.ConfiguredDriverTestSupport
                .resolver(jar, "same-provider-revision").resolve(4L,
                        cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_CHECK_OBJECT_NOT_EXISTS)
                .getConfigurationRevision();
        for (var audit : List.of(originalAudit, recheckAudit)) {
            var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
            read.setConfigurationRevision(revision); audit.setReadEvidence(JsonUtils.toJsonString(read));
        }
        String otherRevision = cn.iocoder.yudao.module.governance.service.master.ConfiguredDriverTestSupport
                .resolver(other, "same-provider-revision").resolve(4L,
                        cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_CHECK_OBJECT_NOT_EXISTS)
                .getConfigurationRevision();
        assertThat(otherRevision).isEqualTo(revision);
        assertThat(judge().recheckStatus()).isEqualTo("PASS");
        java.nio.file.Files.write(other, new byte[]{0}, java.nio.file.StandardOpenOption.APPEND);
        var read = JsonUtils.parseObject(recheckAudit.getReadEvidence(), QualityReadEvidence.class);
        read.setConfigurationRevision(cn.iocoder.yudao.module.governance.service.master.ConfiguredDriverTestSupport
                .resolver(other, "same-provider-revision").resolve(4L,
                        cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_CHECK_OBJECT_NOT_EXISTS)
                .getConfigurationRevision());
        recheckAudit.setReadEvidence(JsonUtils.toJsonString(read));
        assertWaiting();
        assertThat(judge().checks().getFirst()).containsEntry("reasonCode", "DATASOURCE_REVISION_CHANGED");
    }

    @Test void notRunResultCannotBecomePass() {
        recheckAudit.setResultType("NOT_RUN");
        assertWaiting();
    }

    @Test void zeroSamplePassCannotBecomePass() {
        recheckAudit.setTotalCount(0L);
        assertWaiting();
    }

    @Test void unchangedDirectSourceCanPassWithoutMetadataScan() {
        var judge = judge();
        assertThat(judge.recheckStatus()).isEqualTo("PASS");
        assertThat(judge.comparable()).isTrue();
        assertThat(judge.checks().getFirst()).containsEntry("reasonCode", "COMPARABLE");
    }

    @Test void comparableFailedCheckIsStillFailure() {
        recheckAudit.setResultType("FAIL"); recheckAudit.setFailCount(1L);
        var judge = judge();
        assertThat(judge.recheckStatus()).isEqualTo("FAIL");
        assertThat(judge.comparable()).isTrue();
    }

    @Test void duplicatedAuditCannotSelectAnArbitraryPass() {
        when(audits.selectList(any())).thenReturn(List.of(recheckAudit, recheckAudit));
        assertWaiting();
    }

    @Test void oneFailedTargetDoesNotHideAnotherMissingTarget() {
        recheckAudit.setResultType("FAIL"); recheckAudit.setFailCount(1L);
        when(relations.selectListByWorkOrderId(8L)).thenReturn(List.of(
                QualityWorkOrderIssueDO.builder().issueId(30L).build(),
                QualityWorkOrderIssueDO.builder().issueId(31L).build()));
        assertWaiting();
        assertThat(judge().checks()).hasSize(2);
    }

    @Test void missingHistoricalReadCannotPassThroughService() {
        originalAudit.setReadEvidence(null);
        assertWaiting();
    }

    @Test void enoughRowsDoNotProveRecordedProblemKeysWereCovered() {
        issue.setPkValue("sample-1,sample-2");
        recheckAudit.setTotalCount(200L);
        assertWaiting();
        assertThat(judge().checks().getFirst()).containsEntry("reasonCode", "ROW_COVERAGE_UNPROVEN");
    }

    @Test void invalidReadTimestampFailsClosed() {
        var evidence = JsonUtils.parseObject(recheckAudit.getReadEvidence(), QualityReadEvidence.class);
        evidence.setStartedAt("invalid");
        recheckAudit.setReadEvidence(JsonUtils.toJsonString(evidence));
        assertThat(compare().evidence()).containsEntry("reasonCode", "EVIDENCE_INVALID");
        assertWaiting();
    }

    @Test void identicalCompleteKeyPopulationCanPassWithoutParsingLossyIssueCsv() {
        configureKeyEvidence("a".repeat(64), "a".repeat(64));
        issue.setPkValue("old truncated display,not a trusted key list");
        assertThat(judge().recheckStatus()).isEqualTo("PASS");
        assertThat(judge().checks().getFirst()).containsEntry("keyPopulationHash", "a".repeat(64));
    }

    @Test void sameCountWithReplacedKeysCannotPass() {
        configureKeyEvidence("a".repeat(64), "b".repeat(64));
        assertWaiting();
        assertThat(judge().checks().getFirst()).containsEntry("reasonCode", "ROW_POPULATION_CHANGED");
    }

    @Test void configuredKeyWithoutCapturedIssueCsvStillRequiresProof() {
        for (var definition : List.of(baseline, current)) {
            definition.getRules().getFirst().setParamSchema("{\"column\":\"amount\",\"min\":10,\"pkColumn\":\"id\"}");
        }
        assertWaiting();
        assertThat(judge().checks().getFirst()).containsEntry("reasonCode", "ROW_COVERAGE_UNPROVEN");
    }

    @Test void incompleteOrUnboundKeyProofCannotPass() {
        java.util.List<java.util.function.Consumer<QualityReadEvidence.KeyScope>> invalid = List.of(
                scope -> scope.setSchemaVersion(2), scope -> scope.setComplete(false),
                scope -> scope.setReasonCode("KEY_LIMIT_EXCEEDED"), scope -> scope.setColumn("other_id"),
                scope -> scope.setAlgorithm("UNKNOWN"), scope -> scope.setJdbcType(java.sql.Types.VARCHAR),
                scope -> scope.setKeyHash("bad"), scope -> scope.setSqlHash("b".repeat(64)),
                scope -> scope.setKeyCount(99L), scope -> scope.setTotal(99L), scope -> scope.setFail(1L));
        for (var mutation : invalid) {
            configureKeyEvidence("a".repeat(64), "a".repeat(64));
            var read = JsonUtils.parseObject(recheckAudit.getReadEvidence(), QualityReadEvidence.class);
            mutation.accept(read.getKeyScope());
            recheckAudit.setReadEvidence(JsonUtils.toJsonString(read));
            assertWaiting();
            assertThat(judge().checks().getFirst()).containsEntry("reasonCode", "ROW_COVERAGE_UNPROVEN");
        }
    }

    @Test void matchingPopulationDoesNotTurnFailedRuleIntoPass() {
        recheckAudit.setFailCount(1L); recheckAudit.setResultType("FAIL");
        configureKeyEvidence("a".repeat(64), "a".repeat(64));
        assertThat(judge().recheckStatus()).isEqualTo("FAIL");
        assertThat(judge().comparable()).isTrue();
    }

    private void configureKeyEvidence(String beforeHash, String afterHash) {
        for (var definition : List.of(baseline, current)) {
            definition.getRules().getFirst().setParamSchema("{\"column\":\"amount\",\"min\":10,\"pkColumn\":\"id\"}");
        }
        attachKeyEvidence(originalAudit, beforeHash);
        attachKeyEvidence(recheckAudit, afterHash);
    }

    @SuppressWarnings("unchecked")
    private void attachKeyEvidence(QualityAuditLogDO audit, String keyHash) {
        java.util.Map<String, Object> read = JsonUtils.parseObject(audit.getReadEvidence(), java.util.Map.class);
        var scope = new java.util.LinkedHashMap<String, Object>();
        scope.put("schemaVersion", 1); scope.put("column", "id"); scope.put("complete", true);
        scope.put("algorithm", "SHA256_SORTED_INT64_V1"); scope.put("jdbcType", java.sql.Types.BIGINT);
        scope.put("sqlHash", read.get("sqlHash")); scope.put("keyHash", keyHash);
        scope.put("keyCount", audit.getTotalCount()); scope.put("total", audit.getTotalCount());
        scope.put("fail", audit.getFailCount());
        read.put("keyScope", scope);
        audit.setReadEvidence(JsonUtils.toJsonString(read));
    }

    private void assertWaiting() {
        var judge = judge();
        assertThat(judge.recheckStatus()).isEqualTo(QualityWorkOrderSubmissionDO.RECHECK_WAITING);
        assertThat(judge.comparable()).isFalse();
    }

    @Test void comparisonSameDefinitionAndFullDirectReadIsComparable() {
        assertThat(compare().comparable()).isTrue();
    }

    @Test void mysqlDirectWholeTableProofCanPassButReplacementCannot() {
        for (var audit : List.of(originalAudit, recheckAudit)) {
            var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
            read.setProductType("MYSQL");
            audit.setReadEvidence(JsonUtils.toJsonString(read));
        }
        assertThat(compare().comparable()).isTrue();
        assertThat(judge().recheckStatus()).isEqualTo("PASS");
        var replaced = JsonUtils.parseObject(recheckAudit.getReadEvidence(), QualityReadEvidence.class);
        replaced.setRelationFingerprint("f".repeat(64));
        recheckAudit.setReadEvidence(JsonUtils.toJsonString(replaced));
        assertThat(compare().evidence()).containsEntry("reasonCode", "RELATION_CHANGED");
        assertWaiting();
    }

    @Test void mysqlSignedIntegerCoverageCanPassButChangedPopulationCannot() {
        configureKeyEvidence("a".repeat(64), "a".repeat(64));
        for (var audit : List.of(originalAudit, recheckAudit)) {
            var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
            read.setProductType("MYSQL");
            audit.setReadEvidence(JsonUtils.toJsonString(read));
        }
        assertThat(compare().comparable()).isTrue();
        assertThat(judge().recheckStatus()).isEqualTo("PASS");
        var changed = JsonUtils.parseObject(recheckAudit.getReadEvidence(), QualityReadEvidence.class);
        changed.getKeyScope().setKeyHash("b".repeat(64));
        recheckAudit.setReadEvidence(JsonUtils.toJsonString(changed));
        assertThat(compare().evidence()).containsEntry("reasonCode", "ROW_POPULATION_CHANGED");
        assertWaiting();
    }

    @Test void mysqlCannotBorrowPostgresTypedKeyProof() {
        configureKeyEvidence("a".repeat(64), "a".repeat(64));
        for (var audit : List.of(originalAudit, recheckAudit)) {
            var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
            read.setProductType("MYSQL");
            read.getKeyScope().setAlgorithm(cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot.TYPED_ALGORITHM);
            read.getKeyScope().setJdbcType(java.sql.Types.VARCHAR);
            audit.setReadEvidence(JsonUtils.toJsonString(read));
        }
        assertThat(compare().evidence()).containsEntry("reasonCode", "ROW_COVERAGE_UNPROVEN");
        assertWaiting();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"CLICKHOUSE,false", "CLICKHOUSE,true", "OPENGAUSS,false",
            "OPENGAUSS,true", "STARROCKS,false", "STARROCKS,true"})
    void nativeTextProofCannotUpgradeHistoryOrBorrowForeignAlgorithms(String product, boolean org) {
        if (org) configureMysqlOrgKeyEvidence();
        else configureKeyEvidence("a".repeat(64), "a".repeat(64));
        for (var definition : List.of(baseline, current))
            definition.getRules().getFirst().setStrategyKey("NOT_NULL_VALIDATION");
        for (var audit : List.of(originalAudit, recheckAudit)) {
            var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
            read.setProductType(product);
            if (org) {
                read.getOrgScope().setGroupEncoding(product + "_EXACT_UTF8_GROUP_V1");
                read.getOrgScope().setGroupJdbcType(java.sql.Types.VARCHAR);
            }
            audit.setReadEvidence(JsonUtils.toJsonString(read));
            changeTypedScope(audit, org, scope -> {
                scope.setJdbcType(java.sql.Types.VARCHAR);
                scope.setAlgorithm("SHA256_SORTED_" + product + "_UTF8_BYTES_V1");
            });
        }
        assertThat(compare().comparable()).isTrue();
        assertThat(judge().recheckStatus()).isEqualTo("PASS");
        String historical = originalAudit.getReadEvidence();
        changeTypedScope(originalAudit, org, scope -> scope.setComplete(false));
        assertWaiting(); // A new complete read cannot repair missing historical row coverage.
        originalAudit.setReadEvidence(historical);
        if (org) {
            var read = JsonUtils.parseObject(historical, QualityReadEvidence.class);
            read.getOrgScope().setSchemaVersion(1);
            originalAudit.setReadEvidence(JsonUtils.toJsonString(read));
            assertWaiting();
            originalAudit.setReadEvidence(historical);
        }
        changeTypedScope(recheckAudit, org, scope -> scope.setAlgorithm("SHA256_SORTED_MYSQL_UTF8_BYTES_V1"));
        assertWaiting();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void tdengineIntegerProofCannotUpgradeHistoryOrBorrowTextTypes(boolean org) {
        if (org) configureMysqlOrgKeyEvidence();
        else configureKeyEvidence("a".repeat(64), "a".repeat(64));
        for (var definition : List.of(baseline, current))
            definition.getRules().getFirst().setStrategyKey("NOT_NULL_VALIDATION");
        for (var audit : List.of(originalAudit, recheckAudit)) {
            var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
            read.setProductType("TDENGINE");
            if (org) read.getOrgScope().setGroupEncoding("TDENGINE_SIGNED_INT64_DECIMAL_V1");
            audit.setReadEvidence(JsonUtils.toJsonString(read));
        }
        assertThat(compare().comparable()).isTrue(); assertThat(judge().recheckStatus()).isEqualTo("PASS");
        String historical = originalAudit.getReadEvidence();
        changeTypedScope(originalAudit, org, scope -> scope.setComplete(false)); assertWaiting();
        originalAudit.setReadEvidence(historical);
        if (org) {
            var read = JsonUtils.parseObject(historical, QualityReadEvidence.class);
            read.getOrgScope().setSchemaVersion(1); originalAudit.setReadEvidence(JsonUtils.toJsonString(read));
            assertWaiting(); originalAudit.setReadEvidence(historical);
        }
        changeTypedScope(recheckAudit, org, scope -> {
            scope.setJdbcType(java.sql.Types.VARCHAR); scope.setAlgorithm("SHA256_SORTED_MYSQL_UTF8_BYTES_V1");
        });
        assertWaiting();
    }

    @Test void mysqlTextKeysRequireTheirOwnProtocolForAllAndOrganizationScopes() {
        for (boolean org : new boolean[]{false, true}) {
            for (int type : new int[]{java.sql.Types.VARCHAR, java.sql.Types.LONGVARCHAR}) {
                if (org) configureMysqlOrgKeyEvidence();
                else configureKeyEvidence("a".repeat(64), "a".repeat(64));
                for (var audit : List.of(originalAudit, recheckAudit)) {
                    var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
                    read.setProductType("MYSQL"); audit.setReadEvidence(JsonUtils.toJsonString(read));
                    changeTypedScope(audit, org, scope -> {
                        scope.setJdbcType(type);
                        scope.setAlgorithm(cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot.MYSQL_TEXT_ALGORITHM);
                    });
                }
                assertThat(judge().recheckStatus()).isEqualTo("PASS");
                changeTypedScope(recheckAudit, org, scope -> scope.setJdbcType(type == java.sql.Types.VARCHAR
                        ? java.sql.Types.LONGVARCHAR : java.sql.Types.VARCHAR));
                assertWaiting();
                changeTypedScope(recheckAudit, org, scope -> scope.setJdbcType(java.sql.Types.OTHER));
                assertWaiting();
            }
        }
    }

    @Test void postgresCannotBorrowMysqlTextKeyEncoding() {
        for (boolean org : new boolean[]{false, true}) {
            configureTypedEvidence(org, java.sql.Types.VARCHAR);
            for (var audit : List.of(originalAudit, recheckAudit)) {
                changeTypedScope(audit, org, scope -> scope.setAlgorithm(
                        cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot.MYSQL_TEXT_ALGORITHM));
            }
            assertWaiting();
        }
    }

    @Test void comparisonJsonKeyOrderAndDisplayNameDoNotChangeMeaning() {
        current.getRules().getFirst().setRuleName("仅修改名称");
        current.getRules().getFirst().setParamSchema("{\"min\":10, \"column\":\"amount\"}");
        assertThat(compare().comparable()).isTrue();
    }

    @Test void comparisonThresholdChangeIsRejected() {
        current.getRules().getFirst().setParamSchema("{\"column\":\"amount\",\"min\":0}");
        assertThat(compare().evidence()).containsEntry("reasonCode", "RULE_CHANGED");
    }

    @Test void comparisonMissingHistoricalReadIsRejected() {
        originalAudit.setReadEvidence(null);
        assertThat(compare().evidence()).containsEntry("reasonCode", "READ_EVIDENCE_MISSING");
    }

    @Test void historicalVersionWithoutPhysicalRelationProofRequiresReview() {
        var historical = JsonUtils.parseObject(originalAudit.getReadEvidence(), QualityReadEvidence.class);
        historical.setSchemaVersion(1);
        historical.setRelationFingerprint(null);
        originalAudit.setReadEvidence(JsonUtils.toJsonString(historical));
        assertThat(compare().evidence()).containsEntry("reasonCode", "READ_EVIDENCE_MISSING");
    }

    @Test void comparisonChangedDatabaseRevisionIsRejected() {
        var evidence = JsonUtils.parseObject(recheckAudit.getReadEvidence(), QualityReadEvidence.class);
        evidence.setConfigurationRevision("changed");
        recheckAudit.setReadEvidence(JsonUtils.toJsonString(evidence));
        assertThat(compare().evidence()).containsEntry("reasonCode", "DATASOURCE_REVISION_CHANGED");
    }

    @Test void sameConfigurationAndSqlCannotProveAReplacedPhysicalRelation() {
        for (var audit : List.of(originalAudit, recheckAudit)) {
            java.util.Map<String, Object> read = JsonUtils.parseObject(audit.getReadEvidence(), java.util.Map.class);
            read.put("relationFingerprint", audit == originalAudit ? "a".repeat(64) : "b".repeat(64));
            audit.setReadEvidence(JsonUtils.toJsonString(read));
        }
        assertThat(compare().evidence()).containsEntry("reasonCode", "RELATION_CHANGED");
    }

    @Test void comparisonReplicaWaitsForRealArrivalProof() {
        baseline.getObjects().getFirst().setDatasourceType("ODS");
        current.getObjects().getFirst().setDatasourceType("ODS");
        for (var audit : List.of(originalAudit, recheckAudit)) {
            var evidence = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
            evidence.setReadMode("UNPROVEN_REPLICA");
            evidence.setUsageType(2);
            audit.setReadEvidence(JsonUtils.toJsonString(evidence));
        }
        assertThat(compare().evidence()).containsEntry("reasonCode", "DATA_ARRIVAL_UNPROVEN");
    }

    @Test void comparisonOrgCannotBorrowAggregatePass() {
        issue.setStatScope("ORG"); issue.setOrgId(9L);
        assertThat(compare().evidence()).containsEntry("reasonCode", "SCOPE_UNPROVEN");
    }

    @Test void stableSuccessfulSyncFactsStillCannotProveReplicaArrival() {
        comparisonReplicaWaitsForRealArrivalProof();
        var observation = new QualitySyncEvidenceCollector.Observation("OBSERVED", 99L,
                QualitySyncEvidenceCollectorTest.batch(1, "rev", false, false));
        for (var audit : List.of(originalAudit, recheckAudit)) {
            var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
            read.setSyncScope(QualitySyncEvidenceCollector.combine(observation, observation));
            audit.setReadEvidence(JsonUtils.toJsonString(read));
        }
        assertThat(compare().evidence()).containsEntry("reasonCode", "DATA_ARRIVAL_UNPROVEN");
        assertWaiting();
    }

    @Test void postSubmissionStartOrderStillRequiresActualTargetVisibility() {
        comparisonReplicaWaitsForRealArrivalProof();
        var legacy = QualitySyncEvidenceCollectorTest.batch(1, "rev", false, false);
        var b = legacy.batches().getFirst();
        var fresh = new cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBatchObservationDTO(
                1, 20L, "rev", "POSTGRESQL", "public", "orders", "public.orders", false, false,
                List.of(new cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBatchObservationDTO.Batch(
                        b.runId(), 1, 1L, 3, false, false, false, "COMPLETE", "rev", "TABLE_COPY", "CONFIRMED",
                        true, true, 3L, 2L, new cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncTableTransferDTO(
                                1, "FULL_SCAN_TABLE", 3L, 3L, 2, 2, 0L, false, false, false))));
        var boundary = new cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBoundaryDTO(
                1, "quality:" + "a".repeat(64), 20L, "rev", "POSTGRESQL", "public", "orders", 1L);
        var observation = new QualitySyncEvidenceCollector.Observation("OBSERVED", 99L, fresh, boundary);
        var scope = QualitySyncEvidenceCollector.combine(observation, observation);
        assertThat(scope.reason()).isEqualTo("POST_SUBMISSION_BATCH_WRITE_ACKNOWLEDGED");
        for (var audit : List.of(originalAudit, recheckAudit)) {
            var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
            read.setSyncScope(scope); audit.setReadEvidence(JsonUtils.toJsonString(read));
        }
        assertThat(compare().evidence()).containsEntry("reasonCode", "DATA_ARRIVAL_UNPROVEN");
        assertWaiting();
    }

    @Test void organizationCanPassEvenWhenAnotherOrganizationStillFails() {
        configureOrgEvidence("PASS", 0L);
        recheckAudit.setResultType("FAIL"); recheckAudit.setFailCount(10L);
        var result = judge();
        assertThat(result.recheckStatus()).isEqualTo("PASS");
        assertThat(result.comparable()).isTrue();
    }

    @Test void organizationFailureCannotBorrowAggregatePass() {
        configureOrgEvidence("FAIL", 1L);
        var result = judge();
        assertThat(result.recheckStatus()).isEqualTo("FAIL");
        assertThat(result.comparable()).isTrue();
    }

    @Test void organizationMissingOrTruncatedEvidenceCannotPass() {
        configureOrgEvidence("PASS", 0L);
        changeOrgScope(scope -> scope.setComplete(false));
        assertWaiting();
        changeOrgScope(scope -> scope.setComplete(true));
        var read = JsonUtils.parseObject(originalAudit.getReadEvidence(), QualityReadEvidence.class);
        read.setOrgScope(null);
        originalAudit.setReadEvidence(JsonUtils.toJsonString(read));
        assertWaiting();
    }

    @Test void organizationCannotBorrowAnotherKeysCount() {
        configureOrgEvidence("PASS", 0L);
        issue.setOrgId(10L); issue.setRegionCode("10");
        assertWaiting();
        assertThat(judge().checks().getFirst()).containsEntry("reasonCode", "ORG_COVERAGE_UNPROVEN");
    }

    @Test void organizationMissingOrShrunkSampleIsNotComparable() {
        configureOrgEvidence("PASS", 0L);
        changeOrgScope(scope -> scope.setGroups(List.of()));
        assertWaiting();
        attachOrgEvidence(recheckAudit, "PASS", 0L);
        changeOrgScope(scope -> scope.setGroups(List.of(new QualityReadEvidence.OrgCount(
                scope.getGroups().getFirst().keyHash(), 19L, 0L, "PASS"))));
        assertWaiting();
        assertThat(judge().checks().getFirst()).containsEntry("reasonCode", "ORG_COVERAGE_UNPROVEN");
    }

    @Test void organizationDuplicateGroupsAndChangedSqlAreRejected() {
        configureOrgEvidence("PASS", 0L);
        changeOrgScope(scope -> scope.setGroups(List.of(scope.getGroups().getFirst(), scope.getGroups().getFirst())));
        assertWaiting();
        attachOrgEvidence(recheckAudit, "PASS", 0L);
        changeOrgScope(scope -> scope.setSqlHash(cn.hutool.crypto.digest.DigestUtil.sha256Hex("changed-sql")));
        assertWaiting();
    }

    @Test void organizationStaleTimeAndIdentityConflictAreRejected() {
        configureOrgEvidence("PASS", 0L);
        changeOrgScope(scope -> scope.setStartedAt(submittedAt.minusHours(1).toString()));
        assertWaiting();
        attachOrgEvidence(recheckAudit, "PASS", 0L);
        issue.setOrgId(10L);
        assertWaiting();
    }

    @Test void organizationCannotTreatEmptyOrInvalidCountsAsPass() {
        configureOrgEvidence("PASS", 0L);
        changeOrgScope(scope -> scope.setGroups(List.of(new QualityReadEvidence.OrgCount(
                scope.getGroups().getFirst().keyHash(), 0L, 0L, "PASS"))));
        assertWaiting();
        attachOrgEvidence(recheckAudit, "PASS", 0L);
        changeOrgScope(scope -> scope.setGroups(List.of(new QualityReadEvidence.OrgCount(
                scope.getGroups().getFirst().keyHash(), 20L, 21L, "PASS"))));
        assertWaiting();
    }

    @Test void organizationWithConfiguredProblemKeysStillNeedsRowCoverage() {
        configureOrgEvidence("PASS", 0L);
        for (var definition : List.of(baseline, current)) {
            definition.getRules().getFirst().setParamSchema("{\"column\":\"amount\",\"min\":10,\"pkColumn\":\"id\"}");
        }
        assertWaiting();
        assertThat(judge().checks().getFirst()).containsEntry("reasonCode", "ROW_COVERAGE_UNPROVEN");
    }

    @Test void organizationUnverifiedProductAndFailedEvidenceRemainWaiting() {
        configureOrgEvidence("PASS", 0L);
        changeOrgScope(scope -> scope.setReasonCode("ORG_READ_FAILED"));
        assertWaiting();
        changeOrgScope(scope -> scope.setReasonCode(null));
        for (var audit : List.of(originalAudit, recheckAudit)) {
            var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
            read.setProductType("MYSQL");
            audit.setReadEvidence(JsonUtils.toJsonString(read));
        }
        assertWaiting();
    }

    @Test void organizationWithMatchingOwnKeyPopulationCanPass() {
        configureOrgKeyEvidence("a".repeat(64), "a".repeat(64));
        assertThat(judge().recheckStatus()).isEqualTo("PASS");
        assertThat(judge().checks().getFirst()).containsEntry("keyPopulationHash", "a".repeat(64));
    }

    @Test void mysqlOrganizationRequiresVersionedNativeGroupProofAndMatchingOwnKeys() {
        configureMysqlOrgKeyEvidence();
        assertThat(judge().recheckStatus()).isEqualTo("PASS");
        changeOrgScope(scope -> scope.getGroups().getFirst().keyScope().setKeyHash("b".repeat(64)));
        assertWaiting();
        assertThat(judge().checks().getFirst()).containsEntry("reasonCode", "ROW_POPULATION_CHANGED");
    }

    @Test void mysqlOrganizationCannotBorrowLegacyUnsupportedOrChangedGroupType() {
        for (java.util.function.Consumer<QualityReadEvidence.OrgScope> change :
                List.<java.util.function.Consumer<QualityReadEvidence.OrgScope>>of(
                        scope -> scope.setSchemaVersion(1), scope -> scope.setGroupEncoding(null),
                        scope -> scope.setGroupEncoding("PG_TEXT"), scope -> scope.setGroupJdbcType(null),
                        scope -> scope.setGroupJdbcType(java.sql.Types.VARCHAR),
                        scope -> scope.setGroupJdbcType(java.sql.Types.INTEGER),
                        scope -> scope.setKeyCaptureReason("GROUP_KEY_UNSUPPORTED"))) {
            configureMysqlOrgKeyEvidence();
            changeOrgScope(change); assertWaiting();
        }
    }

    @Test void mysqlOrganizationCannotBorrowPostgresTypedKeys() {
        configureMysqlOrgKeyEvidence();
        for (var audit : List.of(originalAudit, recheckAudit)) {
            changeTypedScope(audit, true, scope -> {
                scope.setJdbcType(java.sql.Types.VARCHAR);
                scope.setAlgorithm(cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot.TYPED_ALGORITHM);
            });
        }
        assertWaiting();
        assertThat(judge().checks().getFirst()).containsEntry("reasonCode", "ROW_COVERAGE_UNPROVEN");
    }

    private void configureMysqlOrgKeyEvidence() {
        configureOrgKeyEvidence("a".repeat(64), "a".repeat(64));
        for (var audit : List.of(originalAudit, recheckAudit)) {
            var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
            read.setProductType("MYSQL");
            read.getOrgScope().setSchemaVersion(2);
            read.getOrgScope().setGroupEncoding(
                    cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot.MYSQL_INTEGER_GROUP_ENCODING);
            read.getOrgScope().setGroupJdbcType(java.sql.Types.BIGINT);
            audit.setReadEvidence(JsonUtils.toJsonString(read));
        }
    }

    @Test void mysqlTextOrganizationRequiresMatchingNativeTypeAndEncoding() {
        for (int type : new int[]{java.sql.Types.VARCHAR, java.sql.Types.LONGVARCHAR}) {
            configureMysqlOrgKeyEvidence();
            for (var audit : List.of(originalAudit, recheckAudit)) {
                var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
                read.getOrgScope().setGroupEncoding(
                        cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot.MYSQL_TEXT_GROUP_ENCODING);
                read.getOrgScope().setGroupJdbcType(type);
                audit.setReadEvidence(JsonUtils.toJsonString(read));
            }
            assertThat(judge().recheckStatus()).isEqualTo("PASS");
            changeOrgScope(scope -> scope.setGroupJdbcType(java.sql.Types.CHAR));
            assertWaiting();
            changeOrgScope(scope -> scope.setGroupJdbcType(type == java.sql.Types.VARCHAR
                    ? java.sql.Types.LONGVARCHAR : java.sql.Types.VARCHAR));
            assertWaiting();
            changeOrgScope(scope -> {
                scope.setGroupJdbcType(type);
                scope.setGroupEncoding(cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot.MYSQL_INTEGER_GROUP_ENCODING);
            });
            assertWaiting();
        }
    }

    @Test void configuredCompositeKeysCannotPassOnRowCountsWithoutKeyProof() {
        for (var definition : List.of(baseline, current)) {
            definition.getRules().getFirst().setParamSchema(
                    "{\"column\":\"amount\",\"min\":10,\"pkColumns\":[\"org_code\",\"id\"]}");
        }
        for (var audit : List.of(originalAudit, recheckAudit)) {
            var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
            read.setSchemaVersion(QualityReadEvidence.COMPOSITE_SCHEMA_VERSION);
            audit.setReadEvidence(JsonUtils.toJsonString(read));
        }
        assertWaiting();
        assertThat(judge().checks().getFirst()).containsEntry("reasonCode", "ROW_COVERAGE_UNPROVEN");
    }

    @Test void malformedCompositeConfigurationCannotDowngradeToNoKeyCheck() {
        for (String config : List.of("null", "[]", "[\"id\"]", "\"id,org_code\"", "[\"id\",1]")) {
            for (var definition : List.of(baseline, current)) {
                definition.getRules().getFirst().setParamSchema(
                        "{\"column\":\"amount\",\"min\":10,\"pkColumns\":" + config + "}");
            }
            assertWaiting();
        }
    }

    @Test void completeCompositeProofCanQualifyWholeTableAndOrganization() {
        for (boolean org : new boolean[]{false, true}) {
            configureCompositeEvidence(org);
            assertThat(judge().recheckStatus()).isEqualTo("PASS");
            changeTypedScope(recheckAudit, org, scope -> scope.setKeyHash("b".repeat(64)));
            assertWaiting();
            assertThat(judge().checks().getFirst()).containsEntry("reasonCode", "ROW_POPULATION_CHANGED");
        }
    }

    @Test void mysqlCompositeProofRequiresProductSpecificAlgorithmAndOrderedColumns() {
        for (boolean org : new boolean[]{false, true}) {
            configureCompositeEvidence(org);
            for (var audit : List.of(originalAudit, recheckAudit)) {
                var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
                read.setProductType("MYSQL");
                if (org) {
                    read.getOrgScope().setSchemaVersion(2);
                    read.getOrgScope().setGroupEncoding(
                            cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot.MYSQL_TEXT_GROUP_ENCODING);
                    read.getOrgScope().setGroupJdbcType(java.sql.Types.VARCHAR);
                }
                audit.setReadEvidence(JsonUtils.toJsonString(read));
                changeTypedScope(audit, org, scope -> scope.setAlgorithm(
                        cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot.MYSQL_COMPOSITE_ALGORITHM));
            }
            assertThat(judge().recheckStatus()).isEqualTo("PASS");
            changeTypedScope(recheckAudit, org, scope -> scope.setColumns(List.of("id", "source_id")));
            assertWaiting();
            changeTypedScope(recheckAudit, org, scope -> {
                scope.setColumns(List.of("source_id", "id"));
                scope.setAlgorithm(cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot.COMPOSITE_ALGORITHM);
            });
            assertWaiting();
        }
    }

    @Test void postgresCompositeProofCannotBorrowMysqlTupleEncoding() {
        for (boolean org : new boolean[]{false, true}) {
            configureCompositeEvidence(org);
            for (var audit : List.of(originalAudit, recheckAudit)) {
                changeTypedScope(audit, org, scope -> scope.setAlgorithm(
                        cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot.MYSQL_COMPOSITE_ALGORITHM));
            }
            assertWaiting();
        }
    }

    @Test void compositeEvidenceMustBindOrderedColumnsAndCannotBorrowSingleKeyProtocol() {
        List<java.util.function.Consumer<QualityReadEvidence.KeyScope>> changes = List.of(
                scope -> scope.setColumns(List.of("id", "source_id")), scope -> scope.setColumns(null),
                scope -> scope.setColumn("id"), scope -> scope.setJdbcType(java.sql.Types.BIGINT),
                scope -> scope.setAlgorithm(cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot.TYPED_ALGORITHM));
        for (boolean org : new boolean[]{false, true}) {
            for (var change : changes) {
                configureCompositeEvidence(org); changeTypedScope(recheckAudit, org, change);
                assertWaiting();
                assertThat(judge().checks().getFirst()).containsEntry("reasonCode", "ROW_COVERAGE_UNPROVEN");
            }
        }
    }

    private void configureCompositeEvidence(boolean org) {
        if (org) configureOrgKeyEvidence("a".repeat(64), "a".repeat(64));
        else configureKeyEvidence("a".repeat(64), "a".repeat(64));
        for (var definition : List.of(baseline, current)) {
            definition.getRules().getFirst().setParamSchema(
                    "{\"column\":\"amount\",\"min\":10,\"pkColumns\":[\"source_id\",\"id\"]}");
        }
        for (var audit : List.of(originalAudit, recheckAudit)) {
            var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
            read.setSchemaVersion(QualityReadEvidence.COMPOSITE_SCHEMA_VERSION);
            audit.setReadEvidence(JsonUtils.toJsonString(read));
            changeTypedScope(audit, org, scope -> {
                scope.setColumn(null); scope.setColumns(List.of("source_id", "id")); scope.setJdbcType(java.sql.Types.STRUCT);
                scope.setAlgorithm(cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot.COMPOSITE_ALGORITHM);
            });
        }
    }

    @Test void compositeProofCannotReuseAVersionOneReadEnvelope() {
        for (boolean org : new boolean[]{false, true}) {
            configureCompositeEvidence(org);
            var read = JsonUtils.parseObject(originalAudit.getReadEvidence(), QualityReadEvidence.class);
            read.setSchemaVersion(1); originalAudit.setReadEvidence(JsonUtils.toJsonString(read));
            assertWaiting();
            assertThat(judge().checks().getFirst()).containsEntry("reasonCode", "READ_EVIDENCE_MISSING");
        }
    }

    @Test void singleKeyProofCannotBorrowTheCompositeReadEnvelope() {
        configureKeyEvidence("a".repeat(64), "a".repeat(64));
        var read = JsonUtils.parseObject(recheckAudit.getReadEvidence(), QualityReadEvidence.class);
        read.setSchemaVersion(QualityReadEvidence.COMPOSITE_SCHEMA_VERSION);
        recheckAudit.setReadEvidence(JsonUtils.toJsonString(read));
        assertWaiting();
    }

    @Test void typedWholeTableAndOrganizationProofsCanPassOnlyWithSupportedEncoding() {
        for (boolean org : new boolean[]{false, true}) {
            for (int type : new int[]{java.sql.Types.LONGVARCHAR, java.sql.Types.VARCHAR, java.sql.Types.OTHER}) {
                configureTypedEvidence(org, type);
                assertThat(judge().recheckStatus()).isEqualTo("PASS");
                changeTypedScope(recheckAudit, org, scope -> scope.setAlgorithm("SHA256_UNKNOWN"));
                assertWaiting();
                assertThat(judge().checks().getFirst()).containsEntry("reasonCode", "ROW_COVERAGE_UNPROVEN");
            }
        }
    }

    @Test void typedProofCannotBorrowIntegerAlgorithmOrUnsupportedType() {
        for (boolean org : new boolean[]{false, true}) {
            configureTypedEvidence(org, java.sql.Types.LONGVARCHAR);
            changeTypedScope(recheckAudit, org, scope -> scope.setAlgorithm(
                    cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot.ALGORITHM));
            assertWaiting();
            configureTypedEvidence(org, java.sql.Types.LONGVARCHAR);
            changeTypedScope(recheckAudit, org, scope -> scope.setJdbcType(java.sql.Types.NUMERIC));
            assertWaiting();
        }
    }

    @Test void identicalTypedHashCannotHideAChangedTextType() {
        for (boolean org : new boolean[]{false, true}) {
            configureTypedEvidence(org, java.sql.Types.LONGVARCHAR);
            changeTypedScope(recheckAudit, org, scope -> scope.setJdbcType(java.sql.Types.VARCHAR));
            assertWaiting();
            assertThat(judge().checks().getFirst()).containsEntry("reasonCode", "ROW_POPULATION_CHANGED");
        }
    }

    @Test void newTypedProofCannotRetroactivelyQualifyAnUnsupportedBaseline() {
        for (boolean org : new boolean[]{false, true}) {
            configureTypedEvidence(org, java.sql.Types.LONGVARCHAR);
            changeTypedScope(originalAudit, org, scope -> {
                scope.setComplete(false); scope.setKeyHash(null); scope.setReasonCode("KEY_TYPE_UNSUPPORTED");
                scope.setJdbcType(0); scope.setAlgorithm(cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot.ALGORITHM);
            });
            assertWaiting();
            assertThat(judge().checks().getFirst()).containsEntry("reasonCode", "ROW_COVERAGE_UNPROVEN");
        }
    }

    private void configureTypedEvidence(boolean org, int type) {
        if (org) configureOrgKeyEvidence("a".repeat(64), "a".repeat(64));
        else {
            issue.setStatScope("ALL"); issue.setIssueCount(10L);
            baseline.getTask().setOrgField(null); current.getTask().setOrgField(null);
            configureKeyEvidence("a".repeat(64), "a".repeat(64));
        }
        for (var audit : List.of(originalAudit, recheckAudit)) {
            changeTypedScope(audit, org, scope -> {
                scope.setAlgorithm(cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot.TYPED_ALGORITHM);
                scope.setJdbcType(type);
            });
        }
    }

    private void changeTypedScope(QualityAuditLogDO audit, boolean org,
                                 java.util.function.Consumer<QualityReadEvidence.KeyScope> change) {
        var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
        change.accept(org ? read.getOrgScope().getGroups().getFirst().keyScope() : read.getKeyScope());
        audit.setReadEvidence(JsonUtils.toJsonString(read));
    }

    @Test void organizationCannotBorrowUnchangedWholeTableKeysAfterRowsMove() {
        configureOrgKeyEvidence("a".repeat(64), "b".repeat(64));
        assertWaiting();
        assertThat(judge().checks().getFirst()).containsEntry("reasonCode", "ROW_POPULATION_CHANGED");
    }

    @Test void organizationKeyProofMustBindItsOwnIdentitySqlAndCounts() {
        java.util.List<java.util.function.Consumer<QualityReadEvidence.KeyScope>> changes = List.of(
                keys -> keys.setGroupKeyHash(null), keys -> keys.setGroupKeyHash("f".repeat(64)),
                keys -> keys.setSqlHash("f".repeat(64)), keys -> keys.setTotal(100L),
                keys -> keys.setFail(1L), keys -> keys.setComplete(false),
                keys -> keys.setColumn("other_id"), keys -> keys.setReasonCode("KEY_NOT_UNIQUE_OR_NULL"));
        for (var change : changes) {
            configureOrgKeyEvidence("a".repeat(64), "a".repeat(64));
            changeOrgScope(scope -> change.accept(scope.getGroups().getFirst().keyScope()));
            assertWaiting();
            assertThat(judge().checks().getFirst()).containsEntry("reasonCode", "ROW_COVERAGE_UNPROVEN");
        }
    }

    @Test void groupedReadFallbackCannotBecomeACompleteKeyProof() {
        configureOrgKeyEvidence("a".repeat(64), "a".repeat(64));
        changeOrgScope(scope -> scope.setKeyCaptureReason("KEY_LIMIT_EXCEEDED"));
        assertWaiting();
    }

    @Test void organizationKeysDoNotOverrideItsFailedRuleWithWholeTablePass() {
        configureOrgKeyEvidence("a".repeat(64), "a".repeat(64));
        changeOrgScope(scope -> {
            var own = scope.getGroups().getFirst(); own.keyScope().setFail(1L);
            scope.setGroups(List.of(new QualityReadEvidence.OrgCount(own.keyHash(), own.total(), 1L, "FAIL", own.keyScope())));
        });
        assertThat(judge().recheckStatus()).isEqualTo("FAIL"); assertThat(judge().comparable()).isTrue();
    }

    @Test void invalidKeysInAnotherOrganizationDoNotReplaceOwnEvidence() {
        configureOrgKeyEvidence("a".repeat(64), "a".repeat(64));
        changeOrgScope(scope -> {
            var invalid = new QualityReadEvidence.KeyScope(); invalid.setComplete(false);
            scope.setGroups(List.of(scope.getGroups().getFirst(),
                    new QualityReadEvidence.OrgCount("f".repeat(64), 2L, 1L, "FAIL", invalid)));
        });
        assertThat(judge().recheckStatus()).isEqualTo("PASS");
    }

    @SuppressWarnings("unchecked")
    private void configureOrgKeyEvidence(String oldHash, String newHash) {
        configureKeyEvidence("c".repeat(64), "c".repeat(64)); // 全表集合不变不能代替机构集合。
        configureOrgEvidence("PASS", 0L);
        for (var audit : List.of(originalAudit, recheckAudit)) {
            java.util.Map<String, Object> read = JsonUtils.parseObject(audit.getReadEvidence(), java.util.Map.class);
            var org = (java.util.Map<String, Object>) read.get("orgScope");
            var groups = (java.util.List<java.util.Map<String, Object>>) org.get("groups");
            var count = groups.getFirst();
            var keys = new java.util.LinkedHashMap<>((java.util.Map<String, Object>) read.get("keyScope"));
            keys.put("keyHash", audit == originalAudit ? oldHash : newHash);
            keys.put("sqlHash", org.get("sqlHash")); keys.put("total", count.get("total"));
            keys.put("keyCount", count.get("total")); keys.put("fail", count.get("fail"));
            keys.put("groupKeyHash", count.get("keyHash"));
            count.put("keyScope", keys);
            audit.setReadEvidence(JsonUtils.toJsonString(read));
        }
    }

    @Test void postgresMicrosecondRoundingDoesNotCreateFalseTimeReversal() {
        var read = JsonUtils.parseObject(recheckAudit.getReadEvidence(), QualityReadEvidence.class);
        var actualStart = recheckAudit.getCheckTime().plusNanos(600);
        read.setStartedAt(actualStart.toString());
        recheckAudit.setReadEvidence(JsonUtils.toJsonString(read));
        recheckAudit.setCheckTime(actualStart.plusNanos(500).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(judge().recheckStatus()).isEqualTo("PASS");
        recheckAudit.setCheckTime(actualStart.plusNanos(1_000_000));
        assertWaiting(); // 真实更早的读取不能借用精度对齐通过。
    }

    private void changeOrgScope(java.util.function.Consumer<QualityReadEvidence.OrgScope> change) {
        var read = JsonUtils.parseObject(recheckAudit.getReadEvidence(), QualityReadEvidence.class);
        change.accept(read.getOrgScope());
        recheckAudit.setReadEvidence(JsonUtils.toJsonString(read));
    }

    private void configureOrgEvidence(String result, long fail) {
        baseline.getTask().setOrgField("org_code"); current.getTask().setOrgField("org_code");
        issue.setStatScope("ORG"); issue.setOrgId(9L); issue.setRegionCode("9"); issue.setIssueCount(5L);
        attachOrgEvidence(originalAudit, "FAIL", 5L);
        attachOrgEvidence(recheckAudit, result, fail);
    }

    @SuppressWarnings("unchecked")
    private void attachOrgEvidence(QualityAuditLogDO audit, String result, long fail) {
        java.util.Map<String, Object> read = JsonUtils.parseObject(audit.getReadEvidence(), java.util.Map.class);
        var scope = new java.util.LinkedHashMap<String, Object>();
        scope.put("schemaVersion", 1); scope.put("field", "org_code"); scope.put("complete", true);
        scope.put("sqlHash", cn.hutool.crypto.digest.DigestUtil.sha256Hex("fixture-group-count-sql"));
        scope.put("startedAt", audit.getCheckTime().plusSeconds(2).toString());
        scope.put("finishedAt", audit.getCheckTime().plusSeconds(3).toString());
        scope.put("groups", List.of(java.util.Map.of("keyHash", cn.hutool.crypto.digest.DigestUtil.sha256Hex("9"),
                "total", 20L, "fail", fail, "resultType", result)));
        read.put("orgScope", scope);
        audit.setReadEvidence(JsonUtils.toJsonString(read));
    }

    @Test void comparisonChangedExecutedSqlIsRejectedEvenWithEnoughRows() {
        recheckAudit.setResultSql(recheckAudit.getResultSql() + " WHERE region=1");
        assertThat(compare().evidence()).containsEntry("reasonCode", "EXECUTED_SCOPE_CHANGED");
    }

    @Test void comparisonEmptyAndUnknownResultsAreNotComparable() {
        recheckAudit.setTotalCount(0L);
        assertThat(compare().comparable()).isFalse();
        recheckAudit.setTotalCount(100L);
        recheckAudit.setResultType("NOT_RUN");
        assertThat(compare().comparable()).isFalse();
    }

    @Test void comparisonMalformedEvidenceFailsClosed() {
        recheckAudit.setReadEvidence("{invalid}");
        assertThat(compare().evidence()).containsEntry("reasonCode", "EVIDENCE_INVALID");
    }

    @Test void persistedPassCannotBeReusedAfterAttemptNumberChanges() {
        var order = QualityWorkOrderDO.builder().id(8L).taskId(1L).currentSubmissionId(40L).build();
        var submission = QualityWorkOrderSubmissionDO.builder().id(40L).workOrderId(8L).instanceId(20L)
                .revision(1).submittedAt(submittedAt).payloadHash("b".repeat(64)).issueIds("[30]").build();
        var attempt = QualityWorkOrderRecheckDO.builder().id(50L).attemptNo(1).requestKey("recheck:50")
                .requestedBy(60L).workOrderId(8L).submissionId(40L).instanceId(20L)
                .status("PASS").comparableFlag(1).build();
        attempt.setEvidenceJson(JsonUtils.toJsonString(QualityRecheckEvidence.decision(order, submission, attempt,
                "PASS", true, List.of(compare().evidence()))));
        assertThat(QualityRecheckEvidence.qualifies(attempt, order, submission)).isTrue();
        attempt.setAttemptNo(2);
        assertThat(QualityRecheckEvidence.qualifies(attempt, order, submission)).isFalse();
    }

    @Test void comparisonEvidenceEnvelopeIsBoundToExactAttempt() {
        var order = QualityWorkOrderDO.builder().id(8L).taskId(1L).currentSubmissionId(40L).build();
        var submission = QualityWorkOrderSubmissionDO.builder().id(40L).workOrderId(8L).instanceId(20L)
                .revision(1).submittedAt(submittedAt).payloadHash("b".repeat(64)).issueIds("[30]").build();
        var attempt = QualityWorkOrderRecheckDO.builder().id(50L).attemptNo(1).requestKey("recheck:50")
                .requestedBy(60L).workOrderId(8L).submissionId(40L).instanceId(20L)
                .status("PASS").comparableFlag(1).build();
        assertThat(QualityRecheckEvidence.qualifies(attempt, order, submission)).isFalse();
        attempt.setEvidenceJson(JsonUtils.toJsonString(new QualityRecheckEvidence.Decision(1,8L,40L,20L,
                "PASS",true,List.of(compare().evidence()))));
        assertThat(QualityRecheckEvidence.qualifies(attempt, order, submission)).isFalse();
        attempt.setEvidenceJson(JsonUtils.toJsonString(new QualityRecheckEvidence.Decision(
                QualityRecheckEvidence.DECISION_SCHEMA_VERSION,8L,40L,20L,
                "PASS",true,List.of(Map.of("reasonCode", "COMPARABLE")))));
        assertThat(QualityRecheckEvidence.qualifies(attempt, order, submission)).isFalse();
        attempt.setEvidenceJson(JsonUtils.toJsonString(QualityRecheckEvidence.decision(order, submission, attempt,
                "PASS", true, List.of(compare().evidence()))));
        assertThat(QualityRecheckEvidence.qualifies(attempt, order, submission)).isTrue();
        assertThat(QualityRecheckEvidence.qualifies(attempt, order, submission,
                cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredSourceGenerations.empty(), List.of(30L, 31L)))
                .isFalse();
        assertThat(QualityRecheckEvidence.qualifies(attempt, order, submission,
                cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredSourceGenerations.empty(), List.of(30L, 30L)))
                .isFalse();
        submission.setContent("different resolution");
        assertThat(QualityRecheckEvidence.qualifies(attempt, order, submission)).isFalse();
        submission.setContent(null);
        attempt.setId(51L);
        assertThat(QualityRecheckEvidence.qualifies(attempt, order, submission)).isFalse();
        attempt.setId(50L);
        attempt.setRequestedBy(61L);
        assertThat(QualityRecheckEvidence.qualifies(attempt, order, submission)).isFalse();
        attempt.setRequestedBy(60L);
        attempt.setRequestKey("other request");
        assertThat(QualityRecheckEvidence.qualifies(attempt, order, submission)).isFalse();
        attempt.setRequestKey("recheck:50");
        submission.setInstanceId(21L);
        assertThat(QualityRecheckEvidence.qualifies(attempt, order, submission)).isFalse();
        submission.setInstanceId(20L);
        order.setCurrentSubmissionId(41L);
        assertThat(QualityRecheckEvidence.qualifies(attempt, order, submission)).isFalse();
    }

    @Test void malformedOrIncompleteDecisionChecksCannotCloseOrder() {
        var order = QualityWorkOrderDO.builder().id(8L).taskId(1L).currentSubmissionId(40L).build();
        var submission = QualityWorkOrderSubmissionDO.builder().id(40L).workOrderId(8L).instanceId(20L)
                .revision(1).submittedAt(submittedAt).payloadHash("b".repeat(64)).issueIds("[30]").build();
        var attempt = QualityWorkOrderRecheckDO.builder().id(50L).attemptNo(1).requestKey("recheck:50")
                .requestedBy(60L).workOrderId(8L).submissionId(40L).instanceId(20L)
                .status("PASS").comparableFlag(1).build();
        for (String field : List.of("objectId", "ruleId", "auditId", "readHash", "baselineReadHash", "definitionHash", "statScope")) {
            var facts = new java.util.LinkedHashMap<>(compare().evidence());
            facts.remove(field);
            attempt.setEvidenceJson(JsonUtils.toJsonString(QualityRecheckEvidence.decision(order, submission, attempt,
                    "PASS", true, List.of(facts))));
            assertThat(QualityRecheckEvidence.qualifies(attempt, order, submission)).as(field).isFalse();
        }
        var facts = compare().evidence();
        attempt.setEvidenceJson(JsonUtils.toJsonString(QualityRecheckEvidence.decision(order, submission, attempt,
                "PASS", true, List.of(facts, facts))));
        assertThat(QualityRecheckEvidence.qualifies(attempt, order, submission)).isFalse();
        assertThat(QualityRecheckEvidence.qualifies(attempt, null, submission)).isFalse();
    }

    private QualityRecheckEvidence.Comparison compare() {
        original.setDefinitionSnapshot(JsonUtils.toJsonString(baseline));
        recheck.setDefinitionSnapshot(JsonUtils.toJsonString(current));
        return QualityRecheckEvidence.compare(QualityWorkOrderDO.builder().id(8L).taskId(1L).build(), issue,
                original, originalAudit, recheck, recheckAudit, submittedAt);
    }

    @Test void currentChecksMatchPersistedDecisionWithoutDependingOnIssueOrMapOrder() {
        var order = QualityWorkOrderDO.builder().id(8L).taskId(1L).currentSubmissionId(40L).build();
        var submission = QualityWorkOrderSubmissionDO.builder().id(40L).workOrderId(8L).instanceId(20L)
                .revision(1).submittedAt(submittedAt).payloadHash("b".repeat(64)).issueIds("[30,31]").build();
        var attempt = QualityWorkOrderRecheckDO.builder().id(50L).attemptNo(1).requestKey("recheck:50")
                .requestedBy(60L).workOrderId(8L).submissionId(40L).instanceId(20L)
                .status("PASS").comparableFlag(1).build();
        var first = compare().evidence();
        var second = new java.util.LinkedHashMap<>(first);
        second.put("issueId", 31L);
        attempt.setEvidenceJson(JsonUtils.toJsonString(QualityRecheckEvidence.decision(order, submission, attempt,
                "PASS", true, List.of(first, second))));
        var reversedKeys = new java.util.LinkedHashMap<String, Object>();
        first.keySet().stream().toList().reversed().forEach(key -> reversedKeys.put(key, first.get(key)));
        assertThat(QualityRecheckEvidence.matchesCurrentChecks(attempt, "PASS", true, List.of(second, reversedKeys))).isTrue();
        assertThat(QualityRecheckEvidence.matchesCurrentChecks(attempt, "FAIL", true, List.of(first, second))).isFalse();
        assertThat(QualityRecheckEvidence.matchesCurrentChecks(attempt, "PASS", false, List.of(first, second))).isFalse();
        assertThat(QualityRecheckEvidence.matchesCurrentChecks(attempt, "PASS", true, List.of(first))).isFalse();
        assertThat(QualityRecheckEvidence.matchesCurrentChecks(attempt, "PASS", true, List.of(first, first))).isFalse();
        assertThat(QualityRecheckEvidence.matchesCurrentChecks(attempt, "PASS", true, List.of())).isFalse();
        assertThat(QualityRecheckEvidence.matchesCurrentChecks(attempt, "PASS", true, null)).isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"total", "fail", "readHash", "baselineReadHash",
            "definitionHash", "baselineDefinitionHash", "auditId", "baselineAuditId", "issueBindingHash",
            "reasonCode", "resultType", "statScope", "productType"})
    void currentChecksCannotReplaceAnOldPassWithDifferentExecutionFacts(String field) {
        var order = QualityWorkOrderDO.builder().id(8L).taskId(1L).currentSubmissionId(40L).build();
        var submission = QualityWorkOrderSubmissionDO.builder().id(40L).workOrderId(8L).instanceId(20L)
                .revision(1).submittedAt(submittedAt).payloadHash("b".repeat(64)).issueIds("[30]").build();
        var attempt = QualityWorkOrderRecheckDO.builder().id(50L).attemptNo(1).requestKey("recheck:50")
                .requestedBy(60L).workOrderId(8L).submissionId(40L).instanceId(20L)
                .status("PASS").comparableFlag(1).build();
        var facts = compare().evidence();
        attempt.setEvidenceJson(JsonUtils.toJsonString(QualityRecheckEvidence.decision(order, submission, attempt,
                "PASS", true, List.of(facts))));
        var changed = new java.util.LinkedHashMap<>(facts);
        changed.put(field, "different");
        assertThat(QualityRecheckEvidence.matchesCurrentChecks(attempt, "PASS", true, List.of(changed))).isFalse();
        attempt.setEvidenceJson("{}");
        assertThat(QualityRecheckEvidence.matchesCurrentChecks(attempt, "PASS", true, List.of(facts))).isFalse();
    }

    @Test void decisionKeepsUnsafeJavaScriptIdsExactAcrossJson() {
        long issueId = 9007199254740993L;
        var order = QualityWorkOrderDO.builder().id(8L).taskId(1L).currentSubmissionId(40L).build();
        var submission = QualityWorkOrderSubmissionDO.builder().id(40L).workOrderId(8L).instanceId(20L)
                .revision(1).submittedAt(submittedAt).payloadHash("b".repeat(64))
                .issueIds("[\"" + issueId + "\"]").build();
        var attempt = QualityWorkOrderRecheckDO.builder().id(Long.MAX_VALUE).attemptNo(1).requestKey("recheck:large")
                .requestedBy(60L).workOrderId(8L).submissionId(40L).instanceId(20L)
                .status("PASS").comparableFlag(1).build();
        issue.setId(issueId);
        var facts = compare().evidence();
        attempt.setEvidenceJson(JsonUtils.toJsonString(QualityRecheckEvidence.decision(order, submission, attempt,
                "PASS", true, List.of(facts))));
        assertThat(QualityRecheckEvidence.qualifies(attempt, order, submission)).isTrue();
        assertThat(QualityRecheckEvidence.matchesCurrentChecks(attempt, "PASS", true, List.of(facts))).isTrue();
        var parsed = JsonUtils.parseObject(attempt.getEvidenceJson(), QualityRecheckEvidence.Decision.class);
        assertThat(parsed.binding().attemptId()).isEqualTo(Long.MAX_VALUE);
        assertThat(parsed.binding().issueIds()).containsExactly(issueId);
        assertThat(QualityRecheckEvidence.qualifiesCurrentIssues(attempt, order, submission,
                cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredSourceGenerations.empty(), List.of(issue))).isTrue();
    }

    private String readEvidence(QualityAuditLogDO audit) {
        var connection = new cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver.ConnectionInfo();
        connection.setDatabaseId(4L); connection.setUsageType(1); connection.setSchema("public");
        connection.setConfigurationRevision("2026-09-20T10:00:00.123456");
        connection.setProductType(cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL);
        var read = QualityReadEvidence.capture(connection, current.getObjects().getFirst(),
                audit.getResultSql(), true, audit.getCheckTime(), audit.getCheckTime().plusSeconds(1));
        read.setSchemaVersion(QualityReadEvidence.RELATION_SCHEMA_VERSION);
        read.setRelationFingerprint("a".repeat(64)); // 冻结的同一普通表；具体解析由 DB Switch PG 用例证明。
        return JsonUtils.toJsonString(read);
    }

    private QualityWorkOrderServiceImpl.RecheckJudge judge() {
        original.setDefinitionSnapshot(JsonUtils.toJsonString(baseline));
        recheck.setDefinitionSnapshot(JsonUtils.toJsonString(current));
        return service.judgeRecheck(QualityWorkOrderDO.builder().id(8L).taskId(1L).build(), recheck,
                QualityWorkOrderSubmissionDO.builder().id(40L).workOrderId(8L).submittedAt(submittedAt).build());
    }

    private QualityTaskExecutor.GateDefinition definition() {
        var definition = new QualityTaskExecutor.GateDefinition();
        definition.setTask(QualityTaskDO.builder().id(1L).build());
        definition.setObjects(List.of(QualityCheckObjectDO.builder().id(3L).datasourceConnId(4L)
                .datasourceType("THIRD_PARTY").tableName("orders").build()));
        definition.setRules(List.of(QualityRuleDO.builder().id(2L).ruleType("RANGE").strategyKey("range")
                .checkLevel("FIELD").status(0).paramSchema("{\"column\":\"amount\",\"min\":10}").build()));
        return definition;
    }

    private QualityAuditLogDO audit(long id, long instance, String status, long fail) {
        return QualityAuditLogDO.builder().id(id).instanceId(instance).taskId(1L).ruleId(2L).checkObjectId(3L)
                .tableName("orders").checkField("amount").ruleType("RANGE")
                .checkTime(submittedAt.plusMinutes(1).plusSeconds(1)).totalCount(100L).failCount(fail)
                .resultType(status).resultSql("SELECT COUNT(*), COUNT(*) FILTER (WHERE amount < 10) FROM orders").build();
    }
}
