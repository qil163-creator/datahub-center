package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityAuditLogDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityCheckObjectDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityTaskDO;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** Real PostgreSQL execution of the same typed predicates frozen into ETL target-batch instances. */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class QualityRunServiceEtlBatchPostgreSqlTest {

    @Test
    void qualityAuditReadsOnlyExactBatchAndAdmittedPassWarnRows() throws Exception {
        String schema = "q_etl_batch_" + UUID.randomUUID().toString().replace("-", "");
        String batchA = "a".repeat(64);
        String batchB = "b".repeat(64);
        String url = System.getProperty("datahub.test.postgres.url");
        String user = System.getProperty("datahub.test.postgres.user", System.getProperty("datahub.test.postgres.user", "dataforge_test"));
        try (var connection = DriverManager.getConnection(url, user, ""); var sql = connection.createStatement()) {
            sql.setQueryTimeout(10);
            sql.execute("CREATE SCHEMA " + schema);
            try {
                connection.setSchema(schema);
                sql.execute("CREATE TABLE target (_etl_batch_key text NOT NULL, _etl_row_no integer NOT NULL, "
                        + "_quality_status text NOT NULL, id text)");
                try (var insert = connection.prepareStatement("INSERT INTO target VALUES(?,?,?,?)")) {
                    insert.setString(1, batchA); insert.setInt(2, 1); insert.setString(3, "PASS"); insert.setString(4, "1"); insert.executeUpdate();
                    insert.setString(1, batchA); insert.setInt(2, 2); insert.setString(3, "WARN"); insert.setString(4, "2"); insert.executeUpdate();
                    insert.setString(1, batchA); insert.setInt(2, 3); insert.setString(3, "REJECT"); insert.setString(4, null); insert.executeUpdate();
                    insert.setString(1, batchA); insert.setInt(2, 4); insert.setString(3, "ERROR"); insert.setString(4, null); insert.executeUpdate();
                    insert.setString(1, batchB); insert.setInt(2, 1); insert.setString(3, "PASS"); insert.setString(4, null); insert.executeUpdate();
                }

                QualityTaskExecutor executor = QualityRuntimeFixture.executor();
                ReflectionTestUtils.setField(executor, "auditSqlRegistry", new AuditSqlRegistry());
                ReflectionTestUtils.setField(executor, "auditLogMapper",
                        mock(cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityAuditLogMapper.class));
                MasterDatabaseResolver.ConnectionInfo route = new MasterDatabaseResolver.ConnectionInfo();
                route.setDatabaseId(60L);
                route.setProductType(ProductTypeEnum.POSTGRESQL);
                route.setConfigurationRevision("pg-etl-test");
                route.setSchema(schema);
                route.setUsageType(1);
                QualityTaskDO task = QualityTaskDO.builder().id(71L).retryCount(0).build();
                QualityCheckObjectDO object = QualityCheckObjectDO.builder().id(81L)
                        .datasourceConnId(60L).datasourceType("THIRD_PARTY").tableName("target").build();
                QualityRuleDO rule = QualityRuleDO.builder().id(91L).strategyKey("NOT_NULL_VALIDATION")
                        .checkLevel("FIELD").paramSchema("{\"column\":\"id\"}").collectIssueDetail(0).build();

                QualityAuditLogDO passAndWarn = execute(executor, connection, route, task, object, rule,
                        batchA, List.of("PASS", "WARN"), 2L, 901L);
                assertEquals(QualityAuditLogDO.RESULT_PASS, passAndWarn.getResultType(), passAndWarn.getErrorDetail());
                assertEquals(2L, passAndWarn.getTotalCount());
                assertEquals(0L, passAndWarn.getFailCount());
                assertTrue(passAndWarn.getResultSql().contains("_etl_batch_key"));
                assertTrue(passAndWarn.getResultSql().contains("_quality_status"));
                assertEquals(71L, passAndWarn.getTaskId());
                assertEquals(901L, passAndWarn.getInstanceId());

                QualityAuditLogDO passOnly = execute(executor, connection, route, task, object, rule,
                        batchA, List.of("PASS"), 1L, 902L);
                assertEquals(QualityAuditLogDO.RESULT_PASS, passOnly.getResultType(), passOnly.getErrorDetail());
                assertEquals(1L, passOnly.getTotalCount());
                assertEquals(0L, passOnly.getFailCount());

                QualityAuditLogDO wrongExpectedPopulation = execute(executor, connection, route, task, object, rule,
                        batchA, List.of("PASS", "WARN"), 3L, 903L);
                assertEquals(QualityAuditLogDO.RESULT_ERROR, wrongExpectedPopulation.getResultType());
                assertTrue(wrongExpectedPopulation.getErrorDetail().contains("准入行数"));
            } finally {
                sql.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static QualityAuditLogDO execute(QualityTaskExecutor executor, java.sql.Connection connection,
                                              MasterDatabaseResolver.ConnectionInfo route, QualityTaskDO task,
                                              QualityCheckObjectDO object, QualityRuleDO rule,
                                              String batchValue, List<String> statuses, long admitted,
                                              long instanceId) {
        QualityTaskExecutor.EtlBatchScope scope = new QualityTaskExecutor.EtlBatchScope();
        scope.setBatchColumn("_etl_batch_key");
        scope.setBatchValue(batchValue);
        scope.setStatusColumn("_quality_status");
        scope.setAdmittedStatuses(statuses);
        scope.setAdmittedRowCount(admitted);
        return ReflectionTestUtils.invokeMethod(executor, "executeRule", instanceId, task, connection,
                route, object, rule, scope);
    }
}
