package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class EtlNativeFlinkBoundedCompilerTest {
    private static final EtlNativeSqlCompiler.Engine FLINK = EtlNativeSqlCompiler.Engine.FLINK;
    private static Map<String,Object> column() { return Map.of("name","id","type","Long","index",0); }
    private static EtlDataxConnectorCompiler.Binding binding(Set<String> codes, String root, String fs, List<String> addresses,
            String database, EtlDataxConnectorCompiler.Authentication authentication, String... plugins) {
        return new EtlDataxConnectorCompiler.Binding(codes,root,fs,addresses,database,authentication,Set.of(plugins));
    }
    private static cn.hutool.json.JSONObject plan(String sql) {
        var match=Pattern.compile("'plan' = '([A-Za-z0-9+/=]+)'").matcher(sql); assertTrue(match.find());
        return JSONUtil.parseObj(new String(Base64.getDecoder().decode(match.group(1)),StandardCharsets.UTF_8));
    }
    @Test void threeFileFormatsCompileToRealBoundedTablesWithExplicitFiftyMiBBudget() {
        var resource=binding(Set.of("read-csv","read-json","read-excel"),"/data/managed",null,List.of(),null,EtlDataxConnectorCompiler.Authentication.NONE,"flink-bounded-file");
        for(String code:List.of("read-csv","read-json","read-excel")) {
            var config=new LinkedHashMap<String,Object>();config.put("resourceBindingId","files");config.put("relativePath",code.equals("read-csv")?"input.csv":code.equals("read-json")?"input.jsonl":"input.xlsx");config.put("columns",List.of(column()));
            var compiled=EtlNativeFileCompiler.compile(FLINK,false,"node-"+code,code,config,resource,Set.of());
            assertTrue(compiled.initializationSql().contains("CREATE TEMPORARY TABLE"));assertTrue(compiled.initializationSql().contains("'connector' = 'datahub-bounded'"));
            assertFalse(compiled.table().continuousSource());assertEquals("BIGINT",compiled.table().columns().get("id"));
            if(!code.equals("read-excel")) assertEquals(52428800,plan(compiled.initializationSql()).getInt("maxBytes"));
        }
        var config=Map.<String,Object>of("relativePath","input.csv","columns",List.of(column()));
        assertThrows(IllegalArgumentException.class,()->EtlNativeFileCompiler.compile(FLINK,true,"node","read-csv",config,resource));
        assertThrows(IllegalArgumentException.class,()->EtlNativeFileCompiler.compile(FLINK,false,"node","read-csv",config,binding(Set.of("read-csv"),"/data/managed",null,List.of(),null,EtlDataxConnectorCompiler.Authentication.NONE)));
    }
    @Test void redisAndOdpsRequireTheirOwnFlinkInstallationProofAndKeepSecretReferences() {
        var redis=new EtlNativeRedisCompiler.Binding("redis.internal",6379,0,false,"redisRef",Set.of("source:*"),Set.of("result:"),false,true);
        var config=Map.<String,Object>of("databaseId",1,"dataType","string","keyPattern","source:*","columns",List.of(column()));
        var source=EtlNativeRedisCompiler.compile(FLINK,false,"redis","read-redis",config,redis,Set.of(),Set.of("redisRef"));
        assertEquals("redisRef",plan(source.initializationSql()).getStr("credentialRef"));assertFalse(source.initializationSql().contains("password"));
        var legacy=new EtlNativeRedisCompiler.Binding("redis.internal",6379,0,false,"redisRef",Set.of("source:*"),Set.of("result:"),true);
        assertThrows(IllegalArgumentException.class,()->EtlNativeRedisCompiler.compile(FLINK,false,"redis","read-redis",config,legacy,Set.of(),Set.of("redisRef")));
        var odps=new EtlNativeOdpsCompiler.Binding("https://service.example.test","https://tunnel.example.test","project","odpsRef",List.of(new EtlNativeOdpsCompiler.Resource("events","",Map.of("id","BIGINT"),true,true,false)),false,false,true);
        var odpsConfig=Map.<String,Object>of("databaseId",1,"tableName","events","columns",List.of(column()));
        var result=EtlNativeOdpsCompiler.compile(FLINK,false,"odps","read-odps",odpsConfig,odps,Set.of(),Set.of("odpsRef"));
        assertEquals("project",plan(result.initializationSql()).getStr("project"));
        assertThrows(IllegalArgumentException.class,()->EtlNativeOdpsCompiler.compile(FLINK,false,"odps","read-odps",odpsConfig,odps,Set.of(),Set.of()));
    }
    @Test void ftpHdfsAndMongoPreserveValidatedPathsAndExplicitFieldShapes() {
        var ftp=binding(Set.of("read-ftp","write-ftp"),"/",null,List.of("ftps://files.example.test:2121"),null,EtlDataxConnectorCompiler.Authentication.REQUIRED,"flink-bounded-ftp","ftp-chroot");
        var ftpConfig=Map.<String,Object>of("resourceBindingId","ftp","credentialRef","ftpRef","path","source.csv","columns",List.of(column()));
        var ftpPlan=EtlNativeFtpCompiler.compile(FLINK,false,"ftp","read-ftp",ftpConfig,ftp,Set.of(),Set.of("ftpRef"));
        assertEquals("source.csv",plan(ftpPlan.initializationSql()).getStr("path"));
        var hdfs=binding(Set.of("read-hdfs"),"/warehouse/safe","hdfs://namenode.example.test:9000",List.of(),null,EtlDataxConnectorCompiler.Authentication.NONE,"flink-bounded-hdfs");
        var hdfsPlan=EtlNativeHdfsCompiler.compile(FLINK,false,"hdfs","read-hdfs",Map.of("resourceBindingId","hdfs","path","input","columns",List.of(column())),hdfs);
        assertEquals("hdfs://namenode.example.test:9000/warehouse/safe/input",plan(hdfsPlan.initializationSql()).getJSONObject("options").getStr("path"));
        var mongo=binding(Set.of("read-mongodb"),null,null,List.of("mongo.example.test:27017"),"eventsdb",EtlDataxConnectorCompiler.Authentication.ANONYMOUS,"flink-bounded-mongodb");
        var mongoPlan=EtlNativeMongoCompiler.compile(FLINK,false,"mongo","read-mongodb",Map.of("databaseId",1,"tableName","events","columns",List.of(column())),mongo);
        assertEquals("events",plan(mongoPlan.initializationSql()).getStr("collection"));assertEquals("BIGINT",mongoPlan.table().columns().get("id"));
    }
    @Test void httpAndSoapUseFlinkTransportRatherThanSparkFunctions() {
        var resource=binding(Set.of("rest-api","read-webservice"),null,null,List.of("https://api.example.test/data","https://api.example.test/service?wsdl"),null,EtlDataxConnectorCompiler.Authentication.NONE,"flink-bounded-http","flink-bounded-webservice");
        var api=Map.of("requestUrl","https://api.example.test/data","requestMethod","GET","bodyType","none","dataColumns",List.of(Map.of("fieldName","id","fieldType",-5)));
        var http=EtlNativeHttpCompiler.compile(FLINK,false,"api","rest-api",Map.of("resourceBindingId","api","apiConfig",api),resource,Set.of(),Set.of());
        assertTrue(http.initializationSql().contains("'kind' = 'api'"));assertFalse(http.initializationSql().contains("LATERAL VIEW"));
        var soap=EtlNativeWebServiceCompiler.compile(FLINK,false,"soap","read-webservice",Map.of("resourceBindingId","api","apiUrl","https://api.example.test/service?wsdl","operationName","query","columns",List.of(column())),resource,Set.of(),Set.of());
        assertTrue(soap.initializationSql().contains("'kind' = 'soap'"));assertEquals("BIGINT",soap.table().columns().get("id"));
    }
}
