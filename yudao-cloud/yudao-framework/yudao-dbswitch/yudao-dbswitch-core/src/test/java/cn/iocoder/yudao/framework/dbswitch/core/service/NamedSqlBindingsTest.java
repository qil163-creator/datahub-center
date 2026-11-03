package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NamedSqlBindingsTest {

    @Test
    void scalarBindingsRetainOccurrenceOrderAcrossAnAuthorizedRewrite() {
        var binding = NamedSqlBindings.compile(ProductTypeEnum.POSTGRESQL,
                "SELECT amount FROM fixture WHERE id=${id} AND amount BETWEEN ${min} AND ${max} OR id=${id}");
        assertEquals(List.of("id", "min", "max", "id"), binding.parameterNames());
        assertEquals("SELECT amount FROM fixture WHERE id=? AND amount BETWEEN ? AND ? OR id=?", binding.sql());
        String rewritten = "SELECT \"amount\" FROM \"authorized\".\"fixture\" WHERE \"id\"=? "
                + "AND \"amount\" BETWEEN ? AND ? OR \"id\"=?";
        assertEquals(rewritten.replaceFirst("\\?", "\\${id}").replaceFirst("\\?", "\\${min}")
                        .replaceFirst("\\?", "\\${max}").replaceFirst("\\?", "\\${id}"),
                binding.restore(ProductTypeEnum.POSTGRESQL, rewritten));
        assertThrows(UnsupportedOperationException.class, () -> binding.parameterNames().clear());
        assertFalse(binding.toString().contains("fixture"));
        assertEquals(4, SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, binding.sql()).steps().getFirst().parameterCount());
    }

    @Test
    void restoreDoesNotTouchQuestionMarksInQuotedAndCommentedText() {
        String sql = "SELECT '?' AS marker FROM fixture WHERE id=${id} /* ? */ -- ?\n";
        var binding = NamedSqlBindings.compile(ProductTypeEnum.MYSQL, sql);
        assertEquals(sql, binding.restore(ProductTypeEnum.MYSQL, binding.sql()));
        var postgres = NamedSqlBindings.compile(ProductTypeEnum.POSTGRESQL,
                "SELECT $body$?; $1$body$ FROM fixture WHERE id=${id}");
        assertEquals("SELECT $body$?; $1$body$ FROM fixture WHERE id=${id}",
                postgres.restore(ProductTypeEnum.POSTGRESQL, postgres.sql()));
    }

    @Test
    void quotedOrCommentedNamedParametersAndRawTemplatesFailClosed() {
        for (String sql : List.of("SELECT '${id}'", "SELECT \"${id}\"", "SELECT 1 /* ${id} */",
                "SELECT 1 -- ${id}\n", "SELECT $body$${id}$body$", "SELECT !{id}", "SELECT '!{id}'",
                "SELECT $[yyyyMMdd]", "SELECT '$[day]'", "SELECT ?", "SELECT $1", "SELECT ${bad name}",
                "SELECT ${}", "SELECT ${id", "SELECT ${" + "x".repeat(65) + "}")) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> NamedSqlBindings.compile(ProductTypeEnum.POSTGRESQL, sql));
            assertFalse(failure.toString().contains(sql));
            assertNull(failure.getCause());
        }
        assertThrows(IllegalArgumentException.class, () -> NamedSqlBindings.compile(ProductTypeEnum.SQLSERVER,
                "SELECT [${id}] FROM fixture"));
        assertThrows(IllegalArgumentException.class, () -> NamedSqlBindings.compile(ProductTypeEnum.MYSQL,
                "SELECT `${id}` FROM fixture"));
    }

    @Test
    void missingExtraOrUnknownRewriteBindingsAreRejected() {
        var binding = NamedSqlBindings.compile(ProductTypeEnum.POSTGRESQL, "SELECT ${id}");
        for (String rewrite : List.of("SELECT 1", "SELECT ?, ?", "SELECT $1", "SELECT ${id}", "SELECT '?'")) {
            assertThrows(IllegalArgumentException.class, () -> binding.restore(ProductTypeEnum.POSTGRESQL, rewrite));
        }
        assertEquals("SELECT 1", NamedSqlBindings.compile(ProductTypeEnum.POSTGRESQL, "SELECT 1")
                .restore(ProductTypeEnum.POSTGRESQL, "SELECT 1"));
    }

    @Test
    void actualProjectionAndRowFilterRewriteRetainsPaginationIdentity() {
        var binding = NamedSqlBindings.compile(ProductTypeEnum.POSTGRESQL,
                "SELECT id FROM fixture WHERE station=${station} ORDER BY id OFFSET ${offset} LIMIT ${limit}");
        String rewritten = RestrictedSelectGuard.restrictProjectionWithRowFilters(ProductTypeEnum.POSTGRESQL,
                binding.sql(), "authorized", "fixture", List.of("id", "station"), Set.of("id", "station"),
                List.of("id > 0")).sql();
        assertTrue(rewritten.indexOf("LIMIT") < rewritten.indexOf("OFFSET"), rewritten);
        String restored = binding.restore(ProductTypeEnum.POSTGRESQL, rewritten);
        assertTrue(restored.contains("LIMIT ${limit} OFFSET ${offset}"), restored);
        assertEquals(List.of("station", "limit", "offset"),
                NamedSqlBindings.compile(ProductTypeEnum.POSTGRESQL, restored).parameterNames());
        assertTrue(restored.contains("\"station\" = ${station}"), restored);
    }

    @Test
    void mysqlPaginationAndDistinctPredicateRolesSurviveFormatting() {
        var binding = NamedSqlBindings.compile(ProductTypeEnum.MYSQL,
                "SELECT id FROM fixture WHERE station=${station} LIMIT ${offset},${limit}");
        String rewritten = RestrictedSelectGuard.restrictProjectionWithRowFilters(ProductTypeEnum.MYSQL,
                binding.sql(), "authorized", "fixture", List.of("id", "station"), Set.of("id", "station"),
                List.of("id > 0")).sql();
        var restored = NamedSqlBindings.compile(ProductTypeEnum.MYSQL,
                binding.restore(ProductTypeEnum.MYSQL, rewritten));
        assertEquals(List.of("station", "offset", "limit"), restored.parameterNames());

        var predicates = NamedSqlBindings.compile(ProductTypeEnum.POSTGRESQL,
                "SELECT id FROM fixture WHERE id=${id} AND station=${station}");
        assertEquals("SELECT id FROM fixture WHERE station=${station} AND id=${id}",
                predicates.restore(ProductTypeEnum.POSTGRESQL,
                        "SELECT id FROM fixture WHERE station=? AND id=?"));
    }

    @Test
    void changedPredicateOrPaginationRolesFailEvenWithTheSameParameterCount() {
        var binding = NamedSqlBindings.compile(ProductTypeEnum.POSTGRESQL,
                "SELECT id FROM fixture WHERE id>${id} OFFSET ${offset} LIMIT ${limit}");
        for (String rewritten : List.of("SELECT id FROM fixture WHERE station>? OFFSET ? LIMIT ?",
                "SELECT id FROM fixture WHERE id=? OFFSET ? LIMIT ?",
                "SELECT id FROM fixture WHERE id>? AND station=? LIMIT ?",
                "SELECT id FROM fixture WHERE id>? OFFSET ? + 1 LIMIT ?")) {
            assertThrows(IllegalArgumentException.class,
                    () -> binding.restore(ProductTypeEnum.POSTGRESQL, rewritten));
        }
        var insert = NamedSqlBindings.compile(ProductTypeEnum.POSTGRESQL,
                "INSERT INTO fixture(id) VALUES(${id})");
        assertEquals("INSERT INTO fixture(id) VALUES(${id})", insert.restore(ProductTypeEnum.POSTGRESQL, insert.sql()));
        assertThrows(IllegalArgumentException.class,
                () -> insert.restore(ProductTypeEnum.POSTGRESQL, "INSERT INTO fixture (id) VALUES (?)"));
    }
}
