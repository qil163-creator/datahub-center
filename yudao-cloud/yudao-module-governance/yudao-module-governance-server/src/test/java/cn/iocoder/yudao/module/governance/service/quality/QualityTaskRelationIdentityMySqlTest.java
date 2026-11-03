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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Only an explicitly supplied, isolated loopback MySQL instance; never the application datasource. */
@EnabledIfSystemProperty(named = "datahub.test.mysql.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:.*")
class QualityTaskRelationIdentityMySqlTest {
    @Test
    void bindsActualTableAndRejectsReplacementAndTemporaryShadow() throws Exception {
        String schema = "quality_identity_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.mysql.url"),
                System.getProperty("datahub.test.mysql.user", "root"), "");
             var setup = connection.createStatement()) {
            setup.execute("CREATE DATABASE " + schema);
            connection.setCatalog(schema);
            try (var sql = connection.createStatement()) {
                sql.execute("CREATE TABLE sample(id bigint) ENGINE=InnoDB");
                sql.execute("INSERT INTO sample VALUES(1)");
                var before = read(connection, schema);
                assertEquals(QualityReadEvidence.RELATION_SCHEMA_VERSION, before.getSchemaVersion());
                assertNotNull(before.getRelationFingerprint());
                assertEquals("FULL_PREDICATE", before.getCoverage());
                sql.execute("DROP TABLE sample");
                sql.execute("CREATE TABLE sample(id bigint) ENGINE=InnoDB");
                sql.execute("INSERT INTO sample VALUES(1)");
                assertNotEquals(before.getRelationFingerprint(), read(connection, schema).getRelationFingerprint());
                sql.execute("CREATE TEMPORARY TABLE sample(id bigint) ENGINE=InnoDB");
                sql.execute("INSERT INTO sample VALUES(1)");
                var shadow = read(connection, schema);
                assertNull(shadow.getRelationFingerprint());
                assertEquals("RELATION_UNPROVEN", shadow.getCoverage());
                sql.execute("DROP TEMPORARY TABLE sample");
            } finally {
                connection.setCatalog("mysql");
                try (var cleanup = connection.createStatement()) {
                    cleanup.execute("DROP DATABASE " + schema);
                }
            }
        }
    }

    @Test
    void columnOnlyAccountCanRunQualityButCannotClaimAutomaticAcceptanceProof() throws Exception {
        String schema = "quality_identity_" + UUID.randomUUID().toString().replace("-", "");
        String user = "quality_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        try (var admin = DriverManager.getConnection(System.getProperty("datahub.test.mysql.url"),
                System.getProperty("datahub.test.mysql.user", "root"), "");
             var sql = admin.createStatement()) {
            String peer;
            try (var rows = sql.executeQuery("SELECT SUBSTRING_INDEX(USER(),'@',-1)")) {
                assertTrue(rows.next()); peer = rows.getString(1);
            }
            assertTrue(peer.matches("[A-Za-z0-9:._-]+"));
            String account = "'" + user + "'@'" + peer + "'";
            sql.execute("CREATE DATABASE " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".sample(id bigint) ENGINE=InnoDB");
                sql.execute("INSERT INTO " + schema + ".sample VALUES(1)");
                sql.execute("CREATE USER " + account);
                try {
                    sql.execute("GRANT SELECT(id) ON " + schema + ".sample TO " + account);
                    String fixtureUrl = System.getProperty("datahub.test.mysql.url");
                    int databaseStart = fixtureUrl.indexOf('/', "jdbc:mysql://".length());
                    assertTrue(databaseStart > 0);
                    int queryStart = fixtureUrl.indexOf('?', databaseStart);
                    // The restricted account has no privilege on the fixture's default database.
                    // Connect directly to its granted schema without broadening the column-only grant.
                    String authorizedUrl = fixtureUrl.substring(0, databaseStart + 1) + schema
                            + (queryStart < 0 ? "" : fixtureUrl.substring(queryStart));
                    try (var reader = DriverManager.getConnection(authorizedUrl, user, "")) {
                        reader.setCatalog(schema);
                        var evidence = read(reader, schema);
                        assertEquals(1, evidence.getSchemaVersion());
                        assertNull(evidence.getRelationFingerprint());
                        assertEquals("RELATION_UNPROVEN", evidence.getCoverage());
                    }
                } finally {
                    sql.execute("DROP USER " + account);
                }
            } finally {
                sql.execute("DROP DATABASE " + schema);
            }
        }
    }

    private QualityReadEvidence read(Connection connection, String schema) {
        var executor = QualityRuntimeFixture.executor();
        ReflectionTestUtils.setField(executor, "auditSqlRegistry", new AuditSqlRegistry());
        ReflectionTestUtils.setField(executor, "auditLogMapper", mock(QualityAuditLogMapper.class));
        var route = new MasterDatabaseResolver.ConnectionInfo();
        route.setDatabaseId(4L); route.setProductType(ProductTypeEnum.MYSQL);
        route.setConfigurationRevision("revision"); route.setSchema(schema); route.setUsageType(1);
        var task = QualityTaskDO.builder().id(1L).build();
        var object = QualityCheckObjectDO.builder().id(3L).datasourceConnId(4L)
                .datasourceType("THIRD_PARTY").tableName("sample").build();
        var rule = QualityRuleDO.builder().id(2L).strategyKey("NOT_NULL_VALIDATION")
                .checkLevel("FIELD").paramSchema("{\"column\":\"id\"}").collectIssueDetail(0).build();
        QualityAuditLogDO audit = QualityRuntimeFixture.executeRule(executor,
                10L, task, connection, route, object, rule);
        assertNotNull(audit);
        assertEquals(QualityAuditLogDO.RESULT_PASS, audit.getResultType(), audit.getErrorDetail());
        return JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
    }
}
