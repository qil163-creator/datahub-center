package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.quality.core.audit.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityAuditLogMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityIssueDataMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnabledIfSystemProperty(named = "datahub.test.starrocks.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
class QualityTextKeyStarrocksTest {
    private Connection connection;
    private Statement sql;
    private String schema;
    private HikariDataSource pool;
    private MasterDatabaseResolver.ConnectionInfo route;
    private MasterDatabaseResolver resolver;

    @BeforeEach void setup() throws Exception {
        var dto = new cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO();
        dto.setId(4L); dto.setProductType("STARROCKS"); dto.setJdbcUrl(System.getProperty("datahub.test.starrocks.url"));
        dto.setUsername("root"); dto.setPassword(""); dto.setUsageType(1); dto.setConfigurationRevision("test-revision");
        var api = mock(cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi.class);
        org.mockito.Mockito.when(api.getDatabaseConnection(4L)).thenReturn(cn.iocoder.yudao.framework.common.pojo.CommonResult.success(dto));
        resolver = new MasterDatabaseResolver();
        ReflectionTestUtils.setField(resolver, "databaseApi", api);
        route = resolver.resolve(4L, cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_CHECK_OBJECT_NOT_EXISTS);
        pool = resolver.createDataSource(route); connection = pool.getConnection();
        sql = connection.createStatement(); sql.setQueryTimeout(20);
        schema = "quality_starrocks_keys_" + UUID.randomUUID().toString().replace("-", "");
        sql.execute("CREATE DATABASE " + schema);
        route.setSchema(schema);
        create();
    }

    @AfterEach void cleanup() throws Exception {
        try { if (sql != null && schema != null) sql.execute("DROP DATABASE IF EXISTS " + schema + " FORCE"); }
        finally { if (sql != null) sql.close(); if (connection != null) connection.close(); if (pool != null) pool.close(); }
    }

    private String table() { return schema + ".sample"; }
    @ParameterizedTest @CsvSource({"VARCHAR(500),false,ALL", "VARCHAR(500),false,ORG", "VARCHAR(500),true,ALL", "VARCHAR(500),true,ORG", "STRING,false,ALL", "STRING,false,ORG", "STRING,true,ALL", "STRING,true,ORG"})
    void textRowsAndInstitutionsPreserveExactIdentity(String keyType, boolean composite, String scope) throws Exception {
        sql.execute("DROP TABLE " + table() + "");
        sql.execute("CREATE TABLE " + table() + "(slot BIGINT NOT NULL,id " + keyType
                + ",part INTEGER,org_code VARCHAR(128),amount BIGINT) PRIMARY KEY(slot) DISTRIBUTED BY HASH(slot) BUCKETS 1 PROPERTIES ('replication_num'='1')");
        sql.execute("INSERT INTO " + table() + " VALUES(1,'A',11,'9',NULL),(2,'a',12,'9',1),(3,'other',13,'09',1)");
        var before = run(10, composite);
        var evidence = JsonUtils.parseObject(before.audit().getReadEvidence(), QualityReadEvidence.class);
        assertTrue(evidence.getKeyScope().getComplete(), evidence.getKeyScope().getReasonCode());
        assertEquals(composite ? "SHA256_SORTED_STARROCKS_TYPED_TUPLE_V1" : "SHA256_SORTED_STARROCKS_UTF8_BYTES_V1",
                evidence.getKeyScope().getAlgorithm());
        assertEquals("STARROCKS_EXACT_UTF8_GROUP_V1", evidence.getOrgScope().getGroupEncoding());
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
        sql.execute("CREATE TABLE " + table() + "(slot BIGINT NOT NULL,id BIGINT,part INTEGER,org_code BIGINT,amount BIGINT) PRIMARY KEY(slot) DISTRIBUTED BY HASH(slot) BUCKETS 1 PROPERTIES ('replication_num'='1')");
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
        sql.execute("CREATE TABLE " + table() + "(slot BIGINT NOT NULL,id " + keyType
                + ",part INTEGER,org_code " + orgType + ",amount BIGINT) PRIMARY KEY(slot) DISTRIBUTED BY HASH(slot) BUCKETS 1 PROPERTIES ('replication_num'='1')");
    }

    @ParameterizedTest @CsvSource({"BIGINT,VARCHAR(100),false", "BIGINT,VARCHAR(100),true",
            "VARCHAR(100),BIGINT,false", "VARCHAR(100),BIGINT,true", "STRING,BIGINT,false", "STRING,BIGINT,true",
            "VARCHAR(100),STRING,false", "VARCHAR(100),STRING,true"})
    void independentlyTypedKeysAndInstitutionsNeverBorrowEachOthersMetadata(String keyType, String orgType, boolean composite) throws Exception {
        createText(keyType, orgType);
        sql.execute("INSERT INTO " + table() + " VALUES(1,'1',1,'9',NULL),(2,'2',2,'9',1),(3,'3',3,'10',1)");
        var before = run(10, composite);
        mutate("amount=1", "slot=1");
        var after = run(20, composite);
        assertTrue(compare(before, after, "ORG").comparable());
        var evidence = JsonUtils.parseObject(after.audit().getReadEvidence(), QualityReadEvidence.class);
        assertEquals("BIGINT".equals(orgType) ? AuditKeySnapshot.STARROCKS_INTEGER_GROUP_ENCODING
                : AuditKeySnapshot.STARROCKS_TEXT_GROUP_ENCODING, evidence.getOrgScope().getGroupEncoding());
        mutate("org_code=CASE WHEN slot=1 THEN '10' ELSE '9' END", "slot IN (1,3)");
        assertReason("ROW_POPULATION_CHANGED", compare(before, run(21, composite), "ORG"));
    }

    private AuditRuleContext textContext() {
        var context = new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover()); context.setProductType(ProductTypeEnum.STARROCKS);
        context.setTableName(table()); context.setColumn("amount"); context.setConfig(Map.of()); return context;
    }

    @Test void malformedNativeUtf8IsNotReplacedOrHashed() throws Exception {
        createText("STRING", "VARCHAR(100)");
        sql.execute("INSERT INTO " + table() + " VALUES(1,unhex('FF'),1,'9',NULL)");
        var generator = new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");
        assertThrows(SQLException.class, () -> AuditKeySnapshot.read(connection, generator, textContext(), "id", 10, 10));
        assertThrows(SQLException.class, () -> AuditKeySnapshot.readComposite(connection, generator, textContext(), List.of("id","part"), 10, 10));
        assertFalse(connection.isClosed());
    }

    @Test void textOuterOrderingSurvivesJdbcRowLimitRepeatedly() throws Exception {
        createText("STRING", "VARCHAR(100)");
        sql.execute("INSERT INTO " + table() + " VALUES(1,'A',1,'A',NULL),(2,'a',2,'a',1),(3,'A ',3,'A',1)");
        var generator = new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");
        String hash = null;
        for (int attempt=0; attempt<30; attempt++) {
            var read = AuditKeySnapshot.read(connection,generator,textContext(),"id",3,10);
            assertTrue(read.complete(),read.reasonCode()); assertTrue(read.sql().endsWith("ASC LIMIT 4"));
            if(hash==null) hash=read.keyHash(); else assertEquals(hash,read.keyHash());
            var groups=AuditKeySnapshot.readGrouped(connection,generator,textContext(),"id","org_code",3,2,10);
            assertTrue(groups.complete()); assertEquals(2,groups.groups().size());
            assertTrue(groups.groups().stream().allMatch(g->g.snapshot().complete()));
            assertTrue(groups.sql().endsWith("ASC LIMIT 4"));
        }
    }

    @Test void exactTextLocationsAreSafeAndDoNotMergeUnicodeOrQuotedInstitutions() throws Exception {
        createText("VARCHAR(500)", "VARCHAR(500)");
        var values = List.of("A", "a", "A ", "0009", "9", "中国😀", "e\u0301", "é", "a\\' OR 1=1 -- ");
        for (int i = 0; i < values.size(); i++) {
            String hex = HexFormat.of().formatHex(values.get(i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            sql.execute("INSERT INTO " + table() + " VALUES(" + i + ",'key" + i + "',1,unhex('" + hex + "'),NULL)");
        }
        var captured = new ArrayList<QualityIssueDataDO>(); run(10, false, captured);
        var orgIssues = captured.stream().filter(issue -> "ORG".equals(issue.getStatScope())).toList();
        assertEquals(values.size(), orgIssues.size());
        for (var issue : orgIssues) {
            assertTrue(issue.getLocationSql().contains("hex("));
            try (var rows = sql.executeQuery(issue.getLocationSql())) {
                assertTrue(rows.next()); assertEquals(issue.getRegionCode(), rows.getString("org_code")); assertFalse(rows.next());
            }
        }
    }

    @Test void byteBudgetsIncludeTupleFramingAndNeverTruncate() throws Exception {
        createText("STRING", "VARCHAR(500)");
        var generator = new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");
        sql.execute("INSERT INTO " + table() + " VALUES(1,repeat('x',235),1,'9',NULL)");
        assertTrue(AuditKeySnapshot.readComposite(connection, generator, textContext(), List.of("id", "part"), 10, 10).complete());
        mutate("id=repeat('x',236)", "slot=1");
        assertEquals("KEY_NULL_OR_TOO_LONG", AuditKeySnapshot.readComposite(connection, generator, textContext(), List.of("id", "part"), 10, 10).reasonCode());
        mutate("id=concat(repeat('中',85),'a')", "slot=1");
        assertTrue(AuditKeySnapshot.read(connection, generator, textContext(), "id", 10, 10).complete());
        for (String invalid : List.of("repeat('x',257)", "NULL")) {
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

    @Test void actualObjectEntryClosesEveryOwnedPool() throws Exception {
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
            assertEquals(AuditKeySnapshot.STARROCKS_TYPED_COMPOSITE_ALGORITHM, evidence.getKeyScope().getAlgorithm());
            assertEquals(AuditKeySnapshot.STARROCKS_TEXT_GROUP_ENCODING, evidence.getOrgScope().getGroupEncoding());
            assertTrue(pools.stream().allMatch(HikariDataSource::isClosed)); assertFalse(connection.isClosed());
            mutate("amount=1", "slot=1");
        }
        assertEquals(2, pools.size());
    }
}
