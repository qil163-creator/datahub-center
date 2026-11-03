package cn.iocoder.yudao.framework.dbswitch.product.influxdb3;

import cn.iocoder.yudao.framework.dbswitch.common.config.*;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.recovery.PostgresTableRewriteStore;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.*;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.driver.DriverPoolFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Core mutations behind a response-loss proxy, with real PG durable recovery state.
 * This proves the neutral state machine, not yet MigrationService's Influx DIFF integration. */
@EnabledIfSystemProperty(named="datahub.test.influx.http",matches="http://127[.]0[.]0[.]1:[0-9]+")
@EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+")
@EnabledIfEnvironmentVariable(named="DATAHUB_INFLUX_TEST_TOKEN",matches="apiv3_.+")
@Timeout(70)
class TableRewriteInfluxFaultTest {
    private static final String SCOPE="a".repeat(64),RESOURCE="b".repeat(64),OWNER="table_owner_0001",NEXT_OWNER="table_owner_0002";
    private static final Instant TIME=Instant.parse("2026-10-04T00:00:00.123456789Z");
    private static final TableRewriteLimits LIMITS=new TableRewriteLimits(new BatchLimits(1,65536,32768,100,1000),20,20,1024*1024);
    private String database,schema,proxyBase;private HttpClient http;private HttpServer proxy;private ExecutorService proxyWorkers;
    private ConfiguredJdbcDrivers drivers;private HikariDataSource pool;private DriverManagerDataSource control;private PostgresTableRewriteStore store;
    private final AtomicReference<String> failOnce=new AtomicReference<>();private final AtomicInteger creates=new AtomicInteger(),drops=new AtomicInteger(),writes=new AtomicInteger();
    @BeforeEach void setup()throws Exception {
        http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();database="rewrite_"+UUID.randomUUID().toString().replace("-","");
        request(false,"POST","/api/v3/configure/database","{\"db\":\""+database+"\"}","application/json");create(false);
        request(false,"POST","/api/v3/write_lp?db="+database+"&precision=nanosecond","rows,host=old value=1i "+InfluxLineProtocol.nanos(TIME),"text/plain");
        var path=Path.of(Objects.requireNonNull(System.getProperty("datahub.test.influx.driver")));var sha=MessageDigest.getInstance("SHA-256");
        try(var input=Files.newInputStream(path)){byte[] bytes=new byte[8192];int count;while((count=input.read(bytes))!=-1)sha.update(bytes,0,count);}
        var spec=new DbSwitchProperties.Driver();spec.setPath(path.toAbsolutePath().toString());spec.setSha256(HexFormat.of().formatHex(sha.digest()));spec.setDriverClassName(ProductTypeEnum.INFLUXDB3.getDriveClassName());
        drivers=new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.INFLUXDB3,spec));var props=new TargetDataSourceProperties();props.setTargetProductType(ProductTypeEnum.INFLUXDB3);
        props.setUrl("jdbc:arrow-flight-sql://127.0.0.1:"+URI.create(base()).getPort()+"/?useEncryption=0&database="+database);props.setPassword(token());props.setPoolSize(1);
        pool=DataSourceUtils.createTargetDataSource(props,null,null,drivers.find(ProductTypeEnum.INFLUXDB3).orElseThrow(),drivers.runtimes());
        Class.forName("org.postgresql.Driver");control=new DriverManagerDataSource(System.getProperty("datahub.test.postgres.url"),System.getProperty("datahub.test.postgres.user","dataforge_test"),"");
        schema="influx_rewrite_"+UUID.randomUUID().toString().replace("-","");sql("CREATE SCHEMA "+schema);
        for(String ddl:PostgresTableRewriteStore.schemaStatements(schema+".journal",schema+".pages"))sql(ddl);
        byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);
        store=new PostgresTableRewriteStore(control,schema+".journal",schema+".pages",SCOPE,"fixture",Map.of("fixture",new SecretKeySpec(key,"AES")),LIMITS,10,(c,k)->{});
        proxy=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);proxyWorkers=Executors.newFixedThreadPool(2);proxy.setExecutor(proxyWorkers);
        proxy.createContext("/",exchange->{
            try(exchange) {
                String method=exchange.getRequestMethod(),pathAndQuery=exchange.getRequestURI().toString(),pathOnly=exchange.getRequestURI().getPath();
                String operation=method+" "+pathOnly;
                if(operation.equals("POST /api/v3/configure/table"))creates.incrementAndGet();
                if(operation.equals("DELETE /api/v3/configure/table"))drops.incrementAndGet();
                if(operation.equals("POST /api/v3/write_lp"))writes.incrementAndGet();
                byte[] body=exchange.getRequestBody().readNBytes(1024*1024+1);if(body.length>1024*1024)throw new IOException("fixture request budget");
                var response=http.send(HttpRequest.newBuilder(URI.create(base()+pathAndQuery)).timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+token())
                        .header("Content-Type",exchange.getRequestHeaders().getFirst("Content-Type")).method(method,HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofByteArray());
                String armed=failOnce.get();boolean lose=response.statusCode()>=200&&response.statusCode()<300&&operation.equals(armed)&&failOnce.compareAndSet(armed,null);
                byte[] output=lose?new byte[0]:response.body();response.headers().firstValue("cluster-uuid").ifPresent(v->exchange.getResponseHeaders().set("cluster-uuid",v));
                exchange.sendResponseHeaders(lose?503:response.statusCode(),output.length==0?-1:output.length);if(output.length>0)exchange.getResponseBody().write(output);
            }catch(Exception failure){exchange.close();}
        });proxy.start();proxyBase="http://127.0.0.1:"+proxy.getAddress().getPort();
    }
    @AfterEach void cleanup()throws Exception {
        try {
            if(proxy!=null)proxy.stop(0);if(proxyWorkers!=null){proxyWorkers.shutdownNow();assertTrue(proxyWorkers.awaitTermination(10,TimeUnit.SECONDS));}
            if(pool!=null){var receipt=DriverPoolFactory.physicalTermination(pool);pool.close();receipt.toCompletableFuture().get(10,TimeUnit.SECONDS);}
            if(drivers!=null)drivers.close();
        }finally {
            try{if(control!=null&&schema!=null)sql("DROP SCHEMA "+schema+" CASCADE");}
            finally{if(http!=null)try(var owned=http){if(database!=null)request(false,"DELETE","/api/v3/configure/database?db="+database,"","application/json");}}
        }
    }
    @Test void normalExecutionCommitsCompleteAfterImageAndNativeGenerationBeforeApplied()throws Exception {
        var material=material();var key=key();store.prepare(key,OWNER,material);
        var result=new TableRewriteExecution(store,()->{}).executePrepared(key,OWNER,target());
        assertEquals(TableRewriteRecord.Phase.APPLIED,result.phase());assertEquals(identity(),result.newIdentity());assertNotEquals(material.oldIdentity(),result.newIdentity());
        assertEquals(2,query("SELECT * FROM rows").size());assertEquals(1,creates.get());assertEquals(1,drops.get());assertTrue(store.pending(SCOPE,RESOURCE,null,10).keys().isEmpty());
    }
    @Test void createResponseLostAfterActualCreationRetainsUnknownAndRejectsSameNameAdoption()throws Exception {
        var material=material();var key=key();store.prepare(key,OWNER,material);failOnce.set("POST /api/v3/configure/table");
        assertThrows(UnconfirmedWriteException.class,()->new TableRewriteExecution(store,()->{}).executePrepared(key,OWNER,target()));
        var unknown=store.read(key).orElseThrow();assertEquals(TableRewriteRecord.Phase.CREATE_UNKNOWN,unknown.phase());assertNull(unknown.newIdentity());
        assertNotEquals(material.oldIdentity(),identity(),"Server created a real different generation before the proxy lost its response");
        assertEquals(0,query("SELECT * FROM rows").size());assertEquals(1,creates.get());assertEquals(0,writes.get());
        assertTrue(store.claimRecovery(key,unknown.revision(),NEXT_OWNER).isEmpty());
        assertThrows(IllegalStateException.class,()->new TableRewriteExecution(store,()->{}).reconcileClaimed(unknown,target()));
        assertEquals(1,creates.get());assertEquals(List.of(key),store.pending(SCOPE,RESOURCE,null,10).keys());assertEquals(material.hash(),unknown.material().hash());
    }
    @Test void dropResponseLostCannotAdvanceToCreateOrReleaseOccupiedResource()throws Exception {
        var key=key();store.prepare(key,OWNER,material());failOnce.set("DELETE /api/v3/configure/table");
        assertThrows(UnconfirmedWriteException.class,()->new TableRewriteExecution(store,()->{}).executePrepared(key,OWNER,target()));
        var unknown=store.read(key).orElseThrow();assertEquals(TableRewriteRecord.Phase.DROP_UNKNOWN,unknown.phase());
        assertEquals(0,query("SELECT table_name FROM information_schema.tables WHERE table_schema='iox' AND table_name='rows'").size());
        assertEquals(0,creates.get());assertTrue(store.claimRecovery(key,unknown.revision(),NEXT_OWNER).isEmpty());assertEquals(List.of(key),store.pending(SCOPE,RESOURCE,null,10).keys());
    }
    @Test void acknowledgedGenerationAllowsExplicitRecoveryAfterPartialWriteWithoutAnotherDrop()throws Exception {
        var key=key();store.prepare(key,OWNER,material());failOnce.set("POST /api/v3/write_lp");
        assertThrows(UnconfirmedWriteException.class,()->new TableRewriteExecution(store,()->{}).executePrepared(key,OWNER,target()));
        var pending=store.read(key).orElseThrow();assertEquals(TableRewriteRecord.Phase.RESTORE_INTENT,pending.phase());assertEquals(identity(),pending.newIdentity());
        // This fixture's synchronous proxy handler has returned and all its target calls have completed.
        var claimed=store.claimRecovery(key,pending.revision(),NEXT_OWNER).orElseThrow();
        var applied=new TableRewriteExecution(store,()->{}).reconcileClaimed(claimed,target());assertEquals(TableRewriteRecord.Phase.APPLIED,applied.phase());
        assertEquals(1,creates.get());assertEquals(1,drops.get());assertEquals(3,writes.get());assertEquals(2,query("SELECT * FROM rows").size());
    }
    @Test void rebuiltGenerationAfterFailedRestoreRejectsReplayAndPreservesForeignRows()throws Exception {
        var key=key();store.prepare(key,OWNER,material());failOnce.set("POST /api/v3/write_lp");
        assertThrows(UnconfirmedWriteException.class,()->new TableRewriteExecution(store,()->{}).executePrepared(key,OWNER,target()));
        request(false,"DELETE","/api/v3/configure/table?db="+database+"&table=rows","","application/json");create(false);
        request(false,"POST","/api/v3/write_lp?db="+database+"&precision=nanosecond","rows,host=foreign value=99i "+InfluxLineProtocol.nanos(TIME),"text/plain");
        var prior=store.read(key).orElseThrow();var claim=store.claimRecovery(key,prior.revision(),NEXT_OWNER).orElseThrow();
        assertThrows(UnconfirmedWriteException.class,()->new TableRewriteExecution(store,()->{}).reconcileClaimed(claim,target()));
        assertEquals(1,writes.get());assertEquals("foreign",query("SELECT * FROM rows").get(0).get("host").asText());assertFalse(store.pending(SCOPE,RESOURCE,null,10).keys().isEmpty());
    }
    private TableRewriteMaterial material()throws Exception {
        var definition=InfluxNativeTables.describe(pool,"iox","rows",1024*1024,10);
        return TableRewriteMaterial.create("c".repeat(64),"iox","rows",identity(),definition,List.of("host","time"),
                Collections.singletonList(new Object[]{"old",TIME,1L}),List.of(new Object[]{"new",TIME,2L},new Object[]{"next",TIME.plusNanos(1),3L}),LIMITS);
    }
    private TableRewriteExecution.Target target() {
        return new TableRewriteExecution.Target() {
            @Override public void verifyBefore(TableRewriteMaterial m)throws Exception{assertEquals(m.oldIdentity(),identity());assertEquals(1,query("SELECT * FROM rows").size());}
            @Override public void drop(TableRewriteMaterial m)throws Exception{request(true,"DELETE","/api/v3/configure/table?db="+database+"&table=rows","","application/json");assertEquals(0,query("SELECT table_name FROM information_schema.tables WHERE table_schema='iox' AND table_name='rows'").size());}
            @Override public TableRewriteIdentity create(TableRewriteMaterial m)throws Exception{TableRewriteInfluxFaultTest.this.create(true);return identity();}
            @Override public void verifyGeneration(TableRewriteMaterial m,TableRewriteIdentity pinned)throws Exception{if(!pinned.equals(identity()))throw new IllegalStateException("Native generation changed");}
            @Override public void restore(TableRewriteMaterial m,TableRewriteIdentity pinned,TableRewriteMaterial.Page page)throws Exception {
                InfluxNativeCatalog.Table table;try(var c=pool.getConnection()){table=InfluxNativeCatalog.require(InfluxNativeSession.open(pool,c,1024*1024,10),"rows");}
                byte[] bytes=InfluxLineProtocol.batch(table,m.fields(),m.rows(page,LIMITS),LIMITS.page().maxBatchBytes());
                request(true,"POST","/api/v3/write_lp?db="+database+"&precision=nanosecond&accept_partial=false",new String(bytes,java.nio.charset.StandardCharsets.UTF_8),"text/plain");
            }
            @Override public void verifyAfter(TableRewriteMaterial m,TableRewriteIdentity pinned)throws Exception {
                var rows=query("SELECT host,CAST(time AS BIGINT) AS epoch_nanos,value FROM rows ORDER BY host");assertEquals(2,rows.size());assertEquals("new",rows.get(0).get("host").asText());assertEquals(2,rows.get(0).get("value").longValue());
                assertEquals("next",rows.get(1).get("host").asText());assertEquals(3,rows.get(1).get("value").longValue());
                assertEquals(InfluxLineProtocol.nanos(TIME),rows.get(0).get("epoch_nanos").longValue());assertEquals(InfluxLineProtocol.nanos(TIME.plusNanos(1)),rows.get(1).get("epoch_nanos").longValue());
            }
        };
    }
    private TableRewriteIdentity identity()throws Exception {
        try(var c=pool.getConnection()) {
            var session=InfluxNativeSession.open(pool,c,1024*1024,10);var id=InfluxNativeIdentity.capture(session,InfluxNativeCatalog.require(session,"rows"),InfluxNativeIdentity.ManagementProbePermission.EXCLUSIVE_TARGET_REPLACEMENT,ignored->{});
            return new TableRewriteIdentity("influx-catalog-v1",id.clusterUuid()+":"+id.databaseId()+":"+id.tableId());
        }
    }
    private TableRewriteKey key(){return new TableRewriteKey(SCOPE,RESOURCE,"d".repeat(64));}
    private void create(boolean viaProxy)throws Exception{request(viaProxy,"POST","/api/v3/configure/table","{\"db\":\""+database+"\",\"table\":\"rows\",\"tags\":[\"host\"],\"fields\":[{\"name\":\"value\",\"type\":\"int64\"}]}","application/json");}
    private JsonNode query(String sql)throws Exception{return InfluxNativeSession.JSON.readTree(request(false,"POST","/api/v3/query_sql",InfluxNativeSession.JSON.createObjectNode().put("db",database).put("q",sql).put("format","json").toString(),"application/json"));}
    private String request(boolean proxy,String method,String path,String body,String contentType)throws Exception {
        var result=http.send(HttpRequest.newBuilder(URI.create((proxy?proxyBase:base())+path)).timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+token()).header("Content-Type",contentType).method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        if(result.statusCode()<200||result.statusCode()>=300)throw new IOException("Influx fixture request was not acknowledged; status="+result.statusCode());return result.body();
    }
    private void sql(String statement)throws Exception{try(var c=control.getConnection();var s=c.createStatement()){s.execute(statement);}}
    private static String token(){return System.getenv("DATAHUB_INFLUX_TEST_TOKEN");}private static String base(){return System.getProperty("datahub.test.influx.http");}
}
