package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.product.gbase8a.Gbase8aProductProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.util.Gbase8aSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.cursor.*;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import static org.junit.jupiter.api.Assertions.*;

/** Native 8.6 EXPRESS transactions and table locks, not MySQL ON DUPLICATE emulation. */
@Timeout(120)
@EnabledIfSystemProperty(named="datahub.test.gbase8a.url", matches="jdbc:gbase://127[.]0[.]0[.]1:[0-9]+/gbase")
@EnabledIfEnvironmentVariable(named="DATAHUB_GBASE8A_TEST_PASSWORD", matches=".+")
class GbaseAtomicUpsertTest {
    private static final List<String> FIELDS=List.of("id","amount","note");
    private Connection control;
    private DataSource source;
    private String schema;
    private String cursorSource;
    @BeforeEach void setup() throws Exception {
        Class.forName("com.gbase.jdbc.Driver");
        source=new DriverManagerDataSource(System.getProperty("datahub.test.gbase8a.url"),"root",System.getenv("DATAHUB_GBASE8A_TEST_PASSWORD"));
        control=source.getConnection();
        schema="gu89_"+UUID.randomUUID().toString().replace("-","");
        sql("CREATE DATABASE "+schema);
        sql("CREATE TABLE "+schema+".t(id BIGINT NOT NULL,amount DECIMAL(38,8),note VARCHAR(80),PRIMARY KEY(id)) ENGINE=EXPRESS DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
    }
    @AfterEach void cleanup() throws Exception {
        if(control!=null)try(var owned=control){if(cursorSource!=null)sql("DROP DATABASE "+cursorSource);if(schema!=null)sql("DROP DATABASE "+schema);}
    }
    @Test void insertUpdateReplayAndExactDecimals() throws Exception {
        var writer=writer(source); writer.prepareUpsert(schema,"t",FIELDS,List.of("id"));
        var amount=new BigDecimal("123456789012345678901234567890.12345678");
        var rows=List.of(new Object[]{1L,amount,"中文'\\"},new Object[]{2L,null,""});
        var first=writer.writeWithReceipt(FIELDS,rows);
        assertEquals(2,first.acknowledgedRows()); assertEquals(CommitAcknowledgement.JDBC_COMMIT_RETURNED,first.commitAcknowledgement());
        writer.write(FIELDS,rows); assertEquals(2,count());
        writer.write(FIELDS,List.of(new Object[]{1L,new BigDecimal("-0.00000001"),"changed"},new Object[]{3L,amount,"third"}));
        assertEquals(3,count());
        try(var s=control.createStatement();var r=s.executeQuery("SELECT amount,note FROM "+schema+".t WHERE id=3")){
            assertTrue(r.next());assertEquals(0,amount.compareTo(r.getBigDecimal(1)));assertEquals("third",r.getString(2));assertFalse(r.next());
        }
    }
    @Test void competingWritersDoNotCreateDuplicateKeys() throws Exception {
        int participants=4;var ready=new CountDownLatch(participants);var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(participants)){
            var tasks=new ArrayList<Future<?>>();
            for(int i=0;i<participants;i++){final int value=i;tasks.add(executor.submit(()->{
                var writer=writer(source);writer.prepareUpsert(schema,"t",FIELDS,List.of("id"));ready.countDown();
                assertTrue(start.await(20,TimeUnit.SECONDS));
                writer.write(FIELDS,List.of(new Object[]{1L,new BigDecimal(value),"v"+value},new Object[]{2L,new BigDecimal(value),"v"+value}));return null;
            }));}
            assertTrue(ready.await(20,TimeUnit.SECONDS));start.countDown();for(var task:tasks)task.get(40,TimeUnit.SECONDS);
        }
        assertEquals(2,count());
        try(var s=control.createStatement();var r=s.executeQuery("SELECT COUNT(DISTINCT note) FROM "+schema+".t")){assertTrue(r.next());assertEquals(1,r.getInt(1));}
    }
    @Test void preparedWriterSurvivesNativeDmlOnSamePhysicalTable() throws Exception {
        var writer=writer(source);writer.prepareUpsert(schema,"t",FIELDS,List.of("id"));
        Thread.sleep(1100); // 原厂 CREATE_TIME 是秒级，确保测试跨秒再触发表目录刷新。
        sql("INSERT INTO "+schema+".t VALUES(1,1,'native')");
        writer.write(FIELDS,Collections.singletonList(new Object[]{1L,BigDecimal.TEN,"updated"}));
        assertEquals(1,count());assertEquals("updated",note());
    }
    @Test void keysOnlyMergeReplaysWithoutNeedingAnUpdateClause() throws Exception {
        sql("CREATE TABLE "+schema+".keys_only(id INT NOT NULL,PRIMARY KEY(id)) ENGINE=EXPRESS");
        var writer=writer(source);writer.prepareUpsert(schema,"keys_only",List.of("id"),List.of("id"));
        var rows=List.of(new Object[]{1},new Object[]{2});writer.write(List.of("id"),rows);writer.write(List.of("id"),rows);
        try(var s=control.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+schema+".keys_only")){r.next();assertEquals(2,r.getInt(1));}
    }
    @Test void nativeCivilDateTimeAndNullValuesRoundTripExactly() throws Exception {
        sql("CREATE TABLE "+schema+".civil(id INT NOT NULL,d DATE,dt DATETIME,tm TIME,PRIMARY KEY(id)) ENGINE=EXPRESS");
        var fields=List.of("id","d","dt","tm");var writer=writer(source);writer.prepareUpsert(schema,"civil",fields,List.of("id"));
        var date=java.time.LocalDate.of(2026,10,1);var timestamp=date.atTime(12,34,56,123456000);var time=java.time.LocalTime.of(23,59,59);
        var rows=List.of(new Object[]{1,date,timestamp,time},new Object[]{2,null,null,null});writer.write(fields,rows);writer.write(fields,rows);
        try(var s=control.createStatement();var r=s.executeQuery("SELECT d,dt,tm FROM "+schema+".civil WHERE id=1")){
            r.next();assertEquals(date,r.getDate(1).toLocalDate());assertEquals(timestamp,r.getTimestamp(2).toLocalDateTime());assertEquals(time,r.getTime(3).toLocalTime());
        }
    }
    @Test void rejectsExistingAndIncomingDuplicateKeysWithoutChangingTarget() throws Exception {
        sql("INSERT INTO "+schema+".t VALUES(1,1,'sentinel')");
        var writer=writer(source);writer.prepareUpsert(schema,"t",FIELDS,List.of("id"));
        assertThrows(RuntimeException.class,()->writer.write(FIELDS,List.of(new Object[]{1L,BigDecimal.ONE,"a"},new Object[]{1L,BigDecimal.TEN,"b"})));
        assertEquals(1,count());assertEquals("sentinel",note());
        sql("INSERT INTO "+schema+".t VALUES(1,2,'duplicate')");
        assertThrows(IllegalArgumentException.class,()->writer(source).prepareUpsert(schema,"t",FIELDS,List.of("id")));
        assertEquals(2,count());
    }
    @Test void rejectsLossyWholeBatchAndCallerTransaction() throws Exception {
        var writer=writer(source);writer.prepareUpsert(schema,"t",FIELDS,List.of("id"));
        assertThrows(RuntimeException.class,()->writer.write(FIELDS,List.of(new Object[]{1L,BigDecimal.ONE,"valid"},new Object[]{2L,new BigDecimal("0.000000001"),"lossy"})));
        assertEquals(0,count());
        control.setAutoCommit(false);
        try {assertThrows(IllegalArgumentException.class,()->writer(new SingleConnectionDataSource(control,true)).prepareUpsert(schema,"t",FIELDS,List.of("id")));}
        finally {control.rollback();control.setAutoCommit(true);}
        assertEquals(0,count());
    }
    @Test void unknownCommitNeverReplaysOrReturnsReceipt() throws Exception {
        var fault=new Fault("commit");var writer=writer(fault.source(source));writer.prepareUpsert(schema,"t",FIELDS,List.of("id"));
        fault.armed=true;
        assertThrows(UnconfirmedWriteException.class,()->writer.writeWithReceipt(FIELDS,Collections.singletonList(new Object[]{1L,BigDecimal.ONE,"committed"})));
        assertEquals(1,fault.merges);assertEquals(1,count());assertEquals("committed",note());
        assertThrows(RuntimeException.class,()->writer.write(FIELDS,Collections.singletonList(new Object[]{1L,BigDecimal.TEN,"retry"})));
        assertEquals(1,fault.merges);assertEquals(0,fault.cleanupAfterFailure);
        var recovered=writer(source);recovered.prepareUpsert(schema,"t",FIELDS,List.of("id"));
        recovered.write(FIELDS,Collections.singletonList(new Object[]{1L,BigDecimal.ONE,"reconciled"}));
        assertEquals(1,count());assertEquals("reconciled",note());
    }
    @Test void mergeFailureRollsBackButStillReportsUnknown() throws Exception {
        var fault=new Fault("merge");var writer=writer(fault.source(source));writer.prepareUpsert(schema,"t",FIELDS,List.of("id"));fault.armed=true;
        assertThrows(UnconfirmedWriteException.class,()->writer.write(FIELDS,List.of(new Object[]{1L,BigDecimal.ONE,"first"},new Object[]{2L,BigDecimal.TEN,"second"})));
        assertEquals(1,fault.merges);assertEquals(0,count());assertEquals(0,fault.cleanupAfterFailure);
    }
    @Test void cleanupFailureAfterCommitDoesNotAcknowledgeSuccess() throws Exception {
        var fault=new Fault("unlock");var writer=writer(fault.source(source));writer.prepareUpsert(schema,"t",FIELDS,List.of("id"));fault.armed=true;
        assertThrows(UnconfirmedWriteException.class,()->writer.write(FIELDS,Collections.singletonList(new Object[]{1L,BigDecimal.ONE,"committed"})));
        assertEquals(1,fault.merges);assertEquals(1,count());assertEquals(0,fault.cleanupAfterFailure);
    }
    @Test void repeatedFatalCloseKeepsTheUnknownCommitMarker() throws Exception {
        var fault=new Fault("fatal-close");var writer=writer(fault.source(source));writer.prepareUpsert(schema,"t",FIELDS,List.of("id"));fault.armed=true;
        var failure=assertThrows(AssertionError.class,()->writer.write(FIELDS,Collections.singletonList(new Object[]{1L,BigDecimal.ONE,"committed"})));
        assertTrue(Arrays.stream(failure.getSuppressed()).anyMatch(UnconfirmedWriteException.class::isInstance));
        assertEquals(1,count());assertEquals(1,fault.merges);assertTrue(fault.closeFailures>=2);
    }
    @Test void recreatedTableAndLossyTextAreRejectedBeforeMerge() throws Exception {
        var writer=writer(source);writer.prepareUpsert(schema,"t",FIELDS,List.of("id"));
        sql("DROP TABLE "+schema+".t");
        sql("CREATE TABLE "+schema+".t(id BIGINT NOT NULL,amount DECIMAL(38,8),note VARCHAR(80),PRIMARY KEY(id)) ENGINE=EXPRESS DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
        assertThrows(cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException.class,()->writer.write(FIELDS,Collections.singletonList(new Object[]{1L,BigDecimal.ONE,"stale"})));
        var current=writer(source);current.prepareUpsert(schema,"t",FIELDS,List.of("id"));
        assertThrows(cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException.class,()->current.write(FIELDS,List.of(new Object[]{1L,BigDecimal.ONE,"valid"},new Object[]{2L,BigDecimal.TEN,"超".repeat(81)})));
        assertEquals(0,count());
    }
    @Test void unknownPooledSessionIsEvictedBeforeNextBorrow() throws Exception {
        var fault=new Fault("commit");
        try(var pool=new com.zaxxer.hikari.HikariDataSource()){
            pool.setDataSource(fault.source(source));pool.setMaximumPoolSize(1);pool.setMinimumIdle(0);
            pool.setConnectionTimeout(5000);pool.setPoolName("gbase89-isolated");
            var writer=writer(pool);writer.prepareUpsert(schema,"t",FIELDS,List.of("id"));fault.armed=true;
            assertThrows(UnconfirmedWriteException.class,()->writer.write(FIELDS,Collections.singletonList(new Object[]{1L,BigDecimal.ONE,"committed"})));
            assertEquals(1,count());
            var next=writer(pool);next.prepareUpsert(schema,"t",FIELDS,List.of("id"));
            next.write(FIELDS,Collections.singletonList(new Object[]{1L,BigDecimal.TEN,"fresh session"}));
            assertEquals(1,count());assertEquals("fresh session",note());
            assertTrue(fault.connections>=2);
        }
    }
    @Test void publicCursorRequiresTargetGenerationAndRejectsRotatedCheckpoint() throws Exception {
        cursorSource=schema+"s";sql("CREATE DATABASE "+cursorSource);
        sql("CREATE TABLE "+cursorSource+".t(id BIGINT NOT NULL,amount DECIMAL(38,8),note VARCHAR(80),PRIMARY KEY(id)) ENGINE=EXPRESS DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
        sql("INSERT INTO "+cursorSource+".t VALUES(1,1,'first'),(2,2,'second')");
        var sourcePin=Gbase8aSourceGeneration.initialize(control,cursorSource,15);
        var targetPin=Gbase8aSourceGeneration.initialize(control,schema,15);
        var saved=new AtomicReference<CursorCheckpoint>();
        try(var runtime=DbSwitchRuntime.standalone()){
            assertThrows(IllegalArgumentException.class,()->transfer(runtime,sourcePin,null,saved));assertEquals(0,count());
            transfer(runtime,sourcePin,targetPin,saved);assertEquals(2,count());assertTrue(saved.get().complete());
            assertEquals(CommitAcknowledgement.JDBC_COMMIT_RETURNED,saved.get().lastCommitAcknowledgement());
            Thread.sleep(1100);
            sql("INSERT INTO "+cursorSource+".t VALUES(3,3,'next window')");
            transfer(runtime,sourcePin,targetPin,saved);assertEquals(3,count());
            Gbase8aSourceGeneration.fence(control,targetPin,15);
            assertThrows(IllegalArgumentException.class,()->transfer(runtime,sourcePin,targetPin,saved));
            sql("TRUNCATE TABLE "+schema+".t");
            var next=Gbase8aSourceGeneration.rotate(control,targetPin,UUID.randomUUID().toString(),15);
            assertThrows(IllegalArgumentException.class,()->transfer(runtime,sourcePin,next,saved));assertEquals(0,count());
            saved.set(null);migrate(runtime,sourcePin,next,saved);assertEquals(3,count());
        }
    }
    private void transfer(DbSwitchRuntime runtime,SourceGenerationPin sourcePin,SourceGenerationPin targetPin,AtomicReference<CursorCheckpoint> saved)throws Exception{
        CursorTransfer.execute(runtime,source,source,ProductTypeEnum.GBASE8A,ProductTypeEnum.GBASE8A,
                cursorSource,"t",schema,"t",FIELDS,FIELDS,List.of("id"),List.of("id"),"id",
                new BatchLimits(8,65536,16384,100,1000),1,
                new CursorRunBinding("b".repeat(64),saved.get(),checkpoint->{saved.set(checkpoint);return true;},sourcePin,targetPin),
                new DbSwitchTableResult(),()->{});
    }
    private void migrate(DbSwitchRuntime runtime,SourceGenerationPin sourcePin,SourceGenerationPin targetPin,AtomicReference<CursorCheckpoint> saved)throws Exception{
        var request=new MigrationRequest();var input=new SourceDataSourceProperties();
        input.setSourceProductType(ProductTypeEnum.GBASE8A);input.setDriverClassName("com.gbase.jdbc.Driver");
        input.setUrl(System.getProperty("datahub.test.gbase8a.url"));input.setUsername("root");input.setPassword(System.getenv("DATAHUB_GBASE8A_TEST_PASSWORD"));
        input.setSourceSchema(cursorSource);input.setSourceIncludes("t");input.setSourcePrimaryKeys(List.of("id"));
        input.setIncreaseColumnName("id");input.setCursorWatermarkContract("MONOTONIC_WATERMARK_V1");input.setPoolSize(2);input.setFetchSize(1);
        request.getSource().add(input);var output=request.getTarget();output.setTargetProductType(ProductTypeEnum.GBASE8A);
        output.setDriverClassName(input.getDriverClassName());output.setUrl(input.getUrl());output.setUsername(input.getUsername());output.setPassword(input.getPassword());
        output.setTargetSchema(schema);output.setPoolSize(2);output.setChangeDataSync(true);output.setChangeDataSyncType("INCREASE_COLUMN");
        output.setCursorRunBindings(Map.of("t",new CursorRunBinding("b".repeat(64),saved.get(),checkpoint->{saved.set(checkpoint);return true;},sourcePin,targetPin)));
        var result=new MigrationService(request,ignored->{},null,runtime).run();
        assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());assertTrue(saved.get().complete());
    }
    private IDatabaseUpsertWriter writer(DataSource ds){
        var writer=new Gbase8aProductProvider().products().iterator().next().upsertWriter(ds);
        writer.configure(new DbSwitchOptions(65536,false,false,15,0,0,250,0));return writer;
    }
    private void sql(String sql)throws SQLException{try(var s=control.createStatement()){s.setQueryTimeout(20);s.execute(sql);}}
    private long count()throws SQLException{try(var s=control.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+schema+".t")){r.next();return r.getLong(1);}}
    private String note()throws SQLException{try(var s=control.createStatement();var r=s.executeQuery("SELECT note FROM "+schema+".t")){r.next();return r.getString(1);}}
    private static final class Fault {
        private final String failure; private boolean armed,failed; private int merges,cleanupAfterFailure,connections,closeFailures;
        Fault(String failure){this.failure=failure;}
        DataSource source(DataSource delegate){return new org.springframework.jdbc.datasource.AbstractDataSource(){
            public Connection getConnection()throws SQLException{connections++;return (Connection)wrap(delegate.getConnection(),Connection.class,"");}
            public Connection getConnection(String user,String password)throws SQLException{return getConnection();}
        };}
        private Object wrap(Object delegate,Class<?> type,String prepared) {
        return java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{
            String sql=args!=null && args.length>0 && args[0] instanceof String text ? text : prepared;
            try {
                boolean executing=method.getName().startsWith("execute");
                if(executing && (sql.startsWith("DROP TEMPORARY") || sql.startsWith("UNLOCK")) && failed)cleanupAfterFailure++;
                if(executing && sql.startsWith("MERGE"))merges++;
                Object value=method.invoke(delegate,args);
                if(armed && type==Connection.class && failure.equals("fatal-close") && method.getName().equals("close")){
                    closeFailures++;throw new AssertionError("Synthetic repeated fatal connection close");
                }
                if(armed && !failed && (failure.equals("commit") && method.getName().equals("commit")
                        || failure.equals("merge") && executing && sql.startsWith("MERGE")
                        || failure.equals("unlock") && executing && sql.startsWith("UNLOCK"))){
                    failed=true;throw new SQLException("injected lost acknowledgement","08006");
                }
                if(value instanceof PreparedStatement)return wrap(value,PreparedStatement.class,sql);
                if(value instanceof Statement)return wrap(value,Statement.class,sql);
                return value;
            }catch(java.lang.reflect.InvocationTargetException failure){
                throw failure.getCause();
            }
        });
        }
    }
}
