package cn.iocoder.yudao.framework.dbswitch.core.database;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseInfluxdbImpl;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class InfluxdbConnectionDiagnosticTest {
    @org.junit.jupiter.api.Test void flightSqlDoesNotFabricateGrantOrCommentCatalogs() {
        var database=new cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseInfluxdbImpl();var c=mock(java.sql.Connection.class);
        var grants=org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLFeatureNotSupportedException.class,()->database.probeAccountPrivileges(c,"iox",3));
        var comments=org.junit.jupiter.api.Assertions.assertThrows(cn.iocoder.yudao.framework.dbswitch.core.model.DiagnosticNotApplicableException.class,()->database.probeExtendedProperties(c,"iox","events",3));
        org.junit.jupiter.api.Assertions.assertTrue(grants.getMessage().contains("Token"));
        org.junit.jupiter.api.Assertions.assertTrue(comments.getMessage().contains("COMMENT"));
        verifyNoInteractions(c);
    }

    @Test void catalogRefusesOverflowRatherThanReturningPartialList() throws Exception {
        var c=mock(Connection.class);var s=mock(PreparedStatement.class);var r=mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(s);when(s.executeQuery()).thenReturn(r);
        when(r.next()).thenReturn(true,true,false);when(r.getString(1)).thenReturn("iox");when(r.getString(2)).thenReturn("first","second");
        assertThrows(SQLException.class,()->new DatabaseInfluxdbImpl().queryCatalogTableList(c,"iox",1,3));
        verify(r).close();
    }
    @Test void boundedCatalogUsesExactInformationSchema() throws Exception {
        var c=mock(Connection.class);var s=mock(PreparedStatement.class);var r=mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(s);when(s.executeQuery()).thenReturn(r);
        when(r.next()).thenReturn(true,false);when(r.getString(1)).thenReturn("iox");when(r.getString(2)).thenReturn("events");
        assertEquals("events",new DatabaseInfluxdbImpl().queryCatalogTableList(c,"iox",10,3).getFirst().getTableName());
        verify(s).setMaxRows(11);verify(s).setQueryTimeout(3);verify(s).setString(1,"iox");
    }
    @Test void namespaceRequiresActualExactSchemaInServerDirectory() throws Exception {
        var connection=mock(Connection.class); var statement=mock(PreparedStatement.class);var rows=mock(ResultSet.class);
        when(connection.prepareStatement("SELECT schema_name FROM information_schema.schemata WHERE schema_name = ?")).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);when(rows.next()).thenReturn(true);when(rows.getString(1)).thenReturn("iox");
        new DatabaseInfluxdbImpl().probeNamespace(connection,"iox",3);
        verify(statement).setString(1,"iox");verify(statement).setQueryTimeout(3);verify(connection,never()).setSchema(anyString());
    }
    @Test void missingSchemaCannotPass() throws Exception {
        var connection=mock(Connection.class);var statement=mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);when(statement.executeQuery()).thenReturn(mock(ResultSet.class));
        assertThrows(SQLException.class,()->new DatabaseInfluxdbImpl().probeNamespace(connection,"missing",3));
    }
}
