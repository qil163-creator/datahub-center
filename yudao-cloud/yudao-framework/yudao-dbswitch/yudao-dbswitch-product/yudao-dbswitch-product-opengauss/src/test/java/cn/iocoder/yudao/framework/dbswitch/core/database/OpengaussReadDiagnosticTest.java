package cn.iocoder.yudao.framework.dbswitch.core.database;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOpengaussImpl;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** 仅验证 openGauss 产品查询契约，不代表实库验证。 */
class OpengaussReadDiagnosticTest {
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"logical,2,2,true,true", "replica,2,2,true,false", "logical,0,2,true,false", "logical,2,0,true,false", "logical,2,2,false,false"})
 void nativeCdcRequiresOpenGaussAModeLoggingCapacityAndReplicationRole(String wal,int slots,int senders,boolean replication,boolean ready) throws Exception {
  var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true,false);
  when(r.getString(1)).thenReturn("(openGauss 7.0.0-RC3 build test)"); when(r.getString(2)).thenReturn("A"); when(r.getString(3)).thenReturn(wal); when(r.getInt(4)).thenReturn(slots); when(r.getInt(5)).thenReturn(senders); when(r.getBoolean(7)).thenReturn(replication);
  var result=new DatabaseOpengaussImpl().probeCdcReadiness(c,3); assertEquals(ready,result.logConfigurationReady()&&result.accountPrivilegeReady()); verify(s).setQueryTimeout(3); verify(c).prepareStatement(contains("rolsystemadmin")); verify(c,never()).createStatement(); verify(c,never()).close();
  when(r.next()).thenReturn(true,false); when(r.getString(2)).thenReturn("B"); assertThrows(SQLFeatureNotSupportedException.class,()->new DatabaseOpengaussImpl().probeCdcReadiness(c,3));
  when(r.next()).thenReturn(true,false); when(r.getString(2)).thenReturn("A"); when(r.getString(1)).thenReturn("(openGauss 7.0.0-RC30 build test)"); assertThrows(SQLFeatureNotSupportedException.class,()->new DatabaseOpengaussImpl().probeCdcReadiness(c,3));
 }

    @Test void extendedPropertiesBindNamesAndAcceptNoComments() throws Exception {
        var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r);
        when(r.next()).thenReturn(true,false); when(r.getInt(2)).thenReturn(2);
        var result=new DatabaseOpengaussImpl().probeExtendedProperties(c,"app'","orders'",3);
        assertEquals(2,result.columnsRead()); assertFalse(result.tableCommentPresent()); assertEquals(0,result.columnCommentsPresent());
        verify(s).setString(1,"app'"); verify(s).setString(2,"orders'"); verify(s).setQueryTimeout(3); verify(s).close(); verify(r).close(); verify(c,never()).close();
    }

 @Test void namespaceRequiresUsageEvidence() throws Exception {
  var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true);
  assertEquals("42501",assertThrows(SQLException.class,()->new DatabaseOpengaussImpl().probeNamespace(c,"app",3)).getSQLState());
  verify(s).setString(1,"app"); verify(s).setQueryTimeout(3);
 }
 @Test void catalogRejectsOverflow() throws Exception {
  var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true); when(r.getString(1)).thenReturn("orders");
  assertThrows(SQLException.class,()->new DatabaseOpengaussImpl().queryCatalogTableList(c,"app",1,3));
  verify(s).setMaxRows(2); verify(s).setString(1,"app");
 }
 @Test void accountDirectoryMustRespond() throws Exception {
  var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true); when(r.getBoolean(1)).thenReturn(true); when(r.getString(1)).thenReturn("reader");
  new DatabaseOpengaussImpl().probeAccountPrivileges(c,"app",3); verify(c).prepareStatement(contains("pg_roles"));
 }
 @Test void invalidBoundsFailBeforeJdbc() { assertThrows(IllegalArgumentException.class,()->new DatabaseOpengaussImpl().queryCatalogTableList(null,"app",0,3)); }
}
