package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import java.io.Reader;
import java.io.StringReader;
import static org.junit.jupiter.api.Assertions.*;

class SqlScriptReaderTest {
    @Test void nativeDollarStringsAndIdentifierTokensRoundTripWithoutChangingProjectionAdmission() {
        for(var product:java.util.List.of(ProductTypeEnum.POSTGRESQL,ProductTypeEnum.OPENGAUSS)) {
            String sql="SELECT $tag$slash\\'; literal; ? $other$ 中文$tag$::text AS \"a\"\"; b\"; SELECT ?";
            var plan=SqlScriptPlan.parse(product,sql);
            assertEquals(2,plan.steps().size());assertEquals(0,plan.steps().getFirst().parameterCount());
            assertEquals(1,plan.steps().getLast().parameterCount());
            assertTrue(plan.steps().getFirst().sql().contains("$tag$slash\\'; literal; ? $other$ 中文$tag$"));
            assertTrue(plan.steps().getFirst().sql().contains("\"a\"\"; b\""));
            assertEquals(plan.steps(),SqlScriptPlan.parse(product,plan.steps().stream().map(SqlScriptPlan.Step::sql).collect(java.util.stream.Collectors.joining(";"))).steps());
        }
        assertThrows(IllegalArgumentException.class,()->RestrictedSelectGuard.parseBound(ProductTypeEnum.POSTGRESQL,
                "SELECT $tag$text$tag$ FROM public.citizen",null));
    }
    @Test void everyReaderChunkProducesTheSamePlan() {
        String sql = "/* start */ insert into example(value) values ('left;right'); -- line\nupdate example set value='a'';b' where id=?;";
        SqlScriptPlan expected = SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, sql);
        for (int chunk = 1; chunk <= 17; chunk++) {
            int size = chunk;
            Reader reader = new StringReader(sql) {
                @Override public int read(char[] target, int offset, int length) throws java.io.IOException {
                    return super.read(target, offset, Math.min(size, length));
                }
            };
            SqlScriptPlan actual = SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, reader);
            assertEquals(expected.steps(), actual.steps());
            assertEquals(expected.digest(), actual.digest());
        }
        assertEquals(0, expected.steps().getFirst().parameterCount());
        assertEquals(1, expected.steps().getLast().parameterCount());
    }

    @Test void unfinishedTokensAndNestingNeverReachTheParser() {
        for (String sql : java.util.List.of("select 'unfinished", "select \"unfinished", "select 1 /* unfinished", "select (1", "select $tag$unfinished", "select $$$")) {
            assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, new StringReader(sql)));
        }
        assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, "select " + "(".repeat(65) + "1" + ")".repeat(65)));
        assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, "select '" + "a".repeat(25_000) + "'"));
    }

    @Test void directivesInsideStringLiteralsAreNotClientInstructions() {
        var plan = SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, "select 'DELIMITER ;' as value; select 'GO' as command;");
        assertEquals(2, plan.steps().size());
        assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.parse(ProductTypeEnum.MYSQL, "DELIMITER $$\nselect 1$$"));
    }

    @Test void statementCountAndWholeInputBudgetsApplyBeforeAnyExecution() {
        assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, "select 1;".repeat(501)));
        assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL,
                ("select '" + "a".repeat(20_000) + "';").repeat(5)));
        assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL,
                "select $" + "a".repeat(65) + "$value$" + "a".repeat(65) + "$"));
        assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.parse(ProductTypeEnum.MYSQL, "select 1; /*! delete from example */"));
    }

    @Test void nativeIdentityOptionsRoundTripExactlyAndRejectOverflowDuplicatesAndInvalidRanges() {
        String options = "START WITH 5 INCREMENT BY 3 MINVALUE -9223372036854775808 MAXVALUE 9223372036854775807 CACHE 7 NO CYCLE";
        var plan = SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, "create table example(id bigint generated always as identity (" + options + ") NOT NULL)");
        assertTrue(plan.steps().getFirst().sql().contains(options));
        assertEquals(plan.steps(), SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, plan.steps().getFirst().sql()).steps());
        for (String invalid : java.util.List.of("CACHE 0", "INCREMENT BY 0", "START WITH 1 START WITH 2",
                "MAXVALUE 9223372036854775808", "MINVALUE 10 MAXVALUE 1", "MINVALUE 1 START WITH 0", "CYCLE NO CYCLE")) {
            assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL,
                    "create table example(id bigint generated always as identity (" + invalid + "))"));
        }
        assertEquals(3, SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL,
                "insert into example(id,value) values (?,?) on conflict(id) do update set value=?").steps().getFirst().parameterCount());
    }
}
