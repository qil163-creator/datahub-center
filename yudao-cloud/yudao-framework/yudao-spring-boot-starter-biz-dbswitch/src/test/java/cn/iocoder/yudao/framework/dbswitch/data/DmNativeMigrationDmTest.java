package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.config.*;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.core.util.*;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.product.dm.*;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
class DmNativeMigrationDmTest {
    private ConfiguredJdbcDrivers drivers;private DbSwitchRuntime runtime;private Connection admin;private String schema;
    @BeforeEach void setup()throws Exception {
        Path jar=Path.of(System.getProperty("datahub.test.dm.driver")).toRealPath();var spec=new DbSwitchProperties.Driver();spec.setPath(jar.toString());spec.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        drivers=new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.DM,spec));runtime=new DbSwitchRuntime(DbSwitchProductRegistry.discover(),DbSwitchOptions.defaults(),drivers);
        admin=open();schema="NATIVE_DM_"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);sql("CREATE SCHEMA "+q(schema));
    }
    @AfterEach void cleanup()throws Exception {try{if(admin!=null)try(var owned=admin){if(schema!=null)sql("DROP SCHEMA "+q(schema)+" CASCADE");}}finally{if(runtime!=null)runtime.close();if(drivers!=null)drivers.close();}}

    @Test void publicFullAndDiffPreserveDefaultsCommentsOrderedKeysAndSecondaryIndexes()throws Exception {
        source();sql("INSERT INTO "+t("S")+"(ID,PART,AMOUNT) VALUES(9007199254740993,2,-0.000000000000000001)");sql("INSERT INTO "+t("S")+" VALUES(9007199254740994,1,99999999999999999999.999999999999999999,'second')");
        var request=request();var result=new MigrationService(request,ignored->{},null,runtime).run();assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());
        assertEquals(rows("S"),rows("T"));assertEquals(nativeShape("S"),nativeShape("T"));
        assertEquals("it'\\s;默认",scalar("SELECT NOTE FROM "+t("T")+" WHERE PART=2"));
        assertThrows(SQLException.class,()->sql("INSERT INTO "+t("T")+" VALUES(9,9,2,'second')"));
        sql("UPDATE "+t("S")+" SET AMOUNT=-9,NOTE='changed' WHERE PART=2");sql("DELETE FROM "+t("S")+" WHERE PART=1");sql("INSERT INTO "+t("S")+" VALUES(4,4,4,'new')");
        request.getTarget().setChangeDataSync(true);request.getTarget().setChangeDataSyncType("PK_ORDER_COMPARE");
        var diff=new MigrationService(request,ignored->{},null,runtime).run();assertTrue(diff.getIfAllSuccess().get(),()->diff.getTableResultList().toString());assertEquals(rows("S"),rows("T"));assertEquals(nativeShape("S"),nativeShape("T"));
    }
    @Test void physicalQuotedColumnsAndMappedDefaultKeepEveryDelimiter()throws Exception {
        String field="v'\";,()",mapped="x'\";,()",target="target'\";,()";
        sql("CREATE TABLE "+t("S")+"(ID BIGINT PRIMARY KEY,"+q(field)+" VARCHAR(100) DEFAULT 'quote'';\\值')");sql("CREATE INDEX "+t("ix'\";,")+" ON "+t("S")+"("+q(field)+" DESC)");
        DmNativeTables.plan(ds(),DmNativeTables.describe(ds(),schema,"S",15),schema,target,Map.of("ID","renamed ID",field,mapped),false,15).execute();
        sql("INSERT INTO "+t(target)+"("+q("renamed ID")+") VALUES(1)");assertEquals("quote';\\值",scalar("SELECT "+q(mapped)+" FROM "+t(target)));
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void explicitClusterKeysAndStandaloneDescendingClusterIndexesRemainClustered(boolean constraint)throws Exception {
        sql("CREATE TABLE "+t("S")+"(A BIGINT NOT NULL,B INT NOT NULL"+(constraint?",CONSTRAINT CP CLUSTER PRIMARY KEY(B,A)":"")+")");
        if(!constraint)sql("CREATE CLUSTER INDEX "+t("CI")+" ON "+t("S")+"(B DESC,A ASC)");
        DmNativeTables.plan(ds(),DmNativeTables.describe(ds(),schema,"S",15),schema,"T",Map.of("A","A","B","B"),false,15).execute();assertEquals(nativeShape("S"),nativeShape("T"));
    }
    @Test void existingMissingUniqueConstraintFailsBeforeAnyTruncate()throws Exception {
        sql("CREATE TABLE "+t("S")+"(ID INT PRIMARY KEY,V INT UNIQUE)");sql("CREATE TABLE "+t("T")+"(ID INT PRIMARY KEY,V INT)");sql("INSERT INTO "+t("T")+" VALUES(9,9)");
        assertThrows(IllegalStateException.class,()->DmNativeTables.plan(ds(),DmNativeTables.describe(ds(),schema,"S",15),schema,"T",Map.of("ID","ID","V","V"),false,15));assertEquals("9",scalar("SELECT ID FROM "+t("T")));
    }
    @Test void indexedColumnCannotDisappearInMapping()throws Exception {
        source();assertThrows(IllegalArgumentException.class,()->DmNativeTables.plan(ds(),DmNativeTables.describe(ds(),schema,"S",15),schema,"T",Map.of("ID","ID","PART","PART","NOTE","NOTE"),false,15));assertEquals(0,tableCount("T"));
    }
    @Test void lostIndexAcknowledgementKeepsPhysicalResourceAndRejectsReplay()throws Exception {
        source();var plan=DmNativeTables.plan(fault("CREATE INDEX"),DmNativeTables.describe(ds(),schema,"S",15),schema,"T",mapping(),false,15);
        assertThrows(UnconfirmedWriteException.class,plan::execute);assertEquals(1,tableCount("T"));assertNotNull(plan.recoveryResources().getFirst().expectedPhysicalIdentity());assertThrows(IllegalStateException.class,plan::execute);
        assertEquals(1,count("SELECT COUNT(*) FROM ALL_INDEXES WHERE OWNER=? AND TABLE_NAME=? AND GENERATED='N'",schema,"T"));
    }
    @Test void confirmedDropThenUnknownCreateNeverRetainsTheOldPhysicalIdentity()throws Exception {
        source();sql("CREATE TABLE "+t("T")+"(ID INT)");var plan=DmNativeTables.plan(fault("CREATE TABLE"),DmNativeTables.describe(ds(),schema,"S",15),schema,"T",mapping(),true,15);
        assertTrue(plan.requiresExclusiveDdlOwnership());assertNotNull(plan.recoveryResources().getFirst().expectedPhysicalIdentity());assertThrows(UnconfirmedWriteException.class,plan::execute);assertNull(plan.recoveryResources().getFirst().expectedPhysicalIdentity());assertEquals(1,tableCount("T"));
    }
    @Test void changedTargetOrLateNameCollisionFailsBeforeDrop()throws Exception {
        source();sql("CREATE TABLE "+t("T")+"(ID INT)");sql("INSERT INTO "+t("T")+" VALUES(9)");
        var plan=DmNativeTables.plan(ds(),DmNativeTables.describe(ds(),schema,"S",15),schema,"T",mapping(),true,15);sql("ALTER TABLE "+t("T")+" ADD ADDED INT");assertThrows(IllegalStateException.class,plan::execute);assertEquals("9",scalar("SELECT ID FROM "+t("T")));
        var plan2=DmNativeTables.plan(ds(),DmNativeTables.describe(ds(),schema,"S",15),schema,"T",mapping(),true,15);
        String name=DmNativeIndexes.targetName(new DmNativeIndexes.Entry("SI",false,false,List.of(new DmNativeIndexes.Key("AMOUNT",true),new DmNativeIndexes.Key("ID",false)),null,null),schema,"T");
        sql("CREATE TABLE "+t("OTHER")+"(ID INT)");sql("CREATE INDEX "+t(name)+" ON "+t("OTHER")+"(ID)");assertThrows(IllegalStateException.class,plan2::execute);assertEquals("9",scalar("SELECT ID FROM "+t("T")));
    }
    @ParameterizedTest @ValueSource(strings={"EXPRESSION_DEFAULT","FUNCTION_CHECK","EXPRESSION_INDEX","GENERATED"})
    void dependentStructuresAreDetectedWithoutDroppingTheirSemantics(String kind)throws Exception {
        switch(kind) {
            case "EXPRESSION_DEFAULT"->sql("CREATE TABLE "+t("S")+"(ID BIGINT DEFAULT (1+2))");
            case "FUNCTION_CHECK"->sql("CREATE TABLE "+t("S")+"(ID BIGINT CHECK(ABS(ID)>0))");
            case "EXPRESSION_INDEX"->{sql("CREATE TABLE "+t("S")+"(ID INT,V VARCHAR(20))");sql("CREATE INDEX "+t("FI")+" ON "+t("S")+"(LOWER(V))");}
            case "GENERATED"->sql("CREATE TABLE "+t("S")+"(ID INT,V AS(ID+1))");
        }
        assertThrows(IllegalStateException.class,()->DmNativeTables.describe(ds(),schema,"S",15));assertEquals(0,tableCount("T"));
    }
    @Test void physicalIdentityRetainsQuotedNamesAndChangesAfterTruncateOrRecreate()throws Exception {
        String table="id'\"; table";sql("CREATE TABLE "+t(table)+"(ID BIGINT)");String first=DmRelationIdentity.physicalFingerprint(admin,schema,table,15);
        sql("INSERT INTO "+t(table)+" VALUES(1)");assertEquals(first,DmRelationIdentity.physicalFingerprint(admin,schema,table,15));sql("TRUNCATE TABLE "+t(table));String second=DmRelationIdentity.physicalFingerprint(admin,schema,table,15);assertNotEquals(first,second);
        sql("DROP TABLE "+t(table));sql("CREATE TABLE "+t(table)+"(ID BIGINT)");assertNotEquals(second,DmRelationIdentity.physicalFingerprint(admin,schema,table,15));
        sql("CREATE VIEW "+t("V")+" AS SELECT ID FROM "+t(table));assertThrows(SQLException.class,()->DmRelationIdentity.physicalFingerprint(admin,schema,"V",15));
    }
    @Test void physicalIdentityIsIndependentOfTheAuthenticatedPrincipal()throws Exception {
        String user="DMUSR_"+UUID.randomUUID().toString().replace("-","").substring(0,20).toUpperCase(Locale.ROOT),secret="Dm!9"+UUID.randomUUID().toString().replace("-","");
        sql("CREATE TABLE "+t("S")+"(ID BIGINT)");String expected=DmRelationIdentity.physicalFingerprint(admin,schema,"S",15);
        sql("CREATE USER "+q(user)+" IDENTIFIED BY "+q(secret));
        try {
            sql("GRANT DBA TO "+q(user));
            try(Connection other=runtime.openConnection(ProductTypeEnum.DM,null,url(),user,secret)) {
                assertEquals(expected,DmRelationIdentity.physicalFingerprint(other,schema,"S",15));
                var source=DmNativeTables.describe(new AbstractDataSource(){@Override public Connection getConnection()throws SQLException{return runtime.openConnection(ProductTypeEnum.DM,null,url(),user,secret);}@Override public Connection getConnection(String u,String p)throws SQLException{throw new SQLException("Fixture uses one principal");}},schema,"S",15);
                assertThrows(IllegalStateException.class,()->DmNativeTables.plan(ds(),source,schema,"S",Map.of("ID","ID"),true,15));
            }
        }finally{sql("DROP USER "+q(user)+" CASCADE");}
    }
    @Test void deniedIdentityCatalogEvidenceCannotProduceAPlan()throws Exception {
        sql("CREATE TABLE "+t("S")+"(ID INT)");
        Connection denied=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
            if(m.getName().equals("prepareStatement")&&a[0] instanceof String text&&text.contains("SYSOPENHISTORY"))throw new SQLException("permission denied","42501");
            try{return m.invoke(admin,a);}catch(InvocationTargetException e){throw e.getCause();}
        });
        assertThrows(SQLException.class,()->DmRelationIdentity.physicalFingerprint(denied,schema,"S",15));
    }
    @Test void clusteredUniqueConstraintRetainsItsClusterAndUniqueness()throws Exception {
        sql("CREATE TABLE "+t("S")+"(ID INT NOT NULL,V INT NOT NULL,CONSTRAINT CU CLUSTER UNIQUE KEY(V,ID))");
        DmNativeTables.plan(ds(),DmNativeTables.describe(ds(),schema,"S",15),schema,"T",Map.of("ID","ID","V","V"),false,15).execute();assertEquals(nativeShape("S"),nativeShape("T"));
        sql("INSERT INTO "+t("T")+" VALUES(1,2)");assertThrows(SQLException.class,()->sql("INSERT INTO "+t("T")+" VALUES(1,2)"));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void publicReplacementRequiresExplicitDdlOwnershipBeforeDroppingTarget(boolean owned)throws Exception {
        source();sql("INSERT INTO "+t("S")+"(ID,PART) VALUES(1,1)");sql("CREATE TABLE "+t("T")+"(ID INT)");sql("INSERT INTO "+t("T")+" VALUES(9)");
        var request=request();request.getTarget().setTargetDrop(true);request.getTarget().setExclusiveTargetDdlOwnership(owned);
        var result=new MigrationService(request,ignored->{},null,runtime).run();assertEquals(owned,result.getIfAllSuccess().get());
        if(owned)assertEquals(rows("S"),rows("T"));else assertEquals("9",scalar("SELECT ID FROM "+t("T")));
    }
    @Test void defaultOnNullSurvivesCaptureAndExplicitNullInsert()throws Exception {
        sql("CREATE TABLE "+t("S")+"(ID INT PRIMARY KEY,V INT DEFAULT ON NULL 7)");
        DmNativeTables.plan(ds(),DmNativeTables.describe(ds(),schema,"S",15),schema,"T",Map.of("ID","ID","V","V"),false,15).execute();
        sql("INSERT INTO "+t("S")+" VALUES(1,NULL)");sql("INSERT INTO "+t("T")+" VALUES(1,NULL)");assertEquals("7",scalar("SELECT V FROM "+t("T")));assertEquals(nativeShape("S"),nativeShape("T"));
    }
    @Test void invalidMappedNameAndDefaultExpressionCannotReachDestructiveDdl()throws Exception {
        sql("CREATE TABLE "+t("S")+"(ID INT PRIMARY KEY,V INT DEFAULT 7)");sql("CREATE TABLE "+t("T")+"(ID INT)");sql("INSERT INTO "+t("T")+" VALUES(9)");
        var source=DmNativeTables.describe(ds(),schema,"S",15);
        assertThrows(IllegalArgumentException.class,()->DmNativeTables.plan(ds(),source,schema,"T",Map.of("ID","ID","V","界".repeat(64)),true,15));
        var json=cn.hutool.json.JSONUtil.parseObj(source.document());json.getJSONArray("columns").getJSONObject(1).set("default","7); DROP TABLE T;--");
        var forged=new cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition(ProductTypeEnum.DM,source.format(),json.toString(),source.fields());
        assertThrows(IllegalArgumentException.class,()->DmNativeTables.plan(ds(),forged,schema,"T",Map.of("ID","ID","V","V"),true,15));assertEquals("9",scalar("SELECT ID FROM "+t("T")));
    }
    @ParameterizedTest @ValueSource(ints={10,24,53})
    void nativeFloatPrecisionIsConfirmedByCatalogAndActualValues(int precision)throws Exception {
        sql("CREATE TABLE "+t("S")+"(ID INT PRIMARY KEY,V FLOAT("+precision+"))");sql("INSERT INTO "+t("S")+" VALUES(1,1.23456789012345)");
        DmNativeTables.plan(ds(),DmNativeTables.describe(ds(),schema,"S",15),schema,"T",Map.of("ID","ID","V","V"),false,15).execute();sql("INSERT INTO "+t("T")+" SELECT * FROM "+t("S"));
        assertEquals(scalar("SELECT DATA_TYPE||':'||COALESCE(CAST(DATA_PRECISION AS VARCHAR(20)),'null')||':'||COALESCE(CAST(DATA_SCALE AS VARCHAR(20)),'null') FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? AND COLUMN_NAME='V'",schema,"S"),scalar("SELECT DATA_TYPE||':'||COALESCE(CAST(DATA_PRECISION AS VARCHAR(20)),'null')||':'||COALESCE(CAST(DATA_SCALE AS VARCHAR(20)),'null') FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? AND COLUMN_NAME='V'",schema,"T"));
        assertEquals(scalar("SELECT V FROM "+t("S")),scalar("SELECT V FROM "+t("T")));
    }
    @ParameterizedTest @ValueSource(strings={"VIEW","FK"})
    void replacementCannotInvalidateAnExternalDependency(String dependency)throws Exception {
        sql("CREATE TABLE "+t("S")+"(ID INT PRIMARY KEY)");sql("CREATE TABLE "+t("T")+"(ID INT PRIMARY KEY)");sql("INSERT INTO "+t("T")+" VALUES(9)");
        var plan=DmNativeTables.plan(ds(),DmNativeTables.describe(ds(),schema,"S",15),schema,"T",Map.of("ID","ID"),true,15);
        if(dependency.equals("VIEW"))sql("CREATE VIEW "+t("DEP")+" AS SELECT ID FROM "+t("T"));else sql("CREATE TABLE "+t("DEP")+"(ID INT REFERENCES "+t("T")+"(ID))");
        assertThrows(IllegalStateException.class,plan::execute);
        assertThrows(IllegalStateException.class,()->DmNativeTables.plan(ds(),DmNativeTables.describe(ds(),schema,"S",15),schema,"T",Map.of("ID","ID"),true,15));assertEquals("9",scalar("SELECT ID FROM "+t("T")));
    }
    @ParameterizedTest @ValueSource(strings={"2","NULL"})
    void everyOpenHistoryMagicMustBeKnownAndConsistent(String secondCurrent)throws Exception {
        sql("CREATE TABLE "+t("S")+"(ID INT)");
        Connection synthetic=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
            if(m.getName().equals("prepareStatement")&&a[0] instanceof String text&&text.contains("SYSOPENHISTORY h")) {
                a=a.clone();a[0]=text.replace("SYSOPENHISTORY h","(SELECT 1 PRIMARY_DB_MAGIC,1 CUR_DB_MAGIC FROM DUAL UNION ALL SELECT 1,"+secondCurrent+" FROM DUAL) h");
            }
            try{return m.invoke(admin,a);}catch(InvocationTargetException e){throw e.getCause();}
        });
        assertThrows(SQLException.class,()->DmRelationIdentity.physicalFingerprint(synthetic,schema,"S",15));
    }
    private void source()throws Exception {
        sql("CREATE TABLE "+t("S")+"(ID BIGINT NOT NULL,PART INT NOT NULL,AMOUNT DECIMAL(38,18) DEFAULT -0.000000000000000001,NOTE VARCHAR(120) DEFAULT 'it''\\s;默认',CONSTRAINT SP NOT CLUSTER PRIMARY KEY(PART,ID),CONSTRAINT SU UNIQUE(NOTE))");
        sql("CREATE INDEX "+t("SI")+" ON "+t("S")+"(AMOUNT DESC,ID ASC)");sql("COMMENT ON TABLE "+t("S")+" IS '表'';\\注释'");sql("COMMENT ON COLUMN "+t("S")+".NOTE IS '列'';\\注释'");
    }
    private MigrationRequest request() {
        var r=new MigrationRequest();var s=new SourceDataSourceProperties();s.setSourceProductType(ProductTypeEnum.DM);s.setUrl(url());s.setUsername("SYSDBA");s.setPassword(password());s.setSourceSchema(schema);s.setSourceIncludes("S");s.setPoolSize(1);s.setRegexTableMapper(List.of(new PatternMapper("^S$","T")));r.getSource().add(s);
        var t=r.getTarget();t.setTargetProductType(ProductTypeEnum.DM);t.setUrl(url());t.setUsername("SYSDBA");t.setPassword(password());t.setTargetSchema(schema);t.setPoolSize(1);t.setWriterEngineInsert(true);return r;
    }
    private List<List<Object>> rows(String table)throws Exception {List<List<Object>> result=new ArrayList<>();try(var s=admin.createStatement();var r=s.executeQuery("SELECT ID,PART,AMOUNT,NOTE FROM "+t(table)+" ORDER BY PART,ID")){while(r.next())result.add(Arrays.asList(r.getLong(1),r.getInt(2),r.getBigDecimal(3),r.getString(4)));}return result;}
    private String nativeShape(String table) {var root=cn.hutool.json.JSONUtil.parseObj(DmNativeTables.describe(ds(),schema,table,15).document());root.remove("identity");var indexes=root.getJSONObject("indexes").getJSONArray("entries");for(Object entry:indexes){var e=(cn.hutool.json.JSONObject)entry;e.remove("name");e.remove("constraintName");}var sorted=indexes.stream().map(Object::toString).sorted().toList();root.set("indexes",sorted);return root.toString();}
    private Map<String,String> mapping(){return Map.of("ID","ID","PART","PART","AMOUNT","AMOUNT","NOTE","NOTE");}
    private DataSource ds(){return new AbstractDataSource(){@Override public Connection getConnection()throws SQLException{return open();}@Override public Connection getConnection(String u,String p)throws SQLException{throw new SQLException("Explicit fixture principal required");}};}
    private DataSource fault(String prefix) {return new AbstractDataSource(){
        @Override public Connection getConnection()throws SQLException {Connection actual=open();return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{try{Object value=m.invoke(actual,a);if(m.getName().equals("createStatement"))return Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{Statement.class},(sp,sm,sa)->{try{Object v=sm.invoke(value,sa);if(sm.getName().equals("execute")&&sa[0] instanceof String sql&&sql.startsWith(prefix))throw new SQLException("Injected acknowledgement loss","08006");return v;}catch(InvocationTargetException e){throw e.getCause();}});return value;}catch(InvocationTargetException e){throw e.getCause();}});}
        @Override public Connection getConnection(String u,String p)throws SQLException{throw new SQLException("Explicit fixture principal required");}};}
    private Connection open()throws SQLException{return runtime.openConnection(ProductTypeEnum.DM,null,url(),"SYSDBA",password());}
    private String scalar(String sql,String...args)throws Exception{try(var p=admin.prepareStatement(sql)){for(int i=0;i<args.length;i++)p.setString(i+1,args[i]);try(var r=p.executeQuery()){assertTrue(r.next());String v=r.getString(1);assertFalse(r.next());return v;}}}
    private int count(String sql,String...args)throws Exception{return Integer.parseInt(scalar(sql,args));}
    private int tableCount(String table)throws Exception{return count("SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER=? AND TABLE_NAME=?",schema,table);}
    private void sql(String sql)throws SQLException{try(var s=admin.createStatement()){s.setQueryTimeout(15);s.execute(sql);}}
    private String t(String table){return DmSqlNames.relation(schema,table);}private static String q(String name){return DmSqlNames.quote(name);}
    private static String url(){return System.getProperty("datahub.test.dm.url");}private static String password(){return System.getenv("DATAHUB_DM_TEST_PASSWORD");}
}
