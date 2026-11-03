package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.YudaoDbSwitchAutoConfiguration;
import cn.iocoder.yudao.framework.quality.core.audit.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityAuditLogMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** UXsinoDB 2.1.0.3 ordinary heap reads. Does not use a business database. */
@EnabledIfSystemProperty(named = "datahub.test.uxdb.url", matches = "jdbc:uxdb://127[.]0[.]0[.]1:[0-9]+/uxdb")
@EnabledIfSystemProperty(named = "datahub.test.uxdb.driver", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DATAHUB_UXDB_TEST_PASSWORD", matches = ".+")
class QualityIntegerKeyUxdbTest {
    private Connection connection;
    private com.zaxxer.hikari.HikariDataSource pool;
    private MasterDatabaseResolver.ConnectionInfo route;
    private MasterDatabaseResolver resolver;
    private Statement sql;
    private String schema;
    private final AuditSqlGenerator generator = new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");

    @BeforeEach void setup() throws Exception {
        Path jar = Path.of(System.getProperty("datahub.test.uxdb.driver")).toRealPath();
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        var dto = new cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO();
        dto.setId(4L); dto.setProductType("UXDB");
        dto.setJdbcUrl(System.getProperty("datahub.test.uxdb.url"));
        dto.setUsername("uxdb"); dto.setPassword(System.getenv("DATAHUB_UXDB_TEST_PASSWORD"));
        dto.setUsageType(1); dto.setConfigurationRevision("test-revision");
        var api = mock(cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi.class);
        org.mockito.Mockito.when(api.getDatabaseConnection(4L))
                .thenReturn(cn.iocoder.yudao.framework.common.pojo.CommonResult.success(dto));
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(YudaoDbSwitchAutoConfiguration.class))
                .withBean(cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi.class, () -> api)
                .withBean(MasterDatabaseResolver.class)
                .withPropertyValues("yudao.dbswitch.drivers.UXDB.path=" + jar,
                        "yudao.dbswitch.drivers.UXDB.sha256=" + sha256)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    resolver = context.getBean(MasterDatabaseResolver.class);
                    route = resolver.resolve(4L, cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_CHECK_OBJECT_NOT_EXISTS);
                    pool = resolver.createDataSource(route);
                    connection = pool.getConnection();
                });
        sql = connection.createStatement(); sql.setQueryTimeout(30);
        schema = "q11u_" + UUID.randomUUID().toString().replace("-", "");
        sql.execute("CREATE SCHEMA " + schema);
        route.setSchema(schema);
    }

    @AfterEach void cleanup() throws Exception {
        try { if (sql != null && schema != null) sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
        finally { if (sql != null) sql.close(); if (connection != null) connection.close(); if (pool != null) pool.close(); }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"SMALLINT|-32768|32767|5", "INTEGER|-2147483648|2147483647|4",
            "BIGINT|-9223372036854775808|9223372036854775807|-5"})
    void nativeIntegersKeepBoundariesAndShareOneGlobalScanBudget(String type, String low, String high, int jdbcType) throws Exception {
        create(type, type);
        insert("(1," + low + ",1,1,1," + low + ",NULL),(2," + high + ",2,2,2," + high + ",1)");
        var snapshot = single(2);
        assertTrue(snapshot.complete(), snapshot.reasonCode());
        assertEquals(jdbcType, snapshot.jdbcType());
        assertEquals(2, snapshot.total()); assertEquals(1, snapshot.fail());
        assertEquals(snapshot, JsonUtils.parseObject(JsonUtils.toJsonString(snapshot), AuditKeySnapshot.class));
        var grouped = grouped(false, 2, 2);
        assertTrue(grouped.complete(), grouped.reasonCode());
        assertEquals(AuditKeySnapshot.UXDB_INTEGER_GROUP_ENCODING, grouped.groupEncoding());
        assertEquals(List.of(low, high), grouped.groups().stream().map(AuditKeySnapshot.Group::key).toList());
        assertTrue(grouped.groups().stream().allMatch(group -> group.snapshot().complete()));
        assertEquals("KEY_LIMIT_EXCEEDED", single(1).reasonCode());
        for (boolean composite : new boolean[]{false, true}) {
            assertEquals("KEY_LIMIT_EXCEEDED", grouped(composite, 1, 2).reasonCode());
            assertEquals("GROUP_LIMIT_EXCEEDED", grouped(composite, 2, 1).reasonCode());
        }
    }

    @ParameterizedTest @CsvSource({"false,ALL", "false,ORG", "true,ALL", "true,ORG"})
    void repairedRowsPassButSameCountReplacementAndInstitutionMovesDoNot(boolean composite, String scope) throws Exception {
        create("BIGINT", "BIGINT");
        insert("(1,9007199254740992,11,1,1,9,NULL),(2,9007199254740993,12,1,1,9,1),(3,3,13,1,1,10,1)");
        var before = run(10, composite);
        mutate("amount=1", "slot=1");
        var repaired = run(20, composite);
        var accepted = compare(before, repaired, scope);
        assertTrue(accepted.comparable(), accepted.reason());
        assertEquals("PASS", accepted.resultType());
        var evidence = JsonUtils.parseObject(repaired.audit().getReadEvidence(), QualityReadEvidence.class);
        assertEquals("UXDB", evidence.getProductType());
        assertNotNull(evidence.getRelationFingerprint());
        mutate("id=9007199254740994", "slot=1");
        assertReason("ROW_POPULATION_CHANGED", compare(before, run(21, composite), scope));
        mutate("id=9007199254740992", "slot=1");
        if (composite) {
            mutate("part=CASE WHEN slot=1 THEN 12 ELSE 11 END", "slot IN (1,2)");
            assertReason("ROW_POPULATION_CHANGED", compare(before, run(22, true), scope));
            mutate("part=CASE WHEN slot=1 THEN 11 ELSE 12 END", "slot IN (1,2)");
        }
        if ("ORG".equals(scope)) {
            mutate("org_code=CASE WHEN slot=1 THEN 10 ELSE 9 END, amount=1", "slot IN (1,3)");
            assertReason("ROW_POPULATION_CHANGED", compare(before, run(23, composite), scope));
        }
    }

    @ParameterizedTest @ValueSource(ints = {2, 3, 4})
    void compositeFramesBindColumnOrderAndTypes(int size) throws Exception {
        create("BIGINT", "BIGINT");
        insert("(1,-9223372036854775808,11,21,31,9007199254740992,NULL),(2,9223372036854775807,12,22,32,9007199254740993,1)");
        var columns = List.of("id", "part", "third_key", "fourth_key").subList(0, size);
        var snapshot = AuditKeySnapshot.readComposite(connection, generator, context(), columns, 2, 15);
        assertTrue(snapshot.complete(), snapshot.reasonCode());
        assertEquals(AuditKeySnapshot.UXDB_COMPOSITE_ALGORITHM, snapshot.algorithm());
        assertEquals(snapshot, JsonUtils.parseObject(JsonUtils.toJsonString(snapshot), AuditKeySnapshot.class));
        assertNotEquals(snapshot.keyHash(), AuditKeySnapshot.readComposite(connection, generator, context(), columns.reversed(), 2, 15).keyHash());
        var groups = AuditKeySnapshot.readGroupedComposite(connection, generator, context(), columns, "org_code", 2, 2, 15);
        assertTrue(groups.complete(), groups.reasonCode());
        assertEquals(Set.of("9007199254740992", "9007199254740993"),
                new HashSet<>(groups.groups().stream().map(AuditKeySnapshot.Group::key).toList()));
        assertEquals(AuditKeySnapshot.UXDB_INTEGER_GROUP_ENCODING, groups.groupEncoding());
    }

    @ParameterizedTest @CsvSource(delimiter = '|', value = {
            "VARCHAR(64)|'9'", "CHAR(64)|'9'", "TEXT|'9'", "NUMERIC(20,0)|9", "FLOAT8|9", "BOOLEAN|true"})
    void convertedNonIntegerKeysAndGroupsNeverBecomeQualified(String type, String value) throws Exception {
        create(type, type); insert("(1," + value + ",1,1,1," + value + ",NULL)");
        assertEquals("KEY_TYPE_UNSUPPORTED", single(10).reasonCode());
        assertEquals("KEY_TYPE_UNSUPPORTED", composite().reasonCode());
        for (boolean tuple : new boolean[]{false, true}) {
            var groups = grouped(tuple, 10, 10);
            assertEquals("GROUP_KEY_UNSUPPORTED", groups.reasonCode());
            assertTrue(groups.groups().isEmpty());
        }
        var before = run(10, true);
        mutate("amount=1", "slot=1");
        var after = run(20, true);
        assertEquals("PASS", after.audit().getResultType());
        assertFalse(compare(before, after, "ALL").comparable());
        assertFalse(compare(before, after, "ORG").comparable());
    }

    @Test void nullAndDuplicateRowsKeepCountsWithoutUsableCoverage() throws Exception {
        create("BIGINT", "BIGINT"); insert("(1,1,1,1,1,9,NULL),(2,2,2,1,1,9,1)");
        var before = run(10, true);
        insert("(3,1,1,1,1,9,1)");
        assertEquals("KEY_NOT_UNIQUE_OR_NULL", composite().reasonCode());
        assertEquals("KEY_NOT_UNIQUE_OR_NULL", single(10).reasonCode());
        assertReason("ROW_COVERAGE_UNPROVEN", compare(before, run(20, true), "ALL"));
        mutate("id=NULL", "slot=2");
        assertFalse(single(10).complete());
        assertFalse(composite().complete());
        mutate("org_code=NULL", "slot=1");
        assertEquals("GROUP_KEY_UNSUPPORTED", grouped(false, 10, 10).reasonCode());
        assertEquals("GROUP_KEY_UNSUPPORTED", grouped(true, 10, 10).reasonCode());
    }

    @Test void foreignCodecsTruncateAndRowPolicyCannotReuseProof() throws Exception {
        create("BIGINT", "BIGINT"); insert("(1,1,1,1,1,9,NULL)");
        var before = run(10, true);
        mutate("amount=1", "slot=1");
        var after = run(20, true);
        assertTrue(compare(before, after, "ALL").comparable());
        var evidence = JsonUtils.parseObject(after.audit().getReadEvidence(), QualityReadEvidence.class);
        evidence.getOrgScope().setGroupEncoding(AuditKeySnapshot.MYSQL_INTEGER_GROUP_ENCODING);
        after.audit().setReadEvidence(JsonUtils.toJsonString(evidence));
        assertReason("ORG_EVIDENCE_INCOMPLETE", compare(before, after, "ORG"));
        sql.execute("TRUNCATE " + table());
        insert("(1,1,1,1,1,9,1)");
        var rebuilt = run(21, true);
        assertEquals("PASS", rebuilt.audit().getResultType());
        assertFalse(compare(before, rebuilt, "ALL").comparable());
        sql.execute("ALTER TABLE " + table() + " ENABLE ROW LEVEL SECURITY");
        sql.execute("CREATE POLICY filter_rows ON " + table() + " USING (id>0)");
        try {
            var hidden = run(22, true);
            var hiddenEvidence = JsonUtils.parseObject(hidden.audit().getReadEvidence(), QualityReadEvidence.class);
            assertEquals("RELATION_UNPROVEN", hiddenEvidence.getCoverage());
            assertNull(hiddenEvidence.getRelationFingerprint());
            assertFalse(compare(before, hidden, "ALL").comparable());
        } finally {
            sql.execute("DROP POLICY filter_rows ON " + table());
            sql.execute("ALTER TABLE " + table() + " DISABLE ROW LEVEL SECURITY");
        }
    }

    @Test void numericZeroIsNotEmptyAndUnverifiedRulesStayUnqualified() throws Exception {
        create("BIGINT", "BIGINT"); insert("(1,1,1,1,1,9,NULL),(2,2,2,2,2,10,0)");
        var before = run(10, false);
        mutate("amount=0", "slot=1");
        var after = run(20, false);
        assertEquals("PASS", after.audit().getResultType());
        assertTrue(compare(before, after, "ALL").comparable());
        for (var run : List.of(before, after)) {
            var definition = JsonUtils.parseObject(run.instance().getDefinitionSnapshot(), QualityTaskExecutor.GateDefinition.class);
            definition.getRules().getFirst().setStrategyKey("LENGTH_VALIDATION");
            run.instance().setDefinitionSnapshot(JsonUtils.toJsonString(definition));
        }
        assertReason("RULE_DIALECT_UNVERIFIED", compare(before, after, "ALL"));
    }

    private String table() { return schema + ".sample"; }
    private void create(String idType, String orgType) throws SQLException {
        sql.execute("DROP TABLE IF EXISTS " + table());
        sql.execute("CREATE TABLE " + table() + "(slot BIGINT, id " + idType + ", part BIGINT, third_key INT, fourth_key SMALLINT, org_code "
                + orgType + ", amount BIGINT)");
    }
    private void insert(String values) throws SQLException { sql.execute("INSERT INTO " + table() + " VALUES" + values); }
    private void mutate(String assignment, String where) throws SQLException {
        sql.execute("UPDATE " + table() + " SET " + assignment + " WHERE " + where);
    }
    private AuditRuleContext context() {
        var result = new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover()); result.setProductType(ProductTypeEnum.UXDB);
        result.setTableName(table()); result.setColumn("amount"); result.setConfig(Map.of()); return result;
    }
    private AuditKeySnapshot single(int limit) throws SQLException {
        return AuditKeySnapshot.read(connection, generator, context(), "id", limit, 15);
    }
    private AuditKeySnapshot composite() throws SQLException {
        return AuditKeySnapshot.readComposite(connection, generator, context(), List.of("id", "part"), 10, 15);
    }
    private AuditKeySnapshot.Grouped grouped(boolean composite, int keys, int groups) throws SQLException {
        return composite
                ? AuditKeySnapshot.readGroupedComposite(connection, generator, context(), List.of("id", "part"), "org_code", keys, groups, 15)
                : AuditKeySnapshot.readGrouped(connection, generator, context(), "id", "org_code", keys, groups, 15);
    }
    private record Run(QualityTaskInstanceDO instance, QualityAuditLogDO audit) { }
    private Run run(long id, boolean composite) {
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
        QualityAuditLogDO audit = QualityRuntimeFixture.executeRule(executor, id, task, connection, route, object, rule);
        assertNotNull(audit); audit.setId(id * 10); instance.setEndTime(LocalDateTime.now());
        assertNotEquals("ERROR", audit.getResultType(), audit.getErrorDetail());
        return new Run(instance, audit);
    }
    private QualityRecheckEvidence.Comparison compare(Run before, Run after, String scope) {
        var issue = QualityIssueDataDO.builder().id(30L).taskId(1L).ruleId(2L).objectId(3L).instanceId(before.instance().getId())
                .auditLogId(before.audit().getId()).tableName(table()).checkField("amount").statScope(scope)
                .regionCode("ORG".equals(scope) ? "9" : null).issueCount(1L).build();
        return QualityRecheckEvidence.compare(QualityWorkOrderDO.builder().id(8L).taskId(1L).build(), issue,
                before.instance(), before.audit(), after.instance(), after.audit(), before.instance().getEndTime());
    }
    private static void assertReason(String reason, QualityRecheckEvidence.Comparison result) {
        assertFalse(result.comparable());
        assertEquals(reason, result.evidence().get("reasonCode"));
    }
}
