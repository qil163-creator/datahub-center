package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.util.PostgresRelationIdentity;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityAuditLogDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityCheckObjectDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityTaskDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityAuditLogMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/** 真实读取连接、真实 SQL 生成器及 DB Switch 指纹；业务审计表写入由 mock 隔离。 */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class QualityTaskRelationIdentityPostgreSqlTest {
    @Test
    void auditReadBindsThePhysicalTableActuallyUsedByTheCountSql() throws Exception {
        String schema = "quality_relation_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.postgres.url"),
                System.getProperty("datahub.test.postgres.user", System.getProperty("datahub.test.postgres.user", "dataforge_test")), "");
             var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            try {
                connection.setSchema(schema);
                sql.execute("CREATE TABLE sample(id bigint)");
                sql.execute("INSERT INTO sample VALUES(1)");
                var executor = QualityRuntimeFixture.executor();
                ReflectionTestUtils.setField(executor, "auditSqlRegistry", new AuditSqlRegistry());
                ReflectionTestUtils.setField(executor, "auditLogMapper", mock(QualityAuditLogMapper.class));
                var route = new MasterDatabaseResolver.ConnectionInfo();
                route.setDatabaseId(4L); route.setProductType(ProductTypeEnum.POSTGRESQL);
                route.setConfigurationRevision("revision"); route.setSchema(schema); route.setUsageType(1);
                var task = QualityTaskDO.builder().id(1L).build();
                var object = QualityCheckObjectDO.builder().id(3L).datasourceConnId(4L)
                        .datasourceType("THIRD_PARTY").tableName("sample").build();
                var rule = QualityRuleDO.builder().id(2L).strategyKey("NOT_NULL_VALIDATION")
                        .checkLevel("FIELD").paramSchema("{\"column\":\"id\"}").collectIssueDetail(0).build();
                QualityAuditLogDO first = QualityRuntimeFixture.executeRule(executor,
                        10L, task, connection, route, object, rule);
                assertNotNull(first);
                assertEquals(QualityAuditLogDO.RESULT_PASS, first.getResultType());
                var before = JsonUtils.parseObject(first.getReadEvidence(), QualityReadEvidence.class);
                assertEquals(QualityReadEvidence.RELATION_SCHEMA_VERSION, before.getSchemaVersion());
                assertEquals(PostgresRelationIdentity.fingerprint(connection, "sample"), before.getRelationFingerprint());
                sql.execute("DROP TABLE sample");
                sql.execute("CREATE TABLE sample(id bigint)");
                sql.execute("INSERT INTO sample VALUES(1)");
                QualityAuditLogDO second = QualityRuntimeFixture.executeRule(executor,
                        11L, task, connection, route, object, rule);
                assertNotNull(second);
                assertEquals(QualityAuditLogDO.RESULT_PASS, second.getResultType());
                var after = JsonUtils.parseObject(second.getReadEvidence(), QualityReadEvidence.class);
                assertNotEquals(before.getRelationFingerprint(), after.getRelationFingerprint());
                sql.execute("CREATE VIEW sample_view AS SELECT id FROM sample");
                object.setTableName("sample_view");
                QualityAuditLogDO viewAudit = QualityRuntimeFixture.executeRule(executor,
                        12L, task, connection, route, object, rule);
                assertNotNull(viewAudit);
                assertEquals(QualityAuditLogDO.RESULT_PASS, viewAudit.getResultType());
                var viewRead = JsonUtils.parseObject(viewAudit.getReadEvidence(), QualityReadEvidence.class);
                assertNull(viewRead.getRelationFingerprint());
                assertEquals(1, viewRead.getSchemaVersion());
            } finally {
                sql.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
