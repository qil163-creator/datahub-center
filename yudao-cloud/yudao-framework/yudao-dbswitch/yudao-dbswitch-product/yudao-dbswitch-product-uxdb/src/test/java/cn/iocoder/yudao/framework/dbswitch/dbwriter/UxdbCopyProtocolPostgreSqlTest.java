package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.uxdb.UxdbCopyWriterImpl;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;

import javax.sql.DataSource;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Real PG text COPY exercises only UXDB's internal reflection/protocol mechanics.
 * The extracted transport mechanics are invoked explicitly; the public UXDB writer remains refused without
 * native UXDB evidence. This fixture cannot certify the UXDB product, driver or runtime admission.
 */
@EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
@Timeout(30)
class UxdbCopyProtocolPostgreSqlTest {
    public interface CopyAccess extends Connection { CopyManager getCopyAPI() throws Exception; }
    HikariDataSource pool;
    Connection control;
    String schema;
    String fault="";
    AtomicInteger copies=new AtomicInteger(),commits=new AtomicInteger(),inserts=new AtomicInteger();
    UxdbCopyWriterImpl writer;
    DataSource facade;

    @BeforeEach void setup() throws Exception {
        String url=System.getProperty("datahub.test.postgres.url");
        assertTrue(url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+"));
        pool=new HikariDataSource();pool.setJdbcUrl(url+"?socketTimeout=5");pool.setUsername(System.getProperty("datahub.test.postgres.user","dataforge_test"));pool.setPassword(System.getProperty("datahub.test.postgres.password",""));
        pool.setMaximumPoolSize(1);pool.setMinimumIdle(0);pool.setConnectionTimeout(3000);
        control=DriverManager.getConnection(url,System.getProperty("datahub.test.postgres.user","dataforge_test"),System.getProperty("datahub.test.postgres.password",""));
        schema="uxprotocol_"+UUID.randomUUID().toString().replace("-","");
        sql("CREATE SCHEMA "+schema);sql("CREATE TABLE "+schema+".rows(id integer)");
        facade=mock(DataSource.class);
        when(facade.getConnection()).thenAnswer(call->{
            var actual=pool.getConnection();var pg=actual.unwrap(PGConnection.class);
            var api=mock(CopyManager.class);
            when(api.copyIn(anyString(),any(InputStream.class),anyInt())).thenAnswer(copy->{
                copies.incrementAndGet();
                if(fault.equals("copy-before"))throw lost();
                if(fault.equals("active-copy")){
                    var operation=pg.getCopyAPI().copyIn(copy.getArgument(0));
                    byte[] prefix="1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    operation.writeToCopy(prefix,0,prefix.length);operation.flushCopy();
                    throw lost(); // 保持活动协议，不能再发 rollback 等待自己；必须终止连接。
                }
                long count=pg.getCopyAPI().copyIn(copy.getArgument(0,String.class),copy.getArgument(1,InputStream.class),copy.getArgument(2,Integer.class));
                if(fault.equals("copy-after"))throw lost();
                return count;
            });
            return Proxy.newProxyInstance(CopyAccess.class.getClassLoader(),new Class<?>[]{CopyAccess.class},(proxy,method,args)->{
                String name=method.getName();
                if(name.equals("getCopyAPI"))return api;
                if(name.equals("prepareStatement") && ((String)args[0]).stripLeading().startsWith("INSERT"))inserts.incrementAndGet();
                if(name.equals("commit")){commits.incrementAndGet();if(fault.equals("commit-before"))throw lost();}
                Object result=invoke(actual,method,args);
                if((name.equals("commit")&&fault.equals("commit-after"))
                        || (name.equals("setAutoCommit")&&Boolean.TRUE.equals(args[0])&&fault.equals("restore-after"))
                        || (name.equals("close")&&fault.equals("close-after")))throw lost();
                return result;
            });
        });
        writer=new UxdbCopyWriterImpl(facade);
    }
    @AfterEach void cleanup() throws Exception {
        if(pool!=null)pool.close();
        if(control!=null)try{
            assertTrue(schema.matches("uxprotocol_[a-f0-9]{32}"));sql("DROP SCHEMA "+schema+" CASCADE");
        }finally{control.close();}
    }
    @Test void exactRowsAreVisibleFromIndependentConnectionAndPoolCanBeReused() throws Exception {
        assertEquals(2,writeProtocol(List.of(new Object[]{1},new Object[]{2})));
        assertEquals(1,writeProtocol(List.<Object[]>of(new Object[]{3})));
        assertEquals(3,count());assertEquals(2,copies.get());assertEquals(2,commits.get());assertEquals(0,inserts.get());
        try(var c=pool.getConnection()){assertTrue(c.getAutoCommit());}
    }
    @Test void pgProtocolFixtureCannotAdmitThePublicUxDbWriter() throws Exception {
        assertThrows(SQLFeatureNotSupportedException.class, writer::preflightRuntimeWrite);
        assertThrows(SQLFeatureNotSupportedException.class,()->writer.prepareWrite(schema,"rows",List.of("id")));
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> writer.write(List.of("id"), List.<Object[]>of(new Object[]{1})));
        assertEquals(0, copies.get()); assertEquals(0, commits.get()); assertEquals(0, count());
    }
    @ParameterizedTest @CsvSource({"copy-before,0","copy-after,0","active-copy,0","commit-before,0","commit-after,2","restore-after,2","close-after,2"})
    void lostReceiptsNeverCauseASecondWrite(String injected,long expected) throws Exception {
        fault=injected;
        var error=assertThrows(UnconfirmedWriteException.class,()->writeProtocol(List.of(new Object[]{1},new Object[]{2})));
        assertEquals(expected,count());assertEquals(1,copies.get());assertEquals(0,inserts.get());
        assertEquals(injected.startsWith("copy-")||injected.equals("active-copy")?0:1,commits.get());
        assertNull(error.getCause());assertEquals(0,error.getSuppressed().length);
        try(var c=pool.getConnection()){assertTrue(c.getAutoCommit());}
    }
    @Test void actualShortCopyReceiptRollsBackInsteadOfClaimingSuccess() throws Exception {
        sql("CREATE FUNCTION "+schema+".skip_second() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN IF NEW.id=2 THEN RETURN NULL; END IF; RETURN NEW; END'");
        sql("CREATE TRIGGER skip_second BEFORE INSERT ON "+schema+".rows FOR EACH ROW EXECUTE FUNCTION "+schema+".skip_second()");
        assertThrows(UnconfirmedWriteException.class,()->writeProtocol(List.of(new Object[]{1},new Object[]{2})));
        assertEquals(0,count());assertEquals(0,commits.get());assertEquals(0,inserts.get());
    }
    @Test void rejectedRowLeavesNoPrefixAndNoFallback() throws Exception {
        sql("ALTER TABLE "+schema+".rows ADD CHECK(id>0)");
        assertThrows(UnconfirmedWriteException.class,()->writeProtocol(List.of(new Object[]{1},new Object[]{-1})));
        assertEquals(0,count());assertEquals(0,inserts.get());
    }
    private long count() throws SQLException {
        try(var s=control.createStatement();var rs=s.executeQuery("SELECT count(*) FROM "+schema+".rows")){assertTrue(rs.next());return rs.getLong(1);}
    }
    private void sql(String sql) throws SQLException {try(var s=control.createStatement()){s.execute(sql);}}
    private static SQLException lost(){return new SQLException("fixture-private-lost-reply");}
    private long writeProtocol(List<Object[]> rows) {
        String text=rows.stream().map(row->row[0].toString()+"\n").collect(java.util.stream.Collectors.joining());
        return cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor.execute(facade,context->{
            Object api=context.connection().getClass().getMethod("getCopyAPI").invoke(context.connection());
            return cn.iocoder.yudao.framework.dbswitch.dbwriter.uxdb.UxdbCopyProtocol.execute(context,api,
                    "COPY "+schema+".rows(id) FROM STDIN",text.getBytes(java.nio.charset.StandardCharsets.UTF_8),rows.size());
        });
    }
    private static Object invoke(Object target,Method method,Object[] args) throws Throwable {
        try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}
    }
}
