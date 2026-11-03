package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.etl.core.model.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EtlNativeFlinkUpsertTest {
    private static final Map<String,String> FIELDS = Map.of("id","BIGINT","label","STRING");
    private static EtlNativeSqlCompiler.Table target(boolean verified) {
        return new EtlNativeSqlCompiler.Table(List.of("verified_sink"), FIELDS, ProductTypeEnum.POSTGRESQL,"public","target",false,
                verified ? new EtlNativeSqlCompiler.JdbcUpsertSink(List.of("id")) : null);
    }
    private static List<EtlNodeDef> nodes(Map<String,Object> changes) {
        var read = new EtlNodeDef(); read.setNo("read"); read.setName("read"); read.setToolCode("read-db"); read.setNodeJson(JSONUtil.toJsonStr(Map.of("databaseId",1,"tableName","source")));
        var config = new LinkedHashMap<String,Object>(Map.of("databaseId",2,"tableName","target","dataSyncType",2,"upsertKeys",List.of("id"),
                "fieldMappings",List.of(Map.of("source","id","target","id"),Map.of("source","label","target","label")))); config.putAll(changes);
        var write = new EtlNodeDef(); write.setNo("write"); write.setName("write"); write.setToolCode("insert-db"); write.setNodeJson(JSONUtil.toJsonStr(config)); return List.of(read,write);
    }
    private static String compile(EtlNativeSqlCompiler.Engine engine,boolean streaming,boolean verified,Map<String,Object> changes) {
        var edge = new EtlEdgeDef(); edge.setSourceNo("read"); edge.setTargetNo("write");
        return EtlNativeSqlCompiler.compile(engine,streaming,nodes(changes),List.of(edge),(database,name,schema)->database==1
                ? new EtlNativeSqlCompiler.Table(List.of("source"),FIELDS,ProductTypeEnum.POSTGRESQL,"public","source",true) : target(verified));
    }
    @Test void onlyVerifiedRealtimeFlinkTargetCanExecuteIncrementalUpdates() {
        assertTrue(compile(EtlNativeSqlCompiler.Engine.FLINK,true,true,Map.of()).contains("INSERT INTO `verified_sink`"));
        assertThrows(IllegalArgumentException.class,()->compile(EtlNativeSqlCompiler.Engine.FLINK,true,false,Map.of()));
        assertThrows(IllegalArgumentException.class,()->compile(EtlNativeSqlCompiler.Engine.SPARK,false,true,Map.of()));
        assertThrows(IllegalArgumentException.class,()->compile(EtlNativeSqlCompiler.Engine.FLINK,false,true,Map.of()));
        for(var bad:List.of(Map.<String,Object>of("upsertKeys",List.of("label")),Map.<String,Object>of("upsertKeys",List.of("id","id")),Map.<String,Object>of("upsertKeys",List.of()),
                Map.<String,Object>of("fieldMappings",List.of(Map.of("source","label","target","label")))))
            assertThrows(IllegalArgumentException.class,()->compile(EtlNativeSqlCompiler.Engine.FLINK,true,true,bad));
    }
    @Test void wrapperInheritsActualCatalogPkInsteadOfCreatingOneAndNeverCopiesCredentials() throws Exception {
        var original = new EtlNativeSqlCompiler.Table(List.of("default_catalog","default_database","original_sink"),FIELDS,ProductTypeEnum.POSTGRESQL,"public","target");
        var plan = EtlNativeFlinkJdbcSinkSql.wrap(original,"jdbc:postgresql://127.0.0.1:5432/fixture",List.of("id"),1024);
        assertTrue(plan.initializationSql().contains("LIKE `default_catalog`.`default_database`.`original_sink` (INCLUDING ALL OVERWRITING OPTIONS)"));
        assertFalse(plan.initializationSql().contains("PRIMARY KEY")); assertFalse(plan.initializationSql().contains("jdbc:postgresql"));
        assertEquals(List.of("id"),plan.table().jdbcUpsertSink().primaryKeys());
        Path path=Path.of("target/native-flink-upsert/wrapper.sql");Files.createDirectories(path.getParent());Files.writeString(path,plan.initializationSql());
    }
}
