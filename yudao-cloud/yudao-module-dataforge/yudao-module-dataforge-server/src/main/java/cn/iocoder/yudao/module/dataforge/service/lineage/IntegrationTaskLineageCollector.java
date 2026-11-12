package cn.iocoder.yudao.module.dataforge.service.lineage;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.SqlLineageStatus;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.IntegrationTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.integration.IntegrationTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.lineage.LineageMarkerMapper;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlGraphConverter;
import cn.iocoder.yudao.module.dataforge.service.integration.IntegrationFlowCompiler;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageReportReqDTO;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageSourceTypeEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 离线/实时集成任务血缘：解析已保存的画布（与 ETL 共用组件模型），修订号为任务修订号。
 * 画布先经 {@link IntegrationFlowCompiler#graph} 做与保存相同的大小与结构校验。
 */
@Component
public class IntegrationTaskLineageCollector implements LineageSourceCollector {

    @Resource
    private LineageMarkerMapper markerMapper;
    @Resource
    private IntegrationTaskMapper taskMapper;
    @Resource
    private IntegrationFlowCompiler compiler;
    @Resource
    private EtlGraphLineageExtractor extractor;

    /**
     * 返回来源类型。
     */
    @Override
    public String sourceType() {
        return LineageSourceTypeEnum.INTEGRATION_TASK.name();
    }

    /**
     * 返回解析器版本。
     */
    @Override
    public int analyzerVersion() {
        return LineageReportVersions.INTEGRATION_TASK;
    }

    /**
     * 有界扫描待上报的集成任务。
     */
    @Override
    public List<LineageMarkerRow> pending(int limit) {
        List<LineageMarkerRow> rows = new ArrayList<>(markerMapper.selectIntegrationPending(analyzerVersion(), limit));
        if (rows.size() < limit) {
            rows.addAll(markerMapper.selectIntegrationRemoved(limit - rows.size()));
        }
        return rows;
    }

    /**
     * 按当前画布构造上报内容。
     */
    @Override
    public LineageReportReqDTO build(LineageMarkerRow row) {
        long revision = row.getSourceRevision() == null ? 0L : row.getSourceRevision();
        IntegrationTaskDO task = row.isRemovedSource() ? null : taskMapper.selectById(row.getId());
        if (task == null) {
            return LineageReportBuilder.removed(sourceType(), row.getId(), row.getSourceName(), revision,
                    analyzerVersion());
        }
        long current = task.getRevision() == null ? 0L : task.getRevision();
        LineageReportBuilder builder = new LineageReportBuilder(sourceType(), task.getId(), task.getName(), current,
                analyzerVersion());
        JSONObject graph;
        try {
            graph = compiler.graph(task.getFlowJson());
        } catch (RuntimeException invalid) {
            return builder.status(SqlLineageStatus.FAILED)
                    .issue("GRAPH_INVALID", "集成任务画布无效，无法解析血缘", null).build();
        }
        extractor.extract(EtlGraphConverter.toNodeDefs(EtlPipelineLineageCollector.maps(graph, "nodes", "nodes")),
                EtlGraphConverter.toEdgeDefs(EtlPipelineLineageCollector.maps(graph, "edges", "edges")), builder);
        return builder.build();
    }

    /**
     * 上报后按旧标记 CAS 更新。
     */
    @Override
    public boolean mark(LineageMarkerRow row, String newKey) {
        return markerMapper.updateIntegrationKey(row.getId(), row.getLineageReportKey(), newKey) == 1;
    }
}
