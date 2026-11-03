package cn.iocoder.yudao.framework.dbswitch.core.database;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSqlserverImpl;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** 现代 SQL Server 只读诊断契约，不代表实库验证。 */
class SqlserverReadDiagnosticTest {
    @Test void extendedPropertiesBindExactNamesAndAllowAbsentComments() throws Exception {
        var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r);
        when(r.next()).thenReturn(true,false); when(r.getInt(2)).thenReturn(2);
        var result=new DatabaseSqlserverImpl().probeExtendedProperties(c,"dbo' OR 1=1 --","orders'",3);
        assertEquals(2,result.columnsRead()); assertFalse(result.tableCommentPresent()); assertEquals(0,result.columnCommentsPresent());
        verify(s).setString(1,"dbo' OR 1=1 --"); verify(s).setString(2,"orders'"); verify(s).setQueryTimeout(3);
        verify(s).close(); verify(r).close(); verify(c,never()).close(); verify(c,never()).createStatement();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"1,0,2,2,true,true", "1,0,2,1,true,false", "0,1,0,0,false,true", "1,1,0,0,false,true", "1,0,0,0,false,false"})
    void cdcRequiresEnabledCaptureAndSourceSelectAndGatingRole(int enabled,int owner,int captures,int permitted,boolean logs,boolean privilege) throws Exception {
        var c=mock(Connection.class); var config=mock(PreparedStatement.class); var capture=mock(PreparedStatement.class); var cr=mock(ResultSet.class); var rr=mock(ResultSet.class); var md=mock(DatabaseMetaData.class);
        when(c.getMetaData()).thenReturn(md); when(md.getDatabaseProductName()).thenReturn("Microsoft SQL Server"); when(md.getDatabaseMajorVersion()).thenReturn(16);
        when(c.prepareStatement(contains("sys.databases"))).thenReturn(config); when(config.executeQuery()).thenReturn(cr); when(cr.next()).thenReturn(true,false); when(cr.getInt(1)).thenReturn(enabled); when(cr.getInt(2)).thenReturn(owner); when(cr.getInt(3)).thenReturn(0);
        when(c.prepareStatement(contains("cdc.change_tables"))).thenReturn(capture); when(capture.executeQuery()).thenReturn(rr); when(rr.next()).thenReturn(true,false); when(rr.getInt(1)).thenReturn(captures); when(rr.getInt(2)).thenReturn(permitted);
        var result=new DatabaseSqlserverImpl().probeCdcReadiness(c,3); assertEquals(logs,result.logConfigurationReady()); assertEquals(privilege,result.accountPrivilegeReady());
        verify(config).setQueryTimeout(3); verify(c,never()).createStatement(); verify(c,never()).commit(); verify(c,never()).close();
        if(enabled==0) verify(c,never()).prepareStatement(contains("cdc.change_tables"));
    }
    @Test void temporaryPlanUsesOnlyLocalTemporaryObjects() throws Exception {
        var d=new DatabaseSqlserverImpl(); var p=d.temporaryTableDiagnosticPlan("dbswitch_diag_"+"a".repeat(32));
        assertTrue(p.createSql().startsWith("CREATE TABLE [#dbswitch_diag_"));
        assertTrue(p.tableReference().startsWith("[#")); assertTrue(p.alterSql().contains(p.tableReference()));
        assertTrue(p.dropSql().contains("tempdb..#")); assertTrue(p.truncateSql().contains(p.tableReference()));
        assertNull(p.renameSql()); assertNull(p.upsertSql()); assertEquals(10,p.mergeMinimumMajorVersion());
        assertThrows(IllegalArgumentException.class,()->d.temporaryTableDiagnosticPlan("x];DROP TABLE business;--"));
    }

 @Test void namespaceRequiresServerPermissionEvidence() throws Exception {
  var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true);
  assertEquals("42501",assertThrows(SQLException.class,()->new DatabaseSqlserverImpl().probeNamespace(c,"app",3)).getSQLState()); verify(s).setString(1,"app"); verify(s).setQueryTimeout(3);
 }
 @Test void catalogRejectsOverflow() throws Exception {
  var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true); when(r.getString(1)).thenReturn("orders");
  assertThrows(SQLException.class,()->new DatabaseSqlserverImpl().queryCatalogTableList(c,"app",1,3)); verify(c).prepareStatement(contains("sys.tables")); verify(s).setMaxRows(2);
 }
 @Test void accountDirectoryIsActuallyRead() throws Exception {
  var c=mock(Connection.class); var p=mock(PreparedStatement.class); var r=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(p); when(p.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true); when(r.getBoolean(1)).thenReturn(true); when(r.getString(1)).thenReturn("reader");
  new DatabaseSqlserverImpl().probeAccountPrivileges(c,"app",3); verify(c).prepareStatement(contains("sys.database_permissions")); verify(c,never()).close();
 }
 @Test void invalidBoundsAreRejected() { assertThrows(IllegalArgumentException.class,()->new DatabaseSqlserverImpl().queryCatalogTableList(null,"app",0,3)); }
}
