package cn.iocoder.yudao.framework.dbswitch.core.database;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseGreenplumImpl;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** 仅验证 Greenplum 原生查询契约，不代表实库验证。 */
class GreenplumReadDiagnosticTest {
    @Test void extendedPropertiesBindNamesAndAcceptNoComments() throws Exception {
        var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r);
        when(r.next()).thenReturn(true,false); when(r.getInt(2)).thenReturn(2);
        var result=new DatabaseGreenplumImpl().probeExtendedProperties(c,"app'","orders'",3);
        assertEquals(2,result.columnsRead()); assertFalse(result.tableCommentPresent()); assertEquals(0,result.columnCommentsPresent());
        verify(s).setString(1,"app'"); verify(s).setString(2,"orders'"); verify(s).setQueryTimeout(3); verify(s).close(); verify(r).close(); verify(c,never()).close();
    }

 @Test void namespaceRequiresUsageEvidence() throws Exception {
  var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true); when(r.getBoolean(1)).thenReturn(false);
  assertEquals("42501",assertThrows(SQLException.class,()->new DatabaseGreenplumImpl().probeNamespace(c,"app",3)).getSQLState());
  verify(s).setString(1,"app"); verify(s).setQueryTimeout(3); verify(c,never()).close();
 }
 @Test void catalogIsBoundedAndRejectsOverflow() throws Exception {
  var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true); when(r.getString(1)).thenReturn("orders");
  assertThrows(SQLException.class,()->new DatabaseGreenplumImpl().queryCatalogTableList(c,"app",1,3));
  verify(s).setString(1,"app"); verify(s).setMaxRows(2); verify(s).setQueryTimeout(3);
 }
 @Test void accountDirectoryMustRespond() throws Exception {
  var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true); when(r.getBoolean(1)).thenReturn(true); when(r.getString(1)).thenReturn("reader");
  new DatabaseGreenplumImpl().probeAccountPrivileges(c,"app",3); verify(c).prepareStatement(contains("pg_roles"));
 }
 @Test void invalidBoundsFailBeforeJdbc() { assertThrows(IllegalArgumentException.class,()->new DatabaseGreenplumImpl().queryCatalogTableList(null,"app",0,3)); }
}
