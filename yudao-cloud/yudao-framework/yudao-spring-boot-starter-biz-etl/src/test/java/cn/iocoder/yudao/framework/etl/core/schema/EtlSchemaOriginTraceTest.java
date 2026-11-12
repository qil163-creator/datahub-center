package cn.iocoder.yudao.framework.etl.core.schema;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.LineageTransform;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableSchemaMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionInfo;
import cn.iocoder.yudao.framework.etl.core.model.EtlDag;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EtlSchemaOriginTraceTest {

    @Test
    void renameFilterAndSortKeepIdentityOriginsUpToTheOutputNode() {
        var rename = node("rename", "column-map", 2, Map.of("regexColumnMapper",
                List.of(Map.of("fromPattern", "^amount$", "toValue", "amt"))));
        var filter = node("filter", "data-filter", 2, Map.of());
        var sort = node("sort", "sort-records", 2, Map.of());
        var output = node("out", "insert-db", 3, Map.of("databaseId", 2, "tableName", "target"));
        var trace = trace(chain(read("read", "orders"), rename, filter, sort, output));
        assertTrue(trace.errors().isEmpty(), trace.errors().toString());
        var written = trace.nodes().get("out").inputs();
        assertEquals(List.of("id", "amt"), written.stream().map(EtlFieldOriginResult.TracedField::name).toList());
        assertEquals(List.of(new EtlFieldOrigin("read", "amount", LineageTransform.IDENTITY, false)),
                field(written, "amt").origins());
        assertTrue(field(written, "amt").complete());
    }

    @Test
    void desensitizeIsTransformationWithMaskingAndValueMapDerivesFromSourceField() {
        var mask = node("mask", "data-desensitize", 2, Map.of("desensitizeFields",
                List.of(Map.of("fieldName", "amount", "outputFieldName", "amount_masked"))));
        var map = node("map", "value-map", 2, Map.of("sourceField", "id", "targetField", "id_label"));
        var output = node("out", "insert-db", 3, Map.of());
        var written = trace(chain(read("read", "orders"), mask, map, output)).nodes().get("out").inputs();
        assertEquals(List.of(new EtlFieldOrigin("read", "amount", LineageTransform.TRANSFORMATION, true)),
                field(written, "amount_masked").origins());
        assertEquals(List.of(new EtlFieldOrigin("read", "id", LineageTransform.TRANSFORMATION, false)),
                field(written, "id_label").origins());
        assertFalse(written.stream().anyMatch(f -> "amount".equals(f.name())), "原字段未保留时应被移除");
    }

    @Test
    void mergeCombinesOriginsOfSameNamedFieldsFromEveryUpstream() {
        var left = read("left", "orders");
        var right = read("right", "orders_history");
        var merge = node("merge", "data-merge", 2, Map.of());
        var output = node("out", "insert-db", 3, Map.of());
        var dag = EtlDag.build(List.of(left, right, merge, output),
                List.of(edge("left", "merge"), edge("right", "merge"), edge("merge", "out")));
        var written = trace(dag).nodes().get("out").inputs();
        assertEquals(List.of(new EtlFieldOrigin("left", "id", LineageTransform.IDENTITY, false),
                new EtlFieldOrigin("right", "id", LineageTransform.IDENTITY, false)), field(written, "id").origins());
    }

    @Test
    void scriptsAndCalculatorsMarkTheirOutputsIncomplete() {
        var script = node("script", "js-script", 2, Map.of());
        var outputAfterScript = node("out", "insert-db", 3, Map.of());
        var scripted = trace(chain(read("read", "orders"), script, outputAfterScript)).nodes().get("out").inputs();
        assertTrue(scripted.stream().noneMatch(EtlFieldOriginResult.TracedField::complete));

        var calculator = node("calc", "calculator", 2, Map.of("rules", List.of(Map.of("targetField", "total"))));
        var outputAfterCalc = node("out", "insert-db", 3, Map.of());
        var calculated = trace(chain(read("read", "orders"), calculator, outputAfterCalc)).nodes().get("out").inputs();
        assertFalse(field(calculated, "total").complete());
        assertTrue(field(calculated, "id").complete());
    }

    @Test
    void constantsHaveNoOriginButAreComplete() {
        var constant = node("const", "add-constant", 2, Map.of("fields",
                List.of(Map.of("name", "source_system", "fieldType", "String"))));
        var output = node("out", "insert-db", 3, Map.of());
        var written = trace(chain(read("read", "orders"), constant, output)).nodes().get("out").inputs();
        assertTrue(field(written, "source_system").origins().isEmpty());
        assertTrue(field(written, "source_system").complete());
    }

    @Test
    void plainResolveDoesNotTrackAndSerializedContractIsUnchanged() throws Exception {
        var service = new EtlSchemaService(runtime(List.of(column("id"), column("amount"))));
        var dag = chain(read("read", "orders"), node("sort", "sort-records", 2, Map.of()));
        var plain = service.resolve(dag, ignored -> connection(), null);
        var traced = service.traceOrigins(dag, ignored -> connection(), null, null);
        EtlFieldMeta field = plain.getNodes().get("sort").getOutputFields().getFirst();
        assertTrue(field.origins().isEmpty());
        assertEquals(1, traced.nodes().get("sort").outputs().getFirst().origins().size());

        EtlFieldMeta tracedMeta = new EtlFieldMeta("id", "int4").originAt("read");
        EtlFieldMeta untraced = new EtlFieldMeta("id", "int4");
        assertEquals(untraced, tracedMeta, "来源追踪不参与相等性");
        assertEquals(untraced.hashCode(), tracedMeta.hashCode());
        assertEquals(JSONUtil.toJsonStr(untraced), JSONUtil.toJsonStr(tracedMeta));
        String json = new ObjectMapper().writeValueAsString(tracedMeta);
        assertFalse(json.contains("origin"), json);
        assertEquals(new ObjectMapper().writeValueAsString(untraced), json);
    }

    // ==================== 工具 ====================

    private static EtlFieldOriginResult trace(EtlDag dag) {
        return new EtlSchemaService(runtime(List.of(column("id"), column("amount"))))
                .traceOrigins(dag, ignored -> connection(), null, null);
    }

    private static DbSwitchRuntime runtime(List<ColumnDescription> columns) {
        var runtime = mock(DbSwitchRuntime.class);
        var metadata = mock(MetaDataByJdbcServiceImpl.class);
        when(runtime.jdbcMetadata(eq(ProductTypeEnum.POSTGRESQL), any())).thenReturn(metadata);
        when(metadata.queryTableSchema(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new TableSchemaMetadata(columns, List.of()));
        return runtime;
    }

    private static EtlConnectionInfo connection() {
        var conn = new EtlConnectionInfo();
        conn.setProductType(ProductTypeEnum.POSTGRESQL);
        conn.setJdbcUrl("jdbc:test");
        conn.setUsername("test");
        conn.setPassword("");
        conn.setSchema("public");
        return conn;
    }

    private static ColumnDescription column(String name) {
        var column = new ColumnDescription();
        column.setFieldName(name);
        column.setFieldType(Types.INTEGER);
        column.setFieldTypeName("int4");
        return column;
    }

    private static EtlDag chain(EtlNodeDef... nodes) {
        var edges = new ArrayList<EtlEdgeDef>();
        for (int i = 1; i < nodes.length; i++) {
            edges.add(edge(nodes[i - 1].getNo(), nodes[i].getNo()));
        }
        return EtlDag.build(List.of(nodes), edges);
    }

    private static EtlNodeDef read(String id, String table) {
        return node(id, "read-db", 1, Map.of("databaseId", 1, "tableName", table));
    }

    private static EtlNodeDef node(String id, String code, int type, Map<String, Object> config) {
        var node = new EtlNodeDef();
        node.setNo(id);
        node.setName(id);
        node.setToolCode(code);
        node.setToolType(type);
        node.setNodeJson(JSONUtil.toJsonStr(config));
        return node;
    }

    private static EtlEdgeDef edge(String source, String target) {
        var edge = new EtlEdgeDef();
        edge.setSourceNo(source);
        edge.setTargetNo(target);
        edge.setEdgeType("DATA");
        return edge;
    }

    private static EtlFieldOriginResult.TracedField field(List<EtlFieldOriginResult.TracedField> fields, String name) {
        return fields.stream().filter(f -> name.equals(f.name())).findFirst().orElseThrow();
    }
}
