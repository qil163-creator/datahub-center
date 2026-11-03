package cn.iocoder.yudao.framework.dbswitch.product.elasticsearch;

import cn.iocoder.yudao.framework.dbswitch.common.config.*;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.*;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.spi.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.AbstractDataSource;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@EnabledIfSystemProperty(named="datahub.test.elasticsearch.http", matches="http://127[.]0[.]0[.]1:[0-9]+")
@Timeout(120)
class ElasticsearchSchemaElasticsearchTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static ConfiguredJdbcDrivers drivers;
    private static String user, password;
    private DbSwitchRuntime runtime;
    private String source, target, alias;
    private final Set<String> indices = new LinkedHashSet<>();
    private final List<NativeTargetPlan> plans = new ArrayList<>();
    private final List<NativeRecoveryResource> resources = new ArrayList<>();
    private static final String COMPLEX = """
        {"settings":{"number_of_replicas":0,"max_ngram_diff":2,"analysis":{"analyzer":{"native_text":{"tokenizer":"standard","filter":["lowercase"]}}}},
         "mappings":{"dynamic":"strict","_meta":{"业务":"原样","lookup":"metadata only"},"properties":{
           "nested":{"type":"nested","properties":{"name":{"type":"keyword"}}},
           "scaled":{"type":"scaled_float","scaling_factor":100},
           "text":{"type":"text","analyzer":"native_text","fields":{"raw":{"type":"keyword"}}},
           "scaled_alias":{"type":"alias","path":"scaled"}}}}
        """;
    @BeforeAll static void configure() throws Exception {
        user=UUID.randomUUID().toString();password=UUID.randomUUID().toString();
        Path jar=Path.of(System.getProperty("datahub.test.elasticsearch.driver")).toRealPath();
        var spec=new DbSwitchProperties.Driver();spec.setPath(jar.toString());
        spec.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        spec.setDriverClassName(ProductTypeEnum.ELASTICSEARCH.getDriveClassName());
        drivers=new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.ELASTICSEARCH,spec));
    }
    @AfterAll static void closeDrivers(){if(drivers!=null)drivers.close();}
    @BeforeEach void setup(){
        runtime=new DbSwitchRuntime(DbSwitchProductRegistry.discover(),DbSwitchOptions.defaults(),drivers);
        String id=UUID.randomUUID().toString().replace("-","");source="es89_source_"+id;target="es89_target_"+id;alias="es89_alias_"+id;
    }
    @AfterEach void cleanup() throws Exception {
        for(var plan:plans) resources.addAll(plan.recoveryResources());
        for(var resource:resources) {
            if(resource.role()==NativeRecoveryResource.Role.STAGING && resource.name().matches("dbswitch_(stage|backup)_[a-f0-9]{32}")) {
                var response=request("GET","/"+resource.name()+"?flat_settings=true",null,200,404);
                if(response.has(resource.name())) {
                    String actual=response.path(resource.name()).path("settings").path("index.uuid").asText();
                    if(resource.expectedPhysicalIdentity()!=null)assertEquals(resource.expectedPhysicalIdentity(),actual);
                    request("DELETE","/"+resource.name(),null,200);
                }
            }
        }
        for(String index:indices)request("DELETE","/"+index,null,200,404);
        runtime.close();assertEquals(0,drivers.runtimes().connections().snapshot().active());
    }
    @Test void complexNativeOnlyCreatePreservesAllMappingsSettingsAndNeverGrantsDataFrame() throws Exception {
        create(source,COMPLEX);create(target,"{\"settings\":{\"number_of_replicas\":0}}");
        var reader=reader();var definition=reader.describeNativeTable("es",source);
        assertTrue(reader.nativeMigrationColumns("es",source).stream().anyMatch(c->c.getFieldType()==Types.OTHER));
        var plan=track(reader.prepareNativeTarget(definition,"es",target,identity(definition),true));
        assertTrue(plan.requiresExclusiveDdlOwnership());assertThrows(IllegalArgumentException.class,plan::preflightDataTransfer);
        plan.execute();assertEquals(mapping(source),mapping(target));
        assertEquals(settings(source).path("index.analysis.analyzer.native_text.filter"),settings(target).path("index.analysis.analyzer.native_text.filter"));
        assertEquals("2",settings(target).path("index.max_ngram_diff").asText());
        assertThrows(IllegalStateException.class,plan::execute);
    }
    @Test void publicOnlyCreateAcceptsNestedStructureAndFullRefusesBeforeReplacingSentinel() throws Exception {
        create(source,COMPLEX);create(target,"{\"settings\":{\"number_of_replicas\":0},\"mappings\":{\"properties\":{\"keep\":{\"type\":\"keyword\"}}}}");
        request("PUT","/"+target+"/_doc/keep?refresh=true","{\"keep\":\"保留\"}",201);
        var request=migration();request.getTarget().setTargetDrop(true);request.getTarget().setOnlyCreate(false);
        String before=uuid(target);assertFalse(new MigrationService(request,ignored->{},null,runtime).run().getIfAllSuccess().get());
        assertEquals(before,uuid(target));assertEquals("保留",request("GET","/"+target+"/_doc/keep",null,200).path("_source").path("keep").asText());
        request.getTarget().setOnlyCreate(true);request.getTarget().setExclusiveTargetDdlOwnership(false);
        assertFalse(new MigrationService(request,ignored->{},null,runtime).run().getIfAllSuccess().get());assertEquals(before,uuid(target));
        request.getTarget().setExclusiveTargetDdlOwnership(true);
        var outcome=new MigrationService(request,ignored->{},null,runtime).run();assertTrue(outcome.getIfAllSuccess().get());
        // 公共回执保留恢复资源，测试只清理本次生成并核对身份的资源。
        for(var result:outcome.getTableResultList()) resources.addAll(result.getNativeRecoveryResources());
        assertEquals(mapping(source),mapping(target));
    }
    @Test void aliasesWithFiltersAndRoutingArePreservedOnlyWhenUnowned() throws Exception {
        ObjectNode body=(ObjectNode)JSON.readTree(COMPLEX);body.set("aliases",JSON.createObjectNode().set(alias,
                JSON.readTree("{\"filter\":{\"range\":{\"scaled\":{\"gte\":0}}},\"search_routing\":\"one\"}")));
        create(source,body.toString());var definition=reader().describeNativeTable("es",source);
        assertThrows(IllegalArgumentException.class,()->reader().prepareNativeTarget(definition,"es",target,identity(definition),false));
        request("DELETE","/"+source+"/_alias/"+alias,null,200);
        indices.add(target);var plan=track(reader().prepareNativeTarget(definition,"es",target,identity(definition),false));plan.execute();
        assertEquals(body.path("aliases"),request("GET","/"+target+"/_alias",null,200).path(target).path("aliases"));
    }
    @Test void addNestedPropertiesAndMultifieldsPreservesUuidDocumentsAndRejectsTypeChanges() throws Exception {
        String original="{\"settings\":{\"number_of_replicas\":0},\"mappings\":{\"properties\":{\"n\":{\"type\":\"nested\",\"properties\":{\"a\":{\"type\":\"keyword\"}}},\"t\":{\"type\":\"text\"}}}}";
        create(source,original);create(target,original);request("PUT","/"+target+"/_doc/keep?refresh=true","{\"n\":[{\"a\":\"原值\"}],\"t\":\"old\"}",201);
        request("PUT","/"+source+"/_mapping","{\"properties\":{\"n\":{\"type\":\"nested\",\"properties\":{\"b\":{\"type\":\"long\"}}},\"t\":{\"type\":\"text\",\"fields\":{\"raw\":{\"type\":\"keyword\"}}},\"added\":{\"type\":\"boolean\"}}}",200);
        String before=uuid(target);var definition=reader().describeNativeTable("es",source);
        var plan=track(reader().prepareNativeSchemaEvolution(definition,"es",target));assertFalse(plan.replacesExisting());plan.execute();
        assertEquals(before,uuid(target));assertEquals(mapping(source),mapping(target));assertEquals("old",request("GET","/"+target+"/_doc/keep",null,200).path("_source").path("t").asText());
        var bad=edit(definition,e->((ObjectNode)e.path("body").path("mappings").path("properties").path("added")).put("type","keyword"));
        assertThrows(IllegalArgumentException.class,()->reader().prepareNativeSchemaEvolution(bad,"es",target));assertEquals(before,uuid(target));
    }
    @Test void invalidServerMappingCannotDeleteOrBlockAnExistingTarget() throws Exception {
        create(source,COMPLEX);create(target,"{\"settings\":{\"number_of_replicas\":0}}");
        request("PUT","/"+target+"/_doc/keep?refresh=true","{\"keep\":1}",201);String before=uuid(target);
        var definition=reader().describeNativeTable("es",source);
        var invalid=edit(definition,e->((ObjectNode)e.path("body").path("mappings").path("properties").path("scaled")).put("scaling_factor",-1));
        var plan=track(reader().prepareNativeTarget(invalid,"es",target,identity(invalid),true));
        assertThrows(UnconfirmedWriteException.class,plan::execute);assertEquals(before,uuid(target));
        assertFalse(settings(target).path("index.blocks.write").asBoolean(false));assertEquals(1,count(target));
    }
    @Test void successfulReplaceRetainsVerifiedCloneEvenWhenSourceIsDisabled() throws Exception {
        create(source,COMPLEX);create(target,"{\"settings\":{\"number_of_replicas\":0},\"mappings\":{\"_source\":{\"enabled\":false},\"properties\":{\"keep\":{\"type\":\"keyword\"}}}}");
        request("PUT","/"+target+"/_doc/keep?refresh=true","{\"keep\":\"索引数据\"}",201);String before=uuid(target);
        var def=reader().describeNativeTable("es",source);var plan=track(reader().prepareNativeTarget(def,"es",target,identity(def),true));plan.execute();
        assertNotEquals(before,uuid(target));assertEquals(0,count(target));
        var backup=plan.recoveryResources().stream().filter(r->r.name().startsWith("dbswitch_backup_")).findFirst().orElseThrow();
        assertEquals(backup.expectedPhysicalIdentity(),uuid(backup.name()));assertEquals(1,count(backup.name()));
        assertEquals(1,request("POST","/"+backup.name()+"/_count","{\"query\":{\"term\":{\"keep\":\"索引数据\"}}}",200).path("count").asInt());
    }
    @Test void targetDeletedWithLostAcknowledgementIsUnknownAndKeepsRecoveryClone() throws Exception {
        create(source,COMPLEX);create(target,"{\"settings\":{\"number_of_replicas\":0}}");
        request("PUT","/"+target+"/_doc/keep?refresh=true","{\"keep\":\"saved\"}",201);
        var def=reader().describeNativeTable("es",source);var plan=track(reader().prepareNativeTarget(def,"es",target,identity(def),true));
        try(var mocks=mockStatic(ElasticsearchNativeSession.class,CALLS_REAL_METHODS)) {
            mocks.when(()->ElasticsearchNativeSession.open(any(),anyLong(),anyInt())).thenAnswer(invocation->{
                var nativeSession=(ElasticsearchNativeSession)invocation.callRealMethod();var spy=spy(nativeSession);
                doAnswer(call->{call.callRealMethod();throw new SQLException("injected lost delete ACK");}).when(spy).request("DELETE","/"+target,null);
                return spy;
            });
            assertThrows(UnconfirmedWriteException.class,plan::execute);
        }
        assertFalse(request("GET","/"+target+"?flat_settings=true",null,404).has(target));
        var backup=plan.recoveryResources().stream().filter(r->r.name().startsWith("dbswitch_backup_")).findFirst().orElseThrow();assertEquals(1,count(backup.name()));
        assertEquals("saved",request("GET","/"+backup.name()+"/_doc/keep",null,200).path("_source").path("keep").asText());
        assertThrows(IllegalStateException.class,plan::execute);
    }
    @Test void staleGenerationAndTemplateChangesRefuseBeforeAnyTargetMutation() throws Exception {
        create(source,COMPLEX);create(target,"{\"settings\":{\"number_of_replicas\":0}}");var def=reader().describeNativeTable("es",source);
        var stale=track(reader().prepareNativeTarget(def,"es",target,identity(def),true));
        request("DELETE","/"+target,null,200);create(target,"{\"settings\":{\"number_of_replicas\":0}}");String before=uuid(target);
        assertThrows(IllegalStateException.class,stale::execute);assertEquals(before,uuid(target));
        var plan=track(reader().prepareNativeTarget(def,"es",target,identity(def),true));String template="es89_template_"+UUID.randomUUID().toString().replace("-","");
        try {
            request("PUT","/_index_template/"+template,"{\"index_patterns\":[\""+target+"\"],\"template\":{\"settings\":{\"number_of_replicas\":0}}}",200);
            assertThrows(IllegalStateException.class,plan::execute);assertEquals(before,uuid(target));
        } finally {request("DELETE","/_index_template/"+template,null,200);}
    }
    @Test void filteredSourceRuntimeDynamicTemplatesAndCopyToRemainExactStructureOnly() throws Exception {
        create(source,"""
            {"settings":{"number_of_replicas":0},"mappings":{"_source":{"includes":["keep*"]},
             "dynamic_templates":[{"strings":{"match_mapping_type":"string","mapping":{"type":"keyword"}}}],
             "runtime":{"calculated":{"type":"long","script":{"source":"emit(1)"}}},
             "properties":{"keep":{"type":"keyword","copy_to":"all"},"all":{"type":"text"}}}}
            """);
        var definition=reader().describeNativeTable("es",source);indices.add(target);
        var plan=track(reader().prepareNativeTarget(definition,"es",target,identity(definition),false));
        assertThrows(IllegalArgumentException.class,plan::preflightDataTransfer);plan.execute();assertEquals(mapping(source),mapping(target));
        assertThrows(IllegalStateException.class,()->runtime.writer(dataSource(),ProductTypeEnum.ELASTICSEARCH,WriteMode.INSERT).prepareWrite("es",target,List.of("_id","keep")));
    }
    @Test void cloneWithLostAcknowledgementKeepsOriginalBlockedAndExposesUnconfirmedBackup() throws Exception {
        create(source,COMPLEX);create(target,"{\"settings\":{\"number_of_replicas\":0}}");
        request("PUT","/"+target+"/_doc/keep?refresh=true","{\"keep\":\"saved\"}",201);String before=uuid(target);
        var def=reader().describeNativeTable("es",source);var plan=track(reader().prepareNativeTarget(def,"es",target,identity(def),true));
        String backup=plan.recoveryResources().stream().filter(r->r.name().startsWith("dbswitch_backup_")).findFirst().orElseThrow().name();
        try(var mocks=mockStatic(ElasticsearchNativeSession.class,CALLS_REAL_METHODS)) {
            mocks.when(()->ElasticsearchNativeSession.open(any(),anyLong(),anyInt())).thenAnswer(invocation->{
                var nativeSession=(ElasticsearchNativeSession)invocation.callRealMethod();var spy=spy(nativeSession);
                doAnswer(call->{call.callRealMethod();throw new SQLException("injected lost clone ACK");}).when(spy).request(eq("POST"),eq("/"+target+"/_clone/"+backup),anyString());return spy;
            });assertThrows(UnconfirmedWriteException.class,plan::execute);
        }
        assertEquals(before,uuid(target));assertEquals(1,count(target));assertTrue(settings(target).path("index.blocks.write").asBoolean(false));
        assertTrue(plan.recoveryResources().stream().anyMatch(r->r.name().equals(backup)&&r.expectedPhysicalIdentity()==null));
        request("GET","/_cluster/health/"+backup+"?wait_for_status=yellow&timeout=20s",null,200);assertEquals(1,count(backup));
        assertThrows(IllegalStateException.class,plan::execute);
    }
    @Test void mappingAppliedWithLostAcknowledgementIsUnknownWithoutReplacingTheIndex() throws Exception {
        create(source,"{\"settings\":{\"number_of_replicas\":0},\"mappings\":{\"properties\":{\"keep\":{\"type\":\"keyword\"},\"added\":{\"type\":\"long\"}}}}");
        create(target,"{\"settings\":{\"number_of_replicas\":0},\"mappings\":{\"properties\":{\"keep\":{\"type\":\"keyword\"}}}}");
        request("PUT","/"+target+"/_doc/keep?refresh=true","{\"keep\":\"saved\"}",201);String before=uuid(target);
        var plan=track(reader().prepareNativeSchemaEvolution(reader().describeNativeTable("es",source),"es",target));
        try(var mocks=mockStatic(ElasticsearchNativeSession.class,CALLS_REAL_METHODS)) {
            mocks.when(()->ElasticsearchNativeSession.open(any(),anyLong(),anyInt())).thenAnswer(invocation->{
                var nativeSession=(ElasticsearchNativeSession)invocation.callRealMethod();var spy=spy(nativeSession);
                doAnswer(call->{call.callRealMethod();throw new SQLException("injected lost mapping ACK");}).when(spy).request(eq("PUT"),eq("/"+target+"/_mapping"),anyString());return spy;
            });assertThrows(UnconfirmedWriteException.class,plan::execute);
        }
        assertEquals(before,uuid(target));assertEquals(1,count(target));assertEquals(mapping(source),mapping(target));assertThrows(IllegalStateException.class,plan::execute);
    }
    @Test void unknownAnalyzerIsRejectedOnStagingBeforeOldTargetIsBlocked() throws Exception {
        create(source,COMPLEX);create(target,"{\"settings\":{\"number_of_replicas\":0}}");String before=uuid(target);
        var def=reader().describeNativeTable("es",source);
        var bad=edit(def,e->((ObjectNode)e.path("body").path("mappings").path("properties").path("text")).put("analyzer","not_installed"));
        var plan=track(reader().prepareNativeTarget(bad,"es",target,identity(bad),true));
        assertThrows(UnconfirmedWriteException.class,plan::execute);assertEquals(before,uuid(target));assertFalse(settings(target).path("index.blocks.write").asBoolean(false));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void routedSourceFullRefusesBeforeReplacingSentinelButOnlyCreateStillWorks(boolean required)throws Exception {
        create(source,"{\"settings\":{\"number_of_replicas\":0},\"mappings\":{\"_routing\":{\"required\":"+required+"},\"properties\":{\"keep\":{\"type\":\"keyword\"}}}}");
        create(target,"{\"settings\":{\"number_of_replicas\":0},\"mappings\":{\"properties\":{\"keep\":{\"type\":\"keyword\"}}}}");
        request("PUT","/"+source+"/_doc/same?routing=route1&refresh=true","{\"keep\":\"routed value\"}",201);
        request("PUT","/"+target+"/_doc/sentinel?refresh=true","{\"keep\":\"retain target\"}",201);String before=uuid(target);
        var request=migration();request.getTarget().setTargetDrop(true);
        var rejected=new MigrationService(request,ignored->{},null,runtime).run();assertFalse(rejected.getIfAllSuccess().get());
        assertEquals(before,uuid(target));assertEquals("retain target",request("GET","/"+target+"/_doc/sentinel",null,200).path("_source").path("keep").asText());
        assertThrows(IllegalStateException.class,()->reader().queryTableData("es",source,List.of("_id","keep")));
        request.getTarget().setOnlyCreate(true);var created=new MigrationService(request,ignored->{},null,runtime).run();assertTrue(created.getIfAllSuccess().get());
        for(var result:created.getTableResultList())resources.addAll(result.getNativeRecoveryResources());assertEquals(mapping(source),mapping(target));
    }
    @Test void unobservedRoutingPermissionDoesNotBlockOnlyStructureButCannotAuthorizeFull()throws Exception {
        create(source,"{\"settings\":{\"number_of_replicas\":0},\"mappings\":{\"properties\":{\"keep\":{\"type\":\"keyword\"}}}}");
        NativeTableDefinition definition;
        try(var mocks=mockStatic(ElasticsearchNativeSession.class,CALLS_REAL_METHODS)) {
            mocks.when(()->ElasticsearchNativeSession.open(any(),anyLong(),anyInt())).thenAnswer(invocation->{
                var nativeSession=(ElasticsearchNativeSession)invocation.callRealMethod();var spy=spy(nativeSession);
                doReturn(new ElasticsearchNativeSession.Response(403,JSON.createObjectNode())).when(spy).request(eq("POST"),eq("/"+source+"/_count"),anyString());return spy;
            });definition=reader().describeNativeTable("es",source);
        }
        indices.add(target);var plan=track(reader().prepareNativeTarget(definition,"es",target,identity(definition),false));
        assertThrows(IllegalArgumentException.class,plan::preflightDataTransfer);plan.execute();assertEquals(mapping(source),mapping(target));
    }
    @Test void routingAppearingAfterThePlanCannotBeSilentlyReducedToIdOnRead()throws Exception {
        create(source,"{\"settings\":{\"number_of_replicas\":0},\"mappings\":{\"properties\":{\"keep\":{\"type\":\"keyword\"}}}}");
        var definition=reader().describeNativeTable("es",source);indices.add(target);
        var plan=track(reader().prepareNativeTarget(definition,"es",target,identity(definition),false));plan.preflightDataTransfer();
        request("PUT","/"+source+"/_doc/later?routing=late&refresh=true","{\"keep\":\"late\"}",201);
        plan.execute();assertThrows(IllegalStateException.class,()->reader().queryTableData("es",source,List.of("_id","keep")));assertEquals(0,count(target));
    }
    @Test void optionalRoutingTargetRefusesUpsertPreparationAndLaterBulkWithoutTouchingValues()throws Exception {
        create(target,"{\"settings\":{\"number_of_replicas\":0,\"refresh_interval\":\"-1\"},\"mappings\":{\"properties\":{\"keep\":{\"type\":\"keyword\"}}}}");
        var writer=new cn.iocoder.yudao.framework.dbswitch.dbwriter.elasticsearch.ElasticsearchUpsertWriter(dataSource());
        writer.prepareUpsert("es",target,List.of("_id","keep"),List.of("_id"));
        request("PUT","/"+target+"/_doc/same?routing=route","{\"keep\":\"untouched\"}",201);
        assertTrue(request("GET","/"+target+"/_doc/same?routing=route",null,200).path("found").asBoolean());
        assertEquals(0,count(target),"NRT count cannot see the unrefreshed routed document");
        assertThrows(IllegalStateException.class,()->writer.write(List.of("_id","keep"),List.<Object[]>of(new Object[]{"same","wrong"})));
        assertThrows(IllegalArgumentException.class,()->writer.prepareUpsert("es",target,List.of("_id","keep"),List.of("_id")));
        assertEquals(1,count(target));assertEquals("untouched",request("GET","/"+target+"/_doc/same?routing=route",null,200).path("_source").path("keep").asText());
    }
    private NativeTargetPlan track(NativeTargetPlan p){plans.add(p);return p;}
    private static Map<String,String> identity(NativeTableDefinition d){var m=new LinkedHashMap<String,String>();d.fields().forEach(n->m.put(n,n));return m;}
    private static NativeTableDefinition edit(NativeTableDefinition d,java.util.function.Consumer<ObjectNode> change)throws Exception{var e=(ObjectNode)JSON.readTree(d.document());change.accept(e);return new NativeTableDefinition(d.product(),d.format(),e.toString(),d.fields());}
    private cn.iocoder.yudao.framework.dbswitch.dbcommon.database.IDatabaseOperator reader(){return runtime.reader(dataSource(),ProductTypeEnum.ELASTICSEARCH);}
    private void create(String name,String body)throws Exception{indices.add(name);request("PUT","/"+name,body,200);}
    private JsonNode mapping(String name)throws Exception{return request("GET","/"+name+"/_mapping",null,200).path(name).path("mappings");}
    private JsonNode settings(String name)throws Exception{return request("GET","/"+name+"/_settings?flat_settings=true",null,200).path(name).path("settings");}
    private String uuid(String name)throws Exception{return settings(name).path("index.uuid").asText();}
    private long count(String name)throws Exception{return request("GET","/"+name+"/_count",null,200).path("count").longValue();}
    private AbstractDataSource dataSource(){return new AbstractDataSource(){public Connection getConnection()throws SQLException{return runtime.openConnection(ProductTypeEnum.ELASTICSEARCH,null,url(),user,password);}public Connection getConnection(String u,String p)throws SQLException{throw new SQLException("fixed fixture");}};}
    private MigrationRequest migration(){
        var request=new MigrationRequest();var s=new SourceDataSourceProperties();s.setSourceProductType(ProductTypeEnum.ELASTICSEARCH);s.setUrl(url());s.setUsername(user);s.setPassword(password);
        s.setDriverClassName(ProductTypeEnum.ELASTICSEARCH.getDriveClassName());s.setSourceSchema("es");s.setSourceIncludes(source);s.setPoolSize(1);
        s.setRegexTableMapper(List.of(new PatternMapper("^"+source+"$",target)));request.getSource().add(s);
        var t=request.getTarget();t.setTargetProductType(ProductTypeEnum.ELASTICSEARCH);t.setUrl(url());t.setUsername(user);t.setPassword(password);t.setDriverClassName(ProductTypeEnum.ELASTICSEARCH.getDriveClassName());
        t.setTargetSchema("es");t.setTargetTable(target);t.setPoolSize(1);t.setExclusiveTargetDdlOwnership(true);return request;
    }
    private static String url(){var endpoint=URI.create(System.getProperty("datahub.test.elasticsearch.http"));return "jdbc:jest://"+endpoint.getHost()+":"+endpoint.getPort();}
    private static JsonNode request(String method,String path,String body,int... accepted)throws Exception{
        try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()){
            var request=HttpRequest.newBuilder(URI.create(System.getProperty("datahub.test.elasticsearch.http")+path)).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json")
                    .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());assertTrue(Arrays.stream(accepted).anyMatch(s->s==response.statusCode()),"Native fixture HTTP "+response.statusCode()+": "+response.body());return JSON.readTree(response.body());
        }
    }
}
