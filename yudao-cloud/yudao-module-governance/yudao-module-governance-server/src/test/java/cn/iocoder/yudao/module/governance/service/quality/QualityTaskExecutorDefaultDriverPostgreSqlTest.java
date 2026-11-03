package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityAuditLogMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_CHECK_OBJECT_NOT_EXISTS;

/** Actual executeObject entry with the existing classpath driver, not just a passed-in executeRule connection. */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class QualityTaskExecutorDefaultDriverPostgreSqlTest {
    @Test void existingDefaultDriverStillRunsRulesAndReleasesEachObjectPool() throws Exception {
        String schema = "q11_default_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getProperty("datahub.test.postgres.url");
        String user = System.getProperty("datahub.test.postgres.user", System.getProperty("datahub.test.postgres.user", "dataforge_test"));
        try (var fixture = DriverManager.getConnection(url, user, ""); var sql = fixture.createStatement()) {
            sql.setQueryTimeout(10); sql.execute("CREATE SCHEMA " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".sample(id BIGINT PRIMARY KEY,org_code BIGINT,amount BIGINT)");
                sql.execute("INSERT INTO " + schema + ".sample VALUES(1,9,NULL)");
                var dto = new DatabaseConnectionRespDTO(); dto.setId(4L); dto.setProductType("POSTGRESQL");
                dto.setJdbcUrl(url); dto.setSchema(schema); dto.setUsername(user); dto.setPassword("");
                dto.setUsageType(1); dto.setConfigurationRevision("test-revision");
                var api = mock(DatabaseApi.class); when(api.getDatabaseConnection(4L)).thenReturn(CommonResult.success(dto));
                var resolver = spy(new MasterDatabaseResolver()); ReflectionTestUtils.setField(resolver, "databaseApi", api);
                var route = resolver.resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS);
                var pools = new ArrayList<HikariDataSource>();
                doAnswer(call -> { var pool = (HikariDataSource) call.callRealMethod(); pools.add(pool); return pool; })
                        .when(resolver).createDataSource(route);
                var executor = QualityRuntimeFixture.executor(); ReflectionTestUtils.setField(executor, "databaseResolver", resolver);
                ReflectionTestUtils.setField(executor, "auditSqlRegistry", new AuditSqlRegistry());
                ReflectionTestUtils.setField(executor, "auditLogMapper", mock(QualityAuditLogMapper.class));
                var task = QualityTaskDO.builder().id(1L).orgField("org_code").build();
                var object = QualityCheckObjectDO.builder().id(3L).datasourceConnId(4L).datasourceType("THIRD_PARTY")
                        .tableName(schema + ".sample").build();
                var rule = QualityRuleDO.builder().id(2L).status(0).strategyKey("NOT_NULL_VALIDATION").checkLevel("FIELD")
                        .paramSchema("{\"column\":\"amount\",\"pkColumn\":\"id\"}").collectIssueDetail(0).build();
                for (String expected : List.of("FAIL", "PASS")) {
                    var results = new ArrayList<QualityAuditLogDO>();
                    QualityRuntimeFixture.executeObject(executor, 10L, task, route, object, List.of(rule), results);
                    assertEquals(1, results.size()); var audit = results.getFirst();
                    assertEquals(expected, audit.getResultType(), audit.getErrorDetail());
                    var evidence = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
                    assertNotNull(evidence.getRelationFingerprint()); assertTrue(evidence.getKeyScope().getComplete());
                    assertTrue(evidence.getOrgScope().getComplete()); assertFalse(fixture.isClosed());
                    assertTrue(pools.stream().allMatch(HikariDataSource::isClosed));
                    sql.execute("UPDATE " + schema + ".sample SET amount=0");
                }
                assertEquals(2, pools.size());
            } finally { sql.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }
}
