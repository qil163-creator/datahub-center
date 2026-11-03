package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.config.*;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.util.SybaseSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.SybaseDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.*;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.*;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.sybase.SybaseUpsertWriter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import javax.sql.DataSource;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Sybase ASE 16.0 SP02 PL02 cursor and native MERGE upsert. set rowcount keyset paging over the exact
 * ordered primary key under SERIALIZABLE isolation; concurrent writers block for the snapshot lifetime.
 * The jconn4 SybDriver does not self-register, so the fixture registers it once for DriverManager pools.
 */
@Timeout(180)
@EnabledIfSystemProperty(named="datahub.test.sybase.url",matches="jdbc:sybase:Tds:127[.]0[.]0[.]1:[0-9]+/tempdb")
@EnabledIfSystemProperty(named="datahub.test.sybase.driver",matches=".+")
@EnabledIfEnvironmentVariable(named="DATAHUB_SYBASE_TEST_PASSWORD",matches=".+")
class MigrationCursorSybaseTest {
    private static String url;
    private Connection control;
    private String generationNamespace;
    private SourceGenerationPin pin;
    private DbSwitchRuntime runtime;
    private final List<String> fields=List.of("WM","K1","K2","V"),keys=List.of("K2","K1");
    private final BatchLimits limits=new BatchLimits(2,65536,16384,2000,1000);
    private final AtomicReference<CursorCheckpoint> saved=new AtomicReference<>();
    @BeforeAll static void driver()throws Exception {
        // jconn4 is not a compile dependency; load it reflectively from maven.test.additionalClasspath at runtime.
        DriverManager.registerDriver((Driver) Class.forName("com.sybase.jdbc4.jdbc.SybDriver").getDeclaredConstructor().newInstance());
        url=System.getProperty("datahub.test.sybase.url");
    }
    @BeforeEach void setup()throws Exception {
        control=open();generationNamespace="g_"+UUID.randomUUID().toString().replace("-","");
        pin=SybaseSourceGeneration.initialize(control,generationNamespace,15);runtime=DbSwitchRuntime.standalone();
    }
    @AfterEach void cleanup()throws Exception {
        try{if(runtime!=null)runtime.close();}finally{if(control!=null)try(var c=control){
            for(String table:List.of("S","T","a'b],(","nativevals"))try{sql(c,"DROP TABLE "+t(table));}catch(SQLException ignored){}
            if(generationNamespace!=null)try{SybaseSourceGeneration.drop(c,generationNamespace,15);}catch(SQLException ignored){}
        }}
    }
    private Connection open()throws SQLException{DriverManager.setLoginTimeout(15);return DriverManager.getConnection(url,"sa",password());}
    private DataSource ds(){return new AbstractDataSource(){public Connection getConnection()throws SQLException{return open();}public Connection getConnection(String u,String p)throws SQLException{throw new SQLException("Fixture principal is fixed");}};}
    private static String password(){return System.getenv("DATAHUB_SYBASE_TEST_PASSWORD");}
    private static void sql(Connection c,String value)throws SQLException{try(var s=c.createStatement()){s.setQueryTimeout(15);s.execute(value);}}
    private String t(String name){return "dbo.["+name.replace("]","]]")+"]";}
    private void create(String table)throws SQLException{sql(control,"CREATE TABLE "+t(table)+"([WM] decimal(38,18) NOT NULL,[K1] bigint NOT NULL,[K2] numeric(38,0) NOT NULL,[V] varchar(80) NULL,PRIMARY KEY([K2],[K1]))");}
    private void rows(int count)throws SQLException{for(int i=1;i<=count;i++)sql(control,"INSERT "+t("S")+" VALUES(10,"+i+","+(i%2)+",'original')");}
    private long count(String table)throws SQLException{try(var s=control.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+t(table))){assertTrue(r.next());return r.getLong(1);}}
    private CursorReadSession cursor(){return cursor(fields,keys,"WM",null,null);}
    private CursorReadSession cursor(List<String> names,List<String> keyNames,String watermark,cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursor lower,String expected){var reader=new SybaseDatabaseOperator(ds());reader.setFetchSize(1);return reader.openCursor(new CursorReadRequest("dbo","S",names,watermark,keyNames,lower,null,expected,limits,false,pin));}
    private SybaseUpsertWriter writer(String table,List<String> names,List<String> keyNames){var writer=new SybaseUpsertWriter(ds());writer.configureBatchLimits(limits);writer.prepareUpsert("dbo",table,names,keyNames);return writer;}
    private boolean save(CursorCheckpoint value){saved.set(value);return true;}
    private CursorRunBinding binding(CursorRunBinding.CheckpointStore store){return new CursorRunBinding("e".repeat(64),saved.get(),store,pin);}
    private DbSwitchTableResult transfer(DataSource target,CursorRunBinding.CheckpointStore store)throws Exception{var result=new DbSwitchTableResult();CursorTransfer.execute(runtime,ds(),target,ProductTypeEnum.SYBASE,ProductTypeEnum.SYBASE,"dbo","S","dbo","T",fields,fields,keys,keys,"WM",limits,2,binding(store),result,()->{});return result;}
    private MigrationRequest request(){var r=new MigrationRequest();var s=new SourceDataSourceProperties();s.setSourceProductType(ProductTypeEnum.SYBASE);s.setUrl(url);s.setUsername("sa");s.setPassword(password());s.setPoolSize(1);s.setSourceSchema("dbo");s.setSourceIncludes("S");s.setFetchSize(2);s.setIncreaseColumnName("WM");s.setCursorWatermarkContract("MONOTONIC_WATERMARK_V1");s.setRegexTableMapper(List.of(new PatternMapper("^S$","T")));r.getSource().add(s);
        var target=r.getTarget();target.setTargetProductType(ProductTypeEnum.SYBASE);target.setUrl(url);target.setUsername("sa");target.setPassword(password());target.setPoolSize(1);target.setTargetSchema("dbo");target.setChangeDataSync(true);target.setChangeDataSyncType("INCREASE_COLUMN");target.setCursorRunBindings(Map.of("S",binding(this::save)));return r;}

    @Test void directNativeCursorAndUpsertPreserveCompositeNumeric38Keys()throws Exception {
        create("S");create("T");rows(5);var writer=writer("T",fields,keys);int read=0;try(var cursor=cursor()){while(cursor.next()){assertEquals(1,writer.writeWithReceipt(fields,Collections.singletonList(cursor.row())).acknowledgedRows());read++;}}assertEquals(5,read);assertEquals(5,count("T"));
    }
    @Test void rowcountKeysetNeverSkipsOrDuplicatesAtEqualWatermarks()throws Exception {
        sql(control,"CREATE TABLE "+t("S")+"([WM] datetime NOT NULL,[K2] int NOT NULL,[K1] int NOT NULL,[V] varchar(10) NULL,PRIMARY KEY([K2],[K1]))");
        for(int i=1;i<=7;i++){String tick=i<=2?".000":i<=4?".003":".007";sql(control,"INSERT "+t("S")+" VALUES('2026-10-06 10:00:00"+tick+"',"+i+","+i+",'r')");}
        var names=List.of("WM","K2","K1","V");var keyNames=List.of("K2","K1");var seen=new ArrayList<Integer>();
        var reader=new SybaseDatabaseOperator(ds());reader.setFetchSize(2);
        try(var cursor=reader.openCursor(new CursorReadRequest("dbo","S",names,"WM",keyNames,null,null,null,limits,false,pin))){while(cursor.next())seen.add((Integer)cursor.row()[1]);}
        assertEquals(List.of(1,2,3,4,5,6,7),seen);
    }
    @Test void missingDeploymentPinAndReorderedPrimaryKeyFailBeforeReading()throws Exception {
        create("S");rows(1);var operator=new SybaseDatabaseOperator(ds());
        assertThrows(IllegalArgumentException.class,()->operator.openCursor(new CursorReadRequest("dbo","S",fields,"WM",keys,null,null,null,limits,false,null)));
        assertThrows(IllegalArgumentException.class,()->cursor(fields,List.of("K1","K2"),"WM",null,null));
    }
    @Test void textWatermarkIsRefusedBeforeAnyRowIsRead()throws Exception {
        sql(control,"CREATE TABLE "+t("S")+"([WM] varchar(20) NOT NULL,[K2] int NOT NULL,[K1] int NOT NULL,[V] int NULL,PRIMARY KEY([K2],[K1]))");
        sql(control,"INSERT "+t("S")+" VALUES('w',1,1,1)");
        assertThrows(IllegalArgumentException.class,this::cursor);
        assertEquals(1,count("S"));
    }
    @Test void serializableSnapshotBlocksConcurrentUpdatesAndInsertsUntilClose()throws Exception {
        create("S");rows(3);
        try(var cursor=cursor()){assertTrue(cursor.next());
            try(var other=open()){try{sqlT(other,"UPDATE "+t("S")+" SET [V]='changed' WHERE K1=1",5);fail("Concurrent update must block");}catch(SQLException blocked){}
                try{sqlT(other,"INSERT "+t("S")+" VALUES(11,99,99,'phantom')",5);fail("Concurrent insert must block");}catch(SQLException blocked){}}
            assertEquals("original",value("S",1));assertEquals(3,count("S"));}
        try(var other=open()){sql(other,"UPDATE "+t("S")+" SET [V]='changed' WHERE K1=1");sql(other,"INSERT "+t("S")+" VALUES(11,99,99,'phantom')");}
        assertEquals("changed",value("S",1));assertEquals(4,count("S"));
    }
    private static void sqlT(Connection c,String value,int timeout)throws SQLException{try(var s=c.createStatement()){s.setQueryTimeout(timeout);s.execute(value);}}
    private String value(String table,int k1)throws SQLException{try(var s=control.createStatement();var r=s.executeQuery("SELECT [V] FROM "+t(table)+" WHERE K1="+k1)){assertTrue(r.next());return r.getString(1);}}
    @Test void generationFenceIsCurrentInsideSnapshotAndRotationIsExplicitIdempotent()throws Exception {
        create("S");rows(3);assertEquals(pin,SybaseSourceGeneration.initialize(control,generationNamespace,15));
        try(var cursor=cursor()){assertTrue(cursor.next());SybaseSourceGeneration.fence(control,pin,15);assertThrows(SQLException.class,cursor::next);}
        var old=pin;String next=UUID.randomUUID().toString();pin=SybaseSourceGeneration.rotate(control,old,next,15);assertEquals(pin,SybaseSourceGeneration.rotate(control,old,next,15));
        assertThrows(SQLException.class,()->SybaseSourceGeneration.verifyCurrent(control,old,15));SybaseSourceGeneration.verifyCurrent(control,pin,15);SybaseSourceGeneration.fence(control,pin,15);
        assertThrows(SQLException.class,()->SybaseSourceGeneration.rotate(control,pin,old.generation(),15));
    }
    @ParameterizedTest @ValueSource(strings={"DELETE","CHANGE"})
    void sourceMarkerTamperingNeverBootstrapsDuringRead(String operation)throws Exception {
        create("S");rows(1);String marker=t(SybaseSourceGeneration.marker(generationNamespace));
        sql(control,operation.equals("DELETE")?"DELETE FROM "+marker:"UPDATE "+marker+" SET generation='"+UUID.randomUUID()+"'");assertThrows(IllegalArgumentException.class,this::cursor);assertEquals(1,count("S"));
    }
    @Test void targetWithoutVerifiedPrimaryKeyIsRejectedBeforeWrites()throws Exception {
        sql(control,"CREATE TABLE "+t("T")+"([K] int NOT NULL,[V] int NULL)");
        assertThrows(IllegalArgumentException.class,()->writer("T",List.of("K","V"),List.of("K")));assertEquals(0,count("T"));
    }
    @Test void publicServiceReplaysEqualWatermarkAndPersistsCommittedReceipts()throws Exception {
        create("S");create("T");rows(5);var request=request();var result=new MigrationService(request,ignored->{},null,runtime).run();assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());
        assertTrue(saved.get().complete());assertEquals(CommitAcknowledgement.JDBC_COMMIT_RETURNED,saved.get().lastCommitAcknowledgement());assertEquals(5,count("T"));
        sql(control,"INSERT "+t("S")+" VALUES(10,0,0,'late')");request.getTarget().setCursorRunBindings(Map.of("S",binding(this::save)));assertTrue(new MigrationService(request,ignored->{},null,runtime).run().getIfAllSuccess().get());assertEquals(6,count("T"));
    }
    @Test void nativeMergePreservesExactNumericTicksUnicodeAndNull()throws Exception {
        String table="nativevals";var names=List.of("K","D","DT","BDT","BT","UV","V");sql(control,"CREATE TABLE "+t(table)+"([K] numeric(38,0) NOT NULL PRIMARY KEY,[D] decimal(38,18) NULL,[DT] datetime NULL,[BDT] bigdatetime NULL,[BT] bigtime NULL,[UV] univarchar(40) NULL,[V] varchar(10) NULL)");
        var writer=writer(table,names,List.of("K"));var maximum=new BigDecimal("99999999999999999999999999999999999999");
        var tick=LocalDateTime.of(2026,10,6,10,0,0,996_666_666);var micro=LocalDateTime.of(2026,10,6,10,0,0,123_456_000);
        Object[] row={maximum,new BigDecimal("-1E-18"),tick,micro,LocalTime.parse("10:00:00.654321"),"\u6c49\u5b57","v"};
        for(int repeat=0;repeat<2;repeat++)assertEquals(1,writer.writeWithReceipt(names,Collections.singletonList(row)).acknowledgedRows());
        try(var s=control.createStatement();var r=s.executeQuery("SELECT * FROM "+t(table))){assertTrue(r.next());assertEquals(maximum,r.getBigDecimal(1));assertEquals(row[1],r.getBigDecimal(2));assertEquals(tick,r.getTimestamp(3).toLocalDateTime());assertEquals(micro,r.getTimestamp(4).toLocalDateTime());assertEquals(LocalTime.parse("10:00:00.654321"),r.getTimestamp(5).toLocalDateTime().toLocalTime());assertEquals("\u6c49\u5b57",r.getString(6));assertEquals("v",r.getString(7));assertFalse(r.next());}
        assertEquals(1,writer.writeWithReceipt(names,Collections.singletonList(new Object[]{maximum,null,null,null,null,null,null})).acknowledgedRows());
    }
    @Test void oddPhysicalNamesRejectOnlyWhereJconn4CannotPrepareThem()throws Exception {
        String table="a'b],(";sql(control,"CREATE TABLE "+t(table)+"([K] int NOT NULL PRIMARY KEY,[V] varchar(10) NULL)");
        sql(control,"INSERT "+t(table)+" VALUES(1,'x')");
        var writer=writer(table,List.of("K","V"),List.of("K"));
        assertThrows(RuntimeException.class,()->writer.writeWithReceipt(List.of("K","V"),Collections.singletonList(new Object[]{2,"y"})));
        assertThrows(IllegalArgumentException.class,()->cursor(List.of("K","V"),List.of("K"),"K",null,null));
        assertEquals(1,count(table));
    }
    @Test void temporalAndTextKeysUseNativeOrderingAtPageBoundaries()throws Exception {
        var names=List.of("WM","T","K","V");var primary=List.of("T","K");sql(control,"CREATE TABLE "+t("S")+"([WM] datetime NOT NULL,[T] datetime NOT NULL,[K] varchar(30) NOT NULL,[V] int NULL,PRIMARY KEY([T],[K]))");
        sql(control,"INSERT "+t("S")+" VALUES('2026-10-06 10:00:00.000','2026-10-06 10:00:00.000','z',1)");
        sql(control,"INSERT "+t("S")+" VALUES('2026-10-06 10:00:00.000','2026-10-06 10:00:00.000','\u00c1',2)");
        sql(control,"INSERT "+t("S")+" VALUES('2026-10-06 10:00:00.003','2026-10-06 10:00:00.003','b',3)");
        var observed=new ArrayList<Integer>();try(var cursor=cursor(names,primary,"WM",null,null)){while(cursor.next()){int value=(Integer)cursor.row()[3];observed.add(value);assertEquals(value==3?LocalDateTime.of(2026,10,6,10,0,0,3_333_333):LocalDateTime.of(2026,10,6,10,0,0),cursor.position().values().get(1).jdbcValue());}}
        assertEquals(List.of(1,2,3),observed);
    }
    @Test void explicitIdentityMergePreservesIdsAndNativeNextAllocation()throws Exception {
        sql(control,"CREATE TABLE "+t("T")+"([K] numeric(18,0) IDENTITY PRIMARY KEY,[V] varchar(20) NULL)");var names=List.of("K","V");var writer=writer("T",names,List.of("K"));
        BigDecimal key=new BigDecimal("123456789012345670");for(int i=0;i<2;i++)writer.writeWithReceipt(names,Collections.singletonList(new Object[]{key,"explicit"}));
        sql(control,"INSERT "+t("T")+"([V]) VALUES('native')");try(var s=control.createStatement();var r=s.executeQuery("SELECT [K] FROM "+t("T")+" WHERE [V]='native'")){assertTrue(r.next());assertEquals(key.add(BigDecimal.ONE),r.getBigDecimal(1));}
    }
    @Test void invalidLaterValueRejectsWholeBatchBeforeBorrowingTarget()throws Exception {
        sql(control,"CREATE TABLE "+t("T")+"([K] bigint PRIMARY KEY,[DT] datetime NULL,[V] varchar(4) NULL)");var borrowed=new AtomicInteger();var source=new AbstractDataSource(){public Connection getConnection()throws SQLException{borrowed.incrementAndGet();return open();}public Connection getConnection(String u,String p)throws SQLException{return getConnection();}};
        var writer=new SybaseUpsertWriter(source);writer.prepareUpsert("dbo","T",List.of("K","DT","V"),List.of("K"));borrowed.set(0);
        assertThrows(RuntimeException.class,()->writer.writeWithReceipt(List.of("K","DT","V"),List.of(new Object[]{1L,LocalDateTime.of(2026,10,6,10,0,0,1_000_000),"ok"},new Object[]{2L,null,"ok"})));
        assertThrows(RuntimeException.class,()->writer.writeWithReceipt(List.of("K","DT","V"),Collections.singletonList(new Object[]{3L,null,"too long"})));assertEquals(0,borrowed.get());assertEquals(0,count("T"));
    }
    @Test void commitBeforeCheckpointCrashRewindsWithoutDuplicatingTarget()throws Exception {
        create("S");create("T");rows(5);var calls=new AtomicInteger();assertThrows(IllegalStateException.class,()->transfer(ds(),value->calls.incrementAndGet()!=3&&save(value)));assertEquals(4,count("T"));assertFalse(saved.get().complete());
        transfer(ds(),this::save);assertEquals(5,count("T"));assertTrue(saved.get().complete());
    }
    @Test void actualCommitLostAcknowledgementDoesNotAdvanceCheckpoint()throws Exception {
        create("S");create("T");rows(2);var fault=fault("commit");assertThrows(UnconfirmedWriteException.class,()->transfer(fault,this::save));assertEquals(2,count("T"));assertNull(saved.get().applied());assertEquals(0,saved.get().sequence());transfer(ds(),this::save);assertEquals(2,count("T"));
    }
    @Test void completedEmptyWindowRemainsFrozenUntilExplicitNextRun()throws Exception {
        create("S");create("T");var calls=new AtomicInteger();assertThrows(IllegalStateException.class,()->transfer(ds(),value->calls.incrementAndGet()==1&&save(value)));assertNull(saved.get().fixedUpper());rows(1);transfer(ds(),this::save);assertEquals(0,count("T"));transfer(ds(),this::save);assertEquals(1,count("T"));
    }
    @ParameterizedTest @ValueSource(strings={"SOURCE_RECREATE","TARGET_RECREATE","SOURCE_TRUNCATE","PIN_ROTATE"})
    void resumeRequiresUnchangedPhysicalSchemaAndExternalGeneration(String mode)throws Exception {
        create("S");create("T");rows(1);transfer(ds(),this::save);
        if(mode.equals("SOURCE_RECREATE")){sql(control,"DROP TABLE "+t("S"));create("S");rows(1);}else if(mode.equals("TARGET_RECREATE")){sql(control,"DROP TABLE "+t("T"));create("T");}
        else if(mode.equals("SOURCE_TRUNCATE"))sql(control,"TRUNCATE TABLE "+t("S"));else{SybaseSourceGeneration.fence(control,pin,15);pin=SybaseSourceGeneration.rotate(control,pin,UUID.randomUUID().toString(),15);}
        long before=count("T");assertThrows(RuntimeException.class,()->transfer(ds(),this::save));assertEquals(before,count("T"));
    }
    @Test void concurrentIndependentMergesSerializeWithoutDuplicateKeyRace()throws Exception {
        sql(control,"CREATE TABLE "+t("T")+"([K] bigint PRIMARY KEY,[V] int NULL)");var one=writer("T",List.of("K","V"),List.of("K"));var two=writer("T",List.of("K","V"),List.of("K"));
        try(var threads=Executors.newFixedThreadPool(2)){var start=new CountDownLatch(1);var a=threads.submit(()->{start.await();return one.writeWithReceipt(List.of("K","V"),Collections.singletonList(new Object[]{1L,10}));});var b=threads.submit(()->{start.await();return two.writeWithReceipt(List.of("K","V"),Collections.singletonList(new Object[]{1L,20}));});start.countDown();assertEquals(1,a.get(30,TimeUnit.SECONDS).acknowledgedRows());assertEquals(1,b.get(30,TimeUnit.SECONDS).acknowledgedRows());}assertEquals(1,count("T"));
    }
    private DataSource fault(String method){return new AbstractDataSource(){public Connection getConnection()throws SQLException{Connection c=open();return(Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{try{Object v=m.invoke(c,a);if(m.getName().equals(method))throw new SQLException("fixture response lost");return v;}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});}public Connection getConnection(String u,String p)throws SQLException{return getConnection();}};}
}
