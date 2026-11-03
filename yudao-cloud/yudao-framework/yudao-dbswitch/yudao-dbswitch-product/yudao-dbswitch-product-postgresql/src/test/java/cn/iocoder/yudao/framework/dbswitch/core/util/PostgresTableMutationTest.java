package cn.iocoder.yudao.framework.dbswitch.core.util;
import cn.hutool.crypto.digest.DigestUtil;
import org.junit.jupiter.api.*;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL transactional DDL, identity drift, source-write races and unknown commit outcomes. */
@Timeout(45)
class PostgresTableMutationTest {
    static PostgreSQLContainer<?> postgres;
    static String jdbcUrl, username, password;
    String schema;
    @BeforeAll static void start() throws Exception {
        jdbcUrl = System.getProperty("datahub.test.postgres.url");
        if (jdbcUrl != null) {
            if (!jdbcUrl.startsWith("jdbc:postgresql://"))
                throw new IllegalArgumentException("Table mutation tests require an isolated loopback dbswitch_test database");
            var address = java.net.URI.create(jdbcUrl.substring("jdbc:".length()));
            if (address.getHost() == null || !Set.of("127.0.0.1", "localhost", "[::1]").contains(address.getHost())
                    || address.getPort() < 1 || address.getPort() > 65535 || !"/dbswitch_test".equals(address.getPath())
                    || address.getRawUserInfo() != null || address.getRawQuery() != null || address.getRawFragment() != null) {
                throw new IllegalArgumentException("Table mutation tests require an isolated loopback dbswitch_test database");
            }
            username = System.getProperty("datahub.test.postgres.user", "dataforge_test");
            password = System.getProperty("datahub.test.postgres.password", "");
        } else {
            Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Provide the isolated PostgreSQL fixture URL or Docker");
            postgres = new PostgreSQLContainer<>("postgres:16-alpine"); postgres.start();
            jdbcUrl = postgres.getJdbcUrl(); username = postgres.getUsername(); password = postgres.getPassword();
        }
        try (var connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            assertEquals(16, connection.getMetaData().getDatabaseMajorVersion(), "This fixture proves PostgreSQL 16 behavior");
        }
    }
    @AfterAll static void stop() { if (postgres != null) postgres.stop(); }
    Connection open() throws SQLException { return DriverManager.getConnection(jdbcUrl, username, password); }
    void sql(String text) throws SQLException { try(var c=open();var s=c.createStatement()) { s.execute(text); } }
    @BeforeEach void fixtures() throws Exception {
        schema="mutation_"+UUID.randomUUID().toString().replace("-","");
        sql("CREATE SCHEMA "+schema+";CREATE TABLE "+schema+".citizen(id bigint,amount numeric(38,10));INSERT INTO "+schema+".citizen VALUES (1,12345678901234567890.1234567890),(1,12345678901234567890.1234567890);CREATE TABLE "+schema+".archive(LIKE "+schema+".citizen INCLUDING ALL);INSERT INTO "+schema+".archive SELECT * FROM "+schema+".citizen");
    }
    @AfterEach void cleanup() throws Exception { if (schema != null) sql("DROP SCHEMA IF EXISTS "+schema+" CASCADE"); }
    PostgresTableMutation.Request request(String operation) throws Exception {
        try(var c=open()) { return new PostgresTableMutation.Request("case-"+UUID.randomUUID(),DigestUtil.sha256Hex(schema+operation),operation,
                PostgresTableMutation.inspect(c,schema,"citizen"),"retired", "DROP".equals(operation)?PostgresTableMutation.inspect(c,schema,"archive"):null); }
    }
    boolean exists(String table) throws Exception {
        try(var c=open();var s=c.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) { s.setString(1,schema+"."+table);try(var r=s.executeQuery()) {r.next();return r.getBoolean(1);} }
    }
    @Test void completeArchiveDropsAndReplayNeverDeletesReplacement() throws Exception {
        var request=request("DROP");
        try(var c=open()) { assertEquals(2,PostgresTableMutation.execute(c,request).rows()); }
        assertFalse(exists("citizen")); sql("CREATE TABLE "+schema+".citizen(id bigint)");
        try(var c=open()) { assertTrue(PostgresTableMutation.execute(c,request).replayed()); }
        assertTrue(exists("citizen")); assertTrue(exists("archive"));
    }
    @Test void partialArchiveIncludingDuplicateMultiplicityCannotDestroy() throws Exception {
        var request=request("DROP"); sql("DELETE FROM "+schema+".archive WHERE ctid IN (SELECT ctid FROM "+schema+".archive LIMIT 1)");
        try(var c=open()) { assertThrows(SQLException.class,()->PostgresTableMutation.execute(c,request)); assertNull(PostgresTableMutation.receipt(c,request.operationKey())); }
        assertTrue(exists("citizen"));
    }
    @Test void jsonDuplicateKeysAndArrayLowerBoundsMustMatchExactly() throws Exception {
        sql("DROP TABLE "+schema+".citizen,"+schema+".archive;CREATE TABLE "+schema+".citizen(doc json,items integer[]);"
                +"INSERT INTO "+schema+".citizen VALUES ('{\"key\":1,\"key\":2}','[0:1]={1,2}');"
                +"CREATE TABLE "+schema+".archive(LIKE "+schema+".citizen);INSERT INTO "+schema+".archive SELECT * FROM "+schema+".citizen");
        var request=request("DROP");
        sql("UPDATE "+schema+".archive SET doc='{\"key\":2}'");
        try(var c=open()) {assertThrows(SQLException.class,()->PostgresTableMutation.execute(c,request));}
        sql("UPDATE "+schema+".archive SET doc='{\"key\":1,\"key\":2}',items='{1,2}'");
        try(var c=open()) {assertThrows(SQLException.class,()->PostgresTableMutation.execute(c,request));}
        assertTrue(exists("citizen"));sql("UPDATE "+schema+".archive SET items='[0:1]={1,2}'");
        try(var c=open()) {assertEquals(1,PostgresTableMutation.execute(c,request).rows());}
        assertFalse(exists("citizen"));
    }
    @Test void replacingSameNameInvalidatesApprovalFingerprint() throws Exception {
        var request=request("RENAME"); sql("DROP TABLE "+schema+".citizen;CREATE TABLE "+schema+".citizen(id bigint,amount numeric(38,10))");
        try(var c=open()) { assertThrows(SQLException.class,()->PostgresTableMutation.execute(c,request)); }
        assertTrue(exists("citizen")); assertFalse(exists("retired"));
    }
    @Test void sourceWriteBeforeExclusiveLockIsIncludedInCoverageCheck() throws Exception {
        var request=request("DROP");
        try(var writer=open();var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            writer.setAutoCommit(false); writer.createStatement().execute("INSERT INTO "+schema+".citizen VALUES (3,100)");
            CountDownLatch started=new CountDownLatch(1);
            var result=pool.submit(()-> {try(var c=open()) {started.countDown(); return PostgresTableMutation.execute(c,request);} });
            assertTrue(started.await(3,TimeUnit.SECONDS));
            // The source writer holds ROW EXCLUSIVE; disposal cannot cross its write-freeze boundary.
            assertThrows(TimeoutException.class,()->result.get(300,TimeUnit.MILLISECONDS));
            writer.commit(); assertThrows(ExecutionException.class,()->result.get(15,TimeUnit.SECONDS));
        }
        assertTrue(exists("citizen"));
    }
    @Test void lostCommitResponseReconcilesReceiptWithoutRepeatingDdl() throws Exception {
        var request=request("RENAME"); AtomicBoolean first=new AtomicBoolean(true);
        try(var real=open()) {
            Connection lossy=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,method,args)-> {
                try { Object result=method.invoke(real,args); if (method.getName().equals("commit") && first.getAndSet(false)) throw new SQLException("simulated lost commit response"); return result; }
                catch(InvocationTargetException ex) {throw ex.getCause();}
            });
            assertThrows(SQLException.class,()->PostgresTableMutation.execute(lossy,request));
        }
        assertFalse(exists("citizen")); assertTrue(exists("retired"));
        assertThrows(SQLException.class,()->sql("INSERT INTO "+schema+".retired VALUES(8,20)"));
        assertThrows(SQLException.class,()->sql("TRUNCATE "+schema+".retired"));
        try(var c=open()) { assertNotNull(PostgresTableMutation.receipt(c,request.operationKey())); assertTrue(PostgresTableMutation.execute(c,request).replayed()); }
    }
    @Test void receiptFailureRollsBackDdlAndRetryCanCommit() throws Exception {
        var init=request("RENAME");try(var c=open()) {PostgresTableMutation.execute(c,init);}
        sql("ALTER TABLE "+schema+".retired RENAME TO citizen");var request=request("DROP");
        String trigger = "receipt_failure_"+schema, function = "fail_receipt_"+schema;
        sql("CREATE FUNCTION \"_yudao_control\"."+function+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'simulated receipt failure'; END $$;CREATE TRIGGER "+trigger+" BEFORE INSERT ON \"_yudao_control\".table_mutation_receipt_v1 FOR EACH ROW WHEN (NEW.operation_key='"+request.operationKey()+"') EXECUTE FUNCTION \"_yudao_control\"."+function+"()");
        try {
            try(var c=open()) {assertThrows(SQLException.class,()->PostgresTableMutation.execute(c,request)); assertNull(PostgresTableMutation.receipt(c,request.operationKey()));}
            assertTrue(exists("citizen"));
        } finally {sql("DROP TRIGGER "+trigger+" ON \"_yudao_control\".table_mutation_receipt_v1;DROP FUNCTION \"_yudao_control\"."+function+"()");}
        try(var c=open()) { assertFalse(PostgresTableMutation.execute(c,request).replayed()); }
        assertFalse(exists("citizen"));
    }
    @Test void twoWorkersUseOneAtomicReceiptAndChangedPlanCannotReplay() throws Exception {
        var request=request("RENAME");try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var a=pool.submit(()->{try(var c=open()){return PostgresTableMutation.execute(c,request);}});
            var b=pool.submit(()->{try(var c=open()){return PostgresTableMutation.execute(c,request);}});
            assertNotEquals(a.get(15,TimeUnit.SECONDS).replayed(),b.get(15,TimeUnit.SECONDS).replayed());
        }
        var changed=new PostgresTableMutation.Request(request.operationKey(),DigestUtil.sha256Hex("changed"),request.operation(),request.source(),"elsewhere",null);
        try(var c=open()) {assertThrows(SQLException.class,()->PostgresTableMutation.execute(c,changed));}
    }
    @Test void unprovenRlsRelationCannotBePrepared() throws Exception {
        sql("ALTER TABLE "+schema+".citizen ENABLE ROW LEVEL SECURITY");
        try(var c=open()) { assertThrows(SQLFeatureNotSupportedException.class,()->PostgresTableMutation.inspect(c,schema,"citizen")); }
    }
}
