package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.pg.PostgresUpsertWriter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** PostgreSQL time(p) columns must keep their native microseconds through typed cursor reads and UPSERT binding. */
@EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
class PostgresCursorTimeTest {
    private final String binding="c".repeat(64);
    private final BatchLimits limits=new BatchLimits(2,65536,16384,65535,1000);
    private final List<String> fields=List.of("stamp","id","clock");
    private final List<String> keys=List.of("id");
    private DriverManagerDataSource source;
    private DbSwitchRuntime runtime;
    private Connection control;
    private String schema;
    @BeforeEach void setup() throws Exception {
        source=new DriverManagerDataSource(System.getProperty("datahub.test.postgres.url"),
                System.getProperty("datahub.test.postgres.user","dataforge_test"),System.getProperty("datahub.test.postgres.password",""));
        control=source.getConnection(); runtime=DbSwitchRuntime.standalone();
        assertEquals(16,control.getMetaData().getDatabaseMajorVersion());
        schema="cursortime_"+UUID.randomUUID().toString().replace("-",""); sql("CREATE SCHEMA "+schema);
        sql("CREATE TABLE "+schema+".source(stamp numeric(38,8) NOT NULL,id bigint NOT NULL,clock time(6),PRIMARY KEY(id))");
        sql("CREATE TABLE "+schema+".target(LIKE "+schema+".source INCLUDING ALL)");
    }
    @AfterEach void cleanup() throws Exception {
        if(runtime!=null)runtime.close();
        if(control!=null)try{sql("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}finally{control.close();}
    }
    private void sql(String sql) throws Exception {try(var s=control.createStatement()){s.execute(sql);}}
    private long count(String table) throws Exception {try(var s=control.createStatement();var r=s.executeQuery("SELECT count(*) FROM "+schema+"."+table)){r.next();return r.getLong(1);}}
    private void row(String stamp,long id,String clock) throws Exception {
        try(var p=control.prepareStatement("INSERT INTO "+schema+".source VALUES(?,?,?::time)")){
            p.setBigDecimal(1,new BigDecimal(stamp));p.setLong(2,id);p.setString(3,clock);p.executeUpdate();
        }
    }
    private List<String> clock(String table) throws Exception {
        var values=new ArrayList<String>();
        try(var s=control.createStatement();var r=s.executeQuery("SELECT to_char(clock,'HH24:MI:SS.US') FROM "+schema+"."+table+" ORDER BY id")){
            while(r.next())values.add(r.getString(1));
        }
        return values;
    }
    private DbSwitchTableResult transfer(CursorCheckpoint previous,CursorRunBinding.CheckpointStore store,
            List<String> names,List<String> keys) throws Exception {
        var result=new DbSwitchTableResult();
        CursorTransfer.execute(runtime,source,source,schema,"source",schema,"target",names,names,keys,keys,
                "stamp",limits,2,new CursorRunBinding(binding,previous,store),result,()->{});
        return result;
    }
    @Test void timeFieldKeepsNativeMicrosecondsInCursorRows() throws Exception {
        row("10",1,"12:34:56.123456");
        var request=new CursorReadRequest(schema,"source",fields,"stamp",keys,null,null,null,limits);
        try(var cursor=runtime.reader(source,ProductTypeEnum.POSTGRESQL).openCursor(request)){
            assertTrue(cursor.next());
            assertEquals(LocalTime.parse("12:34:56.123456"),cursor.row()[2]);
            assertInstanceOf(LocalTime.class,cursor.row()[2]);
            assertFalse(cursor.next());
        }
    }
    @Test void transferPreservesMicrosecondTimeAcrossCheckpointReplay() throws Exception {
        row("10",1,"12:34:56.123456");row("10",2,"00:00:00.000001");row("11",3,"23:59:59.999999");
        var saved=new AtomicReference<CursorCheckpoint>();
        var result=transfer(null,next->{saved.set(next);return true;},fields,keys);
        assertEquals(3,count("target"));assertEquals(3,result.getSyncCount().get());
        assertEquals(List.of("12:34:56.123456","00:00:00.000001","23:59:59.999999"),clock("target"));
        assertTrue(saved.get().complete());
        row("12",4,"01:02:03.004005");
        transfer(saved.get(),next->{saved.set(next);return true;},fields,keys);
        assertEquals(4,count("target"));
        assertEquals(List.of("12:34:56.123456","00:00:00.000001","23:59:59.999999","01:02:03.004005"),clock("target"));
    }
    @Test void timePrimaryKeyPartRoundTripsThroughCheckpointAndUpperBound() throws Exception {
        sql("CREATE TABLE "+schema+".keys(stamp numeric(38,8) NOT NULL,id bigint NOT NULL,clock time(6) NOT NULL,PRIMARY KEY(id,clock))");
        sql("CREATE TABLE "+schema+".keystarget(LIKE "+schema+".keys INCLUDING ALL)");
        sql("INSERT INTO "+schema+".keys VALUES(10,1,'10:00:00.000001'),(10,1,'10:00:00.000002')");
        var saved=new AtomicReference<CursorCheckpoint>();
        var result=new DbSwitchTableResult();
        CursorTransfer.execute(runtime,source,source,schema,"keys",schema,"keystarget",fields,fields,
                List.of("id","clock"),List.of("id","clock"),"stamp",limits,2,
                new CursorRunBinding(binding,null,next->{saved.set(next);return true;}),result,()->{});
        assertEquals(2,result.getSyncCount().get());
        assertEquals("10:00:00.000002",saved.get().applied().values().get(2).value());
        assertTrue(saved.get().complete());
        sql("INSERT INTO "+schema+".keys VALUES(10,1,'10:00:00.000003')");
        var result2=new DbSwitchTableResult();
        CursorTransfer.execute(runtime,source,source,schema,"keys",schema,"keystarget",fields,fields,
                List.of("id","clock"),List.of("id","clock"),"stamp",limits,2,
                new CursorRunBinding(binding,saved.get(),next->{saved.set(next);return true;}),result2,()->{});
        // Recovery re-reads the equal-watermark group; UPSERT is idempotent, so only the new key lands.
        assertEquals(3,result2.getSyncCount().get());
        assertEquals(List.of("10:00:00.000001","10:00:00.000002","10:00:00.000003"),clock("keystarget"));
    }
    @Test void twentyFourHourTimeIsRefusedInsteadOfCorrupted() throws Exception {
        row("10",1,"24:00:00");
        var saved=new AtomicReference<CursorCheckpoint>();
        var failure=assertThrows(SQLException.class,()->transfer(null,next->{saved.set(next);return true;},fields,keys));
        assertTrue(failure.getMessage().contains("24:00"),failure.getMessage());
        assertEquals(0,count("target"));
    }
    @Test void upsertTargetRefusesUnprovableTimeCarriersBeforeAnyWrite() throws Exception {
        var writer=new PostgresUpsertWriter(source); writer.configureBatchLimits(limits);
        writer.prepareUpsert(schema,"target",fields,keys);
        // Millisecond java.sql.Time cannot prove an exact microsecond TIME.
        assertThrows(IllegalStateException.class,()->writer.writeWithReceipt(fields,
                List.<Object[]>of(new Object[]{new BigDecimal("10"),1L,java.sql.Time.valueOf("12:34:56")})));
        assertEquals(0,count("target"));
        // Durations are outside the PostgreSQL time domain.
        assertThrows(IllegalStateException.class,()->writer.writeWithReceipt(fields,
                List.<Object[]>of(new Object[]{new BigDecimal("10"),1L,Duration.ofSeconds(59)})));
        assertEquals(0,count("target"));
        // pgjdbc would silently round sub-microsecond nanos; such a value cannot be proven exact.
        assertThrows(IllegalStateException.class,()->writer.writeWithReceipt(fields,
                List.<Object[]>of(new Object[]{new BigDecimal("10"),1L,LocalTime.of(1,2,3,123456789)})));
        assertEquals(0,count("target"));
        // A microsecond-exact LocalTime is bound losslessly.
        writer.writeWithReceipt(fields,List.<Object[]>of(new Object[]{new BigDecimal("10"),1L,LocalTime.parse("12:34:56.123456")}));
        assertEquals(1,count("target"));assertEquals(List.of("12:34:56.123456"),clock("target"));
    }
}
