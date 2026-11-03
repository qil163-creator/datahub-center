package cn.iocoder.yudao.framework.dbswitch.product.influxdb3;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import cn.iocoder.yudao.framework.dbswitch.driver.DriverPoolFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="datahub.test.influx.http",matches="http://127[.]0[.]0[.]1:[0-9]+")
@EnabledIfEnvironmentVariable(named="DATAHUB_INFLUX_TEST_TOKEN",matches="apiv3_.+")
@Timeout(60)
class InfluxNativeIdentityInfluxTest {
    private String database;private HttpClient http;private ConfiguredJdbcDrivers drivers;private HikariDataSource pool;
    @BeforeEach void setup()throws Exception {
        http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();database="identity_"+UUID.randomUUID().toString().replace("-","");
        json("POST","/api/v3/configure/database","{\"db\":\""+database+"\"}");
        var path=Path.of(Objects.requireNonNull(System.getProperty("datahub.test.influx.driver")));var sha=MessageDigest.getInstance("SHA-256");
        try(var input=Files.newInputStream(path)){byte[] bytes=new byte[8192];int length;while((length=input.read(bytes))!=-1)sha.update(bytes,0,length);}
        var spec=new DbSwitchProperties.Driver();spec.setPath(path.toAbsolutePath().toString());spec.setSha256(HexFormat.of().formatHex(sha.digest()));spec.setDriverClassName(ProductTypeEnum.INFLUXDB3.getDriveClassName());
        drivers=new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.INFLUXDB3,spec));pool=pool("127.0.0.1");create("source_rows");create("target_rows");
        request("POST","/api/v3/write_lp?db="+database+"&precision=nanosecond","source_rows,host=a value=1i 1790985600123456789\ntarget_rows,host=b value=2i 1790985600123456789","text/plain");
    }
    @AfterEach void cleanup()throws Exception {
        try{if(pool!=null)close(pool);if(drivers!=null){assertEquals(0,drivers.runtimes().connections().snapshot().workload());drivers.close();}}
        finally{if(http!=null)try(var owned=http){if(database!=null)json("DELETE","/api/v3/configure/database?db="+database,"");}}
    }
    @Test void managementProbeRemovesOnlyItsOwnCacheAndDoesNotChangeRows()throws Exception {
        json("POST","/api/v3/configure/last_cache","{\"db\":\""+database+"\",\"table\":\"target_rows\",\"name\":\"existing_cache\",\"key_columns\":[\"host\"],\"value_columns\":[\"value\"],\"count\":1,\"ttl\":1}");
        var before=query("SELECT * FROM target_rows");var events=new ArrayList<Optional<InfluxNativeIdentity.CacheResource>>();
        try(var connection=pool.getConnection()) {
            var session=InfluxNativeSession.open(pool,connection,1024*1024,10);var table=InfluxNativeCatalog.require(session,"target_rows");
            assertThrows(IllegalArgumentException.class,()->InfluxNativeIdentity.capture(session,table,InfluxNativeIdentity.ManagementProbePermission.DISABLED,events::add));assertTrue(events.isEmpty());
            var identity=InfluxNativeIdentity.capture(session,table,InfluxNativeIdentity.ManagementProbePermission.EXCLUSIVE_TARGET_REPLACEMENT,events::add);
            assertEquals(64,identity.fingerprint().length());assertTrue(events.getFirst().isPresent());assertTrue(events.getLast().isEmpty());
            assertEquals(identity,events.get(1).orElseThrow().observedIdentity());
        }
        assertEquals(before,query("SELECT * FROM target_rows"));var caches=query("SELECT name FROM system.last_caches");assertEquals(1,caches.size());assertEquals("existing_cache",caches.get(0).get("name").asText());
    }
    @Test void actualDropAndIdenticalRecreateChangeTableGenerationWhileClusterAndDatabaseStayPinned()throws Exception {
        var first=capture("target_rows");json("DELETE","/api/v3/configure/table?db="+database+"&table=target_rows","");create("target_rows");var second=capture("target_rows");
        assertEquals(first.clusterUuid(),second.clusterUuid());assertEquals(first.databaseId(),second.databaseId());assertNotEquals(first.tableId(),second.tableId());assertNotEquals(first.fingerprint(),second.fingerprint());
        assertEquals(0,query("SELECT * FROM system.last_caches").size());
    }
    @Test void readOnlySourceAndHttpAliasCannotAuthorizeReplacingTheSamePhysicalScope()throws Exception {
        var source=InfluxNativeTables.describe(pool,"iox","source_rows",1024*1024,10);var mapping=new LinkedHashMap<String,String>();source.fields().forEach(field->mapping.put(field,field));
        assertTrue(UUID.fromString(InfluxNativeSession.JSON.readTree(source.document()).get("sourceCluster").asText()).version()>0);
        try(var alias=pool("localhost")) {
            assertThrows(IllegalStateException.class,()->InfluxNativeTables.plan(alias,source,"iox","source_rows",mapping,true,1024*1024,10));
            assertEquals(0,query("SELECT * FROM system.last_caches").size());assertEquals(1,query("SELECT * FROM source_rows").size());
        }
    }
    @Test void exclusiveReplacementActuallyCapturesOldAndNewNativeGenerationsAndLeavesNoProbeCache()throws Exception {
        var source=InfluxNativeTables.describe(pool,"iox","source_rows",1024*1024,10);var mapping=new LinkedHashMap<String,String>();source.fields().forEach(field->mapping.put(field,field));
        var plan=InfluxNativeTables.plan(pool,source,"iox","target_rows",mapping,true,1024*1024,10);
        assertTrue(plan.requiresExclusiveDdlOwnership());assertEquals(0,query("SELECT * FROM system.last_caches").size());
        plan.execute();var identities=plan.recoveryResources().stream().map(resource->resource.expectedPhysicalIdentity()).toList();
        assertEquals(2,identities.size());assertTrue(identities.stream().allMatch(value->value!=null&&value.length()==64));assertNotEquals(identities.get(0),identities.get(1));
        assertEquals(capture("target_rows").fingerprint(),identities.get(1));assertEquals(0,query("SELECT * FROM system.last_caches").size());
        assertEquals(0,query("SELECT * FROM target_rows").size());assertEquals(1,query("SELECT * FROM source_rows").size());
    }
    private InfluxNativeIdentity.Identity capture(String table)throws Exception {
        try(var connection=pool.getConnection()){var session=InfluxNativeSession.open(pool,connection,1024*1024,10);return InfluxNativeIdentity.capture(session,InfluxNativeCatalog.require(session,table),InfluxNativeIdentity.ManagementProbePermission.EXCLUSIVE_TARGET_REPLACEMENT,ignored->{});}
    }
    private HikariDataSource pool(String host) {
        var p=new TargetDataSourceProperties();p.setTargetProductType(ProductTypeEnum.INFLUXDB3);p.setUrl("jdbc:arrow-flight-sql://"+host+":"+URI.create(System.getProperty("datahub.test.influx.http")).getPort()+"/?useEncryption=0&database="+database);p.setPassword(token());p.setPoolSize(1);
        return DataSourceUtils.createTargetDataSource(p,null,null,drivers.find(ProductTypeEnum.INFLUXDB3).orElseThrow(),drivers.runtimes());
    }
    private static void close(HikariDataSource pool)throws Exception {var exit=DriverPoolFactory.physicalTermination(pool);pool.close();exit.toCompletableFuture().get(5,TimeUnit.SECONDS);}
    private void create(String name)throws Exception {json("POST","/api/v3/configure/table","{\"db\":\""+database+"\",\"table\":\""+name+"\",\"tags\":[\"host\"],\"fields\":[{\"name\":\"value\",\"type\":\"int64\"}]}");}
    private JsonNode query(String sql)throws Exception {return json("POST","/api/v3/query_sql",InfluxNativeSession.JSON.createObjectNode().put("db",database).put("q",sql).put("format","json").toString());}
    private JsonNode json(String method,String path,String body)throws Exception {String result=request(method,path,body,"application/json");return result.isEmpty()?InfluxNativeSession.JSON.nullNode():InfluxNativeSession.JSON.readTree(result);}
    private String request(String method,String path,String body,String contentType)throws Exception {
        var response=http.send(HttpRequest.newBuilder(URI.create(System.getProperty("datahub.test.influx.http")+path)).timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+token()).header("Content-Type",contentType).method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode()>=200&&response.statusCode()<300,()->"Influx fixture status="+response.statusCode());return response.body();
    }
    private static String token(){return System.getenv("DATAHUB_INFLUX_TEST_TOKEN");}
}
