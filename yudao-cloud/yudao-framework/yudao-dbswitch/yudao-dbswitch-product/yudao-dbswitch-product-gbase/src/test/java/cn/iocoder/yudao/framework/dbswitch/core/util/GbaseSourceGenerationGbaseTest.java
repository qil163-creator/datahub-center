package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="datahub.test.gbase8a.url",matches="jdbc:gbase://127[.]0[.]0[.]1:[0-9]+/gbase")
@EnabledIfEnvironmentVariable(named="DATAHUB_GBASE8A_TEST_PASSWORD",matches=".+")
@Timeout(90)
class GbaseSourceGenerationGbaseTest {
    private Connection control;private String database;
    @BeforeEach void setup()throws Exception {
        Class.forName("com.gbase.jdbc.Driver");control=open();database="gg89_"+UUID.randomUUID().toString().replace("-","");
        sql(control,"CREATE DATABASE "+Gbase8aCursorMetadata.quote(database));
    }
    @AfterEach void cleanup()throws Exception {
        if(control!=null)try(var owned=control){sql(control,"DROP DATABASE "+Gbase8aCursorMetadata.quote(database));}
    }
    @Test void managedInitializeFenceRotateAndRetryHaveOneActualHead()throws Exception {
        var first=Gbase8aSourceGeneration.initialize(control,database,10);
        assertEquals(4,UUID.fromString(first.instanceId()).version());
        assertEquals(first,Gbase8aSourceGeneration.initialize(control,database,10));
        Gbase8aSourceGeneration.verifyCurrent(control,first,10);Gbase8aSourceGeneration.verify(control,first,database,10);
        String next=UUID.randomUUID().toString();assertThrows(SQLException.class,()->Gbase8aSourceGeneration.rotate(control,first,next,10));
        Gbase8aSourceGeneration.fence(control,first,10);Gbase8aSourceGeneration.fence(control,first,10);
        assertThrows(SQLException.class,()->Gbase8aSourceGeneration.verifyCurrent(control,first,10));
        var current=Gbase8aSourceGeneration.rotate(control,first,next,10);
        assertEquals(current,Gbase8aSourceGeneration.rotate(control,first,next,10));
        assertThrows(SQLException.class,()->Gbase8aSourceGeneration.verifyCurrent(control,first,10));
        Gbase8aSourceGeneration.verifyCurrent(control,current,10);
        assertEquals(1,count(Gbase8aSourceGeneration.references(current).getFirst()));
        assertEquals(2,count(Gbase8aSourceGeneration.references(current).getLast()));
        assertTrue(control.getAutoCommit());
        Gbase8aSourceGeneration.drop(control,database,10);
        assertThrows(SQLException.class,()->Gbase8aSourceGeneration.verifyCurrent(control,current,10));
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void realDuplicatePrimaryKeysInEitherMarkerAreRejected(boolean head)throws Exception {
        var pin=Gbase8aSourceGeneration.initialize(control,database,10);var refs=Gbase8aSourceGeneration.references(pin);
        String table=head?refs.getFirst():refs.getLast();sql(control,"INSERT INTO "+table+" SELECT * FROM "+table);
        assertEquals(2,count(table)); // EXPRESS accepted duplicate declared PRIMARY KEY values.
        assertThrows(SQLException.class,()->Gbase8aSourceGeneration.verifyCurrent(control,pin,10));
        assertThrows(SQLException.class,()->Gbase8aSourceGeneration.fence(control,pin,10));
        assertEquals(2,count(table));assertTrue(control.getAutoCommit());
    }
    @Test void combinedReadLocksBlockAdministratorFenceUntilOwnerReleasesBothMarkers()throws Exception {
        var pin=Gbase8aSourceGeneration.initialize(control,database,10);
        try(var holder=open();var executor=Executors.newSingleThreadExecutor()) {
            sql(holder,"LOCK TABLES "+Gbase8aSourceGeneration.markerReadLocks(pin));
            Gbase8aSourceGeneration.verifyCurrent(holder,pin,10);
            var started=new CountDownLatch(1);var future=executor.submit(()->{try(var admin=open()){started.countDown();Gbase8aSourceGeneration.fence(admin,pin,15);}return null;});
            assertTrue(started.await(5,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,()->future.get(200,TimeUnit.MILLISECONDS));
            sql(holder,"UNLOCK TABLES");future.get(20,TimeUnit.SECONDS);
        }
        assertThrows(SQLException.class,()->Gbase8aSourceGeneration.verifyCurrent(control,pin,10));
    }
    @Test void lostCommitDoesNotUnlockOrReplayAndRequestedUuidCanBeReconciled()throws Exception {
        var first=Gbase8aSourceGeneration.initialize(control,database,10);Gbase8aSourceGeneration.fence(control,first,10);
        String next=UUID.randomUUID().toString();var actions=new ArrayList<String>();
        try(var physical=open()) {
            Connection fault=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                actions.add(method.getName());
                try{Object value=method.invoke(physical,args);if(method.getName().equals("commit"))throw new SQLException("lost acknowledgement");return value;}
                catch(InvocationTargetException failure){throw failure.getCause();}
            });
            var error=assertThrows(SQLException.class,()->Gbase8aSourceGeneration.rotate(fault,first,next,10));assertTrue(error.getMessage().contains("UNKNOWN"));
            assertEquals(1,actions.stream().filter("commit"::equals).count());
            assertFalse(actions.subList(actions.indexOf("commit")+1,actions.size()).contains("createStatement"));
            assertFalse(actions.subList(actions.indexOf("commit")+1,actions.size()).contains("setAutoCommit"));
        }
        var current=new SourceGenerationPin(database,first.instanceId(),next);
        Gbase8aSourceGeneration.verifyCurrent(control,current,10);assertEquals(current,Gbase8aSourceGeneration.rotate(control,first,next,10));
        assertEquals(2,count(Gbase8aSourceGeneration.references(current).getLast()));
    }
    @Test void legacySourcePinRemainsReadOnlyButCannotAuthorizeManagedTarget()throws Exception {
        var external=new SourceGenerationPin(database,Gbase8aSourceGeneration.instance(control,database,10),UUID.randomUUID().toString());
        Gbase8aSourceGeneration.verify(control,external,database,10);
        assertThrows(IllegalArgumentException.class,()->Gbase8aSourceGeneration.verifyCurrent(control,external,10));
        assertThrows(IllegalArgumentException.class,()->Gbase8aSourceGeneration.markerReadLocks(external));
    }
    private Connection open()throws SQLException{return DriverManager.getConnection(System.getProperty("datahub.test.gbase8a.url"),System.getProperty("datahub.test.gbase8a.user","root"),System.getenv("DATAHUB_GBASE8A_TEST_PASSWORD"));}
    private static void sql(Connection c,String sql)throws SQLException {try(var statement=c.createStatement()){statement.setQueryTimeout(20);statement.execute(sql);}}
    private long count(String reference)throws SQLException {try(var s=control.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+reference)){assertTrue(r.next());return r.getLong(1);}}
}
