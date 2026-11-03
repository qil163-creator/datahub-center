package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseDorisImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMysqlImpl;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Doris 服务端绑定与数值分页共用既有查询循环，其他产品保留原占位符契约。 */
class DorisPaginationTest {

    @Test void boundedDorisPagingUsesLongLiteralsAndKeepsBusinessPlaceholders() {
        var database = new DatabaseDorisImpl();
        var model = database.getPageModel("SELECT id FROM sample WHERE region=?", 3, 10);
        assertEquals("SELECT id FROM sample WHERE region=?\nLIMIT 10 OFFSET 20", model.getPageSql());
        assertEquals("SELECT COUNT(1) FROM (SELECT id FROM sample WHERE region=?\n) t", model.getPageCountSql());
        assertFalse(model.isBindPageParameters());
        assertTrue(database.getPageModel("SELECT id FROM sample", Integer.MAX_VALUE, Integer.MAX_VALUE)
                .getPageSql().endsWith("OFFSET 4611686011984936962"));
        assertThrows(IllegalArgumentException.class, () -> database.getPageModel("SELECT 1", 0, 10));
        assertThrows(IllegalArgumentException.class, () -> database.getPageModel("SELECT 1", 1, -1));
        assertThrows(IllegalArgumentException.class, () -> database.getPageModel("SELECT 1", null, 10));
    }

    @Test void singleTerminatorAndTailCommentsDoNotBreakPagingOrLiteralSemicolons() {
        var database = new DatabaseDorisImpl();
        var model = database.getPageModel("SELECT ';' AS marker; -- 尾注释", 1, 10);
        assertEquals("SELECT ';' AS marker\nLIMIT 10 OFFSET 0", model.getPageSql());
        assertEquals("SELECT COUNT(1) FROM (SELECT ';' AS marker\n) t", model.getPageCountSql());
        assertEquals("SELECT id FROM sample -- 尾注释\nLIMIT 10 OFFSET 0",
                database.getPageModel("SELECT id FROM sample -- 尾注释", 1, 10).getPageSql());
        assertThrows(IllegalArgumentException.class, () -> database.getPageModel("SELECT 1; SELECT 2", 1, 10));
    }

    @Test void existingTopLevelPaginationIsAnInnerResultWithoutReorderingBindings() {
        var database = new DatabaseDorisImpl();
        String original = "SELECT id FROM sample WHERE region=? ORDER BY id LIMIT 5 OFFSET 1";
        var model = database.getPageModel(original, 2, 2);
        assertEquals("SELECT * FROM (\n" + original + "\n) datahub_page\nLIMIT 2 OFFSET 2", model.getPageSql());
        assertEquals("SELECT COUNT(1) FROM (" + original + "\n) t", model.getPageCountSql());
        String nested = "SELECT id FROM (SELECT id FROM sample LIMIT 5) s ORDER BY id";
        assertEquals(nested + "\nLIMIT 2 OFFSET 2", database.getPageModel(nested, 2, 2).getPageSql());
        String cte = "WITH s AS (SELECT id FROM sample WHERE region=?) SELECT id FROM s ORDER BY id LIMIT 5";
        assertEquals("SELECT * FROM (\n" + cte + "\n) datahub_page\nLIMIT 2 OFFSET 2",
                database.getPageModel(cte, 2, 2).getPageSql());
        String union = "SELECT id FROM sample WHERE region=? UNION ALL SELECT id FROM sample WHERE region=? "
                + "ORDER BY id LIMIT 5";
        assertEquals("SELECT * FROM (\n" + union + "\n) datahub_page\nLIMIT 2 OFFSET 2",
                database.getPageModel(union, 2, 2).getPageSql());
        String literal = "SELECT 'LIMIT 5' AS label FROM sample";
        assertEquals(literal + "\nLIMIT 2 OFFSET 2", database.getPageModel(literal, 2, 2).getPageSql());
        for (String sql : List.of("SELECT id FROM sample LIMIT ?",
                "SELECT id FROM (SELECT id FROM sample LIMIT ?) s")) {
            var failure = assertThrows(IllegalArgumentException.class, () -> database.getPageModel(sql, 1, 10));
            assertTrue(failure.getMessage().contains("LIMIT/OFFSET"));
        }
    }

    @Test void dorisForcesServerStatementsAndDisablesClientFallbackWithoutLosingOtherOptions() {
        String url = new DatabaseDorisImpl().preparePageQueryJdbcUrl(
                "jdbc:mysql://host:9030/db?useServerPrepStmts=false&useServerPrepStmts=false"
                        + "&emulateUnsupportedPstmts=true&sslMode=VERIFY_IDENTITY");
        assertEquals("jdbc:mysql://host:9030/db?sslMode=VERIFY_IDENTITY"
                + "&emulateUnsupportedPstmts=false&useServerPrepStmts=true", url);
    }

    @Test void mysqlStillBindsPaginationAndKeepsTheCallerConnectionUrl() throws Exception {
        var mysql = new DatabaseMysqlImpl();
        String url = "jdbc:mysql://host/db?useServerPrepStmts=false";
        assertEquals(url, mysql.preparePageQueryJdbcUrl(url));
        assertTrue(mysql.getPageModel("SELECT id FROM sample WHERE region=?", 2, 10).isBindPageParameters());
        assertEquals("SELECT id FROM sample WHERE region=? LIMIT ? OFFSET ?",
                mysql.getPageModel("SELECT id FROM sample WHERE region=?", 2, 10).getPageSql());
        var statement = mock(PreparedStatement.class);
        mysql.bindPageParameters(statement, 1, 2, 10);
        verify(statement).setObject(2, 10);
        verify(statement).setObject(3, 10);
    }

    @Test void sharedQueryLoopBindsOnlyBusinessParametersForDoris() throws Exception {
        var connection = mock(Connection.class);
        var page = mock(PreparedStatement.class);
        var count = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        var totals = mock(ResultSet.class);
        var metadata = mock(ResultSetMetaData.class);
        String sql = "SELECT id FROM sample WHERE region=?";
        when(connection.prepareStatement(sql + "\nLIMIT 10 OFFSET 10")).thenReturn(page);
        when(connection.prepareStatement("SELECT COUNT(1) FROM (" + sql + "\n) t")).thenReturn(count);
        when(page.executeQuery()).thenReturn(rows);
        when(rows.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(0);
        when(count.executeQuery()).thenReturn(totals);
        when(totals.next()).thenReturn(true);
        when(totals.getLong(1)).thenReturn(0L);
        var result = new DatabaseDorisImpl().pageDataBySql(connection, sql, List.of("北区'\\"), 2, 10, null);
        assertEquals(0L, result.getTotal());
        verify(page).setObject(1, "北区'\\");
        verify(count).setObject(1, "北区'\\");
        verify(page, never()).setObject(eq(2), any());
        verify(page, never()).setObject(eq(3), any());
        verify(page).close();
        verify(count).close();
        verify(connection, never()).close();
    }
}
