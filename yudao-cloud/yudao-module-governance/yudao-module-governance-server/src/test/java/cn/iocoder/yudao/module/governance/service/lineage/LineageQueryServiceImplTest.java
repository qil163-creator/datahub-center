package cn.iocoder.yudao.module.governance.service.lineage;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.governance.controller.admin.lineage.vo.LineageGraphNodeRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.lineage.vo.LineageGraphReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.lineage.vo.LineageImpactReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.lineage.LineageColumnKey;
import cn.iocoder.yudao.module.governance.dal.dataobject.lineage.LineageEdgeHit;
import cn.iocoder.yudao.module.governance.dal.dataobject.lineage.LineageNodeDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.lineage.LineageSourceDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.lineage.LineageTableEdgeDO;
import cn.iocoder.yudao.module.governance.dal.mysql.lineage.LineageColumnEdgeMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.lineage.LineageNodeMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.lineage.LineageSourceMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.lineage.LineageTableEdgeMapper;
import cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageDirectionEnum;
import cn.iocoder.yudao.module.governance.service.metadata.MetaTableKeyResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BFS 方向、层数、截断与结果装配；SQL 本身由 PostgreSQL 用例覆盖。
 */
class LineageQueryServiceImplTest {

    /** 内存边表：[上游, 下游, 来源] */
    private final List<long[]> edges = new ArrayList<>();
    private LineageQueryServiceImpl service;
    private LineageNodeMapper nodeMapper;

    @BeforeEach
    void setUp() {
        nodeMapper = mock(LineageNodeMapper.class);
        var sourceMapper = mock(LineageSourceMapper.class);
        var tableEdgeMapper = mock(LineageTableEdgeMapper.class);
        var resolver = mock(MetaTableKeyResolver.class);
        when(resolver.resolve(anyCollection())).thenReturn(Map.of());
        when(nodeMapper.selectById(anyLong())).thenAnswer(call -> node(call.getArgument(0)));
        when(nodeMapper.selectByIds(anyCollection())).thenAnswer(call -> {
            Collection<Long> ids = call.getArgument(0);
            return ids.stream().map(LineageQueryServiceImplTest::node).toList();
        });
        when(sourceMapper.selectByIds(anyCollection())).thenAnswer(call -> {
            Collection<Long> ids = call.getArgument(0);
            return ids.stream().map(id -> {
                LineageSourceDO source = new LineageSourceDO();
                source.setId(id);
                source.setSourceType("DEV_TASK");
                source.setSourceId(id * 10);
                source.setSourceName("task-" + id);
                source.setStatus("SUCCESS");
                return source;
            }).toList();
        });
        when(tableEdgeMapper.selectNeighborIds(anyCollection(), anyCollection(), anyBoolean(), anyInt()))
                .thenAnswer(call -> neighbors(call.getArgument(0), call.getArgument(1), call.getArgument(2),
                        call.getArgument(3)));
        when(tableEdgeMapper.selectInducedEdges(anyCollection(), anyInt())).thenAnswer(call -> {
            Set<Long> ids = new HashSet<>(call.<Collection<Long>>getArgument(0));
            List<LineageTableEdgeDO> rows = new ArrayList<>();
            for (long[] edge : edges) {
                if (ids.contains(edge[0]) && ids.contains(edge[1])) {
                    LineageTableEdgeDO row = new LineageTableEdgeDO();
                    row.setUpstreamNodeId(edge[0]);
                    row.setDownstreamNodeId(edge[1]);
                    row.setOrigin("PARSED");
                    row.setSourceRefId(edge[2]);
                    rows.add(row);
                }
            }
            return rows;
        });
        when(tableEdgeMapper.selectIdsWithOutsideNeighbor(anyCollection(), anyBoolean())).thenAnswer(call -> {
            Set<Long> ids = new HashSet<>(call.<Collection<Long>>getArgument(0));
            boolean upstream = call.getArgument(1);
            Set<Long> result = new HashSet<>();
            for (long[] edge : edges) {
                long self = upstream ? edge[1] : edge[0];
                long other = upstream ? edge[0] : edge[1];
                if (ids.contains(self) && !ids.contains(other)) {
                    result.add(self);
                }
            }
            return new ArrayList<>(result);
        });
        when(tableEdgeMapper.selectDownstreamHits(anyCollection(), anyCollection(), anyInt())).thenAnswer(call -> {
            Set<Long> frontier = new HashSet<>(call.<Collection<Long>>getArgument(0));
            Set<Long> visited = new HashSet<>(call.<Collection<Long>>getArgument(1));
            int limit = call.getArgument(2);
            List<LineageEdgeHit> hits = new ArrayList<>();
            for (long[] edge : edges) {
                if (frontier.contains(edge[0]) && !visited.contains(edge[1]) && hits.size() < limit) {
                    LineageEdgeHit hit = new LineageEdgeHit();
                    hit.setNodeId(edge[1]);
                    hit.setSourceRefId(edge[2]);
                    hits.add(hit);
                }
            }
            return hits;
        });
        service = new LineageQueryServiceImpl();
        ReflectionTestUtils.setField(service, "nodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "sourceMapper", sourceMapper);
        ReflectionTestUtils.setField(service, "tableEdgeMapper", tableEdgeMapper);
        ReflectionTestUtils.setField(service, "columnEdgeMapper", mock(LineageColumnEdgeMapper.class));
        ReflectionTestUtils.setField(service, "tableKeyResolver", resolver);
    }

    @Test
    void graphHonoursDirectionDepthAndExpandableFlags() {
        // 1 → 2 → 3 → 4（链）, 5 → 3, 3 → 6
        edge(1, 2, 1);
        edge(2, 3, 1);
        edge(3, 4, 2);
        edge(5, 3, 3);
        edge(3, 6, 2);

        var upstream = service.getGraph(graph(3, "UPSTREAM", 1));
        assertThat(depths(upstream.getNodes())).containsExactly(Map.entry(2L, -1), Map.entry(5L, -1), Map.entry(3L, 0));
        var root = find(upstream.getNodes(), 3L);
        assertThat(root.getDownstreamExpandable()).isTrue();
        assertThat(root.getUpstreamExpandable()).isFalse();
        assertThat(find(upstream.getNodes(), 2L).getUpstreamExpandable()).isTrue();
        assertThat(find(upstream.getNodes(), 5L).getUpstreamExpandable()).isFalse();
        assertThat(upstream.getEdges()).extracting(e -> e.getUpstreamNodeId() + ">" + e.getDownstreamNodeId())
                .containsExactlyInAnyOrder("2>3", "5>3");
        assertThat(upstream.getTruncated()).isFalse();

        var downstream = service.getGraph(graph(2, "DOWNSTREAM", 2));
        assertThat(depths(downstream.getNodes())).containsExactly(Map.entry(2L, 0), Map.entry(3L, 1),
                Map.entry(4L, 2), Map.entry(6L, 2));

        var both = service.getGraph(graph(3, "BOTH", 5));
        assertThat(depths(both.getNodes())).containsEntry(1L, -2).containsEntry(4L, 1).containsEntry(6L, 1);
        assertThat(both.getEdges()).hasSize(5);
        assertThat(both.getEdges()).filteredOn(e -> e.getUpstreamNodeId() == 3L && e.getDownstreamNodeId() == 4L)
                .singleElement().satisfies(e -> {
                    assertThat(e.getOrigins()).containsExactly("PARSED");
                    assertThat(e.getSources()).singleElement()
                            .satisfies(s -> assertThat(s.getSourceName()).isEqualTo("task-2"));
                });
    }

    @Test
    void graphStopsAtNodeLimitAndMarksTruncated() {
        for (long i = 0; i < 400; i++) {
            edge(1, 1000 + i, 1);
        }
        var result = service.getGraph(graph(1, "DOWNSTREAM", 3));
        assertThat(result.getNodes()).hasSize(LineageQueryService.MAX_GRAPH_NODES);
        assertThat(result.getTruncated()).isTrue();
        assertThat(find(result.getNodes(), 1L).getDownstreamExpandable()).isTrue();
    }

    @Test
    void walkHandlesCyclesAndBudgetAcrossBothDirections() {
        Map<Long, List<Long>> down = Map.of(1L, List.of(2L), 2L, List.of(1L, 3L));
        Map<Long, List<Long>> up = Map.of(1L, List.of(2L), 2L, List.of(1L), 3L, List.of(2L));
        Map<Long, Integer> depthOf = new LinkedHashMap<>();
        depthOf.put(1L, 0);
        boolean truncated = LineageQueryServiceImpl.walk(1L, LineageDirectionEnum.BOTH, 5, depthOf, 300,
                (frontier, visited, upstream, limit) -> frontier.stream()
                        .flatMap(id -> (upstream ? up : down).getOrDefault(id, List.of()).stream())
                        .filter(id -> !visited.contains(id)).distinct().sorted().limit(limit).toList());
        assertThat(truncated).isFalse();
        assertThat(depthOf).containsEntry(1L, 0).containsEntry(2L, -1).containsEntry(3L, 2);

        Map<Long, Integer> small = new LinkedHashMap<>();
        small.put(1L, 0);
        boolean cut = LineageQueryServiceImpl.walk(1L, LineageDirectionEnum.BOTH, 5, small, 2,
                (frontier, visited, upstream, limit) -> List.of(upstream ? 10L : 20L, upstream ? 11L : 21L)
                        .stream().filter(id -> !visited.contains(id)).limit(limit).toList());
        assertThat(cut).isTrue();
        assertThat(small).containsOnlyKeys(1L, 10L);
    }

    @Test
    void columnWalkUsesNodeAndColumnAsIdentity() {
        Map<LineageColumnKey, Integer> depthOf = new LinkedHashMap<>();
        LineageColumnKey root = new LineageColumnKey(1L, "id");
        depthOf.put(root, 0);
        boolean truncated = LineageQueryServiceImpl.walk(root, LineageDirectionEnum.DOWNSTREAM, 2, depthOf, 500,
                (frontier, visited, upstream, limit) -> frontier.contains(root)
                        ? List.of(new LineageColumnKey(2L, "id"), new LineageColumnKey(2L, "order_id"))
                        : List.of());
        assertThat(truncated).isFalse();
        assertThat(depthOf).containsOnlyKeys(root, new LineageColumnKey(2L, "id"), new LineageColumnKey(2L, "order_id"));
    }

    @Test
    void impactIsFlatDownstreamWithSourcesAndCap() {
        edge(1, 2, 7);
        edge(1, 2, 8);
        edge(2, 3, 9);
        edge(3, 1, 9);
        LineageImpactReqVO req = new LineageImpactReqVO();
        req.setNodeId(1L);
        req.setDepth(10);

        var result = service.getImpact(req);

        assertThat(result.getItems()).extracting(item -> item.getNodeId() + "@" + item.getDepth())
                .containsExactly("2@1", "3@2");
        assertThat(result.getItems().get(0).getSources()).extracting(s -> s.getId()).containsExactly(7L, 8L);
        assertThat(result.getItems().get(0).getColumn()).isNull();
        assertThat(result.getTruncated()).isFalse();

        for (long i = 0; i < 600; i++) {
            edge(3, 5000 + i, 1);
        }
        var capped = service.getImpact(req);
        assertThat(capped.getItems()).hasSize(LineageQueryService.MAX_IMPACT_ITEMS);
        assertThat(capped.getTruncated()).isTrue();
    }

    @Test
    void invalidParametersAndMissingNodesUseLineageErrorCodes() {
        assertThatThrownBy(() -> service.getGraph(graph(1, "SIDEWAYS", 1)))
                .isInstanceOfSatisfying(ServiceException.class, error ->
                        assertThat(error.getCode()).isEqualTo(ErrorCodeConstants.LINEAGE_QUERY_INVALID.getCode()));
        assertThatThrownBy(() -> service.getGraph(graph(1, "BOTH", 6)))
                .isInstanceOfSatisfying(ServiceException.class, error ->
                        assertThat(error.getCode()).isEqualTo(ErrorCodeConstants.LINEAGE_QUERY_INVALID.getCode()));
        when(nodeMapper.selectById(404L)).thenReturn(null);
        assertThatThrownBy(() -> service.getGraph(graph(404, "BOTH", 1)))
                .isInstanceOfSatisfying(ServiceException.class, error ->
                        assertThat(error.getCode()).isEqualTo(ErrorCodeConstants.LINEAGE_NODE_NOT_EXISTS.getCode()));
    }

    private void edge(long up, long down, long source) {
        edges.add(new long[]{up, down, source});
    }

    private List<Long> neighbors(Collection<Long> frontier, Collection<Long> visited, boolean upstream, int limit) {
        Set<Long> from = new HashSet<>(frontier);
        Set<Long> seen = new HashSet<>(visited);
        return edges.stream()
                .filter(edge -> from.contains(upstream ? edge[1] : edge[0]))
                .map(edge -> upstream ? edge[0] : edge[1])
                .filter(id -> !seen.contains(id)).distinct().sorted().limit(limit).toList();
    }

    private static LineageNodeDO node(long id) {
        LineageNodeDO node = new LineageNodeDO();
        node.setId(id);
        node.setNodeType("TABLE");
        node.setDatasourceId(1L);
        node.setTableName("t" + id);
        return node;
    }

    private static LineageGraphReqVO graph(long nodeId, String direction, int depth) {
        LineageGraphReqVO req = new LineageGraphReqVO();
        req.setNodeId(nodeId);
        req.setDirection(direction);
        req.setDepth(depth);
        return req;
    }

    private static Map<Long, Integer> depths(List<LineageGraphNodeRespVO> nodes) {
        Map<Long, Integer> result = new LinkedHashMap<>();
        nodes.forEach(node -> result.put(node.getId(), node.getDepth()));
        return result;
    }

    private static LineageGraphNodeRespVO find(List<LineageGraphNodeRespVO> nodes, long id) {
        return nodes.stream().filter(node -> node.getId() == id).findFirst().orElseThrow();
    }

}
