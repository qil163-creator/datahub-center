package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlExecutionSummaryTest {
    @Test void preservesAuthorizedSqlAndPaginationBindingOrderAcrossVerifiedProducts() {
        for (var product : List.of(ProductTypeEnum.POSTGRESQL, ProductTypeEnum.MYSQL,
                ProductTypeEnum.MARIADB, ProductTypeEnum.OPENGAUSS)) {
            String input = "SELECT id FROM samples WHERE value = ? ORDER BY id LIMIT ? OFFSET ?";
            var summary = SqlExecutionSummary.countQuery(product, input);
            assertEquals(input, summary.authorizedSql());
            assertTrue(summary.sql().contains("(\n" + input + "\n)"));
            assertEquals(List.of(0, 1, 2), summary.parameterOrder());
            assertEquals(SqlStatementClassifier.Kind.QUERY, SqlStatementClassifier.classify(product, summary.sql()).kind());
        }
    }

    @Test void rejectsWriteSideEffectsAndUnsupportedSelectShapes() {
        for (String sql : List.of("DELETE FROM samples", "SELECT id INTO copy FROM samples", "SELECT id FROM samples FOR UPDATE",
                "SELECT id FROM samples; SELECT id FROM other_samples", "WITH a AS (SELECT id FROM samples) SELECT id FROM a",
                "SELECT a.id FROM samples a JOIN samples b ON a.id = b.id", "SELECT random() FROM samples")) {
            assertThrows(RuntimeException.class, () -> SqlExecutionSummary.countQuery(ProductTypeEnum.POSTGRESQL, sql));
        }
        assertThrows(UnsupportedOperationException.class,
                () -> SqlExecutionSummary.countQuery(ProductTypeEnum.ORACLE, "SELECT id FROM samples"));
    }

    @Test void terminalSemicolonAndLiteralQuestionMarkPreserveBindingsWhileCommentsStayRejected() {
        var summary = SqlExecutionSummary.countQuery(ProductTypeEnum.POSTGRESQL,
                "SELECT id FROM samples WHERE value = '?' AND id = ?;");
        assertEquals(List.of(0), summary.parameterOrder());
        assertFalse(summary.sql().contains("?;"));
        assertThrows(IllegalArgumentException.class, () -> SqlExecutionSummary.countQuery(ProductTypeEnum.POSTGRESQL,
                "SELECT id FROM samples WHERE id = ? -- last comment"));
    }
}
