package cn.iocoder.yudao.module.governance.service.lineage;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.lineage.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.lineage.LineageColumnEdgeDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.lineage.LineageColumnKey;
import cn.iocoder.yudao.module.governance.dal.dataobject.lineage.LineageEdgeHit;
import cn.iocoder.yudao.module.governance.dal.dataobject.lineage.LineageNodeDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.lineage.LineageSourceDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.lineage.LineageTableEdgeDO;
import cn.iocoder.yudao.module.governance.dal.mysql.lineage.LineageColumnEdgeMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.lineage.LineageNodeMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.lineage.LineageSourceMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.lineage.LineageTableEdgeMapper;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageContractConstants;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageDirectionEnum;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageNodeTypeEnum;
import cn.iocoder.yudao.module.governance.service.metadata.MetaTableKeyResolver;
import cn.iocoder.yudao.module.governance.service.metadata.MetaTableKeyResolver.TableKey;
import cn.iocoder.yudao.module.governance.service.metadata.MetaTableKeyResolver.TableMatch;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.LINEAGE_NODE_NOT_EXISTS;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.LINEAGE_QUERY_INVALID;

/**
 * 血缘查询实现。
 *
 * <p>图遍历在服务端按层 BFS：每层一次带 LIMIT 的 IN 批量查询取未访问邻居，不使用递归 CTE；达到节点上限即停止并标记
 * truncated。结果边取已访问节点的诱导子图，“可继续展开”由“集合内节点是否还有集合外邻居”判定。元数据关联在查询时按
 * {@link MetaTableKeyResolver} 的规则完成，不落库。数据范围为平台内全可见，与元数据表浏览一致。</p>
 */
@Service
public class LineageQueryServiceImpl implements LineageQueryService {

    /** 诱导子图边行上限（同一对节点可能有多来源、多 origin） */
    static final int MAX_EDGE_ROWS = 20000;
    /** 影响分析每层读取的边行上限 */
    static final int IMPACT_ROWS_PER_LEVEL = 5000;
    /** 节点字段列表中来自字段级边的字段上限 */
    static final int NODE_EDGE_COLUMN_LIMIT = 2000;

    @Resource
    private LineageNodeMapper nodeMapper;
    @Resource
    private LineageSourceMapper sourceMapper;
    @Resource
    private LineageTableEdgeMapper tableEdgeMapper;
    @Resource
    private LineageColumnEdgeMapper columnEdgeMapper;
    @Resource
    private MetaTableKeyResolver tableKeyResolver;

    // ========== 节点检索与定位 ==========

    @Override
    public List<LineageNodeRespVO> searchNodes(LineageNodeSearchReqVO reqVO) {
        String keyword = StrUtil.trim(reqVO.getKeyword());
        if (StrUtil.isEmpty(keyword)) {
            throw exception(LINEAGE_QUERY_INVALID, "关键字不能为空");
        }
        int limit = Math.max(1, Math.min(50, reqVO.getLimit() == null ? 20 : reqVO.getLimit()));
        Map<Long, LineageNodeDO> found = new LinkedHashMap<>();
        for (LineageNodeDO node : nodeMapper.selectSearchList("%" + escapeLike(keyword) + "%",
                keyword.toLowerCase(Locale.ROOT), reqVO.getDatasourceId(), limit)) {
            found.put(node.getId(), node);
        }
        List<LineageNodeRespVO> result = toNodeVOs(found.values());
        if (result.size() < limit) {
            for (LineageNodeRespVO extra : searchByBusinessName(keyword, reqVO.getDatasourceId(), limit)) {
                if (result.size() >= limit) {
                    break;
                }
                if (!found.containsKey(extra.getId())) {
                    found.put(extra.getId(), null);
                    result.add(extra);
                }
            }
        }
        return result;
    }

    @Override
    public LineageNodeRespVO resolveByMetaTable(Long metaTableId) {
        TableMatch table = tableKeyResolver.getTable(metaTableId);
        if (table == null) {
            return null;
        }
        List<LineageNodeRespVO> candidates = verifiedCandidates(List.of(table));
        if (candidates.isEmpty()) {
            return null;
        }
        Set<Long> withEdges = new HashSet<>(nodeMapper.selectIdsHavingEdges(
                candidates.stream().map(LineageNodeRespVO::getId).toList()));
        return candidates.stream().filter(node -> withEdges.contains(node.getId())).findFirst()
                .orElse(candidates.get(0));
    }

    // ========== 表级血缘图 ==========

    @Override
    public LineageGraphRespVO getGraph(LineageGraphReqVO reqVO) {
        LineageNodeDO root = requireNode(reqVO.getNodeId());
        LineageDirectionEnum direction = direction(reqVO.getDirection());
        int depth = depth(reqVO.getDepth(), 2, 5);
        Map<Long, Integer> depthOf = new LinkedHashMap<>();
        depthOf.put(root.getId(), 0);
        boolean truncated = walk(root.getId(), direction, depth, depthOf, MAX_GRAPH_NODES,
                (frontier, visited, upstream, limit) -> tableEdgeMapper.selectNeighborIds(frontier, visited, upstream, limit));
        List<Long> ids = new ArrayList<>(depthOf.keySet());

        List<LineageTableEdgeDO> rows = tableEdgeMapper.selectInducedEdges(ids, MAX_EDGE_ROWS + 1);
        if (rows.size() > MAX_EDGE_ROWS) {
            truncated = true;
            rows = rows.subList(0, MAX_EDGE_ROWS);
        }
        Map<List<Long>, EdgeAcc> grouped = new LinkedHashMap<>();
        Set<Long> sourceIds = new LinkedHashSet<>();
        for (LineageTableEdgeDO row : rows) {
            EdgeAcc acc = grouped.computeIfAbsent(List.of(row.getUpstreamNodeId(), row.getDownstreamNodeId()),
                    ignored -> new EdgeAcc());
            acc.origins.add(row.getOrigin());
            acc.sourceIds.add(row.getSourceRefId());
            sourceIds.add(row.getSourceRefId());
        }
        Set<Long> upstreamExpandable = new HashSet<>(tableEdgeMapper.selectIdsWithOutsideNeighbor(ids, true));
        Set<Long> downstreamExpandable = new HashSet<>(tableEdgeMapper.selectIdsWithOutsideNeighbor(ids, false));
        Map<Long, LineageNodeRespVO> nodes = loadNodeVOs(ids);
        Map<Long, LineageSourceBriefRespVO> sources = loadSources(sourceIds);

        LineageGraphRespVO result = new LineageGraphRespVO();
        result.setRootNodeId(root.getId());
        ids.stream().sorted(Comparator.comparing((Long id) -> depthOf.get(id)).thenComparing(Comparator.naturalOrder()))
                .forEach(id -> {
                    LineageNodeRespVO node = nodes.get(id);
                    if (node == null) {
                        return;
                    }
                    LineageGraphNodeRespVO vo = BeanUtils.toBean(node, LineageGraphNodeRespVO.class);
                    vo.setDepth(depthOf.get(id));
                    vo.setUpstreamExpandable(upstreamExpandable.contains(id));
                    vo.setDownstreamExpandable(downstreamExpandable.contains(id));
                    result.getNodes().add(vo);
                });
        grouped.forEach((pair, acc) -> {
            LineageGraphEdgeRespVO edge = new LineageGraphEdgeRespVO();
            edge.setUpstreamNodeId(pair.get(0));
            edge.setDownstreamNodeId(pair.get(1));
            edge.setOrigins(new ArrayList<>(acc.origins));
            edge.setSources(pick(sources, acc.sourceIds));
            result.getEdges().add(edge);
        });
        result.setTruncated(truncated);
        return result;
    }

    // ========== 字段级血缘图 ==========

    @Override
    public LineageColumnGraphRespVO getColumnGraph(LineageColumnGraphReqVO reqVO) {
        LineageNodeDO root = requireNode(reqVO.getNodeId());
        String column = requireColumn(reqVO.getColumn());
        LineageDirectionEnum direction = direction(reqVO.getDirection());
        int depth = depth(reqVO.getDepth(), 2, 5);
        LineageColumnKey rootKey = new LineageColumnKey(root.getId(), column);
        Map<LineageColumnKey, Integer> depthOf = new LinkedHashMap<>();
        depthOf.put(rootKey, 0);
        boolean truncated = walk(rootKey, direction, depth, depthOf, MAX_COLUMN_NODES,
                (frontier, visited, upstream, limit) -> columnEdgeMapper.selectNeighborKeys(frontier, visited, upstream, limit));
        List<LineageColumnKey> keys = new ArrayList<>(depthOf.keySet());

        List<LineageColumnEdgeDO> rows = columnEdgeMapper.selectInducedEdges(keys, MAX_EDGE_ROWS + 1);
        if (rows.size() > MAX_EDGE_ROWS) {
            truncated = true;
            rows = rows.subList(0, MAX_EDGE_ROWS);
        }
        Map<List<Object>, ColumnEdgeAcc> grouped = new LinkedHashMap<>();
        Set<Long> sourceIds = new LinkedHashSet<>();
        for (LineageColumnEdgeDO row : rows) {
            List<Object> groupKey = List.of(row.getUpstreamNodeId(), row.getUpstreamColumn(), row.getDownstreamNodeId(),
                    row.getDownstreamColumn(), row.getTransformType(), row.getTransformSubtype());
            ColumnEdgeAcc acc = grouped.computeIfAbsent(groupKey, ignored -> new ColumnEdgeAcc(row));
            acc.ambiguous |= Boolean.TRUE.equals(row.getAmbiguous());
            acc.masking |= Boolean.TRUE.equals(row.getMasking());
            acc.sourceIds.add(row.getSourceRefId());
            sourceIds.add(row.getSourceRefId());
        }
        Set<LineageColumnKey> upstreamExpandable = new HashSet<>(columnEdgeMapper.selectKeysWithOutsideNeighbor(keys, true));
        Set<LineageColumnKey> downstreamExpandable = new HashSet<>(columnEdgeMapper.selectKeysWithOutsideNeighbor(keys, false));
        Map<Long, LineageNodeRespVO> nodes = loadNodeVOs(keys.stream().map(LineageColumnKey::getNodeId).distinct().toList());
        Map<Long, LineageSourceBriefRespVO> sources = loadSources(sourceIds);

        LineageColumnGraphRespVO result = new LineageColumnGraphRespVO();
        result.setRootNodeId(root.getId());
        result.setRootColumn(column);
        keys.stream().sorted(Comparator.comparing((LineageColumnKey key) -> depthOf.get(key))
                        .thenComparing(LineageColumnKey::getNodeId).thenComparing(LineageColumnKey::getColumnName))
                .forEach(key -> {
                    LineageNodeRespVO node = nodes.get(key.getNodeId());
                    if (node == null) {
                        return;
                    }
                    LineageColumnGraphNodeRespVO vo = new LineageColumnGraphNodeRespVO();
                    vo.setNodeId(key.getNodeId());
                    vo.setColumn(key.getColumnName());
                    vo.setDepth(depthOf.get(key));
                    vo.setUpstreamExpandable(upstreamExpandable.contains(key));
                    vo.setDownstreamExpandable(downstreamExpandable.contains(key));
                    vo.setNode(node);
                    result.getNodes().add(vo);
                });
        grouped.values().forEach(acc -> {
            LineageColumnGraphEdgeRespVO edge = new LineageColumnGraphEdgeRespVO();
            edge.setUpstreamNodeId(acc.first.getUpstreamNodeId());
            edge.setUpstreamColumn(acc.first.getUpstreamColumn());
            edge.setDownstreamNodeId(acc.first.getDownstreamNodeId());
            edge.setDownstreamColumn(acc.first.getDownstreamColumn());
            edge.setTransformType(acc.first.getTransformType());
            edge.setTransformSubtype(acc.first.getTransformSubtype());
            edge.setAmbiguous(acc.ambiguous);
            edge.setMasking(acc.masking);
            edge.setSources(pick(sources, acc.sourceIds));
            result.getEdges().add(edge);
        });
        result.setTruncated(truncated);
        return result;
    }

    @Override
    public List<String> getNodeColumns(Long nodeId) {
        LineageNodeDO node = requireNode(nodeId);
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (LineageNodeTypeEnum.TABLE.name().equals(node.getNodeType())) {
            TableKey key = new TableKey(node.getDatasourceId(), node.getSchemaName(), node.getTableName());
            TableMatch match = tableKeyResolver.resolve(List.of(key)).get(key);
            if (match != null) {
                for (String name : tableKeyResolver.loadColumnNames(List.of(match.tableId()))
                        .getOrDefault(match.tableId(), List.of())) {
                    if (name != null && seen.add(name.toLowerCase(Locale.ROOT))) {
                        result.add(name);
                    }
                }
            }
        }
        boolean wildcard = false;
        for (String name : columnEdgeMapper.selectColumnNames(nodeId, NODE_EDGE_COLUMN_LIMIT)) {
            if (LineageContractConstants.WILDCARD_COLUMN.equals(name)) {
                wildcard = true;
            } else if (seen.add(name.toLowerCase(Locale.ROOT))) {
                result.add(name);
            }
        }
        if (wildcard) {
            result.add(LineageContractConstants.WILDCARD_COLUMN);
        }
        return result;
    }

    // ========== 影响分析 ==========

    @Override
    public LineageImpactRespVO getImpact(LineageImpactReqVO reqVO) {
        LineageNodeDO root = requireNode(reqVO.getNodeId());
        int depth = depth(reqVO.getDepth(), 5, 10);
        String column = StrUtil.isBlank(reqVO.getColumn()) ? null : requireColumn(reqVO.getColumn());
        Map<LineageColumnKey, ImpactAcc> items = new LinkedHashMap<>();
        boolean truncated;
        if (column == null) {
            truncated = expandImpact(new LineageColumnKey(root.getId(), null), depth, items, (frontier, visited, limit) ->
                    tableEdgeMapper.selectDownstreamHits(ids(frontier), ids(visited), limit));
        } else {
            truncated = expandImpact(new LineageColumnKey(root.getId(), column), depth, items,
                    columnEdgeMapper::selectDownstreamHits);
        }
        Set<Long> sourceIds = new LinkedHashSet<>();
        items.values().forEach(acc -> sourceIds.addAll(acc.sourceIds));
        Map<Long, LineageNodeRespVO> nodes = loadNodeVOs(items.keySet().stream()
                .map(LineageColumnKey::getNodeId).distinct().toList());
        Map<Long, LineageSourceBriefRespVO> sources = loadSources(sourceIds);

        LineageImpactRespVO result = new LineageImpactRespVO();
        items.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<LineageColumnKey, ImpactAcc> entry) -> entry.getValue().depth)
                        .thenComparing(entry -> entry.getKey().getNodeId())
                        .thenComparing(entry -> StrUtil.nullToEmpty(entry.getKey().getColumnName())))
                .forEach(entry -> {
                    LineageNodeRespVO node = nodes.get(entry.getKey().getNodeId());
                    if (node == null) {
                        return;
                    }
                    LineageImpactItemRespVO item = new LineageImpactItemRespVO();
                    item.setNodeId(entry.getKey().getNodeId());
                    item.setColumn(entry.getKey().getColumnName());
                    item.setDepth(entry.getValue().depth);
                    item.setSources(pick(sources, entry.getValue().sourceIds));
                    item.setNode(node);
                    result.getItems().add(item);
                });
        result.setTruncated(truncated);
        return result;
    }

    // ========== 上报来源 ==========

    @Override
    public PageResult<LineageSourceRespVO> getSourcePage(LineageSourcePageReqVO reqVO) {
        PageResult<LineageSourceDO> page = sourceMapper.selectPage(reqVO);
        List<LineageSourceRespVO> list = new ArrayList<>(page.getList().size());
        for (LineageSourceDO source : page.getList()) {
            LineageSourceRespVO vo = new LineageSourceRespVO();
            vo.setId(source.getId());
            vo.setSourceType(source.getSourceType());
            vo.setSourceId(source.getSourceId());
            vo.setSourceName(source.getSourceName());
            vo.setSourceRevision(source.getSourceRevision());
            vo.setAnalyzerVersion(source.getAnalyzerVersion());
            vo.setStatus(source.getStatus());
            vo.setIssues(parseIssues(source.getIssues()));
            vo.setTableEdgeCount(source.getTableEdgeCount());
            vo.setColumnEdgeCount(source.getColumnEdgeCount());
            vo.setReportedTime(source.getReportedTime());
            list.add(vo);
        }
        return new PageResult<>(list, page.getTotal());
    }

    // ========== 遍历 ==========

    /**
     * 一层邻居查询：返回 frontier 在指定方向上未访问的邻居，按稳定顺序、最多 limit 个。
     */
    @FunctionalInterface
    interface NeighborQuery<K> {
        List<K> query(List<K> frontier, List<K> visited, boolean upstream, int limit);
    }

    /**
     * 影响分析的一层下游查询：返回 frontier 未访问的下游及触达它的来源。
     */
    @FunctionalInterface
    interface HitQuery {
        List<LineageEdgeHit> query(List<LineageColumnKey> frontier, List<LineageColumnKey> visited, int limit);
    }

    /**
     * 按层分批 BFS；BOTH 时同一层先上游后下游，使两侧近邻优先占用节点预算。两个方向各自维护已访问集合
     * （环上的节点可同时出现在两侧的遍历中，层数取首次发现的值），节点预算按两侧并集计算。返回是否因上限截断。
     */
    static <K> boolean walk(K root, LineageDirectionEnum direction, int depth, Map<K, Integer> depthOf, int maxNodes,
            NeighborQuery<K> neighbors) {
        boolean up = direction != LineageDirectionEnum.DOWNSTREAM;
        boolean down = direction != LineageDirectionEnum.UPSTREAM;
        List<K> upFrontier = up ? List.of(root) : List.of();
        List<K> downFrontier = down ? List.of(root) : List.of();
        Set<K> upVisited = new LinkedHashSet<>(List.of(root));
        Set<K> downVisited = new LinkedHashSet<>(List.of(root));
        for (int level = 1; level <= depth && (!upFrontier.isEmpty() || !downFrontier.isEmpty()); level++) {
            if (!upFrontier.isEmpty()) {
                upFrontier = expandLevel(upFrontier, true, -level, depthOf, upVisited, maxNodes, neighbors);
                if (upFrontier == null) {
                    return true;
                }
            }
            if (!downFrontier.isEmpty()) {
                downFrontier = expandLevel(downFrontier, false, level, depthOf, downVisited, maxNodes, neighbors);
                if (downFrontier == null) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 展开一层，返回新的 frontier；预算不足以容纳全部新节点时写入能容纳的部分并返回 null 表示截断。
     * 查询上限 = 预算剩余 + 1 + 另一方向已发现但本方向未访问的节点数，后者不占预算。
     */
    private static <K> List<K> expandLevel(List<K> frontier, boolean upstream, int levelDepth, Map<K, Integer> depthOf,
            Set<K> visited, int maxNodes, NeighborQuery<K> neighbors) {
        int limit = Math.max(1, maxNodes - visited.size() + 1);
        List<K> found = neighbors.query(frontier, new ArrayList<>(visited), upstream, limit);
        List<K> next = new ArrayList<>(found.size());
        for (K key : found) {
            if (!visited.add(key)) {
                continue;
            }
            if (!depthOf.containsKey(key)) {
                if (depthOf.size() >= maxNodes) {
                    return null;
                }
                depthOf.put(key, levelDepth);
            }
            next.add(key);
        }
        return next;
    }

    /**
     * 影响分析逐层向下游展开；返回是否因条目或单层边行上限截断。
     */
    static boolean expandImpact(LineageColumnKey root, int depth, Map<LineageColumnKey, ImpactAcc> items,
            HitQuery hits) {
        boolean truncated = false;
        List<LineageColumnKey> frontier = List.of(root);
        for (int level = 1; level <= depth && !frontier.isEmpty(); level++) {
            List<LineageColumnKey> visited = new ArrayList<>(items.size() + 1);
            visited.add(root);
            visited.addAll(items.keySet());
            List<LineageEdgeHit> rows = hits.query(frontier, visited, IMPACT_ROWS_PER_LEVEL + 1);
            if (rows.size() > IMPACT_ROWS_PER_LEVEL) {
                truncated = true;
                rows = rows.subList(0, IMPACT_ROWS_PER_LEVEL);
            }
            List<LineageColumnKey> next = new ArrayList<>();
            for (LineageEdgeHit row : rows) {
                LineageColumnKey key = new LineageColumnKey(row.getNodeId(), row.getColumnName());
                if (key.equals(root)) {
                    continue;
                }
                ImpactAcc acc = items.get(key);
                if (acc == null) {
                    if (items.size() >= MAX_IMPACT_ITEMS) {
                        truncated = true;
                        continue;
                    }
                    acc = new ImpactAcc(level);
                    items.put(key, acc);
                    next.add(key);
                }
                if (acc.depth == level) {
                    acc.sourceIds.add(row.getSourceRefId());
                }
            }
            frontier = next;
        }
        return truncated;
    }

    // ========== 节点与来源装配 ==========

    private List<LineageNodeRespVO> searchByBusinessName(String keyword, Long datasourceId, int limit) {
        List<TableMatch> tables = tableKeyResolver.searchByBusinessName(keyword, datasourceId, limit);
        if (tables.isEmpty()) {
            return List.of();
        }
        List<LineageNodeRespVO> candidates = verifiedCandidates(tables);
        if (candidates.isEmpty()) {
            return List.of();
        }
        Set<Long> withEdges = new HashSet<>(nodeMapper.selectIdsHavingEdges(
                candidates.stream().map(LineageNodeRespVO::getId).toList()));
        return candidates.stream().filter(node -> withEdges.contains(node.getId())).toList();
    }

    /**
     * 为元数据表生成可能的节点键（schema 限定、库名限定、不限定），只保留经正向匹配确认对应该表的节点，按键的优先级排序。
     */
    private List<LineageNodeRespVO> verifiedCandidates(List<TableMatch> tables) {
        Map<String, Long> keyToTable = new LinkedHashMap<>();
        for (TableMatch table : tables) {
            for (String namespace : new LinkedHashSet<>(Arrays.asList(StrUtil.nullToEmpty(table.schemaName()),
                    StrUtil.nullToEmpty(table.dbName()), ""))) {
                keyToTable.putIfAbsent(LineageNodeKeys.tableKey(table.datasourceId(), namespace, table.tableName()),
                        table.tableId());
            }
        }
        Map<String, LineageNodeDO> byKey = new HashMap<>();
        for (LineageNodeDO node : nodeMapper.selectListByNodeKeys(keyToTable.keySet())) {
            byKey.put(node.getNodeKey(), node);
        }
        List<LineageNodeDO> ordered = new ArrayList<>();
        List<Long> expected = new ArrayList<>();
        keyToTable.forEach((key, tableId) -> {
            LineageNodeDO node = byKey.get(key);
            if (node != null) {
                ordered.add(node);
                expected.add(tableId);
            }
        });
        List<LineageNodeRespVO> vos = toNodeVOs(ordered);
        List<LineageNodeRespVO> result = new ArrayList<>();
        for (int i = 0; i < vos.size(); i++) {
            if (expected.get(i).equals(vos.get(i).getMetaTableId())) {
                result.add(vos.get(i));
            }
        }
        return result;
    }

    private Map<Long, LineageNodeRespVO> loadNodeVOs(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, LineageNodeRespVO> result = new HashMap<>();
        for (LineageNodeRespVO vo : toNodeVOs(nodeMapper.selectByIds(ids))) {
            result.put(vo.getId(), vo);
        }
        return result;
    }

    /**
     * 节点转 VO，并按元数据匹配规则批量补齐 metaTableId 与 businessName。
     */
    private List<LineageNodeRespVO> toNodeVOs(Collection<LineageNodeDO> nodes) {
        List<LineageNodeRespVO> result = new ArrayList<>(nodes.size());
        List<LineageNodeRespVO> tableVOs = new ArrayList<>();
        List<TableKey> tableKeys = new ArrayList<>();
        for (LineageNodeDO node : nodes) {
            LineageNodeRespVO vo = BeanUtils.toBean(node, LineageNodeRespVO.class);
            result.add(vo);
            if (LineageNodeTypeEnum.TABLE.name().equals(node.getNodeType())) {
                tableVOs.add(vo);
                tableKeys.add(new TableKey(node.getDatasourceId(), node.getSchemaName(), node.getTableName()));
            }
        }
        if (!tableKeys.isEmpty()) {
            Map<TableKey, TableMatch> matches = tableKeyResolver.resolve(tableKeys);
            for (int i = 0; i < tableVOs.size(); i++) {
                TableMatch match = matches.get(tableKeys.get(i));
                if (match != null) {
                    tableVOs.get(i).setMetaTableId(match.tableId());
                    tableVOs.get(i).setBusinessName(match.businessName());
                }
            }
        }
        return result;
    }

    private Map<Long, LineageSourceBriefRespVO> loadSources(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, LineageSourceBriefRespVO> result = new HashMap<>();
        for (LineageSourceDO source : sourceMapper.selectByIds(ids)) {
            result.put(source.getId(), BeanUtils.toBean(source, LineageSourceBriefRespVO.class));
        }
        return result;
    }

    private static List<LineageSourceBriefRespVO> pick(Map<Long, LineageSourceBriefRespVO> sources,
            Collection<Long> ids) {
        return ids.stream().map(sources::get).filter(Objects::nonNull).toList();
    }

    private static List<LineageIssueRespVO> parseIssues(String json) {
        if (StrUtil.isBlank(json)) {
            return new ArrayList<>();
        }
        List<LineageIssueRespVO> issues = JsonUtils.parseArray(json, LineageIssueRespVO.class);
        return issues == null ? new ArrayList<>() : issues;
    }

    // ========== 参数 ==========

    private LineageNodeDO requireNode(Long nodeId) {
        LineageNodeDO node = nodeId == null ? null : nodeMapper.selectById(nodeId);
        if (node == null) {
            throw exception(LINEAGE_NODE_NOT_EXISTS);
        }
        return node;
    }

    private static String requireColumn(String value) {
        String column = LineageNodeKeys.column(value);
        if (column == null || column.length() > LineageContractConstants.MAX_COLUMN_NAME_LENGTH) {
            throw exception(LINEAGE_QUERY_INVALID, "字段名不能为空且不能超过 255 个字符");
        }
        return column;
    }

    private static LineageDirectionEnum direction(String value) {
        if (StrUtil.isBlank(value)) {
            return LineageDirectionEnum.BOTH;
        }
        if (!LineageDirectionEnum.isValid(value)) {
            throw exception(LINEAGE_QUERY_INVALID, "方向必须是 UPSTREAM/DOWNSTREAM/BOTH");
        }
        return LineageDirectionEnum.valueOf(value);
    }

    private static int depth(Integer value, int defaultValue, int max) {
        int depth = value == null ? defaultValue : value;
        if (depth < 1 || depth > max) {
            throw exception(LINEAGE_QUERY_INVALID, "层数必须在 1 到 " + max + " 之间");
        }
        return depth;
    }

    private static List<Long> ids(List<LineageColumnKey> keys) {
        return keys.stream().map(LineageColumnKey::getNodeId).toList();
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static final class EdgeAcc {
        private final Set<String> origins = new TreeSet<>();
        private final Set<Long> sourceIds = new LinkedHashSet<>();
    }

    private static final class ColumnEdgeAcc {
        private final LineageColumnEdgeDO first;
        private final Set<Long> sourceIds = new LinkedHashSet<>();
        private boolean ambiguous;
        private boolean masking;

        private ColumnEdgeAcc(LineageColumnEdgeDO first) {
            this.first = first;
        }
    }

    static final class ImpactAcc {
        final int depth;
        final Set<Long> sourceIds = new LinkedHashSet<>();

        ImpactAcc(int depth) {
            this.depth = depth;
        }
    }

}
