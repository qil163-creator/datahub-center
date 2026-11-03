package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot;
import cn.iocoder.yudao.framework.quality.core.audit.AuditRuleContext;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityAuditLogMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnabledIfSystemProperty(named = "datahub.test.tidb.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:.*")
class QualityCompositeKeyTidbTest {
    private Connection connection;
    private Statement sql;
    private String schema;

    @BeforeEach void setup() throws Exception {
        connection = DriverManager.getConnection(System.getProperty("datahub.test.tidb.url"),
                System.getProperty("datahub.test.tidb.user", "root"), "");
        sql = connection.createStatement();
        schema = "quality_tuple_" + UUID.randomUUID().toString().replace("-", "");
        sql.execute("CREATE DATABASE " + schema);
    }

    @AfterEach void cleanup() throws Exception {
        try {
            if (sql != null && schema != null) sql.execute("DROP DATABASE IF EXISTS " + schema);
        } finally {
            if (sql != null) sql.close();
            if (connection != null) connection.close();
        }
    }

    @ParameterizedTest
    @CsvSource({"BIGINT,ALL", "BIGINT,ORG", "VARCHAR(64),ALL", "VARCHAR(64),ORG", "TEXT,ALL", "TEXT,ORG"})
    void tupleProofCannotBeReplacedByIndependentColumnSets(String orgType, String scope) throws Exception {
        sql.execute("CREATE TABLE " + table() + "(part BIGINT, id VARCHAR(500) CHARACTER SET utf8mb4 "
                + "COLLATE utf8mb4_general_ci, org_code " + orgType + ", amount BIGINT) ENGINE=InnoDB");
        sql.execute("INSERT INTO " + table() + " VALUES(9007199254740992,'A',9,NULL),"
                + "(9007199254740993,'a',9,10),(3,'other',10,10)");
        var snapshot = AuditKeySnapshot.readComposite(connection, new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION"),
                context(), List.of("part", "id"), 10, 5);
        assertTrue(snapshot.complete(), snapshot.reasonCode());
        assertEquals("SHA256_SORTED_TIDB_TYPED_TUPLE_V1", snapshot.algorithm());
        assertEquals(snapshot, JsonUtils.parseObject(JsonUtils.toJsonString(snapshot), AuditKeySnapshot.class));
        var baseline = run(10);
        sql.execute("UPDATE " + table() + " SET amount=10");
        var fixed = compare(baseline, run(20), scope);
        assertTrue(fixed.comparable(), fixed.reason()); assertEquals("PASS", fixed.resultType());
        // Same counts and the same per-column sets, but the original tuples no longer exist.
        sql.execute("UPDATE " + table() + " SET id=CASE WHEN part=9007199254740992 THEN 'a' "
                + "WHEN part=9007199254740993 THEN 'A' ELSE id END");
        assertReason("ROW_POPULATION_CHANGED", compare(baseline, run(21), scope));
        sql.execute("UPDATE " + table() + " SET id=CASE WHEN part=9007199254740992 THEN 'A' "
                + "WHEN part=9007199254740993 THEN 'a' ELSE id END");
        if (scope.equals("ORG")) {
            sql.execute("UPDATE " + table() + " SET org_code=CASE WHEN part=9007199254740992 THEN 10 "
                    + "WHEN part=3 THEN 9 ELSE org_code END");
            assertReason("ROW_POPULATION_CHANGED", compare(baseline, run(22), scope));
        }
        sql.execute("INSERT INTO " + table() + " VALUES(9007199254740993,'a',9,10)");
        assertReason("ROW_COVERAGE_UNPROVEN", compare(baseline, run(23), scope));
    }

    private String table() { return schema + ".sample"; }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
            "BIGINT|NULL|KEY_NULL_OR_TOO_LONG", "VARCHAR(500)|REPEAT('x',250)|KEY_NULL_OR_TOO_LONG",
            "VARCHAR(500)|NULL|KEY_NULL_OR_TOO_LONG", "BIGINT UNSIGNED|18446744073709551615|KEY_TYPE_UNSUPPORTED",
            "CHAR(64)|'a'|KEY_TYPE_UNSUPPORTED", "ENUM('a','b')|'a'|KEY_TYPE_UNSUPPORTED",
            "DECIMAL(20,0)|9|KEY_TYPE_UNSUPPORTED"
    })
    void incompleteTuplesKeepCountsWithoutProvidingRowProof(String type, String value, String reason) throws Exception {
        sql.execute("CREATE TABLE " + table() + "(part BIGINT, id " + type + ", org_code BIGINT, amount BIGINT) ENGINE=InnoDB");
        sql.execute("INSERT INTO " + table() + " VALUES(1," + value + ",9,NULL)");
        var read = AuditKeySnapshot.readComposite(connection, new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION"),
                context(), List.of("part", "id"), 10, 5);
        assertFalse(read.complete()); assertNull(read.keyHash()); assertEquals(reason, read.reasonCode());
        assertEquals(1, read.total()); assertEquals(1, read.fail());
    }

    @Test void fourColumnsPreserveTupleFramingTypeOrderAndGlobalLimits() throws Exception {
        sql.execute("CREATE TABLE " + table() + "(part BIGINT, id TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci, "
                + "extra INT, tail VARCHAR(100), org_code VARCHAR(64), amount BIGINT) ENGINE=InnoDB");
        sql.execute("SET SESSION max_sort_length=4");
        sql.execute("INSERT INTO " + table() + " VALUES(1,'A',10,'bc','9',NULL),(1,'a',10,'bc','9',1),"
                + "(1,'A ',10,'bc','09',1),(2,'ab',10,'c','09',1)");
        var generator = new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");
        var columns = List.of("part", "id", "extra", "tail");
        var baseline = AuditKeySnapshot.readComposite(connection, generator, context(), columns, 4, 5);
        assertTrue(baseline.complete(), baseline.reasonCode()); assertEquals(4, baseline.keyCount());
        var reordered = AuditKeySnapshot.readComposite(connection, generator, context(), List.of("extra", "id", "part", "tail"), 4, 5);
        assertNotEquals(baseline.keyHash(), reordered.keyHash());
        assertEquals("KEY_LIMIT_EXCEEDED", AuditKeySnapshot.readComposite(connection, generator, context(), columns, 3, 5).reasonCode());
        var grouped = AuditKeySnapshot.readGroupedComposite(connection, generator, context(), columns, "org_code", 4, 2, 5);
        assertTrue(grouped.complete()); assertTrue(grouped.groups().stream().allMatch(group -> group.snapshot().complete()));
        assertEquals("GROUP_LIMIT_EXCEEDED", AuditKeySnapshot.readGroupedComposite(connection, generator, context(), columns, "org_code", 4, 1, 5).reasonCode());
        sql.execute("INSERT INTO " + table() + " VALUES(1,'A',10,'bc','9',1)");
        assertEquals("KEY_NOT_UNIQUE_OR_NULL", AuditKeySnapshot.readComposite(connection, generator, context(), columns, 4, 5).reasonCode());
    }
    private AuditRuleContext context() {
        var context = new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover()); context.setProductType(ProductTypeEnum.TIDB);
        context.setTableName(table()); context.setColumn("amount"); context.setConfig(Map.of()); return context;
    }
    private record Run(QualityTaskInstanceDO instance, QualityAuditLogDO audit) { }
    private Run run(long id) {
        var task = QualityTaskDO.builder().id(1L).orgField("org_code").build();
        var object = QualityCheckObjectDO.builder().id(3L).datasourceConnId(4L).datasourceType("THIRD_PARTY")
                .tableName(table()).build();
        var rule = QualityRuleDO.builder().id(2L).status(0).strategyKey("NOT_NULL_VALIDATION").checkLevel("FIELD")
                .paramSchema("{\"column\":\"amount\",\"pkColumns\":[\"part\",\"id\"]}").collectIssueDetail(0).build();
        var definition = new QualityTaskExecutor.GateDefinition();
        definition.setTask(task); definition.setRules(List.of(rule)); definition.setObjects(List.of(object));
        var instance = QualityTaskInstanceDO.builder().id(id).taskId(1L).instanceStatus("SUCCESS")
                .definitionSnapshot(JsonUtils.toJsonString(definition)).startTime(LocalDateTime.now()).build();
        var route = new MasterDatabaseResolver.ConnectionInfo(); route.setDatabaseId(4L);
        route.setProductType(ProductTypeEnum.TIDB); route.setUsageType(1); route.setSchema(schema);
        route.setConfigurationRevision("test-revision");
        var executor = QualityRuntimeFixture.executor();
        ReflectionTestUtils.setField(executor, "auditSqlRegistry", new AuditSqlRegistry());
        ReflectionTestUtils.setField(executor, "auditLogMapper", mock(QualityAuditLogMapper.class));
        QualityAuditLogDO audit = QualityRuntimeFixture.executeRule(executor, id, task,
                connection, route, object, rule);
        assertNotNull(audit); audit.setId(id * 10); instance.setEndTime(LocalDateTime.now());
        assertNotEquals("ERROR", audit.getResultType(), audit.getErrorDetail()); return new Run(instance, audit);
    }
    private QualityRecheckEvidence.Comparison compare(Run baseline, Run current, String scope) {
        var issue = QualityIssueDataDO.builder().id(30L).taskId(1L).ruleId(2L).objectId(3L)
                .instanceId(baseline.instance().getId()).auditLogId(baseline.audit().getId()).tableName(table())
                .checkField("amount").statScope(scope).regionCode("ORG".equals(scope) ? "9" : null).issueCount(1L).build();
        return QualityRecheckEvidence.compare(QualityWorkOrderDO.builder().id(8L).taskId(1L).build(), issue,
                baseline.instance(), baseline.audit(), current.instance(), current.audit(), baseline.instance().getEndTime());
    }
    private static void assertReason(String expected, QualityRecheckEvidence.Comparison result) {
        assertFalse(result.comparable()); assertEquals(expected, result.evidence().get("reasonCode"));
    }
}
