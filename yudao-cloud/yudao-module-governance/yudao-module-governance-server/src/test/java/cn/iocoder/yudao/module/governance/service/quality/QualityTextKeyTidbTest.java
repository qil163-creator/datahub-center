package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot;
import cn.iocoder.yudao.framework.quality.core.audit.AuditRuleContext;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityAuditLogMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnabledIfSystemProperty(named = "datahub.test.tidb.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:.*")
class QualityTextKeyTidbTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
            "BIGINT UNSIGNED|18446744073709551615", "DECIMAL(20,0)|9223372036854775808",
            "VARCHAR(5000)|REPEAT('9',4000)", "BIGINT|NULL", "VARCHAR(500)|NULL",
            "TEXT|REPEAT('x',257)", "TEXT CHARACTER SET utf8mb4|REPEAT('中',86)",
            "CHAR(64)|'a'", "ENUM('a','b')|'a'", "SET('a','b')|'a'", "VARBINARY(64)|'a'",
            "TINYINT|9", "MEDIUMINT|9"
    })
    void unsupportedOrNullKeysKeepOrdinaryCountsButNeverProvideCompleteProof(String type, String value) throws Exception {
        String schema = "quality_keys_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.tidb.url"),
                System.getProperty("datahub.test.tidb.user", "root"), "");
             var sql = connection.createStatement()) {
            sql.execute("CREATE DATABASE " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".sample(id " + type + ", amount BIGINT) ENGINE=InnoDB");
                sql.execute("INSERT INTO " + schema + ".sample VALUES(" + value + ",10)");
                var run = run(connection, schema, 10L);
                assertEquals("PASS", run.audit().getResultType(), run.audit().getErrorDetail());
                assertEquals(1, run.audit().getTotalCount()); assertEquals(0, run.audit().getFailCount());
                var read = JsonUtils.parseObject(run.audit().getReadEvidence(), QualityReadEvidence.class);
                assertNotNull(read.getRelationFingerprint());
                assertFalse(read.getKeyScope().getComplete()); assertNull(read.getKeyScope().getKeyHash());
            } finally {
                sql.execute("DROP DATABASE " + schema);
            }
        }
    }

    private record Run(QualityTaskInstanceDO instance, QualityAuditLogDO audit) { }

    @Test
    void textKeyByteAndRowLimitsRejectTruncationAndNeverReturnPartialProof() throws Exception {
        String schema = "quality_keys_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.tidb.url"),
                System.getProperty("datahub.test.tidb.user", "root"), "");
             var sql = connection.createStatement()) {
            sql.execute("CREATE DATABASE " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".sample(id LONGTEXT CHARACTER SET utf8mb4 "
                        + "COLLATE utf8mb4_general_ci, amount BIGINT) ENGINE=InnoDB");
                var context = new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover()); context.setProductType(ProductTypeEnum.TIDB);
                context.setTableName(schema + ".sample"); context.setColumn("amount"); context.setConfig(java.util.Map.of());
                var generator = new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");
                sql.execute("INSERT INTO " + schema + ".sample VALUES(REPEAT('x',256),NULL)");
                var bounded = AuditKeySnapshot.read(connection, generator, context, "id", 2, 5);
                assertTrue(bounded.complete());
                sql.execute("UPDATE " + schema + ".sample SET id=REPEAT('x',257)");
                var tooLong = AuditKeySnapshot.read(connection, generator, context, "id", 2, 5);
                assertFalse(tooLong.complete()); assertNull(tooLong.keyHash());
                assertEquals("KEY_NULL_OR_TOO_LONG", tooLong.reasonCode());
                assertEquals(1, tooLong.total()); assertEquals(1, tooLong.fail());
                sql.execute("UPDATE " + schema + ".sample SET id='A'");
                sql.execute("INSERT INTO " + schema + ".sample VALUES('a',1),('A ',1)");
                var overflow = AuditKeySnapshot.read(connection, generator, context, "id", 2, 5);
                assertEquals("KEY_LIMIT_EXCEEDED", overflow.reasonCode());
                assertEquals(AuditKeySnapshot.TIDB_TEXT_ALGORITHM, overflow.algorithm());
                assertEquals(-1, overflow.total()); assertNull(overflow.keyHash()); assertFalse(overflow.complete());
                var exact = AuditKeySnapshot.read(connection, generator, context, "id", 3, 5);
                assertTrue(exact.complete()); assertEquals(3, exact.keyCount());
            } finally {
                sql.execute("DROP DATABASE " + schema);
            }
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "VARCHAR(1000)|12", "TINYTEXT|-1", "TEXT|-1", "MEDIUMTEXT|-1", "LONGTEXT|-1"
    })
    void textKeysUseExactBoundedBytesAndAcceptOnlyTheOriginalRows(String type, int jdbcType) throws Exception {
        String schema = "quality_keys_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.tidb.url"),
                System.getProperty("datahub.test.tidb.user", "root"), "");
             var sql = connection.createStatement()) {
            sql.execute("CREATE DATABASE " + schema);
            try {
                sql.execute("SET SESSION max_sort_length=4");
                sql.execute("CREATE TABLE " + schema + ".sample(id " + type
                        + " CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci, amount BIGINT) ENGINE=InnoDB");
                // Database collation equates some of these values; evidence must not merge their identities.
                List<String> keys = List.of("A", "a", "A ", "0009", "9", "中国😀", "é", "é", "", "x".repeat(255));
                try (var insert = connection.prepareStatement("INSERT INTO " + schema + ".sample VALUES(?,?)")) {
                    for (int i = 0; i < keys.size(); i++) {
                        insert.setString(1, keys.get(i)); insert.setObject(2, i == 0 ? null : 10); insert.executeUpdate();
                    }
                }
                var baseline = run(connection, schema, 10);
                var proof = JsonUtils.parseObject(baseline.audit().getReadEvidence(), QualityReadEvidence.class).getKeyScope();
                assertTrue(proof.getComplete(), proof.getReasonCode());
                assertEquals("SHA256_SORTED_TIDB_UTF8_BYTES_V1", proof.getAlgorithm());
                assertEquals(jdbcType, proof.getJdbcType()); assertEquals(keys.size(), proof.getKeyCount());
                assertEquals(keys.size(), baseline.audit().getTotalCount()); assertEquals(1, baseline.audit().getFailCount());
                var issue = QualityIssueDataDO.builder().id(30L).taskId(1L).ruleId(2L).objectId(3L)
                        .instanceId(10L).auditLogId(baseline.audit().getId()).tableName(schema + ".sample")
                        .checkField("amount").statScope("ALL").issueCount(1L).build();
                var order = QualityWorkOrderDO.builder().id(8L).taskId(1L).build();
                sql.execute("UPDATE " + schema + ".sample SET amount=10");
                var fixed = run(connection, schema, 20);
                assertTrue(QualityRecheckEvidence.compare(order, issue, baseline.instance(), baseline.audit(),
                        fixed.instance(), fixed.audit(), baseline.instance().getEndTime()).comparable());
                sql.execute("UPDATE " + schema + ".sample SET id='replacement' WHERE CAST(id AS BINARY)=X'41'");
                var replaced = run(connection, schema, 21);
                var changed = QualityRecheckEvidence.compare(order, issue, baseline.instance(), baseline.audit(),
                        replaced.instance(), replaced.audit(), baseline.instance().getEndTime());
                assertFalse(changed.comparable()); assertEquals("ROW_POPULATION_CHANGED", changed.evidence().get("reasonCode"));
                sql.execute("INSERT INTO " + schema + ".sample VALUES('a',10)");
                var duplicated = run(connection, schema, 22);
                var incomplete = QualityRecheckEvidence.compare(order, issue, baseline.instance(), baseline.audit(),
                        duplicated.instance(), duplicated.audit(), baseline.instance().getEndTime());
                assertFalse(incomplete.comparable()); assertEquals("ROW_COVERAGE_UNPROVEN", incomplete.evidence().get("reasonCode"));
            } finally {
                sql.execute("DROP DATABASE " + schema);
            }
        }
    }

    private Run run(Connection connection, String schema, long instanceId) {
        var task = QualityTaskDO.builder().id(1L).build();
        var object = QualityCheckObjectDO.builder().id(3L).datasourceConnId(4L).datasourceType("THIRD_PARTY")
                .tableName(schema + ".sample").build();
        var rule = QualityRuleDO.builder().id(2L).status(0).strategyKey("NOT_NULL_VALIDATION").checkLevel("FIELD")
                .paramSchema("{\"column\":\"amount\",\"pkColumn\":\"id\"}").collectIssueDetail(0).build();
        var definition = new QualityTaskExecutor.GateDefinition();
        definition.setTask(task); definition.setRules(List.of(rule)); definition.setObjects(List.of(object));
        var instance = QualityTaskInstanceDO.builder().id(instanceId).taskId(1L).instanceStatus("SUCCESS")
                .definitionSnapshot(JsonUtils.toJsonString(definition)).startTime(LocalDateTime.now()).build();
        var route = new MasterDatabaseResolver.ConnectionInfo();
        route.setDatabaseId(4L); route.setProductType(ProductTypeEnum.TIDB); route.setUsageType(1);
        route.setSchema(schema); route.setConfigurationRevision("test-revision");
        var executor = QualityRuntimeFixture.executor();
        ReflectionTestUtils.setField(executor, "auditSqlRegistry", new AuditSqlRegistry());
        ReflectionTestUtils.setField(executor, "auditLogMapper", mock(QualityAuditLogMapper.class));
        QualityAuditLogDO audit = QualityRuntimeFixture.executeRule(executor,
                instanceId, task, connection, route, object, rule);
        assertNotNull(audit); audit.setId(instanceId * 10);
        instance.setEndTime(LocalDateTime.now());
        assertNotEquals("ERROR", audit.getResultType(), audit.getErrorDetail());
        return new Run(instance, audit);
    }
}
