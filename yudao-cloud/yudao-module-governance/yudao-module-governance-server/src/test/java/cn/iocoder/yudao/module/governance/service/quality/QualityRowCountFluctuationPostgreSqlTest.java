package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityAuditLogDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityCheckObjectDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityTaskDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityAuditLogMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityIssueDataMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 表行数波动检测在真实 PostgreSQL 上经执行器完整运行：统计带过滤条件的行数，按历史基准判定，结果计为一个检查项，
 * 写入指标值供下次比较，不产生问题行。
 */
@Testcontainers
@Timeout(120)
class QualityRowCountFluctuationPostgreSqlTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private Connection connection;
    private QualityTaskExecutor executor;
    private QualityAuditLogMapper auditLogs;
    private QualityIssueDataMapper issues;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS public.orders");
            statement.execute("CREATE TABLE public.orders(id bigint PRIMARY KEY, status text, biz_date date)");
            // 7 行：前 3 行已支付；前 5 行的业务日期是“昨天”（未注入实例时按检测日期前一天），其余是更早的日期
            statement.execute("INSERT INTO public.orders SELECT g, CASE WHEN g <= 3 THEN 'PAID' ELSE 'NEW' END, "
                    + "CASE WHEN g <= 5 THEN current_date - 1 ELSE current_date - 9 END FROM generate_series(1, 7) g");
        }
        executor = QualityRuntimeFixture.executor();
        auditLogs = mock(QualityAuditLogMapper.class);
        issues = mock(QualityIssueDataMapper.class);
        ReflectionTestUtils.setField(executor, "auditSqlRegistry", new AuditSqlRegistry());
        ReflectionTestUtils.setField(executor, "evaluationService", QualityTaskEvaluationServiceTest.service());
        ReflectionTestUtils.setField(executor, "auditLogMapper", auditLogs);
        ReflectionTestUtils.setField(executor, "issueDataMapper", issues);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    private QualityAuditLogDO run(Map<String, Object> params) {
        var route = new MasterDatabaseResolver.ConnectionInfo();
        route.setDatabaseId(4L);
        route.setProductType(ProductTypeEnum.POSTGRESQL);
        route.setUsageType(1);
        route.setConfigurationRevision("fixture");
        var rule = QualityRuleDO.builder().id(2L).strategyKey("ROW_COUNT_FLUCTUATION")
                .paramSchema(JsonUtils.toJsonString(params)).build();
        return QualityRuntimeFixture.executeRule(executor, 10L, QualityTaskDO.builder().id(1L).build(), connection,
                route, QualityCheckObjectDO.builder().id(3L).datasourceType("THIRD_PARTY").tableName("public.orders")
                        .build(), rule);
    }

    @Test
    void dropBeyondLimitFailsAsOneCheckItemAndRecordsRowCount() {
        when(auditLogs.selectMetricBaseline(eq(1L), eq(3L), eq("ROW_COUNT"), anyString(), any(),
                isNull(), isNull(), isNull())).thenReturn(QualityAuditLogDO.builder()
                .metricValue(BigDecimal.TEN).checkTime(LocalDateTime.of(2026, 11, 15, 2, 0)).build());

        QualityAuditLogDO audit = run(Map.of("maxDecreasePercent", "30", "where", "status = 'PAID'",
                "columns", List.of("status")));

        assertEquals(QualityAuditLogDO.RESULT_FAIL, audit.getResultType(), audit.getErrorDetail());
        assertEquals(1L, audit.getTotalCount());
        assertEquals(1L, audit.getFailCount());
        assertEquals("ROW_COUNT", audit.getMetricKey());
        assertEquals(0, BigDecimal.valueOf(3).compareTo(audit.getMetricValue()));
        assertEquals("3", audit.getIndexValue());
        assertEquals("表行数", audit.getCheckField());
        assertEquals(64, audit.getMetricScope().length());
        assertTrue(audit.getErrorDetail().contains("-70.00%") && audit.getErrorDetail().contains("降幅超过上限 30%"),
                audit.getErrorDetail());
        verify(auditLogs).insert(audit);
        verifyNoInteractions(issues);
    }

    @Test
    void bizdateVariableFiltersByBusinessDateAndKeepsTemplateScope() {
        QualityAuditLogDO first = run(Map.of("minRowCount", "1", "where", "biz_date = ${bizdate}",
                "columns", List.of("biz_date")));
        assertEquals(QualityAuditLogDO.RESULT_PASS, first.getResultType(), first.getErrorDetail());
        assertEquals("5", first.getIndexValue());
        assertEquals(LocalDateTime.now().toLocalDate().minusDays(1), first.getBizDate());
        assertTrue(first.getResultSql().contains("'" + first.getBizDate() + "'"), first.getResultSql());

        QualityAuditLogDO quoted = run(Map.of("minRowCount", "1", "where", "biz_date = '${bizdate}'",
                "columns", List.of("biz_date")));
        assertEquals("5", quoted.getIndexValue());

        QualityAuditLogDO unknown = run(Map.of("minRowCount", "1", "where", "biz_date = ${today}",
                "columns", List.of("biz_date")));
        assertEquals(QualityAuditLogDO.RESULT_ERROR, unknown.getResultType());
        assertTrue(unknown.getErrorDetail().contains("不支持的变量"), unknown.getErrorDetail());
    }

    @Test
    void dynamicThresholdUsesHistoryAndRecordsExpectedRange() {
        when(auditLogs.selectMetricHistory(eq(1L), eq(3L), eq("ROW_COUNT"), anyString(), any(),
                isNull(), eq(30))).thenReturn(List.of(BigDecimal.valueOf(20), BigDecimal.valueOf(21),
                BigDecimal.valueOf(19), BigDecimal.valueOf(20), BigDecimal.valueOf(22), BigDecimal.valueOf(20),
                BigDecimal.valueOf(18)));

        QualityAuditLogDO audit = run(Map.of("compareMode", "DYNAMIC"));

        assertEquals(QualityAuditLogDO.RESULT_FAIL, audit.getResultType(), audit.getErrorDetail());
        assertEquals("7", audit.getIndexValue());
        assertNotNull(audit.getMetricLower());
        assertNotNull(audit.getMetricUpper());
        assertTrue(audit.getMetricLower().compareTo(BigDecimal.valueOf(7)) > 0);
        assertTrue(audit.getErrorDetail().contains("低于动态阈值下限"), audit.getErrorDetail());
        verify(auditLogs, never()).selectMetricBaseline(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void firstRunWithoutBaselineOnlyRecordsAndChangedFilterUsesAnotherScope() {
        QualityAuditLogDO first = run(Map.of("maxDecreasePercent", "30"));
        assertEquals(QualityAuditLogDO.RESULT_SKIPPED, first.getResultType());
        assertEquals(0L, first.getTotalCount());
        assertEquals("7", first.getIndexValue());
        assertTrue(first.getErrorDetail().contains("本次只记录行数"));

        QualityAuditLogDO filtered = run(Map.of("maxDecreasePercent", "30", "where", "status = 'PAID'",
                "columns", List.of("status")));
        assertNotEquals(first.getMetricScope(), filtered.getMetricScope(), "过滤条件变化后口径不同，不与旧值比较");

        QualityAuditLogDO range = run(Map.of("minRowCount", "10"));
        assertEquals(QualityAuditLogDO.RESULT_FAIL, range.getResultType(), "没有历史时固定区间照样判定");
        assertTrue(range.getErrorDetail().contains("低于最少行数 10"));
    }

}
