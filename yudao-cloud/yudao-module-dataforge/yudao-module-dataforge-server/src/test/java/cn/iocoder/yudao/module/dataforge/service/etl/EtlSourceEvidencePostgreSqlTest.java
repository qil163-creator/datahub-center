package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.etl.core.connection.EtlRowPolicyPort;
import cn.iocoder.yudao.framework.etl.core.engine.*;
import cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry;
import cn.iocoder.yudao.framework.etl.core.model.*;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRowOrigin;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers
class EtlSourceEvidencePostgreSqlTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new cn.iocoder.yudao.module.dataforge.support.DataforgePostgreSqlContainer();
    private EtlBatchPgFixture pg;
    private final EtlQualityEvidenceService evidence = new EtlQualityEvidenceService();
    private final EtlSourceEvidenceService sources = new EtlSourceEvidenceService();
    private final EtlNodeAccessService access = new EtlNodeAccessService();
    private EtlQualityIssueMapper issues;
    private EtlRecordDO record;
    private Long batch;

    @BeforeEach void setup() throws Exception {
        pg = new EtlBatchPgFixture(POSTGRES); migrate();
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(99L).setTenantId(7L), new MockHttpServletRequest());
        pg.sql("CREATE TABLE " + pg.targetSchema + ".source_rows(id bigint PRIMARY KEY, secret text)");
        pg.sql("INSERT INTO " + pg.targetSchema + ".source_rows VALUES(9007199254740993,'private-fixture')");
        pg.connections.resolve(1L).setConfigurationRevision("revision-1");
        ReflectionTestUtils.setField(sources, "connections", pg.connections);
        ReflectionTestUtils.setField(sources, "runtime", pg.runtime);
        ReflectionTestUtils.setField(sources, "access", pg.access);
        ReflectionTestUtils.setField(access, "executionAccessService", pg.access);
        ReflectionTestUtils.setField(access, "queries", pg.queries);
        issues = pg.app.getBean(EtlQualityIssueMapper.class);
        ReflectionTestUtils.setField(evidence, "issueMapper", issues);
        ReflectionTestUtils.setField(evidence, "batchMapper", pg.app.getBean(EtlQualityBatchMapper.class));
        ReflectionTestUtils.setField(evidence, "recordMapper", pg.records);
        ReflectionTestUtils.setField(evidence, "coordinator", pg.coordinator);
        ReflectionTestUtils.setField(evidence, "sourceEvidence", sources);
        ReflectionTestUtils.setField(evidence, "archives", mock(EtlArchiveService.class));
        ReflectionTestUtils.setField(evidence, "access", pg.access);
    }
    @AfterEach void close() throws Exception { SecurityContextHolder.clearContext(); if (pg != null) pg.close(); }

    @Test void persistedProofRequeriesOriginalRowAndHidesInternalIdentity() throws Exception {
        var issue = execute(false);
        assertNotNull(issue.getProvenanceJson());
        assertTrue(issue.getProvenanceJson().contains("9007199254740993"));
        var response = evidence.issue(issue.getId());
        assertTrue(response.isSourceVerified());
        assertTrue(response.getRowJson().contains("private-fixture"));
        assertFalse(JsonUtils.toJsonString(response).contains("provenance"));
        assertEquals(0, pg.runtime.queries().activeOperations());
        verify(pg.access, atLeastOnce()).requireUserPermission(99L, "dataforge:etl:query");
    }

    @Test void changedDeletedSourceAndChangedConnectionCannotReturnSavedContent() throws Exception {
        var issue = execute(false);
        pg.sql("UPDATE " + pg.targetSchema + ".source_rows SET secret='changed'");
        assertThrows(RuntimeException.class, () -> evidence.issue(issue.getId()));
        pg.sql("UPDATE " + pg.targetSchema + ".source_rows SET secret='private-fixture'");
        pg.connections.resolve(1L).setConfigurationRevision("revision-2");
        assertThrows(RuntimeException.class, () -> evidence.issue(issue.getId()));
        pg.connections.resolve(1L).setConfigurationRevision("revision-1");
        pg.sql("DELETE FROM " + pg.targetSchema + ".source_rows");
        assertThrows(RuntimeException.class, () -> evidence.issue(issue.getId()));
    }

    @Test void transformedSnapshotIsVisibleWithoutClaimingSourceVerification() throws Exception {
        var transformed = execute(true); assertNull(transformed.getProvenanceJson());
        var snapshot = evidence.issue(transformed.getId());
        assertEquals(transformed.getRowJson(), snapshot.getRowJson());
        assertFalse(snapshot.isSourceVerified());
        verify(pg.access, atLeastOnce()).requireUserPermission(99L, "dataforge:etl:query");
        assertThrows(RuntimeException.class, () -> evidence.issue(-1L));
        clearInvocations(pg.databaseApi); verifyNoInteractions(pg.databaseApi);
    }

    @Test void objectAccessRevocationOrProofTamperingFailsClosedAndMigrationReplays() throws Exception {
        var issue = execute(false); migrate();
        doThrow(new SecurityException("denied")).when(pg.access).requireDatabase(1L);
        assertThrows(RuntimeException.class, () -> evidence.issue(issue.getId()));
        doNothing().when(pg.access).requireDatabase(1L);
        pg.sql("UPDATE dh_dataforge_etl_quality_issue SET provenance_json=replace(provenance_json,'revision-1','revision-forged') WHERE id=" + issue.getId());
        assertThrows(RuntimeException.class, () -> evidence.issue(issue.getId()));
        assertThrows(java.sql.SQLException.class, () -> pg.sql("UPDATE dh_dataforge_etl_quality_issue SET provenance_json=repeat('x',32769) WHERE id=" + issue.getId()));
    }

    @Test void failedProofInsertRollsBackRowAndLostLeaseCannotAppendEvidence() throws Exception {
        var issue = execute(false);
        String proof = issue.getProvenanceJson();
        assertThrows(RuntimeException.class, () -> evidence.recordIssue(record, batch, 2, "{}", List.of(), "x".repeat(32769)));
        assertEquals(1, issues.selectCount(null));
        Long interrupted = evidence.begin(record, "interrupted", "frozen-plan");
        pg.coordinator.requestStop(record.getId());
        assertThrows(RuntimeException.class, () -> evidence.recordIssue(record, batch, 2, "{}", List.of(), proof));
        assertThrows(RuntimeException.class, () -> evidence.complete(record, interrupted, new EtlRowPolicyPort.Counts(1, 1, 0, 0, 0)));
        var unfinished = pg.app.getBean(EtlQualityBatchMapper.class).selectById(interrupted);
        assertEquals("COLLECTING", unfinished.getRegistrationStatus()); assertNull(unfinished.getInputCount());
        assertEquals(1, issues.selectCount(null));
    }

    @Test void historicalSnapshotRemainsReadableButExpiredRetentionRejectsIt() throws Exception {
        var issue = execute(false);
        var archive = (EtlArchiveService) ReflectionTestUtils.getField(evidence, "archives");
        doThrow(new IllegalStateException("retention expired")).when(archive).requireEvidenceAccess(record.getId());
        clearInvocations(pg.queries);
        assertThrows(RuntimeException.class, () -> evidence.issue(issue.getId())); verifyNoInteractions(pg.queries);
        doNothing().when(archive).requireEvidenceAccess(record.getId());
        pg.sql("UPDATE dh_dataforge_etl_quality_issue SET provenance_json=NULL WHERE id=" + issue.getId());
        var historical = evidence.issue(issue.getId());
        assertEquals(issue.getRowJson(), historical.getRowJson());
        assertFalse(historical.isSourceVerified());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"quoted'key", "back\\slash", "line\nbreak", "a' OR 1=1; DROP TABLE secret; --"})
    void textPrimaryKeysRemainBoundDataWithoutStringInterpolation(String value) throws Exception {
        pg.sql("ALTER TABLE " + pg.targetSchema + ".source_rows ALTER COLUMN id TYPE text USING id::text");
        try (var statement = pg.control.prepareStatement("UPDATE " + pg.targetSchema + ".source_rows SET id=?")) {
            statement.setString(1, value); statement.executeUpdate();
        }
        var issue = execute(false); var verified = evidence.issue(issue.getId());
        assertTrue(verified.isSourceVerified());
        assertEquals(value, JsonUtils.parseObject(verified.getRowJson(), Map.class).get("id"));
    }

    @Test void uuidPrimaryKeyUsesNativeBoundValueWithoutStringInterpolation() throws Exception {
        String uuid = "8e12b7d8-0105-4000-9000-123456789abc";
        pg.sql("ALTER TABLE " + pg.targetSchema + ".source_rows ALTER COLUMN id TYPE uuid USING '" + uuid + "'::uuid");
        var issue = execute(false); var verified = evidence.issue(issue.getId());
        assertTrue(verified.isSourceVerified()); assertTrue(verified.getRowJson().contains(uuid));
    }

    @Test void optionalProofWhoseEscapedUtf8ExceedsStorageBudgetIsOmittedBeforeInsert() throws Exception {
        var issue = execute(false);
        var proof = JsonUtils.parseObject(issue.getProvenanceJson(), EtlSourceEvidenceService.Proof.class);
        var original = proof.origin();
        var keys = new TreeMap<String, cn.iocoder.yudao.framework.dbswitch.common.util.TypedRowFingerprint.KeyValue>();
        for (int i = 0; i < 4; i++) keys.put("key" + i,
                cn.iocoder.yudao.framework.dbswitch.common.util.TypedRowFingerprint.KeyValue.of("\u0001".repeat(4096)));
        var oversized = new EtlRowOrigin(1, original.sourceNode(), original.connectionId(), original.configurationRevision(),
                original.connectionHash(), original.schema(), original.table(), keys, original.sourceHash(), original.emittedHash());
        var batchRow = pg.app.getBean(EtlQualityBatchMapper.class).selectById(batch);
        assertNull(sources.freeze(record, batchRow, oversized, Map.of("id", 9007199254740993L, "secret", "private-fixture")));
        assertEquals(1, issues.selectCount(null));
    }

    @Test void currentLookupAndFullReaderShareTypedFingerprintForNativePostgresScalars() throws Exception {
        pg.sql("ALTER TABLE " + pg.targetSchema + ".source_rows ADD COLUMN n numeric(30,6), ADD COLUMN flag boolean,"
                + " ADD COLUMN f real, ADD COLUMN d double precision, ADD COLUMN b bytea, ADD COLUMN day date,"
                + " ADD COLUMN stamp timestamp, ADD COLUMN empty text");
        pg.sql("UPDATE " + pg.targetSchema + ".source_rows SET n=12345678901234567890.123456,flag=true,f=1.25,d=1.23456789,"
                + "b=decode('00ff01','hex'),day='2026-10-03',stamp='2026-10-03 12:34:56.123456'");
        var issue = execute(false);
        assertNotNull(issue.getProvenanceJson()); assertTrue(evidence.issue(issue.getId()).isSourceVerified());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"time", "timetz", "timestamptz"})
    void unprovenJdbcTemporalPrecisionLeavesSnapshotReadableWithoutSourceVerification(String type) throws Exception {
        pg.sql("ALTER TABLE " + pg.targetSchema + ".source_rows ADD COLUMN unproven " + type);
        var issue = execute(false);
        assertNull(issue.getProvenanceJson());
        var snapshot = evidence.issue(issue.getId());
        assertEquals(issue.getRowJson(), snapshot.getRowJson());
        assertFalse(snapshot.isSourceVerified());
    }

    private EtlQualityIssueDO execute(boolean transform) throws Exception {
        record = pg.run("source-proof-" + UUID.randomUUID(), true);
        List<Map<String, Object>> definitions = new ArrayList<>();
        definitions.add(graph("source", "read-db", 1, Map.of("databaseId", 1, "tableName", "source_rows")));
        List<Map<String, Object>> connections = new ArrayList<>(); String previous = "source";
        if (transform) {
            definitions.add(graph("transform", "add-constant", 2, Map.of("fields", List.of(Map.of("name", "added", "value", "x")))));
            connections.add(Map.of("sourceNodeId", previous, "targetNodeId", "transform")); previous = "transform";
        }
        definitions.add(graph("quality", "quality-check", 2, Map.of()));
        connections.add(Map.of("sourceNodeId", previous, "targetNodeId", "quality"));
        String snapshot = JsonUtils.toJsonString(Map.of("nodes", definitions, "edges", connections, "parameters", List.of()));
        pg.sql("UPDATE dh_dataforge_etl_record SET config_json='" + snapshot.replace("'", "''") + "'::jsonb WHERE id=" + record.getId());
        record = pg.records.selectById(record.getId()); batch = evidence.begin(record, "quality", "frozen-plan");
        var port = new EtlRowPolicyPort() {
            public List<Violation> evaluate(String node, Map<String, Object> row) { return List.of(new Violation("rule", "secret", "ERROR", "INVALID", "invalid")); }
            public void recordIssue(String node, long sequence, Map<String, Object> row, List<Violation> violations) { fail("Origin callback required"); }
            public void recordIssue(String node, long sequence, Map<String, Object> row, List<Violation> violations, EtlRowOrigin origin) {
                String proof = origin == null ? null : evidence.sourceProof(record, batch, origin, row);
                evidence.recordIssue(record, batch, sequence, JsonUtils.toJsonString(row), violations, proof);
            }
            public void complete(String node, Counts counts) { evidence.complete(record, batch, counts); }
        };
        var dag = EtlDag.build(EtlGraphConverter.toNodeDefs(definitions), EtlGraphConverter.toEdgeDefs(connections));
        try (var engine = new EtlPipelineEngine(new EtlExecutorRegistry(), pg.runtime)) {
            var result = engine.execute(dag, EtlRunOptions.builder().connectionResolver(pg.connections).readPolicyPort(access.createReadPort(99L))
                    .rowPolicyPort(port).captureSourceOrigins(true)
                    .build());
            assertTrue(result.isSuccess(), result.getFirstError());
        }
        return issues.selectList(null).getFirst();
    }
    private static Map<String, Object> graph(String id, String tool, int type, Map<String, Object> config) {
        return Map.of("id", id, "properties", Map.of("toolCode", tool, "toolType", type, "nodeJson", JsonUtils.toJsonString(config)));
    }
    private void migrate() throws Exception {
        pg.migrate("0105_dataforge_etl-source-provenance.sql");
    }
}
