package cn.iocoder.yudao.framework.dbswitch.core.database;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSqlserver2000Impl;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** SQL Server 2000 只使用旧版系统表，不能调用现代 sys.schemas。 */
class Sqlserver2000ReadDiagnosticTest {
    @Test void legacyTemporaryPlanIsLocalAndDoesNotInheritMerge() throws Exception {
        var d=new DatabaseSqlserver2000Impl(); var p=d.temporaryTableDiagnosticPlan("dbswitch_diag_"+"b".repeat(32));
        assertTrue(p.createSql().startsWith("CREATE TABLE [#dbswitch_diag_")); assertTrue(p.dropSql().contains("tempdb..#"));
        assertTrue(p.alterSql().contains(p.tableReference())); assertTrue(p.truncateSql().contains(p.tableReference()));
        assertNull(p.mergeSql()); assertNull(p.upsertSql()); assertNull(p.renameSql());
        assertThrows(IllegalArgumentException.class,()->d.temporaryTableDiagnosticPlan("##global"));
        assertThrows(SQLFeatureNotSupportedException.class,()->d.probeCdcReadiness(null,3));
    }

    @Test void legacyExtendedPropertiesUseSyspropertiesNotModernCatalog() throws Exception {
        var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r);
        when(r.next()).thenReturn(true,false); when(r.getInt(2)).thenReturn(2);
        var result=new DatabaseSqlserver2000Impl().probeExtendedProperties(c,"dbo'","orders'",3);
        assertEquals(2,result.columnsRead()); assertFalse(result.tableCommentPresent());
        verify(c).prepareStatement(contains("sysproperties")); verify(c,never()).prepareStatement(contains("sys.extended_properties"));
        verify(s).setString(1,"dbo'"); verify(s).setString(2,"orders'"); verify(s).setQueryTimeout(3);
        verify(c,never()).close();
    }

 @Test void namespaceRequiresServerPermissionEvidence() throws Exception {
  var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true);
  assertEquals("42501",assertThrows(SQLException.class,()->new DatabaseSqlserver2000Impl().probeNamespace(c,"dbo",3)).getSQLState()); verify(c).prepareStatement(contains("sysusers")); verify(s).setString(1,"dbo");
 }
 @Test void catalogRejectsOverflow() throws Exception {
  var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true); when(r.getString(1)).thenReturn("orders");
  assertThrows(SQLException.class,()->new DatabaseSqlserver2000Impl().queryCatalogTableList(c,"dbo",1,3)); verify(c).prepareStatement(contains("sysobjects")); verify(s).setMaxRows(2);
 }
 @Test void accountDirectoryIsActuallyRead() throws Exception {
  var c=mock(Connection.class); var p=mock(PreparedStatement.class); var r=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(p); when(p.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true); when(r.getBoolean(1)).thenReturn(true); when(r.getString(1)).thenReturn("reader");
  new DatabaseSqlserver2000Impl().probeAccountPrivileges(c,"dbo",3); verify(c).prepareStatement(contains("sysprotects")); verify(c,never()).close();
 }
 @Test void invalidBoundsAreRejected() { assertThrows(IllegalArgumentException.class,()->new DatabaseSqlserver2000Impl().queryCatalogTableList(null,"dbo",0,3)); }
}
