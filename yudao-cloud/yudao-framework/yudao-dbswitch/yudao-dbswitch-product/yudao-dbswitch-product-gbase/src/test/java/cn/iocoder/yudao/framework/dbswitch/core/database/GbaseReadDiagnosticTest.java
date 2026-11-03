package cn.iocoder.yudao.framework.dbswitch.core.database;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseGbase8aImpl;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** GBase8a 独立声明的目录契约，不代表 MySQL 或 GBase8c。 */
class GbaseReadDiagnosticTest {
    @Test void extendedPropertiesExplicitlyUseGbaseInformationSchema() throws Exception {
        var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r);
        when(r.next()).thenReturn(true,false); when(r.getString(1)).thenReturn(""); when(r.getInt(2)).thenReturn(2);
        var result=new DatabaseGbase8aImpl().probeExtendedProperties(c,"APP","orders",3);
        assertEquals(2,result.columnsRead()); assertFalse(result.tableCommentPresent()); assertEquals(0,result.columnCommentsPresent());
        verify(c).prepareStatement(contains("information_schema.COLUMNS")); verify(s).setString(1,"APP"); verify(s).setString(2,"orders"); verify(s).setQueryTimeout(3);
        verify(c,never()).close();
    }

 @Test void namespaceIsActuallyEntered() throws Exception {
  var c=mock(Connection.class); var s=mock(Statement.class); when(c.createStatement()).thenReturn(s);
  new DatabaseGbase8aImpl().probeNamespace(c,"app",3); verify(s).execute("USE `app`"); verify(s).setQueryTimeout(3);
 }
 @Test void catalogRejectsOverflow() throws Exception {
  var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true); when(r.getString(1)).thenReturn("orders");
  assertThrows(SQLException.class,()->new DatabaseGbase8aImpl().queryCatalogTableList(c,"app",1,3)); verify(c).prepareStatement(contains("information_schema.TABLES")); verify(s).setMaxRows(2);
 }
 @Test void accountDirectoryIsActuallyRead() throws Exception {
  var c=mock(Connection.class); var s=mock(Statement.class); var r=mock(ResultSet.class);
  when(c.createStatement()).thenReturn(s); when(s.executeQuery(anyString())).thenReturn(r); when(r.next()).thenReturn(true,false); when(r.getString(1)).thenReturn("GRANT SELECT ON app.*");
  new DatabaseGbase8aImpl().probeAccountPrivileges(c,"app",3); verify(s).executeQuery("SHOW GRANTS FOR CURRENT_USER"); verify(c,never()).close();
 }
 @Test void invalidBoundsAreRejected() { assertThrows(IllegalArgumentException.class,()->new DatabaseGbase8aImpl().queryCatalogTableList(null,"app",0,3)); }
}
