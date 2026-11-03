package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler;
import cn.iocoder.yudao.framework.etl.core.model.*;
import cn.iocoder.yudao.framework.etl.core.script.*;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.IntegrationTaskDO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationNativeScriptBindingSupportTest {
    private final EtlNativeScriptBroker broker=mock(EtlNativeScriptBroker.class);
    private final IntegrationNativeScriptBindingsProperties properties=new IntegrationNativeScriptBindingsProperties();
    private final IntegrationRuntimeProperties runtime=new IntegrationRuntimeProperties();
    private final IntegrationDataxBindingsProperties http=new IntegrationDataxBindingsProperties();
    private final IntegrationNativeScriptBindingSupport support=new IntegrationNativeScriptBindingSupport();
    IntegrationNativeScriptBindingSupportTest()throws Exception {
        var factory=new StaticListableBeanFactory();factory.addBean("broker",broker);
        set("properties",properties);set("runtime",runtime);set("httpBindings",http);set("brokers",factory.getBeanProvider(EtlNativeScriptBroker.class));
        properties.setEnabled(true);properties.setEndpoint("https://broker.internal/v1/script");
        var identity=new IntegrationNativeScriptBindingsProperties.Identity();identity.setTaskId(1004L);identity.setWorkerGroup("isolated");identity.setEngine("SPARK");identity.setCertificateSha256("a".repeat(64));identity.setPrivateKeyRef("task1004.key");identity.setCertificateRef("task1004.cert");identity.setAuthorityRef("ca");
        var resource=new IntegrationNativeScriptBindingsProperties.ResourceBinding();resource.setIdentities(List.of(identity));resource.setHttpResourceRefs(Set.of("api"));properties.setResources(Map.of("scripts",resource));
        var grant=new IntegrationRuntimeProperties.ManagedSecretBinding();grant.setAllowedTaskIds(Set.of(1004L));grant.setWorkerGroups(Set.of("isolated"));grant.setEngines(Set.of("SPARK"));runtime.setManagedSecretBindings(Map.of("task1004.key",grant,"task1004.cert",grant,"ca",grant));
        var api=new IntegrationDataxBindingsProperties.ManagedBinding();api.setAllowedTaskIds(Set.of(1004L));api.setConnectorCodes(Set.of("rest-api"));api.setAddresses(List.of("https://example.internal/rows"));api.setInstalledPlugins(Set.of("spark-http"));http.setBindings(Map.of("api",api));
        var version=new EtlNativeScriptBroker.PreparedVersion("script-fixed",UUID.randomUUID().toString());when(broker.describeVersion(anyString(),anyString(),any())).thenReturn(version);when(broker.prepareVersion(anyString(),anyString(),any())).thenReturn(version);
    }
    private void set(String name,Object value)throws Exception{var field=support.getClass().getDeclaredField(name);field.setAccessible(true);field.set(support,value);}
    private IntegrationTaskDO task(){var task=new IntegrationTaskDO();task.setId(1004L);task.setWorkerGroup("isolated");task.setEngine("SPARK");task.setMode("OFFLINE");task.setEngineConfigJson("{\"parallelism\":1}");return task;}
    private static EtlNodeDef node(String id,String code,Map<String,Object> values){var node=new EtlNodeDef();node.setNo(id);node.setName(id);node.setToolCode(code);node.setNodeJson(JSONUtil.toJsonStr(values));return node;}
    private List<EtlNodeDef> original(){return List.of(node("token","custom-token",Map.of("resourceBindingId","scripts","code","public class Token implements EtlBase {}","tokenKey","login","workerState","BATCH","batchPolicy","PARTITION","maximumRows",100)),node("api","rest-api",Map.of("resourceBindingId","api","headerContextRefs",Map.of("Authorization",Map.of("tokenKey","login","prefix","Bearer ")),"apiConfig",Map.of("requestUrl","https://example.internal/rows","requestMethod","GET"))),node("sink","insert-db",Map.of("databaseId",2,"tableName","target")));}
    private IntegrationNativeScriptBindingSupport.Prepared compile(IntegrationTaskDO task,IntegrationNativeScriptBindingSupport.Purpose purpose){
        var original=original();var prepared=List.of(original.getFirst(),node("api","read-db",Map.of("databaseId",1,"tableName","source")),original.getLast());
        var first=new EtlEdgeDef();first.setSourceNo("token");first.setTargetNo("api");var second=new EtlEdgeDef();second.setSourceNo("api");second.setTargetNo("sink");
        return support.compile(task,prepared,List.of(first,second),(id,name,schema)->new EtlNativeSqlCompiler.Table(List.of(name),Map.of("id","BIGINT"),ProductTypeEnum.POSTGRESQL,null,name),"",original,purpose);
    }
    @Test void checkIsReadOnlyAndRunRequiresAnActivePublishedVersion(){
        assertEquals(1,compile(task(),IntegrationNativeScriptBindingSupport.Purpose.CHECK).plan().program().initializers().size());
        verify(broker,never()).prepareVersion(anyString(),anyString(),any());verify(broker,never()).prepareHttpResource(any(),anyString(),anyString(),any());
        assertThrows(IllegalArgumentException.class,()->compile(task(),IntegrationNativeScriptBindingSupport.Purpose.RUN));
    }
    @Test void publicationRegistersOnlyRealTaskBoundHttpResourceAndCompletesExplicitly(){
        var publication=compile(task(),IntegrationNativeScriptBindingSupport.Purpose.PUBLICATION);verify(broker).prepareHttpResource(any(),eq("api"),matches("[a-f0-9]{64}"),any());verify(broker,never()).activateVersions(any());
        support.complete(publication);verify(broker).activateVersions(publication.versions());
    }
    @Test void defaultEmptyTaskGroupEngineSecretsAndHttpResourceAuthorizationFailClosed(){
        var other=task();other.setWorkerGroup("another");assertThrows(IllegalArgumentException.class,()->compile(other,IntegrationNativeScriptBindingSupport.Purpose.PUBLICATION));
        runtime.setManagedSecretBindings(Map.of());assertThrows(IllegalArgumentException.class,()->compile(task(),IntegrationNativeScriptBindingSupport.Purpose.PUBLICATION));
        verify(broker,never()).prepareVersion(anyString(),anyString(),any());
    }
    @Test void flinkTokenPublicationFailsBeforePreparingVersion(){
        var task=task();task.setEngine("FLINK");
        var error=assertThrows(IllegalArgumentException.class,()->compile(task,IntegrationNativeScriptBindingSupport.Purpose.PUBLICATION));
        assertTrue(error.getMessage().contains("已开放"));verify(broker,never()).prepareVersion(anyString(),anyString(),any());
    }
    @Test void flinkTokenCanPublishAsTheStartupControlOfAnAllowedTableSourceWithoutOpeningRestApi() {
        var task = task(); task.setEngine("FLINK");
        var resource = properties.getResources().get("scripts"); resource.getIdentities().getFirst().setEngine("FLINK");
        runtime.getManagedSecretBindings().values().forEach(binding -> binding.setEngines(Set.of("FLINK")));
        var nodes = List.of(original().getFirst(), node("read", "read-db", Map.of("databaseId", 1, "tableName", "source")), original().getLast());
        var control = new EtlEdgeDef(); control.setSourceNo("token"); control.setTargetNo("read");
        var data = new EtlEdgeDef(); data.setSourceNo("read"); data.setTargetNo("sink");
        var prepared = support.compile(task, nodes, List.of(control, data), (id, name, schema) -> new EtlNativeSqlCompiler.Table(List.of(name), Map.of("id", "BIGINT"), ProductTypeEnum.POSTGRESQL, null, name), "", nodes, IntegrationNativeScriptBindingSupport.Purpose.PUBLICATION);
        assertEquals("FLINK", prepared.plan().program().engine()); assertEquals(1, prepared.plan().program().initializers().size());
        assertTrue(prepared.plan().program().stages().isEmpty()); assertEquals(1, prepared.plan().program().inserts().size());
        verify(broker).prepareVersion(anyString(), anyString(), any()); verify(broker, never()).prepareHttpResource(any(), anyString(), anyString(), any());
    }
    @Test void publicationFingerprintKeepsJsonbObjectKeysStableButIncludesExecutionSlotsAndEngineConfiguration()throws Exception{
        var method=IntegrationNativeScriptBindingSupport.class.getDeclaredMethod("publication",IntegrationTaskDO.class,List.class,List.class);method.setAccessible(true);
        var task=task();var first=new EtlEdgeDef();first.setSourceNo("left");first.setTargetNo("join");first.setInputPort("input-1");
        var firstConfig=new LinkedHashMap<String,Object>();firstConfig.put("tableName","source");firstConfig.put("databaseId",1);
        var nodes=List.of(node("left","read-db",firstConfig));
        String original=(String)method.invoke(null,task,nodes,List.of(first));
        var secondConfig=new LinkedHashMap<String,Object>();secondConfig.put("databaseId",1);secondConfig.put("tableName","source");
        var reordered=List.of(node("left","read-db",secondConfig));assertNotEquals(nodes.getFirst().getNodeJson(),reordered.getFirst().getNodeJson());
        assertEquals(original,method.invoke(null,task,reordered,List.of(first)));
        first.setInputPort("input-2");assertNotEquals(original,method.invoke(null,task,nodes,List.of(first)));first.setInputPort("input-1");
        first.setEdgeType(EtlEdgeDef.TYPE_CONTROL);assertNotEquals(original,method.invoke(null,task,nodes,List.of(first)));first.setEdgeType(EtlEdgeDef.TYPE_DATA);
        task.setEngineConfigJson("{\"parallelism\":2}");assertNotEquals(original,method.invoke(null,task,nodes,List.of(first)));
        task.setEngineConfigJson("{\"parallelism\":1,\"options\":{\"b\":2,\"a\":1}}");var engineOrder=method.invoke(null,task,nodes,List.of(first));
        task.setEngineConfigJson("{\"options\":{\"a\":1,\"b\":2},\"parallelism\":1}");assertEquals(engineOrder,method.invoke(null,task,nodes,List.of(first)));
        var second=new EtlEdgeDef();second.setSourceNo("right");second.setTargetNo("join");second.setInputPort("input-2");
        assertNotEquals(method.invoke(null,task,nodes,List.of(first,second)),method.invoke(null,task,nodes,List.of(second,first)));
        var right=node("right","read-db",Map.of("tableName","right","databaseId",1));
        assertNotEquals(method.invoke(null,task,List.of(nodes.getFirst(),right),List.of(first)),method.invoke(null,task,List.of(right,nodes.getFirst()),List.of(first)));
        task.setEngineConfigJson("{\"parallelism\":1}");task.setMode("REALTIME");assertNotEquals(original,method.invoke(null,task,nodes,List.of(first)));
    }
    @Test void minimalContextKeepsBrokerDisabledAndResolvesOptionalProvider(){
        try(var context=new AnnotationConfigApplicationContext()){
            context.registerBean(IntegrationNativeScriptBindingsProperties.class);context.registerBean(IntegrationRuntimeProperties.class);context.registerBean(IntegrationDataxBindingsProperties.class);context.register(IntegrationNativeScriptConfiguration.class,IntegrationNativeScriptBindingSupport.class);context.refresh();
            assertTrue(context.getBeansOfType(EtlNativeScriptBroker.class).isEmpty());assertNotNull(context.getBean(IntegrationNativeScriptBindingSupport.class));
        }
    }
}
