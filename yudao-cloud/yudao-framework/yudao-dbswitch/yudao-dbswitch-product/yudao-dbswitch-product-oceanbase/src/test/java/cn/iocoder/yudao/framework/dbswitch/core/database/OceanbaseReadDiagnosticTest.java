package cn.iocoder.yudao.framework.dbswitch.core.database;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOceanbaseImpl;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** OceanBase 每次使用本连接的真实模式，不复用其他会话缓存。 */
class OceanbaseReadDiagnosticTest {
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"MYSQL","ORACLE"})
 void extendedPropertiesSelectCatalogFromThisConnectionMode(String modeValue) throws Exception {
  var c=mock(Connection.class); var mode=mock(PreparedStatement.class); var catalog=mock(PreparedStatement.class); var mr=mock(ResultSet.class); var cr=mock(ResultSet.class);
  when(c.prepareStatement(contains("ob_compatibility_mode"))).thenReturn(mode); when(mode.executeQuery()).thenReturn(mr); when(mr.next()).thenReturn(true,false); when(mr.getString(2)).thenReturn(modeValue);
  String marker="MYSQL".equals(modeValue)?"information_schema.COLUMNS":"ALL_COL_COMMENTS";
  when(c.prepareStatement(contains(marker))).thenReturn(catalog); when(catalog.executeQuery()).thenReturn(cr); when(cr.next()).thenReturn(true,false); when(cr.getString(1)).thenReturn(""); when(cr.getInt(2)).thenReturn(2);
  var result=new DatabaseOceanbaseImpl().probeExtendedProperties(c,"APP","ORDERS",3); assertEquals(2,result.columnsRead()); assertFalse(result.tableCommentPresent());
  verify(catalog).setString(1,"APP"); verify(catalog).setString(2,"ORDERS"); verify(catalog).setQueryTimeout(3); verify(c,never()).close();
 }

 @Test void mysqlModeActuallyEntersNamespace() throws Exception {
  var c=mock(Connection.class); var p=mock(PreparedStatement.class); var r=mock(ResultSet.class); var s=mock(Statement.class);
  when(c.prepareStatement(anyString())).thenReturn(p); when(p.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true,false); when(r.getString(2)).thenReturn("MYSQL"); when(c.createStatement()).thenReturn(s);
  new DatabaseOceanbaseImpl().probeNamespace(c,"app",3); verify(s).execute("USE `app`");
 }
 @Test void mysqlCatalogHasIndependentBoundAndOverflowEvidence() throws Exception {
  var c=mock(Connection.class); var mode=mock(PreparedStatement.class); var catalog=mock(PreparedStatement.class); var mr=mock(ResultSet.class); var cr=mock(ResultSet.class);
  when(c.prepareStatement(contains("ob_compatibility_mode"))).thenReturn(mode); when(mode.executeQuery()).thenReturn(mr); when(mr.next()).thenReturn(true,false); when(mr.getString(2)).thenReturn("MYSQL");
  when(c.prepareStatement(contains("information_schema.TABLES"))).thenReturn(catalog); when(catalog.executeQuery()).thenReturn(cr); when(cr.next()).thenReturn(true); when(cr.getString(1)).thenReturn("orders");
  assertEquals("54000",assertThrows(SQLException.class,()->new DatabaseOceanbaseImpl().queryCatalogTableList(c,"app",1,3)).getSQLState()); verify(catalog).setString(1,"app"); verify(catalog).setMaxRows(2); verify(catalog).setQueryTimeout(3);
 }
 @Test void oracleModeUsesBoundOracleCatalogNotMysqlUse() throws Exception {
  var c=mock(Connection.class); var mode=mock(PreparedStatement.class); var catalog=mock(PreparedStatement.class); var mr=mock(ResultSet.class); var cr=mock(ResultSet.class); var s=mock(Statement.class);
  when(c.prepareStatement(contains("ob_compatibility_mode"))).thenReturn(mode); when(mode.executeQuery()).thenReturn(mr); when(mr.next()).thenReturn(true,false); when(mr.getString(2)).thenReturn("ORACLE");
  when(c.prepareStatement(contains("ALL_TABLES"))).thenReturn(catalog); when(catalog.executeQuery()).thenReturn(cr); when(cr.next()).thenReturn(true); when(cr.getString(1)).thenReturn("ORDERS"); when(c.createStatement()).thenReturn(s); when(s.executeQuery(anyString())).thenReturn(cr);
  new DatabaseOceanbaseImpl().probeNamespace(c,"APP",3); verify(s).executeQuery(contains("\"APP\".\"ORDERS\" WHERE 1=0")); verify(s,never()).execute(anyString());
 }
 @Test void mysqlAccountDirectoryIsActuallyRead() throws Exception {
  var c=mock(Connection.class); var mode=mock(PreparedStatement.class); var mr=mock(ResultSet.class); var s=mock(Statement.class); var grants=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(mode); when(mode.executeQuery()).thenReturn(mr); when(mr.next()).thenReturn(true,false); when(mr.getString(2)).thenReturn("MYSQL"); when(c.createStatement()).thenReturn(s); when(s.executeQuery(anyString())).thenReturn(grants); when(grants.next()).thenReturn(true,false); when(grants.getString(1)).thenReturn("GRANT SELECT ON app.*");
  new DatabaseOceanbaseImpl().probeAccountPrivileges(c,"app",3); verify(s).executeQuery("SHOW GRANTS FOR CURRENT_USER"); verify(c,never()).close();
 }
 @Test void invalidBoundsRejectedBeforeModeLookup() { assertThrows(IllegalArgumentException.class,()->new DatabaseOceanbaseImpl().queryCatalogTableList(null,"app",0,3)); }
 @Test void modeFailuresNeverDefaultToMysql() throws Exception {
  var c=mock(Connection.class); when(c.prepareStatement(anyString())).thenThrow(new SQLException("hidden"));
  assertThrows(SQLException.class,()->new DatabaseOceanbaseImpl().probeNamespace(c,"app",3));
 }
}
