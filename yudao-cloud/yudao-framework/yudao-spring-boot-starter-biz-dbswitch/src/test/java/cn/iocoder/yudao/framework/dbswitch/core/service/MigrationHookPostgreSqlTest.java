package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class MigrationHookPostgreSqlTest {
    @Test void establishedIdentitySequenceAndExactDollarLiteralsExecuteOnTheSameOwnedTransaction() throws Exception {
        String table = "hook_" + UUID.randomUUID().toString().replace("-", "");
        String sequence = table + "_seq";
        String value = "slash\\'; literal; ? @ :bind $other$ 中文";
        String script = "CREATE SEQUENCE public." + sequence + " AS bigint START WITH 5 INCREMENT BY 3"
                + " MINVALUE 1 MAXVALUE 9223372036854775807 CACHE 1 NO CYCLE;"
                + "ALTER SEQUENCE public." + sequence + " RESTART WITH 11;"
                + "CREATE TABLE " + table + "(id bigint GENERATED ALWAYS AS IDENTITY (START WITH 5 INCREMENT BY 3) PRIMARY KEY,"
                + " note text COLLATE \"C\");"
                + "INSERT INTO " + table + "(note) VALUES($literal$" + value + "$literal$);"
                + "CREATE INDEX " + table + "_ix ON " + table + "(note);"
                + "ALTER TABLE " + table + " ADD extra integer;"
                + "SELECT id,note,nextval('" + sequence + "') AS external_id FROM " + table;
        assertEquals(SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, script).steps().size(),
                SqlScriptPlan.parseMigrationHook(ProductTypeEnum.POSTGRESQL, script).steps().size());
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                var result = DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL).executePlan(connection,
                        SqlScriptPlan.parseMigrationHook(ProductTypeEnum.POSTGRESQL, script), List.of(), 10, null, ignored -> { });
                assertTrue(result.getSuccess(), result::getErrorMsg);
                assertFalse(connection.isClosed());
                assertFalse(connection.getAutoCommit());
                var row = result.getResults().getLast().getRowData().getFirst();
                assertEquals(value, row.get("note"));
                assertEquals("5", String.valueOf(row.get("id")));
                assertEquals("11", String.valueOf(row.get("external_id")));
            } finally { connection.rollback(); }
        }
        try (var connection = open(); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT to_regclass('" + table + "'), to_regclass('" + sequence + "')")) {
            assertTrue(rows.next());
            assertNull(rows.getObject(1));
            assertNull(rows.getObject(2));
        }
    }

    @Test void invalidLaterStatementDoesNotExecuteEarlierDdl() throws Exception {
        String table = "hook_rejected_" + UUID.randomUUID().toString().replace("-", "");
        assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.parseMigrationHook(ProductTypeEnum.POSTGRESQL,
                "CREATE TABLE " + table + "(id int); INSERT INTO " + table + " VALUES(1); COMMIT;"));
        try (var connection = open(); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT to_regclass('" + table + "')")) {
            assertTrue(rows.next());
            assertNull(rows.getObject(1));
        }
    }

    private Connection open() throws Exception {
        return DriverManager.getConnection(System.getProperty("datahub.test.postgres.url"),
                System.getProperty("datahub.test.postgres.user", "dataforge_test"), "");
    }
}
