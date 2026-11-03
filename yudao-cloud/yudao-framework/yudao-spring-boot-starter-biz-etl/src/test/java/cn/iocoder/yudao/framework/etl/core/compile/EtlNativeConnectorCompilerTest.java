package cn.iocoder.yudao.framework.etl.core.compile;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;

import static org.junit.jupiter.api.Assertions.*;

class EtlNativeConnectorCompilerTest {
    private static final EtlNativeConnectorCompiler.KafkaBinding BINDING = new EtlNativeConnectorCompiler.KafkaBinding(
            "managed-kafka:9092", Set.of("input"), Set.of("output"), EtlNativeConnectorCompiler.Authentication.NONE, true);

    @Test void inputCompilesDeclaredJsonPathsAndExactOffsetOptionsWithoutCredentials() {
        var plan = compile(false, true, Map.of("topic", "input", "columns", List.of(
                Map.of("name", "identifier", "type", "Integer", "key", "$.payload.id")),
                "configParameters", "{\"group.id\":\"group-test\",\"auto.offset.reset\":\"earliest\",\"fetch.min.bytes\":1024}"));
        assertTrue(plan.initializationSql().contains("JSON_VALUE(`dh_payload`, '$.payload.id' RETURNING STRING ERROR ON ERROR)"));
        assertTrue(plan.initializationSql().contains("'properties.fetch.min.bytes' = '1024'"));
        assertTrue(plan.initializationSql().contains("'scan.startup.mode' = 'earliest-offset'"));
        assertTrue(plan.table().continuousSource()); assertEquals(Map.of("identifier", "BIGINT"), plan.table().columns());
        assertFalse(plan.initializationSql().contains("password"));
    }

    @Test void batchInputHasBoundedLatestEndingAndTimestampModeRequiresItsArgument() {
        var config = Map.<String, Object>of("topic", "input", "columns", fields());
        var plan = compile(false, false, config);
        assertTrue(plan.initializationSql().contains("'scan.bounded.mode' = 'latest-offset'")); assertFalse(plan.table().continuousSource());
        assertThrows(IllegalArgumentException.class, () -> compile(false, false, Map.of("topic", "input", "columns", fields(),
                "configParameters", Map.of("scan.startup.mode", "timestamp"))));
        var timestamp = compile(false, false, Map.of("topic", "input", "columns", fields(),
                "configParameters", Map.of("scan.startup.mode", "timestamp", "scan.startup.timestamp-millis", "9007199254740993")));
        assertTrue(timestamp.initializationSql().contains("'9007199254740993'"));
    }

    @Test void outputConsumesByteBatchProducerOptionsAndDeliveryGuarantee() {
        var plan = compile(true, true, Map.of("topic", "output", "columns", fields(), "batchSize", 12000,
                "configParameters", Map.of("batch.size", 12000, "compression.type", "gzip", "acks", "all", "sink.delivery-guarantee", "exactly-once")));
        assertTrue(plan.initializationSql().contains("'properties.batch.size' = '12000'"));
        assertTrue(plan.initializationSql().contains("'sink.transactional-id-prefix'"));
        assertTrue(plan.initializationSql().contains("'properties.compression.type' = 'gzip'"));
        assertFalse(plan.table().continuousSource());
    }

    @Test void unboundTopicMissingPluginAndImplicitAnonymousAccessFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> compile(true, true, Map.of("topic", "input", "columns", fields())));
        var secured = new EtlNativeConnectorCompiler.KafkaBinding("managed:9092", Set.of("input"), Set.of(), null, true);
        assertThrows(IllegalArgumentException.class, () -> EtlNativeConnectorCompiler.kafka(EtlNativeSqlCompiler.Engine.FLINK, true,
                "node", "read-kafka", Map.of("topic", "input", "columns", fields()), secured));
        var absent = new EtlNativeConnectorCompiler.KafkaBinding("managed:9092", Set.of("input"), Set.of(), EtlNativeConnectorCompiler.Authentication.NONE, false);
        assertThrows(IllegalArgumentException.class, () -> EtlNativeConnectorCompiler.kafka(EtlNativeSqlCompiler.Engine.FLINK, true,
                "node", "read-kafka", Map.of("topic", "input", "columns", fields()), absent));
    }

    @Test void unknownSecurityDeserializerAndConflictingOffsetParametersAreNeverIgnored() {
        for (var parameters : List.of(Map.of("sasl.jaas.config", "forbidden"), Map.of("value.deserializer", "user.Class"),
                Map.of("auto.offset.reset", "earliest", "scan.startup.mode", "latest-offset"),
                Map.of("scan.startup.specific-offsets", "partition:0,offset:2"))) {
            assertThrows(IllegalArgumentException.class, () -> compile(false, true,
                    Map.of("topic", "input", "columns", fields(), "configParameters", parameters)));
        }
        assertThrows(IllegalArgumentException.class, () -> compile(false, true,
                Map.of("topic", "input", "columns", fields(), "bootstrapServers", "unmanaged:9092")));
    }

    @Test void invalidSchemaBatchConflictsAndUntrueDeliveryClaimsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> compile(false, true,
                Map.of("topic", "input", "columns", List.of(Map.of("name", "id")))));
        assertThrows(IllegalArgumentException.class, () -> compile(true, true,
                Map.of("topic", "output", "columns", fields(), "batchSize", 12000, "configParameters", Map.of("batch.size", 10000))));
        assertThrows(IllegalArgumentException.class, () -> compile(true, true,
                Map.of("topic", "output", "columns", fields(), "configParameters", Map.of("acks", "0"))));
        assertThrows(IllegalArgumentException.class, () -> compile(false, true,
                Map.of("topic", "input", "columns", List.of(Map.of("name", "id", "type", "String", "key", "$.items[*]")))));
    }

    @Test void emitsNativeKafkaRoundTripProgramsForRealEngineAcceptance() throws Exception {
        var binding = new EtlNativeConnectorCompiler.KafkaBinding("kafka:9092", Set.of("datahub_component_native_input"),
                Set.of("datahub_component_native_output"), EtlNativeConnectorCompiler.Authentication.NONE, true);
        var targetFields = new java.util.ArrayList<>(fields()); targetFields.add(Map.of("name", "origin", "type", "String"));
        for (boolean streaming : List.of(false, true)) {
            var source = EtlNativeConnectorCompiler.kafka(EtlNativeSqlCompiler.Engine.FLINK, streaming, "acceptance-read",
                    "read-kafka", Map.of("topic", "datahub_component_native_input", "columns", fields(),
                            "configParameters", Map.of("group.id", "dh_native_acceptance_" + streaming, "auto.offset.reset", "earliest")), binding);
            var target = EtlNativeConnectorCompiler.kafka(EtlNativeSqlCompiler.Engine.FLINK, streaming, "acceptance-write",
                    "write-kafka", Map.of("topic", "datahub_component_native_output", "columns", targetFields, "batchSize", 12000,
                            "configParameters", Map.of("acks", "all", "compression.type", "gzip")), binding);
            var nodes = List.of(node("read", "read-db", Map.of("databaseId", 1, "tableName", source.table().physicalTable())),
                    node("upper", "string-operation", Map.of("rules", List.of(Map.of("inputField", "label", "caseMode", "UPPER")))),
                    node("constant", "add-constant", Map.of("fields", List.of(Map.of("name", "origin", "fieldType", "String", "value", "kafka-native")))),
                    node("write", "insert-db", Map.of("databaseId", 2, "tableName", target.table().physicalTable())));
            var edges = List.of(edge("read", "upper"), edge("upper", "constant"), edge("constant", "write"));
            String sql = EtlNativeSqlCompiler.compile(EtlNativeSqlCompiler.Engine.FLINK, streaming, nodes, edges,
                    (id, name, schema) -> id == 1 ? source.table() : target.table());
            assertTrue(sql.contains("UPPER("));
            Path file = Path.of("target", "native-kafka-sql", streaming ? "stream.sql" : "batch.sql");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "SET 'execution.target' = 'local';\nSET 'execution.runtime-mode' = '" + (streaming ? "STREAMING" : "BATCH")
                    + "';\nSET 'execution.attached' = 'true';\nSET 'table.dml-sync' = 'true';\nSET 'parallelism.default' = '1';\nSET 'pipeline.name' = 'datahub-kafka-component-acceptance';\n"
                    + source.initializationSql() + target.initializationSql() + sql);
        }
    }

    private static EtlNativeConnectorCompiler.TablePlan compile(boolean output, boolean streaming, Map<String, Object> config) {
        return EtlNativeConnectorCompiler.kafka(EtlNativeSqlCompiler.Engine.FLINK, streaming, "task:node",
                output ? "write-kafka" : "read-kafka", config, BINDING);
    }
    private static List<Map<String, Object>> fields() { return List.of(Map.of("name", "id", "type", "Integer"), Map.of("name", "label", "type", "String")); }
    private static EtlNodeDef node(String id, String code, Map<String, Object> config) {
        var node = new EtlNodeDef(); node.setNo(id); node.setName(id); node.setToolCode(code); node.setNodeJson(JSONUtil.toJsonStr(config)); return node;
    }
    private static EtlEdgeDef edge(String source, String target) {
        var edge = new EtlEdgeDef(); edge.setSourceNo(source); edge.setTargetNo(target); return edge;
    }
}
