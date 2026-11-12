package cn.iocoder.yudao.module.governance.service.lineage;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.lineage.vo.LineageNodeSearchReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.lineage.vo.LineageSourcePageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.lineage.LineageNodeDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.lineage.LineageSourceDO;
import cn.iocoder.yudao.module.governance.dal.mysql.lineage.LineageColumnEdgeMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.lineage.LineageNodeMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.lineage.LineageSourceMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.lineage.LineageTableEdgeMapper;
import cn.iocoder.yudao.module.governance.service.metadata.MetaTableKeyResolver;
import cn.iocoder.yudao.module.governance.service.metadata.MetaTableKeyResolver.TableKey;
import cn.iocoder.yudao.module.governance.service.metadata.MetaTableKeyResolver.TableMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 结果装配：元数据关联、字段合并、检索转义、按元数据表定位与来源分页。
 */
class LineageQueryAssemblyTest {

    private final Map<String, LineageNodeDO> nodesByKey = new HashMap<>();
    private final Map<TableKey, TableMatch> metadata = new HashMap<>();
    private LineageQueryServiceImpl service;
    private LineageNodeMapper nodeMapper;
    private LineageSourceMapper sourceMapper;
    private LineageColumnEdgeMapper columnEdgeMapper;
    private MetaTableKeyResolver resolver;

    @BeforeEach
    void setUp() {
        nodeMapper = mock(LineageNodeMapper.class);
        sourceMapper = mock(LineageSourceMapper.class);
        columnEdgeMapper = mock(LineageColumnEdgeMapper.class);
        resolver = mock(MetaTableKeyResolver.class);
        when(resolver.resolve(anyCollection())).thenAnswer(call -> {
            Map<TableKey, TableMatch> result = new HashMap<>();
            for (TableKey key : call.<Collection<TableKey>>getArgument(0)) {
                metadata.entrySet().stream().filter(entry -> entry.getKey().datasourceId().equals(key.datasourceId())
                                && entry.getKey().tableName().equalsIgnoreCase(key.tableName())
                                && String.valueOf(entry.getKey().schemaName()).equalsIgnoreCase(String.valueOf(key.schemaName())))
                        .findFirst().ifPresent(entry -> result.put(key, entry.getValue()));
            }
            return result;
        });
        when(nodeMapper.selectListByNodeKeys(anyCollection())).thenAnswer(call -> call.<Collection<String>>getArgument(0)
                .stream().map(nodesByKey::get).filter(java.util.Objects::nonNull).toList());
        when(nodeMapper.selectIdsHavingEdges(anyCollection())).thenAnswer(call -> List.copyOf(call.getArgument(0)));
        service = new LineageQueryServiceImpl();
        ReflectionTestUtils.setField(service, "nodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "sourceMapper", sourceMapper);
        ReflectionTestUtils.setField(service, "tableEdgeMapper", mock(LineageTableEdgeMapper.class));
        ReflectionTestUtils.setField(service, "columnEdgeMapper", columnEdgeMapper);
        ReflectionTestUtils.setField(service, "tableKeyResolver", resolver);
    }

    @Test
    void searchEscapesWildcardsEnrichesMetadataAndAddsBusinessNameHits() {
        LineageNodeDO byName = node(1L, "ods", "orders_100%");
        LineageNodeDO byBusiness = node(2L, "dwd", "fact_sales");
        metadata.put(new TableKey(1L, "dwd", "fact_sales"), match(500L, "dwd", "fact_sales", "销售事实"));
        when(nodeMapper.selectSearchList(eq("%100\\%%"), eq("100%"), isNull(), eq(20))).thenReturn(List.of(byName));
        when(resolver.searchByBusinessName("100%", null, 20)).thenReturn(List.of(match(500L, "dwd", "fact_sales", "销售事实")));
        nodesByKey.put(byBusiness.getNodeKey(), byBusiness);

        LineageNodeSearchReqVO req = new LineageNodeSearchReqVO();
        req.setKeyword(" 100% ");
        var result = service.searchNodes(req);

        assertThat(result).extracting(node -> node.getId() + ":" + node.getMetaTableId() + ":" + node.getBusinessName())
                .containsExactly("1:null:null", "2:500:销售事实");
    }

    @Test
    void resolveByMetaTablePrefersVerifiedSchemaQualifiedNodes() {
        TableMatch table = match(700L, "Sales", "Orders", "订单");
        when(resolver.getTable(700L)).thenReturn(table);
        LineageNodeDO qualified = node(11L, "sales", "orders");
        LineageNodeDO unqualified = node(12L, null, "orders");
        nodesByKey.put(qualified.getNodeKey(), qualified);
        nodesByKey.put(unqualified.getNodeKey(), unqualified);
        metadata.put(new TableKey(1L, "sales", "orders"), table);

        assertThat(service.resolveByMetaTable(700L).getId()).isEqualTo(11L);

        nodesByKey.remove(qualified.getNodeKey());
        assertThat(service.resolveByMetaTable(700L)).as("未限定 schema 的节点在元数据中不唯一时不能认领").isNull();
        when(resolver.getTable(701L)).thenReturn(null);
        assertThat(service.resolveByMetaTable(701L)).isNull();
    }

    @Test
    void nodeColumnsMergeMetadataOrderWithEdgeOnlyColumnsAndWildcardLast() {
        LineageNodeDO node = node(5L, "ods", "orders");
        when(nodeMapper.selectById(5L)).thenReturn(node);
        TableMatch table = match(800L, "ods", "orders", "订单");
        metadata.put(new TableKey(1L, "ods", "orders"), table);
        when(resolver.loadColumnNames(List.of(800L))).thenReturn(Map.of(800L, List.of("ID", "Amount")));
        when(columnEdgeMapper.selectColumnNames(eq(5L), anyInt())).thenReturn(List.of("*", "amount", "id", "status"));

        assertThat(service.getNodeColumns(5L)).containsExactly("ID", "Amount", "status", "*");
    }

    @Test
    void sourcePageParsesIssuesJson() {
        LineageSourceDO source = new LineageSourceDO();
        source.setId(3L);
        source.setSourceType("ETL_PIPELINE");
        source.setSourceId(30L);
        source.setSourceName("etl");
        source.setSourceRevision(4L);
        source.setAnalyzerVersion(2);
        source.setStatus("PARTIAL");
        source.setIssues("[{\"code\":\"AMBIGUOUS_COLUMN\",\"message\":\"x 可能来自 a 或 b\",\"statementIndex\":0}]");
        source.setTableEdgeCount(2);
        source.setColumnEdgeCount(5);
        source.setReportedTime(LocalDateTime.of(2026, 11, 11, 8, 0));
        LineageSourcePageReqVO req = new LineageSourcePageReqVO();
        when(sourceMapper.selectPage(req)).thenReturn(new PageResult<>(List.of(source), 1L));

        var page = service.getSourcePage(req);

        assertThat(page.getTotal()).isEqualTo(1L);
        var vo = page.getList().get(0);
        assertThat(vo.getIssues()).singleElement().satisfies(issue -> {
            assertThat(issue.getCode()).isEqualTo("AMBIGUOUS_COLUMN");
            assertThat(issue.getStatementIndex()).isZero();
        });
        assertThat(vo.getSourceRevision()).isEqualTo(4L);
        assertThat(vo.getColumnEdgeCount()).isEqualTo(5);
        assertThat(vo.getReportedTime()).isEqualTo(LocalDateTime.of(2026, 11, 11, 8, 0));
    }

    private static LineageNodeDO node(long id, String schema, String table) {
        LineageNodeDO node = new LineageNodeDO();
        node.setId(id);
        node.setNodeType("TABLE");
        node.setDatasourceId(1L);
        node.setSchemaName(schema);
        node.setTableName(table);
        node.setNodeKey(LineageNodeKeys.tableKey(1L, schema, table));
        return node;
    }

    private static TableMatch match(long tableId, String schema, String table, String businessName) {
        return new TableMatch(tableId, tableId * 10, 1L, schema, schema, table, businessName);
    }

}
