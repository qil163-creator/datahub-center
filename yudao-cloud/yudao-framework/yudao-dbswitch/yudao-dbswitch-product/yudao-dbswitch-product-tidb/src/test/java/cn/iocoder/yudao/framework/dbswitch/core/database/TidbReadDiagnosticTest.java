package cn.iocoder.yudao.framework.dbswitch.core.database;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseTidbImpl;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** TiDB 显式启用自己的只读诊断，不能继承 MySQL 的写入或 binlog 假设。 */
class TidbReadDiagnosticTest {
 @Test void namespaceIsActuallyEntered() throws Exception {
  var c=mock(Connection.class); var s=mock(Statement.class); when(c.createStatement()).thenReturn(s);
  new DatabaseTidbImpl().probeNamespace(c,"app",3); verify(s).execute("USE `app`"); verify(s).setQueryTimeout(3);
 }
 @Test void commentQueryIsBoundToExactRelation() throws Exception {
  var c=mock(Connection.class); var s=mock(PreparedStatement.class); var r=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(s); when(s.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true,false); when(r.getString(1)).thenReturn("comment"); when(r.getInt(2)).thenReturn(2); when(r.getInt(3)).thenReturn(1);
  assertEquals(2,new DatabaseTidbImpl().probeExtendedProperties(c,"app","orders",3).columnsRead()); verify(s).setString(1,"app"); verify(s).setString(2,"orders"); verify(s).setQueryTimeout(3);
 }
 @Test void accountGrantsAreRead() throws Exception {
  var c=mock(Connection.class); var s=mock(Statement.class); var r=mock(ResultSet.class); when(c.createStatement()).thenReturn(s); when(s.executeQuery(anyString())).thenReturn(r); when(r.next()).thenReturn(true,false); when(r.getString(1)).thenReturn("GRANT SELECT ON app.*");
  new DatabaseTidbImpl().probeAccountPrivileges(c,"app",3); verify(s).executeQuery("SHOW GRANTS FOR CURRENT_USER");
 }
 @Test void unverifiedWriteAndCdcRemainClosed() { var d=new DatabaseTidbImpl(); assertThrows(SQLFeatureNotSupportedException.class,()->d.temporaryTableDiagnosticPlan("dbswitch_diag_"+"a".repeat(32))); assertThrows(SQLFeatureNotSupportedException.class,()->d.probeCdcReadiness(null,3)); }
}
