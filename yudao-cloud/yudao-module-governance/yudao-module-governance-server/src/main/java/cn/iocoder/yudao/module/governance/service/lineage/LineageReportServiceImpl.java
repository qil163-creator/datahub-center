package cn.iocoder.yudao.module.governance.service.lineage;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageReportReqDTO;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageReportRespDTO;
import cn.iocoder.yudao.module.governance.dal.dataobject.lineage.LineageColumnEdgeDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.lineage.LineageNodeDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.lineage.LineageSourceDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.lineage.LineageTableEdgeDO;
import cn.iocoder.yudao.module.governance.dal.mysql.lineage.LineageColumnEdgeMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.lineage.LineageNodeMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.lineage.LineageSourceMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.lineage.LineageTableEdgeMapper;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageReportResultEnum;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageReportStatusEnum;
import cn.iocoder.yudao.module.governance.service.lineage.LineageNormalizedReport.ColumnEdgeSpec;
import cn.iocoder.yudao.module.governance.service.lineage.LineageNormalizedReport.NodeSpec;
import cn.iocoder.yudao.module.governance.service.lineage.LineageNormalizedReport.TableEdgeSpec;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 血缘上报实现。
 *
 * <p>并发与幂等：先 {@code INSERT ... ON CONFLICT DO NOTHING} 确保来源行存在，再 {@code SELECT ... FOR UPDATE}
 * 锁住它，同一来源的上报因此串行；不同来源只在共享节点上相遇，节点按 nodeKey 排序插入、已存在时不加锁，
 * 避免并发首建时互相等待成环。整个过程在一个事务内，失败整体回滚，生产者可原样重试。
 * 调用方的租户、组织或操作人参数一概不被信任，契约中也不存在这些字段。</p>
 */
@Service
public class LineageReportServiceImpl implements LineageReportService {

    /** 无登录用户（服务间调用）时写入审计字段的操作人 */
    static final String SYSTEM_ACTOR = "lineage-report";

    private static final int NODE_BATCH_SIZE = 500;
    private static final int NODE_QUERY_BATCH_SIZE = 1000;
    private static final int EDGE_BATCH_SIZE = 1000;

    @Resource
    private LineageSourceMapper sourceMapper;
    @Resource
    private LineageNodeMapper nodeMapper;
    @Resource
    private LineageTableEdgeMapper tableEdgeMapper;
    @Resource
    private LineageColumnEdgeMapper columnEdgeMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LineageReportRespDTO report(LineageReportReqDTO req) {
        LineageNormalizedReport report = LineageReportNormalizer.normalize(req);
        String actor = currentActor();
        boolean created = sourceMapper.insertIfAbsent(report.sourceType(), report.sourceId(), report.sourceName(),
                report.status(), actor) > 0;
        LineageSourceDO source = sourceMapper.selectByKeyForUpdate(report.sourceType(), report.sourceId());
        if (source == null) {
            throw new IllegalStateException("血缘来源行确保后仍不可见：" + report.sourceType() + "/" + report.sourceId());
        }
        int order = compareRevision(report.sourceRevision(), report.analyzerVersion(), source);
        boolean storedRemoved = !created && LineageReportStatusEnum.REMOVED.name().equals(source.getStatus());

        if (LineageReportStatusEnum.REMOVED.name().equals(report.status())) {
            if (storedRemoved && order <= 0) {
                return result(LineageReportResultEnum.NOOP, source);
            }
            deleteEdges(source.getId());
            boolean advance = order >= 0;
            return accept(source, report, actor, advance, 0, 0);
        }
        if (order < 0 || (order == 0 && storedRemoved)) {
            return result(LineageReportResultEnum.STALE, source);
        }
        if (order == 0 && !created && Objects.equals(report.contentHash(), source.getContentHash())) {
            return result(LineageReportResultEnum.NOOP, source);
        }
        deleteEdges(source.getId());
        Map<String, Long> nodeIds = upsertNodes(report.nodes(), actor);
        insertTableEdges(source.getId(), report.tableEdges(), nodeIds, actor);
        insertColumnEdges(source.getId(), report.columnEdges(), nodeIds, actor);
        return accept(source, report, actor, true, report.tableEdges().size(), report.columnEdges().size());
    }

    /**
     * 按 (sourceRevision, analyzerVersion) 字典序比较报告与已存值。
     */
    static int compareRevision(long revision, int analyzerVersion, LineageSourceDO source) {
        int byRevision = Long.compare(revision, source.getSourceRevision());
        return byRevision != 0 ? byRevision : Integer.compare(analyzerVersion, source.getAnalyzerVersion());
    }

    private LineageReportRespDTO accept(LineageSourceDO source, LineageNormalizedReport report, String actor,
            boolean advanceRevision, int tableEdgeCount, int columnEdgeCount) {
        LineageSourceDO update = new LineageSourceDO();
        update.setId(source.getId());
        update.setSourceName(report.sourceName());
        update.setStatus(report.status());
        update.setIssues(report.issuesJson());
        update.setTableEdgeCount(tableEdgeCount);
        update.setColumnEdgeCount(columnEdgeCount);
        update.setReportedTime(LocalDateTime.now());
        update.setUpdater(actor);
        if (advanceRevision) {
            update.setSourceRevision(report.sourceRevision());
            update.setAnalyzerVersion(report.analyzerVersion());
            update.setContentHash(report.contentHash());
        }
        sourceMapper.updateById(update);
        return new LineageReportRespDTO(LineageReportResultEnum.ACCEPTED.name(),
                advanceRevision ? report.sourceRevision() : source.getSourceRevision(),
                advanceRevision ? report.analyzerVersion() : source.getAnalyzerVersion());
    }

    private static LineageReportRespDTO result(LineageReportResultEnum result, LineageSourceDO source) {
        return new LineageReportRespDTO(result.name(), source.getSourceRevision(), source.getAnalyzerVersion());
    }

    private void deleteEdges(Long sourceRefId) {
        columnEdgeMapper.deleteBySourceRefId(sourceRefId);
        tableEdgeMapper.deleteBySourceRefId(sourceRefId);
    }

    /**
     * 插入缺失节点并返回 nodeKey → 节点编号；已有节点仅在展示名为空时补齐展示名。
     */
    private Map<String, Long> upsertNodes(Map<String, NodeSpec> specs, String actor) {
        if (specs.isEmpty()) {
            return Map.of();
        }
        List<NodeSpec> sorted = specs.values().stream().sorted(Comparator.comparing(NodeSpec::nodeKey)).toList();
        for (List<NodeSpec> batch : CollUtil.split(sorted, NODE_BATCH_SIZE)) {
            nodeMapper.insertIgnoreExisting(batch.stream().map(LineageReportServiceImpl::toNode).toList(), actor);
        }
        Map<String, Long> ids = new HashMap<>(specs.size() * 2);
        List<LineageNodeDO> displayFills = new ArrayList<>();
        for (List<NodeSpec> batch : CollUtil.split(sorted, NODE_QUERY_BATCH_SIZE)) {
            List<LineageNodeDO> rows = nodeMapper.selectListByNodeKeys(batch.stream().map(NodeSpec::nodeKey).toList());
            for (LineageNodeDO row : rows) {
                ids.put(row.getNodeKey(), row.getId());
                NodeSpec spec = specs.get(row.getNodeKey());
                if (row.getDisplayName() == null && spec != null && spec.displayName() != null) {
                    LineageNodeDO fill = new LineageNodeDO();
                    fill.setId(row.getId());
                    fill.setDisplayName(spec.displayName());
                    displayFills.add(fill);
                }
            }
        }
        if (ids.size() != specs.size()) {
            throw new IllegalStateException("血缘节点写入后数量不一致：期望 " + specs.size() + "，实际 " + ids.size());
        }
        displayFills.sort(Comparator.comparing(LineageNodeDO::getId));
        for (List<LineageNodeDO> batch : CollUtil.split(displayFills, NODE_BATCH_SIZE)) {
            nodeMapper.fillMissingDisplayNames(batch, actor);
        }
        return ids;
    }

    private void insertTableEdges(Long sourceRefId, List<TableEdgeSpec> specs, Map<String, Long> nodeIds,
            String actor) {
        List<LineageTableEdgeDO> rows = new ArrayList<>(specs.size());
        for (TableEdgeSpec spec : specs) {
            LineageTableEdgeDO row = new LineageTableEdgeDO();
            row.setSourceRefId(sourceRefId);
            row.setUpstreamNodeId(nodeIds.get(spec.upstreamKey()));
            row.setDownstreamNodeId(nodeIds.get(spec.downstreamKey()));
            row.setOrigin(spec.origin());
            row.setStatementIndex(spec.statementIndex());
            rows.add(row);
        }
        for (List<LineageTableEdgeDO> batch : CollUtil.split(rows, EDGE_BATCH_SIZE)) {
            tableEdgeMapper.insertEdges(batch, actor);
        }
    }

    private void insertColumnEdges(Long sourceRefId, List<ColumnEdgeSpec> specs, Map<String, Long> nodeIds,
            String actor) {
        List<LineageColumnEdgeDO> rows = new ArrayList<>(specs.size());
        for (ColumnEdgeSpec spec : specs) {
            LineageColumnEdgeDO row = new LineageColumnEdgeDO();
            row.setSourceRefId(sourceRefId);
            row.setUpstreamNodeId(nodeIds.get(spec.upstreamKey()));
            row.setUpstreamColumn(spec.upstreamColumn());
            row.setDownstreamNodeId(nodeIds.get(spec.downstreamKey()));
            row.setDownstreamColumn(spec.downstreamColumn());
            row.setTransformType(spec.transformType());
            row.setTransformSubtype(spec.transformSubtype());
            row.setAmbiguous(spec.ambiguous());
            row.setMasking(spec.masking());
            row.setStatementIndex(spec.statementIndex());
            rows.add(row);
        }
        for (List<LineageColumnEdgeDO> batch : CollUtil.split(rows, EDGE_BATCH_SIZE)) {
            columnEdgeMapper.insertEdges(batch, actor);
        }
    }

    private static LineageNodeDO toNode(NodeSpec spec) {
        LineageNodeDO node = new LineageNodeDO();
        node.setNodeKey(spec.nodeKey());
        node.setNodeType(spec.nodeType());
        node.setDatasourceId(spec.datasourceId());
        node.setSchemaName(spec.schemaName());
        node.setTableName(spec.tableName());
        node.setExternalKind(spec.externalKind());
        node.setExternalKey(spec.externalKey());
        node.setDisplayName(spec.displayName());
        return node;
    }

    private static String currentActor() {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        return userId == null ? SYSTEM_ACTOR : String.valueOf(userId);
    }

}
