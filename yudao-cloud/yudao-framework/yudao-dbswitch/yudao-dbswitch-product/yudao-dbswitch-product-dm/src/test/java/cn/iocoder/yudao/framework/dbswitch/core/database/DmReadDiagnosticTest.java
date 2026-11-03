package cn.iocoder.yudao.framework.dbswitch.core.database;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseDmImpl;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** DM8 查询契约，不代表实库验证。 */
class DmReadDiagnosticTest {
    @Test void extendedPropertiesUseDmCommentsCatalogAndBoundNames() throws Exception {
        var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r);
        when(r.next()).thenReturn(true,false); when(r.getInt(2)).thenReturn(2);
        var result=new DatabaseDmImpl().probeExtendedProperties(c,"APP'","orders'",3);
        assertEquals(2,result.columnsRead()); assertFalse(result.tableCommentPresent()); assertEquals(0,result.columnCommentsPresent());
        verify(c).prepareStatement(contains("ALL_COL_COMMENTS")); verify(s).setString(1,"APP'"); verify(s).setString(2,"orders'"); verify(s).setQueryTimeout(3);
        verify(s).close(); verify(r).close(); verify(c,never()).close();
    }

 @Test void catalogUsesBoundedNativeQuery() throws Exception {
  var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true); when(r.getString(1)).thenReturn("ORDERS");
  assertThrows(SQLException.class,()->new DatabaseDmImpl().queryCatalogTableList(c,"APP",1,3));
  verify(c).prepareStatement(contains("ALL_TABLES")); verify(s).setMaxRows(2); verify(s).setString(1,"APP"); verify(s).setQueryTimeout(3);
 }
 @Test void namespaceRequiresRealSelectPermission() throws Exception {
  var c=mock(Connection.class); var p=mock(PreparedStatement.class); var s=mock(Statement.class); var r=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(p); when(p.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true); when(r.getString(1)).thenReturn("ORDERS"); when(c.createStatement()).thenReturn(s);
  when(s.executeQuery(anyString())).thenThrow(new SQLException("denied","42501"));
  assertEquals("0A000",assertThrows(SQLFeatureNotSupportedException.class,()->new DatabaseDmImpl().probeNamespace(c,"APP",3)).getSQLState()); verify(s).executeQuery(contains("WHERE 1=0"));
 }
 @Test void accountDirectoryIsActuallyRead() throws Exception {
  var c=mock(Connection.class); var p=mock(PreparedStatement.class); var s=mock(Statement.class); var r=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(p); when(p.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true); when(r.getString(1)).thenReturn("ORDERS"); when(c.createStatement()).thenReturn(s); when(s.executeQuery(anyString())).thenReturn(r);
  new DatabaseDmImpl().probeAccountPrivileges(c,"APP",3); verify(c).prepareStatement(contains("SESSION_PRIVS")); verify(c,never()).close();
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={"42501","42000","3F000","42P01","08S01"})
 void firstSampleFailureDoesNotProveWholeNamespaceDenied(String state) throws Exception {
  var c=mock(Connection.class); var p=mock(PreparedStatement.class); var s=mock(Statement.class); var rows=mock(ResultSet.class); var readable=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(p); when(p.executeQuery()).thenReturn(rows); when(rows.next()).thenReturn(true,true,false); when(rows.getString(1)).thenReturn("first_denied","second_readable"); when(c.createStatement()).thenReturn(s);
  var failure=new SQLException("sample only",state); when(s.executeQuery(contains("first_denied"))).thenThrow(failure); when(s.executeQuery(contains("second_readable"))).thenReturn(readable);
  var actual=assertThrows(SQLException.class,()->new DatabaseDmImpl().probeNamespace(c,"APP",3));
  if("08S01".equals(state)) assertSame(failure,actual); else { assertInstanceOf(SQLFeatureNotSupportedException.class,actual); assertEquals("0A000",actual.getSQLState()); assertSame(failure,actual.getCause()); }
  verify(s,never()).executeQuery(contains("second_readable")); verify(c,never()).close();
 }
 @Test void noSampleLeavesScopeUnverified() throws Exception {
  var c=mock(Connection.class); var p=mock(PreparedStatement.class); var r=mock(ResultSet.class); when(c.prepareStatement(anyString())).thenReturn(p); when(p.executeQuery()).thenReturn(r);
  assertEquals("0A000",assertThrows(SQLFeatureNotSupportedException.class,()->new DatabaseDmImpl().probeNamespace(c,"APP",3)).getSQLState()); verify(c,never()).createStatement();
 }
 @Test void invalidBoundsAreRejected() { assertThrows(IllegalArgumentException.class,()->new DatabaseDmImpl().queryCatalogTableList(null,"APP",0,3)); }
}
