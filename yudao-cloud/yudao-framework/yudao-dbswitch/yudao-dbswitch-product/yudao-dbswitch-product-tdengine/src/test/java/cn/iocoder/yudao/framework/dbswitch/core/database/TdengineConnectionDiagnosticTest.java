package cn.iocoder.yudao.framework.dbswitch.core.database;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseTdengineImpl;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class TdengineConnectionDiagnosticTest {
    @org.junit.jupiter.api.Test void accountUsesNativeCurrentPrincipalAndBoundedGrantCatalog() throws Exception {
        var c=mock(java.sql.Connection.class);var s=mock(java.sql.Statement.class);var r=mock(java.sql.ResultSet.class);var grants=mock(java.sql.PreparedStatement.class);var g=mock(java.sql.ResultSet.class);
        when(c.createStatement()).thenReturn(s);when(s.executeQuery("SELECT current_user()")).thenReturn(r);when(r.next()).thenReturn(true,false);when(r.getString(1)).thenReturn("reader@node");
        when(c.prepareStatement(anyString())).thenReturn(grants);when(grants.executeQuery()).thenReturn(g);
        new cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseTdengineImpl().probeAccountPrivileges(c,"warehouse",3);
        verify(s).execute("USE `warehouse`");
        verify(grants).setString(1,"reader");verify(grants).setString(2,"warehouse");verify(grants).setQueryTimeout(3);verify(c,never()).close();verify(g).close();
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);verify(c).prepareStatement(sql.capture());org.junit.jupiter.api.Assertions.assertTrue(sql.getValue().endsWith("LIMIT 513"));
    }
    @org.junit.jupiter.api.Test void columnCommentInapplicabilityCannotBecomeFakeSuccessfulEmptyComments() {
        var failure=org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLFeatureNotSupportedException.class,
                ()->new cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseTdengineImpl().probeExtendedProperties(mock(java.sql.Connection.class),"warehouse","events",3));
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("列级 COMMENT"));
        org.junit.jupiter.api.Assertions.assertEquals("0A000",failure.getSQLState());
    }

    @Test void catalogOverflowDoesNotFallBackToUnboundedMetadata() throws Exception {
        var c=mock(Connection.class);var s=mock(PreparedStatement.class);var r=mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(s);when(s.executeQuery()).thenReturn(r);
        when(r.next()).thenReturn(true,true,false);when(r.getString(1)).thenReturn("first","second");
        assertThrows(SQLException.class,()->new DatabaseTdengineImpl().queryCatalogTableList(c,"analytics",1,3));
        verify(c,never()).getMetaData();verify(r).close();
    }
    @Test void boundedCatalogRequiresServerSideLimitAndBoundDatabase() throws Exception {
        var c=mock(Connection.class);var s=mock(PreparedStatement.class);var r=mock(ResultSet.class);
        String sql="SELECT table_name FROM information_schema.ins_tables WHERE db_name=? ORDER BY table_name LIMIT 11";
        var legacy=mock(Statement.class);when(c.createStatement()).thenReturn(legacy);when(legacy.executeQuery(anyString())).thenReturn(r);
        when(c.prepareStatement(sql)).thenReturn(s);when(s.executeQuery()).thenReturn(r);
        when(r.next()).thenReturn(true,false);when(r.getString(1)).thenReturn("events");
        assertEquals("events",new DatabaseTdengineImpl().queryCatalogTableList(c,"analytics",10,3).getFirst().getTableName());
        verify(s).setString(1,"analytics");verify(s,never()).setMaxRows(anyInt());
        verify(c,never()).createStatement();
    }
    @Test void targetDatabaseUsesBoundedSessionCommand() throws Exception {
        var connection=mock(Connection.class); var statement=mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        new DatabaseTdengineImpl().probeNamespace(connection,"warehouse",3);
        verify(statement).execute("USE `warehouse`"); verify(statement).setQueryTimeout(3);
        verify(connection,never()).close();
    }
}
