package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessTaskDO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 DB Switch 迁移结果与证据适配；只操作独立回环测试库中的随机命名空间。 */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
@Timeout(45)
class AccessSyncEvidencePostgreSqlTest {
    private final String sourceSchema = "batch_src_" + UUID.randomUUID().toString().replace("-", "");
    private final String targetSchema = "batch_dst_" + UUID.randomUUID().toString().replace("-", "");
    private Connection control;
    private MigrationRequest properties;

    @BeforeEach void setup() throws Exception {
        String url = System.getProperty("datahub.test.postgres.url");
        assertTrue(url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+"));
        String user = System.getProperty("datahub.test.postgres.user", "dataforge_test");
        control = DriverManager.getConnection(url, user, "");
        try (var sql = control.createStatement()) {
            sql.execute("CREATE SCHEMA " + sourceSchema); sql.execute("CREATE SCHEMA " + targetSchema);
            sql.execute("CREATE TABLE " + sourceSchema + ".orders(id bigint PRIMARY KEY, note text)");
            sql.execute("INSERT INTO " + sourceSchema + ".orders VALUES (1,'fixed'),(9007199254740993,'value')");
        }
        properties = new MigrationRequest();
        var source = new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.POSTGRESQL); source.setDriverClassName("org.postgresql.Driver");
        source.setUrl(url); source.setUsername(user); source.setPassword(""); source.setPoolSize(2);
        source.setSourceSchema(sourceSchema); source.setIncludeOrExclude(1); source.setSourceIncludes("orders");
        properties.getSource().add(source);
        var target = properties.getTarget();
        target.setTargetProductType(ProductTypeEnum.POSTGRESQL); target.setDriverClassName("org.postgresql.Driver");
        target.setUrl(url); target.setUsername(user); target.setPassword(""); target.setPoolSize(2);
        target.setTargetSchema(targetSchema); target.setFailOnFieldReadError(true);
    }

    @AfterEach void cleanup() throws Exception {
        if (control != null) try (var sql = control.createStatement()) {
            assertTrue(sourceSchema.matches("batch_src_[a-f0-9]{32}"));
            assertTrue(targetSchema.matches("batch_dst_[a-f0-9]{32}"));
            sql.execute("DROP SCHEMA IF EXISTS " + sourceSchema + " CASCADE");
            sql.execute("DROP SCHEMA IF EXISTS " + targetSchema + " CASCADE");
        } finally { control.close(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"2,true","2,false","8503,true","8503,false"})
    void actualCopiedRowsAndTargetMappingAgreeWithRecordedBatch(int rowCount, boolean insertEngine) throws Exception {
        int major = control.getMetaData().getDatabaseMajorVersion();
        Assumptions.assumeTrue(major == 16,
                "DB Switch commit witnesses are supported only on PostgreSQL 16; available server is " + major);
        properties.getTarget().setWriterEngineInsert(insertEngine);
        properties.getSource().getFirst().setFetchSize(500);
        if (rowCount > 2) try (var sql = control.createStatement()) {
            sql.execute("INSERT INTO " + sourceSchema + ".orders SELECT n,'many' FROM generate_series(2,8502) n");
        }
        var sourceInfo = AccessSyncEvidenceTest.connection(10); sourceInfo.setSchema(sourceSchema);
        var targetInfo = AccessSyncEvidenceTest.connection(20); targetInfo.setSchema(targetSchema);
        var binding = AccessSyncEvidence.bind(DataAccessTaskDO.builder().id(31L).attemptCount(1).configJson("{}").build(),
                sourceInfo, targetInfo, properties.getSource().getFirst(), properties.getTarget());
        var engine = new MigrationService(properties, ignored -> { });
        var result = engine.run();
        var evidence = AccessSyncEvidence.finish(binding, result);
        assertTrue(evidence.manifestComplete()); assertEquals("CONFIRMED", evidence.termination());
        assertEquals(1L, evidence.successfulTables()); assertEquals(0L, evidence.failedTables());
        var table = evidence.tables().getFirst();
        assertEquals(sourceSchema, table.sourceSchema()); assertEquals(targetSchema, table.targetSchema());
        assertEquals("orders", table.sourceTable()); assertEquals("orders", table.targetTable()); assertEquals((long)rowCount, table.rows());
        assertEquals("FULL_SCAN_TABLE", table.transfer().operation());
        assertEquals((long)rowCount, table.transfer().readRows()); assertEquals((long)rowCount, table.transfer().acknowledgedRows());
        assertEquals(2, table.transfer().sourceColumns()); assertEquals(2, table.transfer().copiedColumns());
        var commits = table.transfer().commits();
        long batchCount = rowCount == 2 ? 1 : 18;
        assertNotNull(commits); assertEquals(batchCount, commits.totalBatches()); assertEquals(batchCount, commits.witnessedBatches());
        assertEquals(rowCount > 2,commits.truncated()); assertEquals(Math.min(16,batchCount), commits.receipts().size());
        assertNotNull(commits.coverage()); assertEquals(batchCount,commits.coverage().batchCount());
        assertNotNull(commits.coverage().relationFingerprint());
        assertTrue(commits.receipts().stream().allMatch(e -> commits.coverage().relationFingerprint().equals(e.relationFingerprint())));
        var j2 = new com.fasterxml.jackson.databind.ObjectMapper();
        var j3 = tools.jackson.databind.json.JsonMapper.builder().build();
        assertEquals(commits, j2.readValue(j3.writeValueAsString(commits),
                cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncCommitReceiptsDTO.class));
        assertEquals(commits, j3.readValue(j2.writeValueAsString(commits),
                cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncCommitReceiptsDTO.class));
        assertEquals(cn.iocoder.yudao.framework.dbswitch.core.util.PostgresRelationIdentity.storageFingerprint(
                control, targetSchema + ".orders", false), commits.coverage().relationFingerprint());
        assertEquals((long)rowCount,commits.coverage().acknowledgedRows());
        assertEquals(commits.receipts().getFirst().transactionId(),commits.coverage().firstTransactionId());
        if (rowCount == 2) assertEquals(commits.coverage().firstTransactionId(),commits.coverage().lastTransactionId());
        else assertNotEquals(commits.receipts().getLast().transactionId(),commits.coverage().lastTransactionId());
        try (var query = control.prepareStatement("SELECT pg_catalog.pg_xact_status(?::xid8)")) {
            query.setString(1, commits.receipts().getFirst().transactionId());
            try (var rows = query.executeQuery()) { assertTrue(rows.next()); assertEquals("committed", rows.getString(1)); }
        }
        try (var sql = control.createStatement()) {
            sql.execute("CREATE TABLE " + targetSchema + ".evidence_fixture(payload jsonb)");
        }
        try (var insert = control.prepareStatement("INSERT INTO " + targetSchema + ".evidence_fixture VALUES (?::jsonb)")) {
            insert.setString(1, cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(evidence)); insert.executeUpdate();
        }
        try (var sql = control.createStatement(); var stored = sql.executeQuery("SELECT payload::text FROM " + targetSchema + ".evidence_fixture")) {
            assertTrue(stored.next());
            assertEquals(evidence, cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseObject(stored.getString(1),
                    cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBatchEvidenceDTO.class));
        }
        try (var sql = control.createStatement(); var rows = sql.executeQuery("SELECT count(*) FROM " + sourceSchema
                + ".orders s FULL JOIN " + targetSchema + ".orders t USING(id)"
                + " WHERE s.id IS NULL OR t.id IS NULL OR s.note IS DISTINCT FROM t.note")) {
            assertTrue(rows.next()); assertEquals(0,rows.getLong(1));
        }
        try (var sql = control.createStatement(); var rows = sql.executeQuery("SELECT id,note FROM " + targetSchema
                + ".orders WHERE id IN (1,9007199254740993) ORDER BY id")) {
            assertTrue(rows.next()); assertEquals(1L, rows.getLong(1)); assertEquals("fixed", rows.getString(2));
            assertTrue(rows.next()); assertEquals(9007199254740993L, rows.getLong(1)); assertEquals("value", rows.getString(2));
            assertFalse(rows.next());
        }
    }

    @Test void successfulDdlOnlyRunNeverBecomesDataArrivalEvidence() throws Exception {
        properties.getTarget().setOnlyCreate(true);
        var sourceInfo = AccessSyncEvidenceTest.connection(10); sourceInfo.setSchema(sourceSchema);
        var targetInfo = AccessSyncEvidenceTest.connection(20); targetInfo.setSchema(targetSchema);
        var binding = AccessSyncEvidence.bind(DataAccessTaskDO.builder().id(31L).attemptCount(1).configJson("{}").build(),
                sourceInfo, targetInfo, properties.getSource().getFirst(), properties.getTarget());
        var result = new MigrationService(properties, ignored -> { }).run();
        assertTrue(result.getIfAllSuccess().get());
        var evidence = AccessSyncEvidence.finish(binding, result);
        assertEquals("DDL_ONLY", evidence.mode()); assertFalse(evidence.manifestComplete()); assertTrue(evidence.tables().isEmpty());
        try (var sql = control.createStatement(); var rows = sql.executeQuery("SELECT count(*) FROM " + targetSchema + ".orders")) {
            assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
        }
    }
}
