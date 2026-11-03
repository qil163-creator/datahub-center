package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.YudaoDbSwitchAutoConfiguration;
import cn.iocoder.yudao.framework.quality.core.audit.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityAuditLogMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityIssueDataMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_CHECK_OBJECT_NOT_EXISTS;

@EnabledIfSystemProperty(named = "datahub.test.opengauss.url", matches = "jdbc:opengauss://127[.]0[.]0[.]1:[0-9]+/postgres")
@EnabledIfSystemProperty(named = "datahub.test.opengauss.driver", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DATAHUB_OPENGAUSS_TEST_PASSWORD", matches = ".+")
class QualityIntegerKeyOpengaussTest {
    private Connection connection;
    private Statement sql;
    private String schema;
    private HikariDataSource pool;
    private MasterDatabaseResolver.ConnectionInfo route;
    private MasterDatabaseResolver resolver;

    @BeforeEach void setup() throws Exception {
        schema = "quality_og_" + UUID.randomUUID().toString().replace("-", "");
        Path jar = Path.of(System.getProperty("datahub.test.opengauss.driver")).toRealPath();
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        var dto = new DatabaseConnectionRespDTO(); dto.setId(4L); dto.setProductType("OPENGAUSS"); dto.setSchema(schema);
        dto.setJdbcUrl(System.getProperty("datahub.test.opengauss.url")); dto.setUsername("codex_test"); dto.setPassword(System.getenv("DATAHUB_OPENGAUSS_TEST_PASSWORD"));
        dto.setUsageType(1); dto.setConfigurationRevision("test-revision");
        var api = mock(DatabaseApi.class); when(api.getDatabaseConnection(4L)).thenReturn(success(dto));
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(YudaoDbSwitchAutoConfiguration.class))
                .withBean(DatabaseApi.class, () -> api).withBean(MasterDatabaseResolver.class)
                .withPropertyValues("yudao.dbswitch.drivers.OPENGAUSS.path=" + jar, "yudao.dbswitch.drivers.OPENGAUSS.sha256=" + sha256)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    resolver = context.getBean(MasterDatabaseResolver.class);
                    route = resolver.resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS);
                    assertNotEquals(dto.getConfigurationRevision(), route.getConfigurationRevision());
                    pool = resolver.createDataSource(route); connection = pool.getConnection();
                });
        sql = connection.createStatement(); sql.setQueryTimeout(15);
        sql.execute("CREATE SCHEMA " + schema);
        create();
    }
    @AfterEach void cleanup() throws Exception {
        try { if (sql != null && schema != null) sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
        finally { if (sql != null) sql.close(); if (connection != null) connection.close(); if (pool != null) pool.close(); }
    }

    @ParameterizedTest @CsvSource({"false,ALL", "false,ORG", "true,ALL", "true,ORG"})
    void nativeReadAndJsonAllowRepairButRejectSameCountReplacements(boolean composite, String scope) throws Exception {
        sql.execute("INSERT INTO " + table() + " VALUES(1,9007199254740992,11,9,NULL),(2,9007199254740993,12,9,1),"
                + "(3,3,13,10," + ("ORG".equals(scope) ? "NULL" : "1") + ")");
        var before = run(10, composite);
        mutate("amount=1", "slot=1");
        var repaired = run(20, composite);
        var accepted = compare(before, repaired, scope);
        assertTrue(accepted.comparable(), accepted.reason()); assertEquals("PASS", accepted.resultType());
        var evidence = JsonUtils.parseObject(repaired.audit().getReadEvidence(), QualityReadEvidence.class);
        assertEquals("OPENGAUSS", evidence.getProductType()); assertNotNull(evidence.getRelationFingerprint());
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
        sql.execute("DROP TABLE " + table()); create();
        sql.execute("INSERT INTO " + table() + " VALUES(1,9007199254740992,11,9,1),(2,9007199254740993,12,9,1),(3,3,13,10,1)");
        assertFalse(compare(before, run(24, composite), scope).comparable());
    }

    @Test void globalBudgetsDuplicatesAndNullNeverBecomePartialProof() throws Exception {
        sql.execute("INSERT INTO " + table() + " VALUES(1,-9223372036854775808,11,9,NULL),(2,9223372036854775807,12,10,1)");
        var generator = new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");
        var context = new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover()); context.setProductType(ProductTypeEnum.OPENGAUSS);
        context.setTableName(table()); context.setColumn("amount"); context.setConfig(Map.of());
        var tuple = AuditKeySnapshot.readComposite(connection, generator, context, List.of("id", "part"), 2, 10);
        assertTrue(tuple.complete(), tuple.reasonCode()); assertEquals(AuditKeySnapshot.OPENGAUSS_COMPOSITE_ALGORITHM, tuple.algorithm());
        assertEquals(tuple, JsonUtils.parseObject(JsonUtils.toJsonString(tuple), AuditKeySnapshot.class));
        assertEquals("KEY_LIMIT_EXCEEDED", AuditKeySnapshot.read(connection, generator, context, "id", 1, 10).reasonCode());
        assertEquals("GROUP_LIMIT_EXCEEDED", AuditKeySnapshot.readGroupedComposite(connection, generator, context, List.of("id", "part"), "org_code", 2, 1, 10).reasonCode());
        sql.execute("INSERT INTO " + table() + " VALUES(3,-9223372036854775808,11,9,1)");
        assertEquals("KEY_NOT_UNIQUE_OR_NULL", AuditKeySnapshot.readComposite(connection, generator, context, List.of("id", "part"), 10, 10).reasonCode());
        mutate("id=NULL", "slot=3");
        assertFalse(AuditKeySnapshot.readComposite(connection, generator, context, List.of("id", "part"), 10, 10).complete());
    }

    @Test void nativeObjectProofDoesNotQualifyUnverifiedRuleSemantics() throws Exception {
        sql.execute("INSERT INTO " + table() + " VALUES(1,1,1,9,NULL)");
        var before = run(10, false); mutate("amount=0", "slot=1");
        var after = run(20, false);
        assertEquals("PASS", after.audit().getResultType()); // Numeric zero is not an empty string.
        assertTrue(compare(before, after, "ALL").comparable());
        for (var run : List.of(before, after)) {
            var definition = JsonUtils.parseObject(run.instance().getDefinitionSnapshot(), QualityTaskExecutor.GateDefinition.class);
            definition.getRules().getFirst().setStrategyKey("LENGTH_VALIDATION");
            run.instance().setDefinitionSnapshot(JsonUtils.toJsonString(definition));
        }
        assertReason("RULE_DIALECT_UNVERIFIED", compare(before, after, "ALL"));
    }

    @Test void rowPolicyAdditionCannotAutoAcceptEvenWhenRemainingRowsPass() throws Exception {
        sql.execute("INSERT INTO " + table() + " VALUES(1,1,1,9,NULL)");
        var before = run(10, false);
        mutate("amount=1", "slot=1");
        sql.execute("CREATE ROW LEVEL SECURITY POLICY filtered ON " + table() + " USING (id>0)");
        try {
            var after = run(20, false);
            assertEquals("PASS", after.audit().getResultType());
            var evidence = JsonUtils.parseObject(after.audit().getReadEvidence(), QualityReadEvidence.class);
            assertNull(evidence.getRelationFingerprint());
            assertFalse(compare(before, after, "ALL").comparable());
            assertFalse(compare(before, after, "ORG").comparable());
        } finally { sql.execute("DROP ROW LEVEL SECURITY POLICY filtered ON " + table()); }
    }

    private String table() { return schema + ".sample"; }
    @ParameterizedTest @CsvSource({"VARCHAR(500),false,ALL", "VARCHAR(500),false,ORG", "VARCHAR(500),true,ALL", "VARCHAR(500),true,ORG", "TEXT,false,ALL", "TEXT,false,ORG", "TEXT,true,ALL", "TEXT,true,ORG"})
    void textRowsAndInstitutionsPreserveExactIdentity(String keyType, boolean composite, String scope) throws Exception {
        sql.execute("DROP TABLE " + table() + "");
        sql.execute("CREATE TABLE " + table() + "(slot BIGINT,id " + keyType
                + ",part INTEGER,org_code VARCHAR(128),amount BIGINT)");
        sql.execute("INSERT INTO " + table() + " VALUES(1,'A',11,'9',NULL),(2,'a',12,'9',1),(3,'other',13,'09',1)");
        var before = run(10, composite);
        var evidence = JsonUtils.parseObject(before.audit().getReadEvidence(), QualityReadEvidence.class);
        assertTrue(evidence.getKeyScope().getComplete(), evidence.getKeyScope().getReasonCode());
        assertEquals(composite ? "SHA256_SORTED_OPENGAUSS_TYPED_TUPLE_V1" : "SHA256_SORTED_OPENGAUSS_UTF8_BYTES_V1",
                evidence.getKeyScope().getAlgorithm());
        assertEquals("OPENGAUSS_EXACT_UTF8_GROUP_V1", evidence.getOrgScope().getGroupEncoding());
        mutate("amount=1", "slot=1");
        assertTrue(compare(before, run(20, composite), scope).comparable());
        mutate("id='replacement'", "slot=1");
        assertReason("ROW_POPULATION_CHANGED", compare(before, run(21, composite), scope));
        mutate("id='A'", "slot=1");
        if (composite) {
            mutate("part=CASE WHEN slot=1 THEN 12 ELSE 11 END", "slot IN (1,2)");
            assertReason("ROW_POPULATION_CHANGED", compare(before, run(22, true), scope));
            mutate("part=CASE WHEN slot=1 THEN 11 ELSE 12 END", "slot IN (1,2)");
        }
        if ("ORG".equals(scope)) {
            mutate("org_code=CASE WHEN slot=1 THEN '09' ELSE '9' END", "slot IN (1,3)");
            assertReason("ROW_POPULATION_CHANGED", compare(before, run(23, composite), scope));
            mutate("org_code=CASE WHEN slot=1 THEN '9' ELSE '09' END", "slot IN (1,3)");
        }
        sql.execute("INSERT INTO " + table() + " VALUES(4,'a',12,'9',1)");
        assertReason("ROW_COVERAGE_UNPROVEN", compare(before, run(24, composite), scope));
    }
    private void create() throws SQLException {
        sql.execute("CREATE TABLE " + table() + "(slot BIGINT, id BIGINT, part INTEGER, org_code BIGINT, amount BIGINT)");
    }
    private void mutate(String assignment, String where) throws SQLException {
        sql.execute("UPDATE " + table() + " SET " + assignment + " WHERE " + where);
    }
    private record Run(QualityTaskInstanceDO instance, QualityAuditLogDO audit) { }
    private Run run(long id, boolean composite) {
        return run(id, composite, null);
    }
    private Run run(long id, boolean composite, List<QualityIssueDataDO> captured) {
        var task = QualityTaskDO.builder().id(1L).orgField("org_code").build();
        var object = QualityCheckObjectDO.builder().id(3L).datasourceConnId(4L).datasourceType("THIRD_PARTY").tableName(table()).build();
        var rule = QualityRuleDO.builder().id(2L).status(0).strategyKey("NOT_NULL_VALIDATION").checkLevel("FIELD")
                .paramSchema(composite ? "{\"column\":\"amount\",\"pkColumns\":[\"id\",\"part\"]}" : "{\"column\":\"amount\",\"pkColumn\":\"id\"}")
                .collectIssueDetail(captured == null ? 0 : 1).build();
        var definition = new QualityTaskExecutor.GateDefinition();
        definition.setTask(task); definition.setRules(List.of(rule)); definition.setObjects(List.of(object));
        var instance = QualityTaskInstanceDO.builder().id(id).taskId(1L).instanceStatus("SUCCESS")
                .definitionSnapshot(JsonUtils.toJsonString(definition)).startTime(LocalDateTime.now()).build();
        var executor = QualityRuntimeFixture.executor();
        ReflectionTestUtils.setField(executor, "auditSqlRegistry", new AuditSqlRegistry());
        ReflectionTestUtils.setField(executor, "auditLogMapper", mock(QualityAuditLogMapper.class));
        if (captured != null) {
            var mapper = mock(QualityIssueDataMapper.class);
            org.mockito.Mockito.doAnswer(call -> { captured.add(call.getArgument(0)); return 1; })
                    .when(mapper).insert(org.mockito.ArgumentMatchers.any(QualityIssueDataDO.class));
            ReflectionTestUtils.setField(executor, "issueDataMapper", mapper);
        }
        QualityAuditLogDO audit = QualityRuntimeFixture.executeRule(executor, id, task, connection, route, object, rule);
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

    private void createText(String keyType, String orgType) throws SQLException {
        sql.execute("DROP TABLE " + table() + "");
        sql.execute("CREATE TABLE " + table() + "(slot BIGINT,id " + keyType
                + ",part INTEGER,org_code " + orgType + ",amount BIGINT)");
    }

    @ParameterizedTest @CsvSource({"BIGINT,VARCHAR(100),false", "BIGINT,VARCHAR(100),true",
            "VARCHAR(100),BIGINT,false", "VARCHAR(100),BIGINT,true", "TEXT,BIGINT,false", "TEXT,BIGINT,true",
            "VARCHAR(100),TEXT,false", "VARCHAR(100),TEXT,true"})
    void independentlyTypedKeysAndInstitutionsNeverBorrowEachOthersMetadata(String keyType, String orgType, boolean composite) throws Exception {
        createText(keyType, orgType);
        sql.execute("INSERT INTO " + table() + " VALUES(1,'1',1,'9',NULL),(2,'2',2,'9',1),(3,'3',3,'10',1)");
        var before = run(10, composite);
        mutate("amount=1", "slot=1");
        var after = run(20, composite);
        assertTrue(compare(before, after, "ORG").comparable());
        var evidence = JsonUtils.parseObject(after.audit().getReadEvidence(), QualityReadEvidence.class);
        assertEquals("BIGINT".equals(orgType) ? AuditKeySnapshot.OPENGAUSS_INTEGER_GROUP_ENCODING
                : AuditKeySnapshot.OPENGAUSS_TEXT_GROUP_ENCODING, evidence.getOrgScope().getGroupEncoding());
        mutate("org_code=CASE WHEN slot=1 THEN '10' ELSE '9' END", "slot IN (1,3)");
        assertReason("ROW_POPULATION_CHANGED", compare(before, run(21, composite), "ORG"));
    }

    private AuditRuleContext textContext() {
        var context = new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover()); context.setProductType(ProductTypeEnum.OPENGAUSS);
        context.setTableName(table()); context.setColumn("amount"); context.setConfig(Map.of()); return context;
    }

    @Test void exactTextLocationsAreSafeAndDoNotMergeUnicodeOrQuotedInstitutions() throws Exception {
        createText("VARCHAR(500)", "VARCHAR(500)");
        var values = List.of("A", "a", "A ", "0009", "9", "中国😀", "e\u0301", "é", "a\\' OR 1=1 -- ");
        for (int i = 0; i < values.size(); i++) {
            String hex = HexFormat.of().formatHex(values.get(i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            sql.execute("INSERT INTO " + table() + " VALUES(" + i + ",'key" + i + "',1,pg_catalog.convert_from(pg_catalog.decode('" + hex + "','hex'),'UTF8'),NULL)");
        }
        var captured = new ArrayList<QualityIssueDataDO>(); run(10, false, captured);
        var orgIssues = captured.stream().filter(issue -> "ORG".equals(issue.getStatScope())).toList();
        assertEquals(values.size(), orgIssues.size());
        for (var issue : orgIssues) {
            assertTrue(issue.getLocationSql().contains("pg_catalog.decode("));
            try (var rows = sql.executeQuery(issue.getLocationSql())) {
                assertTrue(rows.next()); assertEquals(issue.getRegionCode(), rows.getString("org_code")); assertFalse(rows.next());
            }
        }
    }

    @Test void byteBudgetsIncludeTupleFramingAndEmptyKeysCannotProveCoverage() throws Exception {
        createText("TEXT", "VARCHAR(500)");
        var generator = new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");
        sql.execute("INSERT INTO " + table() + " VALUES(1,repeat('x',235),1,'9',NULL)");
        assertTrue(AuditKeySnapshot.readComposite(connection, generator, textContext(), List.of("id", "part"), 10, 10).complete());
        mutate("id=repeat('x',236)", "slot=1");
        assertEquals("KEY_NULL_OR_TOO_LONG", AuditKeySnapshot.readComposite(connection, generator, textContext(), List.of("id", "part"), 10, 10).reasonCode());
        mutate("id=concat(repeat('中',85),'a')", "slot=1");
        assertTrue(AuditKeySnapshot.read(connection, generator, textContext(), "id", 10, 10).complete());
        for (String invalid : List.of("repeat('x',257)", "''", "NULL")) {
            mutate("id=" + invalid, "slot=1");
            var read = AuditKeySnapshot.read(connection, generator, textContext(), "id", 10, 10);
            assertFalse(read.complete()); assertNull(read.keyHash()); assertEquals(1, read.total());
        }
        mutate("id='A'", "slot=1");
        sql.execute("INSERT INTO " + table() + " VALUES(2,'a',1,'9',1),(3,'A ',1,'09',1)");
        assertEquals("KEY_LIMIT_EXCEEDED", AuditKeySnapshot.read(connection, generator, textContext(), "id", 2, 10).reasonCode());
        assertEquals("GROUP_LIMIT_EXCEEDED", AuditKeySnapshot.readGrouped(connection, generator, textContext(), "id", "org_code", 10, 1, 10).reasonCode());
        var four = List.of("id", "part", "slot", "org_code");
        var read = AuditKeySnapshot.readComposite(connection, generator, textContext(), four, 3, 10);
        assertTrue(read.complete());
        assertNotEquals(read.keyHash(), AuditKeySnapshot.readComposite(connection, generator, textContext(), four.reversed(), 3, 10).keyHash());
        var overflow = AuditKeySnapshot.readGroupedComposite(connection, generator, textContext(), four, "org_code", 2, 10, 10);
        assertFalse(overflow.complete()); assertTrue(overflow.groups().isEmpty());
    }

    @Test void invalidTextInstitutionsNeverLeakPartialCoverage() throws Exception {
        createText("VARCHAR(500)", "VARCHAR(500)");
        sql.execute("INSERT INTO " + table() + " VALUES(1,'A',1,'9',NULL),(2,'a',1,'other',1)");
        var generator = new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");
        for (String invalid : List.of("repeat('x',65)", "''", "' '", "NULL")) {
            mutate("org_code=" + invalid, "slot=1");
            for (boolean tuple : new boolean[]{false, true}) {
                var grouped = tuple ? AuditKeySnapshot.readGroupedComposite(connection, generator, textContext(), List.of("id", "part"), "org_code", 10, 10, 10)
                        : AuditKeySnapshot.readGrouped(connection, generator, textContext(), "id", "org_code", 10, 10, 10);
                assertFalse(grouped.complete()); assertTrue(grouped.groups().isEmpty());
            }
        }
    }

    @Test void actualObjectEntryUsesPinnedDriverAndClosesEveryOwnedPool() throws Exception {
        createText("VARCHAR(500)", "VARCHAR(500)");
        sql.execute("INSERT INTO " + table() + " VALUES(1,'A',1,'A',NULL),(2,'a',1,'a',1)");
        var executor = QualityRuntimeFixture.executor(); var trackedResolver = org.mockito.Mockito.spy(resolver);
        var pools = new ArrayList<HikariDataSource>();
        org.mockito.Mockito.doAnswer(call -> { var opened = (HikariDataSource) call.callRealMethod(); pools.add(opened); return opened; })
                .when(trackedResolver).createDataSource(route);
        ReflectionTestUtils.setField(executor, "databaseResolver", trackedResolver);
        ReflectionTestUtils.setField(executor, "auditSqlRegistry", new AuditSqlRegistry());
        ReflectionTestUtils.setField(executor, "auditLogMapper", mock(QualityAuditLogMapper.class));
        var task = QualityTaskDO.builder().id(1L).orgField("org_code").build();
        var object = QualityCheckObjectDO.builder().id(3L).datasourceConnId(4L).tableName(table()).build();
        var rule = QualityRuleDO.builder().id(2L).status(0).strategyKey("NOT_NULL_VALIDATION").checkLevel("FIELD")
                .paramSchema("{\"column\":\"amount\",\"pkColumns\":[\"id\",\"part\"]}").collectIssueDetail(0).build();
        for (String expected : List.of("FAIL", "PASS")) {
            var results = new ArrayList<QualityAuditLogDO>();
            QualityRuntimeFixture.executeObject(executor, 30L, task, route, object, List.of(rule), results);
            assertEquals(1, results.size()); assertEquals(expected, results.getFirst().getResultType(), results.getFirst().getErrorDetail());
            var evidence = JsonUtils.parseObject(results.getFirst().getReadEvidence(), QualityReadEvidence.class);
            assertNotNull(evidence.getRelationFingerprint()); assertTrue(evidence.getKeyScope().getComplete());
            assertEquals(AuditKeySnapshot.OPENGAUSS_TYPED_COMPOSITE_ALGORITHM, evidence.getKeyScope().getAlgorithm());
            assertEquals(AuditKeySnapshot.OPENGAUSS_TEXT_GROUP_ENCODING, evidence.getOrgScope().getGroupEncoding());
            assertTrue(pools.stream().allMatch(HikariDataSource::isClosed)); assertFalse(connection.isClosed());
            mutate("amount=1", "slot=1");
        }
        assertEquals(2, pools.size());
    }
}
