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

/**
 * HighGo Database 6 Release 6.0.4.2 cursor and ON CONFLICT upsert. Unquoted names are stored lowercase,
 * so the test spells every ordinary name in lowercase; mixed-case quoted names are refused by identity.
 */
@Timeout(120)
@EnabledIfSystemProperty(named="datahub.test.highgo.url",matches="jdbc:highgo://127[.]0[.]0[.]1:[0-9]+/highgo")
@EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
@EnabledIfEnvironmentVariable(named="DATAHUB_HIGHGO_TEST_PASSWORD",matches=".+")
class MigrationCursorHighgoTest {
    private DbSwitchRuntime runtime; private String schema; private Connection kb, pg;
    private final List<String> fields=List.of("wm","k1","k2","v"); private final List<String> keys=List.of("k2","k1");
    private final BatchLimits limits=new BatchLimits(2,65536,16384,65535,1000);
    private final BatchLimits pagedLimits=new BatchLimits(100,16*1024*1024,16384,65535,1000);
    private final AtomicReference<CursorCheckpoint> saved=new AtomicReference<>();
    private SourceGenerationPin pin;
    @BeforeEach void setup()throws Exception {
        java.util.logging.LogManager.getLogManager().reset();
        runtime=DbSwitchRuntime.standalone();
        kb=DriverManager.getConnection(url(ProductTypeEnum.HIGHGO),"highgo",password(ProductTypeEnum.HIGHGO));
        pg=DriverManager.getConnection(url(ProductTypeEnum.POSTGRESQL),user(ProductTypeEnum.POSTGRESQL),password(ProductTypeEnum.POSTGRESQL));
        schema="dbswitch_hg_cursor_"+UUID.randomUUID().toString().replace("-","");
        sql(kb,"CREATE SCHEMA "+q(schema));sql(pg,"CREATE SCHEMA "+q(schema));
        pin=new SourceGenerationPin(schema,UUID.randomUUID().toString(),UUID.randomUUID().toString());
        var generation=new DbSwitchProperties.SourceGeneration();
        generation.setProduct(ProductTypeEnum.HIGHGO);generation.setRestoreRotationRequired(true);
        generation.setTargetFingerprint(ConfiguredSourceGenerations.targetFingerprint(ProductTypeEnum.HIGHGO,url(ProductTypeEnum.HIGHGO),"highgo"));
        generation.setSchema(pin.schema());generation.setInstanceId(pin.instanceId());generation.setGeneration(pin.generation());
        pin=new ConfiguredSourceGenerations(Map.of("highgo",generation)).find(ProductTypeEnum.HIGHGO,url(ProductTypeEnum.HIGHGO),"highgo").orElseThrow();
    }
    @AfterEach void cleanup()throws Exception {
        try{if(kb!=null)try(var c=kb){sql(c,"DROP SCHEMA IF EXISTS "+q(schema)+" CASCADE");}}
        finally{try{if(pg!=null)try(var c=pg){sql(c,"DROP SCHEMA IF EXISTS "+q(schema)+" CASCADE");}}finally{if(runtime!=null)runtime.close();}}
    }
    private DataSource ds(ProductTypeEnum p){return new DriverManagerDataSource(url(p),user(p),password(p));}
    private Connection control(ProductTypeEnum p){return p==ProductTypeEnum.HIGHGO?kb:pg;}
    private void create(ProductTypeEnum p,String table)throws SQLException {
        sql(control(p),"CREATE TABLE "+t(table)+"(\"wm\" NUMERIC(38,18) NOT NULL,\"k1\" BIGINT NOT NULL,\"k2\" NUMERIC(38,0) NOT NULL,\"v\" VARCHAR(80),PRIMARY KEY(\"k2\",\"k1\"))");
    }
    private void rows(ProductTypeEnum p,int count)throws SQLException{for(int i=1;i<=count;i++)sql(control(p),"INSERT INTO "+t("s")+" VALUES(10,"+i+","+(i%2)+",'original')");}
    private long count(ProductTypeEnum p,String table)throws SQLException{try(var s=control(p).createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+t(table))){assertTrue(r.next());return r.getLong(1);}}
    private CursorRunBinding binding(CursorRunBinding.CheckpointStore store){return new CursorRunBinding("d".repeat(64),saved.get(),store,pin);}
    private boolean save(CursorCheckpoint c){saved.set(c);return true;}
    private DbSwitchTableResult transfer(ProductTypeEnum source,ProductTypeEnum target,DataSource targetSource,CursorRunBinding.CheckpointStore store)throws Exception {
        var result=new DbSwitchTableResult();
        CursorTransfer.execute(runtime,ds(source),targetSource,source,target,schema,"s",schema,"t",fields,fields,keys,keys,"wm",limits,2,binding(store),result,()->{});
        return result;
    }
    private MigrationRequest request(ProductTypeEnum source,ProductTypeEnum target) {
        var r=new MigrationRequest();var s=new SourceDataSourceProperties();
        s.setSourceProductType(source);s.setUrl(url(source));s.setUsername(user(source));s.setPassword(password(source));
        s.setPoolSize(1);s.setSourceSchema(schema);s.setSourceIncludes("s");s.setFetchSize(2);s.setIncreaseColumnName("wm");
        s.setCursorWatermarkContract("MONOTONIC_WATERMARK_V1");s.setRegexTableMapper(List.of(new PatternMapper("^s$","t")));
        r.getSource().add(s);var t=r.getTarget();
        t.setTargetProductType(target);t.setUrl(url(target));t.setUsername(user(target));t.setPassword(password(target));
        t.setTargetSchema(schema);t.setPoolSize(1);t.setChangeDataSync(true);t.setChangeDataSyncType("INCREASE_COLUMN");
        t.setCursorRunBindings(Map.of("s",binding(this::save)));return r;
    }
    @ParameterizedTest @CsvSource({"HIGHGO,HIGHGO","HIGHGO,POSTGRESQL","POSTGRESQL,HIGHGO"})
    void publicServiceUsesNativeCursorUpsertAndRewindsEqualWatermark(ProductTypeEnum source,ProductTypeEnum target)throws Exception {
        create(source,"s");create(target,"t");rows(source,5);var request=request(source,target);
        var first=new MigrationService(request,ignored->{},null,runtime).run();assertTrue(first.getIfAllSuccess().get(),()->first.getTableResultList().toString());
        assertEquals(5,count(target,"t"));assertTrue(saved.get().complete());
        assertEquals(target==ProductTypeEnum.HIGHGO?CommitAcknowledgement.JDBC_COMMIT_RETURNED:CommitAcknowledgement.POSTGRESQL_XID8_VERIFIED,saved.get().lastCommitAcknowledgement());
        sql(control(source),"INSERT INTO "+t("s")+" VALUES(10,0,0,'late equal watermark')");
        request.getTarget().setCursorRunBindings(Map.of("s",binding(this::save)));
        assertTrue(new MigrationService(request,ignored->{},null,runtime).run().getIfAllSuccess().get());
        assertEquals(6,count(target,"t"));
    }
    @Test void commitBeforeCheckpointCrashReplaysFrozenWindowThenAdvances()throws Exception {
        create(ProductTypeEnum.HIGHGO,"s");create(ProductTypeEnum.HIGHGO,"t");rows(ProductTypeEnum.HIGHGO,5);var calls=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->transfer(ProductTypeEnum.HIGHGO,ProductTypeEnum.HIGHGO,ds(ProductTypeEnum.HIGHGO),next->calls.incrementAndGet()!=3&&save(next)));
        assertEquals(4,count(ProductTypeEnum.HIGHGO,"t"));assertEquals(1,saved.get().sequence());assertFalse(saved.get().complete());assertNull(saved.get().lastCommit());
        sql(kb,"INSERT INTO "+t("s")+" VALUES(10,0,0,'late'),(11,99,99,'next window')");
        transfer(ProductTypeEnum.HIGHGO,ProductTypeEnum.HIGHGO,ds(ProductTypeEnum.HIGHGO),this::save);
        assertEquals(6,count(ProductTypeEnum.HIGHGO,"t"));
        transfer(ProductTypeEnum.HIGHGO,ProductTypeEnum.HIGHGO,ds(ProductTypeEnum.HIGHGO),this::save);
        assertEquals(7,count(ProductTypeEnum.HIGHGO,"t"));
    }
    @Test void actualCommitWithLostAckNeverAdvancesCheckpoint()throws Exception {
        create(ProductTypeEnum.HIGHGO,"s");create(ProductTypeEnum.HIGHGO,"t");rows(ProductTypeEnum.HIGHGO,2);
        var fault=new DriverManagerDataSource(url(ProductTypeEnum.HIGHGO),"highgo",password(ProductTypeEnum.HIGHGO)){
            @Override public Connection getConnection()throws SQLException {
                Connection c=super.getConnection();
                return(Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                    try{Object v=m.invoke(c,a);if(m.getName().equals("commit"))throw new SQLException("fixture lost acknowledgement");return v;}
                    catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
                });
            }
        };
        assertThrows(UnconfirmedWriteException.class,()->transfer(ProductTypeEnum.HIGHGO,ProductTypeEnum.HIGHGO,fault,this::save));
        assertEquals(2,count(ProductTypeEnum.HIGHGO,"t"));assertNull(saved.get().applied());assertEquals(0,saved.get().sequence());
        kb.close();kb=null;kb=DriverManager.getConnection(url(ProductTypeEnum.HIGHGO),"highgo",password(ProductTypeEnum.HIGHGO));
        transfer(ProductTypeEnum.HIGHGO,ProductTypeEnum.HIGHGO,ds(ProductTypeEnum.HIGHGO),this::save);
        assertTrue(saved.get().complete());assertEquals(2,count(ProductTypeEnum.HIGHGO,"t"));
    }
    @Test void repeatableReadSnapshotAllowsDmlButAccessSharePreventsTruncate()throws Exception {
        create(ProductTypeEnum.HIGHGO,"s");rows(ProductTypeEnum.HIGHGO,3);
        var reader=runtime.reader(ds(ProductTypeEnum.HIGHGO),ProductTypeEnum.HIGHGO);reader.setFetchSize(1);
        try(var cursor=reader.openCursor(new CursorReadRequest(schema,"s",fields,"wm",keys,null,null,null,limits,false,pin))) {
            assertTrue(cursor.next());
            sql(kb,"UPDATE "+t("s")+" SET \"v\"='changed' WHERE \"k1\"=3");sql(kb,"INSERT INTO "+t("s")+" VALUES(11,9,9,'after snapshot')");
            sql(kb,"SET lock_timeout='1s'");
            try(var s=kb.createStatement()){s.setQueryTimeout(3);assertThrows(SQLException.class,()->s.execute("TRUNCATE TABLE "+t("s")));}
            int read=1;while(cursor.next()){assertEquals("original",cursor.row()[3]);read++;}
            assertEquals(3,read);
        }
        sql(kb,"TRUNCATE TABLE "+t("s"));assertEquals(0,count(ProductTypeEnum.HIGHGO,"s"));
    }
    @ParameterizedTest @ValueSource(strings={"TARGET_RECREATE","SOURCE_TRUNCATE","DEPLOYED_GENERATION"})
    void resumeRejectsChangedPhysicalOrDeploymentGenerationBeforeWrites(String mode)throws Exception {
        create(ProductTypeEnum.HIGHGO,"s");create(ProductTypeEnum.HIGHGO,"t");rows(ProductTypeEnum.HIGHGO,1);
        transfer(ProductTypeEnum.HIGHGO,ProductTypeEnum.HIGHGO,ds(ProductTypeEnum.HIGHGO),this::save);
        if(mode.equals("TARGET_RECREATE")){sql(kb,"DROP TABLE "+t("t"));create(ProductTypeEnum.HIGHGO,"t");}
        else if(mode.equals("SOURCE_TRUNCATE")){sql(kb,"TRUNCATE TABLE "+t("s"));rows(ProductTypeEnum.HIGHGO,1);}
        else pin=new SourceGenerationPin(schema,pin.instanceId(),UUID.randomUUID().toString());
        long before=count(ProductTypeEnum.HIGHGO,"t");
        assertThrows(IllegalArgumentException.class,()->transfer(ProductTypeEnum.HIGHGO,ProductTypeEnum.HIGHGO,ds(ProductTypeEnum.HIGHGO),this::save));
        assertEquals(before,count(ProductTypeEnum.HIGHGO,"t"));
    }
    @Test void directlyBoundTimestamp6Decimal38AndByteaSurviveInsertUpdateReplay()throws Exception {
        sql(kb,"CREATE TABLE "+t("t")+"(\"k\" NUMERIC(38,0) PRIMARY KEY,\"ts\" TIMESTAMP(6),\"n\" NUMERIC(38,18),\"b\" BYTEA)");
        var writer=runtime.upsertWriter(ds(ProductTypeEnum.HIGHGO),ProductTypeEnum.HIGHGO);var names=List.of("k","ts","n","b");
        writer.prepareUpsert(schema,"t",names,List.of("k"));
        BigDecimal key=new BigDecimal("99999999999999999999999999999999999999");Timestamp ts=Timestamp.valueOf("2026-01-02 03:04:05.123456");
        for(int i=0;i<2;i++){var receipt=writer.writeWithReceipt(names,Collections.singletonList(new Object[]{key,ts,new BigDecimal("-1E-18"),new byte[]{0,(byte)255}}));
            assertEquals(1,receipt.acknowledgedRows());assertEquals(CommitAcknowledgement.JDBC_COMMIT_RETURNED,receipt.commitAcknowledgement());}
        try(var s=kb.createStatement();var r=s.executeQuery("SELECT * FROM "+t("t"))){assertTrue(r.next());
            assertEquals(key,r.getBigDecimal(1));assertEquals(ts,r.getTimestamp(2));assertEquals(new BigDecimal("-1E-18"),r.getBigDecimal(3));
            assertArrayEquals(new byte[]{0,(byte)255},r.getBytes(4));assertFalse(r.next());}
    }
    @Test void keysOnlyMergeAcknowledgesReplayWithoutDuplicatingRows()throws Exception {
        sql(kb,"CREATE TABLE "+t("t")+"(\"k\" BIGINT PRIMARY KEY)");
        var writer=runtime.upsertWriter(ds(ProductTypeEnum.HIGHGO),ProductTypeEnum.HIGHGO);
        writer.prepareUpsert(schema,"t",List.of("k"),List.of("k"));
        for(int i=0;i<2;i++)assertEquals(1,writer.writeWithReceipt(List.of("k"),Collections.singletonList(new Object[]{Long.MAX_VALUE})).acknowledgedRows());
        assertEquals(1,count(ProductTypeEnum.HIGHGO,"t"));
    }
    @Test void batchConstraintFailureRollsBackAllRowsAndNeverAcknowledges()throws Exception {
        sql(kb,"CREATE TABLE "+t("t")+"(\"k\" BIGINT PRIMARY KEY,\"v\" INT CHECK(\"v\">0))");
        var writer=runtime.upsertWriter(ds(ProductTypeEnum.HIGHGO),ProductTypeEnum.HIGHGO);
        writer.prepareUpsert(schema,"t",List.of("k","v"),List.of("k"));
        assertThrows(UnconfirmedWriteException.class,()->writer.writeWithReceipt(List.of("k","v"),List.of(new Object[]{1L,1},new Object[]{2L,-1})));
        assertEquals(0,count(ProductTypeEnum.HIGHGO,"t"));
    }
    @Test void invalidFractionalIntegerAndGeneratedDefaultsNeverWrite()throws Exception {
        sql(kb,"CREATE TABLE "+t("t")+"(\"k\" BIGINT PRIMARY KEY,\"v\" INT)");
        sql(kb,"CREATE TABLE "+t("i")+"(\"k\" BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,\"v\" INT)");
        var writer=runtime.upsertWriter(ds(ProductTypeEnum.HIGHGO),ProductTypeEnum.HIGHGO);
        writer.prepareUpsert(schema,"t",List.of("k","v"),List.of("k"));
        assertThrows(RuntimeException.class,()->writer.writeWithReceipt(List.of("k","v"),Collections.singletonList(new Object[]{1L,new BigDecimal("1.5")})));
        assertEquals(0,count(ProductTypeEnum.HIGHGO,"t"));
        assertThrows(IllegalArgumentException.class,()->writer.prepareUpsert(schema,"i",List.of("k","v"),List.of("k")));
        assertThrows(RuntimeException.class,()->writer.write(List.of("k","v"),Collections.singletonList(new Object[]{1L,1})));
        assertEquals(0,count(ProductTypeEnum.HIGHGO,"t"));assertEquals(0,count(ProductTypeEnum.HIGHGO,"i"));
    }
    @Test void numericPagesKeepNegativeDecimal38AndSignedIntegerBoundaries()throws Exception {
        create(ProductTypeEnum.HIGHGO,"s");String max="99999999999999999999999999999999999999";
        sql(kb,"INSERT INTO "+t("s")+" VALUES(-0.000000000000000001,-9223372036854775808,-"+max+",'first'),(-0.000000000000000001,9223372036854775807,-"+max+",'second'),(-0.000000000000000001,0,"+max+",'third'),(0,-1,-"+max+",'fourth')");
        var reader=runtime.reader(ds(ProductTypeEnum.HIGHGO),ProductTypeEnum.HIGHGO);reader.setFetchSize(1);var seen=new ArrayList<String>();
        try(var cursor=reader.openCursor(new CursorReadRequest(schema,"s",fields,"wm",keys,null,null,null,limits,false,pin))){
            while(cursor.next())seen.add((String)cursor.row()[3]);}
        assertEquals(List.of("first","second","third","fourth"),seen);
    }
    @Test void multiSegmentKeysetPagingEqualsGlobalSortedOrder()throws Exception {
        create(ProductTypeEnum.HIGHGO,"s");
        try(var p=kb.prepareStatement("INSERT INTO "+t("s")+" VALUES(?,?,?,?)")){
            for(int i=1;i<=350;i++){p.setBigDecimal(1,BigDecimal.valueOf(i*7919L%1009).movePointLeft(18).add(BigDecimal.valueOf(i)));
                p.setLong(2,i*104729L%1009);p.setBigDecimal(3,BigDecimal.valueOf(i*15485863L%1009));p.setString(4,"row"+i);p.addBatch();}
            p.executeBatch();
        }
        var expected=new ArrayList<String>();
        try(var s=kb.createStatement();var r=s.executeQuery("SELECT \"wm\",\"k1\",\"k2\",\"v\" FROM "+t("s")+" ORDER BY \"wm\",\"k1\",\"k2\"")){
            while(r.next())expected.add(r.getBigDecimal(1).toPlainString()+"|"+r.getLong(2)+"|"+r.getBigDecimal(3).toPlainString()+"|"+r.getString(4));}
        var reader=runtime.reader(ds(ProductTypeEnum.HIGHGO),ProductTypeEnum.HIGHGO);reader.setFetchSize(7);var walked=new ArrayList<String>();
        try(var cursor=reader.openCursor(new CursorReadRequest(schema,"s",fields,"wm",keys,null,null,null,pagedLimits,false,pin))){
            while(cursor.next()){var row=cursor.row();walked.add(((BigDecimal)row[0]).toPlainString()+"|"+row[1]+"|"+((BigDecimal)row[2]).toPlainString()+"|"+row[3]);}}
        assertEquals(expected,walked);
    }
    @Test void frozenEmptyWindowRemainsEmptyUntilNextCompletedRun()throws Exception {
        create(ProductTypeEnum.HIGHGO,"s");create(ProductTypeEnum.HIGHGO,"t");var calls=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->transfer(ProductTypeEnum.HIGHGO,ProductTypeEnum.HIGHGO,ds(ProductTypeEnum.HIGHGO),next->calls.incrementAndGet()==1&&save(next)));
        assertNull(saved.get().fixedUpper());assertFalse(saved.get().complete());
        rows(ProductTypeEnum.HIGHGO,1);
        transfer(ProductTypeEnum.HIGHGO,ProductTypeEnum.HIGHGO,ds(ProductTypeEnum.HIGHGO),this::save);
        assertEquals(0,count(ProductTypeEnum.HIGHGO,"t"));
        transfer(ProductTypeEnum.HIGHGO,ProductTypeEnum.HIGHGO,ds(ProductTypeEnum.HIGHGO),this::save);
        assertEquals(1,count(ProductTypeEnum.HIGHGO,"t"));
    }
    @Test void absentGenerationOrWrongKeyOrderFailsBeforeReading()throws Exception {
        create(ProductTypeEnum.HIGHGO,"s");rows(ProductTypeEnum.HIGHGO,1);
        var reader=runtime.reader(ds(ProductTypeEnum.HIGHGO),ProductTypeEnum.HIGHGO);
        assertThrows(IllegalArgumentException.class,()->reader.openCursor(new CursorReadRequest(schema,"s",fields,"wm",keys,null,null,null,limits)));
        assertThrows(IllegalArgumentException.class,()->reader.openCursor(new CursorReadRequest(schema,"s",fields,"wm",List.of("k1","k2"),null,null,null,limits,false,pin)));
        assertEquals(1,count(ProductTypeEnum.HIGHGO,"s"));
    }
    @Test void mixedCaseQuotedIdentifiersAreRefusedBeforeReading()throws Exception {
        String table="MiXedS";
        sql(kb,"CREATE TABLE "+q(schema)+"."+q(table)+"(\"wm\" NUMERIC(38,18) NOT NULL,\"k1\" BIGINT NOT NULL,\"k2\" NUMERIC(38,0) NOT NULL,\"v\" VARCHAR(20),PRIMARY KEY(\"k2\",\"k1\"))");
        sql(kb,"INSERT INTO "+q(schema)+"."+q(table)+" VALUES(1,1,1,'x')");
        var reader=runtime.reader(ds(ProductTypeEnum.HIGHGO),ProductTypeEnum.HIGHGO);
        assertThrows(IllegalArgumentException.class,()->reader.openCursor(new CursorReadRequest(schema,table,fields,"wm",keys,null,null,null,limits,false,pin)));
        assertEquals(1,count(ProductTypeEnum.HIGHGO,table));
    }
    @ParameterizedTest @ValueSource(strings={"NUL","NANOS","FRACTION","OVERFLOW","OVERLONG"})
    void exactValuePreflightRejectsLossBeforeAnyBatchWrite(String kind)throws Exception {
        sql(kb,"CREATE TABLE "+t("t")+"(\"k\" BIGINT PRIMARY KEY,\"v\" VARCHAR(3),\"n\" INT,\"ts\" TIMESTAMP(6))");
        var writer=runtime.upsertWriter(ds(ProductTypeEnum.HIGHGO),ProductTypeEnum.HIGHGO);var columns=List.of("k","v","n","ts");
        writer.prepareUpsert(schema,"t",columns,List.of("k"));
        Object[] good={1L,"ok",1,Timestamp.valueOf("2026-01-02 03:04:05.123456")};Object[] bad={2L,"ok",1,good[3]};
        switch(kind){case "NUL"->bad[1]="a\0";case "NANOS"->bad[3]=java.time.LocalDateTime.parse("2026-01-02T03:04:05.123456789");
            case "FRACTION"->bad[2]=new BigDecimal("1.5");case "OVERFLOW"->bad[0]=new BigInteger("9223372036854775808");
            case "OVERLONG"->bad[1]="a🙂汉b";default->throw new AssertionError();}
        assertThrows(BatchRejectedException.class,()->writer.writeWithReceipt(columns,List.of(good,bad)));
        assertEquals(0,count(ProductTypeEnum.HIGHGO,"t"));
    }
    @Test void emptyTextPayloadRoundtripsExactly()throws Exception {
        sql(kb,"CREATE TABLE "+t("t")+"(\"k\" BIGINT PRIMARY KEY,\"v\" VARCHAR(20))");
        var writer=runtime.upsertWriter(ds(ProductTypeEnum.HIGHGO),ProductTypeEnum.HIGHGO);
        writer.prepareUpsert(schema,"t",List.of("k","v"),List.of("k"));
        assertEquals(1,writer.writeWithReceipt(List.of("k","v"),Collections.singletonList(new Object[]{1L,""})).acknowledgedRows());
        try(var s=kb.createStatement();var r=s.executeQuery("SELECT v FROM "+t("t"))){assertTrue(r.next());assertEquals("",r.getString(1));}
    }
    @Test void time2400SourceRowIsRefusedInsteadOfSilentlyRewritten()throws Exception {
        sql(kb,"CREATE TABLE "+t("s")+"(\"wm\" NUMERIC(38,18) NOT NULL,\"k1\" BIGINT NOT NULL,\"k2\" NUMERIC(38,0) NOT NULL,\"v\" VARCHAR(20),\"tm\" TIME(6),PRIMARY KEY(\"k2\",\"k1\"))");
        sql(kb,"INSERT INTO "+t("s")+" VALUES(1,1,1,'x','24:00:00')");
        var reader=runtime.reader(ds(ProductTypeEnum.HIGHGO),ProductTypeEnum.HIGHGO);
        var columns=List.of("wm","k1","k2","v","tm");
        try(var cursor=reader.openCursor(new CursorReadRequest(schema,"s",columns,"wm",keys,null,null,null,limits,false,pin))){
            assertThrows(SQLException.class,cursor::next);
        }
        assertEquals(1,count(ProductTypeEnum.HIGHGO,"s"));
    }
    @ParameterizedTest @ValueSource(strings={"DEFAULT","IDENTITY","GENERATED"})
    void unsupportedGenerationAndKeySemanticsRejectBeforeWriting(String kind)throws Exception {
        String definition=switch(kind){
            case "DEFAULT"->"\"k\" BIGINT PRIMARY KEY,\"v\" INT DEFAULT 1";
            case "IDENTITY"->"\"k\" BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,\"v\" INT";
            case "GENERATED"->"\"k\" BIGINT PRIMARY KEY,\"v\" BIGINT GENERATED ALWAYS AS(\"k\"+1) STORED";
            default->throw new AssertionError();};
        sql(kb,"CREATE TABLE "+t("t")+"("+definition+")");
        var writer=runtime.upsertWriter(ds(ProductTypeEnum.HIGHGO),ProductTypeEnum.HIGHGO);
        assertThrows(IllegalArgumentException.class,()->writer.prepareUpsert(schema,"t",List.of("k","v"),List.of("k")));
        assertEquals(0,count(ProductTypeEnum.HIGHGO,"t"));
    }
    @ParameterizedTest @ValueSource(strings={"DEFAULT","IDENTITY","GENERATED","TEXT_WATERMARK"})
    void cursorRejectsUnsupportedSourceSemanticsBeforeReading(String kind)throws Exception {
        String definition=switch(kind){
            case "DEFAULT"->"\"wm\" NUMERIC(38,18) NOT NULL,\"k\" BIGINT PRIMARY KEY,\"v\" INT DEFAULT 1";
            case "IDENTITY"->"\"wm\" NUMERIC(38,18) NOT NULL,\"k\" BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,\"v\" INT";
            case "GENERATED"->"\"wm\" NUMERIC(38,18) NOT NULL,\"k\" BIGINT PRIMARY KEY,\"v\" BIGINT GENERATED ALWAYS AS(\"k\"+1) STORED";
            case "TEXT_WATERMARK"->"\"wm\" VARCHAR(20) NOT NULL,\"k\" BIGINT PRIMARY KEY,\"v\" INT";
            default->throw new AssertionError();};
        sql(kb,"CREATE TABLE "+t("s")+"("+definition+")");
        var reader=runtime.reader(ds(ProductTypeEnum.HIGHGO),ProductTypeEnum.HIGHGO);
        assertThrows(IllegalArgumentException.class,()->reader.openCursor(new CursorReadRequest(schema,"s",fields,"wm",keys,null,null,null,limits,false,pin)));
    }
    @Test void textKeySameProductReplaysUnderTheVerifiedCollationProfile()throws Exception {
        sql(kb,"CREATE TABLE "+t("s")+"(\"wm\" BIGINT NOT NULL,\"tk\" VARCHAR(20) NOT NULL,\"v\" VARCHAR(20),PRIMARY KEY(\"tk\"))");
        sql(kb,"CREATE TABLE "+t("t")+"(\"wm\" BIGINT NOT NULL,\"tk\" VARCHAR(20) NOT NULL,\"v\" VARCHAR(20),PRIMARY KEY(\"tk\"))");
        sql(kb,"INSERT INTO "+t("s")+" VALUES(1,'Alpha','a'),(2,'beta','b'),(3,'alpha','c')");
        assertTrue(new MigrationService(request(ProductTypeEnum.HIGHGO,ProductTypeEnum.HIGHGO),ignored->{},null,runtime).run().getIfAllSuccess().get());
        assertEquals(3,count(ProductTypeEnum.HIGHGO,"t"));
        try(var st=kb.createStatement();var r=st.executeQuery("SELECT \"v\" FROM "+t("t")+" WHERE \"tk\"='Alpha'")){assertTrue(r.next());assertEquals("a",r.getString(1));}
    }
    @Test void textKeyCrossProductReplayIsRejectedBeforeAnyTargetWrite()throws Exception {
        sql(kb,"CREATE TABLE "+t("stext")+"(\"wm\" BIGINT NOT NULL,\"tk\" VARCHAR(20) NOT NULL,\"v\" VARCHAR(20),PRIMARY KEY(\"tk\"))");
        sql(kb,"INSERT INTO "+t("stext")+" VALUES(1,'Alpha','a')");
        sql(pg,"CREATE TABLE "+pt("ttext")+"(\"wm\" BIGINT NOT NULL,\"tk\" VARCHAR(20) NOT NULL,\"v\" VARCHAR(20),PRIMARY KEY(\"tk\"))");
        var columns=List.of("wm","tk","v");var result=new DbSwitchTableResult();
        assertThrows(IllegalArgumentException.class,()->CursorTransfer.execute(runtime,ds(ProductTypeEnum.HIGHGO),ds(ProductTypeEnum.POSTGRESQL),
                ProductTypeEnum.HIGHGO,ProductTypeEnum.POSTGRESQL,schema,"stext",schema,"ttext",columns,columns,List.of("tk"),List.of("tk"),"wm",limits,2,binding(this::save),result,()->{}));
        assertEquals(0,count(ProductTypeEnum.POSTGRESQL,"ttext"));
    }
    @Test void timeValueColumnPreservesMicrosecondsInPublicCursor()throws Exception {
        for(String table:List.of("s","t"))sql(kb,"CREATE TABLE "+t(table)+"(\"wm\" NUMERIC(38,18) NOT NULL,\"k1\" BIGINT NOT NULL,\"k2\" NUMERIC(38,0) NOT NULL,\"v\" VARCHAR(80),\"tm\" TIME(6),PRIMARY KEY(\"k2\",\"k1\"))");
        var time=java.time.LocalTime.parse("03:04:05.123456");
        try(var p=kb.prepareStatement("INSERT INTO "+t("s")+" VALUES(?,?,?,?,?)")){
            p.setBigDecimal(1,new BigDecimal("-1E-18"));p.setLong(2,Long.MIN_VALUE);p.setBigDecimal(3,new BigDecimal("99999999999999999999999999999999999999"));
            p.setString(4,"汉字🙂");p.setObject(5,time);p.executeUpdate();}
        assertTrue(new MigrationService(request(ProductTypeEnum.HIGHGO,ProductTypeEnum.HIGHGO),ignored->{},null,runtime).run().getIfAllSuccess().get());
        try(var st=kb.createStatement();var r=st.executeQuery("SELECT \"tm\" FROM "+t("t"))){assertTrue(r.next());assertEquals(time,r.getObject(1,java.time.LocalTime.class));}
    }
    @Test void temporalWatermarkKeysetPagesInExactOrder()throws Exception {
        sql(kb,"CREATE TABLE "+t("s")+"(\"wm\" TIMESTAMP(6) NOT NULL,\"k\" BIGINT NOT NULL,\"v\" VARCHAR(20),PRIMARY KEY(\"k\"))");
        sql(kb,"CREATE TABLE "+t("t")+"(\"wm\" TIMESTAMP(6) NOT NULL,\"k\" BIGINT NOT NULL,\"v\" VARCHAR(20),PRIMARY KEY(\"k\"))");
        var columns=List.of("wm","k","v");
        try(var p=kb.prepareStatement("INSERT INTO "+t("s")+" VALUES(?,?,?)")){
            for(int i=1;i<=40;i++){p.setObject(1,java.time.LocalDateTime.parse("2026-01-01T08:00:0"+ (i%10) +".123456"));p.setLong(2,i);p.setString(3,"row"+i);p.addBatch();}
            p.executeBatch();
        }
        var request=request(ProductTypeEnum.HIGHGO,ProductTypeEnum.HIGHGO);
        var source=new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.HIGHGO);source.setUrl(url(ProductTypeEnum.HIGHGO));source.setUsername("highgo");source.setPassword(password(ProductTypeEnum.HIGHGO));
        source.setPoolSize(1);source.setSourceSchema(schema);source.setSourceIncludes("s");source.setFetchSize(3);source.setIncreaseColumnName("wm");
        source.setCursorWatermarkContract("MONOTONIC_WATERMARK_V1");source.setRegexTableMapper(List.of(new PatternMapper("^s$","t")));
        request.getSource().clear();request.getSource().add(source);
        assertTrue(new MigrationService(request,ignored->{},null,runtime).run().getIfAllSuccess().get());
        assertEquals(40,count(ProductTypeEnum.HIGHGO,"t"));
        try(var st=kb.createStatement();var r=st.executeQuery("SELECT count(*) FROM "+t("t")+" a JOIN "+t("s")+" b ON a.\"k\"=b.\"k\" AND a.\"wm\"=b.\"wm\" AND a.\"v\"=b.\"v\"")){assertTrue(r.next());assertEquals(40,r.getLong(1));}
    }
    @Test void triggerOnSourceRefusesCursorBeforeReading()throws Exception {
        sql(kb,"CREATE TABLE "+t("s")+"(\"wm\" NUMERIC(38,18) NOT NULL,\"k\" BIGINT PRIMARY KEY,\"v\" INT)");
        sql(kb,"CREATE OR REPLACE FUNCTION "+q(schema)+".noop() RETURNS trigger AS $$ BEGIN RETURN NEW; END; $$ LANGUAGE plpgsql");
        sql(kb,"CREATE TRIGGER noop_trigger BEFORE INSERT ON "+t("s")+" FOR EACH ROW EXECUTE FUNCTION "+q(schema)+".noop()");
        var reader=runtime.reader(ds(ProductTypeEnum.HIGHGO),ProductTypeEnum.HIGHGO);
        assertThrows(IllegalArgumentException.class,()->reader.openCursor(new CursorReadRequest(schema,"s",fields,"wm",keys,null,null,null,limits,false,pin)));
        assertEquals(0,count(ProductTypeEnum.HIGHGO,"s"));
    }
    @ParameterizedTest @ValueSource(strings={"DEFERRABLE","PARTIAL_INDEX","TRIGGER"})
    void unsupportedArbitersAndTargetTriggersRejectBeforeWriting(String kind)throws Exception {
        switch(kind){
            case "DEFERRABLE"->sql(kb,"CREATE TABLE "+t("t")+"(\"k\" BIGINT PRIMARY KEY DEFERRABLE INITIALLY DEFERRED,\"v\" INT)");
            case "PARTIAL_INDEX"->{sql(kb,"CREATE TABLE "+t("t")+"(\"k\" BIGINT,\"v\" INT)");sql(kb,"CREATE UNIQUE INDEX "+q("part_k")+" ON "+t("t")+"(k) WHERE k>0");}
            case "TRIGGER"->{sql(kb,"CREATE TABLE "+t("t")+"(\"k\" BIGINT PRIMARY KEY,\"v\" INT)");
                sql(kb,"CREATE OR REPLACE FUNCTION "+q(schema)+".noop() RETURNS trigger AS $$ BEGIN RETURN NEW; END; $$ LANGUAGE plpgsql");
                sql(kb,"CREATE TRIGGER noop_trigger BEFORE INSERT ON "+t("t")+" FOR EACH ROW EXECUTE FUNCTION "+q(schema)+".noop()");}
            default->throw new AssertionError();}
        var writer=runtime.upsertWriter(ds(ProductTypeEnum.HIGHGO),ProductTypeEnum.HIGHGO);
        assertThrows(IllegalArgumentException.class,()->writer.prepareUpsert(schema,"t",List.of("k","v"),List.of("k")));
        assertEquals(0,count(ProductTypeEnum.HIGHGO,"t"));
    }
    private String t(String table){return q(schema)+'.'+q(table);}
    private String pt(String table){return "\""+schema+"\".\""+table+"\"";}
    private static String q(String n){return "\""+n.replace("\"","\"\"")+"\"";}
    private static void sql(Connection c,String sql)throws SQLException{try(var s=c.createStatement()){s.setQueryTimeout(30);s.execute(sql);}}
    private static String url(ProductTypeEnum p){return System.getProperty(p==ProductTypeEnum.HIGHGO?"datahub.test.highgo.url":"datahub.test.postgres.url");}
    private static String user(ProductTypeEnum p){return p==ProductTypeEnum.HIGHGO?"highgo":System.getProperty("datahub.test.postgres.user","dataforge_test");}
    private static String password(ProductTypeEnum p){return p==ProductTypeEnum.HIGHGO?System.getenv("DATAHUB_HIGHGO_TEST_PASSWORD"):System.getProperty("datahub.test.postgres.password","");}
}
