package cn.iocoder.yudao.module.dataforge.service.lineage;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.schema.EtlSchemaService;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageNodeRefDTO;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageReportReqDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class EtlGraphLineageExtractorTest extends BaseMockitoUnitTest {

    @InjectMocks
    private EtlGraphLineageExtractor extractor;
    @Mock
    private LineageDatasourceResolver datasources;
    @Mock
    private LineageCatalogProvider catalogs;

    @BeforeEach
    void setUp() {
        // 真实组件规则；运行时只在未冻结字段的数据库输入上使用，测试中全部字段均已冻结。
        ReflectionTestUtils.setField(extractor, "schemas", new EtlSchemaService(mock(DbSwitchRuntime.class)));
        lenient().when(datasources.resolve(anyLong())).thenAnswer(invocation -> new LineageDatasourceResolver.Datasource(
                invocation.getArgument(0), ProductTypeEnum.POSTGRESQL, "public"));
        lenient().when(catalogs.prefetch(eq(1L), anyCollection())).thenReturn(new LineageCatalogProvider.Prefetched(
                table -> switch (table.name()) {
                    case "orders" -> Optional.of(List.of("id", "amount", "note"));
                    case "items" -> Optional.of(List.of("order_id", "qty"));
                    default -> Optional.empty();
                }, false));
    }

    @Test
    void tableInputThroughRenameToMappedOutputProducesColumnLineage() {
        var read = node("read", "read-db", 1, Map.of("databaseId", 1, "tableName", "orders",
                "selectedColumns", List.of("id", "amount")));
        var rename = node("rename", "column-map", 2, Map.of("regexColumnMapper",
                List.of(Map.of("fromPattern", "^amount$", "toValue", "amt"))));
        var write = node("write", "insert-db", 3, Map.of("databaseId", 2, "tableName", "fact", "fieldMappings",
                List.of(Map.of("source", "id", "target", "order_id"), Map.of("source", "amt", "target", "total"))));
        var report = extract(List.of(read, rename, write), List.of(edge("read", "rename"), edge("rename", "write")));
        assertEquals("SUCCESS", report.getStatus(), report.getIssues().toString());
        assertEquals(Set.of("1:public.orders -> 2:public.fact"), tables(report));
        assertEquals(Set.of("public.orders.id -> public.fact.order_id IDENTITY",
                "public.orders.amount -> public.fact.total IDENTITY"), columns(report));
    }

    @Test
    void sqlInputIsResolvedToItsPhysicalTables() {
        var read = node("read", "read-db", 1, Map.of("databaseId", 1, "ifSql", 1,
                "sql", "SELECT o.id, o.amount * i.qty AS total FROM orders o JOIN items i ON o.id = i.order_id"));
        var write = node("write", "insert-db", 3, Map.of("databaseId", 2, "tableName", "fact"));
        var report = extract(List.of(read, write), List.of(edge("read", "write")));
        assertEquals(Set.of("1:public.orders -> 2:public.fact", "1:public.items -> 2:public.fact"), tables(report));
        assertTrue(columns(report).containsAll(Set.of("public.orders.id -> public.fact.id IDENTITY",
                "public.orders.amount -> public.fact.total TRANSFORMATION",
                "public.items.qty -> public.fact.total TRANSFORMATION")));
    }

    @Test
    void externalInputsAndOutputsUseCredentialFreeKeys() {
        var read = node("read", "read-kafka", 1, Map.of("topic", "orders_topic", "password", "secret"));
        var write = node("write", "rest-api-output", 3, Map.of("url", "https://user:pw@api.example.com/v1/push?token=x"));
        var report = extract(List.of(read, write), List.of(edge("read", "write")));
        assertEquals(1, report.getTableEdges().size());
        LineageNodeRefDTO upstream = report.getTableEdges().getFirst().getUpstream();
        LineageNodeRefDTO downstream = report.getTableEdges().getFirst().getDownstream();
        assertEquals("KAFKA", upstream.getExternalKind());
        assertEquals("read-kafka:topic=orders_topic", upstream.getExternalKey());
        assertFalse(report.toString().contains("secret"));
        assertEquals("API", downstream.getExternalKind());
        assertFalse(downstream.getExternalKey().contains("pw"));
        assertFalse(downstream.getExternalKey().contains("token"));
    }

    @Test
    void scriptsDegradeColumnLineageButKeepTableLineage() {
        var read = node("read", "read-db", 1, Map.of("databaseId", 1, "tableName", "orders"));
        var script = node("script", "js-script", 2, Map.of());
        var write = node("write", "insert-db", 3, Map.of("databaseId", 2, "tableName", "fact"));
        var report = extract(List.of(read, script, write), List.of(edge("read", "script"), edge("script", "write")));
        assertEquals("PARTIAL", report.getStatus());
        assertTrue(report.getIssues().stream().anyMatch(issue -> "ETL_ORIGIN_INCOMPLETE".equals(issue.getCode())));
        assertEquals(Set.of("1:public.orders -> 2:public.fact"), tables(report));
    }

    @Test
    void traceFieldsMapsEveryNodeFieldToItsPhysicalColumn() {
        var read = node("read", "read-db", 1, Map.of("databaseId", 1, "tableName", "orders",
                "selectedColumns", List.of("id", "amount")));
        var rename = node("rename", "column-map", 2, Map.of("regexColumnMapper",
                List.of(Map.of("fromPattern", "^amount$", "toValue", "amt"))));
        var trace = extractor.traceFields(List.of(read, rename), List.of(edge("read", "rename")));

        var amt = trace.of("rename", "amt");
        assertTrue(amt.complete());
        assertEquals(List.of("orders.amount"), amt.sources().stream()
                .map(source -> source.tableName() + "." + source.column()).toList());
        assertEquals(List.of("orders.id"), trace.of("read", "id").sources().stream()
                .map(source -> source.tableName() + "." + source.column()).toList());
        // 不存在的字段按无法追溯处理。
        assertFalse(trace.of("rename", "missing").complete());
        assertEquals(List.of("orders"), trace.tables().stream().map(table -> table.getTableName()).toList());
    }

    @Test
    void traceFieldsMarksScriptOutputsIncomplete() {
        var read = node("read", "read-db", 1, Map.of("databaseId", 1, "tableName", "orders",
                "selectedColumns", List.of("id", "note")));
        var script = node("script", "js-script", 2, Map.of());
        var trace = extractor.traceFields(List.of(read, script), List.of(edge("read", "script")));
        // 脚本可能改写任意字段：即便同名也不能当作确定来源。
        assertFalse(trace.of("script", "note").complete());
        assertTrue(trace.of("read", "note").complete());
    }

    @Test
    void sanitizedUrlDropsUserInfoQueryAndFragment() {
        assertEquals("https://api.example.com:8443/a/b",
                EtlGraphLineageExtractor.sanitizeUrl("https://u:p@api.example.com:8443/a/b?k=v#frag"));
        assertEquals(null, EtlGraphLineageExtractor.sanitizeUrl("not a url"));
    }

    // ==================== 工具 ====================

    private LineageReportReqDTO extract(List<EtlNodeDef> nodes, List<EtlEdgeDef> edges) {
        LineageReportBuilder builder = new LineageReportBuilder("ETL_PIPELINE", 1L, "p", 1L, 101);
        extractor.extract(new ArrayList<>(nodes), new ArrayList<>(edges), builder);
        return builder.build();
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

    private static Set<String> tables(LineageReportReqDTO report) {
        Set<String> edges = new TreeSet<>();
        report.getTableEdges().forEach(edge -> edges.add(edge.getUpstream().getDatasourceId() + ":"
                + edge.getUpstream().getSchemaName() + "." + edge.getUpstream().getTableName() + " -> "
                + edge.getDownstream().getDatasourceId() + ":" + edge.getDownstream().getSchemaName() + "."
                + edge.getDownstream().getTableName()));
        return edges;
    }

    private static Set<String> columns(LineageReportReqDTO report) {
        Set<String> edges = new TreeSet<>();
        report.getColumnEdges().forEach(edge -> edges.add(edge.getUpstream().getSchemaName() + "."
                + edge.getUpstream().getTableName() + "." + edge.getUpstreamColumn() + " -> "
                + edge.getDownstream().getSchemaName() + "." + edge.getDownstream().getTableName() + "."
                + edge.getDownstreamColumn() + " " + edge.getTransformSubtype()));
        return edges;
    }
}
