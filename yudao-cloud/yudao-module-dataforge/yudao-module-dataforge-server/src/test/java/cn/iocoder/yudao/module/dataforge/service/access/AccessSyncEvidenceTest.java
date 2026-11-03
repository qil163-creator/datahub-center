package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.domain.MigrationTermination;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBatchEvidenceDTO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessTaskDO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AccessSyncEvidenceTest {
    @Test void boundedCommitReceiptsSurviveAdapterAndJsonWithoutBeingFullArrivalProof() throws Exception {
        var collector = new cn.iocoder.yudao.framework.dbswitch.data.domain.BatchCommitSummary.Collector();
        collector.add(null);
        for (int i = 0; i < 20; i++) collector.add(new cn.iocoder.yudao.framework.dbswitch.dbwriter.TransactionCommitReceipt(
                1, "POSTGRESQL_XID8_V1", Long.toString(9007199254740993L + i), "a".repeat(64)));
        var result = success();
        result.getTableResultList().getFirst().setTransferSummary(new cn.iocoder.yudao.framework.dbswitch.data.domain.TableTransferSummary(
                cn.iocoder.yudao.framework.dbswitch.data.domain.TableTransferSummary.Operation.FULL_SCAN_TABLE,
                12, 12, 2, 2, 0, false, false, false, collector.snapshot()));
        var evidence = AccessSyncEvidence.finish(binding(31L, 1), result);
        var commits = evidence.tables().getFirst().transfer().commits();
        assertEquals(21L, commits.totalBatches()); assertEquals(20L, commits.witnessedBatches());
        assertTrue(commits.truncated()); assertEquals(16, commits.receipts().size());
        assertEquals(2L, commits.receipts().getFirst().batch());
        assertThrows(UnsupportedOperationException.class, () -> commits.receipts().clear());
        var j2 = new com.fasterxml.jackson.databind.ObjectMapper();
        var j3 = tools.jackson.databind.json.JsonMapper.builder().build();
        for (String json : List.of(j2.writeValueAsString(commits), j3.writeValueAsString(commits))) {
            assertTrue(j2.readTree(json).get("receipts").get(0).get("transactionId").isTextual());
            assertEquals(commits, j2.readValue(json, cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncCommitReceiptsDTO.class));
            assertEquals(commits, j3.readValue(json, cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncCommitReceiptsDTO.class));
            assertFalse(json.contains("private-"));
        }
        assertEquals(evidence, JsonUtils.parseObject(JsonUtils.toJsonString(evidence), SyncBatchEvidenceDTO.class));
    }

    @Test void actualTransferIsFrozenWithoutChangingInventorySemantics() {
        var result = success();
        var summary = new cn.iocoder.yudao.framework.dbswitch.data.domain.TableTransferSummary(
                cn.iocoder.yudao.framework.dbswitch.data.domain.TableTransferSummary.Operation.FULL_SCAN_TABLE,
                12, 12, 3, 2, 1, true, false, true);
        result.getTableResultList().getFirst().setTransferSummary(summary);
        var evidence = AccessSyncEvidence.finish(binding(31L, 1), result);
        assertTrue(evidence.manifestComplete(), "清单完整仍不表示全字段/无损复制");
        var transfer = evidence.tables().getFirst().transfer();
        assertEquals("FULL_SCAN_TABLE", transfer.operation());
        assertEquals(12L, transfer.acknowledgedRows()); assertEquals(2, transfer.copiedColumns());
        assertEquals(1L, transfer.substitutedNullValues()); assertTrue(transfer.transformed()); assertTrue(transfer.customScripts());
        result.getTableResultList().getFirst().setTransferSummary(null);
        assertEquals(transfer, evidence.tables().getFirst().transfer());
        assertEquals(evidence, JsonUtils.parseObject(JsonUtils.toJsonString(evidence), SyncBatchEvidenceDTO.class));
    }

    @Test void oldSuccessNeverInventsTransferProof() {
        var evidence = AccessSyncEvidence.finish(binding(31L, 1), success());
        assertTrue(evidence.manifestComplete()); assertNull(evidence.tables().getFirst().transfer());
        String json = "{\"sourceSchema\":\"public\",\"sourceTable\":\"orders\",\"targetSchema\":\"public\","
                + "\"targetTable\":\"orders_copy\",\"rows\":12,\"successful\":true}";
        assertNull(JsonUtils.parseObject(json, SyncBatchEvidenceDTO.Table.class).transfer());
        var missing = JsonUtils.parseObject("{\"schemaVersion\":1,\"operation\":\"FULL_SCAN_TABLE\"}",
                cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncTableTransferDTO.class);
        assertNull(missing.readRows()); assertNull(missing.transformed()); assertNull(missing.customScripts());
        assertNull(missing.commits());
    }

    static SyncBatchEvidenceDTO binding(Long runId, Integer attempt) {
        return AccessSyncEvidence.bind(DataAccessTaskDO.builder().id(runId).attemptCount(attempt).configJson("{}").build(),
                connection(10L), connection(20L), new SourceDataSourceProperties(), new TargetDataSourceProperties());
    }

    static AccessDatabaseResolver.ConnectionInfo connection(long id) {
        var info = new AccessDatabaseResolver.ConnectionInfo();
        info.setDatabaseId(id); info.setProductType(ProductTypeEnum.POSTGRESQL); info.setSchema("public");
        info.setConfigurationRevision("2026-09-25T09:01:02.123456");
        info.setJdbcUrl("jdbc:fixture:private-location"); info.setUsername("private-user"); info.setPassword("private-secret");
        return info;
    }

    static DbSwitchResult success() {
        var result = new DbSwitchResult(); result.setTermination(MigrationTermination.CONFIRMED);
        var table = new DbSwitchTableResult();
        table.setSourceSchemaName("public"); table.setSourceTableName("orders");
        table.setTargetSchemaName("public"); table.setTargetTableName("orders_copy");
        table.getSyncCount().set(12); table.setErrorMsg("private-secret"); table.setSuccessMsg("private-user");
        result.getTableResultList().add(table); result.getTotalTableCount().set(1); result.getTotalTableSuccessCount().set(1);
        return result;
    }

    @Test void actualBindingAndManifestContainNoCredentialsOrEngineMessages() {
        var binding = binding(31L, 2);
        assertEquals("RUNNING", binding.termination()); assertFalse(binding.manifestComplete());
        assertEquals(2, binding.attempt()); assertEquals(10L, binding.sourceDatabaseId()); assertEquals(20L, binding.targetDatabaseId());
        var evidence = AccessSyncEvidence.finish(binding, success());
        assertTrue(evidence.manifestComplete()); assertTrue(AccessSyncEvidence.sameBinding(binding, evidence));
        assertEquals(12L, evidence.tables().getFirst().rows());
        String json = JsonUtils.toJsonString(evidence);
        assertFalse(json.contains("private-")); assertFalse(json.contains("password")); assertFalse(json.contains("jdbc:"));
        assertEquals(evidence, JsonUtils.parseObject(json, SyncBatchEvidenceDTO.class));
    }

    @Test void differentExecutionAndConfigurationCannotReuseBinding() {
        assertFalse(AccessSyncEvidence.sameBinding(binding(31L, 1), binding(32L, 1)));
        assertFalse(AccessSyncEvidence.sameBinding(binding(31L, 1), binding(31L, 2)));
        var changed = connection(20L); changed.setConfigurationRevision("new-revision");
        var after = AccessSyncEvidence.bind(DataAccessTaskDO.builder().id(31L).attemptCount(1).configJson("{}").build(),
                connection(10L), changed, new SourceDataSourceProperties(), new TargetDataSourceProperties());
        assertFalse(AccessSyncEvidence.sameBinding(binding(31L, 1), after));
        assertFalse(AccessSyncEvidence.sameBinding(null, after));
    }

    @Test void unknownAndRunningEngineCannotSupplyCompleteManifest() {
        var result = success();
        for (var termination : List.of(MigrationTermination.RUNNING, MigrationTermination.UNCONFIRMED)) {
            result.setTermination(termination); assertFalse(AccessSyncEvidence.finish(binding(31L, 1), result).manifestComplete());
        }
        assertFalse(AccessSyncEvidence.finish(binding(31L, 1), null).manifestComplete());
        result.setTermination(null); assertEquals("UNCONFIRMED", AccessSyncEvidence.finish(binding(31L, 1), result).termination());
    }

    @Test void partialFailureIsPreservedEvenWhenResultInventoryIsComplete() {
        var result = success(); result.getTableResultList().getFirst().getIfSuccess().set(false);
        result.getTotalTableSuccessCount().set(0); result.getTotalTableFailCount().set(1); result.getIfAllSuccess().set(false);
        var evidence = AccessSyncEvidence.finish(binding(31L, 1), result);
        assertTrue(evidence.manifestComplete(), "清单完整不等于同步成功或覆盖整改数据");
        assertEquals(1L, evidence.failedTables()); assertFalse(evidence.tables().getFirst().successful());
    }

    @Test void inconsistentCountersAreNeverComplete() {
        var result = success(); result.getTotalTableCount().set(2);
        assertFalse(AccessSyncEvidence.finish(binding(31L, 1), result).manifestComplete());
        result = success(); result.getTotalTableSuccessCount().set(0);
        assertFalse(AccessSyncEvidence.finish(binding(31L, 1), result).manifestComplete());
        result = success(); result.getIfAllSuccess().set(false);
        assertFalse(AccessSyncEvidence.finish(binding(31L, 1), result).manifestComplete());
        result = success(); result.setTotalTableFailCount(null);
        assertFalse(AccessSyncEvidence.finish(binding(31L, 1), result).manifestComplete());
    }

    @Test void emptyRunCannotProveCoverage() {
        var result = new DbSwitchResult(); result.setTermination(MigrationTermination.CONFIRMED);
        assertFalse(AccessSyncEvidence.finish(binding(31L, 1), result).manifestComplete());
    }

    @Test void missingAndOversizedIdentifiersAndNegativeRowsAreIncomplete() {
        var result = success(); result.getTableResultList().getFirst().setTargetTableName(" ");
        assertTrue(AccessSyncEvidence.finish(binding(31L, 1), result).tables().isEmpty());
        result = success(); result.getTableResultList().getFirst().setSourceSchemaName(null);
        assertFalse(AccessSyncEvidence.finish(binding(31L, 1), result).manifestComplete());
        result = success(); result.getTableResultList().getFirst().setSourceTableName("x".repeat(1025));
        assertTrue(AccessSyncEvidence.finish(binding(31L, 1), result).tables().isEmpty());
        result = success(); result.getTableResultList().getFirst().getSyncCount().set(-1);
        assertFalse(AccessSyncEvidence.finish(binding(31L, 1), result).manifestComplete());
    }

    @Test void duplicateTargetCannotBeCountedAsIndependentCoverage() {
        var result = success(); result.getTableResultList().add(result.getTableResultList().getFirst());
        result.getTotalTableCount().set(2); result.getTotalTableSuccessCount().set(2);
        assertFalse(AccessSyncEvidence.finish(binding(31L, 1), result).manifestComplete());
    }

    @Test void maximumInventoryIsBoundedAndTruncationCannotPass() {
        var result = success(); var tables = new ArrayList<DbSwitchTableResult>();
        for (int i = 0; i <= AccessSyncEvidence.MAX_TABLES; i++) {
            var table = success().getTableResultList().getFirst(); table.setTargetTableName("copy_" + i); tables.add(table);
        }
        result.setTableResultList(tables); result.getTotalTableCount().set(tables.size()); result.getTotalTableSuccessCount().set(tables.size());
        var evidence = AccessSyncEvidence.finish(binding(31L, 1), result);
        assertEquals(AccessSyncEvidence.MAX_TABLES, evidence.tables().size()); assertFalse(evidence.manifestComplete());
    }

    @Test void customSqlAndDdlDoNotLeakSqlOrImplyCopiedData() {
        var source = new SourceDataSourceProperties(); source.setSourceType(2);
        var target = new TargetDataSourceProperties(); target.setChangeDataSync(true);
        var run = DataAccessTaskDO.builder().id(31L).attemptCount(1).configJson("{}").build();
        var custom = AccessSyncEvidence.bind(run, connection(10L), connection(20L), source, target);
        assertEquals("CUSTOM_SQL", custom.mode());
        var result = success(); result.getTableResultList().getFirst().setSourceTableName("SELECT 'private-row' AS secret");
        var evidence = AccessSyncEvidence.finish(custom, result);
        assertFalse(evidence.manifestComplete()); assertTrue(evidence.tables().isEmpty());
        assertFalse(JsonUtils.toJsonString(evidence).contains("private-row"));
        target.setOnlyCreate(true);
        assertEquals("DDL_ONLY", AccessSyncEvidence.bind(run, connection(10L), connection(20L), source, target).mode());
    }

    @Test void capturedManifestDoesNotChangeWhenEngineObjectsAreMutated() {
        var result = success(); var evidence = AccessSyncEvidence.finish(binding(31L, 1), result);
        result.getTableResultList().getFirst().getSyncCount().set(900);
        result.getTableResultList().clear(); result.getTotalTableCount().set(99);
        assertEquals(12L, evidence.tables().getFirst().rows()); assertEquals(1L, evidence.totalTables());
        assertThrows(UnsupportedOperationException.class, () -> evidence.tables().clear());
    }

    @Test void largeIdsAndCountersRoundTripWithoutNarrowing() {
        long id = 9007199254740993L;
        var result = success(); result.getTableResultList().getFirst().getSyncCount().set(id);
        var evidence = AccessSyncEvidence.finish(binding(id, 1), result);
        var decoded = JsonUtils.parseObject(JsonUtils.toJsonString(evidence), SyncBatchEvidenceDTO.class);
        assertEquals(id, decoded.runId()); assertEquals(id, decoded.tables().getFirst().rows());
    }
}
