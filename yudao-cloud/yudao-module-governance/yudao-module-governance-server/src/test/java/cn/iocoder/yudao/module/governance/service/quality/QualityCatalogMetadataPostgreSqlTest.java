package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityAuditLogMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证真实 PostgreSQL 元数据经过治理入口，而非把用户配置当成实际结构。 */
@Timeout(30)
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class QualityCatalogMetadataPostgreSqlTest {
    @Test void metadataCountsColumnsEvenWhenTheSourceHasNoRows() throws Exception {
        withTable((connection, table) -> {
            var service = QualityTaskEvaluationServiceTest.service();
            var input = QualityTaskEvaluationServiceTest.request().getEvaluations().getFirst();
            input.setColumnNames(List.of()); input.setStrategyKey("COLUMN_METADATA_COMPLETENESS");
            input.setConfig(Map.of("requiredProperties", List.of("comment")));
            var context = service.context(ProductTypeEnum.POSTGRESQL, table, input);
            var generator = new AuditSqlRegistry().getGenerator(input.getStrategyKey());
            service.populateMetadata(connection, context, generator);
            assertEquals(3, context.getColumnSnapshots().size());
            try (var statement = connection.createStatement(); var result = statement.executeQuery(generator.buildCountSql(context))) {
                assertTrue(result.next()); assertEquals(3, result.getLong("totalCount"));
                assertEquals(2, result.getLong("errorCount"));
            }
            try (var statement = connection.createStatement(); var result = statement.executeQuery(generator.buildErrorDataSql(context, 10))) {
                int errors = 0; while (result.next()) { assertNotNull(result.getString("columnName")); errors++; }
                assertEquals(2, errors);
            }
            input.setColumnNames(List.of("business name"));
            context = service.context(ProductTypeEnum.POSTGRESQL, table, input);
            service.populateMetadata(connection, context, generator);
            assertEquals(1, context.getColumnSnapshots().size());
            assertEquals("business name", context.getColumnSnapshots().getFirst().name());
            input.setColumnNames(List.of("not-a-column"));
            var missing = service.context(ProductTypeEnum.POSTGRESQL, table, input);
            assertThrows(IllegalArgumentException.class, () -> service.populateMetadata(connection, missing, generator));
        });
    }

    @Test void wholeRecordReallyExcludesTheDatabasePrimaryKey() throws Exception {
        withTable((connection, table) -> {
            try (var statement = connection.createStatement()) {
                statement.execute("INSERT INTO " + table + " VALUES(1,NULL,NULL),(2,'valid',NULL),(3,'',NULL)");
            }
            var service = QualityTaskEvaluationServiceTest.service();
            var input = QualityTaskEvaluationServiceTest.request().getEvaluations().getFirst();
            input.setColumnNames(List.of()); input.setStrategyKey("RECORD_COMPLETENESS_VALIDATION"); input.setConfig(Map.of());
            var context = service.context(ProductTypeEnum.POSTGRESQL, table, input);
            var generator = new AuditSqlRegistry().getGenerator(input.getStrategyKey());
            service.populateMetadata(connection, context, generator);
            assertEquals(List.of("\"business name\"", "\"amount\""), context.resolveColumns());
            try (var statement = connection.createStatement(); var result = statement.executeQuery(generator.buildCountSql(context))) {
                assertTrue(result.next()); assertEquals(3, result.getLong("totalCount")); assertEquals(2, result.getLong("errorCount"));
            }
        });
    }

    @Test void taskPersistsMetadataUnitAndNeverInventsRowKeyCoverage() throws Exception {
        withTable((connection, table) -> {
            var service = QualityTaskEvaluationServiceTest.service();
            var executor = QualityRuntimeFixture.executor();
            var mapper = mock(QualityAuditLogMapper.class);
            ReflectionTestUtils.setField(executor, "auditSqlRegistry", new AuditSqlRegistry());
            ReflectionTestUtils.setField(executor, "evaluationService", service);
            ReflectionTestUtils.setField(executor, "auditLogMapper", mapper);
            var route = new MasterDatabaseResolver.ConnectionInfo();
            route.setDatabaseId(4L); route.setProductType(ProductTypeEnum.POSTGRESQL);
            route.setUsageType(1); route.setConfigurationRevision("fixture");
            var rule = QualityRuleDO.builder().id(2L).strategyKey("MODEL_STRUCTURE_VALIDATION")
                    .paramSchema(JsonUtils.toJsonString(Map.of("expectedColumns", List.of(
                            Map.of("name", "id"), Map.of("name", "business name"), Map.of("name", "amount")))))
                    .collectIssueDetail(0).build();
            var audit = QualityRuntimeFixture.executeRule(executor, 10L,
                    QualityTaskDO.builder().id(1L).orgField("unrelated_org").build(), connection, route,
                    QualityCheckObjectDO.builder().id(3L).datasourceType("THIRD_PARTY").tableName(table).build(), rule);
            assertNotNull(audit); assertEquals(QualityAuditLogDO.RESULT_PASS, audit.getResultType(), audit.getErrorDetail());
            assertEquals(3L, audit.getTotalCount()); assertEquals(0L, audit.getFailCount());
            assertTrue(audit.getCheckField().contains("字段"));
            var evidence = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
            assertEquals("METADATA_COLUMNS", evidence.getCoverage());
            assertNull(evidence.getKeyScope()); assertNull(evidence.getOrgScope());
            verify(mapper).insert(audit);
            assertTrue(connection.getAutoCommit()); assertFalse(connection.isReadOnly());
        });
    }

    @Test void etlRecordCompletenessExcludesTrustedTechnicalColumnsButKeepsTheBatchScope() throws Exception {
        withTable((connection, table) -> {
            try (var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + table + " ADD COLUMN batch_key text, ADD COLUMN admission text");
                statement.execute("INSERT INTO " + table + " VALUES(1,NULL,NULL,'b','PASS'),(2,'valid',NULL,'b','PASS'),(3,NULL,NULL,'other','PASS')");
            }
            var executor = QualityRuntimeFixture.executor();
            ReflectionTestUtils.setField(executor, "auditSqlRegistry", new AuditSqlRegistry());
            ReflectionTestUtils.setField(executor, "evaluationService", QualityTaskEvaluationServiceTest.service());
            ReflectionTestUtils.setField(executor, "auditLogMapper", mock(QualityAuditLogMapper.class));
            var route = new MasterDatabaseResolver.ConnectionInfo();
            route.setDatabaseId(4L); route.setProductType(ProductTypeEnum.POSTGRESQL); route.setUsageType(1);
            route.setConfigurationRevision("fixture");
            var scope = new QualityTaskExecutor.EtlBatchScope();
            scope.setBatchColumn("batch_key"); scope.setBatchValue("b"); scope.setStatusColumn("admission");
            scope.setAdmittedStatuses(List.of("PASS")); scope.setAdmittedRowCount(2L);
            var rule = QualityRuleDO.builder().id(2L).strategyKey("RECORD_COMPLETENESS_VALIDATION")
                    .paramSchema("{}").collectIssueDetail(0).build();
            QualityAuditLogDO audit = ReflectionTestUtils.invokeMethod(executor, "executeRule", 10L,
                    QualityTaskDO.builder().id(1L).build(), connection, route,
                    QualityCheckObjectDO.builder().id(3L).datasourceType("THIRD_PARTY").tableName(table).build(), rule, scope);
            assertNotNull(audit); assertEquals(QualityAuditLogDO.RESULT_FAIL, audit.getResultType(), audit.getErrorDetail());
            assertEquals(2L, audit.getTotalCount()); assertEquals(1L, audit.getFailCount());
            assertFalse(audit.getCheckField().contains("batch_key")); assertFalse(audit.getCheckField().contains("admission"));
        });
    }

    @Test void organizationIssueQueryKeepsTheOriginalPopulationForIqrBoundaries() throws Exception {
        withTable((connection, table) -> {
            try (var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + table + " ADD COLUMN org text");
                statement.execute("INSERT INTO " + table + "(id,amount,org) SELECT n,n,'A' FROM generate_series(1,8) n");
                statement.execute("INSERT INTO " + table + "(id,amount,org) VALUES(9,1000,'B')");
            }
            var input = QualityTaskEvaluationServiceTest.request().getEvaluations().getFirst();
            input.setColumnNames(List.of("amount")); input.setStrategyKey("NUMERIC_OUTLIER_VALIDATION");
            input.setConfig(Map.of("method", "IQR"));
            var context = QualityTaskEvaluationServiceTest.service().context(ProductTypeEnum.POSTGRESQL, table, input);
            var generator = new AuditSqlRegistry().getGenerator(input.getStrategyKey());
            String sql = ReflectionTestUtils.invokeMethod(QualityRuntimeFixture.executor(), "orgIssueLocationSql",
                    generator, context, "org", "B", null);
            try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
                assertTrue(result.next()); assertEquals(9, result.getLong("id")); assertFalse(result.next());
            }
        });
    }

    @FunctionalInterface interface Fixture { void run(Connection connection, String table) throws Exception; }
    private void withTable(Fixture fixture) throws Exception {
        String schema = "quality_meta_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.postgres.url"),
                System.getProperty("datahub.test.postgres.user", "dataforge_test"), ""); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            String table = '"' + schema + "\".\"sample\"";
            try {
                statement.execute("CREATE TABLE " + table + "(id bigint PRIMARY KEY, \"business name\" text, amount numeric(18,4))");
                statement.execute("COMMENT ON COLUMN " + table + ".amount IS '业务金额'");
                fixture.run(connection, table);
            } finally { statement.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }
}
