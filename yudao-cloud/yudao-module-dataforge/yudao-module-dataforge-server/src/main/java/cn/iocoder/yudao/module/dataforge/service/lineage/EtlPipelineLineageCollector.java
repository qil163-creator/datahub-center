package cn.iocoder.yudao.module.dataforge.service.lineage;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.SqlLineageStatus;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlPipelineDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlPipelineMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.lineage.LineageMarkerMapper;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlGraphConverter;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageReportReqDTO;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageSourceTypeEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 可视化 ETL 流程血缘：解析发布快照中实际执行的节点与连线（子流程已展开、环境绑定已解析）。
 * 未发布的流程不上报；修订号为发布修订号。
 */
@Component
public class EtlPipelineLineageCollector implements LineageSourceCollector {

    @Resource
    private LineageMarkerMapper markerMapper;
    @Resource
    private EtlPipelineMapper pipelineMapper;
    @Resource
    private EtlGraphLineageExtractor extractor;

    /**
     * 返回来源类型。
     */
    @Override
    public String sourceType() {
        return LineageSourceTypeEnum.ETL_PIPELINE.name();
    }

    /**
     * 返回解析器版本。
     */
    @Override
    public int analyzerVersion() {
        return LineageReportVersions.ETL_PIPELINE;
    }

    /**
     * 有界扫描待上报的流程。
     */
    @Override
    public List<LineageMarkerRow> pending(int limit) {
        List<LineageMarkerRow> rows = new ArrayList<>(markerMapper.selectEtlPipelinePending(analyzerVersion(), limit));
        if (rows.size() < limit) {
            rows.addAll(markerMapper.selectEtlPipelineRemoved(limit - rows.size()));
        }
        return rows;
    }

    /**
     * 按发布快照构造上报内容。
     */
    @Override
    public LineageReportReqDTO build(LineageMarkerRow row) {
        long revision = row.getSourceRevision() == null ? 0L : row.getSourceRevision();
        EtlPipelineDO pipeline = row.isRemovedSource() ? null : pipelineMapper.selectById(row.getId());
        if (pipeline == null) {
            return LineageReportBuilder.removed(sourceType(), row.getId(), row.getSourceName(), revision,
                    analyzerVersion());
        }
        long published = pipeline.getPublishedRevision() == null ? revision : pipeline.getPublishedRevision();
        LineageReportBuilder builder = new LineageReportBuilder(sourceType(), pipeline.getId(), pipeline.getName(),
                published, analyzerVersion());
        JSONObject snapshot;
        try {
            snapshot = JSONUtil.parseObj(StrUtil.blankToDefault(pipeline.getPublishedConfigJson(), "{}"));
        } catch (RuntimeException malformed) {
            return builder.status(SqlLineageStatus.FAILED)
                    .issue("SNAPSHOT_INVALID", "发布快照无法读取，无法解析血缘", null).build();
        }
        extractor.extract(EtlGraphConverter.toNodeDefs(maps(snapshot, "executionNodes", "nodes")),
                EtlGraphConverter.toEdgeDefs(maps(snapshot, "executionEdges", "edges")), builder);
        return builder.build();
    }

    /**
     * 上报后按旧标记 CAS 更新。
     */
    @Override
    public boolean mark(LineageMarkerRow row, String newKey) {
        return markerMapper.updateEtlPipelineKey(row.getId(), row.getLineageReportKey(), newKey) == 1;
    }

    /**
     * 读取快照中的节点或连线数组，优先使用执行态字段。
     */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(JSONObject snapshot, String preferred, String fallback) {
        JSONArray array = snapshot.containsKey(preferred) ? snapshot.getJSONArray(preferred)
                : snapshot.getJSONArray(fallback);
        if (array == null) {
            return List.of();
        }
        List<Map<String, Object>> items = new ArrayList<>(array.size());
        for (Object item : array.toList(Map.class)) {
            items.add((Map<String, Object>) item);
        }
        return items;
    }
}
