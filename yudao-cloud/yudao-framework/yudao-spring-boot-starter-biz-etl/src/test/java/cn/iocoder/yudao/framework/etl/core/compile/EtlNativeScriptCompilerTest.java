package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.model.*;
import cn.iocoder.yudao.framework.etl.core.script.*;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EtlNativeScriptCompilerTest {
    static EtlNodeDef node(String id,String code,Map<String,Object> config){var node=new EtlNodeDef();node.setNo(id);node.setName(id);node.setToolCode(code);node.setNodeJson(JSONUtil.toJsonStr(config));return node;}
    static EtlEdgeDef edge(String source,String target){var edge=new EtlEdgeDef();edge.setSourceNo(source);edge.setTargetNo(target);return edge;}
    static Map<String,Object> script(){return new LinkedHashMap<>(Map.of("resourceBindingId","isolated","code","public class Source implements EtlBase {}","workerState","BATCH","batchPolicy","PARTITION","maximumRows",100));}
    static EtlScriptBrokerClient.Binding bind(EtlNativeScriptCompiler.Definition definition){return new EtlScriptBrokerClient.Binding("https://broker.internal/v1/script",UUID.randomUUID().toString(),"script-fixed",EtlNativeScriptCompiler.sha(definition.source()),"1004","isolated","SPARK","key","cert","ca");}
    static EtlNativeSqlCompiler.Tables tables(){return(id,name,schema)->new EtlNativeSqlCompiler.Table(List.of(name),Map.of("id","BIGINT","amount","DECIMAL(24,4)"),ProductTypeEnum.POSTGRESQL,null,name);}
    @Test void tokenInitializerHasNoSqlDataParentAndOnlyDirectApiReceivesItsKey(){
        var config=script();config.put("tokenKey","login");var token=node("token","custom-token",config);
        var read=node("read","read-db",Map.of("databaseId",1,"tableName","source"));var write=node("write","insert-db",Map.of("databaseId",2,"tableName","target"));
        var nodes=List.of(token,read,write);var edges=List.of(edge("token","read"),edge("read","write"));
        assertEquals(Set.of("login"),EtlNativeScriptCompiler.contextKeys("read",nodes,edges));assertEquals(Set.of(),EtlNativeScriptCompiler.contextKeys("write",nodes,edges));
        var plan=EtlNativeScriptCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK,false,1,nodes,edges,tables(),"CREATE TEMPORARY VIEW extra AS SELECT ';' AS value;",EtlNativeScriptCompilerTest::bind);
        assertEquals(1,plan.program().initializers().size());assertTrue(plan.program().stages().isEmpty());assertEquals(1,plan.program().initialization().size());
        assertEquals(plan.program(),EtlNativeScriptProgram.fromArguments(plan.program().argumentChunks().toArray(String[]::new)));
        assertTrue(plan.analysis().nodes().get("token").outputFields().isEmpty());assertFalse(plan.program().inserts().getFirst().contains("token"));
    }
    @Test void flinkOfflineTokenProducesRealInitializerWithFlinkBoundIdentity() {
        var config = script(); config.put("tokenKey", "login");
        var nodes = List.of(node("token", "custom-token", config), node("read", "read-db", Map.of("databaseId", 1, "tableName", "source")), node("write", "insert-db", Map.of("databaseId", 2, "tableName", "target")));
        var plan = EtlNativeScriptCompiler.compile(EtlNativeSqlCompiler.Engine.FLINK, false, 1, nodes, List.of(edge("token", "read"), edge("read", "write")), tables(), "", definition ->
                new EtlScriptBrokerClient.Binding("https://broker.internal/v1/script", UUID.randomUUID().toString(), "script-fixed", EtlNativeScriptCompiler.sha(definition.source()), "1004", "isolated", "FLINK", "key", "cert", "ca"));
        assertEquals(1, plan.program().initializers().size()); assertEquals("FLINK", plan.program().initializers().getFirst().binding().engine());
        assertEquals("FLINK", plan.program().engine()); assertTrue(plan.program().stages().isEmpty());
    }

    @Test void javaStageCarriesFullPartitionAndExactDecimalSchemaWithoutSourceInProgram(){
        var config=script();config.put("outputFields",List.of(Map.of("columnName","total","columnType","DECIMAL(24,4)","nullable",false)));
        var nodes=List.of(node("read","read-db",Map.of("databaseId",1,"tableName","source")),node("java","java-script",config),node("write","insert-db",Map.of("databaseId",2,"tableName","target","fieldMappings",List.of(Map.of("source","total","target","amount")))));
        var plan=EtlNativeScriptCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK,false,1,nodes,List.of(edge("read","java"),edge("java","write")),tables(),"",EtlNativeScriptCompilerTest::bind);
        var stage=plan.program().stages().getFirst();assertTrue(stage.partitionBatch());assertEquals("DECIMAL(24,4)",stage.outputFields().getFirst().sqlType());assertFalse(String.join("",plan.program().argumentChunks()).contains("public class"));
    }
    @Test void flinkRealtimeTokenIsAStartupInitializerAndNeverBecomesAWindowedDataStage(){
        var config=script();config.put("tokenKey","login");var definitions=new ArrayList<EtlNativeScriptCompiler.Definition>();
        var nodes=List.of(node("token","custom-token",config),node("read","read-db",Map.of("databaseId",1,"tableName","source")),node("write","insert-db",Map.of("databaseId",2,"tableName","target")));
        var edges=List.of(edge("token","read"),edge("read","write"));
        EtlNativeSqlCompiler.Tables continuousTables=(id,name,schema)->new EtlNativeSqlCompiler.Table(List.of(name),Map.of("id","BIGINT","amount","DECIMAL(24,4)"),ProductTypeEnum.POSTGRESQL,null,name,id==1);
        var plan=EtlNativeScriptCompiler.compile(EtlNativeSqlCompiler.Engine.FLINK,true,1,nodes,edges,continuousTables,"",definition->{definitions.add(definition);return new EtlScriptBrokerClient.Binding("https://broker.internal/v1/script",UUID.randomUUID().toString(),"script-fixed",EtlNativeScriptCompiler.sha(definition.source()),"1004","isolated","FLINK","key","cert","ca");});
        assertTrue(plan.program().streaming());assertEquals(1,plan.program().initializers().size());assertTrue(plan.program().stages().isEmpty());
        assertEquals(EtlScriptRunner.Language.QDATA_TOKEN,definitions.getFirst().language());assertTrue(definitions.getFirst().partitionBatch());assertEquals(0,definitions.getFirst().flushMillis());
        config.put("batchPolicy","COUNT_OR_TIME");config.put("flushMillis",1000);
        assertThrows(IllegalArgumentException.class,()->EtlNativeScriptCompiler.compile(EtlNativeSqlCompiler.Engine.FLINK,true,1,List.of(node("token","custom-token",config),nodes.get(1),nodes.get(2)),edges,tables(),"",EtlNativeScriptCompilerTest::bind));
    }
    @Test void legacyBatchSizeAndUnboundedImplicitPoliciesAreNotSilentlyConsumed(){
        var read=node("read","read-db",Map.of("databaseId",1,"tableName","source"));var write=node("write","insert-db",Map.of("databaseId",2,"tableName","target"));
        for(String changed:List.of("workerState","batchPolicy","maximumRows","batchSize","flushMillis")){
            var config=script();if(Set.of("workerState","batchPolicy","maximumRows").contains(changed))config.remove(changed);else config.put(changed,1);
            assertThrows(IllegalArgumentException.class,()->EtlNativeScriptCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK,false,1,List.of(read,node("java","java-script",config),write),List.of(edge("read","java"),edge("java","write")),tables(),"",EtlNativeScriptCompilerTest::bind));
        }
        assertThrows(IllegalArgumentException.class,()->EtlNativeScriptCompiler.canonical("TIMESTAMP(9)",EtlNativeSqlCompiler.Engine.SPARK));
    }
}
