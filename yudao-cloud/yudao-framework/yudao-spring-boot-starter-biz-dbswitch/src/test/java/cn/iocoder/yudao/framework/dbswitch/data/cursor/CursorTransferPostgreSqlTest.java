package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
class CursorTransferPostgreSqlTest {
    private final String binding="a".repeat(64);
    private final BatchLimits limits=new BatchLimits(2,65536,16384,65535,1000);
    private final List<String> fields=List.of("stamp","key_text","key_num","value");
    private final List<String> keys=List.of("key_text","key_num");
    private DriverManagerDataSource source;
    private DbSwitchRuntime runtime;
    private Connection control;
    private String schema;
    @BeforeEach void setup() throws Exception {
        source=new DriverManagerDataSource(System.getProperty("datahub.test.postgres.url"),
                System.getProperty("datahub.test.postgres.user","dataforge_test"),System.getProperty("datahub.test.postgres.password",""));
        control=source.getConnection(); runtime=DbSwitchRuntime.standalone();
        assertEquals(16,control.getMetaData().getDatabaseMajorVersion());
        schema="cursor_"+UUID.randomUUID().toString().replace("-",""); sql("CREATE SCHEMA "+schema);
        sql("CREATE TABLE "+schema+".source(stamp numeric(38,8) NOT NULL,key_text text NOT NULL,key_num bigint NOT NULL,value text,PRIMARY KEY(key_text,key_num))");
        sql("CREATE TABLE "+schema+".target(LIKE "+schema+".source INCLUDING ALL)");
    }
    @AfterEach void cleanup() throws Exception {
        if(runtime!=null)runtime.close();
        if(control!=null)try{sql("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}finally{control.close();}
    }
    private void sql(String sql) throws Exception {try(var s=control.createStatement()){s.execute(sql);}}
    private long count(String table) throws Exception {try(var s=control.createStatement();var r=s.executeQuery("SELECT count(*) FROM "+schema+"."+table)){r.next();return r.getLong(1);}}
    private void row(String stamp,String key,long number,String value) throws Exception {
        try(var p=control.prepareStatement("INSERT INTO "+schema+".source VALUES(?,?,?,?)")){
            p.setBigDecimal(1,new BigDecimal(stamp));p.setString(2,key);p.setLong(3,number);p.setString(4,value);p.executeUpdate();
        }
    }
    private DbSwitchTableResult transfer(CursorCheckpoint previous,CursorRunBinding.CheckpointStore store) throws Exception {
        var result=new DbSwitchTableResult();
        CursorTransfer.execute(runtime,source,source,schema,"source",schema,"target",fields,fields,keys,keys,
                "stamp",limits,2,new CursorRunBinding(binding,previous,store),result,()->{});
        return result;
    }
    @Test void compositeCursorPreservesDecimalDelimiterUnicodeAndCommitsBeforeCheckpoint() throws Exception {
        String exact="123456789012345678901234567890.12345678";
        row(exact,"中,文'",Long.MAX_VALUE,"甲");row(exact,"a,b",2,"乙");row(exact,"a",3,null);
        var saved=new AtomicReference<CursorCheckpoint>();
        var result=transfer(null,next->{
            if(next.applied()!=null){assertNotNull(next.lastCommit());assertTrue(next.sequence()>0);}
            saved.set(next);return true;
        });
        assertEquals(3,count("target")); assertEquals(3,result.getSyncCount().get());
        assertEquals(2,result.getBatchProgress().acknowledgedBatches()); assertTrue(result.getBatchProgress().terminated());
        assertEquals(exact,saved.get().applied().values().getFirst().value()); assertTrue(saved.get().complete());
        assertEquals(3,result.getBatchProgress().commitWitnessedInputRows());
    }
    @Test void checkpointFailureAfterCommitReplaysBoundedWindowWithoutLosingEqualWatermarkKeys() throws Exception {
        for(int i=1;i<=5;i++)row("10","k"+i,i,"v"+i);
        var saved=new AtomicReference<CursorCheckpoint>(); var calls=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->transfer(null,next->{
            if(calls.incrementAndGet()==3)return false;
            saved.set(next);return true;
        }));
        assertEquals(4,count("target"));assertEquals(1,saved.get().sequence());assertFalse(saved.get().complete());
        row("10","a,new",99,"late equal watermark");row("11","future",100,"future");
        transfer(saved.get(),next->{saved.set(next);return true;});
        assertEquals(6,count("target"));assertTrue(saved.get().complete());
        transfer(saved.get(),next->{saved.set(next);return true;});
        assertEquals(7,count("target"));assertEquals("11.00000000",saved.get().applied().values().getFirst().value());
    }
    @Test void completedWindowRereadsEqualWatermarkGroupIncludingSmallerNewPrimaryKey() throws Exception {
        row("10","z",1,"first");var saved=new AtomicReference<CursorCheckpoint>();
        transfer(null,next->{saved.set(next);return true;});row("10","a",2,"same group");
        transfer(saved.get(),next->{saved.set(next);return true;});assertEquals(2,count("target"));
    }
    @Test void schemaChangeAndTargetRecreationRejectBeforeAnyNewWrite() throws Exception {
        row("10","a",1,"first");var saved=new AtomicReference<CursorCheckpoint>();
        transfer(null,next->{saved.set(next);return true;});sql("DROP TABLE "+schema+".target");
        sql("CREATE TABLE "+schema+".target(LIKE "+schema+".source INCLUDING ALL)");
        assertThrows(IllegalArgumentException.class,()->transfer(saved.get(),next->true));assertEquals(0,count("target"));
        sql("ALTER TABLE "+schema+".source ALTER COLUMN value TYPE varchar(100)");
        assertThrows(IllegalArgumentException.class,()->transfer(saved.get(),next->true));assertEquals(0,count("target"));
    }
    @Test void backwardsWatermarkAndMissingPrimaryKeyRefuseRecovery() throws Exception {
        row("10","a",1,"first");var saved=new AtomicReference<CursorCheckpoint>();
        transfer(null,next->{saved.set(next);return true;});sql("UPDATE "+schema+".source SET stamp=9");
        assertThrows(IllegalArgumentException.class,()->transfer(saved.get(),next->true));assertEquals(1,count("target"));
        sql("ALTER TABLE "+schema+".source DROP CONSTRAINT source_pkey");
        assertThrows(IllegalArgumentException.class,()->transfer(null,next->true));
    }
    @Test void timestampWithTimeZoneKeepsMicrosecondsAndNativeComparison() throws Exception {
        sql("CREATE TABLE "+schema+".times(id integer PRIMARY KEY,stamp timestamptz NOT NULL)");
        sql("INSERT INTO "+schema+".times VALUES(1,'2026-10-03 01:02:03.123456+08'),(2,'2026-10-03 01:02:03.123457+08')");
        var request=new CursorReadRequest(schema,"times",List.of("id","stamp"),"stamp",List.of("id"),null,null,null,limits);
        try(var cursor=runtime.reader(source,ProductTypeEnum.POSTGRESQL).openCursor(request)){
            assertTrue(cursor.next());assertEquals(java.sql.Types.TIMESTAMP_WITH_TIMEZONE,cursor.position().values().getFirst().jdbcType());
            assertTrue(cursor.position().values().getFirst().value().contains("123456"));
            assertInstanceOf(java.time.OffsetDateTime.class,cursor.row()[1]);assertTrue(cursor.next());assertFalse(cursor.next());
        }
    }
    @Test void emptySourceStillPersistsAnExplicitCompleteWindow() throws Exception {
        var saved=new AtomicReference<CursorCheckpoint>();var result=transfer(null,next->{saved.set(next);return true;});
        assertEquals(0,result.getSyncCount().get());assertNull(saved.get().applied());assertNull(saved.get().fixedUpper());assertTrue(saved.get().complete());
    }
    @Test void v1PostgresCheckpointResumesAndUpgradesWithoutChangingItsIdentities() throws Exception {
        row("10","z",1,"first");var saved=new AtomicReference<CursorCheckpoint>();
        transfer(null,next->{saved.set(next);return true;});var current=saved.get();
        var historical=new CursorCheckpoint(1,current.bindingFingerprint(),current.schemaFingerprint(),
                current.targetSchemaFingerprint(),current.fixedUpper(),current.applied(),current.sequence(),current.complete(),current.lastCommit());
        row("10","a",2,"equal watermark");
        transfer(historical,next->{saved.set(next);return true;});
        assertEquals(2,count("target"));assertEquals(2,saved.get().version());
        assertEquals(historical.bindingFingerprint(),saved.get().bindingFingerprint());
        assertEquals(historical.schemaFingerprint(),saved.get().schemaFingerprint());
        assertEquals(historical.targetSchemaFingerprint(),saved.get().targetSchemaFingerprint());
    }
    @Test void crashAfterFreezingEmptyWindowCannotExpandItsUpperBound() throws Exception {
        var saved=new AtomicReference<CursorCheckpoint>();var calls=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->transfer(null,next->{
            if(calls.incrementAndGet()>1)return false;saved.set(next);return true;
        }));
        row("10","a",1,"after frozen empty window");
        transfer(saved.get(),next->{saved.set(next);return true;});
        assertEquals(0,count("target"));assertTrue(saved.get().complete());
        transfer(saved.get(),next->{saved.set(next);return true;});assertEquals(1,count("target"));
    }
    @Test void civilTimestampNeverPassesThroughJvmTimeZoneOrLosesMicroseconds() throws Exception {
        sql("CREATE TABLE "+schema+".civil(id integer PRIMARY KEY,stamp timestamp NOT NULL)");
        sql("INSERT INTO "+schema+".civil VALUES(1,'2026-03-08 02:30:00.123456')");
        var request=new CursorReadRequest(schema,"civil",List.of("id","stamp"),"stamp",List.of("id"),null,null,null,limits);
        try(var cursor=runtime.reader(source,ProductTypeEnum.POSTGRESQL).openCursor(request)){
            assertTrue(cursor.next());
            assertEquals(java.time.LocalDateTime.parse("2026-03-08T02:30:00.123456"),cursor.row()[1]);
            assertEquals("2026-03-08T02:30:00.123456",cursor.position().values().getFirst().value());
        }
    }
    @Test void incompatibleKeyTypeOrCollationFailsBeforeCheckpointAndTargetWrite() throws Exception {
        row("10","a",1,"one");
        sql("ALTER TABLE "+schema+".target ALTER COLUMN key_num TYPE numeric(20,1)");
        var calls=new AtomicInteger();
        assertThrows(IllegalArgumentException.class,()->transfer(null,next->{calls.incrementAndGet();return true;}));
        assertEquals(0,calls.get());assertEquals(0,count("target"));
        sql("ALTER TABLE "+schema+".target ALTER COLUMN key_num TYPE bigint");
        sql("ALTER TABLE "+schema+".target ALTER COLUMN key_text TYPE text COLLATE \"C\"");
        assertThrows(IllegalArgumentException.class,()->transfer(null,next->{calls.incrementAndGet();return true;}));
        assertEquals(0,calls.get());assertEquals(0,count("target"));
    }
}
