package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.config.*;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Core 3 native schema+row transfer through the public MigrationService and verified Arrow bundle. */
@EnabledIfSystemProperty(named="datahub.test.influx.http",matches="http://127[.]0[.]0[.]1:[0-9]+")
@EnabledIfEnvironmentVariable(named="DATAHUB_INFLUX_TEST_TOKEN",matches="apiv3_.+")
@Timeout(90)
class MigrationInfluxTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private HttpClient http;private String database;private ConfiguredJdbcDrivers drivers;
    @BeforeEach void setup()throws Exception {
        http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();database="migration_"+UUID.randomUUID().toString().replace("-","");
        request("POST","/api/v3/configure/database","{\"db\":\""+database+"\"}","application/json");
        Path artifact=Path.of(Objects.requireNonNull(System.getProperty("datahub.test.influx.driver"))).toAbsolutePath();
        var digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(artifact)){byte[] bytes=new byte[8192];int read;while((read=input.read(bytes))!=-1)digest.update(bytes,0,read);}
        var spec=new DbSwitchProperties.Driver();spec.setPath(artifact.toString());spec.setSha256(HexFormat.of().formatHex(digest.digest()));spec.setDriverClassName(ProductTypeEnum.INFLUXDB3.getDriveClassName());
        drivers=new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.INFLUXDB3,spec));
    }
    @AfterEach void cleanup()throws Exception {
        try{if(drivers!=null){assertEquals(0,drivers.runtimes().connections().snapshot().active());assertEquals(0,drivers.runtimes().connections().snapshot().quarantined());drivers.close();}}
        finally{if(http!=null)try(var owned=http){if(database!=null)request("DELETE","/api/v3/configure/database?db="+database,"","application/json");}}
    }
    @Test void fullAutomaticallyCreatesNativeTagFieldUnsignedAndNanosecondStructure()throws Exception {
        seed("source_rows");run("source_rows","target_rows",false,false,false);
        assertEquals(rows("source_rows"),rows("target_rows"));assertEquals(catalog("source_rows"),catalog("target_rows"));
        JsonNode actual=rows("target_rows");assertEquals(3,actual.size());assertEquals("18446744073709551615",actual.get(0).get("u").asText());
        assertEquals("2026-10-03T00:00:00.123456789",actual.get(0).get("time").asText());
    }
    @Test void nativeMetadataAndFlightMetadataAgreeBeforeAnyMutation()throws Exception {
        seed("source_rows");
        var properties=new cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties();
        properties.setTargetProductType(ProductTypeEnum.INFLUXDB3);properties.setUrl("jdbc:arrow-flight-sql://127.0.0.1:"+URI.create(System.getProperty("datahub.test.influx.http")).getPort()+"/?useEncryption=0&database="+database);properties.setPassword(token());properties.setPoolSize(1);
        try(var pool=cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils.createTargetDataSource(properties,null,null,drivers.find(ProductTypeEnum.INFLUXDB3).orElseThrow(),drivers.runtimes());var runtime=new DbSwitchRuntime(DbSwitchProductRegistry.discover(),DbSwitchOptions.defaults(),drivers)) {
            assertDoesNotThrow(()->runtime.metadata(pool,ProductTypeEnum.INFLUXDB3).getTableRemark("iox","source_rows"));
            try(var connection=pool.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT CAST(key AS VARCHAR) AS native_key,CAST(data_type AS VARCHAR) AS native_type FROM system.influxdb_schema WHERE measurement='source_rows' ORDER BY native_key LIMIT 501")) {
                var observed=new ArrayList<String>();while(rows.next())observed.add(rows.getString(1)+":"+rows.getString(2));
                assertEquals(List.of("flag:boolean","host:tag","note:string","ratio:float","time:time","u:uinteger","value:integer"),observed);
            }
            var columns=runtime.metadata(pool,ProductTypeEnum.INFLUXDB3).queryTableColumnMeta("iox","source_rows");assertEquals(7,columns.size(),()->columns.stream().map(c->c.getFieldName()+":"+c.getFieldTypeName()).toList().toString());
            assertEquals(List.of("host","time"),runtime.metadata(pool,ProductTypeEnum.INFLUXDB3).queryTablePrimaryKeys("iox","source_rows"));
            var reader=runtime.reader(pool,ProductTypeEnum.INFLUXDB3);
            var nativeDefinition=reader.describeNativeTable("iox","source_rows");assertEquals(7,nativeDefinition.fields().size());
            assertThrows(IllegalStateException.class,()->reader.prepareNativeTarget(nativeDefinition,"iox","invalid_target",Map.of("host","host","time","time"),false));
            var alias=JSON.readTree(nativeDefinition.document());((com.fasterxml.jackson.databind.node.ObjectNode)alias).put("source","http://localhost:"+URI.create(System.getProperty("datahub.test.influx.http")).getPort()+"/"+database+"/source_rows");
            var aliased=new cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition(ProductTypeEnum.INFLUXDB3,nativeDefinition.format(),alias.toString(),nativeDefinition.fields());
            var identity=new LinkedHashMap<String,String>();nativeDefinition.fields().forEach(field->identity.put(field,field));
            assertThrows(IllegalStateException.class,()->reader.prepareNativeTarget(aliased,"iox","source_rows",identity,true));
            var fields=columns.stream().map(c->c.getFieldName()).toList();
            var values=new ArrayList<Object[]>();
            try(var result=runtime.reader(pool,ProductTypeEnum.INFLUXDB3).queryTableData("iox","source_rows",fields)) {
                var rows=result.getResultset();assertFalse(rows.getMetaData().isSigned(6));
                var textValues=new ArrayList<String>();while(rows.next()){Object[] row=new Object[fields.size()];for(int i=0;i<row.length;i++)row[i]=rows.getObject(i+1);values.add(row);textValues.add(rows.getString(6));}
                assertTrue(textValues.contains("18446744073709551615"),()->textValues.toString());
            }
            assertEquals(3,values.size());
            assertTrue(values.stream().allMatch(row->row[4] instanceof java.sql.Timestamp));
            assertTrue(values.stream().anyMatch(row->new java.math.BigInteger("18446744073709551615").equals(row[5])),
                    ()->values.stream().map(row->Arrays.stream(row).map(value->value==null?"null":value.getClass().getName()).toList()).toList().toString());
            seed("direct_target");
            var writer=runtime.writer(pool,ProductTypeEnum.INFLUXDB3,cn.iocoder.yudao.framework.dbswitch.spi.WriteMode.INSERT);
            writer.prepareWrite("iox","direct_target",fields);assertEquals(3,writer.write(fields,values));
            assertEquals(rows("source_rows"),rows("direct_target"));
        }
    }
    @Test void appendRetainsExistingNativePointsAndSameTimestampDifferentTags()throws Exception {
        seed("source_rows");seed("target_rows");write("target_rows,host=untouched value=99i,u=0u,note=\"keep\",ratio=0.0,flag=false 1790985600123456789");
        run("source_rows","target_rows",false,false,false);
        assertEquals(4,rows("target_rows").size());assertEquals(1,query("SELECT * FROM target_rows WHERE host='untouched'").size());
    }
    @Test void replacementRequiresExclusiveContractThenRestoresNativeSchemaAndExactSourceRows()throws Exception {
        seed("source_rows");seed("target_rows");write("target_rows,host=untouched value=99i,u=0u,note=\"keep\",ratio=0.0,flag=false 1790985600123456789");
        var before=rows("target_rows");var rejected=runResult("source_rows","target_rows",true,false,false);assertTrue(rejected.getTotalTableFailCount().get()>0);assertEquals(before,rows("target_rows"));
        run("source_rows","target_rows",true,true,false);assertEquals(rows("source_rows"),rows("target_rows"));assertEquals(catalog("source_rows"),catalog("target_rows"));
    }
    @Test void structureOnlyCreatesAnEmptyTypedTargetWithoutSeedRows()throws Exception {
        seed("source_rows");run("source_rows","empty_target",false,false,true);assertEquals(0,rows("empty_target").size());assertEquals(catalog("source_rows"),catalog("empty_target"));
    }
    @Test void sameSourceTargetReplacementIsRejectedBeforeDeletingAnyRows()throws Exception {
        seed("source_rows");var before=rows("source_rows");assertTrue(runResult("source_rows","source_rows",true,true,false).getTotalTableFailCount().get()>0);assertEquals(before,rows("source_rows"));
    }
    @Test void quotedTableIdentifiersCannotSelectOrWriteAnotherTable()throws Exception {
        String source="source\"quoted";String target="target\"quoted";seed(source);run(source,target,false,false,false);assertEquals(rows(source),rows(target));
    }
    private void run(String source,String target,boolean replace,boolean exclusive,boolean onlyCreate)throws Exception {
        var result=runResult(source,target,replace,exclusive,onlyCreate);assertEquals(0,result.getTotalTableFailCount().get(),()->result.getTableResultList().stream().map(r->r.getErrorMsg()).toList().toString());assertEquals(1,result.getTotalTableSuccessCount().get());
    }
    private cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult runResult(String sourceTable,String targetTable,boolean replace,boolean exclusive,boolean onlyCreate)throws Exception {
        String url="jdbc:arrow-flight-sql://127.0.0.1:"+URI.create(System.getProperty("datahub.test.influx.http")).getPort()+"/?useEncryption=0&database="+database;
        var request=new MigrationRequest();var source=new SourceDataSourceProperties();source.setSourceProductType(ProductTypeEnum.INFLUXDB3);source.setUrl(url);source.setPassword(token());source.setSourceSchema("iox");source.setSourceIncludes(sourceTable);source.setPoolSize(1);source.setFetchSize(500);source.setRegexTableMapper(List.of(new PatternMapper("^"+java.util.regex.Pattern.quote(sourceTable)+"$",targetTable)));request.getSource().add(source);
        var target=request.getTarget();target.setTargetProductType(ProductTypeEnum.INFLUXDB3);target.setUrl(url);target.setPassword(token());target.setTargetSchema("iox");target.setPoolSize(1);target.setTargetDrop(replace);target.setOnlyCreate(onlyCreate);target.setExclusiveTargetDdlOwnership(exclusive);target.setWriterEngineInsert(true);target.setChangeDataSync(false);
        var options=new DbSwitchOptions(16*1024*1024,true,false,15,0,300000,30000,0);
        try(var runtime=new DbSwitchRuntime(DbSwitchProductRegistry.discover(),options,drivers)){return new MigrationService(request,ignored->{},null,runtime).run();}
    }
    private void seed(String table)throws Exception {
        write(table+",host=a value=9223372036854775807i,u=18446744073709551615u,note=\"中文\\\"quote\\\\slash\",ratio=0.1,flag=true 1790985600123456789\n"
                +table+",host=b value=-9223372036854775808i,u=0u,note=\"\",ratio=-1.5,flag=false 1790985600123456789\n"
                +table+" value=9007199254740993i,u=9007199254740993u,ratio=0.0,flag=true 1790985600123456790");
    }
    private JsonNode rows(String table)throws Exception{return query("SELECT * FROM "+quote(table)+" ORDER BY host NULLS LAST,time");}
    private JsonNode catalog(String table)throws Exception{return query("SELECT key,data_type FROM system.influxdb_schema WHERE measurement='"+table.replace("'","''")+"' ORDER BY key");}
    private JsonNode query(String sql)throws Exception{return JSON.readTree(request("POST","/api/v3/query_sql",JSON.createObjectNode().put("db",database).put("q",sql).put("format","json").toString(),"application/json"));}
    private void write(String body)throws Exception{request("POST","/api/v3/write_lp?db="+database+"&precision=nanosecond&accept_partial=false",body,"text/plain");}
    private String request(String method,String path,String body,String contentType)throws Exception {
        var request=HttpRequest.newBuilder(URI.create(System.getProperty("datahub.test.influx.http")+path)).timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+token()).header("Content-Type",contentType).method(method,HttpRequest.BodyPublishers.ofString(body)).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofString());assertTrue(response.statusCode()>=200&&response.statusCode()<300,()->"Influx fixture status="+response.statusCode());return response.body();
    }
    private static String token(){return System.getenv("DATAHUB_INFLUX_TEST_TOKEN");}
    private static String quote(String value){return "\""+value.replace("\"","\"\"")+"\"";}
}
