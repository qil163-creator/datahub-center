package cn.iocoder.yudao.framework.dbswitch.core.database;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOscarImpl;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** 神通仅使用其原生 INFO_SCHEM 目录，不借用 PostgreSQL 目录。 */
class OscarReadDiagnosticTest {
 @Test void catalogUsesBoundedNativeQuery() throws Exception {
  var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true); when(r.getString(1)).thenReturn("ORDERS");
  assertThrows(SQLException.class,()->new DatabaseOscarImpl().queryCatalogTableList(c,"APP",1,3));
  verify(c).prepareStatement(contains("INFO_SCHEM.SYS_CLASS")); verify(s).setMaxRows(2); verify(s).setString(1,"APP"); verify(s).setQueryTimeout(3);
 }
 @Test void namespaceRequiresRealSelectPermission() throws Exception {
  var c=mock(Connection.class); var p=mock(PreparedStatement.class); var s=mock(Statement.class); var r=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(p); when(p.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true); when(r.getString(1)).thenReturn("ORDERS"); when(c.createStatement()).thenReturn(s);
  when(s.executeQuery(anyString())).thenThrow(new SQLException("denied","42501"));
  assertEquals("42501",assertThrows(SQLException.class,()->new DatabaseOscarImpl().probeNamespace(c,"APP",3)).getSQLState()); verify(s).executeQuery(contains("WHERE 1=0"));
 }
 @Test void unknownAccountCatalogRemainsExplicitGap() throws Exception {
  var c=mock(Connection.class);
  var failure=assertThrows(SQLFeatureNotSupportedException.class,()->new DatabaseOscarImpl().probeAccountPrivileges(c,"APP",3));
  assertEquals("0A000",failure.getSQLState()); verifyNoInteractions(c);
 }
 @Test void invalidBoundsAreRejected() { assertThrows(IllegalArgumentException.class,()->new DatabaseOscarImpl().queryCatalogTableList(null,"APP",0,3)); }
}
