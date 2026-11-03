package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.data.recovery.PostgresTableRewriteStore;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static cn.iocoder.yudao.framework.dbswitch.data.TableRewriteMaterialTest.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(40)
@EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+")
class TableRewriteStorePostgreSqlTest {
    private DriverManagerDataSource source;private String schema,relation,pages;private SecretKeySpec secret;
    @BeforeEach void setup()throws Exception {
        Class.forName("org.postgresql.Driver");source=new DriverManagerDataSource(System.getProperty("datahub.test.postgres.url"),System.getProperty("datahub.test.postgres.user","dataforge_test"),"");
        schema="table_recovery_"+UUID.randomUUID().toString().replace("-","");relation=schema+".journal";pages=schema+".pages";
        byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);secret=new SecretKeySpec(key,"AES");
        sql("CREATE SCHEMA "+schema);for(String ddl:PostgresTableRewriteStore.schemaStatements(relation,pages))sql(ddl);
    }
    @AfterEach void cleanup()throws Exception{if(schema!=null)sql("DROP SCHEMA "+schema+" CASCADE");}
    private PostgresTableRewriteStore store(){return store(SCOPE,(c,k)->{});}
    private PostgresTableRewriteStore store(String scope,PostgresTableRewriteStore.OwnershipGuard guard){return new PostgresTableRewriteStore(source,relation,pages,scope,"fixture",Map.of("fixture",secret),LIMITS,10,guard);}
    private void sql(String sql)throws Exception{try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
    @Test void restartLoadsAllEncryptedPagesAndBothImagesWithoutLosingPrecision()throws Exception {
        var expected=material();store().prepare(key(),OWNER,expected);var restored=store().read(key()).orElseThrow().material();
        assertEquals(expected.hash(),restored.hash());assertEquals(4,restored.pages().size());assertArrayEquals(row("b","3.000"),restored.rows(restored.pages().get(2),LIMITS).getFirst());
        try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT payload FROM "+pages+" ORDER BY image DESC,ordinal")) {
            assertTrue(r.next());assertFalse(Arrays.equals(expected.pages().getFirst().encoded(),r.getBytes(1)));
        }
        assertEquals(0,store().prepare(key(),OWNER,expected).revision());
    }
    @Test void headerMacTamperCannotHidePendingByChangingPhase()throws Exception {
        store().prepare(key(),OWNER,material());sql("UPDATE "+relation+" SET phase='DROP_UNKNOWN'");
        assertThrows(IllegalStateException.class,()->store().read(key()));assertThrows(IllegalStateException.class,()->store().pending(SCOPE,RESOURCE,null,1));
    }
    @Test void pageCiphertextAndMetadataTamperOrMissingPageFailAuthentication()throws Exception {
        store().prepare(key(),OWNER,material());sql("UPDATE "+pages+" SET row_count=2 WHERE image='BEFORE' AND ordinal=0");
        assertThrows(IllegalStateException.class,()->store().read(key()));sql("UPDATE "+pages+" SET row_count=1");
        sql("DELETE FROM "+pages+" WHERE image='AFTER' AND ordinal=1");assertThrows(IllegalStateException.class,()->store().read(key()));
    }
    @Test void changedCiphertextOrUnavailableRotationKeyRejectsRecovery()throws Exception {
        store().prepare(key(),OWNER,material());
        var rotated=new SecretKeySpec(new byte[32],"AES");
        var missing=new PostgresTableRewriteStore(source,relation,pages,SCOPE,"other",Map.of("other",rotated),LIMITS,10,(c,k)->{});
        assertThrows(IllegalStateException.class,()->missing.read(key()));
        var retained=new PostgresTableRewriteStore(source,relation,pages,SCOPE,"other",Map.of("other",rotated,"fixture",secret),LIMITS,10,(c,k)->{});
        assertEquals(material().hash(),retained.read(key()).orElseThrow().material().hash());
        sql("UPDATE "+pages+" SET payload=set_byte(payload,0,get_byte(payload,0)#1) WHERE image='BEFORE' AND ordinal=0");
        assertThrows(IllegalStateException.class,()->store().read(key()));
    }
    @Test void commitResponseLossKeepsAllPreparedPagesReadableAndRetryIsExact() {
        var armed=new AtomicBoolean(true);var uncertain=new PostgresTableRewriteStore(commitFault(armed),relation,pages,SCOPE,"fixture",Map.of("fixture",secret),LIMITS,10,(c,k)->{});
        assertThrows(IllegalStateException.class,()->uncertain.prepare(key(),OWNER,material()));assertFalse(armed.get());
        var actual=store().read(key()).orElseThrow();assertEquals(TableRewriteRecord.Phase.PREPARED,actual.phase());assertEquals(4,actual.material().pages().size());
        assertEquals(0,store().prepare(key(),OWNER,material()).revision());
    }
    @Test void lostCreateConfirmedCommitIsReadBackFromAuthenticatedStateNotInferredFromName() {
        var s=store();var r=s.prepare(key(),OWNER,material());
        for(var phase:List.of(TableRewriteRecord.Phase.DROP_INTENT,TableRewriteRecord.Phase.DROP_CONFIRMED,TableRewriteRecord.Phase.CREATE_INTENT))
            r=s.transition(key(),OWNER,r.revision(),r.phase(),phase,null).orElseThrow();
        var intent=r;var armed=new AtomicBoolean(true);var uncertain=new PostgresTableRewriteStore(commitFault(armed),relation,pages,SCOPE,"fixture",Map.of("fixture",secret),LIMITS,10,(c,k)->{});
        assertThrows(IllegalStateException.class,()->uncertain.transition(key(),OWNER,intent.revision(),intent.phase(),TableRewriteRecord.Phase.CREATE_CONFIRMED,NEXT));
        var confirmed=s.read(key()).orElseThrow();assertEquals(TableRewriteRecord.Phase.CREATE_CONFIRMED,confirmed.phase());assertEquals(NEXT,confirmed.newIdentity());
        assertEquals(NEXT,s.claimRecovery(key(),confirmed.revision(),"table_owner_0002").orElseThrow().newIdentity());
    }
    @Test void partialPageInsertionRollsBackHeaderAndAllEarlierPages()throws Exception {
        sql("ALTER TABLE "+pages+" ADD CONSTRAINT reject_second CHECK(ordinal=0)");
        assertThrows(IllegalStateException.class,()->store().prepare(key(),OWNER,material()));assertTrue(store().read(key()).isEmpty());
        try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+pages)){assertTrue(r.next());assertEquals(0,r.getInt(1));}
    }
    @Test void logicalResourceRemainsGloballyOccupiedAcrossTenantsAndUnknownGenerations() {
        var s=store();s.prepare(key(),OWNER,material());
        var foreign=store("f".repeat(64),(c,k)->{});var other=new TableRewriteKey("f".repeat(64),RESOURCE,"e".repeat(64));
        assertThrows(IllegalArgumentException.class,()->foreign.read(key()));assertThrows(IllegalStateException.class,()->foreign.prepare(other,"table_owner_0002",material()));
        var dropped=s.transition(key(),OWNER,0,TableRewriteRecord.Phase.PREPARED,TableRewriteRecord.Phase.DROP_INTENT,null).orElseThrow();
        var unknown=s.transition(key(),OWNER,dropped.revision(),dropped.phase(),TableRewriteRecord.Phase.DROP_UNKNOWN,null).orElseThrow();
        assertTrue(s.claimRecovery(key(),unknown.revision(),"table_owner_0002").isEmpty());assertEquals(List.of(key()),s.pending(SCOPE,RESOURCE,null,1).keys());
    }
    @Test void exactCasHasOneWinnerAndConfirmedGenerationCannotChangeDuringRecovery()throws Exception {
        store().prepare(key(),OWNER,material());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var latch=new CountDownLatch(1);var results=new ArrayList<Future<Boolean>>();
            for(int i=0;i<2;i++)results.add(pool.submit(()->{latch.await();return store().transition(key(),OWNER,0,TableRewriteRecord.Phase.PREPARED,TableRewriteRecord.Phase.DROP_INTENT,null).isPresent();}));
            latch.countDown();int wins=0;for(var f:results)if(f.get(10,TimeUnit.SECONDS))wins++;assertEquals(1,wins);
        }
        var r=store().read(key()).orElseThrow();r=store().transition(key(),OWNER,r.revision(),r.phase(),TableRewriteRecord.Phase.DROP_CONFIRMED,null).orElseThrow();
        r=store().transition(key(),OWNER,r.revision(),r.phase(),TableRewriteRecord.Phase.CREATE_INTENT,null).orElseThrow();
        r=store().transition(key(),OWNER,r.revision(),r.phase(),TableRewriteRecord.Phase.CREATE_CONFIRMED,NEXT).orElseThrow();
        var claim=store().claimRecovery(key(),r.revision(),"table_owner_0002").orElseThrow();assertEquals(NEXT,claim.newIdentity());
        assertTrue(store().transition(key(),OWNER,claim.revision(),claim.phase(),TableRewriteRecord.Phase.RESTORE_INTENT,NEXT).isEmpty());
        var replacing=new TableRewriteIdentity("fixture-v1","{\"generation\":3}");
        assertThrows(IllegalStateException.class,()->store().transition(key(),claim.ownerToken(),claim.revision(),claim.phase(),TableRewriteRecord.Phase.RESTORE_INTENT,replacing));
    }
    @Test void scanStillChecksOwnershipWhenNoHeadersExist() {
        var denied=store(SCOPE,(c,k)->{throw new java.sql.SQLException("owner revoked");});
        assertThrows(IllegalStateException.class,()->denied.pending(SCOPE,RESOURCE,null,1));assertTrue(store().read(key()).isEmpty());
    }
    @Test void appliedOnlyPageDoesNotHideFollowingWorkAndEveryHeaderIsAuthenticated() {
        var first=new TableRewriteKey(SCOPE,RESOURCE,"1".repeat(64));var s=store();var r=s.prepare(first,OWNER,material());
        for(var phase:List.of(TableRewriteRecord.Phase.DROP_INTENT,TableRewriteRecord.Phase.DROP_CONFIRMED,TableRewriteRecord.Phase.CREATE_INTENT,TableRewriteRecord.Phase.CREATE_CONFIRMED,TableRewriteRecord.Phase.RESTORE_INTENT,TableRewriteRecord.Phase.APPLIED))
            r=s.transition(first,OWNER,r.revision(),r.phase(),phase,TableRewriteRecord.requiresNewIdentity(phase)?NEXT:null).orElseThrow();
        s.prepare(key(),OWNER,material());var page=s.pending(SCOPE,RESOURCE,null,1);assertTrue(page.keys().isEmpty());assertEquals(first.operation(),page.nextAfter());
        assertEquals(List.of(key()),s.pending(SCOPE,RESOURCE,page.nextAfter(),1).keys());
    }
    private javax.sql.DataSource commitFault(AtomicBoolean armed) {
        return new AbstractDataSource() {
            @Override public java.sql.Connection getConnection()throws java.sql.SQLException {
                var connection=source.getConnection();
                return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)-> {
                    try {
                        Object result=method.invoke(connection,args);
                        if(method.getName().equals("commit")&&armed.compareAndSet(true,false))throw new java.sql.SQLException("synthetic committed response loss");
                        return result;
                    }catch(java.lang.reflect.InvocationTargetException invocation){throw invocation.getCause();}
                });
            }
            @Override public java.sql.Connection getConnection(String user,String password)throws java.sql.SQLException{return getConnection();}
        };
    }
}
