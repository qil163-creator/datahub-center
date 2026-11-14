package cn.iocoder.yudao.module.modeling.service.model;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelTypeConfigVO;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic.ModelDraftSaveReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.version.ModelDiffRespVO;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.version.SourceSyncApplyReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.version.SourceSyncApplyRespVO;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.version.SourceSyncPreviewRespVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelDO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;

/**
 * 贴源表结构同步：读取源表当前结构，与当前设计按结构属性比对，确认后写回当前设计。
 * <p>
 * 已对齐字段保留稳定标识与标准、分级、代码表等治理属性，只覆盖结构属性；源表新增的字段追加，源表已删除的字段移除。
 * 写回走草稿保存的 CAS；原本已发布的模型随后重新发布并生成新版本。两步分别提交：发布失败时字段已保存为草稿，
 * 结果中返回原因，用户修正后可直接重新发布。
 */
@Slf4j
@Service
public class LogicModelSourceSyncService {

    private static final String SOURCE = "SOURCE";

    @Resource
    private LogicModelService modelService;

    @Resource
    private LogicModelColumnService columnService;

    @Resource
    private ModelingDatabaseResolver databaseResolver;

    @Resource
    private ModelTypeConfigValidator typeConfigValidator;

    /**
     * 预览源表当前结构与当前设计的差异。
     *
     * @param modelId 逻辑模型编号
     * @return 差异预览
     */
    public SourceSyncPreviewRespVO preview(Long modelId) {
        LogicModelDO model = modelService.getModel(modelId);
        String sourceTable = requireSourceTable(model);
        ModelDiffRespVO diff = diff(model, sourceTable);
        SourceSyncPreviewRespVO result = new SourceSyncPreviewRespVO();
        result.setModelId(model.getId());
        result.setRevision(model.getRevision() == null ? 0L : model.getRevision());
        result.setStatus(model.getStatus());
        result.setDatasourceId(model.getDatasourceId());
        result.setSourceTable(sourceTable);
        result.setDiff(diff);
        return result;
    }

    /**
     * 按源表当前结构更新当前设计。
     *
     * @param request 模型编号与预览时的修订号
     * @return 保存与重新发布结果
     */
    public SourceSyncApplyRespVO apply(SourceSyncApplyReqVO request) {
        LogicModelDO model = modelService.getModel(request.getModelId());
        if (!Objects.equals(model.getRevision() == null ? 0L : model.getRevision(), request.getExpectedRevision())) {
            throw exception(MODEL_DESIGN_REVISION_CONFLICT);
        }
        String sourceTable = requireSourceTable(model);
        ModelDiffRespVO diff = diff(model, sourceTable);
        if (diff.getSummary().getAdded() + diff.getSummary().getRemoved() + diff.getSummary().getModified() == 0) {
            throw exception(MODEL_SOURCE_SYNC_NO_CHANGE);
        }
        boolean wasPublished = LogicModelDO.STATUS_PUBLISHED.equals(model.getStatus());

        // 表类型口径在模型记录中是 JSON 文本、在草稿请求中是对象：先去掉再复制表头，随后单独设置解析结果。
        LogicModelDO header = BeanUtils.toBean(model, LogicModelDO.class);
        header.setTypeConfig(null);
        ModelDraftSaveReqVO draft = BeanUtils.toBean(header, ModelDraftSaveReqVO.class);
        draft.setId(model.getId());
        draft.setModelId(model.getId());
        draft.setExpectedRevision(request.getExpectedRevision());
        draft.setTypeConfig(typeConfigValidator.parse(model.getTypeConfig()));
        draft.setFields(merge(diff));
        modelService.saveDraft(draft);

        SourceSyncApplyRespVO result = new SourceSyncApplyRespVO();
        result.setSaved(true);
        result.setPublished(false);
        if (wasPublished) {
            LogicModelDO saved = modelService.getModel(model.getId());
            try {
                modelService.publishModel(saved.getId(), saved.getRevision());
                result.setPublished(true);
            } catch (ServiceException failed) {
                log.warn("[apply][贴源表同步后重新发布失败 modelId={} code={}]", saved.getId(), failed.getCode());
                result.setPublishMessage(failed.getMessage());
            }
        }
        return result;
    }

    /** 当前设计 → 源表当前结构，只比较结构属性。 */
    private ModelDiffRespVO diff(LogicModelDO model, String sourceTable) {
        List<ModelFieldItem> source = databaseResolver.importFields(model.getDatasourceId(), sourceTable);
        List<ModelFieldItem> current = columnService.listByModelId(model.getId());
        ModelDiffRespVO diff = ModelFieldDiff.compare(null, current, null, source,
                ModelFieldDiff.STRUCTURE_ATTRIBUTES);
        diff.setFromLabel("当前设计");
        diff.setToLabel("源表 " + sourceTable);
        return diff;
    }

    /** 按源表字段顺序生成新字段列表：已对齐字段保留原字段并覆盖结构属性，新增字段取源表定义。 */
    static List<ModelFieldItem> merge(ModelDiffRespVO diff) {
        List<ModelFieldItem> merged = new ArrayList<>();
        int sort = 1;
        for (ModelDiffRespVO.FieldChange change : diff.getFields()) {
            if (ModelDiffRespVO.REMOVED.equals(change.getChangeType())) {
                continue;
            }
            ModelFieldItem source = change.getAfter();
            ModelFieldItem item;
            if (ModelDiffRespVO.ADDED.equals(change.getChangeType())) {
                item = BeanUtils.toBean(source, ModelFieldItem.class);
                item.setFieldUid(null);
            } else {
                item = BeanUtils.toBean(change.getBefore(), ModelFieldItem.class);
                item.setFieldName(source.getFieldName());
                item.setDataType(source.getDataType());
                item.setDataLength(source.getDataLength());
                item.setDataPrecision(source.getDataPrecision());
                item.setDataScale(source.getDataScale());
                item.setPkFlag(source.getPkFlag());
                item.setNullableFlag(source.getNullableFlag());
                item.setDefaultValue(source.getDefaultValue());
                item.setComment(source.getComment());
            }
            item.setSort(sort++);
            merged.add(item);
        }
        ModelFieldUidUtils.ensureFieldUids(merged);
        return merged;
    }

    private String requireSourceTable(LogicModelDO model) {
        if (!SOURCE.equalsIgnoreCase(model.getTableType())) {
            throw exception(MODEL_SOURCE_SYNC_NOT_SOURCE);
        }
        ModelTypeConfigVO config = typeConfigValidator.parse(model.getTypeConfig());
        String sourceTable = config == null || config.getSource() == null ? null : config.getSource().getSourceTable();
        if (model.getDatasourceId() == null || StrUtil.isBlank(sourceTable)) {
            throw exception(MODEL_SOURCE_SYNC_UNAVAILABLE);
        }
        return sourceTable.trim();
    }
}
