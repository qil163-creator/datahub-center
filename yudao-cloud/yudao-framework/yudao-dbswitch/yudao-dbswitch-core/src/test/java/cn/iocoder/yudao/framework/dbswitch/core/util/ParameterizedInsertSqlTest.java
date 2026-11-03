package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.ManagedSchemaScriptPlan;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ParameterizedInsertSqlTest {
    private static final ProductTypeEnum MYSQL = ProductTypeEnum.MYSQL;

    @Test void singleAndMultipleRowsKeepExactQuotedIdentifiersAndRowMajorBindings() {
        assertEquals("INSERT INTO `demo`.`items` (`id`,`name`) VALUES (?,?)",
                GenerateSqlUtils.getParameterizedInsertSql(MYSQL, "demo", "items", List.of("id", "name"), 1));
        String sql = GenerateSqlUtils.getParameterizedInsertSql(MYSQL, "demo", "a`b", List.of("顺序", "c`d"), 2);
        assertEquals("INSERT INTO `demo`.`a``b` (`顺序`,`c``d`) VALUES (?,?),(?,?)", sql);
        var step = ManagedSchemaScriptPlan.parseInsertOnly(MYSQL, "demo", List.of(sql)).steps().getFirst();
        assertEquals(List.of(0, 1, 2, 3), step.parameterOrder());
        assertEquals(java.util.Set.of("a`b"), step.objectNames());
    }

    @Test void valuesNeverEnterSqlAndInjectionShapedIdentifiersRemainSinglePhysicalNames() {
        String table = "x`; DROP TABLE secret; --";
        String sql = GenerateSqlUtils.getParameterizedInsertSql(MYSQL, "demo", table, List.of("c"), 2);
        var step = ManagedSchemaScriptPlan.parseInsertOnly(MYSQL, "demo", List.of(sql)).steps().getFirst();
        assertEquals(java.util.Set.of(table), step.objectNames());
        assertEquals(List.of(0, 1), step.parameterOrder());
        assertEquals(2, sql.chars().filter(ch -> ch == '?').count());
    }

    @Test void parameterAndSqlBudgetsAreCheckedBeforeBuildingUnboundedRows() {
        String boundary = GenerateSqlUtils.getParameterizedInsertSql(MYSQL, "demo", "items", List.of("id"), 5000);
        assertEquals(5000, boundary.chars().filter(ch -> ch == '?').count());
        assertTrue(boundary.length() <= 25000);
        assertThrows(IllegalArgumentException.class,
                () -> GenerateSqlUtils.getParameterizedInsertSql(MYSQL, "demo", "items", List.of("id"), 5001));
        assertThrows(IllegalArgumentException.class,
                () -> GenerateSqlUtils.getParameterizedInsertSql(MYSQL, "demo", "items", List.of("id"), Integer.MAX_VALUE));
        List<String> longColumns = IntStream.range(0, 400).mapToObj(i -> "f" + i + "x".repeat(60)).toList();
        assertThrows(IllegalArgumentException.class,
                () -> GenerateSqlUtils.getParameterizedInsertSql(MYSQL, "demo", "items", longColumns, 1));
    }

    @Test void rejectsEmptyInvalidDuplicateColumnsAndUnverifiedProducts() {
        for (List<String> columns : List.of(List.<String>of(), List.of("id", "ID"), Arrays.asList("id", null), List.of("a\0b"))) {
            assertThrows(IllegalArgumentException.class,
                    () -> GenerateSqlUtils.getParameterizedInsertSql(MYSQL, "demo", "items", columns, 1));
        }
        assertThrows(IllegalArgumentException.class,
                () -> GenerateSqlUtils.getParameterizedInsertSql(MYSQL, "demo", "items", List.of("id"), 0));
        assertThrows(IllegalArgumentException.class,
                () -> GenerateSqlUtils.getParameterizedInsertSql(MYSQL, "demo", "x".repeat(65), List.of("id"), 1));
        assertThrows(UnsupportedOperationException.class,
                () -> GenerateSqlUtils.getParameterizedInsertSql(ProductTypeEnum.MARIADB, "demo", "items", List.of("id"), 1));
    }
}
