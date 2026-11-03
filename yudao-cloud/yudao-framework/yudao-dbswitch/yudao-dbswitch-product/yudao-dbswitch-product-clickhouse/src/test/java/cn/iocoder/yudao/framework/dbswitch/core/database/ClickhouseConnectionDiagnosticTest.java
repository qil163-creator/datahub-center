package cn.iocoder.yudao.framework.dbswitch.core.database;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseClickhouseImpl;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ClickhouseConnectionDiagnosticTest {
    @Test void propertiesNeverReturnTruncatedUnboundedColumnEvidence() throws Exception {
        var c=mock(Connection.class);var s=mock(PreparedStatement.class);var r=mock(ResultSet.class);var count=new java.util.concurrent.atomic.AtomicInteger();
        when(c.prepareStatement(anyString())).thenReturn(s);when(s.executeQuery()).thenReturn(r);when(r.next()).thenAnswer(x->count.incrementAndGet()<=4097);
        assertThrows(SQLException.class,()->new DatabaseClickhouseImpl().probeExtendedProperties(c,"analytics","events",3));
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);verify(c).prepareStatement(sql.capture());assertTrue(sql.getValue().endsWith("LIMIT 4097"));
        verify(r).close();verify(s).close();verify(c,never()).close();
    }

    @Test void catalogNeverReturnsTruncatedOrWrongSchemaResults() throws Exception {
        var c=mock(Connection.class);var s=mock(PreparedStatement.class);var r=mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(s);when(s.executeQuery()).thenReturn(r);
        when(r.next()).thenReturn(true,true,false);when(r.getString(1)).thenReturn("analytics");when(r.getString(2)).thenReturn("first","second");
        assertThrows(SQLException.class,()->new DatabaseClickhouseImpl().queryCatalogTableList(c,"analytics",1,3));
        when(r.next()).thenReturn(true,false);when(r.getString(1)).thenReturn("other");
        assertThrows(SQLException.class,()->new DatabaseClickhouseImpl().queryCatalogTableList(c,"analytics",10,3));
    }
    @Test void boundedCatalogUsesNativeSystemTables() throws Exception {
        var c=mock(Connection.class);var s=mock(PreparedStatement.class);var r=mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(s);when(s.executeQuery()).thenReturn(r);
        when(r.next()).thenReturn(true,false);when(r.getString(1)).thenReturn("analytics");when(r.getString(2)).thenReturn("events");
        assertEquals("events",new DatabaseClickhouseImpl().queryCatalogTableList(c,"analytics",10,3).getFirst().getTableName());
        verify(s).setMaxRows(11);verify(s).setQueryTimeout(3);verify(s).setString(1,"analytics");
    }
    @Test void useValidatesAccessAndEscapesIdentifier() throws Exception {
        var connection = mock(Connection.class); var statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        new DatabaseClickhouseImpl().probeNamespace(connection, "warehouse`east", 4);
        verify(statement).execute("USE `warehouse\\`east`");
        verify(statement).setQueryTimeout(4); verify(statement).close(); verify(connection, never()).close();
    }
    @Test void grantsRequireServerResponse() throws Exception {
        var connection = mock(Connection.class); var statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SHOW GRANTS")).thenReturn(mock(ResultSet.class));
        assertThrows(SQLException.class, () -> new DatabaseClickhouseImpl().probeAccountPrivileges(connection,"default",4));
    }
    @Test void propertiesAreBoundToExactRelation() throws Exception {
        var connection = mock(Connection.class); var statement = mock(PreparedStatement.class); var rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement); when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true,false); when(rows.getString(1)).thenReturn("remark"); when(rows.getString(2)).thenReturn("column");
        var evidence = new DatabaseClickhouseImpl().probeExtendedProperties(connection,"db'","table'",4);
        assertEquals(1,evidence.columnsRead()); assertTrue(evidence.tableCommentPresent()); assertEquals(1,evidence.columnCommentsPresent());
        verify(statement).setString(1,"db'"); verify(statement).setString(2,"table'");
    }
}
