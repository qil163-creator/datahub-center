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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot;
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
@EnabledIfSystemProperty(named = "datahub.test.tidb.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:.*")
class QualityTaskExecutorTextTidbTest {
    @ParameterizedTest @ValueSource(strings = {"text", "integer", "mixed"})
    void actualObjectEntryCarriesTextScopeAndClosesItsPools(String keyMode) throws Exception {
        String schema = "q11_default_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getProperty("datahub.test.tidb.url");
        String user = System.getProperty("datahub.test.tidb.user", "root");
        try (var fixture = DriverManager.getConnection(url, user, ""); var sql = fixture.createStatement()) {
            sql.setQueryTimeout(10); sql.execute("CREATE DATABASE " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".sample(part BIGINT,id VARCHAR(64),org_code VARCHAR(64),amount BIGINT)");
                sql.execute("INSERT INTO " + schema + ".sample VALUES(1,'A','Org',NULL),(2,'a','org',NULL),(3,'A ','Org ',1)");
                var dto = new DatabaseConnectionRespDTO(); dto.setId(4L); dto.setProductType("TIDB");
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
                String keyConfig = keyMode.equals("mixed") ? "\"pkColumns\":[\"part\",\"id\"]"
                        : "\"pkColumn\":\"" + (keyMode.equals("text") ? "id" : "part") + "\"";
                var rule = QualityRuleDO.builder().id(2L).status(0).strategyKey("NOT_NULL_VALIDATION").checkLevel("FIELD")
                        .paramSchema("{\"column\":\"amount\"," + keyConfig + "}").collectIssueDetail(0).build();
                for (String expected : List.of("FAIL", "PASS")) {
                    var results = new ArrayList<QualityAuditLogDO>();
                    QualityRuntimeFixture.executeObject(executor, 10L, task, route, object, List.of(rule), results);
                    assertEquals(1, results.size()); var audit = results.getFirst();
                    assertEquals(expected, audit.getResultType(), audit.getErrorDetail());
                    var evidence = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
                    assertNotNull(evidence.getRelationFingerprint()); assertTrue(evidence.getKeyScope().getComplete());
                    assertTrue(evidence.getOrgScope().getComplete()); assertFalse(fixture.isClosed());
                    assertEquals(AuditKeySnapshot.TIDB_TEXT_GROUP_ENCODING, evidence.getOrgScope().getGroupEncoding());
                    assertEquals(3, evidence.getOrgScope().getGroups().size());
                    assertEquals(keyMode.equals("mixed") ? AuditKeySnapshot.TIDB_TYPED_COMPOSITE_ALGORITHM
                            : keyMode.equals("text") ? AuditKeySnapshot.TIDB_TEXT_ALGORITHM : AuditKeySnapshot.ALGORITHM,
                            evidence.getKeyScope().getAlgorithm());
                    assertTrue(pools.stream().allMatch(HikariDataSource::isClosed));
                    sql.execute("UPDATE " + schema + ".sample SET amount=0");
                }
                assertEquals(2, pools.size());
            } finally { sql.execute("DROP DATABASE " + schema + ""); }
        }
    }
}
