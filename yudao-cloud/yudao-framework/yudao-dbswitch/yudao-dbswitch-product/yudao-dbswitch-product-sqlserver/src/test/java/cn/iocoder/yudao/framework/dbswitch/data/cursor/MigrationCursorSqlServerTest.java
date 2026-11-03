package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.config.*;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.util.SqlServerSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.SqlServerDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.*;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.*;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.mssql.SqlServerUpsertWriter;
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

@Timeout(90)
@EnabledIfSystemProperty(named="datahub.test.sqlserver.url",matches="jdbc:sqlserver://127[.]0[.]0[.]1:[0-9]+;databaseName=codex_q11;encrypt=false")
@EnabledIfEnvironmentVariable(named="DATAHUB_TEST_SQLSERVER_PASSWORD",matches=".+")
class MigrationCursorSqlServerTest {
    private static String database,url;
    private static Connection admin;
    private Connection control;
    private String schema,generationSchema;
    private SourceGenerationPin pin;
    private DbSwitchRuntime runtime;
    private final List<String> fields=List.of("WM","K1","K2","V"),keys=List.of("K2","K1");
    private final BatchLimits limits=new BatchLimits(2,65536,16384,2000,1000);
    private final AtomicReference<CursorCheckpoint> saved=new AtomicReference<>();
    @BeforeAll static void database()throws Exception {
        Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");String endpoint=System.getProperty("datahub.test.sqlserver.url");
        admin=DriverManager.getConnection(endpoint,"sa",password());database="codex_cursor_"+UUID.randomUUID().toString().replace("-","");
        sql(admin,"CREATE DATABASE "+q(database));sql(admin,"ALTER DATABASE "+q(database)+" SET ALLOW_SNAPSHOT_ISOLATION ON");url=endpoint.replace("databaseName=codex_q11","databaseName="+database);
    }
    @AfterAll static void databaseCleanup()throws Exception {if(admin!=null)try(var c=admin){if(database!=null)sql(c,"DROP DATABASE "+q(database));}}
    @BeforeEach void setup()throws Exception {
        control=open();schema="c_"+UUID.randomUUID().toString().replace("-","");generationSchema="g_"+UUID.randomUUID().toString().replace("-","");
        sql(control,"CREATE SCHEMA "+q(schema));sql(control,"CREATE SCHEMA "+q(generationSchema));pin=SqlServerSourceGeneration.initialize(control,generationSchema,15);runtime=DbSwitchRuntime.standalone();
    }
    @AfterEach void cleanup()throws Exception {
        try{if(runtime!=null)runtime.close();}finally{if(control!=null)try(var c=control){for(String selected:List.of(schema,generationSchema)){
            List<String> tables=new ArrayList<>();try(var p=c.prepareStatement("SELECT t.name FROM sys.tables t JOIN sys.schemas s ON s.schema_id=t.schema_id WHERE s.name=?")){p.setString(1,selected);try(var r=p.executeQuery()){while(r.next())tables.add(r.getString(1));}}
            for(String table:tables)sql(c,"DROP TABLE "+q(selected)+'.'+q(table));sql(c,"DROP SCHEMA "+q(selected));}}}
    }
    private Connection open()throws SQLException{return DriverManager.getConnection(url,"sa",password());}
    private DataSource ds(){return new AbstractDataSource(){public Connection getConnection()throws SQLException{return open();}public Connection getConnection(String u,String p)throws SQLException{throw new SQLException("Fixture principal is fixed");}};}
    private static String password(){return System.getenv("DATAHUB_TEST_SQLSERVER_PASSWORD");}
    private static String q(String name){return '['+name.replace("]","]]")+']';}
    private String t(String name){return q(schema)+'.'+q(name);}
    private static void sql(Connection c,String value)throws SQLException{try(var s=c.createStatement()){s.setQueryTimeout(15);s.execute(value);}}
    private void create(String table)throws SQLException{sql(control,"CREATE TABLE "+t(table)+"([WM] decimal(38,18) NOT NULL,[K1] bigint NOT NULL,[K2] numeric(38,0) NOT NULL,[V] nvarchar(80),PRIMARY KEY([K2],[K1]))");}
    private void rows(int count)throws SQLException{for(int i=1;i<=count;i++)sql(control,"INSERT "+t("S")+" VALUES(10,"+i+","+(i%2)+",N'original')");}
    private long count(String table)throws SQLException{try(var s=control.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+t(table))){assertTrue(r.next());return r.getLong(1);}}
    private CursorReadSession cursor() {return cursor(fields,keys,"WM",null,null);}
    private CursorReadSession cursor(List<String> names,List<String> keyNames,String watermark,cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursor lower,String expected){var reader=new SqlServerDatabaseOperator(ds());reader.setFetchSize(1);return reader.openCursor(new CursorReadRequest(schema,"S",names,watermark,keyNames,lower,null,expected,limits,false,pin));}
    private SqlServerUpsertWriter writer(String table,List<String> names,List<String> keyNames){var writer=new SqlServerUpsertWriter(ds());writer.configureBatchLimits(limits);writer.prepareUpsert(schema,table,names,keyNames);return writer;}
    private boolean save(CursorCheckpoint value){saved.set(value);return true;}
    private CursorRunBinding binding(CursorRunBinding.CheckpointStore store){return new CursorRunBinding("e".repeat(64),saved.get(),store,pin);}
    private DbSwitchTableResult transfer(DataSource target,CursorRunBinding.CheckpointStore store)throws Exception{var result=new DbSwitchTableResult();CursorTransfer.execute(runtime,ds(),target,ProductTypeEnum.SQLSERVER,ProductTypeEnum.SQLSERVER,schema,"S",schema,"T",fields,fields,keys,keys,"WM",limits,2,binding(store),result,()->{});return result;}
    private MigrationRequest request(){var r=new MigrationRequest();var s=new SourceDataSourceProperties();s.setSourceProductType(ProductTypeEnum.SQLSERVER);s.setUrl(url);s.setUsername("sa");s.setPassword(password());s.setPoolSize(1);s.setSourceSchema(schema);s.setSourceIncludes("S");s.setFetchSize(2);s.setIncreaseColumnName("WM");s.setCursorWatermarkContract("MONOTONIC_WATERMARK_V1");s.setRegexTableMapper(List.of(new PatternMapper("^S$","T")));r.getSource().add(s);
        var target=r.getTarget();target.setTargetProductType(ProductTypeEnum.SQLSERVER);target.setUrl(url);target.setUsername("sa");target.setPassword(password());target.setPoolSize(1);target.setTargetSchema(schema);target.setChangeDataSync(true);target.setChangeDataSyncType("INCREASE_COLUMN");target.setCursorRunBindings(Map.of("S",binding(this::save)));return r;}

    @Test void directNativeCursorAndUpsertPreserveCompositeNumeric38Keys()throws Exception {
        create("S");create("T");rows(5);var writer=writer("T",fields,keys);int read=0;try(var cursor=cursor()){while(cursor.next()){assertEquals(1,writer.writeWithReceipt(fields,Collections.singletonList(cursor.row())).acknowledgedRows());read++;}}assertEquals(5,read);assertEquals(5,count("T"));
    }
    @Test void snapshotMustBeExplicitlyEnabledAndIsNeverChangedByReader()throws Exception {
        create("S");rows(1);sql(control,"ALTER DATABASE "+q(database)+" SET ALLOW_SNAPSHOT_ISOLATION OFF");
        try { assertThrows(IllegalArgumentException.class,this::cursor);
            try(var s=control.createStatement();var r=s.executeQuery("SELECT snapshot_isolation_state FROM sys.databases WHERE database_id=DB_ID()")){assertTrue(r.next());assertEquals(0,r.getInt(1));}
        } finally { sql(control,"ALTER DATABASE "+q(database)+" SET ALLOW_SNAPSHOT_ISOLATION ON"); }
    }
    @Test void missingDeploymentPinAndReorderedPrimaryKeyFailBeforeReading()throws Exception {
        create("S");rows(1);var operator=new SqlServerDatabaseOperator(ds());
        assertThrows(IllegalArgumentException.class,()->operator.openCursor(new CursorReadRequest(schema,"S",fields,"WM",keys,null,null,null,limits,false,null)));
        assertThrows(IllegalArgumentException.class,()->cursor(fields,List.of("K1","K2"),"WM",null,null));
    }
    @ParameterizedTest @ValueSource(strings={"NULL_WATERMARK","ROWVERSION","TRIGGER"})
    void unprovedSourceSemanticsFailBeforeOpeningAReplayWindow(String kind)throws Exception {
        if(kind.equals("NULL_WATERMARK"))sql(control,"CREATE TABLE "+t("S")+"([WM] decimal(38,18) NULL,[K1] bigint NOT NULL,[K2] numeric(38,0) NOT NULL,[V] nvarchar(80),PRIMARY KEY([K2],[K1]))");
        else {create("S");if(kind.equals("ROWVERSION"))sql(control,"ALTER TABLE "+t("S")+" ADD R rowversion");
            else sql(control,"CREATE TRIGGER "+q(schema)+".[replay_trigger] ON "+t("S")+" AFTER INSERT AS BEGIN SET NOCOUNT ON; END");}
        assertThrows(IllegalArgumentException.class,this::cursor);
    }
    @Test void generationHistoryRequiresItsActualGenerationPrimaryKey()throws Exception {
        create("S");String history=q(generationSchema)+'.'+q(SqlServerSourceGeneration.HISTORY),constraint;
        try(var p=control.prepareStatement("SELECT name FROM sys.key_constraints WHERE parent_object_id=OBJECT_ID(?) AND type='PK'")) {
            p.setString(1,history);try(var r=p.executeQuery()){assertTrue(r.next());constraint=r.getString(1);}
        }
        sql(control,"ALTER TABLE "+history+" DROP CONSTRAINT "+q(constraint));sql(control,"ALTER TABLE "+history+" ADD PRIMARY KEY(instance_id)");
        assertThrows(SQLException.class,()->SqlServerSourceGeneration.verifyCurrent(control,pin,15));
        assertThrows(SQLException.class,()->SqlServerSourceGeneration.initialize(control,generationSchema,15));
        assertThrows(IllegalArgumentException.class,this::cursor);
    }
    @Test void targetPhysicalReplacementAndFailedReprepareDoNotWriteStaleRelation()throws Exception {
        create("T");var writer=writer("T",fields,keys);sql(control,"DROP TABLE "+t("T"));create("T");
        assertThrows(RuntimeException.class,()->writer.writeWithReceipt(fields,Collections.singletonList(new Object[]{BigDecimal.TEN,1L,BigDecimal.ONE,"wrong incarnation"})));assertEquals(0,count("T"));
        assertThrows(RuntimeException.class,()->writer.prepareUpsert(schema,"missing",fields,keys));
        assertThrows(RuntimeException.class,()->writer.writeWithReceipt(fields,Collections.singletonList(new Object[]{BigDecimal.TEN,1L,BigDecimal.ONE,"stale preparation"})));assertEquals(0,count("T"));
    }
    @Test void publicServiceReplaysEqualWatermarkAndPersistsCommittedReceipts()throws Exception {
        create("S");create("T");rows(5);var request=request();var result=new MigrationService(request,ignored->{},null,runtime).run();assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());
        assertTrue(saved.get().complete());assertEquals(CommitAcknowledgement.JDBC_COMMIT_RETURNED,saved.get().lastCommitAcknowledgement());assertEquals(5,count("T"));
        sql(control,"INSERT "+t("S")+" VALUES(10,0,0,N'late')");request.getTarget().setCursorRunBindings(Map.of("S",binding(this::save)));assertTrue(new MigrationService(request,ignored->{},null,runtime).run().getIfAllSuccess().get());assertEquals(6,count("T"));
    }
    @Test void fixedSnapshotHidesConcurrentPayloadUpdatesAndNewRows()throws Exception {
        create("S");rows(3);int n=0;try(var cursor=cursor()){assertTrue(cursor.next());n++;sql(control,"UPDATE "+t("S")+" SET [V]=N'changed'");sql(control,"INSERT "+t("S")+" VALUES(11,99,99,N'new')");while(cursor.next()){assertEquals("original",cursor.row()[3]);n++;}}assertEquals(3,n);
    }
    @ParameterizedTest @ValueSource(strings={"ALTER","TRUNCATE"})
    void sourceDdlInvalidatesAnActiveSnapshotInsteadOfMixingIncarnations(String operation)throws Exception {
        create("S");rows(3);try(var cursor=cursor()){assertTrue(cursor.next());sql(control,operation.equals("ALTER")?"ALTER TABLE "+t("S")+" ADD extra int NULL":"TRUNCATE TABLE "+t("S"));assertThrows(SQLException.class,cursor::next);}
    }
    @Test void generationFenceIsCurrentInsideSnapshotAndRotationIsExplicitIdempotent()throws Exception {
        create("S");rows(3);assertEquals(pin,SqlServerSourceGeneration.initialize(control,generationSchema,15));
        try(var cursor=cursor()){assertTrue(cursor.next());SqlServerSourceGeneration.fence(control,pin,15);assertThrows(SQLException.class,cursor::next);}
        var old=pin;String next=UUID.randomUUID().toString();pin=SqlServerSourceGeneration.rotate(control,old,next,15);assertEquals(pin,SqlServerSourceGeneration.rotate(control,old,next,15));
        assertThrows(SQLException.class,()->SqlServerSourceGeneration.verifyCurrent(control,old,15));SqlServerSourceGeneration.verifyCurrent(control,pin,15);SqlServerSourceGeneration.fence(control,pin,15);
        assertThrows(SQLException.class,()->SqlServerSourceGeneration.rotate(control,pin,old.generation(),15));
    }
    @ParameterizedTest @ValueSource(strings={"DELETE","CHANGE"})
    void sourceMarkerTamperingNeverBootstrapsDuringRead(String operation)throws Exception {
        create("S");rows(1);String marker=q(generationSchema)+'.'+q(SqlServerSourceGeneration.MARKER);
        sql(control,operation.equals("DELETE")?"DELETE FROM "+marker:"UPDATE "+marker+" SET generation='"+UUID.randomUUID()+"'");assertThrows(IllegalArgumentException.class,this::cursor);assertEquals(1,count("S"));
    }
    @Test void nativeMergePreservesTime7Datetime2Numeric38UnicodeBinaryAndNull()throws Exception {
        String table="a'b],(";var names=List.of("K","D","T0","T7","TS","N","B");sql(control,"CREATE TABLE "+t(table)+"([K] numeric(38,0) NOT NULL PRIMARY KEY,[D] decimal(38,18),[T0] time(0),[T7] time(7),[TS] datetime2(7),[N] nvarchar(100),[B] varbinary(10))");
        var writer=writer(table,names,List.of("K"));var maximum=new BigDecimal("99999999999999999999999999999999999999");var timestamp=Timestamp.valueOf("2026-10-04 00:00:00.1234567");
        Object[] row={maximum,new BigDecimal("-1E-18"),LocalTime.of(23,59,59),LocalTime.parse("23:59:59.7654321"),timestamp,"汉字🙂'\\",new byte[]{0,(byte)255}};
        for(int repeat=0;repeat<2;repeat++)assertEquals(1,writer.writeWithReceipt(names,Collections.singletonList(row)).acknowledgedRows());
        try(var s=control.createStatement();var r=s.executeQuery("SELECT * FROM "+t(table))){assertTrue(r.next());assertEquals(maximum,r.getBigDecimal(1));assertEquals(row[1],r.getBigDecimal(2));assertEquals(row[2],r.getObject(3,LocalTime.class));assertEquals(row[3],r.getObject(4,LocalTime.class));assertEquals(timestamp,r.getTimestamp(5));assertEquals(row[5],r.getString(6));assertArrayEquals((byte[])row[6],r.getBytes(7));assertFalse(r.next());}
        assertEquals(1,writer.writeWithReceipt(names,Collections.singletonList(new Object[]{maximum,null,null,null,null,null,null})).acknowledgedRows());
    }
    @Test void temporalAndCollatedTextKeysUseNativeOrderingAtPageBoundaries()throws Exception {
        var names=List.of("WM","T","K","V");var primary=List.of("T","K");sql(control,"CREATE TABLE "+t("S")+"([WM] datetime2(7) NOT NULL,[T] time(7) NOT NULL,[K] nvarchar(30) COLLATE Latin1_General_100_CI_AS NOT NULL,[V] int,PRIMARY KEY([T],[K]))");
        sql(control,"INSERT "+t("S")+" VALUES('2026-10-03T23:59:59.9999999','00:00:00.0000001',N'z',1),('2026-10-03T23:59:59.9999999','00:00:00.0000001',N'Á',2),('2026-10-04T00:00:00.0000001','23:59:59.9999999',N'汉',3)");
        var observed=new ArrayList<Integer>();try(var cursor=cursor(names,primary,"WM",null,null)){while(cursor.next()){int value=(Integer)cursor.row()[3];observed.add(value);assertEquals(value==3?LocalTime.parse("23:59:59.9999999"):LocalTime.parse("00:00:00.0000001"),cursor.position().values().get(1).jdbcValue());}}
        assertEquals(List.of(2,1,3),observed);
    }
    @Test void explicitIdentityMergePreservesIdsAndNativeNextAllocation()throws Exception {
        sql(control,"CREATE TABLE "+t("T")+"([K] numeric(38,0) IDENTITY(7,3) PRIMARY KEY,[V] nvarchar(20))");var names=List.of("K","V");var writer=writer("T",names,List.of("K"));
        BigDecimal key=new BigDecimal("12345678901234567890123456789012345670");for(int i=0;i<2;i++)writer.writeWithReceipt(names,Collections.singletonList(new Object[]{key,"explicit"}));
        sql(control,"INSERT "+t("T")+"([V]) VALUES(N'native')");try(var s=control.createStatement();var r=s.executeQuery("SELECT [K] FROM "+t("T")+" WHERE [V]=N'native'")){assertTrue(r.next());assertEquals(key.add(new BigDecimal(3)),r.getBigDecimal(1));}
    }
    @Test void invalidLaterValueRejectsWholeBatchBeforeBorrowingTarget()throws Exception {
        sql(control,"CREATE TABLE "+t("T")+"([K] bigint PRIMARY KEY,[T] time(3),[V] varchar(4) COLLATE Latin1_General_100_BIN2)");var borrowed=new AtomicInteger();var source=new AbstractDataSource(){public Connection getConnection()throws SQLException{borrowed.incrementAndGet();return open();}public Connection getConnection(String u,String p)throws SQLException{return getConnection();}};
        var writer=new SqlServerUpsertWriter(source);writer.prepareUpsert(schema,"T",List.of("K","T","V"),List.of("K"));borrowed.set(0);
        assertThrows(RuntimeException.class,()->writer.writeWithReceipt(List.of("K","T","V"),List.of(new Object[]{1L,LocalTime.parse("00:00:00.123"),"ok"},new Object[]{2L,LocalTime.parse("00:00:00.1234567"),"ok"})));
        assertThrows(RuntimeException.class,()->writer.writeWithReceipt(List.of("K","T","V"),Collections.singletonList(new Object[]{3L,null,"汉"})));assertEquals(0,borrowed.get());assertEquals(0,count("T"));
    }
    @Test void commitBeforeCheckpointCrashRewindsWithoutDuplicatingTarget()throws Exception {
        create("S");create("T");rows(5);var calls=new AtomicInteger();assertThrows(IllegalStateException.class,()->transfer(ds(),value->calls.incrementAndGet()!=3&&save(value)));assertEquals(4,count("T"));assertFalse(saved.get().complete());
        transfer(ds(),this::save);assertEquals(5,count("T"));assertTrue(saved.get().complete());
    }
    @Test void actualCommitLostAcknowledgementDoesNotAdvanceCheckpoint()throws Exception {
        create("S");create("T");rows(2);var fault=fault("commit");assertThrows(UnconfirmedWriteException.class,()->transfer(fault,this::save));assertEquals(2,count("T"));assertNull(saved.get().applied());assertEquals(0,saved.get().sequence());transfer(ds(),this::save);assertEquals(2,count("T"));
    }
    @ParameterizedTest @ValueSource(strings={"ON","OFF","CLOSE"})
    void identitySessionOrCloseResponseLossRemainsUnknown(String boundary)throws Exception {
        sql(control,"CREATE TABLE "+t("T")+"([K] bigint IDENTITY(1,1) PRIMARY KEY,[V] int)");var enabled=new AtomicBoolean();
        var source=new AbstractDataSource(){public Connection getConnection()throws SQLException{var c=open();return(Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
            try{Object v=m.invoke(c,a);
                if(enabled.get()&&boundary.equals("CLOSE")&&m.getName().equals("close"))throw new SQLException("fixture close response lost");
                if(v instanceof Statement statement&&m.getName().equals("createStatement"))return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Statement.class},(sp,sm,sa)->{
                    try{Object answer=sm.invoke(statement,sa);if(enabled.get()&&sm.getName().equals("execute")&&sa!=null&&sa[0] instanceof String sql&&sql.startsWith("SET IDENTITY_INSERT ")&&sql.endsWith(" "+boundary))throw new SQLException("fixture identity setting response lost");return answer;}
                    catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});return v;
            }catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});}public Connection getConnection(String u,String p)throws SQLException{return getConnection();}};
        var writer=new SqlServerUpsertWriter(source);writer.prepareUpsert(schema,"T",List.of("K","V"),List.of("K"));enabled.set(true);
        assertThrows(UnconfirmedWriteException.class,()->writer.writeWithReceipt(List.of("K","V"),Collections.singletonList(new Object[]{9L,1})));
        assertEquals(boundary.equals("CLOSE")?1:0,count("T"));
        var retry=writer("T",List.of("K","V"),List.of("K"));retry.writeWithReceipt(List.of("K","V"),Collections.singletonList(new Object[]{9L,1}));assertEquals(1,count("T"));
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    @EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
    void publicServicePreservesExactNumericKeysAcrossPostgres(boolean sqlServerSource)throws Exception {
        Class.forName("org.postgresql.Driver");String pgUrl=System.getProperty("datahub.test.postgres.url"),pgUser=System.getProperty("datahub.test.postgres.user","dataforge_test"),pgPassword=System.getProperty("datahub.test.postgres.password","");
        try(var pg=DriverManager.getConnection(pgUrl,pgUser,pgPassword)) {
            String quoted='"'+schema+'"';sql(pg,"CREATE SCHEMA "+quoted);
            try {
                String pgTable=sqlServerSource?"T":"S";sql(pg,"CREATE TABLE "+quoted+".\""+pgTable+"\"(\"WM\" decimal(38,18) NOT NULL,\"K1\" bigint NOT NULL,\"K2\" numeric(38,0) NOT NULL,\"V\" varchar(80),PRIMARY KEY(\"K2\",\"K1\"))");create(sqlServerSource?"S":"T");
                if(sqlServerSource)rows(3);else sql(pg,"INSERT INTO "+quoted+".\"S\" VALUES(-0.000000000000000001,-9223372036854775808,99999999999999999999999999999999999999,'汉字')");
                var r=request();if(sqlServerSource){var target=r.getTarget();target.setTargetProductType(ProductTypeEnum.POSTGRESQL);target.setUrl(pgUrl);target.setUsername(pgUser);target.setPassword(pgPassword);}
                else{var source=r.getSource().getFirst();source.setSourceProductType(ProductTypeEnum.POSTGRESQL);source.setUrl(pgUrl);source.setUsername(pgUser);source.setPassword(pgPassword);}
                var result=new MigrationService(r,ignored->{},null,runtime).run();assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());assertTrue(saved.get().complete());
                if(sqlServerSource){try(var s=pg.createStatement();var resultRows=s.executeQuery("SELECT COUNT(*) FROM "+quoted+".\"T\"")){assertTrue(resultRows.next());assertEquals(3,resultRows.getInt(1));}}
                else try(var s=control.createStatement();var resultRows=s.executeQuery("SELECT * FROM "+t("T"))){assertTrue(resultRows.next());assertEquals(new BigDecimal("-1E-18"),resultRows.getBigDecimal(1));assertEquals(Long.MIN_VALUE,resultRows.getLong(2));assertEquals(new BigDecimal("99999999999999999999999999999999999999"),resultRows.getBigDecimal(3));assertEquals("汉字",resultRows.getString(4));}
            }finally{sql(pg,"DROP SCHEMA "+quoted+" CASCADE");}
        }
    }
    @Test void completedEmptyWindowRemainsFrozenUntilExplicitNextRun()throws Exception {
        create("S");create("T");var calls=new AtomicInteger();assertThrows(IllegalStateException.class,()->transfer(ds(),value->calls.incrementAndGet()==1&&save(value)));assertNull(saved.get().fixedUpper());rows(1);transfer(ds(),this::save);assertEquals(0,count("T"));transfer(ds(),this::save);assertEquals(1,count("T"));
    }
    @ParameterizedTest @ValueSource(strings={"SOURCE_RECREATE","TARGET_RECREATE","SOURCE_TRUNCATE","PIN_ROTATE"})
    void resumeRequiresUnchangedPhysicalSchemaAndExternalGeneration(String mode)throws Exception {
        create("S");create("T");rows(1);transfer(ds(),this::save);
        if(mode.equals("SOURCE_RECREATE")){sql(control,"DROP TABLE "+t("S"));create("S");rows(1);}else if(mode.equals("TARGET_RECREATE")){sql(control,"DROP TABLE "+t("T"));create("T");}
        else if(mode.equals("SOURCE_TRUNCATE"))sql(control,"TRUNCATE TABLE "+t("S"));else{SqlServerSourceGeneration.fence(control,pin,15);pin=SqlServerSourceGeneration.rotate(control,pin,UUID.randomUUID().toString(),15);}
        long before=count("T");assertThrows(RuntimeException.class,()->transfer(ds(),this::save));assertEquals(before,count("T"));
    }
    @Test void concurrentIndependentMergesSerializeWithoutDuplicateKeyRace()throws Exception {
        sql(control,"CREATE TABLE "+t("T")+"([K] bigint PRIMARY KEY,[V] int)");var one=writer("T",List.of("K","V"),List.of("K"));var two=writer("T",List.of("K","V"),List.of("K"));
        try(var threads=Executors.newFixedThreadPool(2)){var start=new CountDownLatch(1);var a=threads.submit(()->{start.await();return one.writeWithReceipt(List.of("K","V"),Collections.singletonList(new Object[]{1L,10}));});var b=threads.submit(()->{start.await();return two.writeWithReceipt(List.of("K","V"),Collections.singletonList(new Object[]{1L,20}));});start.countDown();assertEquals(1,a.get(10,TimeUnit.SECONDS).acknowledgedRows());assertEquals(1,b.get(10,TimeUnit.SECONDS).acknowledgedRows());}assertEquals(1,count("T"));
    }
    @Test void keyOnlyReplayAndConstraintFailureRetainSharedTransactionContract()throws Exception {
        sql(control,"CREATE TABLE "+t("T")+"([K] bigint PRIMARY KEY CHECK([K]>0))");var writer=writer("T",List.of("K"),List.of("K"));for(int i=0;i<2;i++)writer.writeWithReceipt(List.of("K"),Collections.singletonList(new Object[]{1L}));
        assertThrows(UnconfirmedWriteException.class,()->writer.writeWithReceipt(List.of("K"),List.of(new Object[]{2L},new Object[]{-1L})));assertEquals(1,count("T"));
    }
    @Test void sqlserver2000RouteCannotAcquireModernCursor()throws Exception {
        var source=new AbstractDataSource(){public Connection getConnection(){fail("No legacy cursor connection should be borrowed");return null;}public Connection getConnection(String u,String p){return getConnection();}};
        var legacy=new SqlServerDatabaseOperator(source,ProductTypeEnum.SQLSERVER2000);assertThrows(UnsupportedOperationException.class,()->legacy.openCursor(new CursorReadRequest(schema,"S",fields,"WM",keys,null,null,null,limits,false,pin)));
    }
    private DataSource fault(String method){return new AbstractDataSource(){public Connection getConnection()throws SQLException{Connection c=open();return(Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{try{Object v=m.invoke(c,a);if(m.getName().equals(method))throw new SQLException("fixture response lost");return v;}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});}public Connection getConnection(String u,String p)throws SQLException{return getConnection();}};}
}
