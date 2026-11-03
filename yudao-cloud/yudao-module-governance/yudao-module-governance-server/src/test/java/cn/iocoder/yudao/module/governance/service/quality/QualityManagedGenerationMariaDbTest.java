package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredSourceGenerations;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.util.MariaDbSourceGeneration;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityAuditLogMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityIssueDataMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real task entry on a SELECT-only MariaDB source, with deployment-pinned source incarnation. */
@EnabledIfSystemProperty(named = "datahub.test.mariadb.url", matches = "jdbc:mariadb://127[.]0[.]0[.]1:[0-9]+/")
class QualityManagedGenerationMariaDbTest {
    private Connection admin;
    private Statement sql;
    private String schema, metadataSchema, reader, password;
    private SourceGenerationPin pin;
    private MasterDatabaseResolver resolver;
    private MasterDatabaseResolver.ConnectionInfo route;

    @BeforeEach void setup() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        schema = "q26_data_" + suffix; metadataSchema = "q26_meta_" + suffix; reader = "q26_" + suffix;
        password = UUID.randomUUID().toString();
        admin = DriverManager.getConnection(url(), "root", ""); sql = admin.createStatement(); sql.setQueryTimeout(10);
        sql.execute("CREATE DATABASE " + schema); sql.execute("CREATE DATABASE " + metadataSchema);
        sql.execute("CREATE TABLE " + table() + "(id BIGINT PRIMARY KEY,part BIGINT,org_code BIGINT,amount BIGINT) ENGINE=InnoDB");
        sql.execute("INSERT INTO " + table() + " VALUES(9007199254740992,11,9,NULL),(9007199254740993,12,9,0),(3,13,10,1)");
        pin = MariaDbSourceGeneration.initialize(admin, metadataSchema);
        sql.execute("CREATE USER '" + reader + "'@'%' IDENTIFIED BY '" + password + "'");
        sql.execute("GRANT SELECT ON " + schema + ".* TO '" + reader + "'@'%'");
        sql.execute("GRANT SELECT ON " + metadataSchema + ".* TO '" + reader + "'@'%'");
        sql.execute("GRANT PROCESS ON *.* TO '" + reader + "'@'%'");
        activate(pin);
    }

    @AfterEach void cleanup() throws Exception {
        try {
            if (sql != null) {
                sql.execute("DROP DATABASE IF EXISTS " + schema);
                sql.execute("DROP DATABASE IF EXISTS " + metadataSchema);
                sql.execute("DROP USER IF EXISTS '" + reader + "'@'%'");
            }
        } finally { if (sql != null) sql.close(); if (admin != null) admin.close(); }
    }

    @ParameterizedTest @CsvSource({"false,ALL", "false,ORG", "true,ALL", "true,ORG"})
    void realTaskRepairsAreComparableButSameCountReplacementIsNot(boolean composite, String scope) throws Exception {
        var before = run(10, composite); assertEquals("FAIL", before.audit().getResultType());
        sql.execute("UPDATE " + table() + " SET amount=0");
        var after = run(20, composite); assertEquals("PASS", after.audit().getResultType());
        var result = compare(before, after, scope);
        assertTrue(result.comparable(), result.reason()); assertEquals("PASS", result.resultType());
        var read = JsonUtils.parseObject(after.audit().getReadEvidence(), QualityReadEvidence.class);
        assertEquals("FULL_PREDICATE", read.getCoverage()); assertTrue(read.getKeyScope().getComplete());
        assertEquals(read.getRelationFingerprint(), JsonUtils.parseObject(JsonUtils.toJsonString(read), QualityReadEvidence.class).getRelationFingerprint());
        assertFalse(after.audit().getReadEvidence().contains(pin.instanceId()));
        assertFalse(after.audit().getReadEvidence().contains(pin.generation()));
        sql.execute("UPDATE " + table() + " SET id=9007199254740994 WHERE id=9007199254740992");
        assertReason("ROW_POPULATION_CHANGED", compare(before, run(21, composite), scope));
        assertFalse(admin.isClosed());
    }

    @Test void tuplePairingAndInstitutionMovesCannotReuseCoverage() throws Exception {
        var before = run(10, true);
        sql.execute("UPDATE " + table() + " SET amount=0,part=CASE WHEN part=11 THEN 12 WHEN part=12 THEN 11 ELSE part END");
        assertReason("ROW_POPULATION_CHANGED", compare(before, run(20, true), "ALL"));
        sql.execute("UPDATE " + table() + " SET part=CASE WHEN part=11 THEN 12 WHEN part=12 THEN 11 ELSE part END,"
                + "org_code=CASE WHEN id=9007199254740992 THEN 10 WHEN id=3 THEN 9 ELSE org_code END");
        assertReason("ROW_POPULATION_CHANGED", compare(before, run(21, true), "ORG"));
    }

    @Test void fenceStopsFreshReadsAndRotationInvalidatesOldNodeAndOldBaseline() throws Exception {
        var before = run(10, true);
        sql.execute("UPDATE " + table() + " SET amount=0");
        var oldResolver = resolver; var oldRoute = route;
        MariaDbSourceGeneration.fence(admin, pin);
        assertEquals("ERROR", execute(20, true).audit().getResultType());
        var updated = MariaDbSourceGeneration.rotate(admin, pin, UUID.randomUUID().toString());
        assertEquals("ERROR", execute(21, true).audit().getResultType());
        activate(updated);
        assertNotEquals(oldRoute.getConfigurationRevision(), route.getConfigurationRevision());
        assertThrows(IllegalArgumentException.class, () -> resolver.createDataSource(oldRoute));
        assertThrows(IllegalArgumentException.class, () -> oldResolver.createDataSource(route));
        var after = run(22, true);
        assertReason("DATASOURCE_REVISION_CHANGED", compare(before, after, "ALL"));
        assertReason("DATASOURCE_REVISION_CHANGED", compare(before, after, "ORG"));
    }

    @Test void missingConfigurationStaysUnprovenAndTruncateChangesPhysicalIdentity() throws Exception {
        var before = run(10, true);
        activate(null); var unbound = run(20, true);
        assertEquals("RELATION_UNPROVEN", JsonUtils.parseObject(unbound.audit().getReadEvidence(), QualityReadEvidence.class).getCoverage());
        assertFalse(compare(before, unbound, "ALL").comparable());
        activate(pin);
        sql.execute("TRUNCATE TABLE " + table());
        sql.execute("INSERT INTO " + table() + " VALUES(9007199254740992,11,9,0),(9007199254740993,12,9,0),(3,13,10,1)");
        var after = run(21, true);
        assertFalse(compare(before, after, "ALL").comparable()); assertFalse(compare(before, after, "ORG").comparable());
    }

    @Test void nonNullDialectDoesNotAuthorizeOtherRulesOrForeignOrganizationCodecs() throws Exception {
        var before = run(10, true); sql.execute("UPDATE " + table() + " SET amount=0"); var after = run(20, true);
        var read = JsonUtils.parseObject(after.audit().getReadEvidence(), QualityReadEvidence.class);
        read.getOrgScope().setGroupEncoding(cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot.MYSQL_INTEGER_GROUP_ENCODING);
        after.audit().setReadEvidence(JsonUtils.toJsonString(read));
        assertReason("ORG_EVIDENCE_INCOMPLETE", compare(before, after, "ORG"));
        for (var run : List.of(before, after)) {
            var definition = JsonUtils.parseObject(run.instance().getDefinitionSnapshot(), QualityTaskExecutor.GateDefinition.class);
            definition.getRules().getFirst().setStrategyKey("LENGTH_VALIDATION");
            run.instance().setDefinitionSnapshot(JsonUtils.toJsonString(definition));
        }
        assertReason("RULE_DIALECT_UNVERIFIED", compare(before, after, "ALL"));
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void nativeNonNullSemanticsDistinguishEmptyWhitespaceAndZero(boolean text) throws Exception {
        sql.execute("TRUNCATE TABLE " + table());
        sql.execute("ALTER TABLE " + table() + " MODIFY amount "
                + (text ? "VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci" : "DECIMAL(20,2)"));
        sql.execute("INSERT INTO " + table() + " VALUES(1,11,9,NULL),(2,12,9,0),(3,13,9,1)"
                + (text ? ",(4,14,9,''),(5,15,9,' '),(6,16,9,'中文')" : ""));
        var result = run(20, false);
        assertEquals(text ? 6L : 3L, result.audit().getTotalCount());
        assertEquals(text ? 2L : 1L, result.audit().getFailCount());
        var context = new cn.iocoder.yudao.framework.quality.core.audit.AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover());
        context.setProductType(ProductTypeEnum.MARIADB); context.setTableName(table()); context.setColumn("amount");
        context.setConfig(Map.of("allowBlank", true));
        var snapshot = cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot.read(admin,
                new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION"), context, "id", 10, 10);
        assertTrue(snapshot.complete()); assertEquals(1L, snapshot.fail());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void fenceDuringCountOrRotateDuringOrganizationReadCannotProduceUsableEvidence(boolean organization) throws Exception {
        var triggered = new AtomicBoolean();
        try (var connection = runtime()) {
            var observed = observe(connection, query -> {
                if (query.contains("AS `keyValue`") && query.contains("AS `scopeValue`") == organization
                        && triggered.compareAndSet(false, true)) {
                    MariaDbSourceGeneration.fence(admin, pin);
                    if (organization) MariaDbSourceGeneration.rotate(admin, pin, UUID.randomUUID().toString());
                }
            });
            var result = execute(20, false, observed, false);
            assertTrue(triggered.get(), "interleave a real quality query, not a metadata query");
            assertEquals("ERROR", result.audit().getResultType());
            var read = JsonUtils.parseObject(result.audit().getReadEvidence(), QualityReadEvidence.class);
            assertTrue(read == null || !"FULL_PREDICATE".equals(read.getCoverage()));
            assertFalse(connection.isClosed());
        }
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"connect", "identity", "detail"})
    void sourceExceptionsCannotLeakThroughConnectionReadOrDetailLogs(String stage) throws Exception {
        String secret = "fixture-private-account@fixture-endpoint password=fixture-secret";
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(QualityTaskExecutor.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try (var connection = runtime()) {
            Run result;
            if ("connect".equals(stage)) {
                resolver = spy(resolver);
                doThrow(new IllegalStateException(new SQLException(secret, "42000", 1142)))
                        .when(resolver).createDataSource(route);
                result = execute(20, false);
            } else if ("identity".equals(stage)) {
                var observed = spy(connection);
                doThrow(new SQLException(secret, "42000", 1142)).when(observed).prepareStatement(anyString());
                result = execute(20, false, observed, false);
            } else {
                var triggered = new AtomicBoolean();
                var observed = observe(connection, query -> {
                    if (query.endsWith(") t__pk")) {
                        triggered.set(true); throw new SQLException(secret, "42000", 1142);
                    }
                });
                result = execute(20, false, observed, true);
                assertTrue(triggered.get()); assertEquals("FAIL", result.audit().getResultType());
            }
            if (!"detail".equals(stage)) {
                assertEquals("ERROR", result.audit().getResultType());
                assertTrue(result.audit().getErrorDetail().contains("SQLState=42000, vendorCode=1142"));
                assertFalse(result.audit().getErrorDetail().contains(secret));
            }
            assertFalse(appender.list.isEmpty());
            for (var event : appender.list) {
                assertFalse(event.getFormattedMessage().contains(secret));
                assertNull(event.getThrowableProxy());
            }
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @FunctionalInterface private interface QueryHook { void before(String sql) throws SQLException; }
    private Connection observe(Connection connection, QueryHook hook) throws SQLException {
        var observed = spy(connection);
        doAnswer(call -> {
            var statement = spy(connection.createStatement());
            doAnswer(query -> { hook.before(query.getArgument(0)); return query.callRealMethod(); })
                    .when(statement).executeQuery(anyString());
            return statement;
        }).when(observed).createStatement();
        return observed;
    }
    private Connection runtime() throws SQLException {
        return DriverManager.getConnection(ConfiguredSourceGenerations.managedJdbcUrl(url()), reader, password);
    }

    private void activate(SourceGenerationPin binding) {
        var config = new DbSwitchProperties.SourceGeneration();
        if (binding != null) {
            config.setProduct(ProductTypeEnum.MARIADB); config.setRestoreRotationRequired(true);
            config.setTargetFingerprint(ConfiguredSourceGenerations.targetFingerprint(ProductTypeEnum.MARIADB, url(), reader));
            config.setSchema(binding.schema()); config.setInstanceId(binding.instanceId()); config.setGeneration(binding.generation());
        }
        resolver = new MasterDatabaseResolver();
        var dto = new DatabaseConnectionRespDTO();
        dto.setId(4L); dto.setProductType("MARIADB"); dto.setJdbcUrl(url()); dto.setUsername(reader); dto.setPassword(password);
        dto.setSchema(schema); dto.setUsageType(1); dto.setConfigurationRevision("fixture-configuration-v1");
        var api = mock(DatabaseApi.class); when(api.getDatabaseConnection(4L)).thenReturn(CommonResult.success(dto));
        ReflectionTestUtils.setField(resolver, "databaseApi", api);
        ReflectionTestUtils.setField(resolver, "configuredSourceGenerations",
                new ConfiguredSourceGenerations(binding == null ? Map.of() : Map.of("fixture", config)));
        route = resolver.resolve(4L, cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_CHECK_OBJECT_NOT_EXISTS);
        assertFalse(JsonUtils.toJsonString(route).contains("sourceGenerationPin"));
        assertFalse(route.toString().contains("sourceGenerationPin"));
    }

    private Run run(long id, boolean composite) {
        var result = execute(id, composite);
        assertNotEquals("ERROR", result.audit().getResultType(), result.audit().getErrorDetail());
        return result;
    }
    private Run execute(long id, boolean composite) {
        return execute(id, composite, null, false);
    }
    private Run execute(long id, boolean composite, Connection observed, boolean collect) {
        var task = QualityTaskDO.builder().id(1L).orgField("org_code").build();
        var object = QualityCheckObjectDO.builder().id(3L).datasourceConnId(4L).datasourceType("THIRD_PARTY").tableName(table()).build();
        var rule = QualityRuleDO.builder().id(2L).status(0).strategyKey("NOT_NULL_VALIDATION").checkLevel("FIELD")
                .paramSchema(composite ? "{\"column\":\"amount\",\"pkColumns\":[\"id\",\"part\"],\"allowBlank\":false}"
                        : "{\"column\":\"amount\",\"pkColumn\":\"id\",\"allowBlank\":false}")
                .collectIssueDetail(collect ? 1 : 0).build();
        var definition = new QualityTaskExecutor.GateDefinition();
        definition.setTask(task); definition.setRules(List.of(rule)); definition.setObjects(List.of(object));
        var instance = QualityTaskInstanceDO.builder().id(id).taskId(1L).instanceStatus("SUCCESS")
                .definitionSnapshot(JsonUtils.toJsonString(definition)).startTime(LocalDateTime.now()).build();
        var executor = QualityRuntimeFixture.executor();
        ReflectionTestUtils.setField(executor, "auditSqlRegistry", new AuditSqlRegistry());
        ReflectionTestUtils.setField(executor, "auditLogMapper", mock(QualityAuditLogMapper.class));
        ReflectionTestUtils.setField(executor, "issueDataMapper", mock(QualityIssueDataMapper.class));
        ReflectionTestUtils.setField(executor, "databaseResolver", resolver);
        List<QualityAuditLogDO> logs = new ArrayList<>();
        if (observed == null) {
            QualityRuntimeFixture.executeObject(executor, id, task, route, object, List.of(rule), logs);
        } else {
            logs.add(QualityRuntimeFixture.executeRule(executor, id, task, observed, route, object, rule));
        }
        assertEquals(1, logs.size()); var audit = logs.getFirst(); audit.setId(id * 10);
        instance.setEndTime(LocalDateTime.now()); return new Run(instance, audit);
    }
    private record Run(QualityTaskInstanceDO instance, QualityAuditLogDO audit) { }
    private QualityRecheckEvidence.Comparison compare(Run before, Run after, String scope) {
        var issue = QualityIssueDataDO.builder().id(30L).taskId(1L).ruleId(2L).objectId(3L).instanceId(before.instance().getId())
                .auditLogId(before.audit().getId()).tableName(table()).checkField("amount").statScope(scope)
                .regionCode("ORG".equals(scope) ? "9" : null).issueCount(1L).build();
        return QualityRecheckEvidence.compare(QualityWorkOrderDO.builder().id(8L).taskId(1L).build(), issue,
                before.instance(), before.audit(), after.instance(), after.audit(), before.instance().getEndTime());
    }
    private static void assertReason(String reason, QualityRecheckEvidence.Comparison comparison) {
        assertFalse(comparison.comparable()); assertEquals(reason, comparison.evidence().get("reasonCode"));
    }
    private String table() { return schema + ".sample"; }
    private static String url() { return System.getProperty("datahub.test.mariadb.url"); }
}
