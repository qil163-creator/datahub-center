package cn.iocoder.yudao.framework.dbswitch.product.gbase8c;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** 仅测连产品返回分项证据，不误注册元数据或写入能力。 */
class Gbase8cReadDiagnosticTest {
 @Test void extendedCatalogBindsNamespaceAndNoCommentsIsValid() throws Exception {
  var c=mock(Connection.class); var s=mock(Statement.class); var p=mock(PreparedStatement.class); var r=mock(ResultSet.class); var ext=mock(PreparedStatement.class); var er=mock(ResultSet.class);
  when(c.createStatement()).thenReturn(s); when(c.prepareStatement(anyString())).thenReturn(p); when(s.executeQuery(anyString())).thenReturn(r); when(p.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true); when(r.getBoolean(1)).thenReturn(true); when(r.getString(1)).thenReturn("reader");
  when(c.prepareStatement(contains("obj_description"))).thenReturn(ext); when(ext.executeQuery()).thenReturn(er); when(er.next()).thenReturn(true,false); when(er.getInt(3)).thenReturn(2);
  var request=new ConnectionProbeRequest(ProductTypeEnum.GBASE8C,null,"jdbc:postgresql://localhost/test",null,null,null,null,"app'",Map.of(),Duration.ofSeconds(3));
  var result=new Gbase8cProductProvider().products().iterator().next().connectionProbe().diagnose(request,()->c);
  assertTrue(result.checks().stream().anyMatch(x->"EXTENDED_PROPERTIES".equals(x.key())&&"PASS".equals(x.status()))); assertTrue(result.connectionSuccessful());
  verify(ext).setString(1,"app'"); verify(ext).setQueryTimeout(3); verify(ext).close(); verify(er).close();
 }

 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"empty","timeout","denied"})
 void extendedFailureOrNoSampleDoesNotEraseVerifiedSchemaAndNeverLeaksVendorMessage(String scenario) throws Exception {
  var c=mock(Connection.class); var s=mock(Statement.class); var p=mock(PreparedStatement.class); var r=mock(ResultSet.class); var ext=mock(PreparedStatement.class); var er=mock(ResultSet.class);
  when(c.createStatement()).thenReturn(s); when(c.prepareStatement(anyString())).thenReturn(p); when(s.executeQuery(anyString())).thenReturn(r); when(p.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true); when(r.getBoolean(1)).thenReturn(true); when(r.getString(1)).thenReturn("reader");
  when(c.prepareStatement(contains("obj_description"))).thenReturn(ext); when(ext.executeQuery()).thenReturn(er);
  if(!"empty".equals(scenario)) when(ext.executeQuery()).thenThrow(new SQLException("private detail","timeout".equals(scenario)?"HYT00":"42501"));
  var request=new ConnectionProbeRequest(ProductTypeEnum.GBASE8C,null,"jdbc:postgresql://localhost/test",null,null,null,null,"app",Map.of(),Duration.ofSeconds(3));
  var result=new Gbase8cProductProvider().products().iterator().next().connectionProbe().diagnose(request,()->c);
  assertTrue(result.connectionSuccessful()); assertFalse(result.toString().contains("private detail")); assertTrue(result.checks().stream().anyMatch(x->"EXTENDED_PROPERTIES".equals(x.key())&&("empty".equals(scenario)?"SKIPPED":"FAIL").equals(x.status()))); verify(ext).close(); verify(c).close();
 }
 @Test void diagnosticsIncludeNamespaceCatalogAndAccountEvidence() throws Exception {
  var c=mock(Connection.class); var s=mock(Statement.class); var p=mock(PreparedStatement.class); var r=mock(ResultSet.class);
  when(c.createStatement()).thenReturn(s); when(c.prepareStatement(anyString())).thenReturn(p); when(s.executeQuery(anyString())).thenReturn(r); when(p.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true); when(r.getBoolean(1)).thenReturn(true); when(r.getString(1)).thenReturn("reader");
  when(r.getInt(3)).thenReturn(1);
  var descriptor=new Gbase8cProductProvider().products().iterator().next();
  var request=new ConnectionProbeRequest(ProductTypeEnum.GBASE8C,null,"jdbc:gbase8c://localhost/test",null,null,null,null,"app",Map.of(),Duration.ofSeconds(3));
  var result=descriptor.connectionProbe().diagnose(request,()->c);
  for(String key:new String[]{"SCHEMA","TABLES","PRIVILEGES"}) assertTrue(result.checks().stream().anyMatch(check->key.equals(check.key())));
  assertEquals("PASS",result.status()); assertTrue(result.connectionSuccessful());
  verify(c).close(); assertThrows(UnsupportedOperationException.class,descriptor::metadata);
 }
 @Test void overlargeCatalogIsFailedRatherThanSilentlyTruncated() throws Exception {
  var c=mock(Connection.class); var s=mock(Statement.class); var p=mock(PreparedStatement.class); var r=mock(ResultSet.class);
  when(c.createStatement()).thenReturn(s); when(c.prepareStatement(anyString())).thenReturn(p); when(s.executeQuery(anyString())).thenReturn(r); when(p.executeQuery()).thenReturn(r); when(r.next()).thenReturn(true); when(r.getBoolean(1)).thenReturn(true); when(r.getString(1)).thenReturn("reader"); when(r.getInt(1)).thenReturn(101);
  var request=new ConnectionProbeRequest(ProductTypeEnum.GBASE8C,null,"jdbc:gbase8c://localhost/test",null,null,null,null,"app",Map.of(),Duration.ofSeconds(3));
  var result=new Gbase8cProductProvider().products().iterator().next().connectionProbe().diagnose(request,()->c);
  assertTrue(result.connectionSuccessful()); assertEquals("FAIL",result.status()); assertTrue(result.checks().stream().anyMatch(check->"TABLES".equals(check.key())&&"54000".equals(check.sqlState()))); verify(c).close();
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.NullAndEmptySource
 @org.junit.jupiter.params.provider.ValueSource(strings={"42501","08S01","0A000","abcde","42 01","42501\nprivate detail","private detail"})
 void sqlStateIsWhitelistedAndDriverMessageIsNeverReturned(String state) throws Exception {
  var c=mock(Connection.class); var s=mock(Statement.class); var r=mock(ResultSet.class);
  when(c.createStatement()).thenReturn(s); when(s.executeQuery(anyString())).thenReturn(r); when(r.next()).thenReturn(true);
  when(c.prepareStatement(anyString())).thenThrow(new SQLException("private detail",state));
  var request=new ConnectionProbeRequest(ProductTypeEnum.GBASE8C,null,"jdbc:gbase8c://localhost/test",null,null,null,null,"app",Map.of(),Duration.ofSeconds(3));
  var result=new Gbase8cProductProvider().products().iterator().next().connectionProbe().diagnose(request,()->c);
  var target=result.checks().stream().filter(check->"SCHEMA".equals(check.key())).findFirst().orElseThrow();
  assertEquals(state!=null&&state.matches("[A-Z0-9]{5}")?state:null,target.sqlState());
  assertFalse(result.toString().contains("private detail")); assertFalse(result.connectionSuccessful()); verify(c).close();
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={"42501","3F000"})
 void targetFailureVetoesFinalConnectionFlag(String state) throws Exception {
  var c=mock(Connection.class); var s=mock(Statement.class); var p=mock(PreparedStatement.class); var r=mock(ResultSet.class);
  when(c.createStatement()).thenReturn(s); when(s.executeQuery(anyString())).thenReturn(r); when(r.next()).thenReturn(true); when(c.prepareStatement(anyString())).thenReturn(p); when(p.executeQuery()).thenReturn(r); when(r.getString(1)).thenReturn("reader");
  when(c.prepareStatement(contains("has_schema_privilege"))).thenThrow(new SQLException("private detail",state));
  var request=new ConnectionProbeRequest(ProductTypeEnum.GBASE8C,null,"jdbc:gbase8c://localhost/test",null,null,null,null,"app",Map.of(),Duration.ofSeconds(3));
  var result=new Gbase8cProductProvider().products().iterator().next().connectionProbe().diagnose(request,()->c);
  assertFalse(result.connectionSuccessful()); assertTrue(result.checks().stream().anyMatch(check->"TARGET".equals(check.group())&&"SCHEMA".equals(check.key())&&state.equals(check.sqlState())));
  assertTrue(result.checks().stream().anyMatch(check->"connection".equals(check.key())&&"PASS".equals(check.status()))); verify(c).close();
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={"null","empty","blank","missing","exception"})
 void defaultSchemaFailurePreservesBasicEvidence(String scenario) throws Exception {
  var c=mock(Connection.class); var s=mock(Statement.class); var p=mock(PreparedStatement.class); var r=mock(ResultSet.class);
  when(c.createStatement()).thenReturn(s); when(s.executeQuery(anyString())).thenReturn(r); when(r.next()).thenReturn(true); when(c.prepareStatement(anyString())).thenReturn(p); when(p.executeQuery()).thenReturn(r);
  var defaults=mock(ResultSet.class); var defaultStatement=mock(PreparedStatement.class);
  when(c.prepareStatement("SELECT current_schema()")).thenReturn(defaultStatement); when(defaultStatement.executeQuery()).thenReturn(defaults);
  when(defaults.next()).thenReturn(!"missing".equals(scenario));
  when(defaults.getString(1)).thenReturn("empty".equals(scenario)?"":"blank".equals(scenario)?"  ":null);
  if("exception".equals(scenario)) when(c.prepareStatement("SELECT current_schema()")).thenThrow(new SQLException("private detail","42501"));
  var request=new ConnectionProbeRequest(ProductTypeEnum.GBASE8C,null,"jdbc:gbase8c://localhost/test",null,null,null,null,null,Map.of(),Duration.ofSeconds(3));
  var result=new Gbase8cProductProvider().products().iterator().next().connectionProbe().diagnose(request,()->c);
  assertFalse(result.connectionSuccessful()); assertTrue(result.checks().stream().anyMatch(check->"SCHEMA".equals(check.key())&&"FAIL".equals(check.status())));
  assertTrue(result.checks().stream().anyMatch(check->"connection".equals(check.key())&&"PASS".equals(check.status()))); verify(c).close();
 }
 @Test void roleDirectoryFailureDoesNotEraseVerifiedTarget() throws Exception {
  var c=mock(Connection.class); var s=mock(Statement.class); var p=mock(PreparedStatement.class); var r=mock(ResultSet.class);
  when(c.createStatement()).thenReturn(s); when(s.executeQuery(anyString())).thenReturn(r); when(r.next()).thenReturn(true); when(r.getBoolean(1)).thenReturn(true); when(c.prepareStatement(anyString())).thenReturn(p); when(p.executeQuery()).thenReturn(r);
  when(c.prepareStatement(contains("pg_roles"))).thenThrow(new SQLException("denied","42501"));
  var request=new ConnectionProbeRequest(ProductTypeEnum.GBASE8C,null,"jdbc:gbase8c://localhost/test",null,null,null,null,"app",Map.of(),Duration.ofSeconds(3));
  var result=new Gbase8cProductProvider().products().iterator().next().connectionProbe().diagnose(request,()->c);
  assertTrue(result.connectionSuccessful()); assertEquals("FAIL",result.status());
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={"denied","missing","null"})
 void deniedNamespaceCannotBeReportedPass(String scenario) throws Exception {
  var c=mock(Connection.class); var s=mock(Statement.class); var p=mock(PreparedStatement.class); var r=mock(ResultSet.class); var namespace=mock(ResultSet.class);
  when(c.createStatement()).thenReturn(s); when(c.prepareStatement(anyString())).thenReturn(p); when(s.executeQuery(anyString())).thenReturn(r); when(r.next()).thenReturn(true); when(p.executeQuery()).thenReturn(namespace);
  when(namespace.next()).thenReturn(!"missing".equals(scenario)); when(namespace.getBoolean(1)).thenReturn("null".equals(scenario)); when(namespace.wasNull()).thenReturn("null".equals(scenario));
  var request=new ConnectionProbeRequest(ProductTypeEnum.GBASE8C,null,"jdbc:gbase8c://localhost/test",null,null,null,null,"app",Map.of(),Duration.ofSeconds(3));
  var result=new Gbase8cProductProvider().products().iterator().next().connectionProbe().diagnose(request,()->c);
  assertFalse(result.connectionSuccessful()); assertEquals("FAIL",result.status());
  assertTrue(result.checks().stream().anyMatch(check->"SCHEMA".equals(check.key())&&"FAIL".equals(check.status())&&("missing".equals(scenario)?"3F000":"42501").equals(check.sqlState())));
  assertEquals(2,result.checks().stream().filter(check->"SKIPPED".equals(check.status())).count());
  verify(c,never()).prepareStatement(contains("pg_class")); verify(c,never()).prepareStatement(contains("pg_roles")); verify(c).close();
 }
}
