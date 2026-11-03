package cn.iocoder.yudao.framework.dbswitch.dbwriter.olap;

import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.PartialBatchWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.starrocks.StarrocksWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.starrocks.StarrocksDatabaseSyncImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
@EnabledIfSystemProperty(named="datahub.test.starrocks.url",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
class StarrocksMutationConfirmationStarrocksTest {
    private final List<String> fields=List.of("id","note","event_time","amount","payload");
    private final LocalDateTime time=LocalDateTime.of(2026,10,3,1,2,3,123456000);
    private DataSource dataSource;private Connection control;private String schema;
    @BeforeEach void setup()throws Exception {
        dataSource=new DriverManagerDataSource(System.getProperty("datahub.test.starrocks.url"),"root","");control=dataSource.getConnection();
        assertTrue(single("SELECT current_version()").startsWith("3.5.21"));schema="dbswitch_srconfirm_"+UUID.randomUUID().toString().replace("-","");
        sql("CREATE DATABASE "+schema);create("rows");
    }
    @AfterEach void cleanup()throws Exception{if(control!=null)try(var owned=control){if(schema!=null)sql("DROP DATABASE "+schema+" FORCE");}}

    @Test void invalidLaterRowNeverWritesTheValidPrefix()throws Exception {
        var writer=writer();writer.prepareWrite(schema,"rows",fields);
        for(Object[] invalid:List.of(row(new BigDecimal("2.5"),"ok",time),row(2L,"中文",time),row(2L,"ok",time.withNano(123456789)),
                row(2L,"ok",time.atOffset(ZoneOffset.UTC)),row(BigInteger.ONE.shiftLeft(63),"ok",time))) {
            var first=row(1L,"ok",time);var rows=List.of(first,invalid);
            assertThrows(IllegalArgumentException.class,()->writer.write(fields,rows));
            assertEquals("0",single("SELECT COUNT(*) FROM "+table("rows")));assertEquals(1L,first[0]);
        }
        assertEquals(1,writer.write(fields,List.<Object[]>of(row(Long.MAX_VALUE,"😀",time))));
        assertEquals(Long.toString(Long.MAX_VALUE),single("SELECT id FROM "+table("rows")));
        try(var statement=control.createStatement();var result=statement.executeQuery("SELECT event_time,amount,payload FROM "+table("rows"))){assertTrue(result.next());assertEquals(time,result.getTimestamp(1).toLocalDateTime());assertEquals(new BigDecimal("123.12345"),result.getBigDecimal(2));assertArrayEquals(new byte[]{0,(byte)255},result.getBytes(3));}
    }

    @Test void failedReprepareCannotReuseTheOldWriterTarget()throws Exception {
        create("other");var writer=writer();writer.prepareWrite(schema,"rows",fields);
        assertThrows(IllegalStateException.class,()->writer.prepareWrite(schema,"other",List.of("note")));
        assertThrows(IllegalStateException.class,()->writer.write(fields,List.<Object[]>of(row(1L,"old",time))));
        assertEquals("0",single("SELECT COUNT(*) FROM "+table("rows")));assertEquals("0",single("SELECT COUNT(*) FROM "+table("other")));
    }

    @Test void updateAndDeleteAreConfirmedByNativeVisibleValuesAndWholeBatchKeyPreflight()throws Exception {
        seed();var sync=sync(dataSource);
        assertEquals(1,sync.executeUpdate(List.<Object[]>of(row(1L,"edit",time))));
        assertEquals("edit",single("SELECT note FROM "+table("rows")+" WHERE id=1"));
        assertThrows(IllegalStateException.class,()->sync.executeDelete(List.of(row(1L,"edit",time),row(99L,"none",time))));
        assertEquals("2",single("SELECT COUNT(*) FROM "+table("rows")));
        assertEquals(1,sync.executeDelete(List.<Object[]>of(row(1L,"edit",time))));
        assertEquals("0",single("SELECT COUNT(*) FROM "+table("rows")+" WHERE id=1"));
    }

    @Test void microsecondTimestampKeyAndLargeintValuesUseTheirNativeDomains()throws Exception {
        sql("CREATE TABLE "+table("timed")+"(event_time DATETIME NOT NULL,wide LARGEINT,note VARCHAR(4)) PRIMARY KEY(event_time) DISTRIBUTED BY HASH(event_time) BUCKETS 1 PROPERTIES('replication_num'='1')");
        var selected=List.of("event_time","wide","note");var writer=writer();writer.prepareWrite(schema,"timed",selected);
        BigInteger max=BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE),min=BigInteger.ONE.shiftLeft(127).negate();
        assertEquals(1,writer.write(selected,List.<Object[]>of(new Object[]{Timestamp.valueOf(time),max,"old"})));
        assertEquals(max.toString(),single("SELECT wide FROM "+table("timed")));
        var sync=new StarrocksDatabaseSyncImpl(dataSource);sync.prepare(schema,"timed",selected,List.of("event_time"));
        assertEquals(1,sync.executeUpdate(List.<Object[]>of(new Object[]{time,min,"new"})));
        assertEquals(min.toString(),single("SELECT wide FROM "+table("timed")));
        assertThrows(IllegalArgumentException.class,()->sync.executeUpdate(List.<Object[]>of(new Object[]{time.withNano(123456789),min,"bad"})));
        assertEquals("new",single("SELECT note FROM "+table("timed")));
        assertEquals(1,sync.executeDelete(List.<Object[]>of(new Object[]{time,min,"new"})));
        assertEquals("0",single("SELECT COUNT(*) FROM "+table("timed")));
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void secondMutationWithoutAVisibleReceiptPreservesPrefixAndMarksOnlyCurrentUnknown(boolean lostRead)throws Exception {
        seed();var attempts=new AtomicInteger();var wrapped=new AbstractDataSource(){
            @Override public Connection getConnection()throws SQLException{return wrap(dataSource.getConnection(),attempts,lostRead);}
            @Override public Connection getConnection(String user,String password)throws SQLException{return getConnection();}
        };
        var sync=sync(wrapped);
        var failure=assertThrows(PartialBatchWriteException.class,()->sync.executeUpdate(List.of(row(1L,"one",time),row(2L,"two",time))));
        assertEquals(1,failure.acknowledgedRows());assertEquals(1,failure.unknownRows());assertEquals(0,failure.rejectedRows());assertFalse(failure.terminationConfirmed());
        assertEquals(2,attempts.get());assertEquals("one",single("SELECT note FROM "+table("rows")+" WHERE id=1"));
        assertEquals(lostRead?"two":"old",single("SELECT note FROM "+table("rows")+" WHERE id=2"));
    }

    private Connection wrap(Connection real,AtomicInteger attempts,boolean lostRead) {
        return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            if(method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
                if(lostRead&&attempts.get()==2&&sql.startsWith("SELECT ")&&sql.contains(" WHERE `id`=? LIMIT 2"))throw new SQLException("synthetic lost readback","08006");
                PreparedStatement statement=(PreparedStatement)invoke(real,method,args);
                if(sql.startsWith("UPDATE "))return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
                    if(m.getName().equals("executeUpdate")) {int number=attempts.incrementAndGet();if(!lostRead&&number==2)return 0;}
                    return invoke(statement,m,a);
                });
                return statement;
            }
            return invoke(real,method,args);
        });
    }
    private static Object invoke(Object target,Method method,Object[] args)throws Throwable{try{return method.invoke(target,args);}catch(InvocationTargetException error){throw error.getCause();}}
    private StarrocksWriterImpl writer(){var writer=new StarrocksWriterImpl(dataSource);writer.configure(new DbSwitchOptions(128L*1024*1024,true,false,30,0,300000,30000,0));return writer;}
    private StarrocksDatabaseSyncImpl sync(DataSource source){var sync=new StarrocksDatabaseSyncImpl(source);sync.prepare(schema,"rows",fields,List.of("id"));return sync;}
    private Object[] row(Object id,String note,Object eventTime){return new Object[]{id,note,eventTime,new BigDecimal("123.12345"),new byte[]{0,(byte)255}};}
    private void seed(){var writer=writer();writer.prepareWrite(schema,"rows",fields);assertEquals(2,writer.write(fields,List.of(row(1L,"old",time),row(2L,"old",time))));}
    private void create(String name)throws SQLException{sql("CREATE TABLE "+table(name)+"(id BIGINT NOT NULL,note VARCHAR(4),event_time DATETIME,amount DECIMAL(20,5),payload VARBINARY) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')");}
    private String table(String name){return '`'+schema+"`.`"+name+'`';}
    private void sql(String text)throws SQLException{try(var statement=control.createStatement()){statement.setQueryTimeout(30);statement.execute(text);}}
    private String single(String sql)throws SQLException{try(var statement=control.createStatement();var rows=statement.executeQuery(sql)){assertTrue(rows.next());String value=rows.getString(1);assertFalse(rows.next());return value;}}
}
