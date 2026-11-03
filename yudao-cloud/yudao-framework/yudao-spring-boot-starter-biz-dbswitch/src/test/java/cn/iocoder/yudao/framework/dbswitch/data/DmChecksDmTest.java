package cn.iocoder.yudao.framework.dbswitch.data;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.config.*;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.core.util.*;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.product.dm.*;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.jdbc.datasource.AbstractDataSource;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
@EnabledIfSystemProperty(named="datahub.test.dm.url",matches="jdbc:dm://127[.]0[.]0[.]1:[0-9]+")
@EnabledIfSystemProperty(named="datahub.test.dm.driver",matches=".+")
@EnabledIfEnvironmentVariable(named="DATAHUB_DM_TEST_PASSWORD",matches=".+")
class DmChecksDmTest {
    private ConfiguredJdbcDrivers drivers;private DbSwitchRuntime runtime;private Connection admin;private String schema;
    @BeforeEach void setup()throws Exception {
        Path jar=Path.of(System.getProperty("datahub.test.dm.driver")).toRealPath();var spec=new DbSwitchProperties.Driver();spec.setPath(jar.toString());spec.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        drivers=new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.DM,spec));runtime=new DbSwitchRuntime(DbSwitchProductRegistry.discover(),DbSwitchOptions.defaults(),drivers);
        admin=open();schema="CHECK_DM_"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);sql("CREATE SCHEMA "+q(schema));
    }
    @AfterEach void cleanup()throws Exception {try{if(admin!=null)try(var owned=admin){if(schema!=null)sql("DROP SCHEMA "+q(schema)+" CASCADE");}}finally{if(runtime!=null)runtime.close();if(drivers!=null)drivers.close();}}

    @Test void fullAndDiffEnforceMultiColumnDecimalAndNullPredicates()throws Exception {
        source();sql("INSERT INTO "+t("S")+" VALUES(1,NULL,NULL,NULL)");sql("INSERT INTO "+t("S")+" VALUES(2,-0.000000000000000001,-0.000000000000000002,'a''b')");
        var request=request();var full=new MigrationService(request,ignored->{},null,runtime).run();assertTrue(full.getIfAllSuccess().get(),()->full.getTableResultList().toString());assertEquals(rows("S"),rows("T"));assertEquals(checkShapes("S"),checkShapes("T"));
        assertThrows(SQLException.class,()->sql("INSERT INTO "+t("T")+" VALUES(3,-0.000000000000000002,NULL,'ok')"));
        assertThrows(SQLException.class,()->sql("INSERT INTO "+t("T")+" VALUES(3,1,2,'ok')"));
        assertThrows(SQLException.class,()->sql("INSERT INTO "+t("T")+" VALUES(3,1,0,'bad')"));
        sql("UPDATE "+t("S")+" SET A=10,B=9,V='semi;' WHERE ID=1");sql("DELETE FROM "+t("S")+" WHERE ID=2");sql("INSERT INTO "+t("S")+" VALUES(3,NULL,1,'ok')");
        request.getTarget().setChangeDataSync(true);request.getTarget().setChangeDataSyncType("PK_ORDER_COMPARE");var diff=new MigrationService(request,ignored->{},null,runtime).run();assertTrue(diff.getIfAllSuccess().get(),()->diff.getTableResultList().toString());assertEquals(rows("S"),rows("T"));assertEquals(checkShapes("S"),checkShapes("T"));
    }
    @Test void mappingPreservesQuotedColumnsAndLiteralBackslashes()throws Exception {
        String field="v'\"\\;()",target="x'\"\\;()";sql("CREATE TABLE "+t("S")+"(ID INT PRIMARY KEY,"+q(field)+" VARCHAR(40),CONSTRAINT CQ CHECK("+q(field)+" IN ('a''b','x\\y','semi;')))");
        DmNativeTables.plan(ds(false),describe("S"),schema,"T",Map.of("ID","new ID",field,target),false,15).execute();
        for(String text:List.of("a'b","x\\y","semi;"))try(var p=admin.prepareStatement("INSERT INTO "+t("T")+" VALUES(?,?)")){p.setInt(1,text.length());p.setString(2,text);if(text.equals("x\\y"))p.setInt(1,99);p.executeUpdate();}
        assertThrows(SQLException.class,()->sql("INSERT INTO "+t("T")+" VALUES(7,'invalid')"));assertEquals(3,count("SELECT COUNT(*) FROM "+t("T")));
        var checks=JSONUtil.parseObj(describe("T").document()).getJSONObject("checks").getJSONArray("entries");assertEquals(List.of(target),checks.getJSONObject(0).getJSONArray("columns").toList(String.class));
    }
    @Test void disabledConstraintRemainsDisabledIncludingExistingInvalidRows()throws Exception {
        sql("CREATE TABLE "+t("S")+"(ID INT PRIMARY KEY,A INT,CONSTRAINT CD CHECK(A>0))");sql("ALTER TABLE "+t("S")+" DISABLE CONSTRAINT CD");sql("INSERT INTO "+t("S")+" VALUES(1,-1)");
        var request=request();var result=new MigrationService(request,ignored->{},null,runtime).run();assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());
        assertEquals(List.of("DISABLED:NOT VALIDATED"),states("T"));sql("INSERT INTO "+t("T")+" VALUES(2,-2)");assertEquals(2,count("SELECT COUNT(*) FROM "+t("T")));assertEquals(checkShapes("S"),checkShapes("T"));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void unsupportedSourceCheckCannotDropOrTruncateAnExistingTarget(boolean replace)throws Exception {
        sql("CREATE TABLE "+t("S")+"(ID INT PRIMARY KEY,A INT,CONSTRAINT CU CHECK(ABS(A)>0))");sql("CREATE TABLE "+t("T")+"(ID INT PRIMARY KEY,A INT)");sql("INSERT INTO "+t("T")+" VALUES(9,9)");
        String before=DmRelationIdentity.physicalFingerprint(admin,schema,"T",15);var request=request();request.getTarget().setTargetDrop(replace);request.getTarget().setTargetTruncateData(!replace);request.getTarget().setExclusiveTargetDdlOwnership(true);
        var result=new MigrationService(request,ignored->{},null,runtime).run();assertFalse(result.getIfAllSuccess().get());assertEquals(before,DmRelationIdentity.physicalFingerprint(admin,schema,"T",15));assertEquals("9",scalar("SELECT A FROM "+t("T")));
    }
    @Test void missingOrDisabledExistingTargetCheckFailsBeforeTruncate()throws Exception {
        sql("CREATE TABLE "+t("S")+"(ID INT PRIMARY KEY,A INT,CONSTRAINT CS CHECK(A>0))");sql("CREATE TABLE "+t("T")+"(ID INT PRIMARY KEY,A INT,CONSTRAINT CT CHECK(A>0) DISABLE)");sql("INSERT INTO "+t("T")+" VALUES(9,-1)");
        var request=request();request.getTarget().setTargetTruncateData(true);var result=new MigrationService(request,ignored->{},null,runtime).run();assertFalse(result.getIfAllSuccess().get());assertEquals("-1",scalar("SELECT A FROM "+t("T")));
    }
    @Test void referencedColumnRemovalAndForgedExpressionFailBeforeReplacement()throws Exception {
        source();sql("CREATE TABLE "+t("T")+"(ID INT)");sql("INSERT INTO "+t("T")+" VALUES(9)");var evidence=describe("S");
        assertThrows(IllegalArgumentException.class,()->DmNativeTables.plan(ds(false),evidence,schema,"T",Map.of("ID","ID","A","A","V","V"),true,15));
        var json=JSONUtil.parseObj(evidence.document());json.getJSONObject("checks").getJSONArray("entries").getJSONObject(0).set("expression","A>0); DROP TABLE T;--");
        var forged=new NativeTableDefinition(ProductTypeEnum.DM,evidence.format(),json.toString(),evidence.fields());assertThrows(IllegalArgumentException.class,()->DmNativeTables.plan(ds(false),forged,schema,"T",mapping(),true,15));assertEquals("9",scalar("SELECT ID FROM "+t("T")));
    }
    @Test void checkNameCollisionIsCheckedAgainBeforeDrop()throws Exception {
        source();sql("CREATE TABLE "+t("T")+"(ID INT)");sql("INSERT INTO "+t("T")+" VALUES(9)");var plan=DmNativeTables.plan(ds(false),describe("S"),schema,"T",mapping(),true,15);
        String name=DmNativeChecks.targetName(new DmNativeChecks.Entry("CA","A>0",Set.of("A"),true),schema,"T");sql("CREATE TABLE "+t("OTHER")+"(A INT,CONSTRAINT "+q(name)+" CHECK(A>0))");
        assertThrows(IllegalStateException.class,plan::execute);assertEquals("9",scalar("SELECT ID FROM "+t("T")));assertThrows(IllegalStateException.class,()->DmNativeTables.plan(ds(false),describe("S"),schema,"T",mapping(),true,15));
    }
    @Test void changedCheckStateIsDetectedBetweenPlanAndDrop()throws Exception {
        source();DmNativeTables.plan(ds(false),describe("S"),schema,"T",mapping(),false,15).execute();sql("INSERT INTO "+t("T")+" VALUES(9,9,1,'ok')");
        var plan=DmNativeTables.plan(ds(false),describe("S"),schema,"T",mapping(),true,15);String name=DmNativeChecks.targetName(new DmNativeChecks.Entry("CA","A>0",Set.of("A"),true),schema,"T");sql("ALTER TABLE "+t("T")+" DISABLE CONSTRAINT "+q(name));
        assertThrows(IllegalStateException.class,plan::execute);assertEquals("9",scalar("SELECT ID FROM "+t("T")));
    }
    @Test void unknownCreateRetainsResourceEvidenceWithoutClaimingRollbackOrRetry()throws Exception {
        source();var plan=DmNativeTables.plan(ds(true),describe("S"),schema,"T",mapping(),false,15);assertThrows(UnconfirmedWriteException.class,plan::execute);
        assertEquals(2,states("T").size());assertNull(plan.recoveryResources().getFirst().expectedPhysicalIdentity());assertThrows(IllegalStateException.class,plan::execute);assertThrows(SQLException.class,()->sql("INSERT INTO "+t("T")+" VALUES(1,-1,NULL,'ok')"));
    }
    @ParameterizedTest @ValueSource(strings={"A BETWEEN -1 AND 10","A NOT BETWEEN -1 AND 1","NOT(A=0)","A NOT IN(0,1)","A<>0 AND A<=10"})
    void supportedPredicateOperatorsHaveTheSameRealConstraintBehavior(String predicate)throws Exception {
        sql("CREATE TABLE "+t("S")+"(ID INT,A INT,CONSTRAINT C CHECK("+predicate+"))");DmNativeTables.plan(ds(false),describe("S"),schema,"T",Map.of("ID","ID","A","A"),false,15).execute();
        for(Integer value:Arrays.asList(null,-2,-1,0,1,2,10,11))assertEquals(accepts("S",value),accepts("T",value),"predicate behavior for "+value);assertEquals(checkShapes("S"),checkShapes("T"));
    }
    private boolean accepts(String table,Integer value)throws SQLException{try(var p=admin.prepareStatement("INSERT INTO "+t(table)+" VALUES(1,?)")){if(value==null)p.setNull(1,Types.INTEGER);else p.setInt(1,value);p.executeUpdate();return true;}catch(SQLException rejected){return false;}}
    private void source()throws Exception {sql("CREATE TABLE "+t("S")+"(ID BIGINT PRIMARY KEY,A DECIMAL(38,18),B DECIMAL(38,18),V VARCHAR(40),CONSTRAINT CA CHECK(A>=-0.000000000000000001 AND(B IS NULL OR B<A)),CONSTRAINT CV CHECK(V IN('ok','a''b','semi;')))");}
    private Map<String,String> mapping(){return Map.of("ID","ID","A","A","B","B","V","V");}
    private NativeTableDefinition describe(String table){return DmNativeTables.describe(ds(false),schema,table,15);}
    private List<String> checkShapes(String table){return JSONUtil.parseObj(describe(table).document()).getJSONObject("checks").getJSONArray("entries").stream().map(o->{var e=(cn.hutool.json.JSONObject)o;return e.getStr("expression")+":"+e.getBool("enabled");}).sorted().toList();}
    private List<String> states(String table)throws SQLException{var out=new ArrayList<String>();try(var p=admin.prepareStatement("SELECT status,validated FROM ALL_CONSTRAINTS WHERE owner=? AND table_name=? AND constraint_type='C' ORDER BY constraint_name")){p.setString(1,schema);p.setString(2,table);try(var r=p.executeQuery()){while(r.next())out.add(r.getString(1)+":"+r.getString(2));}}return out;}
    private List<List<Object>> rows(String table)throws SQLException{var out=new ArrayList<List<Object>>();try(var s=admin.createStatement();var r=s.executeQuery("SELECT ID,A,B,V FROM "+t(table)+" ORDER BY ID")){while(r.next())out.add(Arrays.asList(r.getLong(1),r.getBigDecimal(2),r.getBigDecimal(3),r.getString(4)));}return out;}
    private MigrationRequest request(){var r=new MigrationRequest();var s=new SourceDataSourceProperties();s.setSourceProductType(ProductTypeEnum.DM);s.setUrl(url());s.setUsername("SYSDBA");s.setPassword(password());s.setSourceSchema(schema);s.setSourceIncludes("S");s.setPoolSize(1);s.setRegexTableMapper(List.of(new PatternMapper("^S$","T")));r.getSource().add(s);var t=r.getTarget();t.setTargetProductType(ProductTypeEnum.DM);t.setUrl(url());t.setUsername("SYSDBA");t.setPassword(password());t.setTargetSchema(schema);t.setPoolSize(1);t.setWriterEngineInsert(true);return r;}
    private DataSource ds(boolean lostCreateAck){return new AbstractDataSource(){@Override public Connection getConnection()throws SQLException{var actual=open();if(!lostCreateAck)return actual;return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{try{Object value=m.invoke(actual,a);if(m.getName().equals("createStatement"))return Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{Statement.class},(sp,sm,sa)->{try{Object result=sm.invoke(value,sa);if(sm.getName().equals("execute")&&sa[0] instanceof String text&&text.startsWith("CREATE TABLE"))throw new SQLException("Injected acknowledgement loss","08006");return result;}catch(InvocationTargetException e){throw e.getCause();}});return value;}catch(InvocationTargetException e){throw e.getCause();}});}@Override public Connection getConnection(String u,String p)throws SQLException{throw new SQLException("One explicit fixture principal required");}};}
    private Connection open()throws SQLException{return runtime.openConnection(ProductTypeEnum.DM,null,url(),"SYSDBA",password());}
    private void sql(String text)throws SQLException{try(var s=admin.createStatement()){s.setQueryTimeout(15);s.execute(text);}}
    private String scalar(String text)throws SQLException{try(var s=admin.createStatement();var r=s.executeQuery(text)){assertTrue(r.next());String value=r.getString(1);assertFalse(r.next());return value;}}
    private int count(String text)throws SQLException{return Integer.parseInt(scalar(text));}
    private String t(String table){return q(schema)+'.'+q(table);}private static String q(String value){return DmSqlNames.quote(value);}
    private static String url(){return System.getProperty("datahub.test.dm.url");}private static String password(){return System.getenv("DATAHUB_DM_TEST_PASSWORD");}
}
