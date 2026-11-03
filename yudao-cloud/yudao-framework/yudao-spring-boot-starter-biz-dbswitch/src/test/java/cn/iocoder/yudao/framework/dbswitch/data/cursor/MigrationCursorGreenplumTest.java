package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.config.*;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.sql.DataSource;
import java.math.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(120)
@EnabledIfSystemProperty(named="datahub.test.greenplum.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/postgres\\?sslmode=disable")
@EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
@EnabledIfEnvironmentVariable(named="DATAHUB_GREENPLUM_TEST_PASSWORD",matches=".+")
class MigrationCursorGreenplumTest {
    private DbSwitchRuntime runtime; private String schema; private Connection gp, pg;
    private final List<String> fields=List.of("WM","K1","K2","V"); private final List<String> keys=List.of("K2","K1");
    private final BatchLimits limits=new BatchLimits(2,65536,16384,65535,1000);
    private final BatchLimits pagedLimits=new BatchLimits(100,16*1024*1024,16384,65535,1000);
    private final AtomicReference<CursorCheckpoint> saved=new AtomicReference<>();
    private SourceGenerationPin pin;
    @BeforeEach void setup()throws Exception {
        java.util.logging.LogManager.getLogManager().reset();
        runtime=DbSwitchRuntime.standalone();
        gp=DriverManager.getConnection(url(ProductTypeEnum.GREENPLUM),"gpadmin",password(ProductTypeEnum.GREENPLUM));
        pg=DriverManager.getConnection(url(ProductTypeEnum.POSTGRESQL),user(ProductTypeEnum.POSTGRESQL),password(ProductTypeEnum.POSTGRESQL));
        schema="dbswitch_gp_cursor_"+UUID.randomUUID().toString().replace("-","");
        sql(gp,"CREATE SCHEMA "+q(schema));sql(pg,"CREATE SCHEMA "+q(schema));
        pin=new SourceGenerationPin(schema,UUID.randomUUID().toString(),UUID.randomUUID().toString());
        var generation=new DbSwitchProperties.SourceGeneration();
        generation.setProduct(ProductTypeEnum.GREENPLUM);generation.setRestoreRotationRequired(true);
        generation.setTargetFingerprint(ConfiguredSourceGenerations.targetFingerprint(ProductTypeEnum.GREENPLUM,url(ProductTypeEnum.GREENPLUM),user(ProductTypeEnum.GREENPLUM)));
        generation.setSchema(pin.schema());generation.setInstanceId(pin.instanceId());generation.setGeneration(pin.generation());
        pin=new ConfiguredSourceGenerations(Map.of("greenplum",generation)).find(ProductTypeEnum.GREENPLUM,url(ProductTypeEnum.GREENPLUM),user(ProductTypeEnum.GREENPLUM)).orElseThrow();
    }
    @AfterEach void cleanup()throws Exception {
        try{if(gp!=null)try(var c=gp){sql(c,"DROP SCHEMA IF EXISTS "+q(schema)+" CASCADE");}}
        finally{try{if(pg!=null)try(var c=pg){sql(c,"DROP SCHEMA IF EXISTS "+q(schema)+" CASCADE");}}finally{if(runtime!=null)runtime.close();}}
    }
    private DataSource ds(ProductTypeEnum p){return new DriverManagerDataSource(url(p),user(p),password(p));}
    private Connection control(ProductTypeEnum p){return p==ProductTypeEnum.GREENPLUM?gp:pg;}
    private void create(ProductTypeEnum p,String table)throws SQLException {
        sql(control(p),"CREATE TABLE "+t(table)+"(\"WM\" NUMERIC(38,18) NOT NULL,\"K1\" BIGINT NOT NULL,\"K2\" NUMERIC(38,0) NOT NULL,\"V\" VARCHAR(80),PRIMARY KEY(\"K2\",\"K1\"))"
                +(p==ProductTypeEnum.GREENPLUM?" DISTRIBUTED BY(\"K2\",\"K1\")":""));
    }
    private void rows(ProductTypeEnum p,int count)throws SQLException{for(int i=1;i<=count;i++)sql(control(p),"INSERT INTO "+t("S")+" VALUES(10,"+i+","+(i%2)+",'original')");}
    private long count(ProductTypeEnum p,String table)throws SQLException{try(var s=control(p).createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+t(table))){assertTrue(r.next());return r.getLong(1);}}
    private CursorRunBinding binding(CursorRunBinding.CheckpointStore store){return new CursorRunBinding("d".repeat(64),saved.get(),store,pin);}
    private boolean save(CursorCheckpoint c){saved.set(c);return true;}
    private DbSwitchTableResult transfer(ProductTypeEnum source,ProductTypeEnum target,DataSource targetSource,CursorRunBinding.CheckpointStore store)throws Exception {
        var result=new DbSwitchTableResult();
        CursorTransfer.execute(runtime,ds(source),targetSource,source,target,schema,"S",schema,"T",fields,fields,keys,keys,"WM",limits,2,binding(store),result,()->{});
        return result;
    }
    private MigrationRequest request(ProductTypeEnum source,ProductTypeEnum target) {
        var r=new MigrationRequest();var s=new SourceDataSourceProperties();
        s.setSourceProductType(source);s.setUrl(url(source));s.setUsername(user(source));s.setPassword(password(source));
        s.setPoolSize(1);s.setSourceSchema(schema);s.setSourceIncludes("S");s.setFetchSize(2);s.setIncreaseColumnName("WM");
        s.setCursorWatermarkContract("MONOTONIC_WATERMARK_V1");s.setRegexTableMapper(List.of(new PatternMapper("^S$","T")));
        r.getSource().add(s);var t=r.getTarget();
        t.setTargetProductType(target);t.setUrl(url(target));t.setUsername(user(target));t.setPassword(password(target));
        t.setTargetSchema(schema);t.setPoolSize(1);t.setChangeDataSync(true);t.setChangeDataSyncType("INCREASE_COLUMN");
        t.setCursorRunBindings(Map.of("S",binding(this::save)));return r;
    }
    @ParameterizedTest @CsvSource({"GREENPLUM,GREENPLUM","GREENPLUM,POSTGRESQL","POSTGRESQL,GREENPLUM"})
    void publicServiceUsesNativeCursorUpsertAndRewindsEqualWatermark(ProductTypeEnum source,ProductTypeEnum target)throws Exception {
        create(source,"S");create(target,"T");rows(source,5);var request=request(source,target);
        var first=new MigrationService(request,ignored->{},null,runtime).run();assertTrue(first.getIfAllSuccess().get(),()->first.getTableResultList().toString());
        assertEquals(5,count(target,"T"));assertTrue(saved.get().complete());
        assertEquals(target==ProductTypeEnum.GREENPLUM?CommitAcknowledgement.JDBC_COMMIT_RETURNED:CommitAcknowledgement.POSTGRESQL_XID8_VERIFIED,saved.get().lastCommitAcknowledgement());
        sql(control(source),"INSERT INTO "+t("S")+" VALUES(10,0,0,'late equal watermark')");
        request.getTarget().setCursorRunBindings(Map.of("S",binding(this::save)));
        assertTrue(new MigrationService(request,ignored->{},null,runtime).run().getIfAllSuccess().get());
        assertEquals(6,count(target,"T"));
    }
    @Test void commitBeforeCheckpointCrashReplaysFrozenWindowThenAdvances()throws Exception {
        create(ProductTypeEnum.GREENPLUM,"S");create(ProductTypeEnum.GREENPLUM,"T");rows(ProductTypeEnum.GREENPLUM,5);var calls=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->transfer(ProductTypeEnum.GREENPLUM,ProductTypeEnum.GREENPLUM,ds(ProductTypeEnum.GREENPLUM),next->calls.incrementAndGet()!=3&&save(next)));
        assertEquals(4,count(ProductTypeEnum.GREENPLUM,"T"));assertEquals(1,saved.get().sequence());assertFalse(saved.get().complete());assertNull(saved.get().lastCommit());
        sql(gp,"INSERT INTO "+t("S")+" VALUES(10,0,0,'late'),(11,99,99,'next window')");
        transfer(ProductTypeEnum.GREENPLUM,ProductTypeEnum.GREENPLUM,ds(ProductTypeEnum.GREENPLUM),this::save);
        assertEquals(6,count(ProductTypeEnum.GREENPLUM,"T"));
        transfer(ProductTypeEnum.GREENPLUM,ProductTypeEnum.GREENPLUM,ds(ProductTypeEnum.GREENPLUM),this::save);
        assertEquals(7,count(ProductTypeEnum.GREENPLUM,"T"));
    }
    @Test void actualCommitWithLostAckNeverAdvancesCheckpoint()throws Exception {
        create(ProductTypeEnum.GREENPLUM,"S");create(ProductTypeEnum.GREENPLUM,"T");rows(ProductTypeEnum.GREENPLUM,2);
        var fault=new DriverManagerDataSource(url(ProductTypeEnum.GREENPLUM),user(ProductTypeEnum.GREENPLUM),password(ProductTypeEnum.GREENPLUM)){
            @Override public Connection getConnection()throws SQLException {
                Connection c=super.getConnection();
                return(Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                    try{Object v=m.invoke(c,a);if(m.getName().equals("commit"))throw new SQLException("fixture lost acknowledgement");return v;}
                    catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
                });
            }
        };
        assertThrows(UnconfirmedWriteException.class,()->transfer(ProductTypeEnum.GREENPLUM,ProductTypeEnum.GREENPLUM,fault,this::save));
        assertEquals(2,count(ProductTypeEnum.GREENPLUM,"T"));assertNull(saved.get().applied());assertEquals(0,saved.get().sequence());
        gp.close();gp=null;gp=DriverManager.getConnection(url(ProductTypeEnum.GREENPLUM),user(ProductTypeEnum.GREENPLUM),password(ProductTypeEnum.GREENPLUM));
        transfer(ProductTypeEnum.GREENPLUM,ProductTypeEnum.GREENPLUM,ds(ProductTypeEnum.GREENPLUM),this::save);
        assertTrue(saved.get().complete());assertEquals(2,count(ProductTypeEnum.GREENPLUM,"T"));
    }
    @Test void repeatableReadSnapshotAllowsDmlButAccessSharePreventsTruncate()throws Exception {
        create(ProductTypeEnum.GREENPLUM,"S");rows(ProductTypeEnum.GREENPLUM,3);
        var reader=runtime.reader(ds(ProductTypeEnum.GREENPLUM),ProductTypeEnum.GREENPLUM);reader.setFetchSize(1);
        try(var cursor=reader.openCursor(new CursorReadRequest(schema,"S",fields,"WM",keys,null,null,null,limits,false,pin))) {
            assertTrue(cursor.next());
            sql(gp,"UPDATE "+t("S")+" SET \"V\"='changed' WHERE \"K1\"=3");sql(gp,"INSERT INTO "+t("S")+" VALUES(11,9,9,'after snapshot')");
            sql(gp,"SET lock_timeout='1s'");
            try(var s=gp.createStatement()){s.setQueryTimeout(3);assertThrows(SQLException.class,()->s.execute("TRUNCATE TABLE "+t("S")));}
            int read=1;while(cursor.next()){assertEquals("original",cursor.row()[3]);read++;}
            assertEquals(3,read);
        }
        sql(gp,"TRUNCATE TABLE "+t("S"));assertEquals(0,count(ProductTypeEnum.GREENPLUM,"S"));
    }
    @ParameterizedTest @ValueSource(strings={"TARGET_RECREATE","SOURCE_TRUNCATE","DEPLOYED_GENERATION"})
    void resumeRejectsChangedPhysicalOrDeploymentGenerationBeforeWrites(String mode)throws Exception {
        create(ProductTypeEnum.GREENPLUM,"S");create(ProductTypeEnum.GREENPLUM,"T");rows(ProductTypeEnum.GREENPLUM,1);
        transfer(ProductTypeEnum.GREENPLUM,ProductTypeEnum.GREENPLUM,ds(ProductTypeEnum.GREENPLUM),this::save);
        if(mode.equals("TARGET_RECREATE")){sql(gp,"DROP TABLE "+t("T"));create(ProductTypeEnum.GREENPLUM,"T");}
        else if(mode.equals("SOURCE_TRUNCATE")){sql(gp,"TRUNCATE TABLE "+t("S"));rows(ProductTypeEnum.GREENPLUM,1);}
        else pin=new SourceGenerationPin(schema,pin.instanceId(),UUID.randomUUID().toString());
        long before=count(ProductTypeEnum.GREENPLUM,"T");
        assertThrows(IllegalArgumentException.class,()->transfer(ProductTypeEnum.GREENPLUM,ProductTypeEnum.GREENPLUM,ds(ProductTypeEnum.GREENPLUM),this::save));
        assertEquals(before,count(ProductTypeEnum.GREENPLUM,"T"));
    }
    @Test void directlyBoundTimestamp6Decimal38AndByteaSurviveInsertUpdateReplay()throws Exception {
        sql(gp,"CREATE TABLE "+t("T")+"(\"K\" NUMERIC(38,0) PRIMARY KEY,\"TS\" TIMESTAMP(6),\"N\" NUMERIC(38,18),\"B\" BYTEA) DISTRIBUTED BY(\"K\")");
        var writer=runtime.upsertWriter(ds(ProductTypeEnum.GREENPLUM),ProductTypeEnum.GREENPLUM);var names=List.of("K","TS","N","B");
        writer.prepareUpsert(schema,"T",names,List.of("K"));
        BigDecimal key=new BigDecimal("99999999999999999999999999999999999999");Timestamp ts=Timestamp.valueOf("2026-01-02 03:04:05.123456");
        for(int i=0;i<2;i++){var receipt=writer.writeWithReceipt(names,Collections.singletonList(new Object[]{key,ts,new BigDecimal("-1E-18"),new byte[]{0,(byte)255}}));
            assertEquals(1,receipt.acknowledgedRows());assertEquals(CommitAcknowledgement.JDBC_COMMIT_RETURNED,receipt.commitAcknowledgement());}
        try(var s=gp.createStatement();var r=s.executeQuery("SELECT * FROM "+t("T"))){assertTrue(r.next());
            assertEquals(key,r.getBigDecimal(1));assertEquals(ts,r.getTimestamp(2));assertEquals(new BigDecimal("-1E-18"),r.getBigDecimal(3));
            assertArrayEquals(new byte[]{0,(byte)255},r.getBytes(4));assertFalse(r.next());}
    }
    @Test void keysOnlyMergeAcknowledgesReplayWithoutDuplicatingRows()throws Exception {
        sql(gp,"CREATE TABLE "+t("T")+"(\"K\" BIGINT PRIMARY KEY) DISTRIBUTED BY(\"K\")");
        var writer=runtime.upsertWriter(ds(ProductTypeEnum.GREENPLUM),ProductTypeEnum.GREENPLUM);
        writer.prepareUpsert(schema,"T",List.of("K"),List.of("K"));
        for(int i=0;i<2;i++)assertEquals(1,writer.writeWithReceipt(List.of("K"),Collections.singletonList(new Object[]{Long.MAX_VALUE})).acknowledgedRows());
        assertEquals(1,count(ProductTypeEnum.GREENPLUM,"T"));
    }
    @Test void batchConstraintFailureRollsBackAllRowsAndNeverAcknowledges()throws Exception {
        sql(gp,"CREATE TABLE "+t("T")+"(\"K\" BIGINT PRIMARY KEY,\"V\" INT CHECK(\"V\">0)) DISTRIBUTED BY(\"K\")");
        var writer=runtime.upsertWriter(ds(ProductTypeEnum.GREENPLUM),ProductTypeEnum.GREENPLUM);
        writer.prepareUpsert(schema,"T",List.of("K","V"),List.of("K"));
        assertThrows(UnconfirmedWriteException.class,()->writer.writeWithReceipt(List.of("K","V"),List.of(new Object[]{1L,1},new Object[]{2L,-1})));
        assertEquals(0,count(ProductTypeEnum.GREENPLUM,"T"));
    }
    @Test void invalidFractionalIntegerAndSequenceDefaultNeverWrite()throws Exception {
        sql(gp,"CREATE TABLE "+t("T")+"(\"K\" BIGINT PRIMARY KEY,\"V\" INT) DISTRIBUTED BY(\"K\")");
        sql(gp,"CREATE TABLE "+t("I")+"(\"K\" BIGSERIAL PRIMARY KEY,\"V\" INT) DISTRIBUTED BY(\"K\")");
        var writer=runtime.upsertWriter(ds(ProductTypeEnum.GREENPLUM),ProductTypeEnum.GREENPLUM);
        writer.prepareUpsert(schema,"T",List.of("K","V"),List.of("K"));
        assertThrows(RuntimeException.class,()->writer.writeWithReceipt(List.of("K","V"),Collections.singletonList(new Object[]{1L,new BigDecimal("1.5")})));
        assertEquals(0,count(ProductTypeEnum.GREENPLUM,"T"));
        assertThrows(IllegalArgumentException.class,()->writer.prepareUpsert(schema,"I",List.of("K","V"),List.of("K")));
        assertThrows(RuntimeException.class,()->writer.write(List.of("K","V"),Collections.singletonList(new Object[]{1L,1})));
        assertEquals(0,count(ProductTypeEnum.GREENPLUM,"T"));assertEquals(0,count(ProductTypeEnum.GREENPLUM,"I"));
    }
    @Test void lexicographicPagesKeepNegativeDecimal38AndSignedIntegerBoundaries()throws Exception {
        create(ProductTypeEnum.GREENPLUM,"S");String max="99999999999999999999999999999999999999";
        sql(gp,"INSERT INTO "+t("S")+" VALUES(-0.000000000000000001,-9223372036854775808,-"+max+",'first'),(-0.000000000000000001,9223372036854775807,-"+max+",'second'),(-0.000000000000000001,0,"+max+",'third'),(0,-1,-"+max+",'fourth')");
        var reader=runtime.reader(ds(ProductTypeEnum.GREENPLUM),ProductTypeEnum.GREENPLUM);reader.setFetchSize(1);var seen=new ArrayList<String>();
        try(var cursor=reader.openCursor(new CursorReadRequest(schema,"S",fields,"WM",keys,null,null,null,limits,false,pin))){
            while(cursor.next())seen.add((String)cursor.row()[3]);}
        assertEquals(List.of("first","second","third","fourth"),seen);
    }
    @Test void multiSegmentKeysetPagingEqualsGlobalSortedOrder()throws Exception {
        create(ProductTypeEnum.GREENPLUM,"S");
        try(var p=gp.prepareStatement("INSERT INTO "+t("S")+" VALUES(?,?,?,?)")){
            for(int i=1;i<=350;i++){p.setBigDecimal(1,BigDecimal.valueOf(i*7919L%1009).movePointLeft(18).add(BigDecimal.valueOf(i)));
                p.setLong(2,i*104729L%1009);p.setBigDecimal(3,BigDecimal.valueOf(i*15485863L%1009));p.setString(4,"row"+i);p.addBatch();}
            p.executeBatch();
        }
        var expected=new ArrayList<String>();
        try(var s=gp.createStatement();var r=s.executeQuery("SELECT \"WM\",\"K1\",\"K2\",\"V\" FROM "+t("S")+" ORDER BY \"WM\",\"K1\",\"K2\"")){
            while(r.next())expected.add(r.getBigDecimal(1).toPlainString()+"|"+r.getLong(2)+"|"+r.getBigDecimal(3).toPlainString()+"|"+r.getString(4));}
        var reader=runtime.reader(ds(ProductTypeEnum.GREENPLUM),ProductTypeEnum.GREENPLUM);reader.setFetchSize(7);var walked=new ArrayList<String>();
        try(var cursor=reader.openCursor(new CursorReadRequest(schema,"S",fields,"WM",keys,null,null,null,pagedLimits,false,pin))){
            while(cursor.next()){var row=cursor.row();walked.add(((BigDecimal)row[0]).toPlainString()+"|"+row[1]+"|"+((BigDecimal)row[2]).toPlainString()+"|"+row[3]);}}
        assertEquals(expected,walked);
    }
    @Test void frozenEmptyWindowRemainsEmptyUntilNextCompletedRun()throws Exception {
        create(ProductTypeEnum.GREENPLUM,"S");create(ProductTypeEnum.GREENPLUM,"T");var calls=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->transfer(ProductTypeEnum.GREENPLUM,ProductTypeEnum.GREENPLUM,ds(ProductTypeEnum.GREENPLUM),next->calls.incrementAndGet()==1&&save(next)));
        assertNull(saved.get().fixedUpper());assertFalse(saved.get().complete());
        rows(ProductTypeEnum.GREENPLUM,1);
        transfer(ProductTypeEnum.GREENPLUM,ProductTypeEnum.GREENPLUM,ds(ProductTypeEnum.GREENPLUM),this::save);
        assertEquals(0,count(ProductTypeEnum.GREENPLUM,"T"));
        transfer(ProductTypeEnum.GREENPLUM,ProductTypeEnum.GREENPLUM,ds(ProductTypeEnum.GREENPLUM),this::save);
        assertEquals(1,count(ProductTypeEnum.GREENPLUM,"T"));
    }
    @Test void absentGenerationOrWrongKeyOrderFailsBeforeReading()throws Exception {
        create(ProductTypeEnum.GREENPLUM,"S");rows(ProductTypeEnum.GREENPLUM,1);
        var reader=runtime.reader(ds(ProductTypeEnum.GREENPLUM),ProductTypeEnum.GREENPLUM);
        assertThrows(IllegalArgumentException.class,()->reader.openCursor(new CursorReadRequest(schema,"S",fields,"WM",keys,null,null,null,limits)));
        assertThrows(IllegalArgumentException.class,()->reader.openCursor(new CursorReadRequest(schema,"S",fields,"WM",List.of("K1","K2"),null,null,null,limits,false,pin)));
        assertEquals(1,count(ProductTypeEnum.GREENPLUM,"S"));
    }
    @Test void nativeIdentifierQuotingPreservesQuotedKeys()throws Exception {
        String source="s\"' ;",target="t\"' ;",watermark="w\";",key="k\"'",value="v\" ;";
        for(String table:List.of(source,target))
            sql(gp,"CREATE TABLE "+t(table)+"("+q(watermark)+" NUMERIC(38,18) NOT NULL,"+q(key)+" BIGINT PRIMARY KEY,"+q(value)+" VARCHAR(20)) DISTRIBUTED BY("+q(key)+")");
        try(var p=gp.prepareStatement("INSERT INTO "+t(source)+" VALUES(?,?,?)")){p.setBigDecimal(1,new BigDecimal("-1E-18"));p.setLong(2,Long.MIN_VALUE);p.setString(3,"quote'");p.executeUpdate();}
        var columns=List.of(watermark,key,value);var result=new DbSwitchTableResult();
        CursorTransfer.execute(runtime,ds(ProductTypeEnum.GREENPLUM),ds(ProductTypeEnum.GREENPLUM),ProductTypeEnum.GREENPLUM,ProductTypeEnum.GREENPLUM,
                schema,source,schema,target,columns,columns,List.of(key),List.of(key),watermark,limits,1,binding(this::save),result,()->{});
        assertEquals(1,result.getSyncCount().get());
        try(var st=gp.createStatement();var r=st.executeQuery("SELECT "+q(value)+" FROM "+t(target))){assertTrue(r.next());assertEquals("quote'",r.getString(1));}
    }
    @ParameterizedTest @ValueSource(strings={"NUL","NANOS","FRACTION","OVERFLOW","OVERLONG"})
    void exactValuePreflightRejectsLossBeforeAnyBatchWrite(String kind)throws Exception {
        sql(gp,"CREATE TABLE "+t("T")+"(\"K\" BIGINT PRIMARY KEY,\"V\" VARCHAR(3),\"N\" INT,\"TS\" TIMESTAMP(6)) DISTRIBUTED BY(\"K\")");
        var writer=runtime.upsertWriter(ds(ProductTypeEnum.GREENPLUM),ProductTypeEnum.GREENPLUM);var columns=List.of("K","V","N","TS");
        writer.prepareUpsert(schema,"T",columns,List.of("K"));
        Object[] good={1L,"ok",1,Timestamp.valueOf("2026-01-02 03:04:05.123456")};Object[] bad={2L,"ok",1,good[3]};
        switch(kind){case "NUL"->bad[1]="a\0";case "NANOS"->bad[3]=java.time.LocalDateTime.parse("2026-01-02T03:04:05.123456789");
            case "FRACTION"->bad[2]=new BigDecimal("1.5");case "OVERFLOW"->bad[0]=new BigInteger("9223372036854775808");
            case "OVERLONG"->bad[1]="a🙂汉b";default->throw new AssertionError();}
        assertThrows(BatchRejectedException.class,()->writer.writeWithReceipt(columns,List.of(good,bad)));
        assertEquals(0,count(ProductTypeEnum.GREENPLUM,"T"));
    }
    @Test void postgresStyleEmptyTextRoundtripsUnlikeOpenGaussCompatibility()throws Exception {
        create(ProductTypeEnum.GREENPLUM,"S");create(ProductTypeEnum.GREENPLUM,"T");sql(gp,"INSERT INTO "+t("S")+" VALUES(10,1,1,'')");
        assertTrue(new MigrationService(request(ProductTypeEnum.GREENPLUM,ProductTypeEnum.GREENPLUM),ignored->{},null,runtime).run().getIfAllSuccess().get());
        try(var s=gp.createStatement();var r=s.executeQuery("SELECT \"V\" FROM "+t("T"))){assertTrue(r.next());assertEquals("",r.getString(1));}
    }
    @ParameterizedTest @ValueSource(strings={"DEFAULT","SERIAL","GENERATED","IDENTITY","TEXT_KEY"})
    void unsupportedGenerationAndKeySemanticsRejectBeforeWriting(String kind)throws Exception {
        String definition=switch(kind){
            case "DEFAULT"->"\"K\" BIGINT PRIMARY KEY,\"V\" INT DEFAULT 1";
            case "SERIAL"->"\"K\" BIGSERIAL PRIMARY KEY,\"V\" INT";
            case "GENERATED"->"\"K\" BIGINT PRIMARY KEY,\"V\" BIGINT GENERATED ALWAYS AS(\"K\"+1) STORED";
            case "IDENTITY"->"\"K\" BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,\"V\" INT";
            case "TEXT_KEY"->"\"K\" VARCHAR(20) PRIMARY KEY,\"V\" INT";
            default->throw new AssertionError();};
        sql(gp,"CREATE TABLE "+t("T")+"("+definition+") DISTRIBUTED BY(\"K\")");
        var writer=runtime.upsertWriter(ds(ProductTypeEnum.GREENPLUM),ProductTypeEnum.GREENPLUM);
        assertThrows(IllegalArgumentException.class,()->writer.prepareUpsert(schema,"T",List.of("K","V"),List.of("K")));
        assertEquals(0,count(ProductTypeEnum.GREENPLUM,"T"));
    }
    @ParameterizedTest @ValueSource(strings={"DEFAULT","SERIAL","GENERATED","IDENTITY","TEXT_KEY"})
    void cursorRejectsUnsupportedSourceSemanticsBeforeReading(String kind)throws Exception {
        String definition=switch(kind){
            case "DEFAULT"->"\"WM\" NUMERIC(38,18) NOT NULL,\"K\" BIGINT PRIMARY KEY,\"V\" INT DEFAULT 1";
            case "SERIAL"->"\"WM\" NUMERIC(38,18) NOT NULL,\"K\" BIGSERIAL PRIMARY KEY,\"V\" INT";
            case "GENERATED"->"\"WM\" NUMERIC(38,18) NOT NULL,\"K\" BIGINT PRIMARY KEY,\"V\" BIGINT GENERATED ALWAYS AS(\"K\"+1) STORED";
            case "IDENTITY"->"\"WM\" NUMERIC(38,18) NOT NULL,\"K\" BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,\"V\" INT";
            case "TEXT_KEY"->"\"WM\" NUMERIC(38,18) NOT NULL,\"K\" VARCHAR(20) PRIMARY KEY,\"V\" INT";
            default->throw new AssertionError();};
        sql(gp,"CREATE TABLE "+t("S")+"("+definition+") DISTRIBUTED BY(\"K\")");
        var reader=runtime.reader(ds(ProductTypeEnum.GREENPLUM),ProductTypeEnum.GREENPLUM);
        assertThrows(IllegalArgumentException.class,()->reader.openCursor(new CursorReadRequest(schema,"S",fields,"WM",keys,null,null,null,limits,false,pin)));
    }
    @ParameterizedTest @ValueSource(strings={"AO_ROW","AO_COLUMN","REPLICATED","RANDOMLY"})
    void unverifiedStorageAndDistributionRefuseCursorBeforeReading(String kind)throws Exception {
        String clause=switch(kind){
            case "AO_ROW"->"USING ao_row DISTRIBUTED BY(\"K2\",\"K1\")";
            case "AO_COLUMN"->"USING ao_column DISTRIBUTED BY(\"K2\",\"K1\")";
            case "REPLICATED"->"DISTRIBUTED REPLICATED";
            case "RANDOMLY"->"DISTRIBUTED RANDOMLY";
            default->throw new AssertionError();};
        String ddl="CREATE TABLE "+t("S")+"(\"WM\" NUMERIC(38,18) NOT NULL,\"K1\" BIGINT NOT NULL,\"K2\" NUMERIC(38,0) NOT NULL,\"V\" VARCHAR(80),PRIMARY KEY(\"K2\",\"K1\")) "+clause;
        if(kind.equals("RANDOMLY"))assertThrows(SQLException.class,()->sql(gp,ddl));
        else{sql(gp,ddl);var reader=runtime.reader(ds(ProductTypeEnum.GREENPLUM),ProductTypeEnum.GREENPLUM);
            assertThrows(IllegalArgumentException.class,()->reader.openCursor(new CursorReadRequest(schema,"S",fields,"WM",keys,null,null,null,limits,false,pin)));}
    }
    @Test void upsertRefusesAppendOptimizedTargetBeforeWriting()throws Exception {
        sql(gp,"CREATE TABLE "+t("T")+"(\"K\" BIGINT PRIMARY KEY,\"V\" INT) USING ao_row DISTRIBUTED BY(\"K\")");
        var writer=runtime.upsertWriter(ds(ProductTypeEnum.GREENPLUM),ProductTypeEnum.GREENPLUM);
        assertThrows(IllegalArgumentException.class,()->writer.prepareUpsert(schema,"T",List.of("K","V"),List.of("K")));
        assertEquals(0,count(ProductTypeEnum.GREENPLUM,"T"));
    }
    @Test void timeValueColumnPreservesMicrosecondsInPublicCursor()throws Exception {
        for(String table:List.of("S","T"))sql(gp,"CREATE TABLE "+t(table)+"(\"WM\" NUMERIC(38,18) NOT NULL,\"K1\" BIGINT NOT NULL,\"K2\" NUMERIC(38,0) NOT NULL,\"V\" VARCHAR(80),\"TM\" TIME(6),PRIMARY KEY(\"K2\",\"K1\")) DISTRIBUTED BY(\"K2\",\"K1\")");
        var time=java.time.LocalTime.parse("03:04:05.123456");
        try(var p=gp.prepareStatement("INSERT INTO "+t("S")+" VALUES(?,?,?,?,?)")){
            p.setBigDecimal(1,new BigDecimal("-1E-18"));p.setLong(2,Long.MIN_VALUE);p.setBigDecimal(3,new BigDecimal("99999999999999999999999999999999999999"));
            p.setString(4,"汉字🙂");p.setObject(5,time);p.executeUpdate();}
        assertTrue(new MigrationService(request(ProductTypeEnum.GREENPLUM,ProductTypeEnum.GREENPLUM),ignored->{},null,runtime).run().getIfAllSuccess().get());
        try(var st=gp.createStatement();var r=st.executeQuery("SELECT \"TM\" FROM "+t("T"))){assertTrue(r.next());assertEquals(time,r.getObject(1,java.time.LocalTime.class));}
    }
    private String t(String table){return q(schema)+'.'+q(table);}
    private static String q(String n){return cn.iocoder.yudao.framework.dbswitch.product.greenplum.GreenplumNativeTables.quote(n);}
    private static void sql(Connection c,String sql)throws SQLException{try(var s=c.createStatement()){s.setQueryTimeout(30);s.execute(sql);}}
    private static String url(ProductTypeEnum p){return System.getProperty(p==ProductTypeEnum.GREENPLUM?"datahub.test.greenplum.url":"datahub.test.postgres.url");}
    private static String user(ProductTypeEnum p){return p==ProductTypeEnum.GREENPLUM?"gpadmin":System.getProperty("datahub.test.postgres.user","dataforge_test");}
    private static String password(ProductTypeEnum p){return p==ProductTypeEnum.GREENPLUM?System.getenv("DATAHUB_GREENPLUM_TEST_PASSWORD"):System.getProperty("datahub.test.postgres.password","");}
}
