package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.util.TidbRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.core.util.TidbSourceGeneration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(40)
@EnabledIfSystemProperty(named="datahub.test.tidb.url",matches="jdbc:mysql://127[.]0[.]0[.]1:.*")
class TidbSourceGenerationTidbTest {
    private final String schema="tidb_gen_"+UUID.randomUUID().toString().replace("-","");
    private Connection admin;
    private Connection open()throws SQLException{return DriverManager.getConnection(System.getProperty("datahub.test.tidb.url"),System.getProperty("datahub.test.tidb.user","root"),System.getProperty("datahub.test.tidb.password",""));}
    @BeforeEach void setup()throws Exception{admin=open();sql("CREATE DATABASE "+schema);}
    @AfterEach void cleanup()throws Exception{if(admin!=null)try(var owned=admin){if(!owned.getAutoCommit()){owned.rollback();owned.setAutoCommit(true);}sql("DROP DATABASE IF EXISTS "+schema);}}
    private void sql(String sql)throws SQLException{try(var s=admin.createStatement()){s.setQueryTimeout(5);s.execute(sql);}}
    private SourceGenerationPin initialize()throws SQLException{return TidbSourceGeneration.initialize(admin,schema,5);}

    @Test void initializeFenceRotateAndRetryRetainHistoryAndNeverReactivateOldPins()throws Exception {
        var first=initialize();assertEquals(first,initialize());
        assertEquals(64,TidbSourceGeneration.verifyCurrent(admin,first,5).length());
        assertThrows(SQLException.class,()->TidbSourceGeneration.rotate(admin,first,UUID.randomUUID().toString(),5));
        TidbSourceGeneration.fence(admin,first,5);TidbSourceGeneration.fence(admin,first,5);
        assertThrows(SQLException.class,()->TidbSourceGeneration.verifyCurrent(admin,first,5));
        assertEquals(first,initialize()); // initialization may not reactivate a fenced head
        String proposed=UUID.randomUUID().toString();var next=TidbSourceGeneration.rotate(admin,first,proposed,5);
        assertEquals(next,TidbSourceGeneration.rotate(admin,first,proposed,5));
        TidbSourceGeneration.verifyCurrent(admin,next,5);
        assertThrows(SQLException.class,()->TidbSourceGeneration.verifyCurrent(admin,first,5));
        TidbSourceGeneration.fence(admin,next,5);
        assertThrows(SQLException.class,()->TidbSourceGeneration.rotate(admin,next,first.generation(),5));
    }
    @Test void missingAndCorruptInstallationsAreNeverRepairedByRuntime()throws Exception {
        var expected=new SourceGenerationPin(schema,UUID.randomUUID().toString(),UUID.randomUUID().toString());
        assertThrows(SQLException.class,()->TidbSourceGeneration.verifyCurrent(admin,expected,5));
        var pin=initialize();sql("DELETE FROM "+schema+"."+TidbSourceGeneration.HISTORY_TABLE);
        assertThrows(SQLException.class,()->TidbSourceGeneration.verifyCurrent(admin,pin,5));
        assertThrows(SQLException.class,this::initialize);
    }
    @Test void initializedPartialStateIsNotSilentlyRegenerated()throws Exception {
        initialize();sql("DROP TABLE "+schema+"."+TidbSourceGeneration.HISTORY_TABLE);
        assertThrows(SQLException.class,this::initialize);
        try(var s=admin.createStatement();var r=s.executeQuery("SELECT count(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='"+schema+"'")){assertTrue(r.next());assertEquals(1,r.getInt(1));}
    }
    @Test void snapshotVerificationIsNotMisrepresentedAsAFreshFenceCheck()throws Exception {
        var pin=initialize();String before=TidbSourceGeneration.verifyCurrent(admin,pin,5);
        try(var cursor=open()){
            cursor.setAutoCommit(false);String tso=TidbRelationIdentity.beginOwnedSnapshot(cursor,5);
            try {
                assertEquals(before,TidbSourceGeneration.verifySnapshot(cursor,pin,tso,5));
                TidbSourceGeneration.fence(admin,pin,5);
                assertEquals(before,TidbSourceGeneration.verifySnapshot(cursor,pin,tso,5)); // intentionally old MVCC image
                assertThrows(SQLException.class,()->TidbSourceGeneration.verifyCurrent(admin,pin,5));
            }finally{cursor.rollback();}
        }
    }
    @Test void administratorCannotImplicitlyCommitACallerTransaction()throws Exception {
        initialize();admin.setAutoCommit(false);
        sql("INSERT INTO "+schema+"."+TidbSourceGeneration.HISTORY_TABLE+" VALUES('"+UUID.randomUUID()+"',NULL,'"+UUID.randomUUID()+"')");
        assertThrows(SQLException.class,this::initialize);assertFalse(admin.getAutoCommit());admin.rollback();admin.setAutoCommit(true);
        initialize();
    }
    @Test void changedNativeMarkerShapeFailsBeforeAdminMutation()throws Exception {
        var pin=initialize();sql("ALTER TABLE "+schema+"."+TidbSourceGeneration.MARKER_TABLE+" ADD COLUMN unexpected int");
        assertThrows(SQLException.class,()->TidbSourceGeneration.verifyCurrent(admin,pin,5));
        assertThrows(SQLException.class,()->TidbSourceGeneration.fence(admin,pin,5));
        try(var s=admin.createStatement();var r=s.executeQuery("SELECT state FROM "+schema+"."+TidbSourceGeneration.MARKER_TABLE)){assertTrue(r.next());assertEquals("ACTIVE",r.getString(1));}
    }

    @Test void anActuallyCommittedRotationWithLostAcknowledgementIsReconciledByItsExactReceipt()throws Exception {
        var pin=initialize();TidbSourceGeneration.fence(admin,pin,5);String next=UUID.randomUUID().toString();
        Connection lost=(Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)-> {
            try {
                Object value=method.invoke(admin,args);
                if(method.getName().equals("commit"))throw new SQLException("fixture lost commit acknowledgement");
                return value;
            }catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
        });
        assertThrows(SQLException.class,()->TidbSourceGeneration.rotate(lost,pin,next,5));
        var reconciled=TidbSourceGeneration.rotate(admin,pin,next,5);assertEquals(next,reconciled.generation());
        TidbSourceGeneration.verifyCurrent(admin,reconciled,5);
        assertThrows(SQLException.class,()->TidbSourceGeneration.verifyCurrent(admin,pin,5));
    }
}
