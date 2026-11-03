package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import cn.iocoder.yudao.framework.dbswitch.core.service.IMetaDataByJdbcService;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionInfo;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.schema.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.IntegrationTaskDO;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlConnectionResolverAdapter;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Types;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IntegrationSchemaServiceTest {
    private final DbSwitchRuntime runtime = mock(DbSwitchRuntime.class);
    private final IntegrationFlowCompiler compiler = spy(new IntegrationFlowCompiler());
    private final IntegrationSchemaService service = spy(new IntegrationSchemaService());
    {
        ReflectionTestUtils.setField(service, "compiler", compiler);
        ReflectionTestUtils.setField(service, "schemas", new EtlSchemaService(runtime));
        ReflectionTestUtils.setField(service, "connections", mock(EtlConnectionResolverAdapter.class));
    }

    @Test void selectedFieldsRenameConstantsAndSinkMappingsRetainRealConstraintsAndProducerNames() {
        var name = new EtlFieldMeta("name", "VARCHAR"); name.setLength(100L); name.setNullable(false); name.setSourceName("表输入");
        var amount = new EtlFieldMeta("amount", "NUMERIC", true, 38, 0, null); amount.setSourceName("表输入");
        var target = new EtlFieldMeta("person", "VARCHAR"); target.setLength(200L);
        Map<String, List<EtlFieldMeta>> metadata = Map.of("source", List.of(new EtlFieldMeta("id", "BIGINT"), name, amount), "target", List.of(target, new EtlFieldMeta("label", "VARCHAR")));
        metadata(metadata);
        var nodes = List.of(node("read", "表输入", "read-db", 1, Map.of("databaseId", 1, "tableName", "source", "selectedColumns", List.of("name", "amount"))),
                node("rename", "字段修改", "column-map", 2, Map.of("regexColumnMapper", List.of(Map.of("fromPattern", "^name$", "toValue", "person")))),
                node("constant", "增加常量", "add-constant", 2, Map.of("fields", List.of(Map.of("name", "label", "fieldType", "String", "value", "fixed")))),
                node("write", "表输出", "insert-db", 3, Map.of("databaseId", 1, "tableName", "target", "fieldMappings", List.of(Map.of("source", "person", "target", "person", "enabled", true), Map.of("source", "amount", "target", "person", "enabled", false), Map.of("source", "label", "target", "label", "enabled", true)))));
        var result = service.resolve(task(), graph(nodes, chain("read", "rename", "constant", "write")));
        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        assertEquals(List.of("name", "amount"), result.getNodes().get("read").getOutputFields().stream().map(EtlFieldMeta::getName).toList());
        var renamed = field(result, "rename", "person"); assertEquals(100L, renamed.getLength()); assertEquals("字段修改", renamed.getSourceName());
        assertEquals(0, field(result, "constant", "amount").getScale()); assertEquals(38, field(result, "constant", "amount").getPrecision());
        assertEquals("增加常量", field(result, "constant", "label").getSourceName());
        var written = result.getNodes().get("write").getOutputFields(); assertEquals(List.of("person", "label"), written.stream().map(EtlFieldMeta::getName).toList());
        assertEquals(200L, written.getFirst().getLength()); assertEquals("表输出", written.getFirst().getSourceName());
        assertTrue(result.getNodes().get("write").getComplete());
    }

    @Test void explicitFieldSelectionUsesNativeCastShapeAndClearsIncompatibleSourceConstraints() {
        var source = new EtlFieldMeta("name", "VARCHAR"); source.setLength(100L); source.setNullable(false); source.setSourceName("源输入");
        metadata(Map.of("source", List.of(source, new EtlFieldMeta("drop_me", "BIGINT")), "target", List.of(new EtlFieldMeta("alias", "VARCHAR"))));
        var nodes = List.of(node("read", "源输入", "read-db", 1, Map.of("databaseId", 1, "tableName", "source")),
                node("map", "字段选择", "column-map", 2, Map.of("columnMappingMode", "FIELDS", "fieldSelections", List.of(Map.of("name", "name", "alias", "alias"), Map.of("name", "drop_me")), "removedFields", List.of("drop_me"))),
                node("write", "输出", "insert-db", 3, Map.of("databaseId", 1, "tableName", "target")));
        var result = service.resolve(task(), graph(nodes, chain("read", "map", "write")));
        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        assertEquals(List.of("alias"), result.getNodes().get("map").getOutputFields().stream().map(EtlFieldMeta::getName).toList());
        assertEquals(100L, field(result, "map", "alias").getLength()); assertEquals("字段选择", field(result, "map", "alias").getSourceName());
        var definition = cn.iocoder.yudao.module.dataforge.service.etl.EtlGraphConverter.toNodeDefs(nodes);
        var cfg = JSONUtil.parseObj(definition.get(1).getNodeJson()); cfg.set("fieldSelections", List.of(Map.of("name", "name", "alias", "alias", "type", "BIGINT"))); definition.get(1).setNodeJson(cfg.toString());
        metadata(Map.of("source", List.of(source, new EtlFieldMeta("drop_me", "BIGINT")), "target", List.of(new EtlFieldMeta("alias", "BIGINT"))));
        var edges = cn.iocoder.yudao.module.dataforge.service.etl.EtlGraphConverter.toEdgeDefs(chain("read", "map", "write"));
        var analysis = compiler.analyzeNative(task(), definition, edges);
        assertEquals(Map.of("alias", "BIGINT"), analysis.nodes().get("map").outputFields());
        assertTrue(analysis.sql().contains("CAST(`name` AS BIGINT)"));
        var converted = service.resolve(task(), graph(List.of(nodes.getFirst(), node("map", "字段选择", "column-map", 2, cfg), nodes.getLast()), chain("read", "map", "write")));
        assertTrue(converted.getErrors().isEmpty(), converted.getErrors().toString());
        var convertedField = field(converted, "map", "alias");
        assertEquals("BIGINT", convertedField.getDataType()); assertEquals("字段选择", convertedField.getSourceName());
        assertNull(convertedField.getLength()); assertNull(convertedField.getNullable());
        assertNull(convertedField.getPrecision()); assertNull(convertedField.getScale()); assertNull(convertedField.getTimezone());
        assertTrue(converted.getNodes().get("map").getComplete());
        for (var unsupported : List.of(Map.of("type", "STRUCT<value:INT>"), Map.of("type", "BIGINT", "length", "100"))) {
            var rule = new LinkedHashMap<String, Object>(unsupported); rule.put("name", "name"); rule.put("alias", "alias");
            cfg.set("fieldSelections", List.of(rule)); definition.get(1).setNodeJson(cfg.toString());
            assertThrows(IllegalArgumentException.class, () -> compiler.analyzeNative(task(), definition, edges));
        }
    }

    @Test void rowColumnV2UsesNativeOutputShapeAndOnlyVerifiedTypeConstraints() {
        metadata(Map.of("source", List.of(new EtlFieldMeta("group_id", "BIGINT"), new EtlFieldMeta("code", "VARCHAR"), new EtlFieldMeta("amount", "NUMERIC")),
                "target", List.of(new EtlFieldMeta("group_id", "BIGINT"), new EtlFieldMeta("total", "NUMERIC"))));
        var config = Map.<String,Object>of("mode", "ROW2COL", "rowColumnVersion", 2, "groupFields", List.of("group_id"), "keyField", "code", "pivotMappings", List.of(
                Map.of("targetField", "total", "sourceField", "amount", "keyValue", "sale", "type", "DECIMAL", "length", "20", "precision", "4", "aggregation", "Sum")));
        var nodes = List.of(node("read", "输入", "read-db", 1, Map.of("databaseId", 1, "tableName", "source")), node("convert", "列转行", "row-column", 2, config), node("write", "输出", "insert-db", 3, Map.of("databaseId", 1, "tableName", "target")));
        var result = service.resolve(task(), graph(nodes, chain("read", "convert", "write")));
        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        var total = field(result, "convert", "total"); assertEquals("DECIMAL(20,4)", total.getDataType());
        assertEquals(20, total.getPrecision()); assertEquals(4, total.getScale()); assertNull(total.getLength()); assertEquals("列转行", total.getSourceName());
        assertTrue(result.getNodes().get("convert").getComplete());
        doThrow(new IllegalArgumentException("未绑定环境")).when(compiler).analyzeNative(any(), anyList(), anyList());
        var partial = service.resolve(task(), graph(nodes, chain("read", "convert", "write")));
        assertEquals(List.of("group_id", "total"), partial.getNodes().get("convert").getOutputFields().stream().map(EtlFieldMeta::getName).toList());
        assertNull(field(partial, "convert", "total").getDataType()); assertNull(field(partial, "convert", "total").getPrecision()); assertFalse(partial.getNodes().get("convert").getComplete());
    }

    @Test void unsupportedScriptKeepsPartialFieldNamesAndNeverInventsStringType() {
        metadata(Map.of("source", List.of(new EtlFieldMeta("amount", "NUMERIC", true, 38, 0, null))));
        doThrow(new IllegalArgumentException("脚本未支持")).when(compiler).analyzeNative(any(), anyList(), anyList());
        var result = service.resolve(task(), graph(List.of(node("read", "输入", "read-db", 1, Map.of("databaseId", 1, "tableName", "source")),
                node("script", "Java", "java-script", 4, Map.of())), chain("read", "script")));
        var fields = result.getNodes().get("script").getOutputFields(); assertEquals("amount", fields.getFirst().getName());
        assertNull(fields.getFirst().getDataType()); assertNull(fields.getFirst().getPrecision());
        assertFalse(result.getNodes().get("script").getComplete()); assertFalse(result.getWarnings().isEmpty());
    }

    @Test void declaredScriptOutputIsPartialAndMissingTypesStayUnknown() {
        metadata(Map.of("source", List.of(new EtlFieldMeta("amount", "NUMERIC"))));
        doThrow(new IllegalArgumentException("脚本未支持")).when(compiler).analyzeNative(any(), anyList(), anyList());
        var result = service.resolve(task(), graph(List.of(node("read", "输入", "read-db", 1, Map.of("databaseId", 1, "tableName", "source")),
                node("script", "Java", "java-script", 4, Map.of("outputFields", List.of(Map.of("name", "declared", "type", "BIGINT"), Map.of("name", "unknown"))))), chain("read", "script")));
        assertEquals("BIGINT", field(result, "script", "declared").getDataType());
        assertNull(field(result, "script", "unknown").getDataType()); assertEquals("Java", field(result, "script", "declared").getSourceName());
        assertFalse(result.getNodes().get("script").getComplete()); assertTrue(result.getWarnings().stream().anyMatch(warning -> warning.contains("配置声明")));
    }

    @Test void branchPortFieldsRemainIndependentAndDownstreamNeverReadsAnotherBranchSummary() {
        metadata(Map.of("source", List.of(new EtlFieldMeta("id", "BIGINT"), new EtlFieldMeta("name", "VARCHAR")),
                "yes_target", List.of(new EtlFieldMeta("id", "BIGINT")), "no_target", List.of(new EtlFieldMeta("name", "VARCHAR"))));
        var nodes = List.of(node("read", "输入", "read-db", 1, Map.of("databaseId", 1, "tableName", "source")),
                node("condition", "分发", "data-condition", 2, Map.of("branches", List.of(Map.of("id", "yes", "type", "IF", "fieldName", "id", "operator", "GT", "value", "1", "fields", List.of("id")), Map.of("id", "no", "type", "ELSE", "fields", List.of("name"))))),
                node("yes", "真输出", "insert-db", 3, Map.of("databaseId", 2, "tableName", "yes_target")),
                node("no", "假输出", "insert-db", 3, Map.of("databaseId", 2, "tableName", "no_target")));
        var edges = List.<Map<String, Object>>of(Map.of("sourceNodeId", "read", "targetNodeId", "condition"),
                Map.of("sourceNodeId", "condition", "targetNodeId", "yes", "sourcePort", "yes"), Map.of("sourceNodeId", "condition", "targetNodeId", "no", "sourcePort", "no"));
        var result = service.resolve(task(), graph(nodes, edges)); assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        var ports = result.getNodes().get("condition").getOutputPorts();
        assertEquals(List.of("id"), ports.get("yes").stream().map(EtlFieldMeta::getName).toList());
        assertEquals(List.of("name"), ports.get("no").stream().map(EtlFieldMeta::getName).toList());
        assertEquals(List.of("id"), result.getNodes().get("yes").getInputFields().stream().map(EtlFieldMeta::getName).toList());
        assertEquals(List.of("name"), result.getNodes().get("no").getInputFields().stream().map(EtlFieldMeta::getName).toList());
        var bad = new ArrayList<>(edges); bad.set(1, Map.of("sourceNodeId", "condition", "targetNodeId", "yes", "sourcePort", "unknown"));
        var rejected = service.resolve(task(), graph(nodes, bad));
        assertTrue(rejected.getErrors().stream().anyMatch(error -> error.contains("输出端口")));
        assertTrue(rejected.getNodes().get("yes").getInputFields().isEmpty());
    }

    @Test void declaredConnectorAndScriptFactsStayExplicitAndPartialWithoutBindings() {
        doThrow(new IllegalArgumentException("绑定未配置")).when(compiler).analyzeNative(any(), anyList(), anyList());
        var nodes = List.of(node("read", "文件声明", "read-csv", 1, Map.of("columns", List.of(
                Map.of("name", "text", "type", "STRING", "length", "120", "nullable", false),
                Map.of("name", "amount", "type", "DECIMAL", "length", "24", "precision", "0"),
                Map.of("name", "unknown", "length", "-1", "precision", "1.5", "nullable", "false")))),
                node("script", "脚本声明", "java-script", 4, Map.of("outputFields", List.of(
                        Map.of("name", "total", "type", "DECIMAL", "precision", "38", "scale", "6", "nullable", true)))));
        var result = service.resolve(task(), graph(nodes, chain("read", "script")));
        var text = field(result, "read", "text"); assertEquals(120L, text.getLength()); assertEquals(false, text.getNullable());
        var amount = field(result, "read", "amount"); assertEquals(24, amount.getPrecision()); assertEquals(0, amount.getScale()); assertNull(amount.getLength());
        var unknown = field(result, "read", "unknown"); assertNull(unknown.getDataType()); assertNull(unknown.getLength()); assertNull(unknown.getPrecision()); assertNull(unknown.getNullable());
        var total = field(result, "script", "total"); assertEquals(38, total.getPrecision()); assertEquals(6, total.getScale()); assertEquals(true, total.getNullable());
        assertFalse(result.getNodes().get("read").getComplete()); assertFalse(result.getNodes().get("script").getComplete());
        assertTrue(result.getWarnings().stream().anyMatch(warning -> warning.contains("配置声明")));
    }

    @Test void joinKeepsBothSourceNamesForCollidingInputFields() {
        var left = new EtlFieldMeta("id", "BIGINT"); left.setSourceName("左输入");
        var right = new EtlFieldMeta("id", "BIGINT"); right.setSourceName("右输入");
        metadata(Map.of("left", List.of(left), "right", List.of(right), "target", List.of(new EtlFieldMeta("id", "BIGINT"))));
        var nodes = List.of(node("left", "左输入", "read-db", 1, Map.of("databaseId", 1, "tableName", "left")),
                node("right", "右输入", "read-db", 1, Map.of("databaseId", 1, "tableName", "right")),
                node("join", "Join", "multi-join", 2, Map.of("keyField", "id", "maxOutputRows", 100)),
                node("write", "输出", "insert-db", 3, Map.of("databaseId", 1, "tableName", "target")));
        var edges = List.of(Map.<String, Object>of("sourceNodeId", "left", "targetNodeId", "join", "properties", Map.of("inputPort", "input-1")), Map.<String, Object>of("sourceNodeId", "right", "targetNodeId", "join", "properties", Map.of("inputPort", "input-2")), Map.<String, Object>of("sourceNodeId", "join", "targetNodeId", "write"));
        var result = service.resolve(task(), graph(nodes, edges));
        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        assertEquals("左输入 | 右输入", result.getNodes().get("join").getInputFields().getFirst().getSourceName());
        assertEquals("BIGINT", field(result, "join", "id").getDataType());
    }

    @Test void databaseFactsUseCharacterLengthAndDecimalEvidenceWithoutIntegerDisplayPrecision() {
        var source = new EtlNodeDef(); source.setName("真实输入"); source.setToolCode("read-db"); source.setNodeJson("{\"databaseId\":1,\"tableName\":\"source\"}");
        var access = mock(ExecutionAccessService.class); var connections = mock(EtlConnectionResolverAdapter.class);
        var conn = new EtlConnectionInfo(); conn.setProductType(ProductTypeEnum.POSTGRESQL); conn.setSchema("public");
        when(connections.resolve(1L)).thenReturn(conn); var jdbc = mock(cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl.class); when(runtime.jdbcMetadata(any(), any())).thenReturn(jdbc);
        var number = column("amount", Types.NUMERIC, "NUMERIC", null, 38, 0); var text = column("name", Types.VARCHAR, "VARCHAR", 120L, null, null); var integer = column("id", Types.BIGINT, "BIGINT", null, 64, 0);
        when(jdbc.queryTableSchema(any(), any(), any(), eq("public"), eq("source"), eq(10_000))).thenReturn(new TableSchemaMetadata(List.of(number, text, integer), List.of()));
        ReflectionTestUtils.setField(service, "access", access); ReflectionTestUtils.setField(service, "connections", connections);
        var fields = service.databaseFields(source, new HashMap<>());
        assertEquals(38, fields.getFirst().getPrecision()); assertEquals(0, fields.getFirst().getScale()); assertNull(fields.getFirst().getLength());
        assertEquals(120L, fields.get(1).getLength()); assertNull(fields.get(1).getPrecision()); assertNull(fields.get(2).getPrecision());
        verify(access).requireDatabase(1L); verify(jdbc, never()).querySqlColumnMeta(any(), any(), any(), any());
    }

    @Test void sourceSqlIsRestrictedAndNeverClaimsNativeConstraintsAndSinkCannotInjectSqlMode() {
        var access = mock(ExecutionAccessService.class); var connections = mock(EtlConnectionResolverAdapter.class);
        var conn = new EtlConnectionInfo(); conn.setProductType(ProductTypeEnum.POSTGRESQL); conn.setSchema("public");
        when(connections.resolve(1L)).thenReturn(conn); var jdbc = mock(cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl.class);
        when(runtime.jdbcMetadata(any(), any())).thenReturn(jdbc);
        var column = column("amount", Types.NUMERIC, "NUMERIC", null, 38, 0);
        when(jdbc.querySqlColumnMeta(any(), any(), any(), anyString())).thenReturn(List.of(column));
        when(jdbc.queryTableSchema(any(), any(), any(), eq("public"), eq("source"), eq(10_000))).thenReturn(new TableSchemaMetadata(List.of(column), List.of()));
        ReflectionTestUtils.setField(service, "access", access); ReflectionTestUtils.setField(service, "connections", connections);
        var node = new EtlNodeDef(); node.setName("SQL输入"); node.setToolCode("read-db");
        node.setNodeJson("{\"databaseId\":1,\"ifSql\":1,\"sql\":\"select amount from source\"}");
        var fields = service.databaseFields(node, new HashMap<>());
        assertEquals("NUMERIC", fields.getFirst().getDataType()); assertNull(fields.getFirst().getPrecision()); assertNull(fields.getFirst().getNullable());
        node.setNodeJson("{\"databaseId\":1,\"ifSql\":1,\"sql\":\"delete from source\"}");
        assertThrows(IllegalArgumentException.class, () -> service.databaseFields(node, new HashMap<>()));
        clearInvocations(jdbc); node.setToolCode("insert-db"); node.setNodeJson("{\"databaseId\":1,\"ifSql\":1,\"sql\":\"delete from source\",\"tableName\":\"source\"}");
        service.databaseFields(node, new HashMap<>()); verify(jdbc, never()).querySqlColumnMeta(any(), any(), any(), any());
    }

    private void metadata(Map<String, List<EtlFieldMeta>> fields) {
        doAnswer(call -> {
            var node = (EtlNodeDef) call.getArgument(0); return fields.getOrDefault(JSONUtil.parseObj(node.getNodeJson()).getStr("tableName"), List.of()).stream().map(EtlFieldMeta::copy).toList();
        }).when(service).databaseFields(any(), anyMap());
        doAnswer(call -> EtlNativeSqlCompiler.analyze(EtlNativeSqlCompiler.Engine.SPARK, false, call.getArgument(1), call.getArgument(2), (id, name, schema) -> {
            var columns = new LinkedHashMap<String, String>(); fields.getOrDefault(name, List.of()).forEach(field -> columns.put(field.getName(), field.getDataType()));
            return new EtlNativeSqlCompiler.Table(List.of("spark_catalog", "default", name), columns, ProductTypeEnum.POSTGRESQL, "public", name);
        })).when(compiler).analyzeNative(any(), anyList(), anyList());
    }
    private static ColumnDescription column(String name, int jdbc, String type, Long length, Integer precision, Integer scale) {
        var column = new ColumnDescription(); column.setFieldName(name); column.setFieldTypeName(type); column.setFieldType(jdbc);
        column.setMetadataEvidence(new ColumnMetadataEvidence(MetadataProvenance.unknown(), MetadataValue.value(type), MetadataValue.unknown(), MetadataValue.value(false),
                precision == null ? MetadataValue.unknown() : MetadataValue.value(precision), scale == null ? MetadataValue.unknown() : MetadataValue.value(scale),
                length == null ? MetadataValue.unknown() : MetadataValue.value(length), MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown())); return column;
    }
    private static EtlFieldMeta field(EtlSchemaResult result, String node, String field) { return result.getNodes().get(node).getOutputFields().stream().filter(value -> field.equals(value.getName())).findFirst().orElseThrow(); }
    private static IntegrationTaskDO task() { var task = new IntegrationTaskDO(); task.setEngine("SPARK"); task.setMode("OFFLINE"); return task; }
    private static Map<String, Object> node(String id, String name, String code, int type, Map<String, Object> config) { return Map.of("id", id, "properties", Map.of("name", name, "toolCode", code, "toolType", type, "nodeJson", config)); }
    private static String graph(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) { return new JSONObject().set("nodes", nodes).set("edges", edges).toString(); }
    private static List<Map<String, Object>> chain(String... nodes) { var edges = new ArrayList<Map<String, Object>>(); for (int i=1;i<nodes.length;i++) edges.add(Map.of("sourceNodeId",nodes[i-1],"targetNodeId",nodes[i])); return edges; }
}
