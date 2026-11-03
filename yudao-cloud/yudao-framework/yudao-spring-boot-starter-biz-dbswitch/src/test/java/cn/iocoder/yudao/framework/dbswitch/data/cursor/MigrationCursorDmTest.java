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
@EnabledIfSystemProperty(named="datahub.test.dm.url",matches="jdbc:dm://127[.]0[.]0[.]1:[0-9]+")
@EnabledIfSystemProperty(named="datahub.test.dm.driver",matches=".+")
@EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
@EnabledIfEnvironmentVariable(named="DATAHUB_DM_TEST_PASSWORD",matches=".+")
class MigrationCursorDmTest {
    private ConfiguredJdbcDrivers drivers;private DbSwitchRuntime runtime;private String schema;private Connection dm,pg;
    private final List<String> fields=List.of("WM","K1","K2","V");private final List<String> keys=List.of("K2","K1");
    private final BatchLimits limits=new BatchLimits(2,65536,16384,65535,1000);private final AtomicReference<CursorCheckpoint> saved=new AtomicReference<>();
    private SourceGenerationPin pin;
    @BeforeEach void setup()throws Exception {
        Path jar=Path.of(System.getProperty("datahub.test.dm.driver")).toRealPath();var spec=new DbSwitchProperties.Driver();spec.setPath(jar.toString());spec.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        drivers=new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.DM,spec));runtime=new DbSwitchRuntime(DbSwitchProductRegistry.discover(),DbSwitchOptions.defaults(),drivers);
        dm=open(ProductTypeEnum.DM);pg=open(ProductTypeEnum.POSTGRESQL);schema="DM_CURSOR_"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
        sql(dm,"CREATE SCHEMA "+q(schema));sql(pg,"CREATE SCHEMA "+q(schema));pin=new SourceGenerationPin(schema,UUID.randomUUID().toString(),UUID.randomUUID().toString());
    }
    @AfterEach void cleanup()throws Exception {try{if(pg!=null)try(var c=pg){sql(c,"DROP SCHEMA "+q(schema)+" CASCADE");}}finally{try{if(dm!=null)try(var c=dm){sql(c,"DROP SCHEMA "+q(schema)+" CASCADE");}}finally{if(runtime!=null)runtime.close();if(drivers!=null)drivers.close();}}}
    private Connection open(ProductTypeEnum p)throws SQLException{return runtime.openConnection(p,null,url(p),user(p),password(p));}
    private DataSource ds(ProductTypeEnum p){return new AbstractDataSource(){public Connection getConnection()throws SQLException{return open(p);}public Connection getConnection(String u,String pw)throws SQLException{throw new SQLException("Explicit fixture principal required");}};}
    private Connection control(ProductTypeEnum p){return p==ProductTypeEnum.DM?dm:pg;}
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
    @ParameterizedTest @CsvSource({"DM,DM","DM,POSTGRESQL","POSTGRESQL,DM"})
    void publicServiceUsesNativeCursorUpsertAndRewindsEqualWatermark(ProductTypeEnum source,ProductTypeEnum target)throws Exception {
        create(source,"S");create(target,"T");rows(source,5);var request=request(source,target);
        var first=new MigrationService(request,ignored->{},null,runtime).run();assertTrue(first.getIfAllSuccess().get(),()->first.getTableResultList().toString());assertEquals(5,count(target,"T"));assertTrue(saved.get().complete());
        assertEquals(target==ProductTypeEnum.DM?CommitAcknowledgement.JDBC_COMMIT_RETURNED:CommitAcknowledgement.POSTGRESQL_XID8_VERIFIED,saved.get().lastCommitAcknowledgement());
        sql(control(source),"INSERT INTO "+t("S")+" VALUES(10,0,0,'late equal watermark')");request.getTarget().setCursorRunBindings(Map.of("S",binding(this::save)));
        assertTrue(new MigrationService(request,ignored->{},null,runtime).run().getIfAllSuccess().get());assertEquals(6,count(target,"T"));
    }
    @Test void commitBeforeCheckpointCrashReplaysFrozenWindowThenAdvances()throws Exception {
        create(ProductTypeEnum.DM,"S");create(ProductTypeEnum.DM,"T");rows(ProductTypeEnum.DM,5);var calls=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->transfer(ProductTypeEnum.DM,ProductTypeEnum.DM,ds(ProductTypeEnum.DM),next->calls.incrementAndGet()!=3&&save(next)));
        assertEquals(4,count(ProductTypeEnum.DM,"T"));assertEquals(1,saved.get().sequence());assertFalse(saved.get().complete());assertNull(saved.get().lastCommit());
        sql(dm,"INSERT INTO "+t("S")+" VALUES(10,0,0,'late'),(11,99,99,'next window')");transfer(ProductTypeEnum.DM,ProductTypeEnum.DM,ds(ProductTypeEnum.DM),this::save);assertEquals(6,count(ProductTypeEnum.DM,"T"));
        transfer(ProductTypeEnum.DM,ProductTypeEnum.DM,ds(ProductTypeEnum.DM),this::save);assertEquals(7,count(ProductTypeEnum.DM,"T"));
    }
    @Test void actualCommitWithLostAckNeverAdvancesCheckpoint()throws Exception {
        create(ProductTypeEnum.DM,"S");create(ProductTypeEnum.DM,"T");rows(ProductTypeEnum.DM,2);var fault=new AbstractDataSource(){public Connection getConnection()throws SQLException{Connection c=open(ProductTypeEnum.DM);return(Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{try{Object v=m.invoke(c,a);if(m.getName().equals("commit"))throw new SQLException("fixture lost acknowledgement");return v;}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});}public Connection getConnection(String u,String p)throws SQLException{return getConnection();}};
        assertThrows(UnconfirmedWriteException.class,()->transfer(ProductTypeEnum.DM,ProductTypeEnum.DM,fault,this::save));assertEquals(2,count(ProductTypeEnum.DM,"T"));assertNull(saved.get().applied());assertEquals(0,saved.get().sequence());
        transfer(ProductTypeEnum.DM,ProductTypeEnum.DM,ds(ProductTypeEnum.DM),this::save);assertTrue(saved.get().complete());assertEquals(2,count(ProductTypeEnum.DM,"T"));
    }
    @Test void serializableSnapshotAllowsDmlButRowSharePreventsTruncate()throws Exception {
        create(ProductTypeEnum.DM,"S");rows(ProductTypeEnum.DM,3);var reader=runtime.reader(ds(ProductTypeEnum.DM),ProductTypeEnum.DM);reader.setFetchSize(1);
        try(var cursor=reader.openCursor(new CursorReadRequest(schema,"S",fields,"WM",keys,null,null,null,limits,false,pin))) {
            assertTrue(cursor.next());sql(dm,"UPDATE "+t("S")+" SET \"V\"='changed' WHERE \"K1\"=3");sql(dm,"INSERT INTO "+t("S")+" VALUES(11,9,9,'after snapshot')");
            try(var s=dm.createStatement()){s.setQueryTimeout(1);assertThrows(SQLException.class,()->s.execute("TRUNCATE TABLE "+t("S")));}
            int read=1;while(cursor.next()){assertEquals("original",cursor.row()[3]);read++;}assertEquals(3,read);
        }
        sql(dm,"TRUNCATE TABLE "+t("S"));assertEquals(0,count(ProductTypeEnum.DM,"S"));
    }
    @ParameterizedTest @ValueSource(strings={"TARGET_RECREATE","SOURCE_TRUNCATE","DEPLOYED_GENERATION"})
    void resumeRejectsChangedPhysicalOrDeploymentGenerationBeforeWrites(String mode)throws Exception {
        create(ProductTypeEnum.DM,"S");create(ProductTypeEnum.DM,"T");rows(ProductTypeEnum.DM,1);transfer(ProductTypeEnum.DM,ProductTypeEnum.DM,ds(ProductTypeEnum.DM),this::save);
        if(mode.equals("TARGET_RECREATE")){sql(dm,"DROP TABLE "+t("T"));create(ProductTypeEnum.DM,"T");}
        else if(mode.equals("SOURCE_TRUNCATE")){sql(dm,"TRUNCATE TABLE "+t("S"));rows(ProductTypeEnum.DM,1);}
        else pin=new SourceGenerationPin(schema,pin.instanceId(),UUID.randomUUID().toString());
        long before=count(ProductTypeEnum.DM,"T");assertThrows(IllegalArgumentException.class,()->transfer(ProductTypeEnum.DM,ProductTypeEnum.DM,ds(ProductTypeEnum.DM),this::save));assertEquals(before,count(ProductTypeEnum.DM,"T"));
    }
    @Test void directlyBoundTimestamp9AndDecimal38SurviveInsertUpdateReplay()throws Exception {
        sql(dm,"CREATE TABLE "+t("T")+"(\"K\" DECIMAL(38,0) PRIMARY KEY,\"TS\" TIMESTAMP(9),\"N\" DECIMAL(38,18),\"B\" VARBINARY(20))");var writer=runtime.upsertWriter(ds(ProductTypeEnum.DM),ProductTypeEnum.DM);var names=List.of("K","TS","N","B");writer.prepareUpsert(schema,"T",names,List.of("K"));
        BigDecimal key=new BigDecimal("99999999999999999999999999999999999999");Timestamp ts=Timestamp.valueOf("2026-01-02 03:04:05.123456789");
        for(int i=0;i<2;i++){var receipt=writer.writeWithReceipt(names,Collections.singletonList(new Object[]{key,ts,new BigDecimal("-1E-18"),new byte[]{0,(byte)255}}));assertEquals(1,receipt.acknowledgedRows());assertEquals(CommitAcknowledgement.JDBC_COMMIT_RETURNED,receipt.commitAcknowledgement());}
        try(var s=dm.createStatement();var r=s.executeQuery("SELECT * FROM "+t("T"))){assertTrue(r.next());assertEquals(key,r.getBigDecimal(1));assertEquals(ts,r.getTimestamp(2));assertEquals(new BigDecimal("-1E-18"),r.getBigDecimal(3));assertArrayEquals(new byte[]{0,(byte)255},r.getBytes(4));assertFalse(r.next());}
    }
    @Test void publicDmCursorPreservesTimestamp9AndBinaryPayload()throws Exception {
        for(String table:List.of("S","T"))sql(dm,"CREATE TABLE "+t(table)+"(\"WM\" DECIMAL(38,18) NOT NULL,\"K1\" BIGINT NOT NULL,\"K2\" DECIMAL(38,0) NOT NULL,\"V\" VARCHAR(80),\"TS\" TIMESTAMP(9),\"B\" VARBINARY(20),PRIMARY KEY(\"K2\",\"K1\"))");
        var timestamp=Timestamp.valueOf("2026-01-02 03:04:05.123456789");try(var p=dm.prepareStatement("INSERT INTO "+t("S")+" VALUES(?,?,?,?,?,?)")){p.setBigDecimal(1,new BigDecimal("-1E-18"));p.setLong(2,Long.MIN_VALUE);p.setBigDecimal(3,new BigDecimal("99999999999999999999999999999999999999"));p.setString(4,"汉字🙂");p.setTimestamp(5,timestamp);p.setBytes(6,new byte[]{0,(byte)255});p.executeUpdate();}
        assertTrue(new MigrationService(request(ProductTypeEnum.DM,ProductTypeEnum.DM),ignored->{},null,runtime).run().getIfAllSuccess().get());
        try(var s=dm.createStatement();var r=s.executeQuery("SELECT \"WM\",\"K1\",\"K2\",\"V\",\"TS\",\"B\" FROM "+t("T"))){assertTrue(r.next());assertEquals(new BigDecimal("-1E-18"),r.getBigDecimal(1));assertEquals(Long.MIN_VALUE,r.getLong(2));assertEquals(new BigDecimal("99999999999999999999999999999999999999"),r.getBigDecimal(3));assertEquals("汉字🙂",r.getString(4));assertEquals(timestamp,r.getTimestamp(5));assertArrayEquals(new byte[]{0,(byte)255},r.getBytes(6));}
    }
    @Test void frozenEmptyWindowRemainsEmptyUntilNextCompletedRun()throws Exception {
        create(ProductTypeEnum.DM,"S");create(ProductTypeEnum.DM,"T");var calls=new AtomicInteger();assertThrows(IllegalStateException.class,()->transfer(ProductTypeEnum.DM,ProductTypeEnum.DM,ds(ProductTypeEnum.DM),next->calls.incrementAndGet()==1&&save(next)));
        assertNull(saved.get().fixedUpper());assertFalse(saved.get().complete());rows(ProductTypeEnum.DM,1);transfer(ProductTypeEnum.DM,ProductTypeEnum.DM,ds(ProductTypeEnum.DM),this::save);assertEquals(0,count(ProductTypeEnum.DM,"T"));
        transfer(ProductTypeEnum.DM,ProductTypeEnum.DM,ds(ProductTypeEnum.DM),this::save);assertEquals(1,count(ProductTypeEnum.DM,"T"));
    }
    @Test void absentGenerationOrWrongKeyOrderFailsBeforeReading()throws Exception {
        create(ProductTypeEnum.DM,"S");rows(ProductTypeEnum.DM,1);var reader=runtime.reader(ds(ProductTypeEnum.DM),ProductTypeEnum.DM);
        assertThrows(IllegalArgumentException.class,()->reader.openCursor(new CursorReadRequest(schema,"S",fields,"WM",keys,null,null,null,limits)));
        assertThrows(IllegalArgumentException.class,()->reader.openCursor(new CursorReadRequest(schema,"S",fields,"WM",List.of("K1","K2"),null,null,null,limits,false,pin)));
        assertEquals(1,count(ProductTypeEnum.DM,"S"));
    }
    @Test void lexicographicPagesKeepNegativeDecimal38AndSignedIntegerBoundaries()throws Exception {
        create(ProductTypeEnum.DM,"S");String max="99999999999999999999999999999999999999";
        sql(dm,"INSERT INTO "+t("S")+" VALUES(-0.000000000000000001,-9223372036854775808,-"+max+",'first'),(-0.000000000000000001,9223372036854775807,-"+max+",'second'),(-0.000000000000000001,0,"+max+",'third'),(0,-1,-"+max+",'fourth')");
        var reader=runtime.reader(ds(ProductTypeEnum.DM),ProductTypeEnum.DM);reader.setFetchSize(1);var seen=new ArrayList<String>();
        try(var cursor=reader.openCursor(new CursorReadRequest(schema,"S",fields,"WM",keys,null,null,null,limits,false,pin))){while(cursor.next())seen.add((String)cursor.row()[3]);}
        assertEquals(List.of("first","second","third","fourth"),seen);
    }
    @Test void keysOnlyMergeAcknowledgesReplayWithoutDuplicatingRows()throws Exception {
        sql(dm,"CREATE TABLE "+t("T")+"(\"K\" BIGINT PRIMARY KEY)");var writer=runtime.upsertWriter(ds(ProductTypeEnum.DM),ProductTypeEnum.DM);writer.prepareUpsert(schema,"T",List.of("K"),List.of("K"));
        for(int i=0;i<2;i++)assertEquals(1,writer.writeWithReceipt(List.of("K"),Collections.singletonList(new Object[]{Long.MAX_VALUE})).acknowledgedRows());assertEquals(1,count(ProductTypeEnum.DM,"T"));
    }
    @Test void batchConstraintFailureRollsBackAllRowsAndNeverAcknowledges()throws Exception {
        sql(dm,"CREATE TABLE "+t("T")+"(\"K\" BIGINT PRIMARY KEY,\"V\" INT CHECK(\"V\">0))");var writer=runtime.upsertWriter(ds(ProductTypeEnum.DM),ProductTypeEnum.DM);writer.prepareUpsert(schema,"T",List.of("K","V"),List.of("K"));
        assertThrows(UnconfirmedWriteException.class,()->writer.writeWithReceipt(List.of("K","V"),List.of(new Object[]{1L,1},new Object[]{2L,-1})));assertEquals(0,count(ProductTypeEnum.DM,"T"));
    }
    @Test void invalidFractionalIntegerAndIdentityReprepareNeverWrite()throws Exception {
        sql(dm,"CREATE TABLE "+t("T")+"(\"K\" BIGINT PRIMARY KEY,\"V\" INT)");sql(dm,"CREATE TABLE "+t("I")+"(\"K\" BIGINT IDENTITY(7,3) PRIMARY KEY,\"V\" INT)");var writer=runtime.upsertWriter(ds(ProductTypeEnum.DM),ProductTypeEnum.DM);writer.prepareUpsert(schema,"T",List.of("K","V"),List.of("K"));
        assertThrows(RuntimeException.class,()->writer.writeWithReceipt(List.of("K","V"),Collections.singletonList(new Object[]{1L,new BigDecimal("1.5")})));assertEquals(0,count(ProductTypeEnum.DM,"T"));
        assertThrows(IllegalArgumentException.class,()->writer.prepareUpsert(schema,"I",List.of("K","V"),List.of("K")));assertThrows(RuntimeException.class,()->writer.write(List.of("K","V"),Collections.singletonList(new Object[]{1L,1})));assertEquals(0,count(ProductTypeEnum.DM,"T"));assertEquals(0,count(ProductTypeEnum.DM,"I"));
    }
    private String t(String table){return q(schema)+'.'+q(table);}private static String q(String n){return DmSqlNames.quote(n);}private static void sql(Connection c,String sql)throws SQLException{try(var s=c.createStatement()){s.setQueryTimeout(15);s.execute(sql);}}
    private static String url(ProductTypeEnum p){return System.getProperty(p==ProductTypeEnum.DM?"datahub.test.dm.url":"datahub.test.postgres.url");}
    private static String user(ProductTypeEnum p){return p==ProductTypeEnum.DM?"SYSDBA":System.getProperty("datahub.test.postgres.user","dataforge_test");}
    private static String password(ProductTypeEnum p){return p==ProductTypeEnum.DM?System.getenv("DATAHUB_DM_TEST_PASSWORD"):System.getProperty("datahub.test.postgres.password","");}
}
