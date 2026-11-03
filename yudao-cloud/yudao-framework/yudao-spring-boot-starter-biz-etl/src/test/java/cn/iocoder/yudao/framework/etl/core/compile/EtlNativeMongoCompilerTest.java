package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class EtlNativeMongoCompilerTest {
    private static final EtlDataxConnectorCompiler.Binding BINDING = new EtlDataxConnectorCompiler.Binding(
            Set.of("read-mongodb", "write-mongodb"), null, null, List.of("mongo-native:27017"), "datahub_components",
            EtlDataxConnectorCompiler.Authentication.ANONYMOUS, Set.of("spark-mongodb"));

    @Test void compilesReadOnlyQueryAndFieldAliasesWithFailFastDeclaredTypes() {
        var plan = compile(false, source());
        assertEquals(Map.of("id", "BIGINT", "label", "STRING"), plan.table().columns());
        assertTrue(plan.initializationSql().contains("spark.mongodb.read.mode` 'FAILFAST'"));
        assertTrue(plan.initializationSql().contains("`source_id` AS `id`"));
        assertTrue(plan.initializationSql().contains("aggregation.pipeline"));
        assertTrue(plan.initializationSql().contains("O\\'Reilly")); assertFalse(plan.table().continuousSource());
    }

    @Test void outputExplicitlyConsumesBatchesAndIncrementalUpdateKeys() {
        var insert = compile(true, target());
        assertTrue(insert.initializationSql().contains("spark.mongodb.write.operationType` 'insert'"));
        assertTrue(insert.initializationSql().contains("spark.mongodb.write.maxBatchSize` '2'"));
        var update = new LinkedHashMap<>(target()); update.put("writeModeType", 2); update.put("primaryKeys", "id");
        var plan = compile(true, update);
        assertTrue(plan.initializationSql().contains("spark.mongodb.write.operationType` 'update'"));
        assertTrue(plan.initializationSql().contains("spark.mongodb.write.idFieldList` 'id'"));
        assertTrue(plan.initializationSql().contains("spark.mongodb.write.upsertDocument` 'true'"));
    }

    @Test void missingAuthenticationPluginOrConnectorAuthorizationCannotBecomeAnonymousExecution() {
        for (var binding : List.of(new EtlDataxConnectorCompiler.Binding(BINDING.connectorCodes(), null, null, BINDING.addresses(),
                        BINDING.database(), EtlDataxConnectorCompiler.Authentication.REQUIRED, BINDING.installedPlugins()),
                new EtlDataxConnectorCompiler.Binding(BINDING.connectorCodes(), null, null, BINDING.addresses(), BINDING.database(),
                        BINDING.authentication(), Set.of()),
                new EtlDataxConnectorCompiler.Binding(Set.of(), null, null, BINDING.addresses(), BINDING.database(),
                        BINDING.authentication(), BINDING.installedPlugins()))) {
            assertThrows(IllegalArgumentException.class, () -> EtlNativeMongoCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK,
                    false, "node", "read-mongodb", source(), binding));
        }
    }

    @Test void scriptsWritesQueriesUnknownOptionsAndImplicitCollectionOverwriteAreRejected() {
        for (var option : List.of(Map.<String, Object>of("whereCondition", "{\"$where\":\"return true\"}"),
                Map.<String, Object>of("whereCondition", "{\"$out\":\"other\"}"), Map.<String, Object>of("whereCondition", "id > 1"),
                Map.<String, Object>of("ifSql", 1), Map.<String, Object>of("readMode", "ID_INCREMENT"),
                Map.<String, Object>of("password", "forbidden"), Map.<String, Object>of("ifSql", 0.5))) {
            var config = new LinkedHashMap<>(source()); config.putAll(option);
            assertThrows(IllegalArgumentException.class, () -> compile(false, config));
        }
        for (var option : List.of(Map.<String, Object>of("writeModeType", 1), Map.<String, Object>of("writeModeType", 2),
                Map.<String, Object>of("writeModeType", 2.5), Map.<String, Object>of("batchSize", 0))) {
            var config = new LinkedHashMap<>(target()); config.putAll(option);
            assertThrows(IllegalArgumentException.class, () -> compile(true, config));
        }
        assertThrows(IllegalArgumentException.class, () -> EtlNativeMongoCompiler.compile(EtlNativeSqlCompiler.Engine.FLINK,
                true, "node", "read-mongodb", source(), BINDING));
    }

    @Test void emitsInsertAndUpsertProgramsForRealMongoSparkAcceptance() throws Exception {
        for (String mode : List.of("insert", "update")) {
            var source = compile(false, source()); var targetConfig = new LinkedHashMap<>(target());
            if (mode.equals("update")) { targetConfig.put("writeModeType", 2); targetConfig.put("primaryKeys", "id"); }
            var target = compile(true, targetConfig);
            var nodes = List.of(node("read", "read-db", Map.of("databaseId", 1, "tableName", source.table().physicalTable())),
                    node("upper", "string-operation", Map.of("rules", List.of(Map.of("inputField", "label", "caseMode", "UPPER")))),
                    node("origin", "add-constant", Map.of("fields", List.of(Map.of("name", "origin", "fieldType", "String", "value", "mongo-native")))),
                    node("write", "insert-db", Map.of("databaseId", 2, "tableName", target.table().physicalTable())));
            var edges = List.of(edge("read", "upper"), edge("upper", "origin"), edge("origin", "write"));
            var sql = EtlNativeSqlCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, nodes, edges,
                    (id, name, schema) -> id == 1 ? source.table() : target.table());
            assertTrue(sql.contains("INSERT INTO"));
            var path = Path.of("target", "native-mongo-sql", mode + ".sql"); Files.createDirectories(path.getParent());
            Files.writeString(path, source.initializationSql() + target.initializationSql() + sql);
        }
    }

    private static EtlNativeConnectorCompiler.TablePlan compile(boolean output, Map<String, Object> config) {
        return EtlNativeMongoCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, output ? "target" : "source",
                output ? "write-mongodb" : "read-mongodb", config, BINDING);
    }
    private static Map<String, Object> source() {
        return Map.of("tableName", "native_input", "whereCondition", "{\"label\":{\"$in\":[\"O'Reilly\",\"Beta\",\"gamma\"]}}",
                "columns", List.of(Map.of("name", "id", "type", "Long", "key", "source_id"), Map.of("name", "label", "type", "String")));
    }
    private static Map<String, Object> target() {
        return Map.of("tableName", "native_output", "batchSize", 2, "columns", List.of(Map.of("name", "id", "type", "Long"),
                Map.of("name", "label", "type", "String"), Map.of("name", "origin", "type", "String")));
    }
    private static EtlNodeDef node(String id, String code, Map<String, Object> config) {
        var node = new EtlNodeDef(); node.setNo(id); node.setName(id); node.setToolCode(code); node.setNodeJson(JSONUtil.toJsonStr(config)); return node;
    }
    private static EtlEdgeDef edge(String source, String target) { var edge = new EtlEdgeDef(); edge.setSourceNo(source); edge.setTargetNo(target); return edge; }
}
