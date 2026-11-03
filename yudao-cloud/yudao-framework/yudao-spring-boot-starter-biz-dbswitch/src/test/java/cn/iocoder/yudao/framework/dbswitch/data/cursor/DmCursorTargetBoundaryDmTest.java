package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.config.*;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.util.DmSqlNames;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.product.dm.DmTargetSideEffects;
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
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** DM targets whose generated values, triggers or referential actions would change replayed/diffed rows. */
@Timeout(120)
@EnabledIfSystemProperty(named="datahub.test.dm.url",matches="jdbc:dm://127[.]0[.]0[.]1:[0-9]+")
@EnabledIfSystemProperty(named="datahub.test.dm.driver",matches=".+")
@EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
@EnabledIfEnvironmentVariable(named="DATAHUB_DM_TEST_PASSWORD",matches=".+")
class DmCursorTargetBoundaryDmTest {
    private ConfiguredJdbcDrivers drivers;private DbSwitchRuntime runtime;private String schema;private Connection dm,pg;
    private final BatchLimits limits=new BatchLimits(2,65536,16384,65535,1000);private final AtomicReference<CursorCheckpoint> saved=new AtomicReference<>();
    private final List<String> fields=List.of("ID","WM","V");private SourceGenerationPin pin;
    @BeforeEach void setup()throws Exception {
        Path jar=Path.of(System.getProperty("datahub.test.dm.driver")).toRealPath();var spec=new DbSwitchProperties.Driver();spec.setPath(jar.toString());spec.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        drivers=new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.DM,spec));runtime=new DbSwitchRuntime(DbSwitchProductRegistry.discover(),DbSwitchOptions.defaults(),drivers);
        dm=open(ProductTypeEnum.DM);pg=open(ProductTypeEnum.POSTGRESQL);schema="DM_TGT_"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
        sql(dm,"CREATE SCHEMA "+q(schema));sql(pg,"CREATE SCHEMA "+q(schema));pin=new SourceGenerationPin(schema,UUID.randomUUID().toString(),UUID.randomUUID().toString());
    }
    @AfterEach void cleanup()throws Exception {try{if(pg!=null)try(var c=pg){sql(c,"DROP SCHEMA "+q(schema)+" CASCADE");}}finally{try{if(dm!=null)try(var c=dm){sql(c,"DROP SCHEMA "+q(schema)+" CASCADE");}}finally{if(runtime!=null)runtime.close();if(drivers!=null)drivers.close();}}}

    @ParameterizedTest @CsvSource({"DM,IDENTITY","POSTGRESQL,IDENTITY","DM,DEFAULT_ON_NULL","POSTGRESQL,DEFAULT_ON_NULL"})
    void generatedTargetValuesFailBeforeTheFirstCursorWrite(ProductTypeEnum source,String kind)throws Exception {
        source(source);generated(kind);
        assertTargetRejected(source);assertNull(saved.get());assertEquals(List.of("100:0:1"),rows("T"));
        // The generator position is untouched: no explicit or replayed value advanced it.
        if(kind.equals("IDENTITY")){sql(dm,"INSERT INTO "+t("T")+"(\"WM\",\"V\") VALUES(0,2)");assertEquals(List.of("100:0:1","103:0:2"),rows("T"));}
    }
    @ParameterizedTest @ValueSource(strings={"IDENTITY","AUTO_INCREMENT","DEFAULT_ON_NULL"})
    void diffRejectsGeneratedTargetValuesBeforeAnyDelete(String kind)throws Exception {
        // DIFF deletes before it inserts: an identity insert failure or a NULL turned into the default would come after a committed delete.
        source(ProductTypeEnum.POSTGRESQL);sql(pg,"INSERT INTO "+t("S")+" VALUES(3,1,NULL)");generated(kind);
        var diff=new MigrationService(request(ProductTypeEnum.POSTGRESQL,true),ignored->{},null,runtime).run();
        assertFalse(diff.getIfAllSuccess().get());assertEquals(List.of("100:0:1"),rows("T"));
        if(kind.equals("IDENTITY")){sql(dm,"INSERT INTO "+t("T")+"(\"WM\",\"V\") VALUES(0,2)");assertEquals(List.of("100:0:1","103:0:2"),rows("T"));}
    }
    @ParameterizedTest @CsvSource({"DM,TRIGGER","POSTGRESQL,TRIGGER","DM,DISABLED_TRIGGER","POSTGRESQL,DISABLED_TRIGGER","DM,CASCADE_PARENT","POSTGRESQL,CASCADE_PARENT"})
    void triggerOrReferentialActionTargetsFailBeforeTheFirstCursorWrite(ProductTypeEnum source,String sideEffect)throws Exception {
        source(source);ordinaryTarget();sideEffect(sideEffect);
        assertTargetRejected(source);assertNull(saved.get());assertEquals(List.of("100:0:1"),rows("T"));
        if(sideEffect.equals("CASCADE_PARENT"))assertEquals(List.of("100"),strings(dm,"SELECT \"PID\" FROM "+t("C")));
    }
    @ParameterizedTest @CsvSource({"DM,TRIGGER","POSTGRESQL,TRIGGER","DM,DISABLED_TRIGGER","POSTGRESQL,DISABLED_TRIGGER","DM,CASCADE_PARENT","POSTGRESQL,CASCADE_PARENT"})
    void diffRejectsTriggerOrReferentialActionTargetsBeforeWrites(ProductTypeEnum source,String sideEffect)throws Exception {
        source(source);
        if(source==ProductTypeEnum.DM){var full=new MigrationService(request(source,false),ignored->{},null,runtime).run();assertTrue(full.getIfAllSuccess().get(),()->full.getTableResultList().toString());}
        else{ordinaryTarget();sql(dm,"INSERT INTO "+t("T")+" VALUES(1,1,10)");}
        if(source==ProductTypeEnum.DM)sql(dm,"INSERT INTO "+t("T")+" VALUES(100,0,1)");
        sideEffect(sideEffect);var before=rows("T");sql(control(source),"UPDATE "+t("S")+" SET \"V\"=11 WHERE \"ID\"=1");
        var diff=new MigrationService(request(source,true),ignored->{},null,runtime).run();
        assertFalse(diff.getIfAllSuccess().get());assertEquals(before,rows("T"));
        if(sideEffect.equals("CASCADE_PARENT"))assertEquals(List.of("100"),strings(dm,"SELECT \"PID\" FROM "+t("C")));
    }
    @Test void parentOfNoActionForeignKeyStillAcceptsCursorAndDiff()throws Exception {
        source(ProductTypeEnum.POSTGRESQL);ordinaryTarget();sql(dm,"DELETE FROM "+t("T"));sql(dm,"INSERT INTO "+t("T")+" VALUES(1,0,0)");
        sql(dm,"CREATE TABLE "+t("C")+"(\"ID\" BIGINT PRIMARY KEY,\"PID\" BIGINT REFERENCES "+t("T")+"(\"ID\"))");sql(dm,"INSERT INTO "+t("C")+" VALUES(1,1)");
        transfer(ProductTypeEnum.POSTGRESQL);assertTrue(saved.get().complete());assertEquals(List.of("1:1:10","2:1:20"),rows("T"));
        sql(pg,"UPDATE "+t("S")+" SET \"V\"=21 WHERE \"ID\"=2");
        var diff=new MigrationService(request(ProductTypeEnum.POSTGRESQL,true),ignored->{},null,runtime).run();
        assertTrue(diff.getIfAllSuccess().get(),()->diff.getTableResultList().toString());assertEquals(List.of("1:1:10","2:1:21"),rows("T"));
    }
    @Test void concurrentTriggerCannotSlipBetweenTargetCheckAndCursorMerge()throws Exception {
        source(ProductTypeEnum.DM);ordinaryTarget();var raced=new AtomicReference<String>();
        CursorTransfer.execute(runtime,ds(ProductTypeEnum.DM),racing("MERGE INTO",raced),ProductTypeEnum.DM,ProductTypeEnum.DM,schema,"S",schema,"T",fields,fields,List.of("ID"),List.of("ID"),"WM",limits,2,binding(),new DbSwitchTableResult(),()->{});
        // The batch locked ROW EXCLUSIVE before its side-effect checks: the concurrent DDL waited out the batch and failed,
        // while another session's reads and DML on the same target were not serialized behind it.
        assertEquals("trigger=blocked,insert=ok,select=ok",raced.get());assertEquals(List.of("1:1:10","2:1:20","100:0:1","200:0:2"),rows("T"));assertTrue(saved.get().complete());
        sideEffect("TRIGGER");sql(dm,"UPDATE "+t("S")+" SET \"V\"=11 WHERE \"ID\"=1");
        assertTargetRejected(ProductTypeEnum.DM);assertEquals(List.of("1:1:10","2:1:20","100:0:1","200:0:2"),rows("T"));
    }
    @Test void concurrentTriggerCannotSlipIntoDiffBatches()throws Exception {
        ordinaryTarget();var raced=new AtomicReference<String>();
        var sync=runtime.synchronizer(racing("INSERT INTO",raced),ProductTypeEnum.DM);sync.configureBatchLimits(limits);sync.prepare(schema,"T",fields,List.of("ID"));
        assertEquals(1,sync.executeInsert(List.<Object[]>of(new Object[]{1L,1L,10})));
        assertEquals("trigger=blocked,insert=ok,select=ok",raced.get());assertEquals(List.of("1:1:10","100:0:1","200:0:2"),rows("T"));
        // A trigger committed after DIFF preparation is found again under the next batch lock before any DML.
        sideEffect("TRIGGER");
        assertThrows(IllegalStateException.class,()->sync.executeUpdate(List.<Object[]>of(new Object[]{1L,1L,11})));
        assertThrows(IllegalStateException.class,()->sync.executeDelete(List.<Object[]>of(new Object[]{100L,0L,1})));
        assertEquals(List.of("1:1:10","100:0:1","200:0:2"),rows("T"));
    }
    @ParameterizedTest @CsvSource({"PLAIN,","NO_ACTION_PARENT,","CASCADE_CHILD,","IDENTITY,identity","AUTO_INCREMENT,identity","DEFAULT_ON_NULL,DEFAULT ON NULL",
            "TRIGGER,trigger","DISABLED_TRIGGER,trigger","CASCADE_PARENT,trigger","SET_NULL_PARENT,trigger"})
    void targetCatalogNamesTheRejectedBoundary(String kind,String reason)throws Exception {
        if(Set.of("IDENTITY","AUTO_INCREMENT","DEFAULT_ON_NULL").contains(kind))generated(kind);
        else if(kind.equals("CASCADE_CHILD")){sql(dm,"CREATE TABLE "+t("P")+"(\"ID\" BIGINT PRIMARY KEY)");sql(dm,"CREATE TABLE "+t("T")+"(\"ID\" BIGINT PRIMARY KEY,\"WM\" BIGINT NOT NULL,\"V\" BIGINT REFERENCES "+t("P")+"(\"ID\") ON DELETE CASCADE)");}
        else {ordinaryTarget();if(kind.equals("NO_ACTION_PARENT"))sql(dm,"CREATE TABLE "+t("C")+"(\"ID\" BIGINT PRIMARY KEY,\"PID\" BIGINT REFERENCES "+t("T")+"(\"ID\"))");
            else if(kind.equals("SET_NULL_PARENT"))sql(dm,"CREATE TABLE "+t("C")+"(\"ID\" BIGINT PRIMARY KEY,\"PID\" BIGINT REFERENCES "+t("T")+"(\"ID\") ON UPDATE SET NULL)");
            else if(!kind.equals("PLAIN"))sideEffect(kind);}
        if(reason==null)DmTargetSideEffects.require(dm,schema,"T",15);
        else {var failure=assertThrows(SQLException.class,()->DmTargetSideEffects.require(dm,schema,"T",15));assertTrue(failure.getMessage().contains(reason),failure.getMessage());}
        // No RLS policy can exist on this server: DBMS_RLS is not installed, so the policy branch rejects only when it is.
        assertEquals(List.of("0"),strings(dm,"SELECT SF_CHECK_RLS_SYS() FROM DUAL"));
    }

    private void source(ProductTypeEnum source)throws SQLException {
        sql(control(source),"CREATE TABLE "+t("S")+"(\"ID\" BIGINT PRIMARY KEY,\"WM\" BIGINT NOT NULL,\"V\" INT)");sql(control(source),"INSERT INTO "+t("S")+" VALUES(1,1,10),(2,1,20)");
    }
    private void ordinaryTarget()throws SQLException{sql(dm,"CREATE TABLE "+t("T")+"(\"ID\" BIGINT PRIMARY KEY,\"WM\" BIGINT NOT NULL,\"V\" INT)");sql(dm,"INSERT INTO "+t("T")+" VALUES(100,0,1)");}
    private void generated(String kind)throws SQLException {
        switch(kind) {
            case "IDENTITY"->{sql(dm,"CREATE TABLE "+t("T")+"(\"ID\" BIGINT IDENTITY(7,3) PRIMARY KEY,\"WM\" BIGINT NOT NULL,\"V\" INT)");
                sql(dm,"SET IDENTITY_INSERT "+t("T")+" ON");sql(dm,"INSERT INTO "+t("T")+"(\"ID\",\"WM\",\"V\") VALUES(100,0,1)");sql(dm,"SET IDENTITY_INSERT "+t("T")+" OFF");}
            case "AUTO_INCREMENT"->{sql(dm,"CREATE TABLE "+t("T")+"(\"ID\" BIGINT AUTO_INCREMENT PRIMARY KEY,\"WM\" BIGINT NOT NULL,\"V\" INT)");sql(dm,"INSERT INTO "+t("T")+"(\"ID\",\"WM\",\"V\") VALUES(100,0,1)");}
            default->{sql(dm,"CREATE TABLE "+t("T")+"(\"ID\" BIGINT PRIMARY KEY,\"WM\" BIGINT NOT NULL,\"V\" INT DEFAULT ON NULL 7)");sql(dm,"INSERT INTO "+t("T")+" VALUES(100,0,1)");}
        }
    }
    private void sideEffect(String kind)throws SQLException {
        if(kind.endsWith("TRIGGER"))sql(dm,"CREATE TRIGGER "+t("T_BIU")+" BEFORE INSERT OR UPDATE ON "+t("T")+" FOR EACH ROW BEGIN :NEW.\"V\" := 7; END;");
        if(kind.equals("DISABLED_TRIGGER"))sql(dm,"ALTER TRIGGER "+t("T_BIU")+" DISABLE");
        if(kind.equals("CASCADE_PARENT")){sql(dm,"CREATE TABLE "+t("C")+"(\"ID\" BIGINT PRIMARY KEY,\"PID\" BIGINT REFERENCES "+t("T")+"(\"ID\") ON DELETE CASCADE)");sql(dm,"INSERT INTO "+t("C")+" VALUES(1,100)");}
    }
    /** The source cursor opened; the target writer refused before any checkpoint or row was written. */
    private void assertTargetRejected(ProductTypeEnum source) {
        var failure=assertThrows(IllegalArgumentException.class,()->transfer(source));assertEquals("Native UPSERT target or unique key is unsupported",failure.getMessage());
    }
    /** Target connections that, from other sessions, try a firing trigger, a write and a read just before the first DML is prepared. */
    private DataSource racing(String dml,AtomicReference<String> outcome) {
        return new AbstractDataSource() {
            @Override public Connection getConnection()throws SQLException {
                Connection actual=open(ProductTypeEnum.DM);
                return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                    if(m.getName().equals("prepareStatement")&&a[0] instanceof String sql&&sql.startsWith(dml)&&outcome.get()==null)outcome.set(concurrently());
                    try{return m.invoke(actual,a);}catch(InvocationTargetException e){throw e.getCause();}
                });
            }
            @Override public Connection getConnection(String u,String p)throws SQLException{throw new SQLException("Explicit fixture principal required");}
        };
    }
    private String concurrently() {
        return "trigger="+attempt("CREATE TRIGGER "+t("T_RACE")+" BEFORE INSERT OR UPDATE ON "+t("T")+" FOR EACH ROW BEGIN :NEW.\"V\" := 7; END;","created")
                +",insert="+attempt("INSERT INTO "+t("T")+" VALUES(200,0,2)","ok")+",select="+attempt("SELECT COUNT(*) FROM "+t("T"),"ok");
    }
    private String attempt(String statement,String success) {
        try(var other=open(ProductTypeEnum.DM);var s=other.createStatement()){s.setQueryTimeout(3);s.execute(statement);return success;}catch(SQLException blocked){return "blocked";}
    }
    private DbSwitchTableResult transfer(ProductTypeEnum source)throws Exception {
        var result=new DbSwitchTableResult();CursorTransfer.execute(runtime,ds(source),ds(ProductTypeEnum.DM),source,ProductTypeEnum.DM,schema,"S",schema,"T",fields,fields,List.of("ID"),List.of("ID"),"WM",limits,2,binding(),result,()->{});return result;
    }
    private CursorRunBinding binding(){return new CursorRunBinding("d".repeat(64),saved.get(),next->{saved.set(next);return true;},pin);}
    private MigrationRequest request(ProductTypeEnum source,boolean diff) {
        var r=new MigrationRequest();var s=new SourceDataSourceProperties();s.setSourceProductType(source);s.setUrl(url(source));s.setUsername(user(source));s.setPassword(password(source));s.setPoolSize(1);s.setSourceSchema(schema);s.setSourceIncludes("S");s.setFetchSize(2);s.setRegexTableMapper(List.of(new PatternMapper("^S$","T")));r.getSource().add(s);
        var t=r.getTarget();t.setTargetProductType(ProductTypeEnum.DM);t.setUrl(url(ProductTypeEnum.DM));t.setUsername(user(ProductTypeEnum.DM));t.setPassword(password(ProductTypeEnum.DM));t.setTargetSchema(schema);t.setPoolSize(1);t.setWriterEngineInsert(true);t.setChangeDataSync(diff);
        if(diff)t.setChangeDataSyncType("PK_ORDER_COMPARE");return r;
    }
    private List<String> rows(String table)throws SQLException{return strings(dm,"SELECT \"ID\"||':'||\"WM\"||':'||\"V\" FROM "+t(table)+" ORDER BY \"ID\"");}
    private static List<String> strings(Connection c,String query)throws SQLException{var values=new ArrayList<String>();try(var s=c.createStatement();var r=s.executeQuery(query)){while(r.next())values.add(r.getString(1));}return values;}
    private Connection open(ProductTypeEnum p)throws SQLException{return runtime.openConnection(p,null,url(p),user(p),password(p));}
    private DataSource ds(ProductTypeEnum p){return new AbstractDataSource(){public Connection getConnection()throws SQLException{return open(p);}public Connection getConnection(String u,String pw)throws SQLException{throw new SQLException("Explicit fixture principal required");}};}
    private Connection control(ProductTypeEnum p){return p==ProductTypeEnum.DM?dm:pg;}
    private String t(String table){return q(schema)+'.'+q(table);}private static String q(String n){return DmSqlNames.quote(n);}private static void sql(Connection c,String sql)throws SQLException{try(var s=c.createStatement()){s.setQueryTimeout(15);s.execute(sql);}}
    private static String url(ProductTypeEnum p){return System.getProperty(p==ProductTypeEnum.DM?"datahub.test.dm.url":"datahub.test.postgres.url");}
    private static String user(ProductTypeEnum p){return p==ProductTypeEnum.DM?"SYSDBA":System.getProperty("datahub.test.postgres.user","dataforge_test");}
    private static String password(ProductTypeEnum p){return p==ProductTypeEnum.DM?System.getenv("DATAHUB_DM_TEST_PASSWORD"):System.getProperty("datahub.test.postgres.password","");}
}
