package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.config.*;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.util.OpengaussRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.*;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.jdbc.datasource.AbstractDataSource;
import javax.sql.DataSource;
import java.math.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
@EnabledIfSystemProperty(named="datahub.test.opengauss.url",matches="jdbc:opengauss://127[.]0[.]0[.]1:[0-9]+/.*")
@EnabledIfSystemProperty(named="datahub.test.opengauss.driver",matches=".+")
@EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
@EnabledIfEnvironmentVariable(named="DATAHUB_OPENGAUSS_TEST_PASSWORD",matches=".+")
class MigrationCursorOpengaussTest {
    private ConfiguredJdbcDrivers drivers;private DbSwitchRuntime runtime;private String schema;private Connection og,pg;
    private final List<String> fields=List.of("WM","K1","K2","V");private final List<String> keys=List.of("K2","K1");
    private final BatchLimits limits=new BatchLimits(2,65536,16384,65535,1000);private final AtomicReference<CursorCheckpoint> saved=new AtomicReference<>();
    private SourceGenerationPin pin;
    @BeforeEach void setup()throws Exception {
        java.util.logging.LogManager.getLogManager().reset();
        Path jar=Path.of(System.getProperty("datahub.test.opengauss.driver")).toRealPath();var spec=new DbSwitchProperties.Driver();spec.setPath(jar.toString());spec.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        drivers=new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.OPENGAUSS,spec));runtime=new DbSwitchRuntime(DbSwitchProductRegistry.discover(),DbSwitchOptions.defaults(),drivers);
        og=open(ProductTypeEnum.OPENGAUSS);pg=open(ProductTypeEnum.POSTGRESQL);schema="OG_CURSOR_"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
        sql(og,"CREATE SCHEMA "+q(schema));sql(pg,"CREATE SCHEMA "+q(schema));pin=new SourceGenerationPin(schema,UUID.randomUUID().toString(),UUID.randomUUID().toString());
        var generation=new DbSwitchProperties.SourceGeneration();generation.setProduct(ProductTypeEnum.OPENGAUSS);generation.setRestoreRotationRequired(true);generation.setTargetFingerprint(ConfiguredSourceGenerations.targetFingerprint(ProductTypeEnum.OPENGAUSS,url(ProductTypeEnum.OPENGAUSS),user(ProductTypeEnum.OPENGAUSS)));generation.setSchema(pin.schema());generation.setInstanceId(pin.instanceId());generation.setGeneration(pin.generation());
        pin=new ConfiguredSourceGenerations(Map.of("opengauss",generation)).find(ProductTypeEnum.OPENGAUSS,url(ProductTypeEnum.OPENGAUSS),user(ProductTypeEnum.OPENGAUSS)).orElseThrow();

    }
    @AfterEach void cleanup()throws Exception {try{if(pg!=null)try(var c=pg){sql(c,"DROP SCHEMA "+q(schema)+" CASCADE");}}finally{try{if(og!=null)try(var c=og){sql(c,"DROP SCHEMA "+q(schema)+" CASCADE");}}finally{if(runtime!=null)runtime.close();if(drivers!=null)drivers.close();}}}
    private Connection open(ProductTypeEnum p)throws SQLException{return runtime.openConnection(p,null,url(p),user(p),password(p));}
    private DataSource ds(ProductTypeEnum p){return new AbstractDataSource(){public Connection getConnection()throws SQLException{return open(p);}public Connection getConnection(String u,String pw)throws SQLException{throw new SQLException("Explicit fixture principal required");}};}
    private Connection control(ProductTypeEnum p){return p==ProductTypeEnum.OPENGAUSS?og:pg;}
    private void create(ProductTypeEnum p,String table)throws SQLException{sql(control(p),"CREATE TABLE "+t(table)+"(\"WM\" DECIMAL(38,18) NOT NULL,\"K1\" BIGINT NOT NULL,\"K2\" DECIMAL(38,0) NOT NULL,\"V\" VARCHAR(80),PRIMARY KEY(\"K2\",\"K1\"))");}
    private void rows(ProductTypeEnum p,int count)throws SQLException{for(int i=1;i<=count;i++)sql(control(p),"INSERT INTO "+t("S")+" VALUES(10,"+i+","+(i%2)+",'original')");}
    private long count(ProductTypeEnum p,String table)throws SQLException{try(var s=control(p).createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+t(table))){assertTrue(r.next());return r.getLong(1);}}
    private CursorRunBinding binding(CursorRunBinding.CheckpointStore store){return new CursorRunBinding("d".repeat(64),saved.get(),store,pin);}
    private boolean save(CursorCheckpoint c){saved.set(c);return true;}
    private DbSwitchTableResult transfer(ProductTypeEnum source,ProductTypeEnum target,DataSource targetSource,CursorRunBinding.CheckpointStore store)throws Exception {
        var result=new DbSwitchTableResult();CursorTransfer.execute(runtime,ds(source),targetSource,source,target,schema,"S",schema,"T",fields,fields,keys,keys,"WM",limits,2,binding(store),result,()->{});return result;
    }
    private MigrationRequest request(ProductTypeEnum source,ProductTypeEnum target) {
        var r=new MigrationRequest();var s=new SourceDataSourceProperties();s.setSourceProductType(source);s.setUrl(url(source));s.setUsername(user(source));s.setPassword(password(source));s.setPoolSize(1);s.setSourceSchema(schema);s.setSourceIncludes("S");s.setFetchSize(2);s.setIncreaseColumnName("WM");s.setCursorWatermarkContract("MONOTONIC_WATERMARK_V1");s.setRegexTableMapper(List.of(new PatternMapper("^S$","T")));r.getSource().add(s);
        var t=r.getTarget();t.setTargetProductType(target);t.setUrl(url(target));t.setUsername(user(target));t.setPassword(password(target));t.setTargetSchema(schema);t.setPoolSize(1);t.setChangeDataSync(true);t.setChangeDataSyncType("INCREASE_COLUMN");t.setCursorRunBindings(Map.of("S",binding(this::save)));return r;
    }
    @ParameterizedTest @CsvSource({"OPENGAUSS,OPENGAUSS","OPENGAUSS,POSTGRESQL","POSTGRESQL,OPENGAUSS"})
    void publicServiceUsesNativeCursorUpsertAndRewindsEqualWatermark(ProductTypeEnum source,ProductTypeEnum target)throws Exception {
        create(source,"S");create(target,"T");rows(source,5);var request=request(source,target);
        if (source == ProductTypeEnum.OPENGAUSS) {
            var columns = runtime.metadata(ds(source), source).queryTableColumnMetaWithNativeEvidence(schema, "S");
            assertEquals(fields, columns.stream().map(column -> column.getFieldName()).toList());
            columns.forEach(column -> assertTrue(column.getMetadataEvidence().provenance().nativeProduct(source)));
        }
        var first=new MigrationService(request,ignored->{},null,runtime).run();assertTrue(first.getIfAllSuccess().get(),()->first.getTableResultList().toString());assertEquals(5,count(target,"T"));assertTrue(saved.get().complete());
        assertEquals(target==ProductTypeEnum.OPENGAUSS?CommitAcknowledgement.JDBC_COMMIT_RETURNED:CommitAcknowledgement.POSTGRESQL_XID8_VERIFIED,saved.get().lastCommitAcknowledgement());
        sql(control(source),"INSERT INTO "+t("S")+" VALUES(10,0,0,'late equal watermark')");request.getTarget().setCursorRunBindings(Map.of("S",binding(this::save)));
        assertTrue(new MigrationService(request,ignored->{},null,runtime).run().getIfAllSuccess().get());assertEquals(6,count(target,"T"));
    }
    @Test void commitBeforeCheckpointCrashReplaysFrozenWindowThenAdvances()throws Exception {
        create(ProductTypeEnum.OPENGAUSS,"S");create(ProductTypeEnum.OPENGAUSS,"T");rows(ProductTypeEnum.OPENGAUSS,5);var calls=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->transfer(ProductTypeEnum.OPENGAUSS,ProductTypeEnum.OPENGAUSS,ds(ProductTypeEnum.OPENGAUSS),next->calls.incrementAndGet()!=3&&save(next)));
        assertEquals(4,count(ProductTypeEnum.OPENGAUSS,"T"));assertEquals(1,saved.get().sequence());assertFalse(saved.get().complete());assertNull(saved.get().lastCommit());
        sql(og,"INSERT INTO "+t("S")+" VALUES(10,0,0,'late'),(11,99,99,'next window')");transfer(ProductTypeEnum.OPENGAUSS,ProductTypeEnum.OPENGAUSS,ds(ProductTypeEnum.OPENGAUSS),this::save);assertEquals(6,count(ProductTypeEnum.OPENGAUSS,"T"));
        transfer(ProductTypeEnum.OPENGAUSS,ProductTypeEnum.OPENGAUSS,ds(ProductTypeEnum.OPENGAUSS),this::save);assertEquals(7,count(ProductTypeEnum.OPENGAUSS,"T"));
    }
    @Test void actualCommitWithLostAckNeverAdvancesCheckpoint()throws Exception {
        create(ProductTypeEnum.OPENGAUSS,"S");create(ProductTypeEnum.OPENGAUSS,"T");rows(ProductTypeEnum.OPENGAUSS,2);var fault=new AbstractDataSource(){public Connection getConnection()throws SQLException{Connection c=open(ProductTypeEnum.OPENGAUSS);return(Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{try{Object v=m.invoke(c,a);if(m.getName().equals("commit"))throw new SQLException("fixture lost acknowledgement");return v;}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});}public Connection getConnection(String u,String p)throws SQLException{return getConnection();}};
        assertThrows(UnconfirmedWriteException.class,()->transfer(ProductTypeEnum.OPENGAUSS,ProductTypeEnum.OPENGAUSS,fault,this::save));assertEquals(2,count(ProductTypeEnum.OPENGAUSS,"T"));assertNull(saved.get().applied());assertEquals(0,saved.get().sequence());
        // The driver runtime drains after abort until every older lease, including this fixture control, has exited.
        og.close();og=null;og=open(ProductTypeEnum.OPENGAUSS);
        transfer(ProductTypeEnum.OPENGAUSS,ProductTypeEnum.OPENGAUSS,ds(ProductTypeEnum.OPENGAUSS),this::save);assertTrue(saved.get().complete());assertEquals(2,count(ProductTypeEnum.OPENGAUSS,"T"));
    }
    @Test void repeatableReadSnapshotAllowsDmlButAccessSharePreventsTruncate()throws Exception {
        create(ProductTypeEnum.OPENGAUSS,"S");rows(ProductTypeEnum.OPENGAUSS,3);var reader=runtime.reader(ds(ProductTypeEnum.OPENGAUSS),ProductTypeEnum.OPENGAUSS);reader.setFetchSize(1);
        try(var cursor=reader.openCursor(new CursorReadRequest(schema,"S",fields,"WM",keys,null,null,null,limits,false,pin))) {
            assertTrue(cursor.next());sql(og,"UPDATE "+t("S")+" SET \"V\"='changed' WHERE \"K1\"=3");sql(og,"INSERT INTO "+t("S")+" VALUES(11,9,9,'after snapshot')");
            sql(og,"SET lockwait_timeout=1000");try(var s=og.createStatement()){s.setQueryTimeout(2);assertThrows(SQLException.class,()->s.execute("TRUNCATE TABLE "+t("S")));}
            int read=1;while(cursor.next()){assertEquals("original",cursor.row()[3]);read++;}assertEquals(3,read);
        }
        sql(og,"TRUNCATE TABLE "+t("S"));assertEquals(0,count(ProductTypeEnum.OPENGAUSS,"S"));
    }
    @ParameterizedTest @ValueSource(strings={"TARGET_RECREATE","SOURCE_TRUNCATE","DEPLOYED_GENERATION"})
    void resumeRejectsChangedPhysicalOrDeploymentGenerationBeforeWrites(String mode)throws Exception {
        create(ProductTypeEnum.OPENGAUSS,"S");create(ProductTypeEnum.OPENGAUSS,"T");rows(ProductTypeEnum.OPENGAUSS,1);transfer(ProductTypeEnum.OPENGAUSS,ProductTypeEnum.OPENGAUSS,ds(ProductTypeEnum.OPENGAUSS),this::save);
        if(mode.equals("TARGET_RECREATE")){sql(og,"DROP TABLE "+t("T"));create(ProductTypeEnum.OPENGAUSS,"T");}
        else if(mode.equals("SOURCE_TRUNCATE")){sql(og,"TRUNCATE TABLE "+t("S"));rows(ProductTypeEnum.OPENGAUSS,1);}
        else pin=new SourceGenerationPin(schema,pin.instanceId(),UUID.randomUUID().toString());
        long before=count(ProductTypeEnum.OPENGAUSS,"T");assertThrows(IllegalArgumentException.class,()->transfer(ProductTypeEnum.OPENGAUSS,ProductTypeEnum.OPENGAUSS,ds(ProductTypeEnum.OPENGAUSS),this::save));assertEquals(before,count(ProductTypeEnum.OPENGAUSS,"T"));
    }
    @Test void directlyBoundTimestamp6AndDecimal38SurviveInsertUpdateReplay()throws Exception {
        sql(og,"CREATE TABLE "+t("T")+"(\"K\" DECIMAL(38,0) PRIMARY KEY,\"TS\" TIMESTAMP(6),\"N\" DECIMAL(38,18),\"B\" BYTEA)");var writer=runtime.upsertWriter(ds(ProductTypeEnum.OPENGAUSS),ProductTypeEnum.OPENGAUSS);var names=List.of("K","TS","N","B");writer.prepareUpsert(schema,"T",names,List.of("K"));
        BigDecimal key=new BigDecimal("99999999999999999999999999999999999999");Timestamp ts=Timestamp.valueOf("2026-01-02 03:04:05.123456");
        for(int i=0;i<2;i++){var receipt=writer.writeWithReceipt(names,Collections.singletonList(new Object[]{key,ts,new BigDecimal("-1E-18"),new byte[]{0,(byte)255}}));assertEquals(1,receipt.acknowledgedRows());assertEquals(CommitAcknowledgement.JDBC_COMMIT_RETURNED,receipt.commitAcknowledgement());}
        try(var s=og.createStatement();var r=s.executeQuery("SELECT * FROM "+t("T"))){assertTrue(r.next());assertEquals(key,r.getBigDecimal(1));assertEquals(ts,r.getTimestamp(2));assertEquals(new BigDecimal("-1E-18"),r.getBigDecimal(3));assertArrayEquals(new byte[]{0,(byte)255},r.getBytes(4));assertFalse(r.next());}
    }
    @Test void publicOpengaussCursorPreservesTimestamp6AndBinaryPayload()throws Exception {
        for(String table:List.of("S","T"))sql(og,"CREATE TABLE "+t(table)+"(\"WM\" DECIMAL(38,18) NOT NULL,\"K1\" BIGINT NOT NULL,\"K2\" DECIMAL(38,0) NOT NULL,\"V\" VARCHAR(80),\"TS\" TIMESTAMP(6),\"B\" BYTEA,PRIMARY KEY(\"K2\",\"K1\"))");
        var timestamp=Timestamp.valueOf("2026-01-02 03:04:05.123456");try(var p=og.prepareStatement("INSERT INTO "+t("S")+" VALUES(?,?,?,?,?,?)")){p.setBigDecimal(1,new BigDecimal("-1E-18"));p.setLong(2,Long.MIN_VALUE);p.setBigDecimal(3,new BigDecimal("99999999999999999999999999999999999999"));p.setString(4,"汉字🙂");p.setTimestamp(5,timestamp);p.setBytes(6,new byte[]{0,(byte)255});p.executeUpdate();}
        assertTrue(new MigrationService(request(ProductTypeEnum.OPENGAUSS,ProductTypeEnum.OPENGAUSS),ignored->{},null,runtime).run().getIfAllSuccess().get());
        try(var s=og.createStatement();var r=s.executeQuery("SELECT \"WM\",\"K1\",\"K2\",\"V\",\"TS\",\"B\" FROM "+t("T"))){assertTrue(r.next());assertEquals(new BigDecimal("-1E-18"),r.getBigDecimal(1));assertEquals(Long.MIN_VALUE,r.getLong(2));assertEquals(new BigDecimal("99999999999999999999999999999999999999"),r.getBigDecimal(3));assertEquals("汉字🙂",r.getString(4));assertEquals(timestamp,r.getTimestamp(5));assertArrayEquals(new byte[]{0,(byte)255},r.getBytes(6));}
    }
    @Test void frozenEmptyWindowRemainsEmptyUntilNextCompletedRun()throws Exception {
        create(ProductTypeEnum.OPENGAUSS,"S");create(ProductTypeEnum.OPENGAUSS,"T");var calls=new AtomicInteger();assertThrows(IllegalStateException.class,()->transfer(ProductTypeEnum.OPENGAUSS,ProductTypeEnum.OPENGAUSS,ds(ProductTypeEnum.OPENGAUSS),next->calls.incrementAndGet()==1&&save(next)));
        assertNull(saved.get().fixedUpper());assertFalse(saved.get().complete());rows(ProductTypeEnum.OPENGAUSS,1);transfer(ProductTypeEnum.OPENGAUSS,ProductTypeEnum.OPENGAUSS,ds(ProductTypeEnum.OPENGAUSS),this::save);assertEquals(0,count(ProductTypeEnum.OPENGAUSS,"T"));
        transfer(ProductTypeEnum.OPENGAUSS,ProductTypeEnum.OPENGAUSS,ds(ProductTypeEnum.OPENGAUSS),this::save);assertEquals(1,count(ProductTypeEnum.OPENGAUSS,"T"));
    }
    @Test void absentGenerationOrWrongKeyOrderFailsBeforeReading()throws Exception {
        create(ProductTypeEnum.OPENGAUSS,"S");rows(ProductTypeEnum.OPENGAUSS,1);var reader=runtime.reader(ds(ProductTypeEnum.OPENGAUSS),ProductTypeEnum.OPENGAUSS);
        assertThrows(IllegalArgumentException.class,()->reader.openCursor(new CursorReadRequest(schema,"S",fields,"WM",keys,null,null,null,limits)));
        assertThrows(IllegalArgumentException.class,()->reader.openCursor(new CursorReadRequest(schema,"S",fields,"WM",List.of("K1","K2"),null,null,null,limits,false,pin)));
        assertEquals(1,count(ProductTypeEnum.OPENGAUSS,"S"));
    }
    @Test void lexicographicPagesKeepNegativeDecimal38AndSignedIntegerBoundaries()throws Exception {
        create(ProductTypeEnum.OPENGAUSS,"S");String max="99999999999999999999999999999999999999";
        sql(og,"INSERT INTO "+t("S")+" VALUES(-0.000000000000000001,-9223372036854775808,-"+max+",'first'),(-0.000000000000000001,9223372036854775807,-"+max+",'second'),(-0.000000000000000001,0,"+max+",'third'),(0,-1,-"+max+",'fourth')");
        var reader=runtime.reader(ds(ProductTypeEnum.OPENGAUSS),ProductTypeEnum.OPENGAUSS);reader.setFetchSize(1);var seen=new ArrayList<String>();
        try(var cursor=reader.openCursor(new CursorReadRequest(schema,"S",fields,"WM",keys,null,null,null,limits,false,pin))){while(cursor.next())seen.add((String)cursor.row()[3]);}
        assertEquals(List.of("first","second","third","fourth"),seen);
    }
    @Test void keysOnlyMergeAcknowledgesReplayWithoutDuplicatingRows()throws Exception {
        sql(og,"CREATE TABLE "+t("T")+"(\"K\" BIGINT PRIMARY KEY)");var writer=runtime.upsertWriter(ds(ProductTypeEnum.OPENGAUSS),ProductTypeEnum.OPENGAUSS);writer.prepareUpsert(schema,"T",List.of("K"),List.of("K"));
        for(int i=0;i<2;i++)assertEquals(1,writer.writeWithReceipt(List.of("K"),Collections.singletonList(new Object[]{Long.MAX_VALUE})).acknowledgedRows());assertEquals(1,count(ProductTypeEnum.OPENGAUSS,"T"));
    }
    @Test void batchConstraintFailureRollsBackAllRowsAndNeverAcknowledges()throws Exception {
        sql(og,"CREATE TABLE "+t("T")+"(\"K\" BIGINT PRIMARY KEY,\"V\" INT UNIQUE)");var writer=runtime.upsertWriter(ds(ProductTypeEnum.OPENGAUSS),ProductTypeEnum.OPENGAUSS);writer.prepareUpsert(schema,"T",List.of("K","V"),List.of("K"));
        assertThrows(UnconfirmedWriteException.class,()->writer.writeWithReceipt(List.of("K","V"),List.of(new Object[]{1L,1},new Object[]{2L,1})));assertEquals(0,count(ProductTypeEnum.OPENGAUSS,"T"));
    }
    @Test void invalidFractionalIntegerAndIdentityReprepareNeverWrite()throws Exception {
        sql(og,"CREATE TABLE "+t("T")+"(\"K\" BIGINT PRIMARY KEY,\"V\" INT)");sql(og,"CREATE TABLE "+t("I")+"(\"K\" BIGSERIAL PRIMARY KEY,\"V\" INT)");var writer=runtime.upsertWriter(ds(ProductTypeEnum.OPENGAUSS),ProductTypeEnum.OPENGAUSS);writer.prepareUpsert(schema,"T",List.of("K","V"),List.of("K"));
        assertThrows(RuntimeException.class,()->writer.writeWithReceipt(List.of("K","V"),Collections.singletonList(new Object[]{1L,new BigDecimal("1.5")})));assertEquals(0,count(ProductTypeEnum.OPENGAUSS,"T"));
        assertThrows(IllegalArgumentException.class,()->writer.prepareUpsert(schema,"I",List.of("K","V"),List.of("K")));assertThrows(RuntimeException.class,()->writer.write(List.of("K","V"),Collections.singletonList(new Object[]{1L,1})));assertEquals(0,count(ProductTypeEnum.OPENGAUSS,"T"));assertEquals(0,count(ProductTypeEnum.OPENGAUSS,"I"));
    }
    @Test void aModeDateTimeAndMicrosecondTimeUseNativeTypesInPublicCursor()throws Exception {
        for(String table:List.of("S","T"))sql(og,"CREATE TABLE "+t(table)+"(\"WM\" DECIMAL(38,18) NOT NULL,\"K1\" BIGINT NOT NULL,\"K2\" DECIMAL(38,0) NOT NULL,\"V\" VARCHAR(80),\"D\" DATE,\"TM\" TIME(6),\"FLAG\" BOOLEAN,PRIMARY KEY(\"K2\",\"K1\"))");
        var date=Timestamp.valueOf("2026-01-02 03:04:05");var time=java.time.LocalTime.parse("03:04:05.123456");
        try(var p=og.prepareStatement("INSERT INTO "+t("S")+" VALUES(?,?,?,?,?,?,?)")){p.setBigDecimal(1,new BigDecimal("-1E-18"));p.setLong(2,Long.MIN_VALUE);p.setBigDecimal(3,new BigDecimal("99999999999999999999999999999999999999"));p.setString(4,"汉字🙂");p.setTimestamp(5,date);p.setObject(6,time);p.setBoolean(7,true);p.executeUpdate();}
        assertTrue(new MigrationService(request(ProductTypeEnum.OPENGAUSS,ProductTypeEnum.OPENGAUSS),ignored->{},null,runtime).run().getIfAllSuccess().get());
        try(var st=og.createStatement();var r=st.executeQuery("SELECT \"D\",\"TM\",\"FLAG\" FROM "+t("T"))){assertTrue(r.next());assertEquals(date,r.getTimestamp(1));assertEquals(time,r.getObject(2,java.time.LocalTime.class));assertTrue(r.getBoolean(3));assertFalse(r.next());}
    }
    @Test void nativeIdentifierQuotingAndProjectionMappingPreserveQuotedKeys()throws Exception {
        String source="s\"' ;",target="t\"' ;",watermark="w\";",key="k\"'",value="v\" ;";
        for(String table:List.of(source,target))sql(og,"CREATE TABLE "+t(table)+"("+q(watermark)+" NUMERIC(38,18) NOT NULL,"+q(key)+" BIGINT PRIMARY KEY,"+q(value)+" VARCHAR(20))");
        try(var p=og.prepareStatement("INSERT INTO "+t(source)+" VALUES(?,?,?)")){p.setBigDecimal(1,new BigDecimal("-1E-18"));p.setLong(2,Long.MIN_VALUE);p.setString(3,"quote'");p.executeUpdate();}
        var columns=List.of(watermark,key,value);var result=new DbSwitchTableResult();CursorTransfer.execute(runtime,ds(ProductTypeEnum.OPENGAUSS),ds(ProductTypeEnum.OPENGAUSS),ProductTypeEnum.OPENGAUSS,ProductTypeEnum.OPENGAUSS,schema,source,schema,target,columns,columns,List.of(key),List.of(key),watermark,limits,1,binding(this::save),result,()->{});
        assertEquals(1,result.getSyncCount().get());try(var st=og.createStatement();var r=st.executeQuery("SELECT "+q(value)+" FROM "+t(target))){assertTrue(r.next());assertEquals("quote'",r.getString(1));}
    }
    @ParameterizedTest @ValueSource(strings={"EMPTY","NUL","NANOS","FRACTION","OVERFLOW","OVERLONG"})
    void exactValuePreflightRejectsLossBeforeAnyBatchWrite(String kind)throws Exception {
        sql(og,"CREATE TABLE "+t("T")+"(\"K\" BIGINT PRIMARY KEY,\"V\" VARCHAR(3),\"N\" INT,\"TS\" TIMESTAMP(6))");var writer=runtime.upsertWriter(ds(ProductTypeEnum.OPENGAUSS),ProductTypeEnum.OPENGAUSS);var columns=List.of("K","V","N","TS");writer.prepareUpsert(schema,"T",columns,List.of("K"));
        Object[] good={1L,"ok",1,Timestamp.valueOf("2026-01-02 03:04:05.123456")};Object[] bad={2L,"ok",1,good[3]};
        switch(kind){case "EMPTY"->bad[1]="";case "NUL"->bad[1]="a\0";case "NANOS"->bad[3]=java.time.LocalDateTime.parse("2026-01-02T03:04:05.123456789");case "FRACTION"->bad[2]=new BigDecimal("1.5");case "OVERFLOW"->bad[0]=new BigInteger("9223372036854775808");case "OVERLONG"->bad[1]="a🙂汉b";default->throw new AssertionError();}
        assertThrows(BatchRejectedException.class,()->writer.writeWithReceipt(columns,List.of(good,bad)));assertEquals(0,count(ProductTypeEnum.OPENGAUSS,"T"));
    }
    @Test void postgresEmptyTextCannotBecomeNullInOpenGaussACompatibility()throws Exception {
        create(ProductTypeEnum.POSTGRESQL,"S");create(ProductTypeEnum.OPENGAUSS,"T");sql(pg,"INSERT INTO "+t("S")+" VALUES(10,1,1,'')");
        var result=new MigrationService(request(ProductTypeEnum.POSTGRESQL,ProductTypeEnum.OPENGAUSS),ignored->{},null,runtime).run();assertFalse(result.getIfAllSuccess().get());assertEquals(0,count(ProductTypeEnum.OPENGAUSS,"T"));assertNotNull(saved.get());assertEquals(0,saved.get().sequence());assertFalse(saved.get().complete());
    }
    @ParameterizedTest @ValueSource(strings={"DEFAULT","SERIAL","GENERATED","TEXT_KEY"})
    void unsupportedGenerationAndKeySemanticsRejectBeforeWriting(String kind)throws Exception {
        String definition=switch(kind){case "DEFAULT"->"\"K\" BIGINT PRIMARY KEY,\"V\" INT DEFAULT 1";case "SERIAL"->"\"K\" BIGSERIAL PRIMARY KEY,\"V\" INT";case "GENERATED"->"\"K\" BIGINT PRIMARY KEY,\"V\" BIGINT GENERATED ALWAYS AS(\"K\"+1) STORED";case "TEXT_KEY"->"\"K\" VARCHAR(20) PRIMARY KEY,\"V\" INT";default->throw new AssertionError();};
        sql(og,"CREATE TABLE "+t("T")+"("+definition+")");var writer=runtime.upsertWriter(ds(ProductTypeEnum.OPENGAUSS),ProductTypeEnum.OPENGAUSS);assertThrows(IllegalArgumentException.class,()->writer.prepareUpsert(schema,"T",List.of("K","V"),List.of("K")));assertEquals(0,count(ProductTypeEnum.OPENGAUSS,"T"));
    }
    private String t(String table){return q(schema)+'.'+q(table);}private static String q(String n){return OpengaussRelationIdentity.quote(n);}private static void sql(Connection c,String sql)throws SQLException{try(var s=c.createStatement()){s.setQueryTimeout(15);s.execute(sql);}}
    private static String url(ProductTypeEnum p){return System.getProperty(p==ProductTypeEnum.OPENGAUSS?"datahub.test.opengauss.url":"datahub.test.postgres.url");}
    private static String user(ProductTypeEnum p){return p==ProductTypeEnum.OPENGAUSS?System.getProperty("datahub.test.opengauss.user","dataforge_test"):System.getProperty("datahub.test.postgres.user","dataforge_test");}
    private static String password(ProductTypeEnum p){return p==ProductTypeEnum.OPENGAUSS?System.getenv("DATAHUB_OPENGAUSS_TEST_PASSWORD"):System.getProperty("datahub.test.postgres.password","");}
}
