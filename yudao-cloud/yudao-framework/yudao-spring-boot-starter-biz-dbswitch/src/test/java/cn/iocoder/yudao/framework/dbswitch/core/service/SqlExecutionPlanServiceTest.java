package cn.iocoder.yudao.framework.dbswitch.core.service;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SqlExecutionPlanServiceTest {
    @Test void rejectsWriteCteAndLockingSelectBeforeOpeningTheConfiguredConnection() {
        var opened = new java.util.concurrent.atomic.AtomicInteger();
        var service = new SqlExecutionPlanService(() -> { opened.incrementAndGet(); throw new java.sql.SQLException(); });
        for (var sql : java.util.List.of("WITH changed AS (DELETE FROM example RETURNING *) SELECT * FROM changed",
                "SELECT * FROM example FOR UPDATE", "SELECT * INTO new_table FROM example")) {
            assertThrows(IllegalArgumentException.class, () -> service.explain(ProductTypeEnum.POSTGRESQL, sql, 10, 10));
        }
        assertEquals(0, opened.get());
    }
    @Test void preservesSemicolonsInsideStrings() {
        String plan = SqlExecutionPlanService.planSql(ProductTypeEnum.POSTGRESQL, "select ';' as separator;");
        assertTrue(plan.startsWith("EXPLAIN (FORMAT TEXT)"));
        assertTrue(plan.contains("';'"));
        assertFalse(plan.endsWith(";"));
    }
    @Test void rejectsMultipleStatements() {
        assertThrows(IllegalArgumentException.class, () -> SqlExecutionPlanService.planSql(
                ProductTypeEnum.POSTGRESQL, "select 1; delete from example"));
    }
    @Test void rejectsExplicitAnalyzeAndWrites() {
        assertThrows(IllegalArgumentException.class, () -> SqlExecutionPlanService.planSql(
                ProductTypeEnum.POSTGRESQL, "explain analyze select 1"));
        assertThrows(IllegalArgumentException.class, () -> SqlExecutionPlanService.planSql(
                ProductTypeEnum.MYSQL, "update example set id = 1"));
    }
    @Test void acceptsAReadOnlyCte() {
        String plan = SqlExecutionPlanService.planSql(ProductTypeEnum.POSTGRESQL,
                "with items as (select 1 as id) select * from items");
        assertTrue(plan.contains("WITH"));
    }
    @Test void failsClosedForUnverifiedProduct() {
        assertThrows(UnsupportedOperationException.class, () -> SqlExecutionPlanService.planSql(
                ProductTypeEnum.SQLSERVER2000, "select 1 from dual"));
    }
    @Test void usesMysqlPlanSyntax() {
        assertTrue(SqlExecutionPlanService.planSql(ProductTypeEnum.MYSQL, "select 1").startsWith("EXPLAIN\n"));
    }
}
