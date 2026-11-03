package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import cn.iocoder.yudao.framework.dbswitch.core.schema.NativeSchemaReader;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.mssql.SqlServerWriterImpl;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQL Server transactions with faults injected at native session and JDBC acknowledgement boundaries. */
@Timeout(90)
@EnabledIfSystemProperty(named="datahub.test.sqlserver.url",matches="jdbc:sqlserver://127[.]0[.]0[.]1:[0-9]+;databaseName=codex_q11;encrypt=false")
class SqlServerIdentityOutcomeTest {
    private Connection control;
    private String schema;
    @BeforeEach void setup()throws Exception {
        control=connect();schema="identity_outcome_"+UUID.randomUUID().toString().replace("-","");
        sql("CREATE SCHEMA ["+schema+"]");
        sql("CREATE TABLE "+table()+" (id BIGINT IDENTITY(5,3) PRIMARY KEY, note NVARCHAR(40))");
    }
    @AfterEach void cleanup()throws Exception {
        if(control!=null)try(var owned=control){sql("DROP TABLE IF EXISTS "+table());sql("DROP SCHEMA ["+schema+"]");}
    }
    @Test void identitySessionAndBatchUseOnePhysicalConnectionAndReleaseItAfterCommit()throws Exception {
        var probe=new FaultDataSource("NONE");var writer=prepared(probe);probe.arm();
        assertEquals(1,writer.write(List.of("id","note"),List.<Object[]>of(new Object[]{17L,"exact"})));
        assertEquals(List.of("ON","BATCH","OFF","COMMIT","CLOSE"),probe.events);
        assertEquals(1,probe.operationConnections.size());assertTrue(probe.last.isClosed());
        assertEquals(List.of(17L),ids());
    }
    @ParameterizedTest @ValueSource(strings={"ON_ACK","BATCH_ACK","OFF_BEFORE","OFF_ACK","COMMIT_ACK","CLOSE_ACK"})
    void attemptedIdentityWriteFailureIsUnknownNeverRetriedAndDiscardsSession(String fault)throws Exception {
        var probe=new FaultDataSource(fault);var writer=prepared(probe);probe.arm();
        var failure=assertThrows(UnconfirmedWriteException.class,()->writer.write(List.of("id","note"),List.<Object[]>of(new Object[]{17L,"secret-row-value"})));
        assertFalse(failure.toString().contains("secret-row-value"));assertNull(failure.getCause());
        assertTrue(probe.failed);assertEquals(1,Collections.frequency(probe.events,"ON"));
        assertEquals(1,Collections.frequency(probe.events,"OFF"));
        assertTrue(Collections.frequency(probe.events,"BATCH")<=1);assertEquals(1,probe.operationConnections.size());
        assertTrue(probe.events.contains("ABORT"));assertTrue(probe.last.isClosed());
        assertEquals(fault.equals("COMMIT_ACK")||fault.equals("CLOSE_ACK")?List.of(17L):List.of(),ids());
    }
    @Test void alignmentAcknowledgementLossDoesNotPretendTheCounterWasRolledBack()throws Exception {
        var probe=new FaultDataSource("RESEED_ACK");var writer=prepared(probe);
        writer.write(List.of("id","note"),List.<Object[]>of(new Object[]{17L,"exact"}));probe.arm();
        assertThrows(UnconfirmedWriteException.class,writer::completeIdentityTransfer);
        assertEquals(1,Collections.frequency(probe.events,"RESEED"));assertTrue(probe.events.contains("ABORT"));
        assertEquals(List.of(17L),ids());assertTrue(probe.last.isClosed());
    }
    @Test void physicalReplacementAfterPreparationFailsBeforeIdentityInsertOrBatch()throws Exception {
        var probe=new FaultDataSource("NONE");var writer=prepared(probe);
        sql("DROP TABLE "+table());sql("CREATE TABLE "+table()+" (id BIGINT IDENTITY(5,3) PRIMARY KEY,note NVARCHAR(40))");
        probe.arm();assertThrows(IllegalStateException.class,()->writer.write(List.of("id","note"),List.<Object[]>of(new Object[]{17L,"exact"})));
        assertFalse(probe.events.contains("ON"));assertFalse(probe.events.contains("BATCH"));assertTrue(ids().isEmpty());
    }
    private SqlServerWriterImpl prepared(DataSource dataSource)throws Exception {
        var identity=NativeSchemaReader.readSqlServerIdentityTable(control,schema,"rows",20).columns();
        var writer=new SqlServerWriterImpl(dataSource);writer.configureIdentityTransfer(new IdentityTransferOptions(true,true,Map.of(),identity));
        writer.preflightIdentityTransfer(schema,"rows",true);writer.prepareWrite(schema,"rows",List.of("id","note"));return writer;
    }
    private List<Long> ids()throws Exception {
        var result=new ArrayList<Long>();try(var s=control.createStatement();var r=s.executeQuery("SELECT id FROM "+table()+" ORDER BY id")) {while(r.next())result.add(r.getLong(1));}return result;
    }
    private void sql(String text)throws Exception {try(var s=control.createStatement()){s.setQueryTimeout(20);s.execute(text);}}
    private String table(){return '['+schema+"].[rows]";}
    private static Connection connect()throws SQLException {return DriverManager.getConnection(System.getProperty("datahub.test.sqlserver.url"),"sa",System.getenv("DATAHUB_TEST_SQLSERVER_PASSWORD"));}
    private static Object invoke(Method method,Object receiver,Object[] args)throws Throwable {
        try{return method.invoke(receiver,args);}catch(InvocationTargetException error){throw error.getCause();}
    }
    private static final class FaultDataSource extends org.springframework.jdbc.datasource.AbstractDataSource {
        private final String fault;
        private boolean armed,failed;
        private int sequence;
        private Connection last;
        private final List<String> events=new ArrayList<>();
        private final Set<Integer> operationConnections=new HashSet<>();
        FaultDataSource(String fault){this.fault=fault;}
        void arm(){armed=true;events.clear();operationConnections.clear();}
        @Override public Connection getConnection()throws SQLException {
            var physical=connect();int id=++sequence;
            if(!armed)return physical;
            last=physical;
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                String name=method.getName();
                if(name.equals("createStatement")||name.equals("prepareStatement")) {
                    Object statement=invoke(method,physical,args);Class<?> api=name.equals("createStatement")?Statement.class:PreparedStatement.class;
                    return Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{api},(p,m,a)->{
                        String event=null;
                        if(m.getName().equals("executeBatch"))event="BATCH";
                        else if(m.getName().equals("execute")&&a!=null&&a.length>0&&a[0] instanceof String sql) {
                            if(sql.startsWith("SET IDENTITY_INSERT "))event=sql.endsWith(" ON")?"ON":"OFF";
                            else if(sql.startsWith("DBCC CHECKIDENT"))event="RESEED";
                        }
                        if(event!=null){events.add(event);operationConnections.add(id);if(event.equals("OFF"))fail("OFF_BEFORE");}
                        Object value=invoke(m,statement,a);if(event!=null)fail(event+"_ACK");return value;
                    });
                }
                if(name.equals("commit")||name.equals("close")||name.equals("abort")) {
                    events.add(name.toUpperCase(Locale.ROOT));operationConnections.add(id);
                    Object value=invoke(method,physical,args);fail(name.toUpperCase(Locale.ROOT)+"_ACK");return value;
                }
                return invoke(method,physical,args);
            });
        }
        @Override public Connection getConnection(String user,String password)throws SQLException{return getConnection();}
        private void fail(String point)throws SQLException {if(!failed&&fault.equals(point)){failed=true;throw new SQLException("injected-secret-marker");}}
    }
}
