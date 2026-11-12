package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.framework.etl.core.engine.EtlPreviewEngine;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.schema.EtlFieldMeta;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
import cn.iocoder.yudao.module.dataforge.service.database.DatabaseDynamicMaskSupport;
import cn.iocoder.yudao.module.dataforge.service.lineage.EtlGraphLineageExtractor;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageNodeRefDTO;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyDynamicMaskReqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.ETL_PREVIEW_MASK_FAILED;

/**
 * ETL 设计预览的动态脱敏：目标节点输出与各输入样本都按字段的物理来源脱敏。
 * <p>
 * 字段来源复用血缘的画布追溯（{@link EtlGraphLineageExtractor#traceFields}）。来源无法完整追溯的字段
 * （脚本/表达式组件、SQL 无法解析等）以画布读取的全部物理表为候选，由 governance 判定是否整体打码；
 * 追溯本身失败时所有字段都按无法追溯处理。governance 不可用时预览不返回数据。
 */
@Slf4j
@Component
public class EtlPreviewMaskSupport {

    static final String SCENE = "ETL_PREVIEW";
    private static final int MAX_SOURCES = 50;
    private static final int MAX_CANDIDATES = 20;

    @Resource
    private EtlGraphLineageExtractor extractor;
    @Resource
    private DatabaseDynamicMaskSupport maskSupport;

    /**
     * 对成功的预览结果脱敏；失败的结果不含数据，原样返回。
     *
     * @param nodes  参与预览的节点（已展开子流程）
     * @param edges  参与预览的连线
     * @param target 目标节点编号
     * @param actor  查看结果的用户
     */
    public EtlPreviewEngine.Result mask(EtlPreviewEngine.Result result, List<EtlNodeDef> nodes, List<EtlEdgeDef> edges,
                                        String target, Long actor) {
        if (!result.success()) {
            return result;
        }
        EtlGraphLineageExtractor.FieldTrace trace = trace(nodes, edges);
        String resource = "ETL 预览：" + target;
        List<EtlPreviewEngine.MaskedField> markers = new ArrayList<>();

        var targetOutcome = maskSupport.maskColumns(SCENE, actor, resource,
                columns(trace, target, names(result.columns(), result.rows())), result.rows(), ETL_PREVIEW_MASK_FAILED);
        targetOutcome.maskedColumns().forEach(column -> markers.add(marker(null, column)));

        List<EtlPreviewEngine.InputSample> samples = new ArrayList<>(result.inputSamples().size());
        for (EtlPreviewEngine.InputSample sample : result.inputSamples()) {
            var outcome = maskSupport.maskColumns(SCENE, actor, resource,
                    columns(trace, sample.sourceNodeNo(), names(sample.columns(), sample.rows())), sample.rows(),
                    ETL_PREVIEW_MASK_FAILED);
            outcome.maskedColumns().forEach(column -> markers.add(marker(sample.sourceNodeNo(), column)));
            samples.add(new EtlPreviewEngine.InputSample(sample.sourceNodeNo(), sample.sourceNodeName(),
                    sample.sourceOutputPort(), sample.inputPort(), sample.columns(), outcome.rows(), sample.rowCount(),
                    sample.truncated()));
        }
        return result.withMaskedRows(targetOutcome.rows(), samples, markers);
    }

    // ==================== 内部 ====================

    /**
     * 追溯字段来源；追溯失败时返回空结果，所有字段按无法追溯处理。
     */
    private EtlGraphLineageExtractor.FieldTrace trace(List<EtlNodeDef> nodes, List<EtlEdgeDef> edges) {
        try {
            return extractor.traceFields(nodes, edges);
        } catch (RuntimeException failure) {
            log.warn("[trace][ETL 预览字段追溯失败，按无法追溯脱敏 failureType={}]",
                    failure.getClass().getSimpleName());
            return new EtlGraphLineageExtractor.FieldTrace(Map.of(), List.of());
        }
    }

    /**
     * 结果中实际出现的字段：声明的列加上行里出现但未声明的键（防止漏掉未声明的字段）。
     */
    static List<String> names(List<EtlFieldMeta> declared, List<Map<String, Object>> rows) {
        Set<String> names = new LinkedHashSet<>();
        if (declared != null) {
            declared.forEach(field -> names.add(field.getName()));
        }
        if (rows != null) {
            rows.forEach(row -> names.addAll(row.keySet()));
        }
        names.remove(null);
        return new ArrayList<>(names);
    }

    /**
     * 构造节点字段的来源。
     */
    private static List<SafetyDynamicMaskReqDTO.Column> columns(EtlGraphLineageExtractor.FieldTrace trace,
                                                               String nodeNo, List<String> names) {
        List<SafetyDynamicMaskReqDTO.Source> candidates = trace.tables().stream().limit(MAX_CANDIDATES)
                .map(EtlPreviewMaskSupport::table).toList();
        List<SafetyDynamicMaskReqDTO.Column> columns = new ArrayList<>(names.size());
        for (String name : names) {
            SafetyDynamicMaskReqDTO.Column column = new SafetyDynamicMaskReqDTO.Column();
            column.setOutputName(name);
            EtlGraphLineageExtractor.TracedField traced = trace.of(nodeNo, name);
            if (!traced.complete()) {
                column.setTraceable(false);
                column.setSources(List.of());
                column.setCandidateTables(candidates);
            } else {
                // 经过哈希、计数等变换的来源不再暴露原值。
                column.setSources(traced.sources().stream().filter(source -> !source.masking()).limit(MAX_SOURCES)
                        .map(source -> SafetyDynamicMaskReqDTO.Source.of(source.datasourceId(), source.schemaName(),
                                source.tableName(), source.column())).toList());
            }
            columns.add(column);
        }
        return columns;
    }

    /**
     * 候选表。
     */
    private static SafetyDynamicMaskReqDTO.Source table(LineageNodeRefDTO ref) {
        return SafetyDynamicMaskReqDTO.Source.of(ref.getDatasourceId(), ref.getSchemaName(), ref.getTableName(), null);
    }

    /**
     * 脱敏字段标注。
     */
    private static EtlPreviewEngine.MaskedField marker(String nodeNo, DatabasePreviewRespDTO.MaskedColumn column) {
        return new EtlPreviewEngine.MaskedField(nodeNo, column.getName(), column.getReason(),
                column.getCategoryNames());
    }
}
