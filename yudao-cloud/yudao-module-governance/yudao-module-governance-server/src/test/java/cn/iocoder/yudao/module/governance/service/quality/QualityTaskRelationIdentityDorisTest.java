package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityAuditLogMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnabledIfSystemProperty(named = "datahub.test.doris.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
class QualityTaskRelationIdentityDorisTest {
    private static final String SUFFIX = " UNIQUE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ('replication_num'='1')";

    @Test
    void ordinaryFullRuleBindsNativeGenerationThroughJsonAndDecision() throws Exception {
        String schema = "quality_doris_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.doris.url"), "root", "");
             var sql = connection.createStatement()) {
            sql.execute("CREATE DATABASE " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".sample(id BIGINT, amount BIGINT)" + SUFFIX);
                sql.execute("INSERT INTO " + schema + ".sample VALUES(1,NULL)");
                var before = run(connection, schema, 10, false);
                var proof = read(before);
                assertEquals(3, proof.getSchemaVersion());
                assertEquals("DORIS", proof.getProductType());
                assertEquals("FULL_PREDICATE", proof.getCoverage());
                assertNotNull(proof.getRelationFingerprint());
                sql.execute("UPDATE " + schema + ".sample SET amount=10 WHERE id=1");
                var fixed = run(connection, schema, 20, false);
                var compared = compare(schema, before, fixed);
                assertTrue(compared.comparable(), compared.reason());
                assertEquals("PASS", compared.resultType());
                sql.execute("TRUNCATE TABLE " + schema + ".sample");
                sql.execute("INSERT INTO " + schema + ".sample VALUES(1,10)");
                var truncated = run(connection, schema, 21, false);
                assertNotEquals(proof.getRelationFingerprint(), read(truncated).getRelationFingerprint());
                assertFalse(compare(schema, before, truncated).comparable());
                sql.execute("CREATE TEMPORARY TABLE " + schema + ".sample(id BIGINT, amount BIGINT)" + SUFFIX);
                sql.execute("INSERT INTO " + schema + ".sample VALUES(1,10)");
                var shadow = run(connection, schema, 22, false);
                assertEquals("RELATION_UNPROVEN", read(shadow).getCoverage());
                assertNull(read(shadow).getRelationFingerprint());
                assertFalse(compare(schema, before, shadow).comparable());
                sql.execute("DROP TABLE " + schema + ".sample");
            } finally { sql.execute("DROP DATABASE " + schema + " FORCE"); }
        }
    }

    @Test
    void relationIdentityDoesNotQualifyUnsupportedDecimalKeys() throws Exception {
        String schema = "quality_doris_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.doris.url"), "root", "");
             var sql = connection.createStatement()) {
            sql.execute("CREATE DATABASE " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".sample(id DECIMAL(18,0), amount BIGINT)" + SUFFIX);
                sql.execute("INSERT INTO " + schema + ".sample VALUES(1,NULL)");
                var before = run(connection, schema, 10, true);
                sql.execute("UPDATE " + schema + ".sample SET amount=10 WHERE id=1");
                var after = run(connection, schema, 20, true);
                assertEquals("PASS", after.audit().getResultType());
                assertNotNull(read(after).getRelationFingerprint());
                assertFalse(compare(schema, before, after).comparable());
            } finally { sql.execute("DROP DATABASE " + schema + " FORCE"); }
        }
    }

    private QualityRecheckEvidence.Comparison compare(String schema, Run before, Run after) {
        var issue = QualityIssueDataDO.builder().id(30L).taskId(1L).ruleId(2L).objectId(3L)
                .instanceId(10L).auditLogId(before.audit().getId()).tableName(schema + ".sample")
                .checkField("amount").statScope("ALL").issueCount(1L).build();
        return QualityRecheckEvidence.compare(QualityWorkOrderDO.builder().id(8L).taskId(1L).build(), issue,
                before.instance(), before.audit(), after.instance(), after.audit(), before.instance().getEndTime());
    }

    private QualityReadEvidence read(Run run) {
        return JsonUtils.parseObject(run.audit().getReadEvidence(), QualityReadEvidence.class);
    }

    private record Run(QualityTaskInstanceDO instance, QualityAuditLogDO audit) { }
    private Run run(Connection connection, String schema, long id, boolean keys) {
        var task = QualityTaskDO.builder().id(1L).build();
        var object = QualityCheckObjectDO.builder().id(3L).datasourceConnId(4L).datasourceType("THIRD_PARTY")
                .tableName(schema + ".sample").build();
        var rule = QualityRuleDO.builder().id(2L).status(0).strategyKey("NOT_NULL_VALIDATION").checkLevel("FIELD")
                .paramSchema(keys ? "{\"column\":\"amount\",\"pkColumn\":\"id\"}" : "{\"column\":\"amount\"}")
                .collectIssueDetail(0).build();
        var definition = new QualityTaskExecutor.GateDefinition();
        definition.setTask(task); definition.setRules(List.of(rule)); definition.setObjects(List.of(object));
        var instance = QualityTaskInstanceDO.builder().id(id).taskId(1L).instanceStatus("SUCCESS")
                .definitionSnapshot(JsonUtils.toJsonString(definition)).startTime(LocalDateTime.now()).build();
        var route = new MasterDatabaseResolver.ConnectionInfo();
        route.setDatabaseId(4L); route.setProductType(ProductTypeEnum.DORIS); route.setUsageType(1);
        route.setSchema(schema); route.setConfigurationRevision("test-revision");
        var executor = QualityRuntimeFixture.executor();
        ReflectionTestUtils.setField(executor, "auditSqlRegistry", new AuditSqlRegistry());
        ReflectionTestUtils.setField(executor, "auditLogMapper", mock(QualityAuditLogMapper.class));
        QualityAuditLogDO audit = QualityRuntimeFixture.executeRule(executor, id, task, connection, route, object, rule);
        assertNotNull(audit); audit.setId(id * 10);
        instance.setEndTime(LocalDateTime.now());
        assertNotEquals("ERROR", audit.getResultType(), audit.getErrorDetail());
        return new Run(instance, audit);
    }
}
