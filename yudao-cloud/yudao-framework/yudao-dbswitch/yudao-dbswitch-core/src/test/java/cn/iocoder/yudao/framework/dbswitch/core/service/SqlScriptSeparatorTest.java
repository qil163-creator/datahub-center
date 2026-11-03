package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlScriptSeparatorTest {

    @Test
    void semicolonDefaultPreservesQuotesCommentsAndPostgresBodies() {
        String script = "SELECT ';', \"semi;colon\"; /* ; */\nSELECT $body$BEGIN;\n/\nEND$body$; -- ; tail";
        var statements = SqlScriptPlan.splitStatements(ProductTypeEnum.POSTGRESQL, script, null);
        assertEquals(2, statements.size());
        assertTrue(statements.getFirst().contains("';'"));
        assertTrue(statements.getLast().contains("BEGIN;\n/\nEND"));
        assertEquals(statements, SqlScriptPlan.splitStatements(ProductTypeEnum.POSTGRESQL, script, ";"));
        assertThrows(UnsupportedOperationException.class, () -> statements.clear());
    }

    @Test
    void standaloneSlashPreservesProcedureBodiesAndArithmetic() {
        String procedure = "BEGIN\n  EXECUTE IMMEDIATE 'SELECT 8 / 2;';\n  /*\n/\n  */\n  NULL;\nEND;";
        String second = "CREATE TABLE fixture(id BIGINT);";
        String script = "-- development script\r\n" + procedure + "\r\n \t/ \t\r\n" + second + "\n/";
        var statements = SqlScriptPlan.splitStatements(ProductTypeEnum.DM, script, "/");
        assertEquals(List.of("-- development script\r\n" + procedure, second), statements);
        assertTrue(statements.getFirst().contains("/*\n/\n  */"));
        assertEquals(List.of("SELECT 8 / 2"), SqlScriptPlan.splitStatements(ProductTypeEnum.MYSQL, "SELECT 8 / 2\n/", "/"));
    }

    @Test
    void customMarkerInsideMultilineStringsCommentsAndDollarBodiesIsNotASplit() {
        String script = "SELECT 'first\n//\nlast', \"a\n//\nb\";\n//\n"
                + "/*\n//\n*/ SELECT $tag$first;\n//\nlast$tag$;\n//\n-- comment only\n";
        var statements = SqlScriptPlan.splitStatements(ProductTypeEnum.POSTGRESQL, script, "//");
        assertEquals(2, statements.size());
        assertTrue(statements.getFirst().contains("'first\n//\nlast'"));
        assertTrue(statements.getLast().contains("$tag$first;\n//\nlast$tag$"));
        assertEquals(2, SqlScriptPlan.splitStatements(ProductTypeEnum.MYSQL,
                "CREATE PROCEDURE fixture() BEGIN SELECT 1; END\n$$\nSELECT 2\n$$", "$$").size());
        assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.splitStatements(ProductTypeEnum.POSTGRESQL,
                "DO\n$$\nBEGIN PERFORM 1; END\n$$;", "$$"));
    }

    @Test
    void explicitPlanKeepsOldAdmissionAndDigest() {
        String script = "SELECT 1;\n/\nINSERT INTO fixture VALUES (?);\n/";
        var plan = SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, script, "/");
        assertEquals(2, plan.steps().size());
        assertEquals(1, plan.steps().getLast().parameterCount());
        assertEquals(SqlScriptPlan.digest(script), plan.digest());
        assertEquals(SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, "SELECT 1;").steps(),
                SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, "SELECT 1;", ";").steps());
        assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL,
                "DO $$BEGIN PERFORM 1; END$$;\n/", "/"));
        assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL,
                "SELECT 1; SELECT 2;\n/", "/"));
    }

    @Test
    void readerIsNotClosedAndFailuresNeverExposeInput() {
        class CallerReader extends StringReader {
            boolean closed;
            CallerReader(String text) { super(text); }
            @Override public void close() { closed = true; super.close(); }
        }
        var reader = new CallerReader("SELECT 1\n/\nSELECT 2\n/");
        assertEquals(2, SqlScriptPlan.splitStatements(ProductTypeEnum.ORACLE, reader, "/").size());
        assertFalse(reader.closed);
        var failure = assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.splitStatements(
                ProductTypeEnum.POSTGRESQL, "SELECT 'fixture_secret\n/", "/"));
        assertFalse(failure.toString().contains("fixture_secret"));
        assertNull(failure.getCause());
        var brokenReader = new StringReader("SELECT 1") {
            @Override public int read() throws IOException { throw new IOException("fixture_secret"); }
            @Override public int read(char[] buffer, int offset, int length) throws IOException {
                throw new IOException("fixture_secret");
            }
        };
        assertFalse(assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.splitStatements(
                ProductTypeEnum.POSTGRESQL, brokenReader, "/")).toString().contains("fixture_secret"));
    }

    @Test
    void budgetsAndUnsupportedDirectivesRemainClosedBeforeExecution() {
        for (String script : List.of("SELECT 'unfinished", "SELECT (1", "SELECT 1 /* unfinished",
                "DELIMITER $$\nSELECT 1\n/", "SELECT 1\nGO", "SELECT 1; /*! SET autocommit=0 */")) {
            assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.splitStatements(ProductTypeEnum.MYSQL, script, "/"));
        }
        assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.splitStatements(ProductTypeEnum.POSTGRESQL,
                "SELECT 1\n/\n".repeat(501), "/"));
        assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.splitStatements(ProductTypeEnum.DM,
                "BEGIN\n" + "NULL;\n".repeat(5000) + "END;\n/", "/"));
        assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.splitStatements(ProductTypeEnum.POSTGRESQL,
                "SELECT (" + "a;".repeat(13000) + ")", ";"));
        assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.splitStatements(ProductTypeEnum.POSTGRESQL,
                ("SELECT '" + "x".repeat(20_000) + "'\n/\n").repeat(5), "/"));
        for (String separator : List.of("GO", "a".repeat(17), " ".repeat(17), " /", "--", "/*", "#", "';'", ";/")) {
            assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.splitStatements(ProductTypeEnum.DM, "SELECT 1", separator));
        }
        assertThrows(UnsupportedOperationException.class, () -> SqlScriptPlan.splitStatements(ProductTypeEnum.MONGODB, "SELECT 1", "/"));
    }
}
