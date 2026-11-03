package cn.iocoder.yudao.framework.etl.core.schema;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionInfo;
import cn.iocoder.yudao.framework.etl.core.model.*;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EtlSchemaFieldContractTest {
    @Test void nativeEvidenceSurvivesRenameFilterAndSortWithoutInferringLegacyOrIntegerPrecision() {
        var decimal = column("amount", Types.NUMERIC, "numeric", false, 38, 18);
        var integer = column("id", Types.INTEGER, "int4", false, 32, 0);
        var legacy = new ColumnDescription(); legacy.setFieldName("legacy"); legacy.setFieldTypeName("numeric");
        legacy.setFieldType(Types.NUMERIC); legacy.setNullable(false); legacy.setPrecisionSize(12); legacy.setScaleSize(2);
        var rename = node("rename", "column-map", 2, Map.of("regexColumnMapper",
                List.of(Map.of("fromPattern", "^amount$", "toValue", "renamed"))));
        var filter = node("filter", "data-filter", 2, Map.of());
        var sort = node("sort", "sort-records", 2, Map.of());
        var result = resolve(List.of(decimal, integer, legacy), read(false), rename, filter, sort);
        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        var amount = field(result, "sort", "renamed");
        assertEquals(Boolean.FALSE, amount.getNullable()); assertEquals(38, amount.getPrecision()); assertEquals(18, amount.getScale());
        assertNull(amount.getTimezone());
        assertEquals(Boolean.FALSE, field(result, "sort", "id").getNullable());
        assertNull(field(result, "sort", "id").getPrecision());
        assertUnknown(field(result, "sort", "legacy"));
        amount.setPrecision(1);
        assertEquals(38, field(result, "read", "amount").getPrecision(), "Downstream metadata must not mutate source evidence");
    }

    @Test void sqlResultsNeverClaimNativeSourceConstraintsEvenWhenProducerCarriesFacts() {
        var result = resolve(List.of(column("amount", Types.NUMERIC, "numeric", false, 38, 18)), read(true));
        assertTrue(result.getErrors().isEmpty()); assertUnknown(field(result, "read", "amount"));
    }

    @Test void changedValuesLoseConstraintsWhileUntouchedColumnsKeepEvidence() {
        for (var mutation : List.of(
                node("change", "value-map", 2, Map.of("sourceField", "amount")),
                node("change", "data-desensitize", 2, Map.of("desensitizeFields", List.of(Map.of("fieldName", "amount")))),
                node("change", "data-clean", 2, Map.of("enableFormatting", true,
                        "formatRules", List.of(Map.of("fieldName", "amount", "type", "TRIM")))),
                node("change", "data-clean", 2, Map.of("enableNullHandling", true, "nullHandlingStrategy", "FILL_DEFAULT",
                        "defaultValues", List.of(Map.of("fieldName", "amount", "defaultValue", "unknown")))))) {
            var result = resolve(List.of(column("amount", Types.NUMERIC, "numeric", false, 38, 18),
                    column("other", Types.NUMERIC, "numeric", true, 9, 3)), read(false), mutation);
            assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
            assertUnknown(field(result, "change", "amount"));
            assertEquals(9, field(result, "change", "other").getPrecision());
            assertEquals(Boolean.TRUE, field(result, "change", "other").getNullable());
        }
    }

    @Test void scriptAndComplexMergeDoNotReuseConstraintsFromSampleInputs() {
        for (String code : List.of("java-script", "js-script", "flatten-row", "data-merge", "sort-merge", "multi-join", "cartesian-product")) {
            var columns = List.of(column("amount", Types.NUMERIC, "numeric", false, 38, 18));
            var change = node("change", code, code.endsWith("script") ? 4 : 2, Map.of());
            EtlSchemaResult result;
            if (change.isMultiInput()) {
                var second = read(false); second.setNo("second");
                result = resolve(columns, EtlDag.build(List.of(read(false), second, change),
                        List.of(edge("read", "change"), edge("second", "change"))));
            } else result = resolve(columns, read(false), change);
            assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
            assertUnknown(field(result, "change", "amount"));
        }
    }

    @Test void declaredBoundaryUsesTheSameContractAndLegacyWireRemainsReadable() throws Exception {
        var boundary = node("boundary", "boundary-input", 1, Map.of("fields", List.of(
                Map.of("name", "amount", "type", "Decimal", "nullable", false, "precision", 38, "scale", 18),
                Map.of("name", "legacy", "type", "Object"))));
        var result = new EtlSchemaService(mock(DbSwitchRuntime.class)).resolve(EtlDag.build(List.of(boundary), List.of()), null, null);
        var amount = field(result, "boundary", "amount");
        assertEquals(Boolean.FALSE, amount.getNullable()); assertEquals(38, amount.getPrecision()); assertEquals(18, amount.getScale());
        assertUnknown(field(result, "boundary", "legacy"));
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var old = mapper.readValue("{\"name\":\"legacy\",\"dataType\":\"String\"}", EtlFieldMeta.class);
        assertUnknown(old);
        var legacyWire = mapper.readTree(mapper.writeValueAsString(new EtlFieldMeta("legacy", "String")));
        assertFalse(legacyWire.has("nullable")); assertFalse(legacyWire.has("precision"));
        var newWire = mapper.readTree(mapper.writeValueAsString(amount));
        assertFalse(newWire.get("nullable").booleanValue()); assertEquals(38, newWire.get("precision").intValue());
    }

    @Test void conflictingOrMissingInputFactsRemainUnknownInMultiInputUnion() {
        var left = node("left", "boundary-input", 1, Map.of("fields", List.of(
                Map.of("name", "amount", "type", "Decimal", "nullable", false, "precision", 38, "scale", 18),
                Map.of("name", "only_left", "type", "String", "nullable", false))));
        var right = node("right", "boundary-input", 1, Map.of("fields", List.of(
                Map.of("name", "amount", "type", "Decimal", "nullable", true, "precision", 9, "scale", 3))));
        var merge = node("merge", "data-merge", 2, Map.of());
        var result = new EtlSchemaService(mock(DbSwitchRuntime.class)).resolve(
                EtlDag.build(List.of(left, right, merge), List.of(edge("left", "merge"), edge("right", "merge"))), null, null);
        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        result.getNodes().get("merge").getInputFields().forEach(EtlSchemaFieldContractTest::assertUnknown);
    }

    private EtlSchemaResult resolve(List<ColumnDescription> columns, EtlNodeDef... nodes) {
        var edges = new java.util.ArrayList<EtlEdgeDef>();
        for (int i = 1; i < nodes.length; i++) edges.add(edge(nodes[i - 1].getNo(), nodes[i].getNo()));
        return resolve(columns, EtlDag.build(List.of(nodes), edges));
    }
    private EtlSchemaResult resolve(List<ColumnDescription> columns, EtlDag dag) {
        var runtime = mock(DbSwitchRuntime.class); var metadata = mock(MetaDataByJdbcServiceImpl.class);
        when(runtime.jdbcMetadata(ProductTypeEnum.POSTGRESQL, null)).thenReturn(metadata);
        when(metadata.queryTableSchema("jdbc:test", "test", "", "public", "example", 10_000))
                .thenReturn(new TableSchemaMetadata(columns, List.of()));
        when(metadata.querySqlColumnMeta("jdbc:test", "test", "", "select amount from example")).thenReturn(columns);
        var conn = new EtlConnectionInfo(); conn.setProductType(ProductTypeEnum.POSTGRESQL); conn.setJdbcUrl("jdbc:test");
        conn.setUsername("test"); conn.setPassword(""); conn.setSchema("public");
        return new EtlSchemaService(runtime).resolve(dag, ignored -> conn, null);
    }
    private static ColumnDescription column(String name, int jdbcType, String type, boolean nullable, int precision, int scale) {
        var column = new ColumnDescription(); column.setFieldName(name); column.setFieldType(jdbcType); column.setFieldTypeName(type);
        column.setMetadataEvidence(new ColumnMetadataEvidence(MetadataProvenance.unknown(), MetadataValue.value(type),
                MetadataValue.unknown(), MetadataValue.value(nullable), MetadataValue.value(precision), MetadataValue.value(scale),
                MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown()));
        return column;
    }
    private static EtlNodeDef read(boolean sql) {
        return node("read", "read-db", 1, sql ? Map.of("databaseId", 1, "ifSql", 1, "sql", "select amount from example")
                : Map.of("databaseId", 1, "tableName", "example"));
    }
    private static EtlNodeDef node(String id, String code, int type, Map<String, Object> config) {
        var node = new EtlNodeDef(); node.setNo(id); node.setName(id); node.setToolCode(code); node.setToolType(type);
        node.setNodeJson(JSONUtil.toJsonStr(config)); return node;
    }
    private static EtlEdgeDef edge(String source, String target) {
        var edge = new EtlEdgeDef(); edge.setSourceNo(source); edge.setTargetNo(target); edge.setEdgeType("DATA"); return edge;
    }
    private static EtlFieldMeta field(EtlSchemaResult result, String node, String name) {
        return result.getNodes().get(node).getOutputFields().stream().filter(value -> name.equals(value.getName())).findFirst().orElseThrow();
    }
    private static void assertUnknown(EtlFieldMeta field) {
        assertNull(field.getNullable(), field.getName()); assertNull(field.getPrecision(), field.getName());
        assertNull(field.getScale(), field.getName()); assertNull(field.getTimezone(), field.getName());
    }
}
