package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOracleImpl;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 仅验证查询契约和拒绝边界，不代表 Oracle 实库验证。 */
class OracleReadDiagnosticTest {
    @Test void extendedPropertiesBindExactNamesAndAllowAbsentComments() throws Exception {
        var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r);
        when(r.next()).thenReturn(true,false); when(r.getInt(2)).thenReturn(2);
        var result=new DatabaseOracleImpl().probeExtendedProperties(c,"APP' OR 1=1 --","orders'",3);
        assertEquals(2,result.columnsRead()); assertFalse(result.tableCommentPresent()); assertEquals(0,result.columnCommentsPresent());
        verify(s).setString(1,"APP' OR 1=1 --"); verify(s).setString(2,"orders'"); verify(s).setQueryTimeout(3);
        verify(s).close(); verify(r).close(); verify(c,never()).close(); verify(c,never()).createStatement();
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"0,0", "2,3", "2,-1"})
    void incompleteExtendedCountsFailClosed(int columns,int comments) throws Exception {
        var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true,false); when(r.getInt(2)).thenReturn(columns); when(r.getInt(3)).thenReturn(comments);
        assertThrows(SQLException.class,()->new DatabaseOracleImpl().probeExtendedProperties(c,"APP","orders",3)); verify(s).close(); verify(r).close(); verify(c,never()).close();
    }
    @Test void nativeCatalogTimeoutIsPropagatedWithResourcesClosed() throws Exception {
        var c=mock(Connection.class); var s=mock(PreparedStatement.class); var failure=new SQLTimeoutException("private detail","HYT00");
        when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenThrow(failure);
        assertSame(failure,assertThrows(SQLTimeoutException.class,()->new DatabaseOracleImpl().probeExtendedProperties(c,"APP","orders",3))); verify(s).close(); verify(c,never()).close();
    }
    @Test void extendedPropertiesRejectMissingRelation() throws Exception {
        var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r);
        assertThrows(SQLException.class,()->new DatabaseOracleImpl().probeExtendedProperties(c,"APP","missing",3));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"ARCHIVELOG,YES,YES,true,true,true", "NOARCHIVELOG,YES,YES,true,true,false", "ARCHIVELOG,NO,NO,true,true,false", "ARCHIVELOG,YES,YES,false,true,false", "ARCHIVELOG,YES,YES,true,false,false"})
    void logMinerReadinessRequiresActualLoggingAndMiningPrivileges(String logMode,String minimum,String all,
            boolean privileges,boolean execute,boolean ready) throws Exception {
        var c=mock(Connection.class); var config=mock(PreparedStatement.class); var role=mock(PreparedStatement.class);
        var cr=mock(ResultSet.class); var rr=mock(ResultSet.class); var md=mock(DatabaseMetaData.class);
        when(c.getMetaData()).thenReturn(md); when(md.getDatabaseProductName()).thenReturn("Oracle"); when(md.getDatabaseMajorVersion()).thenReturn(23);
        when(c.prepareStatement(contains("V$DATABASE"))).thenReturn(config); when(config.executeQuery()).thenReturn(cr);
        when(cr.next()).thenReturn(true,false); when(cr.getString(1)).thenReturn(logMode); when(cr.getString(2)).thenReturn(minimum); when(cr.getString(3)).thenReturn(all);
        when(c.prepareStatement(contains("SESSION_PRIVS"))).thenReturn(role); when(role.executeQuery()).thenReturn(rr);
        when(rr.next()).thenReturn(true,false); when(rr.getInt(1)).thenReturn(privileges?3:0); when(rr.getInt(2)).thenReturn(execute?1:0);
        var result=new DatabaseOracleImpl().probeCdcReadiness(c,3);
        assertEquals(ready,result.logConfigurationReady()&&result.accountPrivilegeReady());
        verify(config).setQueryTimeout(3); verify(role).setQueryTimeout(3); verify(c,never()).createStatement(); verify(c,never()).commit(); verify(c,never()).close();
    }
    @Test void oracleCdcRejectsCompatibleAndUnverifiedVersions() throws Exception {
        var c=mock(Connection.class); var md=mock(DatabaseMetaData.class); when(c.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn("OceanBase"); when(md.getDatabaseMajorVersion()).thenReturn(23);
        assertThrows(SQLFeatureNotSupportedException.class,()->new DatabaseOracleImpl().probeCdcReadiness(c,3)); verify(c,never()).prepareStatement(anyString());
    }
    @Test void oracleGlobalTemporaryDdlMustNotBeEnabled() {
        assertThrows(SQLFeatureNotSupportedException.class,()->new DatabaseOracleImpl().temporaryTableDiagnosticPlan("dbswitch_diag_"+"a".repeat(32)));
    }

    @Test void boundedCatalogBindsNamespaceAndDoesNotTruncate() throws Exception {
        var connection = mock(Connection.class); var statement = mock(PreparedStatement.class); var rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement); when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false); when(rows.getString(1)).thenReturn("orders");
        var database = new DatabaseOracleImpl();
        assertEquals("orders", database.queryCatalogTableList(connection, "APP", 1, 3).getFirst().getTableName());
        verify(statement).setString(1, "APP"); verify(statement).setMaxRows(2); verify(statement).setQueryTimeout(3);
        when(rows.next()).thenReturn(true, true, false);
        assertThrows(SQLException.class, () -> database.queryCatalogTableList(connection, "APP", 1, 3));
        verify(connection, never()).close();
    }
    @Test void namespaceRequiresActualZeroRowSelectNotCatalogVisibility() throws Exception {
        var connection = mock(Connection.class); var prepared = mock(PreparedStatement.class); var statement = mock(Statement.class); var rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(prepared); when(prepared.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false); when(rows.getString(1)).thenReturn("orders");
        when(connection.createStatement()).thenReturn(statement); when(statement.executeQuery(anyString())).thenThrow(new SQLException("denied", "42501"));
        assertEquals("0A000", assertThrows(SQLFeatureNotSupportedException.class, () -> new DatabaseOracleImpl().probeNamespace(connection, "APP", 3)).getSQLState());
        verify(statement).executeQuery(contains("WHERE 1=0"));
    }
    @Test void accountDirectoryIsActuallyRead() throws Exception {
        var connection = mock(Connection.class); var prepared = mock(PreparedStatement.class); var rows = mock(ResultSet.class); var statement = mock(Statement.class);
        when(connection.prepareStatement(anyString())).thenReturn(prepared); when(prepared.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true); when(rows.getString(1)).thenReturn("orders"); when(rows.getBoolean(1)).thenReturn(true);
        when(connection.createStatement()).thenReturn(statement); when(statement.executeQuery(anyString())).thenReturn(rows);
        new DatabaseOracleImpl().probeAccountPrivileges(connection, "APP", 3);
        verify(connection).prepareStatement(contains("SESSION_PRIVS"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"42501","3F000","42P01","08S01"})
    void firstSampleFailureDoesNotProveWholeNamespaceDenied(String state) throws Exception {
        var c=mock(Connection.class); var p=mock(PreparedStatement.class); var s=mock(Statement.class); var rows=mock(ResultSet.class); var readable=mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(p); when(p.executeQuery()).thenReturn(rows); when(rows.next()).thenReturn(true,true,false); when(rows.getString(1)).thenReturn("first_denied","second_readable"); when(c.createStatement()).thenReturn(s);
        var failure=new SQLException("sample only",state); when(s.executeQuery(contains("first_denied"))).thenThrow(failure); when(s.executeQuery(contains("second_readable"))).thenReturn(readable);
        var actual=assertThrows(SQLException.class,()->new DatabaseOracleImpl().probeNamespace(c,"APP",3));
        if("08S01".equals(state)) assertSame(failure,actual); else { assertInstanceOf(SQLFeatureNotSupportedException.class,actual); assertEquals("0A000",actual.getSQLState()); assertSame(failure,actual.getCause()); }
        verify(s,never()).executeQuery(contains("second_readable")); verify(c,never()).close();
    }
    @Test void noSampleLeavesScopeUnverified() throws Exception {
        var c=mock(Connection.class); var p=mock(PreparedStatement.class); var r=mock(ResultSet.class); when(c.prepareStatement(anyString())).thenReturn(p); when(p.executeQuery()).thenReturn(r);
        assertEquals("0A000",assertThrows(SQLFeatureNotSupportedException.class,()->new DatabaseOracleImpl().probeNamespace(c,"APP",3)).getSQLState()); verify(c,never()).createStatement();
    }
    @Test void invalidBoundsAreRejectedBeforeJdbc() {
        var database = new DatabaseOracleImpl();
        assertThrows(IllegalArgumentException.class, () -> database.queryCatalogTableList(null, "APP", 0, 3));
        assertThrows(IllegalArgumentException.class, () -> database.probeNamespace(null, "APP", 0));
    }
}
