package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSqliteImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.DriverManager;
import java.sql.Types;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class DDLFormatterUtilsTest {
    static Stream<Arguments> protectedText() {
        return Stream.of(
                Arguments.of("CREATE TABLE \"a'b,(\" (id INTEGER)", "\"a'b,(\""),
                Arguments.of("CREATE TABLE t (\"a'\"\"b,(\" TEXT, id INTEGER)", "\"a'\"\"b,(\""),
                Arguments.of("CREATE TABLE `a'b,(` (`c``d\"'([,` TEXT, id INTEGER)", "`c``d\"'([,`"),
                Arguments.of("CREATE TABLE [a'b,(] ([c]]d\"'([,] TEXT, id INTEGER)", "[c]]d\"'([,]"),
                Arguments.of("CREATE TABLE t (note TEXT DEFAULT 'a\"[,(''尾,)', id INTEGER)", "'a\"[,(''尾,)'"),
                Arguments.of("CREATE TABLE t (note TEXT DEFAULT 'one\ntwo,(', id INTEGER)", "'one\ntwo,('"),
                Arguments.of("CREATE TABLE t (note TEXT DEFAULT N'a\"[,(''尾,)', id INTEGER)", "N'a\"[,(''尾,)'"),
                Arguments.of("ALTER TABLE \"a'b,(\" ADD \"drop' add,(\" VARCHAR(30)", "\"drop' add,(\""),
                Arguments.of("COMMENT ON COLUMN \"a'b,(\".\"c'd\" IS 'a\" is, (''原样)'", "'a\" is, (''原样)'"),
                Arguments.of("CREATE TABLE t (a INT, -- 'quoted,(\" stays in this comment\r\n b TEXT)", "-- 'quoted,(\" stays in this comment\r\n"),
                Arguments.of("CREATE TABLE t (a INT /* ' \" ) , [ ` */ , b TEXT)", "/* ' \" ) , [ ` */"),
                Arguments.of("CREATE TABLE t (a INT /*!80000 COMMENT 'a,(' */ , b TEXT)", "/*!80000 COMMENT 'a,(' */"));
    }

    @ParameterizedTest @MethodSource("protectedText")
    void quotedTokensAndCommentsRemainByteForByte(String sql, String protectedText) {
        String formatted = DDLFormatterUtils.format(sql);
        assertTrue(formatted.contains(protectedText), () -> "A quoted token or comment changed: " + formatted);
        assertEquals(formatted, DDLFormatterUtils.format(formatted));
    }

    @ParameterizedTest @ValueSource(strings = {
            "CREATE TABLE t (note TEXT DEFAULT $$a'(,\"$$, id INT)",
            "CREATE TABLE t (note TEXT DEFAULT $tag$a'(,\"$tag$, id INT)",
            "CREATE TABLE t (note TEXT DEFAULT q'[a'b,(]', id INT)",
            "CREATE TABLE t (note TEXT DEFAULT nq'{a'b,(}', id INT)",
            "CREATE TABLE t (note TEXT DEFAULT E'a\\'b,(', id INT)",
            "CREATE TABLE `a\\`b,(` (id INT)",
            "CREATE TABLE t (a TEXT[] DEFAULT ARRAY['a]b,(', 'c'])",
            "CREATE TABLE t (a TEXT, CHECK (a[1] = ']''['))",
            "CREATE TABLE t (a INT /* nested /* ,) */ quote' */ , b INT)",
            "CREATE TABLE t (a INT --x' dialect-dependent comment\n, b INT)",
            "CREATE TABLE t (a INT # dialect-dependent comment\n, b INT)",
            "CREATE TABLE t (\"unterminated, INTEGER)",
            "CREATE TABLE t (a INT /* unfinished comment",
            "CREATE TABLE t (a DECIMAL(10,2)",
            "CREATE TABLE t (a INT));",
            "CREATE TABLE t (a INT); INSERT INTO t VALUES (1)",
            "SELECT 'unrelated, SQL'"
    })
    void ambiguousOrIncompleteSyntaxIsReturnedUnchanged(String sql) {
        assertEquals(sql, DDLFormatterUtils.format(sql));
    }

    @Test void formatsOnlyOuterCreateDelimitersAndIsIdempotent() {
        String sql = "CREATE TABLE t(id DECIMAL(38,10),note VARCHAR(40),CHECK(id IN (1,2)))";
        String expected = "CREATE TABLE t(\n        id DECIMAL(38,10),\n        note VARCHAR(40),\n        CHECK(id IN (1,2))\n    )";
        assertEquals(expected, DDLFormatterUtils.format(sql));
        assertEquals(expected, DDLFormatterUtils.format(expected));
    }

    @Test void preservesLineCommentNewlineAndFollowingColumnInRealSqlite() throws Exception {
        Class.forName("org.sqlite.JDBC");
        String sql = "CREATE TABLE rows (a INTEGER, -- 'quoted,(\" stays here\r\n b TEXT DEFAULT 'x''y,(')";
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:"); var statement = connection.createStatement()) {
            statement.execute(DDLFormatterUtils.format(sql));
            statement.execute("INSERT INTO rows(a) VALUES(1)");
            try (var result = statement.executeQuery("SELECT b FROM rows")) {
                assertTrue(result.next()); assertEquals("x'y,(", result.getString(1));
            }
        }
    }

    @Test void generatedDdlCreatesTheExactMixedQuotePhysicalNames() throws Exception {
        Class.forName("org.sqlite.JDBC");
        String table = "a'b,(", field = "c'd,(";
        var column = new ColumnDescription(); column.setFieldName(field); column.setFieldType(Types.VARCHAR);
        column.setPrecisionSize(40); column.setNullable(true);
        String ddl = GenerateSqlUtils.getDDLCreateTableSQL(new DatabaseSqliteImpl(), List.of(column), List.of(), "main", table, false);
        assertTrue(ddl.contains('"' + table + '"')); assertTrue(ddl.contains('"' + field + '"'));
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:"); var statement = connection.createStatement()) {
            statement.execute(ddl);
            statement.execute("INSERT INTO \"" + table + "\"(\"" + field + "\") VALUES ('unchanged')");
            try (var result = statement.executeQuery("SELECT \"" + field + "\" FROM \"" + table + "\"")) {
                assertTrue(result.next()); assertEquals("unchanged", result.getString(1));
                assertEquals(field, result.getMetaData().getColumnName(1));
            }
        }
    }

    @Test void nullEmptyAndUnrelatedStatementsAreNotRewritten() {
        assertNull(DDLFormatterUtils.format(null));
        assertEquals("", DDLFormatterUtils.format(""));
        assertEquals("  \n", DDLFormatterUtils.format("  \n"));
        assertEquals("COMMENT ON TABLE t IS 'literal  spacing'", DDLFormatterUtils.format("COMMENT ON TABLE t IS 'literal  spacing'"));
    }
}
