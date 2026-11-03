package cn.iocoder.yudao.framework.etl.messages;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.compile.*;
import cn.iocoder.yudao.framework.etl.core.model.*;
import java.nio.file.*;
import java.util.*;

/** 从实际组件编译器生成Spark Kafka验收SQL，不复制字段/位点转换规则。 */
public final class BoundedMessageCompilerFixture {
    public static void main(String[] arguments) throws Exception {
        var binding = new EtlNativeConnectorCompiler.KafkaBinding("bounded-kafka:9092", Set.of("qa_bounded_input"), Set.of("qa_bounded_output"), EtlNativeConnectorCompiler.Authentication.NONE, false, true);
        var fields = List.<Map<String,Object>>of(Map.of("name", "id", "type", "Long"), Map.of("name", "label", "type", "String"), Map.of("name", "amount", "type", "BigNumber", "length", "38", "precision", "8"));
        EtlNativeConnectorCompiler.TablePlan source, target;
        if (arguments.length > 1 && arguments[1].equals("rabbitmq")) {
            var rabbit = new EtlNativeRabbitCompiler.Binding("bounded-rabbit", 5672, "/", Set.of("qa_bounded_input"), Set.of("qa_bounded_output"), "qa.rabbit", false, false, true, false);
            source = EtlNativeRabbitCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, "qa:source", "read-rabbitmq", Map.of("topic", "qa_bounded_input", "columns", fields), rabbit, Set.of("qa.rabbit"));
            target = EtlNativeRabbitCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, "qa:target", "write-rabbitmq", Map.of("topic", "qa_bounded_output", "columns", fields), rabbit, Set.of("qa.rabbit"));
        } else {
            source = EtlNativeConnectorCompiler.kafka(EtlNativeSqlCompiler.Engine.SPARK, false, "qa:source", "read-kafka", Map.of("topic", "qa_bounded_input", "columns", fields), binding);
            target = EtlNativeConnectorCompiler.kafka(EtlNativeSqlCompiler.Engine.SPARK, false, "qa:target", "write-kafka", Map.of("topic", "qa_bounded_output", "columns", fields), binding);
        }
        var nodes = List.of(node("read", "read-db", Map.of("databaseId", 1, "tableName", source.table().physicalTable())),
                node("upper", "string-operation", Map.of("rules", List.of(Map.of("inputField", "label", "caseMode", "UPPER")))),
                node("write", "insert-db", Map.of("databaseId", 2, "tableName", target.table().physicalTable())));
        String sql = EtlNativeSqlCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, nodes, List.of(edge("read", "upper"), edge("upper", "write")), (id, name, schema) -> id == 1 ? source.table() : target.table());
        Files.writeString(Path.of(arguments[0]), source.initializationSql() + target.initializationSql() + sql);
    }
    private static EtlNodeDef node(String id, String code, Map<String,Object> config) { var value = new EtlNodeDef(); value.setNo(id); value.setName(id); value.setToolCode(code); value.setNodeJson(JSONUtil.toJsonStr(config)); return value; }
    private static EtlEdgeDef edge(String source, String target) { var value = new EtlEdgeDef(); value.setSourceNo(source); value.setTargetNo(target); return value; }
}
