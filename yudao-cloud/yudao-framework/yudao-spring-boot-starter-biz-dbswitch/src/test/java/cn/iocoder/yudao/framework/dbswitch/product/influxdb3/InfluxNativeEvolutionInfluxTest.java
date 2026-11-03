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
class InfluxNativeEvolutionInfluxTest {
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
    @Test void publicOperatorAddsAllNativeFieldKindsAndTagWithoutReplacingRowsOrIdentity()throws Exception {
        json("PATCH","/api/v3/configure/table","{\"db\":\""+database+"\",\"table\":\"source_rows\",\"tags\":[\"rack\"],\"fields\":[{\"name\":\"u\",\"type\":\"uint64\"},{\"name\":\"f\",\"type\":\"float64\"},{\"name\":\"s\",\"type\":\"utf8\"},{\"name\":\"b\",\"type\":\"bool\"}]}");
        var before=query("SELECT host,time,value FROM target_rows");var identity=capture("target_rows");
        try(var runtime=cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime.standalone()) {
            var operator=runtime.reader(pool,ProductTypeEnum.INFLUXDB3);
            var source=operator.describeNativeTable("iox","source_rows");
            var plan=operator.prepareNativeSchemaEvolution(source,"iox","target_rows");
            assertEquals(64,plan.digest().length());assertFalse(plan.replacesExisting());assertTrue(plan.requiresExclusiveDdlOwnership());
            assertEquals(before,query("SELECT host,time,value FROM target_rows"));assertEquals(0,query("SELECT * FROM system.last_caches").size());
            plan.execute();assertEquals(source.fields(),operator.describeNativeTable("iox","target_rows").fields());
            assertEquals(before,query("SELECT host,time,value FROM target_rows"));assertEquals(identity,capture("target_rows"));
            assertEquals(identity.fingerprint(),plan.recoveryResources().getFirst().expectedPhysicalIdentity());
            assertEquals(0,query("SELECT * FROM system.last_caches").size());assertThrows(IllegalStateException.class,plan::execute);
            var noop=operator.prepareNativeSchemaEvolution(source,"iox","target_rows");noop.execute();
            request("POST","/api/v3/write_lp?db="+database+"&precision=nanosecond","target_rows,host=c,rack=r u=18446744073709551615u,f=1.5,s=\"保真\",b=true,value=3i 1790985600123456790","text/plain");
            var result=query("SELECT CAST(u AS VARCHAR) AS u,s,b FROM target_rows WHERE host='c'").get(0);
            assertEquals("18446744073709551615",result.get("u").asText());assertEquals("保真",result.get("s").asText());assertTrue(result.get("b").asBoolean());
        }
    }
    @Test void removalRetypeRetentionAndOversizeDefinitionsRejectBeforeAnySideEffect()throws Exception {
        var before=query("SELECT * FROM target_rows");var nativeDefinition=InfluxNativeTables.describe(pool,"iox","target_rows",1024*1024,10);
        for(int kind=0;kind<3;kind++) {
            var document=(com.fasterxml.jackson.databind.node.ObjectNode)InfluxNativeSession.JSON.readTree(nativeDefinition.document());
            var columns=(com.fasterxml.jackson.databind.node.ArrayNode)document.get("columns");
            if(kind==0)columns.remove(0);
            if(kind==1)((com.fasterxml.jackson.databind.node.ObjectNode)columns.get(2)).put("kind","STRING");
            if(kind==2)document.put("retention","1h");
            var fields=new ArrayList<String>();columns.forEach(c->fields.add(c.get("name").asText()));
            var desired=new cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition(ProductTypeEnum.INFLUXDB3,nativeDefinition.format(),document.toString(),fields);
            assertThrows(IllegalStateException.class,()->InfluxNativeEvolution.plan(pool,desired,"iox","target_rows",1024*1024,10));
        }
        assertThrows(IllegalStateException.class,()->InfluxNativeEvolution.plan(pool,nativeDefinition,"iox","target_rows",16,10));
        assertEquals(before,query("SELECT * FROM target_rows"));assertEquals(0,query("SELECT * FROM system.last_caches").size());
    }
    @Test void stalePlanRejectsBeforeAddingItsRequestedColumn()throws Exception {
        json("PATCH","/api/v3/configure/table","{\"db\":\""+database+"\",\"table\":\"source_rows\",\"tags\":[],\"fields\":[{\"name\":\"desired\",\"type\":\"utf8\"}]}");
        var desired=InfluxNativeTables.describe(pool,"iox","source_rows",1024*1024,10);
        var plan=InfluxNativeEvolution.plan(pool,desired,"iox","target_rows",1024*1024,10);
        json("PATCH","/api/v3/configure/table","{\"db\":\""+database+"\",\"table\":\"target_rows\",\"tags\":[],\"fields\":[{\"name\":\"other\",\"type\":\"utf8\"}]}");
        assertThrows(IllegalStateException.class,plan::execute);
        var observed=InfluxNativeTables.describe(pool,"iox","target_rows",1024*1024,10);
        assertTrue(observed.fields().contains("other"));assertFalse(observed.fields().contains("desired"));
        assertEquals(1,query("SELECT * FROM target_rows").size());assertEquals(0,query("SELECT * FROM system.last_caches").size());
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
