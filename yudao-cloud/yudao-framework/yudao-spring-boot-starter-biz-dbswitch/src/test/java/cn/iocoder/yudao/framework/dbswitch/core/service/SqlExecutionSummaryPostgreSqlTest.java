package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class SqlExecutionSummaryPostgreSqlTest {
    @Test void authorizedPaginationReturnsOnlyTheCountAndKeepsNamedBindingIdentity() throws Exception {
        String table = "summary_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.postgres.url"),
                System.getProperty("datahub.test.postgres.user", "dataforge_test"), ""); var fixture = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                fixture.execute("CREATE TABLE " + table + "(id int, value text)");
                fixture.executeUpdate("INSERT INTO " + table + " VALUES(1,'private-first'),(2,'private-second'),(3,'private-third'),(4,'private-fourth')");
                var original = NamedSqlBindings.compile(ProductTypeEnum.POSTGRESQL,
                        "SELECT id, value FROM " + table + " WHERE id > ${minId} ORDER BY id OFFSET ${offset} LIMIT ${limit}");
                var rewritten = RestrictedSelectGuard.restrictProjectionWithRowFilters(ProductTypeEnum.POSTGRESQL,
                        original.sql(), "public", table, List.of("id", "value"), java.util.Set.of("id", "value"), List.of("id > 0")).sql();
                var authorized = NamedSqlBindings.compile(ProductTypeEnum.POSTGRESQL, original.restore(ProductTypeEnum.POSTGRESQL, rewritten));
                var summary = SqlExecutionSummary.countQuery(ProductTypeEnum.POSTGRESQL, authorized.sql());
                assertTrue(summary.sql().contains(authorized.sql()));
                var namedSummary = authorized.restoreOrdered(ProductTypeEnum.POSTGRESQL, summary.sql(), summary.parameterOrder());
                var executable = NamedSqlBindings.compile(ProductTypeEnum.POSTGRESQL, namedSummary);
                var values = Map.of("minId", 0, "offset", 1, "limit", 2);
                String shadow = "summary_shadow_" + UUID.randomUUID().toString().replace("-", "");
                fixture.execute("CREATE SCHEMA " + shadow);
                fixture.execute("CREATE FUNCTION " + shadow + ".count_transition(integer) RETURNS integer LANGUAGE SQL IMMUTABLE AS $$ SELECT $1 + 10 $$");
                fixture.execute("CREATE AGGREGATE " + shadow + ".count(*) (SFUNC=" + shadow + ".count_transition, STYPE=integer, INITCOND='0')");
                fixture.execute("SET LOCAL search_path TO " + shadow + ", pg_catalog, public");
                try (var query = connection.prepareStatement(executable.sql())) {
                    for (int i = 0; i < executable.parameterNames().size(); i++) {
                        query.setInt(i + 1, values.get(executable.parameterNames().get(i)));
                    }
                    try (var rows = query.executeQuery()) {
                        assertEquals(1, rows.getMetaData().getColumnCount());
                        assertEquals("execution_row_count", rows.getMetaData().getColumnLabel(1));
                        assertTrue(rows.next()); assertEquals(2, rows.getLong(1)); assertFalse(rows.next());
                    }
                }
            } finally { connection.rollback(); }
        }
    }
}
