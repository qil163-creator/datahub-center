package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.config.YudaoDbSwitchAutoConfiguration;
import cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityAuditLogMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_CHECK_OBJECT_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real task entry and fresh reads; Hive relation/authorization evidence is deliberately still unqualified. */
@EnabledIfSystemProperty(named = "datahub.test.hive.url", matches = "jdbc:hive2://127[.]0[.]0[.]1:[0-9]+/default")
@EnabledIfSystemProperty(named = "datahub.test.hive.driver", matches = ".+")
class QualityIntegerKeyHiveTest {
    private HikariDataSource pool;
    private Connection connection;
    private MasterDatabaseResolver resolver;
    private MasterDatabaseResolver.ConnectionInfo route;
    private String schema;

    @BeforeEach void setup() throws Exception {
        schema = "q11hg_" + UUID.randomUUID().toString().replace("-", "");
        Path jar = Path.of(System.getProperty("datahub.test.hive.driver")).toRealPath();
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        var dto = new DatabaseConnectionRespDTO();
        dto.setId(4L); dto.setProductType("HIVE"); dto.setSchema(schema); dto.setUsageType(1);
        dto.setJdbcUrl(System.getProperty("datahub.test.hive.url")); dto.setUsername("hive"); dto.setPassword("");
        dto.setConfigurationRevision("fixture-revision");
        var api = mock(DatabaseApi.class); when(api.getDatabaseConnection(4L)).thenReturn(success(dto));
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(YudaoDbSwitchAutoConfiguration.class))
                .withBean(DatabaseApi.class, () -> api).withBean(MasterDatabaseResolver.class)
                .withPropertyValues("yudao.dbswitch.drivers.HIVE.path=" + jar, "yudao.dbswitch.drivers.HIVE.sha256=" + sha)
                .run(context -> {
                    assertNull(context.getStartupFailure()); resolver = context.getBean(MasterDatabaseResolver.class);
                    route = resolver.resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS);
                    assertNotEquals(dto.getConfigurationRevision(), route.getConfigurationRevision());
                    pool = resolver.createDataSource(route); connection = pool.getConnection();
                });
        execute("CREATE DATABASE " + schema);
        execute("CREATE EXTERNAL TABLE " + table() + " (id BIGINT, part INT, org_code SMALLINT, amount BIGINT) "
                + "STORED AS TEXTFILE TBLPROPERTIES ('external.table.purge'='true')");
    }

    @AfterEach void cleanup() throws Exception {
        try { if (connection != null && schema != null) execute("DROP DATABASE " + schema + " CASCADE"); }
        finally { try { if (connection != null) connection.close(); } finally { if (pool != null) pool.close(); } }
    }

    @Test void actualTaskReadsNewCountsAndClosesPoolsWithoutPromotingIncompleteIdentity() throws Exception {
        var pools = new ArrayList<HikariDataSource>(); var tracked = spy(resolver);
        doAnswer(call -> {
            var created = resolver.createDataSource(call.getArgument(0)); pools.add(created); return created;
        }).when(tracked).createDataSource(any());
        var executor = QualityRuntimeFixture.executor();
        ReflectionTestUtils.setField(executor, "databaseResolver", tracked);
        ReflectionTestUtils.setField(executor, "auditSqlRegistry", new AuditSqlRegistry());
        ReflectionTestUtils.setField(executor, "auditLogMapper", mock(QualityAuditLogMapper.class));
        var originals = new HashMap<Boolean, Run>();
        for (boolean repaired : List.of(false, true)) {
            execute("INSERT OVERWRITE TABLE " + table() + " VALUES (9007199254740992,1,9,"
                    + (repaired ? "0" : "NULL") + "),(9007199254740993,2,10,1)");
            for (boolean composite : List.of(false, true)) {
                var run = run(executor, 10L + pools.size(), composite);
                var audit = run.audit(); var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
                assertEquals(repaired ? "PASS" : "FAIL", audit.getResultType(), audit.getErrorDetail());
                assertEquals(2L, audit.getTotalCount()); assertEquals(repaired ? 0L : 1L, audit.getFailCount());
                assertEquals("HIVE", read.getProductType()); assertEquals("SOURCE_DIRECT", read.getReadMode());
                assertEquals("RELATION_UNPROVEN", read.getCoverage()); assertNull(read.getRelationFingerprint());
                assertTrue(read.getKeyScope().getComplete(), read.getKeyScope().getReasonCode());
                assertEquals(composite ? AuditKeySnapshot.HIVE_COMPOSITE_ALGORITHM : AuditKeySnapshot.ALGORITHM,
                        read.getKeyScope().getAlgorithm());
                assertTrue(read.getOrgScope().getComplete(), read.getOrgScope().getReasonCode());
                assertEquals(AuditKeySnapshot.HIVE_INTEGER_GROUP_ENCODING, read.getOrgScope().getGroupEncoding());
                assertEquals(2, read.getOrgScope().getGroups().size());
                assertTrue(pools.stream().allMatch(HikariDataSource::isClosed)); assertFalse(connection.isClosed());
                if (!repaired) originals.put(composite, run);
                else {
                    var before = originals.get(composite);
                    var previous = JsonUtils.parseObject(before.audit().getReadEvidence(), QualityReadEvidence.class);
                    assertEquals(previous.getKeyScope().getKeyHash(), read.getKeyScope().getKeyHash());
                    assertNotEquals(previous.getStartedAt(), read.getStartedAt());
                    for (String scope : List.of("ALL", "ORG")) {
                        var comparison = compare(before, run, scope);
                        assertFalse(comparison.comparable());
                        assertEquals("READ_EVIDENCE_MISSING", comparison.evidence().get("reasonCode"));
                    }
                }
            }
        }
        assertEquals(4, pools.size());
    }

    private Run run(QualityTaskExecutor executor, long id, boolean composite) {
        var definition = new QualityTaskExecutor.GateDefinition();
        definition.setTask(task()); definition.setObjects(List.of(object())); definition.setRules(List.of(rule(composite)));
        var instance = QualityTaskInstanceDO.builder().id(id).taskId(1L).instanceStatus("SUCCESS")
                .definitionSnapshot(JsonUtils.toJsonString(definition)).startTime(LocalDateTime.now()).build();
        var results = new ArrayList<QualityAuditLogDO>();
        QualityRuntimeFixture.executeObject(executor, id, task(), route, object(), List.of(rule(composite)), results);
        assertEquals(1, results.size()); var audit = results.getFirst(); audit.setId(id * 10);
        assertNotEquals("ERROR", audit.getResultType(), audit.getErrorDetail()); instance.setEndTime(LocalDateTime.now());
        return new Run(instance, audit);
    }

    private QualityRecheckEvidence.Comparison compare(Run before, Run after, String scope) {
        var issue = QualityIssueDataDO.builder().id(30L).taskId(1L).ruleId(2L).objectId(3L)
                .instanceId(before.instance().getId()).auditLogId(before.audit().getId()).tableName(table())
                .checkField("amount").statScope(scope).regionCode("ORG".equals(scope) ? "9" : null).issueCount(1L).build();
        return QualityRecheckEvidence.compare(QualityWorkOrderDO.builder().id(8L).taskId(1L).build(), issue,
                before.instance(), before.audit(), after.instance(), after.audit(), before.instance().getEndTime());
    }

    private String table() { return schema + ".sample"; }
    private void execute(String text) throws SQLException {
        try (var sql = connection.createStatement()) { sql.setQueryTimeout(120); sql.execute(text); }
    }
    private QualityTaskDO task() { return QualityTaskDO.builder().id(1L).orgField("org_code").build(); }
    private QualityCheckObjectDO object() {
        return QualityCheckObjectDO.builder().id(3L).datasourceConnId(4L).datasourceType("THIRD_PARTY").tableName(table()).build();
    }
    private QualityRuleDO rule(boolean composite) {
        return QualityRuleDO.builder().id(2L).status(0).strategyKey("NOT_NULL_VALIDATION").checkLevel("FIELD")
                .paramSchema(composite ? "{\"column\":\"amount\",\"pkColumns\":[\"id\",\"part\"]}"
                        : "{\"column\":\"amount\",\"pkColumn\":\"id\"}").collectIssueDetail(0).build();
    }
    private record Run(QualityTaskInstanceDO instance, QualityAuditLogDO audit) { }
}
