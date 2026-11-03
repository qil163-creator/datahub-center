package cn.iocoder.yudao.framework.dbswitch.core.database;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseKingbaseImpl;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** Kingbase 只读诊断契约，不代表真实产品运行验证。 */
class KingbaseReadDiagnosticTest {
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"logical,2,2,true,true", "replica,2,2,true,false", "logical,0,2,true,false", "logical,2,0,true,false", "logical,2,2,false,false"})
 void nativeCdcRequiresKingbaseOracleProfileLoggingCapacityAndReplicationRole(String wal,int slots,int senders,boolean replication,boolean ready) throws Exception {
  var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true,false);
  when(r.getString(1)).thenReturn("KingbaseES V008R006C009B0014 on x86_64-pc-linux-gnu, compiled by gcc (GCC) 4.8.5 20150623 (Red Hat 4.8.5-28), 64-bit"); when(r.getString(2)).thenReturn("oracle"); when(r.getString(8)).thenReturn("on"); when(r.getString(3)).thenReturn(wal); when(r.getInt(4)).thenReturn(slots); when(r.getInt(5)).thenReturn(senders); when(r.getBoolean(7)).thenReturn(replication);
  var result=new DatabaseKingbaseImpl().probeCdcReadiness(c,3); assertEquals(ready,result.logConfigurationReady()&&result.accountPrivilegeReady()); verify(s).setQueryTimeout(3); verify(c,never()).createStatement(); verify(c,never()).close();
  when(r.next()).thenReturn(true,false); when(r.getString(2)).thenReturn("pg"); assertThrows(SQLFeatureNotSupportedException.class,()->new DatabaseKingbaseImpl().probeCdcReadiness(c,3));
 }

    @Test void extendedPropertiesBindNamesAndAcceptNoComments() throws Exception {
        var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r);
        when(r.next()).thenReturn(true,false); when(r.getInt(2)).thenReturn(2);
        var result=new DatabaseKingbaseImpl().probeExtendedProperties(c,"app'","orders'",3);
        assertEquals(2,result.columnsRead()); assertFalse(result.tableCommentPresent()); assertEquals(0,result.columnCommentsPresent());
        verify(s).setString(1,"app'"); verify(s).setString(2,"orders'"); verify(s).setQueryTimeout(3); verify(s).close(); verify(r).close(); verify(c,never()).close();
    }

 @Test void namespaceRequiresUsageEvidence() throws Exception {
  var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true);
  assertEquals("42501",assertThrows(SQLException.class,()->new DatabaseKingbaseImpl().probeNamespace(c,"app",3)).getSQLState());
  verify(s).setString(1,"app"); verify(s).setQueryTimeout(3);
 }
 @Test void catalogRejectsOverflow() throws Exception {
  var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true); when(r.getString(1)).thenReturn("orders");
  assertThrows(SQLException.class,()->new DatabaseKingbaseImpl().queryCatalogTableList(c,"app",1,3));
  verify(s).setMaxRows(2); verify(s).setString(1,"app");
 }
 @Test void accountDirectoryMustRespond() throws Exception {
  var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true); when(r.getBoolean(1)).thenReturn(true); when(r.getString(1)).thenReturn("reader");
  new DatabaseKingbaseImpl().probeAccountPrivileges(c,"app",3); verify(c).prepareStatement(contains("pg_roles"));
 }
 @Test void invalidBoundsFailBeforeJdbc() { assertThrows(IllegalArgumentException.class,()->new DatabaseKingbaseImpl().queryCatalogTableList(null,"app",0,3)); }
}
