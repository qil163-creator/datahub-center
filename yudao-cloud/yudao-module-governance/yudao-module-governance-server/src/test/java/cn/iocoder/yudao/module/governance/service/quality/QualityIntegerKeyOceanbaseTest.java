package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.quality.core.audit.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityAuditLogMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Actual OceanBase reads, serialized evidence and domain decisions; no business database is used. */
@EnabledIfSystemProperty(named = "datahub.test.oceanbase.url", matches = "jdbc:oceanbase://127[.]0[.]0[.]1:[0-9]+/")
@EnabledIfSystemProperty(named = "datahub.test.oceanbase.driver", matches = ".+")
class QualityIntegerKeyOceanbaseTest {
    private Connection connection;
    private com.zaxxer.hikari.HikariDataSource pool;
    private MasterDatabaseResolver.ConnectionInfo route;
    private MasterDatabaseResolver resolver;
    private Statement sql;
    private String schema;
    private final AuditSqlGenerator generator = new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");

    @BeforeEach void setup() throws Exception {
        var dto = new cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO();
        dto.setId(4L); dto.setProductType("OCEANBASE"); dto.setJdbcUrl(System.getProperty("datahub.test.oceanbase.url"));
        dto.setUsername("root@test"); dto.setPassword(""); dto.setUsageType(1); dto.setConfigurationRevision("test-revision");
        var api = mock(cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi.class);
        org.mockito.Mockito.when(api.getDatabaseConnection(4L)).thenReturn(cn.iocoder.yudao.framework.common.pojo.CommonResult.success(dto));
        resolver = new MasterDatabaseResolver();
        ReflectionTestUtils.setField(resolver, "databaseApi", api);
        var jar = java.nio.file.Path.of(System.getProperty("datahub.test.oceanbase.driver")).toRealPath();
        var config = new cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties.Driver();
        config.setPath(jar.toString());
        config.setSha256(cn.hutool.crypto.digest.DigestUtil.sha256Hex(java.nio.file.Files.readAllBytes(jar)));
        ReflectionTestUtils.setField(resolver, "configuredJdbcDrivers",
                new cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.OCEANBASE, config)));
        route = resolver.resolve(4L, cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_CHECK_OBJECT_NOT_EXISTS);
        pool = resolver.createDataSource(route); connection = pool.getConnection();
        sql = connection.createStatement(); sql.setQueryTimeout(20);
        schema = "quality_ob_keys_" + UUID.randomUUID().toString().replace("-", "");
        sql.execute("CREATE DATABASE " + schema);
        route.setSchema(schema);
    }

    @AfterEach void cleanup() throws Exception {
        try { if (sql != null && schema != null) sql.execute("DROP DATABASE IF EXISTS " + schema); }
        finally { if (sql != null) sql.close(); if (connection != null) connection.close(); if (pool != null) pool.close(); }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"SMALLINT|-32768|32767|5", "INT|-2147483648|2147483647|4",
            "BIGINT|-9223372036854775808|9223372036854775807|-5"})
    void nativeIntegersKeepBoundariesAndShareOneGlobalScanBudget(String type, String low, String high, int jdbcType) throws Exception {
        create(type, type);
        insert("(1," + low + ",1,1,1," + low + ",NULL),(2," + high + ",2,2,2," + high + ",1)");
        var snapshot = single(2);
        assertTrue(snapshot.complete(), snapshot.reasonCode()); assertEquals(jdbcType, snapshot.jdbcType());
        assertEquals(2, snapshot.total()); assertEquals(1, snapshot.fail());
        assertEquals(snapshot, JsonUtils.parseObject(JsonUtils.toJsonString(snapshot), AuditKeySnapshot.class));
        var grouped = grouped(false, 2, 2);
        assertTrue(grouped.complete()); assertEquals(AuditKeySnapshot.OCEANBASE_INTEGER_GROUP_ENCODING, grouped.groupEncoding());
        assertEquals(List.of(low, high), grouped.groups().stream().map(AuditKeySnapshot.Group::key).toList());
        assertTrue(grouped.groups().stream().allMatch(group -> group.snapshot().complete()));
        assertEquals("KEY_LIMIT_EXCEEDED", single(1).reasonCode());
        for (boolean composite : new boolean[]{false, true}) {
            var overflow = grouped(composite, 1, 2);
            assertEquals("KEY_LIMIT_EXCEEDED", overflow.reasonCode()); assertTrue(overflow.groups().isEmpty());
            var groupLimit = grouped(composite, 2, 1);
            assertEquals("GROUP_LIMIT_EXCEEDED", groupLimit.reasonCode()); assertTrue(groupLimit.groups().isEmpty());
        }
    }

    @ParameterizedTest @CsvSource({"false,ALL", "false,ORG", "true,ALL", "true,ORG"})
    void repairedRowsPassButSameCountReplacementAndInstitutionMovesDoNot(boolean composite, String scope) throws Exception {
        create("BIGINT", "BIGINT");
        insert("(1,9007199254740992,11,1,1,9,NULL),(2,9007199254740993,12,1,1,9,1),(3,3,13,1,1,10,1)");
        var before = run(10, composite);
        insert("(1,9007199254740992,11,1,1,9,1)");
        var repaired = run(20, composite);
        var accepted = compare(before, repaired, scope);
        assertTrue(accepted.comparable(), accepted.reason()); assertEquals("PASS", accepted.resultType());
        insert("(1,9007199254740994,11,1,1,9,1)");
        var replacement = run(21, composite);
        var replacementComparison = compare(before, replacement, scope);
        assertFalse(replacementComparison.comparable());
        assertEquals("ROW_POPULATION_CHANGED", replacementComparison.evidence().get("reasonCode"),
                () -> "baseline=" + before.audit().getReadEvidence() + "; replacement=" + replacement.audit().getReadEvidence());
        if (composite) {
            // Individual column sets are unchanged, but tuple pairings differ.
            insert("(1,9007199254740992,12,1,1,9,1),(2,9007199254740993,11,1,1,9,1)");
            assertReason("ROW_POPULATION_CHANGED", compare(before, run(22, true), scope));
        }
        if ("ORG".equals(scope)) {
            insert("(1,9007199254740992,11,1,1,10,1),(2,9007199254740993,12,1,1,9,1),(3,3,13,1,1,9,1)");
            assertReason("ROW_POPULATION_CHANGED", compare(before, run(23, composite), scope));
        }
    }

    @ParameterizedTest @ValueSource(ints = {2, 3, 4})
    void compositeFramesBindColumnOrderAndTypes(int size) throws Exception {
        create("BIGINT", "BIGINT");
        insert("(1,-9223372036854775808,11,21,31,9007199254740992,NULL),(2,9223372036854775807,12,22,32,9007199254740993,1)");
        var columns = List.of("id", "part", "third_key", "fourth_key").subList(0, size);
        var snapshot = AuditKeySnapshot.readComposite(connection, generator, context(), columns, 2, 10);
        assertTrue(snapshot.complete(), snapshot.reasonCode());
        assertEquals(AuditKeySnapshot.OCEANBASE_COMPOSITE_ALGORITHM, snapshot.algorithm());
        assertEquals(snapshot, JsonUtils.parseObject(JsonUtils.toJsonString(snapshot), AuditKeySnapshot.class));
        assertNotEquals(snapshot.keyHash(), AuditKeySnapshot.readComposite(connection, generator, context(), columns.reversed(), 2, 10).keyHash());
        var groups = AuditKeySnapshot.readGroupedComposite(connection, generator, context(), columns, "org_code", 2, 2, 10);
        assertTrue(groups.complete());
        assertEquals(Set.of("9007199254740992", "9007199254740993"), new HashSet<>(groups.groups().stream().map(AuditKeySnapshot.Group::key).toList()));
        assertEquals(AuditKeySnapshot.OCEANBASE_INTEGER_GROUP_ENCODING, groups.groupEncoding());
    }

    @ParameterizedTest @CsvSource(delimiter = '|', value = {
            "VARCHAR(64)|'9'", "CHAR(64)|'9'", "TEXT|'9'", "DECIMAL(20,0)|9", "BIGINT UNSIGNED|9", "TINYINT|9", "DOUBLE|9"})
    void convertedNonIntegerKeysAndGroupsNeverBecomeQualified(String type, String value) throws Exception {
        create(type, type); insert("(1," + value + ",1,1,1," + value + ",NULL)");
        assertEquals("KEY_TYPE_UNSUPPORTED", single(10).reasonCode());
        assertEquals("KEY_TYPE_UNSUPPORTED", composite().reasonCode());
        for (boolean tuple : new boolean[]{false, true}) {
            var groups = grouped(tuple, 10, 10);
            assertEquals("GROUP_KEY_UNSUPPORTED", groups.reasonCode()); assertTrue(groups.groups().isEmpty());
        }
        var before = run(10, true);
        insert("(1," + value + ",1,1,1," + value + ",1)");
        var after = run(20, true);
        assertEquals("PASS", after.audit().getResultType());
        assertFalse(compare(before, after, "ALL").comparable());
        assertFalse(compare(before, after, "ORG").comparable());
    }

    @Test void nullAndDuplicateRowsKeepCountsWithoutUsableCoverage() throws Exception {
        create("BIGINT", "BIGINT"); insert("(1,1,1,1,1,9,NULL),(2,2,2,1,1,9,1)");
        var before = run(10, true);
        insert("(2,1,1,1,1,9,1)");
        assertEquals("KEY_NOT_UNIQUE_OR_NULL", composite().reasonCode());
        assertEquals("KEY_NOT_UNIQUE_OR_NULL", single(10).reasonCode());
        assertReason("ROW_COVERAGE_UNPROVEN", compare(before, run(20, true), "ALL"));
        insert("(1,NULL,1,1,1,9,1),(2,2,2,1,1,9,1)");
        assertFalse(single(10).complete()); assertFalse(composite().complete());
        insert("(1,1,1,1,1,NULL,1)");
        assertEquals("GROUP_KEY_UNSUPPORTED", grouped(false, 10, 10).reasonCode());
        assertEquals("GROUP_KEY_UNSUPPORTED", grouped(true, 10, 10).reasonCode());
    }

    @Test void foreignAndHistoricalCodecsCannotBeBorrowed() throws Exception {
        create("BIGINT", "BIGINT"); insert("(1,1,1,1,1,9,NULL)");
        var before = run(10, true); insert("(1,1,1,1,1,9,1)");
        var after = run(20, true);
        var evidence = JsonUtils.parseObject(after.audit().getReadEvidence(), QualityReadEvidence.class);
        evidence.getOrgScope().setGroupEncoding(AuditKeySnapshot.MYSQL_INTEGER_GROUP_ENCODING);
        after.audit().setReadEvidence(JsonUtils.toJsonString(evidence));
        assertReason("ORG_EVIDENCE_INCOMPLETE", compare(before, after, "ORG"));
        evidence.getOrgScope().setSchemaVersion(1); evidence.getOrgScope().setGroupEncoding(null);
        evidence.getOrgScope().setGroupJdbcType(null);
        after.audit().setReadEvidence(JsonUtils.toJsonString(evidence));
        assertReason("ORG_EVIDENCE_INCOMPLETE", compare(before, after, "ORG"));
    }

    @Test void jdbcMaxRowsDoesNotRemoveNativeSignedOrder() throws Exception {
        create("BIGINT", "BIGINT");
        insert("(1,-9223372036854775808,1,1,1,-9223372036854775808,NULL),"
                + "(2,9223372036854775807,2,1,1,9223372036854775807,1),"
                + "(3,9007199254740993,3,1,1,-9223372036854775808,1),"
                + "(4,9007199254740992,4,1,1,-9223372036854775808,1)");
        String hash = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            var snapshot = single(4); assertTrue(snapshot.complete(), snapshot.reasonCode());
            assertTrue(snapshot.sql().endsWith("ASC"));
            if (hash == null) hash = snapshot.keyHash(); else assertEquals(hash, snapshot.keyHash());
            var groups = grouped(false, 4, 2); assertTrue(groups.complete(), groups.reasonCode());
            assertTrue(groups.sql().endsWith("ASC"));
            assertEquals(List.of("-9223372036854775808", "9223372036854775807"), groups.groups().stream().map(AuditKeySnapshot.Group::key).toList());
            assertTrue(groups.groups().stream().allMatch(group -> group.snapshot().complete()), groups.toString());
        }
    }

    @Test void nonBlankOptionKeepsNumericZeroAndDistinguishesEmptyText() throws Exception {
        create("BIGINT", "BIGINT"); insert("(1,1,1,1,1,9,NULL),(2,2,2,2,2,10,0)");
        var context = context(); context.setConfig(Map.of("allowBlank", false));
        var snapshot = AuditKeySnapshot.read(connection, generator, context, "id", 10, 10);
        assertTrue(snapshot.complete()); assertEquals(2, snapshot.total()); assertEquals(1, snapshot.fail());
        sql.execute("CREATE TABLE " + schema + ".texts(id BIGINT,amount VARCHAR(20)) "
                + "");
        sql.execute("REPLACE INTO " + schema + ".texts VALUES(1,NULL),(2,''),(3,'0'),(4,' ')");
        context.setTableName(schema + ".texts");
        snapshot = AuditKeySnapshot.read(connection, generator, context, "id", 10, 10);
        assertTrue(snapshot.complete()); assertEquals(4, snapshot.total()); assertEquals(2, snapshot.fail());
        var before = run(10, false); insert("(1,1,1,1,1,9,0)"); var after = run(20, false);
        assertTrue(compare(before, after, "ALL").comparable());
        for (var run : List.of(before, after)) {
            var definition = JsonUtils.parseObject(run.instance().getDefinitionSnapshot(), QualityTaskExecutor.GateDefinition.class);
            definition.getRules().getFirst().setStrategyKey("LENGTH_VALIDATION");
            run.instance().setDefinitionSnapshot(JsonUtils.toJsonString(definition));
        }
        assertReason("RULE_DIALECT_UNVERIFIED", compare(before, after, "ALL"));
    }

    @Test void sameKeysAfterTruncateRebuildOrTemporaryShadowCannotReuseProof() throws Exception {
        create("BIGINT", "BIGINT"); insert("(1,1,1,1,1,9,NULL)"); var before = run(10, true);
        sql.execute("TRUNCATE TABLE " + table()); insert("(1,1,1,1,1,9,1)");
        var after = run(20, true); assertEquals("PASS", after.audit().getResultType());
        assertFalse(compare(before, after, "ALL").comparable()); assertFalse(compare(before, after, "ORG").comparable());
        sql.execute("DROP TABLE " + table()); create("BIGINT", "BIGINT"); insert("(1,1,1,1,1,9,1)");
        assertFalse(compare(before, run(21, true), "ALL").comparable());
        sql.execute("CREATE TEMPORARY TABLE " + table() + "(slot BIGINT,id BIGINT,part BIGINT,third_key INT,fourth_key SMALLINT,org_code BIGINT,amount BIGINT)"
                + " ");
        try {
            insert("(1,1,1,1,1,9,1)"); after = run(22, true);
            assertEquals("PASS", after.audit().getResultType());
            assertFalse(compare(before, after, "ALL").comparable()); assertFalse(compare(before, after, "ORG").comparable());
        } finally { sql.execute("DROP TEMPORARY TABLE " + table()); }
    }

    private String table() { return schema + ".sample"; }
    private void create(String idType, String orgType) throws SQLException {
        sql.execute("CREATE TABLE " + table() + "(slot BIGINT NOT NULL, id " + idType + ", part BIGINT, third_key INT, fourth_key SMALLINT, org_code "
                + orgType + ", amount BIGINT, PRIMARY KEY(slot))");
    }
    private void insert(String values) throws SQLException { sql.execute("REPLACE INTO " + table() + " VALUES" + values); }
    private AuditRuleContext context() {
        var result = new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover()); result.setProductType(ProductTypeEnum.OCEANBASE);
        result.setTableName(table()); result.setColumn("amount"); result.setConfig(Map.of()); return result;
    }
    private AuditKeySnapshot single(int limit) throws SQLException {
        return AuditKeySnapshot.read(connection, generator, context(), "id", limit, 10);
    }
    private AuditKeySnapshot composite() throws SQLException {
        return AuditKeySnapshot.readComposite(connection, generator, context(), List.of("id", "part"), 10, 10);
    }
    private AuditKeySnapshot.Grouped grouped(boolean composite, int keys, int groups) throws SQLException {
        return composite ? AuditKeySnapshot.readGroupedComposite(connection, generator, context(), List.of("id", "part"), "org_code", keys, groups, 10)
                : AuditKeySnapshot.readGrouped(connection, generator, context(), "id", "org_code", keys, groups, 10);
    }
    private record Run(QualityTaskInstanceDO instance, QualityAuditLogDO audit) { }
    private Run run(long id, boolean composite) {
        return run(id, composite, false);
    }
    @Test void actualTaskObjectUsesThePinnedDriverWithoutAClasspathFallback() throws Exception {
        assertThrows(ClassNotFoundException.class, () -> Class.forName(ProductTypeEnum.OCEANBASE.getDriveClassName()));
        create("BIGINT", "BIGINT"); insert("(1,1,1,1,1,9,NULL)");
        var before = run(10, true, true);
        insert("(1,1,1,1,1,9,1)");
        var after = run(20, true, true);
        assertTrue(compare(before, after, "ALL").comparable());
        assertTrue(compare(before, after, "ORG").comparable());
        assertFalse(connection.isClosed(), "task must not close the fixture/caller connection");
    }
    private Run run(long id, boolean composite, boolean throughObject) {
        var task = QualityTaskDO.builder().id(1L).orgField("org_code").build();
        var object = QualityCheckObjectDO.builder().id(3L).datasourceConnId(4L).datasourceType("THIRD_PARTY").tableName(table()).build();
        var rule = QualityRuleDO.builder().id(2L).status(0).strategyKey("NOT_NULL_VALIDATION").checkLevel("FIELD")
                .paramSchema(composite ? "{\"column\":\"amount\",\"pkColumns\":[\"id\",\"part\"]}" : "{\"column\":\"amount\",\"pkColumn\":\"id\"}")
                .collectIssueDetail(0).build();
        var definition = new QualityTaskExecutor.GateDefinition();
        definition.setTask(task); definition.setRules(List.of(rule)); definition.setObjects(List.of(object));
        var instance = QualityTaskInstanceDO.builder().id(id).taskId(1L).instanceStatus("SUCCESS")
                .definitionSnapshot(JsonUtils.toJsonString(definition)).startTime(LocalDateTime.now()).build();
        var executor = QualityRuntimeFixture.executor();
        ReflectionTestUtils.setField(executor, "auditSqlRegistry", new AuditSqlRegistry());
        ReflectionTestUtils.setField(executor, "auditLogMapper", mock(QualityAuditLogMapper.class));
        ReflectionTestUtils.setField(executor, "databaseResolver", resolver);
        QualityAuditLogDO audit;
        if (throughObject) {
            List<QualityAuditLogDO> results = new ArrayList<>();
            QualityRuntimeFixture.executeObject(executor, id, task, route, object, List.of(rule), results);
            assertEquals(1, results.size()); audit = results.getFirst();
        } else {
            audit = QualityRuntimeFixture.executeRule(executor, id, task, connection, route, object, rule);
        }
        assertNotNull(audit); audit.setId(id * 10); instance.setEndTime(LocalDateTime.now());
        assertNotEquals("ERROR", audit.getResultType(), audit.getErrorDetail()); return new Run(instance, audit);
    }
    private QualityRecheckEvidence.Comparison compare(Run before, Run after, String scope) {
        var issue = QualityIssueDataDO.builder().id(30L).taskId(1L).ruleId(2L).objectId(3L).instanceId(before.instance().getId())
                .auditLogId(before.audit().getId()).tableName(table()).checkField("amount").statScope(scope)
                .regionCode("ORG".equals(scope) ? "9" : null).issueCount(1L).build();
        return QualityRecheckEvidence.compare(QualityWorkOrderDO.builder().id(8L).taskId(1L).build(), issue,
                before.instance(), before.audit(), after.instance(), after.audit(), before.instance().getEndTime());
    }
    private static void assertReason(String reason, QualityRecheckEvidence.Comparison result) {
        assertFalse(result.comparable()); assertEquals(reason, result.evidence().get("reasonCode"));
    }
}
