package cn.iocoder.yudao.framework.quality.core.audit;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named="datahub.test.influx.http",matches="http://127[.]0[.]0[.]1:[0-9]+")
@EnabledIfSystemProperty(named="datahub.test.influx.driver",matches=".+")
@EnabledIfEnvironmentVariable(named="DATAHUB_INFLUX_TEST_TOKEN",matches="apiv3_.+")
class AuditInfluxKeySnapshotInfluxTest {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final AuditSqlGenerator generator=new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");
    private final AuditRuleContext context=new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover());
    private String schema,table;
    private Connection connection;
    private com.zaxxer.hikari.HikariDataSource pool;
    @BeforeAll void setup() throws Exception {
        schema="q11infq_"+UUID.randomUUID().toString().replace("-", "");
        request("POST","/api/v3/configure/database","{\"db\":\""+schema+"\"}");
        var jar=Path.of(System.getProperty("datahub.test.influx.driver")).toRealPath();
        var config=new DbSwitchProperties.Driver();config.setPath(jar.toString());
        config.setSha256(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        var binding=new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.INFLUXDB3,config)).find(ProductTypeEnum.INFLUXDB3).orElseThrow();
        var props=new TargetDataSourceProperties();props.setTargetProductType(ProductTypeEnum.INFLUXDB3);
        props.setUrl("jdbc:arrow-flight-sql://127.0.0.1:"+URI.create(System.getProperty("datahub.test.influx.http")).getPort()+"/?useEncryption=0&database="+schema);
        props.setPassword(System.getenv("DATAHUB_INFLUX_TEST_TOKEN"));props.setPoolSize(1);props.setConnectionTimeout(5000L);
        pool=DataSourceUtils.createTargetDataSource(props,null,null,binding);connection=pool.getConnection();
        context.setProductType(ProductTypeEnum.INFLUXDB3);context.setColumn("amount");context.setConfig(Map.of());
    }
    @BeforeEach void table() {table="t_"+UUID.randomUUID().toString().replace("-", "");context.setTableName("iox."+table);context.setWhereClause(null);}
    @AfterAll void cleanup() throws Exception {
        try {if(connection!=null)connection.close();if(pool!=null)pool.close();}
        finally {try{if(schema!=null)request("DELETE","/api/v3/configure/database?db="+schema,"");}finally{http.close();}}
    }
    private void request(String method,String path,String body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(System.getProperty("datahub.test.influx.http")+path)).timeout(Duration.ofSeconds(10))
                .header("Authorization","Bearer "+System.getenv("DATAHUB_INFLUX_TEST_TOKEN"))
                .header("Content-Type",path.contains("write_lp")?"text/plain":"application/json")
                .method(method,HttpRequest.BodyPublishers.ofString(body)).build();
        int status=http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode();
        assertTrue(status>=200&&status<300,()->"Influx fixture HTTP status "+status);
    }
    private void insert(String... fields) throws Exception {
        var lines=new ArrayList<String>();long timestamp=System.currentTimeMillis();
        for(int i=0;i<fields.length;i++)lines.add(table+" "+fields[i]+" "+(timestamp+i));
        request("POST","/api/v3/write_lp?db="+schema+"&precision=millisecond",String.join("\n",lines));
    }
    private AuditKeySnapshot read(boolean composite,int limit) throws SQLException {
        return composite?AuditKeySnapshot.readComposite(connection,generator,context,List.of("id","part"),limit,10)
                :AuditKeySnapshot.read(connection,generator,context,"id",limit,10);
    }
    private AuditKeySnapshot.Grouped grouped(boolean composite,int keys,int groups) throws SQLException {
        return composite?AuditKeySnapshot.readGroupedComposite(connection,generator,context,List.of("id","part"),"org_code",keys,groups,10)
                :AuditKeySnapshot.readGrouped(connection,generator,context,"id","org_code",keys,groups,10);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void nativeIntegerSnapshotsKeepCountsPrecisionAndStableJson(boolean composite) throws Exception {
        insert("id=-9223372036854775808i,part=1i,org_code=9i", "id=9007199254740992i,part=2i,org_code=9i,amount=0i",
                "id=9007199254740993i,part=3i,org_code=10i,amount=1i", "id=9223372036854775807i,part=4i,org_code=10i,amount=1i");
        var result=read(composite,4);assertTrue(result.complete(),result.reasonCode());assertEquals(4,result.total());assertEquals(1,result.fail());
        assertEquals(composite?AuditKeySnapshot.INFLUXDB_COMPOSITE_ALGORITHM:AuditKeySnapshot.ALGORITHM,result.algorithm());
        assertEquals(result,JsonUtils.parseObject(JsonUtils.toJsonString(result),AuditKeySnapshot.class));
        for(int i=0;i<5;i++)assertEquals(result.keyHash(),read(composite,4).keyHash());
        assertFalse(AuditKeySnapshot.supportsEncoding("INFLUXDB3",result.algorithm(),result.jdbcType()),"Physical source generation is not yet qualified");
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void groupAndKeyLimitsAreGlobalAndNeverEmitPartialProof(boolean composite) throws Exception {
        insert("id=1i,part=1i,org_code=9i", "id=2i,part=2i,org_code=10i,amount=1i");
        assertEquals("KEY_LIMIT_EXCEEDED",read(composite,1).reasonCode());
        var result=grouped(composite,2,2);assertTrue(result.complete(),result.reasonCode());
        assertEquals(AuditKeySnapshot.INFLUXDB_INTEGER_GROUP_ENCODING,result.groupEncoding());
        assertEquals(Set.of("9","10"),new HashSet<>(result.groups().stream().map(AuditKeySnapshot.Group::key).toList()));
        assertTrue(result.groups().stream().allMatch(g->g.snapshot().complete()));
        assertEquals("GROUP_LIMIT_EXCEEDED",grouped(composite,2,1).reasonCode());
        context.setWhereClause("1=0");var empty=grouped(composite,2,2);
        assertTrue(empty.complete());assertTrue(empty.groups().isEmpty());assertNull(empty.groupJdbcType());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void timestampUniquenessCannotHideDuplicateConfiguredRecordKeys(boolean composite) throws Exception {
        insert("id=1i,part=1i,org_code=9i", "id=1i,part=1i,org_code=9i,amount=1i");
        assertEquals("KEY_NOT_UNIQUE_OR_NULL",read(composite,10).reasonCode());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void missingRecordKeysCannotBecomeZeroOrEmptyIdentity(boolean composite) throws Exception {
        insert("id=1i,part=1i,org_code=9i,amount=1i", "part=2i,org_code=9i,amount=1i");
        assertFalse(read(composite,10).complete());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void missingInstitutionCannotBecomeZeroOrAValidScope(boolean composite) throws Exception {
        insert("id=1i,part=1i,org_code=9i,amount=1i", "id=2i,part=2i,amount=1i");
        var result=grouped(composite,10,10);
        assertFalse(result.complete());assertEquals("GROUP_KEY_UNSUPPORTED",result.reasonCode());assertTrue(result.groups().isEmpty());
    }
    @Test void unsignedTextAndTimestampCannotBorrowSignedKeyOrInstitutionEvidence() throws Exception {
        insert("id=18446744073709551615u,part=1i,org_code=9u,amount=1i,text=\"9\"");
        assertEquals("KEY_TYPE_UNSUPPORTED",read(false,10).reasonCode());
        assertFalse(read(true,10).complete());assertEquals("GROUP_KEY_UNSUPPORTED",grouped(false,10,10).reasonCode());
        for(String key:List.of("time","text"))assertEquals("KEY_TYPE_UNSUPPORTED",AuditKeySnapshot.read(connection,generator,context,key,10,10).reasonCode());
    }
}
