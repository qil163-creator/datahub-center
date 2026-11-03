package cn.iocoder.yudao.framework.dbswitch.dbwriter.gpdb;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
@EnabledIfSystemProperty(named="datahub.test.greenplum.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/postgres[?]sslmode=disable")
@EnabledIfEnvironmentVariable(named="DATAHUB_GREENPLUM_TEST_PASSWORD",matches=".+")
class GreenplumCopyNativeTest {
    private final String schema = "copy88_" + UUID.randomUUID().toString().replace("-", "");
    private DataSource source;
    @BeforeEach void setup() throws Exception {
        source = new DriverManagerDataSource(System.getProperty("datahub.test.greenplum.url"), "gpadmin",
                System.getenv("DATAHUB_GREENPLUM_TEST_PASSWORD"));
        sql("CREATE SCHEMA " + schema);
    }
    @AfterEach void cleanup() throws Exception { if (source != null) sql("DROP SCHEMA " + schema + " CASCADE"); }

    @Test void nativeBinaryScalarRowsLargeFieldsAndNullsRoundTrip() throws Exception {
        sql("CREATE TABLE " + schema + ".t(id bigint PRIMARY KEY, a smallint, b integer, n numeric(38,12),"
                + " f real, d double precision, flag boolean, v varchar(20), ch char(5), note text, payload bytea,"
                + " day date, at time(6), ts timestamp(6)) DISTRIBUTED BY(id)");
        var fields = List.of("id", "a", "b", "n", "f", "d", "flag", "v", "ch", "note", "payload", "day", "at", "ts");
        String text = "中文😀'\\\n".repeat(10000); byte[] bytes = new byte[100000]; new Random(88).nextBytes(bytes);
        var writer = new GreenplumCopyWriterImpl(source); writer.preflightRuntimeWrite(); writer.prepareWrite(schema, "t", fields);
        Object[] first = {Long.MAX_VALUE, Short.MIN_VALUE, Integer.MIN_VALUE, new BigDecimal("99999999999999999999999999.999999999999"),
                1.5f, -2.25d, true, "quoted'中", "abc", text, bytes, LocalDate.of(2026,10,8),
                LocalTime.of(23,59,59,123456000), LocalDateTime.of(2026,10,8,12,34,56,654321000)};
        Object[] second = new Object[fields.size()]; second[0] = Long.MIN_VALUE;
        var result = writer.writeWithReceipt(fields, List.of(first, second));
        assertEquals(2, result.acknowledgedRows()); assertEquals(CommitAcknowledgement.JDBC_COMMIT_RETURNED, result.commitAcknowledgement());
        try (var c = source.getConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + schema + ".t ORDER BY id DESC")) {
            assertTrue(r.next()); assertEquals(Long.MAX_VALUE, r.getLong(1)); assertEquals(Short.MIN_VALUE, r.getShort(2));
            assertEquals(Integer.MIN_VALUE,r.getInt(3)); assertEquals(first[3],r.getBigDecimal(4));
            assertEquals(1.5f,r.getFloat(5)); assertEquals(-2.25d,r.getDouble(6)); assertTrue(r.getBoolean(7));
            assertEquals(first[7],r.getString(8)); assertEquals("abc  ",r.getString(9)); assertEquals(text,r.getString(10));
            assertArrayEquals(bytes,r.getBytes(11)); assertEquals(first[11],r.getObject(12,LocalDate.class));
            assertEquals("23:59:59.123456",r.getString(13)); assertEquals(first[13],r.getObject(14,LocalDateTime.class));
            assertTrue(r.next()); assertEquals(Long.MIN_VALUE,r.getLong(1));
            for(int i=2;i<=14;i++) assertNull(r.getObject(i)); assertFalse(r.next());
        }
    }

    @Test void constraintFailureCancelsCopyAndRollsBackTheWholeBatch() throws Exception {
        sql("CREATE TABLE " + schema + ".t(id integer PRIMARY KEY CHECK(id>0)) DISTRIBUTED BY(id)");
        var writer = new GreenplumCopyWriterImpl(source); writer.prepareWrite(schema,"t",List.of("id"));
        assertThrows(UnconfirmedWriteException.class, () -> writer.write(List.of("id"),List.of(new Object[]{1},new Object[]{-1})));
        assertEquals(0, count());
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void lostCommitResponseIsUnknownWithoutReplay(boolean commitFirst) throws Exception {
        sql("CREATE TABLE " + schema + ".t(id integer PRIMARY KEY) DISTRIBUTED BY(id)");
        var commits = new AtomicInteger();
        DataSource fault = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection real = source.getConnection();
                return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                    if (method.getName().equals("commit")) {
                        commits.incrementAndGet(); if(commitFirst) real.commit();
                        throw new SQLException("synthetic lost response");
                    }
                    try { return method.invoke(real,args); } catch(InvocationTargetException e) { throw e.getCause(); }
                });
            }
            @Override public Connection getConnection(String u,String p) { throw new UnsupportedOperationException(); }
        };
        var writer = new GreenplumCopyWriterImpl(fault); writer.prepareWrite(schema,"t",List.of("id"));
        var failure=assertThrows(UnconfirmedWriteException.class,()->writer.write(List.of("id"),List.<Object[]>of(new Object[]{1})));
        assertNull(failure.getCause()); assertEquals(1,commits.get()); assertEquals(commitFirst?1:0,count());
    }

    @Test void rePreparationAndRecreatedRelationCannotReuseEarlierAdmission() throws Exception {
        sql("CREATE TABLE " + schema + ".t(id integer PRIMARY KEY) DISTRIBUTED BY(id)");
        var writer = new GreenplumCopyWriterImpl(source); writer.prepareWrite(schema,"t",List.of("id"));
        sql("DROP TABLE " + schema + ".t");sql("CREATE TABLE " + schema + ".t(id integer PRIMARY KEY) DISTRIBUTED BY(id)");
        assertThrows(RuntimeException.class,()->writer.write(List.of("id"),List.<Object[]>of(new Object[]{1})));
        assertEquals(0,count());
        sql("CREATE TABLE " + schema + ".unsupported(id integer,doc jsonb) DISTRIBUTED RANDOMLY");
        assertThrows(SQLFeatureNotSupportedException.class,()->writer.prepareWrite(schema,"unsupported",List.of("id","doc")));
        assertThrows(SQLFeatureNotSupportedException.class,()->writer.write(List.of("id"),List.<Object[]>of(new Object[]{1})));
        assertEquals(0,count());
    }

    @Test void preparedTruncateRefreshesGenerationAndRejectsDifferentOrRecreatedTargets() throws Exception {
        sql("CREATE TABLE " + schema + ".t(id integer PRIMARY KEY) DISTRIBUTED BY(id)");
        sql("INSERT INTO " + schema + ".t VALUES(7)");
        var writer = new GreenplumCopyWriterImpl(source); writer.prepareWrite(schema,"t",List.of("id"));
        assertThrows(SQLFeatureNotSupportedException.class,()->writer.truncatePreparedTarget(schema,"another"));
        assertEquals(1,count());
        writer.prepareWrite(schema,"t",List.of("id"));
        assertTrue(writer.truncatePreparedTarget(schema,"t"));assertEquals(0,count());
        assertEquals(1,writer.write(List.of("id"),List.<Object[]>of(new Object[]{1})));
        sql("TRUNCATE TABLE " + schema + ".t");sql("INSERT INTO " + schema + ".t VALUES(9)");
        assertThrows(RuntimeException.class,()->writer.truncatePreparedTarget(schema,"t"));
        assertEquals(1,count());
        assertThrows(SQLFeatureNotSupportedException.class,()->writer.write(List.of("id"),List.<Object[]>of(new Object[]{2})));
        assertEquals(1,count());
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void truncateLostCommitResponseInvalidatesAdmissionAndNeverRetries(boolean commitFirst) throws Exception {
        sql("CREATE TABLE " + schema + ".t(id integer PRIMARY KEY) DISTRIBUTED BY(id)");
        sql("INSERT INTO " + schema + ".t VALUES(7)");
        var commits=new AtomicInteger();
        var fault=new AbstractDataSource(){
            @Override public Connection getConnection() throws SQLException {
                var real=source.getConnection();
                return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                    if(method.getName().equals("commit")) {
                        commits.incrementAndGet();if(commitFirst)real.commit();throw new SQLException("synthetic truncate lost response");
                    }
                    try{return method.invoke(real,args);}catch(InvocationTargetException e){throw e.getCause();}
                });
            }
            @Override public Connection getConnection(String u,String p){throw new UnsupportedOperationException();}
        };
        var writer=new GreenplumCopyWriterImpl(fault);writer.prepareWrite(schema,"t",List.of("id"));
        var failure=assertThrows(UnconfirmedWriteException.class,()->writer.truncatePreparedTarget(schema,"t"));
        assertNull(failure.getCause());assertEquals(1,commits.get());assertEquals(commitFirst?0:1,count());
        assertThrows(SQLFeatureNotSupportedException.class,()->writer.write(List.of("id"),List.<Object[]>of(new Object[]{2})));
        assertEquals(commitFirst?0:1,count());
    }

    private void sql(String text) throws Exception { try(var c=source.getConnection();var s=c.createStatement()){s.setQueryTimeout(10);s.execute(text);} }
    private long count() throws Exception { try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM "+schema+".t")){assertTrue(r.next());return r.getLong(1);} }
}
