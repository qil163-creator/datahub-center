package cn.iocoder.yudao.framework.dbswitch.core.database;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseStarrocksImpl;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class StarrocksConnectionDiagnosticTest {
    @Test void boundedCatalogBypassesMysqlProductGuard() throws Exception {
        var c=mock(Connection.class);var s=mock(PreparedStatement.class);var r=mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(s);when(s.executeQuery()).thenReturn(r);
        when(r.next()).thenReturn(true,false);when(r.getString(1)).thenReturn("analytics");when(r.getString(2)).thenReturn("events");
        var tables=new DatabaseStarrocksImpl().queryCatalogTableList(c,"analytics",10,3);
        assertEquals("events",tables.getFirst().getTableName());verify(s).setMaxRows(11);verify(s).setQueryTimeout(3);
    }
    @Test void namespaceEntersStarrocksDatabase() throws Exception {
        var connection=mock(Connection.class);var statement=mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        new DatabaseStarrocksImpl().probeNamespace(connection,"analytics",3);
        verify(statement).execute("USE `analytics`");verify(statement).setQueryTimeout(3);
        verify(connection,never()).close();
    }
    @Test void mysqlCdcDoesNotLeakIntoStarrocks() {
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> new DatabaseStarrocksImpl().probeCdcReadiness(mock(Connection.class),3));
    }
    @Test void nativeTemporaryPlanUsesPrimaryKeyModelAndTemporaryDrop() throws Exception {
        var plan = new DatabaseStarrocksImpl().temporaryTableDiagnosticPlan("dbswitch_diag_" + "a".repeat(32));
        assertTrue(plan.createSql().startsWith("CREATE TEMPORARY TABLE "));
        assertTrue(plan.createSql().contains("PRIMARY KEY"));
        assertFalse(plan.createSql().contains("UNIQUE KEY"));
        assertTrue(plan.dropSql().startsWith("DROP TEMPORARY TABLE IF EXISTS "));
        assertNotNull(plan.upsertSql());
        assertNull(plan.alterSql());
        assertNull(plan.renameSql());
    }
    @Test void unsafeTemporaryNameIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new DatabaseStarrocksImpl().temporaryTableDiagnosticPlan("orders"));
    }
}
