package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.config.*;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.*;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.recovery.PostgresTableRewriteStore;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.*;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import cn.iocoder.yudao.framework.dbswitch.driver.DriverPoolFactory;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.crypto.spec.SecretKeySpec;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="datahub.test.influx.http",matches="http://127[.]0[.]0[.]1:[0-9]+")
@EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+")
@EnabledIfEnvironmentVariable(named="DATAHUB_INFLUX_TEST_TOKEN",matches="apiv3_.+")
@Timeout(120)
class MigrationInfluxDifferenceTest {
    private static final ObjectMapper JSON=new ObjectMapper();private static final String SCOPE="a".repeat(64);
    private HttpClient http;private String database,schema;private ConfiguredJdbcDrivers drivers;private DriverManagerDataSource control;
    private SecretKeySpec secret;private TableRewriteLimits limits;private final AtomicInteger admissions=new AtomicInteger(),permissions=new AtomicInteger();
    private java.util.function.UnaryOperator<TableRewriteStore> decorateStore=java.util.function.UnaryOperator.identity();
    @BeforeEach void setup()throws Exception {
        http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();database="difference_"+UUID.randomUUID().toString().replace("-","");
        request("POST","/api/v3/configure/database","{\"db\":\""+database+"\"}","application/json");
        for(String name:List.of("source_rows","target_rows"))request("POST","/api/v3/configure/table","{\"db\":\""+database+"\",\"table\":\""+name+"\",\"tags\":[\"host\"],\"fields\":[{\"name\":\"value\",\"type\":\"int64\"},{\"name\":\"u\",\"type\":\"uint64\"},{\"name\":\"note\",\"type\":\"utf8\"}]}","application/json");
        write("source_rows,host=a value=9007199254740993i,u=18446744073709551615u 1790985600123456789\nsource_rows,host=c value=3i,u=3u,note=\"inserted\" 1790985600123456790\n"
                +"target_rows,host=a value=1i,u=1u,note=\"must_clear\" 1790985600123456789\ntarget_rows,host=b value=2i,u=2u,note=\"delete\" 1790985600123456789");
        Path jar=Path.of(Objects.requireNonNull(System.getProperty("datahub.test.influx.driver")));var sha=MessageDigest.getInstance("SHA-256");
        try(var input=Files.newInputStream(jar)){byte[] bytes=new byte[8192];int n;while((n=input.read(bytes))!=-1)sha.update(bytes,0,n);}
        var spec=new DbSwitchProperties.Driver();spec.setPath(jar.toAbsolutePath().toString());spec.setSha256(HexFormat.of().formatHex(sha.digest()));spec.setDriverClassName(ProductTypeEnum.INFLUXDB3.getDriveClassName());drivers=new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.INFLUXDB3,spec));
        Class.forName("org.postgresql.Driver");control=new DriverManagerDataSource(System.getProperty("datahub.test.postgres.url"),System.getProperty("datahub.test.postgres.user","dataforge_test"),"");
        schema="influx_diff_"+UUID.randomUUID().toString().replace("-","");sql("CREATE SCHEMA "+schema);
        for(String ddl:PostgresTableRewriteStore.schemaStatements(schema+".journal",schema+".pages"))sql(ddl);
        byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);secret=new SecretKeySpec(key,"AES");
        limits=new TableRewriteLimits(new BatchLimits(1,65536,32768,100,1000),100,100,1024*1024);
    }
    @AfterEach void cleanup()throws Exception {
        try{if(drivers!=null){assertEquals(0,drivers.runtimes().connections().snapshot().active());assertEquals(0,drivers.runtimes().connections().snapshot().quarantined());drivers.close();}}
        finally{try{if(schema!=null)sql("DROP SCHEMA "+schema+" CASCADE");}finally{if(http!=null)try(var owned=http){if(database!=null)request("DELETE","/api/v3/configure/database?db="+database,"","application/json");}}}
    }
    @Test void publicMigrationDiffInsertsUpdatesDeletesAndClearsNullWhilePreservingUnsignedAndNanos()throws Exception {
        var result=run(true,false,SyncOptionEnum.INSERT_UPDATE_DELETE);
        assertEquals(rows("source_rows"),rows("target_rows"));assertEquals(0,result.getTotalTableFailCount().get(),()->errors(result));assertTrue(rows("target_rows").get(0).path("note").isMissingNode());
        assertEquals("18446744073709551615",rows("target_rows").get(0).get("u").asText());assertEquals("2026-10-03T00:00:00.123456789",rows("target_rows").get(0).get("time").asText());
        assertEquals(1,admissions.get());assertEquals(1,permissions.get());assertEquals(1,count("SELECT count(*) FROM "+schema+".journal WHERE phase='APPLIED'"));
        assertEquals(4,count("SELECT count(*) FROM "+schema+".pages"));assertNotNull(result.getTableResultList().getFirst().getNativePlanDigest());
    }
    @Test void insertOnlyPreservesUnselectedUpdatesAndDeletes()throws Exception {
        var result=run(true,false,SyncOptionEnum.INSERT);assertEquals(0,result.getTotalTableFailCount().get(),()->errors(result));var actual=rows("target_rows");
        assertEquals(3,actual.size());assertEquals(1,actual.get(0).get("value").longValue());assertEquals("must_clear",actual.get(0).get("note").asText());assertEquals("b",actual.get(1).get("host").asText());
    }
    @Test void missingAdmissionOrRejectedManagementPermissionNeverChangesTarget()throws Exception {
        var before=rows("target_rows");assertTrue(run(false,false,SyncOptionEnum.INSERT_UPDATE_DELETE).getTotalTableFailCount().get()>0);assertEquals(before,rows("target_rows"));
        assertTrue(run(true,true,SyncOptionEnum.INSERT_UPDATE_DELETE).getTotalTableFailCount().get()>0);assertEquals(before,rows("target_rows"));assertEquals(0,count("SELECT count(*) FROM "+schema+".journal"));
    }
    @Test void wholeImageCapacityFailureOccursBeforeDestructiveIntent()throws Exception {
        limits=new TableRewriteLimits(limits.page(),3,100,1024*1024);var before=rows("target_rows");
        assertTrue(run(true,false,SyncOptionEnum.INSERT_UPDATE_DELETE).getTotalTableFailCount().get()>0);assertEquals(before,rows("target_rows"));assertEquals(0,count("SELECT count(*) FROM "+schema+".journal"));
    }
    @Test void nullNativeTagKeyIsRejectedBeforeAnyDropOrWrite()throws Exception {
        write("target_rows value=99i,u=99u 1790985600123456791");var before=rows("target_rows");
        assertTrue(run(true,false,SyncOptionEnum.INSERT_UPDATE_DELETE).getTotalTableFailCount().get()>0);assertEquals(before,rows("target_rows"));assertEquals(0,count("SELECT count(*) FROM "+schema+".journal"));
    }
    @Test void pagePersistenceFailureCannotStartTargetRewrite()throws Exception {
        sql("ALTER TABLE "+schema+".pages ADD CONSTRAINT reject_material CHECK(ordinal=0)");var before=rows("target_rows");
        assertTrue(run(true,false,SyncOptionEnum.INSERT_UPDATE_DELETE).getTotalTableFailCount().get()>0);assertEquals(before,rows("target_rows"));assertEquals(0,count("SELECT count(*) FROM "+schema+".journal"));
    }
    @Test void secondIdenticalDifferenceDoesNotReplaceGenerationOrCreateAnotherJournal()throws Exception {
        assertEquals(0,run(true,false,SyncOptionEnum.INSERT_UPDATE_DELETE).getTotalTableFailCount().get());
        var applied=latest();var second=run(true,false,SyncOptionEnum.INSERT_UPDATE_DELETE);
        assertEquals(0,second.getTotalTableFailCount().get(),()->errors(second));assertEquals(0,second.getTableResultList().getFirst().getSyncCount().get());
        assertEquals(1,count("SELECT count(*) FROM "+schema+".journal"));assertEquals(applied.newIdentity(),latest().newIdentity());
        assertEquals(applied.newIdentity().fingerprint(),second.getTableResultList().getFirst().getNativeRecoveryResources().getFirst().expectedPhysicalIdentity());
    }
    @Test void createSucceededButGenerationCannotBePersistedRemainsUnclaimedAndNeverReplays()throws Exception {
        sql("ALTER TABLE "+schema+".journal ADD CONSTRAINT reject_generation CHECK(phase<>'CREATE_CONFIRMED')");
        var failed=run(true,false,SyncOptionEnum.INSERT_UPDATE_DELETE);assertEquals(1,failed.getTotalTableFailCount().get());
        var record=latest();assertEquals(TableRewriteRecord.Phase.CREATE_INTENT,record.phase());assertNull(record.newIdentity());
        assertEquals(0,rows("target_rows").size());assertNotNull(failed.getTableResultList().getFirst().getNativePlanDigest());
        assertTrue(store().claimRecovery(record.key(),record.revision(),"recovery_owner_0002").isEmpty());
        assertEquals(1,run(true,false,SyncOptionEnum.INSERT_UPDATE_DELETE).getTotalTableFailCount().get());
        assertEquals(0,rows("target_rows").size());assertEquals(record.revision(),latest().revision());assertEquals(1,count("SELECT count(*) FROM "+schema+".journal"));
    }
    @Test void confirmedGenerationCanRestoreAfterPublicMigrationFailedWithoutAnotherDrop()throws Exception {
        var failOnce=new java.util.concurrent.atomic.AtomicBoolean(true);
        decorateStore=delegate->new TableRewriteStore(){
            public Optional<TableRewriteRecord> read(TableRewriteKey key){return delegate.read(key);}
            public TableRewriteRecord prepare(TableRewriteKey key,String owner,TableRewriteMaterial material){return delegate.prepare(key,owner,material);}
            public Optional<TableRewriteRecord> transition(TableRewriteKey key,String owner,long revision,TableRewriteRecord.Phase expected,TableRewriteRecord.Phase next,TableRewriteIdentity identity){
                var changed=delegate.transition(key,owner,revision,expected,next,identity);
                if(next==TableRewriteRecord.Phase.RESTORE_INTENT&&failOnce.compareAndSet(true,false))throw new IllegalStateException("fixture commit acknowledgement lost");return changed;
            }
            public Optional<TableRewriteRecord> claimRecovery(TableRewriteKey key,long revision,String owner){return delegate.claimRecovery(key,revision,owner);}
            public PendingPage pending(String scope,String logical,String after,int size){return delegate.pending(scope,logical,after,size);}
        };
        var failed=run(true,false,SyncOptionEnum.INSERT_UPDATE_DELETE);assertEquals(1,failed.getTotalTableFailCount().get());
        var pending=latest();assertEquals(TableRewriteRecord.Phase.RESTORE_INTENT,pending.phase());assertNotNull(pending.newIdentity());assertEquals(0,rows("target_rows").size());
        // MigrationService has returned; all controlled physical connections have completed before this explicit claim.
        assertEquals(0,drivers.runtimes().connections().snapshot().active());assertEquals(0,drivers.runtimes().connections().snapshot().quarantined());
        var claim=store().claimRecovery(pending.key(),pending.revision(),"recovery_owner_0002").orElseThrow();
        var props=new cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties();props.setTargetProductType(ProductTypeEnum.INFLUXDB3);props.setUrl(url());props.setPassword(token());props.setPoolSize(1);
        var pool=DataSourceUtils.createTargetDataSource(props,null,null,drivers.find(ProductTypeEnum.INFLUXDB3).orElseThrow(),drivers.runtimes());
        var receipt=DriverPoolFactory.physicalTermination(pool);
        try {
            try(var recoveryRuntime=new DbSwitchRuntime(DbSwitchProductRegistry.discover(),new DbSwitchOptions(16*1024*1024,true,false,15,0,300000,30000,0),drivers)){
                var synchronizer=recoveryRuntime.synchronizer(pool,ProductTypeEnum.INFLUXDB3);
                var binding=new TableRewriteBinding(SCOPE,claim.material().bindingFingerprint(),claim.material().oldIdentity(),claim.ownerToken(),store(),limits,true,()->{});
                synchronizer.reconcileTableRewrite(binding,claim);
            }
            var result=latest();
            assertEquals(TableRewriteRecord.Phase.APPLIED,result.phase());assertEquals(pending.newIdentity(),result.newIdentity());assertEquals(rows("source_rows"),rows("target_rows"));
        }finally{pool.close();receipt.toCompletableFuture().get(15,java.util.concurrent.TimeUnit.SECONDS);}
    }
    private cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult run(boolean admitted,boolean rejectProbe,SyncOptionEnum option)throws Exception {
        String url=url();
        var request=new MigrationRequest();var source=new SourceDataSourceProperties();source.setSourceProductType(ProductTypeEnum.INFLUXDB3);source.setUrl(url);source.setPassword(token());source.setSourceSchema("iox");source.setSourceIncludes("source_rows");source.setPoolSize(1);source.setFetchSize(1);source.setRegexTableMapper(List.of(new PatternMapper("^source_rows$","target_rows")));request.getSource().add(source);
        var target=request.getTarget();target.setTargetProductType(ProductTypeEnum.INFLUXDB3);target.setUrl(url);target.setPassword(token());target.setTargetSchema("iox");target.setPoolSize(1);target.setChangeDataSync(true);target.setChangeDataSyncType("PK_ORDER_COMPARE");target.setTargetSyncOption(option);
        if(admitted)target.setTableRewriteBindingProvider(new TableRewriteBindingProvider(){
            @Override public void authorizeManagementProbe(String s,String t,String logical){permissions.incrementAndGet();if(rejectProbe)throw new IllegalStateException("deployment denied");assertEquals("iox",s);assertEquals("target_rows",t);}
            @Override public TableRewriteBinding resolve(String s,String t,String logical,TableRewriteIdentity identity,String structure) {
                admissions.incrementAndGet();assertEquals(64,logical.length());assertEquals(64,structure.length());
                return new TableRewriteBinding(SCOPE,"c".repeat(64),identity,"fixture_owner_0001",decorateStore.apply(store()),limits,true,()->{});
            }
        });
        var options=new DbSwitchOptions(16*1024*1024,true,false,15,0,300000,30000,0);
        try(var runtime=new DbSwitchRuntime(DbSwitchProductRegistry.discover(),options,drivers)){return new MigrationService(request,ignored->{},null,runtime).run();}
    }
    private String errors(cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult result){return result.getTableResultList().stream().map(r->r.getErrorMsg()).toList().toString();}
    private PostgresTableRewriteStore store(){return new PostgresTableRewriteStore(control,schema+".journal",schema+".pages",SCOPE,"fixture",Map.of("fixture",secret),limits,10,(c,k)->{});}
    private TableRewriteRecord latest()throws Exception{try(var c=control.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT scope,logical_resource,operation FROM "+schema+".journal ORDER BY created_at DESC LIMIT 1")){assertTrue(r.next());return store().read(new TableRewriteKey(r.getString(1),r.getString(2),r.getString(3))).orElseThrow();}}
    private String url(){return "jdbc:arrow-flight-sql://127.0.0.1:"+URI.create(System.getProperty("datahub.test.influx.http")).getPort()+"/?useEncryption=0&database="+database;}
    private int count(String query)throws Exception{try(var c=control.getConnection();var s=c.createStatement();var r=s.executeQuery(query)){assertTrue(r.next());return r.getInt(1);}}
    private void sql(String sql)throws Exception{try(var c=control.getConnection();var s=c.createStatement()){s.execute(sql);}}
    private JsonNode rows(String table)throws Exception{return JSON.readTree(request("POST","/api/v3/query_sql",JSON.createObjectNode().put("db",database).put("q","SELECT * FROM "+table+" ORDER BY host NULLS LAST,time").put("format","json").toString(),"application/json"));}
    private void write(String body)throws Exception{request("POST","/api/v3/write_lp?db="+database+"&precision=nanosecond&accept_partial=false",body,"text/plain");}
    private String request(String method,String path,String body,String contentType)throws Exception{var response=http.send(HttpRequest.newBuilder(URI.create(System.getProperty("datahub.test.influx.http")+path)).timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+token()).header("Content-Type",contentType).method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());assertTrue(response.statusCode()>=200&&response.statusCode()<300,()->"Influx fixture status="+response.statusCode());return response.body();}
    private static String token(){return System.getenv("DATAHUB_INFLUX_TEST_TOKEN");}
}
