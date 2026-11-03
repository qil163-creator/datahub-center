package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseGreenplumImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GreenplumDistributionPlanTest {
    @Test void retainsOriginalSqlAndResolvesQuotedPhysicalKeys() {
        String sql = "CREATE TABLE \"DISTRIBUTED BY; table\" (\"Id\" bigint, \"a\"\"b\" integer,"
                + " note text DEFAULT 'DISTRIBUTED BY (secret)', PRIMARY KEY(\"Id\",\"a\"\"b\"))"
                + " /* DISTRIBUTED BY (ignored) */ DISTRIBUTED BY (\"Id\",\"a\"\"b\")";
        var plan = SqlScriptPlan.parseMigrationHook(ProductTypeEnum.GREENPLUM, sql);
        assertEquals(1, plan.steps().size());
        assertEquals(sql, plan.steps().getFirst().sql());
        assertEquals(SqlStatementClassifier.Kind.DDL, plan.steps().getFirst().kind());
        assertThrows(UnsupportedOperationException.class, () -> SqlScriptPlan.parse(ProductTypeEnum.GREENPLUM, sql));
        assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {"()", "(missing)", "(\"ID\")", "(id,id)", "(p.id)", "(abs(id))", "(?)",
            "(id + 1)", "(id) DISTRIBUTED BY (id)", "(id) DROP TABLE other", "(id text_ops)", "(id,)"})
    void rejectsUnverifiedDistributionSyntax(String suffix) {
        var error = assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.parseMigrationHook(
                ProductTypeEnum.GREENPLUM, "CREATE TABLE p(id bigint) DISTRIBUTED BY " + suffix));
        assertNull(error.getCause());
    }

    @Test void generatedDistributionQuotesEveryPhysicalKey() {
        var sql = new StringBuilder("CREATE TABLE p(\"Id\" bigint, \"a\"\"b\" integer)");
        new DatabaseGreenplumImpl().postAppendCreateTableSql(sql, null, List.of("Id", "a\"b"), null, "Id");
        assertEquals("CREATE TABLE p(\"Id\" bigint, \"a\"\"b\" integer)\n DISTRIBUTED BY (\"Id\", \"a\"\"b\")", sql.toString());
    }
}
