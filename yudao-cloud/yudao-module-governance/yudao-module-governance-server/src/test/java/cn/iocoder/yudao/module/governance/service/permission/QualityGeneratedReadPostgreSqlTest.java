package cn.iocoder.yudao.module.governance.service.permission;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseColumnRespDTO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.task.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import cn.iocoder.yudao.module.governance.service.quality.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.DriverManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 真实 PG + 可信质量 SQL 生成器 + 有界 DB Switch 查询，验证物理引用与读取边界。 */
@EnabledIfSystemProperty(named="datahub.test.postgres.url", matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
class QualityGeneratedReadPostgreSqlTest {
    @Test void allTenProbesAndFrozenReadsUseTrustedGeneratorsAndRawValues() throws Exception {
        try (var fixture = new Fixture()) {
            for (var catalog : fixture.service.catalog()) {
                var input = rule(catalog);
                var request = fixture.probe(input);
                var result = fixture.service.spotCheck(request);
                assertEquals(4, result.problemRows().size() + result.normalRows().size(), catalog.strategyKey());
                assertFalse(result.problemRows().isEmpty(), catalog.strategyKey());
                assertFalse(result.normalRows().isEmpty(), catalog.strategyKey());
                assertTrue(result.problemRows().stream().noneMatch(row -> "***".equals(row.get("value"))), catalog.strategyKey());
                var definition = fixture.snapshot(input);
                var audit = QualityAuditLogDO.builder().evaluationId(30L).checkObjectId(1L).build();
                var full = fixture.service.readFrozenRows(definition, audit, 99L, 100);
                assertEquals(result.problemRows(), full.rows(), catalog.strategyKey());
                assertFalse(full.truncated());
                if (full.rows().size() > 1) {
                    var bounded = fixture.service.readFrozenRows(definition, audit, 99L, 1);
                    assertEquals(1, bounded.rows().size()); assertTrue(bounded.truncated());
                }
                assertEquals(0, fixture.runtime.queries().activeOperations());
            }
            verify(fixture.policyFixture.database, never()).executeQuery(any());
        }
    }

    @Test void referenceMustBeBoundAndReconstructedFromTheFrozenObject() throws Exception {
        try (var fixture = new Fixture()) {
            var input = rule(fixture.service.catalog().stream().filter(item -> item.ruleCode().equals("112")).findFirst().orElseThrow());
            var request = fixture.probe(input); request.setObjects(null);
            assertThrows(ServiceException.class, () -> fixture.service.spotCheck(request));
            request.setObjects(List.of(fixture.source, fixture.reference));
            input.setConfig(Map.of("refTable", "private_table", "refColumn", "ref"));
            assertThrows(ServiceException.class, () -> fixture.service.spotCheck(request));
            input.setConfig(Map.of("referenceObjectKey", "2", "refTable", "private_table", "refColumn", "ref", "ignoreNullValue", true));
            var definition = fixture.snapshot(input); var audit = QualityAuditLogDO.builder().evaluationId(30L).checkObjectId(1L).build();
            assertEquals(2, fixture.service.readFrozenRows(definition, audit, 99L, 100).rows().size()); // refTable 被冻结 key 重建
            verifyNoInteractions(fixture.policyFixture.tables, fixture.policyFixture.fields);
            assertEquals(0, fixture.runtime.queries().activeOperations());
        }
    }

    @Test void thousandRowsUseAnAdditionalProbeWithoutMisreportingExhaustion() throws Exception {
        try (var fixture = new Fixture(); var sql = fixture.control.createStatement()) {
            sql.execute("INSERT INTO " + fixture.schema + ".orders(id,value) SELECT n,'bad' FROM generate_series(5,1002) n");
            var input = rule(fixture.service.catalog().getFirst());
            var definition = fixture.snapshot(input); var audit = QualityAuditLogDO.builder().evaluationId(30L).checkObjectId(1L).build();
            var over = fixture.service.readFrozenRows(definition, audit, 99L, 1000);
            assertEquals(1000, over.rows().size()); assertTrue(over.truncated());
            sql.execute("DELETE FROM " + fixture.schema + ".orders WHERE id=1002");
            var exact = fixture.service.readFrozenRows(definition, audit, 99L, 1000);
            assertEquals(1000, exact.rows().size()); assertFalse(exact.truncated());
            assertEquals(0, fixture.runtime.queries().activeOperations());
        }
    }

    @Test void unqualifiedReferenceUsesConnectionDefaultSchemaRatherThanMainTableSchema() throws Exception {
        try (var fixture = new Fixture(); var sql = fixture.control.createStatement()) {
            String defaults = fixture.schema + "_default";
            sql.execute("CREATE SCHEMA " + defaults);
            try {
                sql.execute("CREATE TABLE " + defaults + ".reference(LIKE " + fixture.schema + ".orders)");
                sql.execute("INSERT INTO " + defaults + ".reference(ref) VALUES(20)");
                var route = SafetyPermissionTestFixture.route(ProductTypeEnum.POSTGRESQL, defaults, "r1");
                route.setJdbcUrl(System.getProperty("datahub.test.postgres.url"));
                route.setUsername(System.getProperty("datahub.test.postgres.user", "codex_test")); route.setPassword("");
                when(fixture.policyFixture.database.getDatabaseConnection(10L)).thenReturn(CommonResult.success(route));
                fixture.reference.setTableName("reference");
                var input = rule(fixture.service.catalog().stream().filter(item -> item.ruleCode().equals("112")).findFirst().orElseThrow());
                var result = fixture.service.spotCheck(fixture.probe(input));
                assertEquals(Set.of("1", "3"), result.problemRows().stream().map(row -> row.get("id")).collect(java.util.stream.Collectors.toSet()));
            } finally { sql.execute("DROP SCHEMA " + defaults + " CASCADE"); }
        }
    }

    @Test void unsafePostgresStringModeIsRejectedOnTheActualQuerySession() throws Exception {
        try (var fixture = new Fixture(); var sql = fixture.control.createStatement()) {
            sql.execute("SET standard_conforming_strings=off");
            assertThrows(java.sql.SQLException.class, () -> cn.iocoder.yudao.framework.quality.core.audit.AuditSqlDialect
                    .validateConnection(fixture.control, ProductTypeEnum.POSTGRESQL));
            sql.execute("SET standard_conforming_strings=on");
            var route = SafetyPermissionTestFixture.route(ProductTypeEnum.POSTGRESQL, fixture.schema, "r1");
            route.setJdbcUrl(System.getProperty("datahub.test.postgres.url") + "?options=-c%20standard_conforming_strings%3Doff");
            route.setUsername(System.getProperty("datahub.test.postgres.user", "codex_test")); route.setPassword("");
            when(fixture.policyFixture.database.getDatabaseConnection(10L)).thenReturn(CommonResult.success(route));
            var input = rule(fixture.service.catalog().getFirst());
            input.setConfig(Map.of("useRegex", true, "regex", "a\\' OR true --"));
            assertThrows(ServiceException.class, () -> fixture.service.spotCheck(fixture.probe(input)));
            var definition = fixture.snapshot(input); var audit = QualityAuditLogDO.builder().evaluationId(30L).checkObjectId(1L).build();
            assertThrows(ServiceException.class, () -> fixture.service.readFrozenRows(definition, audit, 99L, 100));
            assertEquals(0, fixture.runtime.queries().activeOperations());
        }
    }

    private static QualityTaskEvaluationSaveReqVO rule(QualityTaskEvaluationService.CatalogItem catalog) {
        var input = new QualityTaskEvaluationSaveReqVO(); input.setRuleCode(catalog.ruleCode()); input.setStrategyKey(catalog.strategyKey());
        input.setQualityDim(catalog.qualityDim()); input.setObjectKey("1"); input.setEvaluationName(catalog.ruleName());
        input.setColumnNames(switch (catalog.ruleCode()) {
            case "306", "110" -> List.of("amount"); case "205", "118" -> List.of("a", "b");
            case "115" -> List.of("t1", "t2"); case "112" -> List.of("ref"); default -> List.of("value");
        });
        input.setConfig(new LinkedHashMap<>(switch (catalog.ruleCode()) {
            case "107" -> Map.of("allowedTypes", List.of("digit"));
            case "306" -> Map.of("minValue", "1", "maxValue", "3", "ignoreNullValue", true);
            case "311" -> Map.of(); case "205" -> Map.of("fillStrategy", 1);
            case "115" -> Map.of("conditions", List.of(Map.of("leftField", "t1", "operator", "<=", "rightField", "t2")));
            case "118" -> Map.of("condition", "NOT_NULL"); case "108" -> Map.of("maxLength", 2);
            case "110" -> Map.of("scale", 2, "skipInteger", true, "ignoreNullValue", true);
            case "111" -> Map.of("validValues", List.of("123", "a2"), "ignoreCase", true);
            case "112" -> Map.of("referenceObjectKey", "2", "refColumn", "ref", "ignoreNullValue", true);
            default -> throw new AssertionError(catalog.ruleCode());
        }));
        return input;
    }

    private static class Fixture implements AutoCloseable {
        final String schema = "qualityread_" + UUID.randomUUID().toString().replace("-", "");
        final java.sql.Connection control;
        final DbSwitchRuntime runtime = DbSwitchRuntime.standalone();
        final SafetyPermissionTestFixture policyFixture = new SafetyPermissionTestFixture(ProductTypeEnum.POSTGRESQL, schema);
        final QualityTaskEvaluationService service = new QualityTaskEvaluationService();
        final QualityTaskObjectSaveReqVO source = object("1", "orders"), reference = object("2", "reference");
        final org.mockito.MockedStatic<SecurityFrameworkUtils> security = mockStatic(SecurityFrameworkUtils.class);
        Fixture() throws Exception {
            String url = System.getProperty("datahub.test.postgres.url"), user = System.getProperty("datahub.test.postgres.user", "codex_test");
            control = DriverManager.getConnection(url, user, "");
            try (var sql = control.createStatement()) {
                sql.execute("CREATE SCHEMA " + schema);
                sql.execute("CREATE TABLE " + schema + ".orders(id integer,value text,a text,b text,amount numeric,t1 timestamp,t2 timestamp,ref integer)");
                sql.execute("CREATE TABLE " + schema + ".reference(LIKE " + schema + ".orders)");
                sql.execute("INSERT INTO " + schema + ".reference(ref) VALUES(10)");
                sql.execute("INSERT INTO " + schema + ".orders VALUES(1,'123',NULL,NULL,1.23,'2026-01-01','2026-01-02',10),"
                        + "(2,'A2','x','y',2.345,'2026-01-03','2026-01-02',20),(3,'A2','x','y',9,'2026-01-01','2026-01-02',30),"
                        + "(4,'Z','z',NULL,NULL,NULL,NULL,NULL)");
            }
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(99L);
            var route = SafetyPermissionTestFixture.route(ProductTypeEnum.POSTGRESQL, schema, "r1");
            route.setJdbcUrl(url); route.setUsername(user); route.setPassword("");
            when(policyFixture.database.getDatabaseConnection(10L)).thenReturn(CommonResult.success(route));
            when(policyFixture.database.getQueryTableColumns(any())).thenReturn(CommonResult.success(List.of(
                    new DatabaseColumnRespDTO("id", "int4"), new DatabaseColumnRespDTO("value", "text"),
                    new DatabaseColumnRespDTO("a", "text"), new DatabaseColumnRespDTO("b", "text"),
                    new DatabaseColumnRespDTO("amount", "numeric"), new DatabaseColumnRespDTO("t1", "timestamp"),
                    new DatabaseColumnRespDTO("t2", "timestamp"), new DatabaseColumnRespDTO("ref", "int4"))));
            var resolver = new MasterDatabaseResolver(); ReflectionTestUtils.setField(resolver, "databaseApi", policyFixture.database);
            ReflectionTestUtils.setField(service, "resolver", resolver);
            ReflectionTestUtils.setField(service, "runtime", runtime); ReflectionTestUtils.setField(service, "registry", new AuditSqlRegistry());
        }
        QualityTaskObjectSaveReqVO object(String key, String table) {
            var value = new QualityTaskObjectSaveReqVO(); value.setId(Long.valueOf(key)); value.setClientKey(key);
            value.setDatasourceConnId(10L); value.setTableName(schema + "." + table); return value;
        }
        QualityTaskProbeReqVO probe(QualityTaskEvaluationSaveReqVO input) {
            var value = new QualityTaskProbeReqVO(); value.setObject(source); value.setObjects(List.of(source, reference)); value.setEvaluation(input); return value;
        }
        QualityTaskExecutor.GateDefinition snapshot(QualityTaskEvaluationSaveReqVO input) {
            var definition = new QualityTaskExecutor.GateDefinition();
            definition.setObjects(List.of(QualityCheckObjectDO.builder().id(1L).datasourceConnId(10L).tableName(source.getTableName()).build(),
                    QualityCheckObjectDO.builder().id(2L).datasourceConnId(10L).tableName(reference.getTableName()).build()));
            var params = QualityTaskEvaluationService.normalizedConfig(input); params.put("columns", input.getColumnNames());
            if (input.getConfig().get("referenceObjectKey") != null) params.put("referenceObjectKey", input.getConfig().get("referenceObjectKey"));
            definition.setRules(List.of(QualityRuleDO.builder().executionEvaluationId(30L).executionObjectId(1L).ruleCode(input.getRuleCode())
                    .strategyKey(input.getStrategyKey()).qualityDim(input.getQualityDim()).paramSchema(JsonUtils.toJsonString(params)).build()));
            return definition;
        }
        public void close() throws Exception {
            security.close(); runtime.close();
            try (var sql = control.createStatement()) { sql.execute("DROP SCHEMA " + schema + " CASCADE"); } finally { control.close(); }
        }
    }
}
