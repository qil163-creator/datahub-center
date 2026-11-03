package cn.iocoder.yudao.framework.dbswitch.core.database;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseHiveImpl;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class HiveConnectionDiagnosticTest {
    @Test void accountReadsCurrentPrincipalGrantsWithoutTreatingEmptyGrantsAsDenial() throws Exception {
        var c=mock(Connection.class); var s=mock(Statement.class); var identity=mock(ResultSet.class); var grants=mock(ResultSet.class);
        when(c.createStatement()).thenReturn(s); when(s.executeQuery("SELECT current_user()")).thenReturn(identity);
        when(identity.next()).thenReturn(true); when(identity.getString(1)).thenReturn("reader`east");
        when(s.executeQuery("SHOW GRANT USER `reader``east`")).thenReturn(grants);
        new DatabaseHiveImpl().probeAccountPrivileges(c,"analytics",3);
        verify(s).execute("USE `analytics`");
        verify(s).executeQuery("SHOW GRANT USER `reader``east`"); verify(identity).close(); verify(grants).close();
        verify(c,never()).close();
    }
    @Test void formattedDescriptionCountsColumnsAndActualTableCommentNotPartitionHeaders() throws Exception {
        var c=mock(Connection.class); var s=mock(Statement.class); var r=mock(ResultSet.class);
        when(c.createStatement()).thenReturn(s); when(s.executeQuery("DESCRIBE FORMATTED `analytics`.`events`")).thenReturn(r);
        when(r.next()).thenReturn(true,true,true,true,true,true,true,false);
        when(r.getString(1)).thenReturn("# col_name", "id", "payload", "# Partition Information", "part", "Table Parameters:", "");
        when(r.getString(2)).thenReturn("data_type","bigint","string","","string","","comment");
        when(r.getString(3)).thenReturn("comment","identifier",null,"","", "", "events comment");
        var result=new DatabaseHiveImpl().probeExtendedProperties(c,"analytics","events",3);
        assertEquals(2,result.columnsRead()); assertEquals(1,result.columnCommentsPresent()); assertTrue(result.tableCommentPresent());
        verify(r).close(); verify(s).close(); verify(c,never()).close();
    }
    @Test void malformedDescriptionDoesNotTurnIntoSuccessfulEmptyCommentCatalog() throws Exception {
        var c=mock(Connection.class); var s=mock(Statement.class); var r=mock(ResultSet.class);
        when(c.createStatement()).thenReturn(s); when(s.executeQuery(anyString())).thenReturn(r);
        assertThrows(SQLException.class,()->new DatabaseHiveImpl().probeExtendedProperties(c,"analytics","events",3));
    }
    @Test void boundedCatalogRefusesBeforeAnyUnboundedMetadataRequest() {
        var connection=mock(Connection.class);
        var failure=assertThrows(SQLFeatureNotSupportedException.class,
                ()->new DatabaseHiveImpl().queryCatalogTableList(connection,"analytics",10,3));
        assertEquals("0A000",failure.getSQLState());
        verifyNoInteractions(connection);
    }
    @Test void namespaceUsesActualUseNotJdbcSetSchema() throws Exception {
        var connection=mock(Connection.class); var statement=mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        new DatabaseHiveImpl().probeNamespace(connection,"warehouse`east",3);
        verify(statement).execute("USE `warehouse``east`"); verify(statement).setQueryTimeout(3);
        verify(connection,never()).setSchema(anyString()); verify(connection,never()).close();
    }
}
