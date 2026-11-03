package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.config.*;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.core.util.DmSqlNames;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.product.dm.DmNativeTables;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.jdbc.datasource.AbstractDataSource;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real DM8 evidence: structure copying does not claim to preserve the non-MVCC generator position. */
@Timeout(90)
@EnabledIfSystemProperty(named="datahub.test.dm.url",matches="jdbc:dm://127[.]0[.]0[.]1:[0-9]+")
@EnabledIfSystemProperty(named="datahub.test.dm.driver",matches=".+")
@EnabledIfEnvironmentVariable(named="DATAHUB_DM_TEST_PASSWORD",matches=".+")
class DmIdentityDmTest {
    private ConfiguredJdbcDrivers drivers;private DbSwitchRuntime runtime;private Connection admin;private String schema;
    @BeforeEach void setup()throws Exception {
        Path jar=Path.of(System.getProperty("datahub.test.dm.driver")).toRealPath();var spec=new DbSwitchProperties.Driver();spec.setPath(jar.toString());spec.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        drivers=new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.DM,spec));runtime=new DbSwitchRuntime(DbSwitchProductRegistry.discover(),DbSwitchOptions.defaults(),drivers);
        admin=open();schema="IDENT_DM_"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);sql("CREATE SCHEMA "+q(schema));
    }
    @AfterEach void cleanup()throws Exception {try{if(admin!=null)try(var owned=admin){if(!admin.getAutoCommit()){admin.rollback();admin.setAutoCommit(true);}if(schema!=null)sql("DROP SCHEMA "+q(schema)+" CASCADE");}}finally{if(runtime!=null)runtime.close();if(drivers!=null)drivers.close();}}

    @ParameterizedTest @CsvSource({"INT,7,3","INT,-7,-3","INT,2147483646,1","INT,-2147483647,-1","BIGINT,9007199254740993,17","BIGINT,-9007199254740993,-17","BIGINT,9223372036854775806,1","BIGINT,-9223372036854775807,-1","DECIMAL(38;0),19,5","DECIMAL(38;0),-19,-5"})
    void publicOnlyCreateRetainsTypeSeedStepAndFirstGeneratedValue(String type,long seed,long step)throws Exception {
        source(type.replace(';',','),seed,step);sql("INSERT INTO "+t("S")+"(V) VALUES(1)");sql("DELETE FROM "+t("S"));
        var request=request();request.getTarget().setOnlyCreate(true);
        var result=new MigrationService(request,ignored->{},null,runtime).run();assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());
        assertEquals(List.of(Long.toString(seed),Long.toString(step)),definition("T"));assertEquals(0,count("SELECT COUNT(*) FROM "+t("T")));
        assertEquals(scalar("SELECT DATA_TYPE||':'||COALESCE(CAST(DATA_PRECISION AS VARCHAR(20)),'null')||':'||COALESCE(CAST(DATA_SCALE AS VARCHAR(20)),'null') FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME='S' AND COLUMN_NAME='ID'",schema),scalar("SELECT DATA_TYPE||':'||COALESCE(CAST(DATA_PRECISION AS VARCHAR(20)),'null')||':'||COALESCE(CAST(DATA_SCALE AS VARCHAR(20)),'null') FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME='T' AND COLUMN_NAME='ID'",schema));
        sql("INSERT INTO "+t("T")+"(V) VALUES(2)");assertEquals(Long.toString(seed),scalar("SELECT ID FROM "+t("T")));
        if(seed==Long.MAX_VALUE-1&&step==1) {
            // This DM release reserves Long.MAX_VALUE: definition and first value survive, the next value is exhausted.
            assertEquals(-7070,assertThrows(SQLException.class,()->sql("INSERT INTO "+t("T")+"(V) VALUES(3)")).getErrorCode());
            assertEquals(-7070,assertThrows(SQLException.class,()->sql("INSERT INTO "+t("S")+"(V) VALUES(3)")).getErrorCode());
            assertEquals(1,count("SELECT COUNT(*) FROM "+t("T")));
        } else {sql("INSERT INTO "+t("T")+"(V) VALUES(3)");assertEquals(Long.toString(Math.addExact(seed,step)),scalar("SELECT ID FROM "+t("T")+" WHERE V=3"));}
    }
    @ParameterizedTest @ValueSource(strings={"FULL_NEW","FULL_REPLACE","FULL_TRUNCATE","DIFF"})
    void dataModesRejectBeforeAnyTargetDdlTruncateOrWrite(String mode)throws Exception {
        source("BIGINT",7,3);sql("INSERT INTO "+t("S")+"(V)VALUES(1)");
        if(!mode.equals("FULL_NEW")){sql("CREATE TABLE "+t("T")+"(ID BIGINT IDENTITY(7,3) PRIMARY KEY,V INT)");sql("SET IDENTITY_INSERT "+t("T")+" ON");sql("INSERT INTO "+t("T")+"(ID,V) VALUES(100,9)");sql("SET IDENTITY_INSERT "+t("T")+" OFF");}
        var request=request();request.getTarget().setTargetDrop(mode.equals("FULL_REPLACE"));request.getTarget().setExclusiveTargetDdlOwnership(true);
        if(mode.equals("DIFF")){request.getTarget().setChangeDataSync(true);request.getTarget().setChangeDataSyncType("PK_ORDER_COMPARE");}
        var result=new MigrationService(request,ignored->{},null,runtime).run();assertFalse(result.getIfAllSuccess().get());
        if(mode.equals("FULL_NEW"))assertEquals(0,count("SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER=? AND TABLE_NAME='T'",schema));
        else {assertEquals("100",scalar("SELECT ID FROM "+t("T")));assertEquals("9",scalar("SELECT V FROM "+t("T")));assertEquals("100",current("T"));}
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void onlyCreateReplacementRequiresDdlOwnershipAndStartsAtOriginalSeed(boolean owned)throws Exception {
        source("BIGINT",-7,-3);sql("CREATE TABLE "+t("T")+"(ID INT)");sql("INSERT INTO "+t("T")+" VALUES(99)");
        var request=request();request.getTarget().setOnlyCreate(true);request.getTarget().setTargetDrop(true);request.getTarget().setExclusiveTargetDdlOwnership(owned);
        var result=new MigrationService(request,ignored->{},null,runtime).run();assertEquals(owned,result.getIfAllSuccess().get());
        if(owned){sql("INSERT INTO "+t("T")+"(V)VALUES(2)");assertEquals("-7",scalar("SELECT ID FROM "+t("T")));}else assertEquals("99",scalar("SELECT ID FROM "+t("T")));
    }
    @Test void mappedQuotedIdentityKeepsPreparedCatalogResolution()throws Exception {
        String field="ID'\";",target="T.'\";";sql("CREATE TABLE "+t("S")+"("+q(field)+" BIGINT IDENTITY(-13,-7) PRIMARY KEY,V INT)");
        var plan=DmNativeTables.plan(ds(false),DmNativeTables.describe(ds(false),schema,"S",15),schema,target,Map.of(field,"mapped'\";","V","V"),false,15);plan.execute();
        sql("INSERT INTO "+t(target)+"(V) VALUES(1)");assertEquals("-13",scalar("SELECT "+q("mapped'\";")+" FROM "+t(target)));
        assertThrows(IllegalArgumentException.class,()->DmNativeTables.plan(ds(false),DmNativeTables.describe(ds(false),schema,"S",15),schema,"OMITTED",Map.of("V","V"),false,15));
    }
    @Test void lostCreateAcknowledgementRemainsUnknownAndCannotReplay()throws Exception {
        source("BIGINT",7,3);var plan=DmNativeTables.plan(ds(true),DmNativeTables.describe(ds(false),schema,"S",15),schema,"T",Map.of("ID","ID","V","V"),false,15);
        assertThrows(UnconfirmedWriteException.class,plan::execute);assertNull(plan.recoveryResources().getFirst().expectedPhysicalIdentity());assertEquals(1,count("SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER=? AND TABLE_NAME='T'",schema));assertThrows(IllegalStateException.class,plan::execute);
        assertEquals(List.of("7","3"),definition("T"));
    }
    @ParameterizedTest @ValueSource(ints={1,-1})
    void nativePositionReadCannotDistinguishUnusedSeedFromRolledBackSeed(int direction)throws Exception {
        long seed=7L*direction,step=3L*direction;source("BIGINT",seed,step);sql("CREATE TABLE "+t("T")+"(ID BIGINT IDENTITY("+seed+","+step+") PRIMARY KEY,V INT)");
        var unused=position("S");admin.setAutoCommit(false);sql("INSERT INTO "+t("S")+"(V) VALUES(1)");admin.rollback();admin.setAutoCommit(true);
        assertEquals(0,count("SELECT COUNT(*) FROM "+t("S")));assertEquals(unused,position("S"));assertEquals(position("S"),position("T"));
        sql("INSERT INTO "+t("S")+"(V) VALUES(2)");sql("INSERT INTO "+t("T")+"(V) VALUES(2)");
        assertEquals(Long.toString(seed+step),scalar("SELECT ID FROM "+t("S")));assertEquals(Long.toString(seed),scalar("SELECT ID FROM "+t("T")));
    }
    @ParameterizedTest @ValueSource(ints={1,-1})
    void explicitValuesAdvanceInIncrementDirectionAndDeleteDoesNotResetButTruncateDoes(int direction)throws Exception {
        long seed=7L*direction,step=3L*direction,value=100L*direction;source("BIGINT",seed,step);
        sql("SET IDENTITY_INSERT "+t("S")+" ON");sql("INSERT INTO "+t("S")+"(ID,V) VALUES("+value+",1)");sql("SET IDENTITY_INSERT "+t("S")+" OFF");assertEquals(Long.toString(value),current("S"));
        sql("DELETE FROM "+t("S"));sql("INSERT INTO "+t("S")+"(V) VALUES(2)");assertEquals(Long.toString(value+step),scalar("SELECT ID FROM "+t("S")));
        sql("TRUNCATE TABLE "+t("S"));sql("INSERT INTO "+t("S")+"(V) VALUES(3)");assertEquals(Long.toString(seed),scalar("SELECT ID FROM "+t("S")));
    }
    @Test void onlyCreateExistingMatchingIdentityNeverRewindsItsCurrentPosition()throws Exception {
        source("BIGINT",7,3);sql("CREATE TABLE "+t("T")+"(ID BIGINT IDENTITY(7,3) PRIMARY KEY,V INT)");
        sql("SET IDENTITY_INSERT "+t("T")+" ON");sql("INSERT INTO "+t("T")+"(ID,V)VALUES(100,9)");sql("SET IDENTITY_INSERT "+t("T")+" OFF");
        var request=request();request.getTarget().setOnlyCreate(true);var result=new MigrationService(request,ignored->{},null,runtime).run();
        assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());assertEquals("100",current("T"));
        sql("INSERT INTO "+t("T")+"(V)VALUES(10)");assertEquals("103",scalar("SELECT ID FROM "+t("T")+" WHERE V=10"));
    }
    @ParameterizedTest @ValueSource(strings={"ZERO_STEP","OUT_OF_RANGE","SQL_TEXT"})
    void invalidIdentityEvidenceFailsBeforeReplacement(String invalid)throws Exception {
        source("INT",7,3);sql("CREATE TABLE "+t("T")+"(ID INT)");sql("INSERT INTO "+t("T")+" VALUES(99)");
        var original=DmNativeTables.describe(ds(false),schema,"S",15);var json=cn.hutool.json.JSONUtil.parseObj(original.document());
        var generator=json.getJSONArray("columns").getJSONObject(0).getJSONObject("generator");
        if(invalid.equals("ZERO_STEP"))generator.set("increment","0");else generator.set("seed",invalid.equals("OUT_OF_RANGE")?"2147483648":"7);DROP TABLE T;--");
        var forged=new cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition(ProductTypeEnum.DM,original.format(),json.toString(),original.fields());
        assertThrows(IllegalArgumentException.class,()->DmNativeTables.plan(ds(false),forged,schema,"T",Map.of("ID","ID","V","V"),true,15));assertEquals("99",scalar("SELECT ID FROM "+t("T")));
    }
    @Test void onlyCreateCannotSilentlyAcceptAnUnimplementedDataPositionOption()throws Exception {
        source("BIGINT",7,3);var request=request();request.getTarget().setOnlyCreate(true);request.getTarget().setPreserveIdentityValues(true);request.getTarget().setExclusiveTargetOwnership(true);
        assertThrows(IllegalArgumentException.class,()->new MigrationService(request,ignored->{},null,runtime));
        assertEquals(0,count("SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER=? AND TABLE_NAME='T'",schema));
    }
    private void source(String type,long seed,long step)throws Exception{sql("CREATE TABLE "+t("S")+"(ID "+type+" IDENTITY("+seed+","+step+") PRIMARY KEY,V INT)");}
    private List<String> definition(String table)throws Exception{return List.of(scalar("SELECT IDENT_SEED(?) FROM DUAL",t(table)),scalar("SELECT IDENT_INCR(?) FROM DUAL",t(table)));}
    private List<String> position(String table)throws Exception {var result=new ArrayList<>(definition(table));result.add(current(table));return result;}
    private String current(String table)throws Exception{return scalar("SELECT IDENT_CURRENT(?) FROM DUAL",t(table));}
    private MigrationRequest request(){var r=new MigrationRequest();var s=new SourceDataSourceProperties();s.setSourceProductType(ProductTypeEnum.DM);s.setUrl(url());s.setUsername("SYSDBA");s.setPassword(password());s.setSourceSchema(schema);s.setSourceIncludes("S");s.setPoolSize(1);s.setRegexTableMapper(List.of(new PatternMapper("^S$","T")));r.getSource().add(s);var t=r.getTarget();t.setTargetProductType(ProductTypeEnum.DM);t.setUrl(url());t.setUsername("SYSDBA");t.setPassword(password());t.setTargetSchema(schema);t.setPoolSize(1);t.setWriterEngineInsert(true);return r;}
    private DataSource ds(boolean lost){return new AbstractDataSource(){@Override public Connection getConnection()throws SQLException{Connection actual=open();if(!lost)return actual;return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{try{Object v=m.invoke(actual,a);if(m.getName().equals("createStatement"))return Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{Statement.class},(sp,sm,sa)->{try{Object r=sm.invoke(v,sa);if(sm.getName().equals("execute")&&sa[0] instanceof String sql&&sql.startsWith("CREATE TABLE"))throw new SQLException("Injected acknowledgement loss","08006");return r;}catch(InvocationTargetException e){throw e.getCause();}});return v;}catch(InvocationTargetException e){throw e.getCause();}});}@Override public Connection getConnection(String u,String p)throws SQLException{throw new SQLException("Explicit fixture principal required");}};}
    private Connection open()throws SQLException{return runtime.openConnection(ProductTypeEnum.DM,null,url(),"SYSDBA",password());}
    private String scalar(String sql,String...args)throws SQLException{try(var p=admin.prepareStatement(sql)){p.setQueryTimeout(15);for(int i=0;i<args.length;i++)p.setString(i+1,args[i]);try(var r=p.executeQuery()){assertTrue(r.next());String v=r.getString(1);assertFalse(r.next());return v;}}}
    private int count(String sql,String...args)throws SQLException{return Integer.parseInt(scalar(sql,args));}
    private void sql(String sql)throws SQLException{try(var s=admin.createStatement()){s.setQueryTimeout(15);s.execute(sql);}}
    private String t(String table){return DmSqlNames.relation(schema,table);}private static String q(String name){return DmSqlNames.quote(name);}
    private static String url(){return System.getProperty("datahub.test.dm.url");}private static String password(){return System.getenv("DATAHUB_DM_TEST_PASSWORD");}
}
