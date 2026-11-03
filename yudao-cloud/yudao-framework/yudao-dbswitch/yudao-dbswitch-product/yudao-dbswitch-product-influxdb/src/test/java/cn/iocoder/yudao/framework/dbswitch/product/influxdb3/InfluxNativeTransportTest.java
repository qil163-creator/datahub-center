package cn.iocoder.yudao.framework.dbswitch.product.influxdb3;

import cn.iocoder.yudao.framework.dbswitch.driver.DriverNativeAccess;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.influxdb.InfluxdbWriterImpl;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** HTTP framing tests; actual native deadline/permit accounting is covered by InfluxDriverBoundaryTest. */
@Timeout(15)
class InfluxNativeTransportTest {
    private HttpServer server;private HikariDataSource pool;private Connection connection;
    @BeforeEach void setup()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.start();
        pool=mock(HikariDataSource.class);connection=mock(Connection.class,withSettings().extraInterfaces(DriverNativeAccess.ConnectionAccess.class));
        when(pool.getJdbcUrl()).thenReturn(url("database=test&useEncryption=0"));when(pool.getPassword()).thenReturn(UUID.randomUUID().toString());
        when(pool.getConnection()).thenReturn(connection);
        when(((DriverNativeAccess.ConnectionAccess)connection).invokeNative(any(),anyLong())).thenAnswer(call->{
            try{return ((Callable<?>)call.getArgument(0)).call();}catch(SQLException failure){throw failure;}catch(Exception failure){throw new SQLException("Native fixture request failed");}
        });
    }
    @AfterEach void stop(){server.stop(0);}

    @Test void wholeResponsesAreBoundedAndInvalidJsonIsRejectedWithoutExposingTheirPayload()throws Exception {
        server.createContext("/api/v3/query_sql",exchange->{byte[] bytes="private-response".repeat(20).getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}});
        var session=InfluxNativeSession.open(pool,connection,32,2);
        var failure=assertThrows(SQLException.class,()->session.query("SELECT 1"));assertFalse(failure.toString().contains("private-response"));
        server.removeContext("/api/v3/query_sql");server.createContext("/api/v3/query_sql",exchange->{byte[] bytes="private-invalid-json".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}});
        failure=assertThrows(SQLException.class,()->session.query("SELECT 1"));assertNull(failure.getCause());assertFalse(failure.toString().contains("private-invalid-json"));
    }

    @Test void redirectIsNotFollowedAndRejectedWriteIsNotRetried()throws Exception {
        var original=new AtomicInteger();var redirected=new AtomicInteger();
        server.createContext("/api/v3/write_lp",exchange->{original.incrementAndGet();exchange.getResponseHeaders().add("Location","/api/v3/other");exchange.sendResponseHeaders(307,-1);exchange.close();});
        server.createContext("/api/v3/other",exchange->{redirected.incrementAndGet();exchange.sendResponseHeaders(204,-1);exchange.close();});
        assertThrows(SQLException.class,()->InfluxNativeSession.open(pool,connection,1024,2).write("m value=1i 1".getBytes(StandardCharsets.UTF_8)));
        assertEquals(1,original.get());assertEquals(0,redirected.get());
    }

    @Test void postSendFailureIsUnknownAndTheWriterNeverReplaysTheBatch()throws Exception {
        var writes=new AtomicInteger();
        server.createContext("/api/v3/query_sql",exchange->{
            String query=InfluxNativeSession.JSON.readTree(exchange.getRequestBody()).path("q").asText();
            String response=query.contains("information_schema.tables")?"[{\"table_name\":\"target\"}]":"[{\"key\":\"time\",\"data_type\":\"time\"},{\"key\":\"value\",\"data_type\":\"integer\"}]";
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
        });
        server.createContext("/api/v3/write_lp",exchange->{writes.incrementAndGet();exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(503,-1);exchange.close();});
        var writer=new InfluxdbWriterImpl(pool);writer.prepareWrite("iox","target",List.of("time","value"));
        assertThrows(UnconfirmedWriteException.class,()->writer.write(List.of("time","value"),List.<Object[]>of(new Object[]{java.time.Instant.EPOCH,1L})));
        assertEquals(1,writes.get());verify(connection,atLeast(2)).close();
    }

    @Test void duplicateEncodedEndpointParametersAndCredentialsInTheUrlFailBeforeOpeningTransport()throws Exception {
        for(String query:List.of("database=a&DATABASE=b","database=a&%64atabase=b","database=a&password=hidden","database=a&useEncryption=0&useencryption=1")) {
            when(pool.getJdbcUrl()).thenReturn(url(query));
            var error=assertThrows(IllegalArgumentException.class,()->InfluxNativeSession.open(pool,connection,1024,2));assertFalse(error.toString().contains("hidden"));
        }
        verify((DriverNativeAccess.ConnectionAccess)connection,never()).invokeNative(any(),anyLong());
    }

    @Test void callerOwnedConnectionCannotBecomeAnUnboundedNativeTransport()throws Exception {
        var callerOwned=mock(Connection.class);var session=InfluxNativeSession.open(pool,callerOwned,1024,2);
        assertThrows(SQLFeatureNotSupportedException.class,()->session.query("SELECT 1"));
    }

    @Test void managementProbeCleanupFailureRetainsExactOwnedResourceAndNeverRetries()throws Exception {
        var sends=new AtomicInteger();var deletes=new AtomicInteger();var name=new java.util.concurrent.atomic.AtomicReference<String>();
        var evidence=new java.util.concurrent.atomic.AtomicReference<Optional<InfluxNativeIdentity.CacheResource>>(Optional.empty());
        String cluster=UUID.randomUUID().toString();
        server.createContext("/api/v3/query_sql",exchange->{exchange.getRequestBody().readAllBytes();reply(exchange,200,"[]",cluster);});
        server.createContext("/api/v3/configure/last_cache",exchange->{
            var body=InfluxNativeSession.JSON.readTree(exchange.getRequestBody());
            if(exchange.getRequestMethod().equals("POST")){sends.incrementAndGet();name.set(body.get("name").asText());reply(exchange,201,cacheCommit(name.get()),cluster);}
            else {deletes.incrementAndGet();assertEquals(name.get(),body.get("name").asText());assertEquals("target",body.get("table").asText());reply(exchange,503,"",cluster);}
        });
        var session=InfluxNativeSession.open(pool,connection,4096,2);
        assertThrows(UnconfirmedWriteException.class,()->InfluxNativeIdentity.capture(session,probeTable(),InfluxNativeIdentity.ManagementProbePermission.EXCLUSIVE_TARGET_REPLACEMENT,evidence::set));
        assertEquals(1,sends.get());assertEquals(1,deletes.get());var retained=evidence.get().orElseThrow();assertEquals(name.get(),retained.cacheName());assertEquals("target",retained.table());assertNotNull(retained.observedIdentity());assertTrue(retained.recoveryName().contains(name.get()));
    }

    @Test void managementProbeConflictCannotAuthorizeDeletingTheConflictingCache()throws Exception {
        var deletes=new AtomicInteger();var evidence=new java.util.concurrent.atomic.AtomicReference<Optional<InfluxNativeIdentity.CacheResource>>(Optional.empty());String cluster=UUID.randomUUID().toString();
        server.createContext("/api/v3/query_sql",exchange->{exchange.getRequestBody().readAllBytes();reply(exchange,200,"[]",cluster);});
        server.createContext("/api/v3/configure/last_cache",exchange->{exchange.getRequestBody().readAllBytes();if(exchange.getRequestMethod().equals("DELETE"))deletes.incrementAndGet();reply(exchange,409,"",cluster);});
        assertThrows(UnconfirmedWriteException.class,()->InfluxNativeIdentity.capture(InfluxNativeSession.open(pool,connection,4096,2),probeTable(),InfluxNativeIdentity.ManagementProbePermission.EXCLUSIVE_TARGET_REPLACEMENT,evidence::set));
        assertEquals(0,deletes.get());assertTrue(evidence.get().isPresent());assertNull(evidence.get().orElseThrow().observedIdentity());
    }

    @Test void managementProbeNeedsExplicitPermitAndAStableServerCatalogUuid()throws Exception {
        var requests=new AtomicInteger();var cluster=new java.util.concurrent.atomic.AtomicReference<>(UUID.randomUUID().toString());
        server.createContext("/api/v3/query_sql",exchange->{requests.incrementAndGet();exchange.getRequestBody().readAllBytes();reply(exchange,200,"[]",cluster.get());});
        var session=InfluxNativeSession.open(pool,connection,4096,2);
        assertThrows(IllegalArgumentException.class,()->InfluxNativeIdentity.capture(session,probeTable(),InfluxNativeIdentity.ManagementProbePermission.DISABLED,ignored->{}));assertEquals(0,requests.get());
        assertEquals(cluster.get(),session.clusterIdentity());cluster.set(UUID.randomUUID().toString());
        assertThrows(SQLException.class,()->session.query("SELECT 1"));assertEquals(2,requests.get());
        assertNotEquals(new InfluxNativeIdentity.Identity(UUID.randomUUID().toString(),1,1).fingerprint(),new InfluxNativeIdentity.Identity(UUID.randomUUID().toString(),1,1).fingerprint());
    }
    @Test void createdStatusForAnotherResourceDoesNotAuthorizeCleanup()throws Exception {
        var deletes=new AtomicInteger();var evidence=new java.util.concurrent.atomic.AtomicReference<Optional<InfluxNativeIdentity.CacheResource>>(Optional.empty());String cluster=UUID.randomUUID().toString();
        server.createContext("/api/v3/query_sql",exchange->{exchange.getRequestBody().readAllBytes();reply(exchange,200,"[]",cluster);});
        server.createContext("/api/v3/configure/last_cache",exchange->{exchange.getRequestBody().readAllBytes();if(exchange.getRequestMethod().equals("DELETE"))deletes.incrementAndGet();reply(exchange,201,cacheCommit("another_resource"),cluster);});
        assertThrows(UnconfirmedWriteException.class,()->InfluxNativeIdentity.capture(InfluxNativeSession.open(pool,connection,4096,2),probeTable(),InfluxNativeIdentity.ManagementProbePermission.EXCLUSIVE_TARGET_REPLACEMENT,evidence::set));
        assertEquals(0,deletes.get());assertTrue(evidence.get().isPresent());assertNull(evidence.get().orElseThrow().observedIdentity());
    }
    private static InfluxNativeCatalog.Table probeTable(){return new InfluxNativeCatalog.Table("target",List.of(new InfluxNativeCatalog.Column("time",InfluxNativeCatalog.Kind.TIME),new InfluxNativeCatalog.Column("value",InfluxNativeCatalog.Kind.INT64)));}
    @Test void nativeEvolutionRejectsOldServerVersionWithoutAnyManagementWrite()throws Exception {
        String cluster=UUID.randomUUID().toString();var writes=new AtomicInteger();
        server.createContext("/ping",exchange->reply(exchange,200,"{\"product_name\":\"InfluxDB 3 Core\",\"version\":\"3.11.5\"}",cluster));
        server.createContext("/api/v3/",exchange->{writes.incrementAndGet();reply(exchange,500,"",cluster);});
        assertThrows(IllegalStateException.class,()->InfluxNativeEvolution.plan(pool,evolutionDefinition(),"iox","target",4096,2));
        assertEquals(0,writes.get());
    }
    @Test void rejectedOrUnverifiablePatchIsUnknownAndNeverReplayed()throws Exception {
        String cluster=UUID.randomUUID().toString();var writes=new AtomicInteger();var status=new AtomicInteger(503);
        server.createContext("/ping",exchange->reply(exchange,200,"{\"product_name\":\"InfluxDB 3 Core\",\"version\":\"3.12.0\"}",cluster));
        server.createContext("/api/v3/query_influxql",exchange->{exchange.getRequestBody().readAllBytes();reply(exchange,200,"[{\"name\":\"autogen\",\"duration\":\"0s\"}]",cluster);});
        server.createContext("/api/v3/query_sql",exchange->{
            String sql=InfluxNativeSession.JSON.readTree(exchange.getRequestBody()).path("q").asText();
            String result=sql.contains("information_schema.tables")?"[{\"table_name\":\"target\"}]":sql.contains("influxdb_schema")?"[{\"key\":\"time\",\"data_type\":\"time\"},{\"key\":\"value\",\"data_type\":\"integer\"}]":"[]";
            reply(exchange,200,result,cluster);
        });
        server.createContext("/api/v3/configure/last_cache",exchange->{
            var body=InfluxNativeSession.JSON.readTree(exchange.getRequestBody());
            boolean create=exchange.getRequestMethod().equals("POST");
            reply(exchange,create?201:200,create?cacheCommit(body.get("name").asText()):"",cluster);
        });
        server.createContext("/api/v3/configure/table",exchange->{writes.incrementAndGet();exchange.getRequestBody().readAllBytes();reply(exchange,status.get(),"",cluster);});
        for(int code:List.of(503,200)) {
            status.set(code);var plan=InfluxNativeEvolution.plan(pool,evolutionDefinition(),"iox","target",4096,2);
            assertThrows(UnconfirmedWriteException.class,plan::execute);assertThrows(IllegalStateException.class,plan::execute);
            assertNotNull(plan.recoveryResources().getFirst().expectedPhysicalIdentity());assertEquals(1,plan.recoveryResources().size());
        }
        assertEquals(2,writes.get());
    }
    private static cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition evolutionDefinition(){
        return new cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition(
                cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.INFLUXDB3,"influxdb3-core-columns-v1",
                "{\"retention\":\"0s\",\"columns\":[{\"name\":\"new_field\",\"kind\":\"STRING\"},{\"name\":\"time\",\"kind\":\"TIME\"},{\"name\":\"value\",\"kind\":\"INT64\"}]}",List.of("new_field","time","value"));
    }
    private static String cacheCommit(String name){return "{\"catalog_batch\":{\"Database\":{\"database_name\":\"test\",\"database_id\":1,\"ops\":[{\"CreateLastCache\":{\"table\":\"target\",\"name\":\""+name+"\",\"table_id\":2,\"id\":3}}]}}}";}
    private static void reply(com.sun.net.httpserver.HttpExchange exchange,int status,String body,String cluster)throws java.io.IOException {
        exchange.getResponseHeaders().add("cluster-uuid",cluster);byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length==0?-1:bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}
    }
    private String url(String parameters){return "jdbc:arrow-flight-sql://127.0.0.1:"+server.getAddress().getPort()+"/?"+parameters;}
}
