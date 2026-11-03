package cn.iocoder.yudao.framework.dbswitch.core.database;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseUxdbImpl;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** UXDB 目录只能使用 ux_catalog，不允许意外继承 pg_catalog。 */
class UxdbReadDiagnosticTest {
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"logical,2,2,true,true", "replica,2,2,true,false", "logical,0,2,true,false", "logical,2,0,true,false", "logical,2,2,false,false"})
 void nativeCdcRequiresUxProfileLoggingCapacityAndReplicationRole(String wal,int slots,int senders,boolean replication,boolean ready) throws Exception {
  var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true,false);
  when(r.getString(1)).thenReturn("UXsinoDB 2.1.0.3 on x86_64"); when(r.getString(2)).thenReturn("10.0"); when(r.getString(3)).thenReturn(wal); when(r.getInt(4)).thenReturn(slots); when(r.getInt(5)).thenReturn(senders); when(r.getBoolean(7)).thenReturn(replication);
  var result=new DatabaseUxdbImpl().probeCdcReadiness(c,3); assertEquals(ready,result.logConfigurationReady()&&result.accountPrivilegeReady()); verify(s).setQueryTimeout(3); verify(c,never()).prepareStatement(contains("pg_catalog")); verify(c,never()).close();
  when(r.next()).thenReturn(true,false); when(r.getString(1)).thenReturn("PostgreSQL 10.0"); assertThrows(SQLFeatureNotSupportedException.class,()->new DatabaseUxdbImpl().probeCdcReadiness(c,3));
  when(r.next()).thenReturn(true,false); when(r.getString(1)).thenReturn("UXsinoDB 2.1.0.30 on x86_64"); assertThrows(SQLFeatureNotSupportedException.class,()->new DatabaseUxdbImpl().probeCdcReadiness(c,3));
 }

    @Test void extendedPropertiesUseUxCatalogNotPgCatalog() throws Exception {
        var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r);
        when(r.next()).thenReturn(true,false); when(r.getInt(2)).thenReturn(2);
        var result=new DatabaseUxdbImpl().probeExtendedProperties(c,"app'","orders'",3);
        assertEquals(2,result.columnsRead()); assertFalse(result.tableCommentPresent()); assertEquals(0,result.columnCommentsPresent());
        verify(c).prepareStatement(contains("ux_catalog.ux_description")); verify(c,never()).prepareStatement(contains("pg_catalog"));
        verify(s).setString(1,"app'"); verify(s).setString(2,"orders'"); verify(s).setQueryTimeout(3); verify(s).close(); verify(r).close(); verify(c,never()).close();
    }

 @Test void namespaceRequiresUsageEvidence() throws Exception {
  var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true);
  assertEquals("42501",assertThrows(SQLException.class,()->new DatabaseUxdbImpl().probeNamespace(c,"app",3)).getSQLState());
  verify(c).prepareStatement(contains("ux_catalog.ux_namespace")); verify(s).setString(1,"app");
 }
 @Test void catalogRejectsOverflow() throws Exception {
  var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true); when(r.getString(1)).thenReturn("orders");
  assertThrows(SQLException.class,()->new DatabaseUxdbImpl().queryCatalogTableList(c,"app",1,3));
  verify(c).prepareStatement(contains("ux_catalog.ux_class")); verify(s).setMaxRows(2);
 }
 @Test void accountDirectoryMustRespond() throws Exception {
  var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true); when(r.getBoolean(1)).thenReturn(true); when(r.getString(1)).thenReturn("reader");
  new DatabaseUxdbImpl().probeAccountPrivileges(c,"app",3); verify(c).prepareStatement(contains("ux_roles"));
 }
 @Test void invalidBoundsFailBeforeJdbc() { assertThrows(IllegalArgumentException.class,()->new DatabaseUxdbImpl().queryCatalogTableList(null,"app",0,3)); }
}
