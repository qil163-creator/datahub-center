package cn.iocoder.yudao.module.modeling.service.model;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic.*;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicRelationDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.DwLayerDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicRelationMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.indicator.IndicatorModelMapper;
import cn.iocoder.yudao.module.modeling.framework.mybatis.core.type.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import tools.jackson.core.type.TypeReference;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertMap;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertSet;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;

/**
 * 数据建模-逻辑模型 Service 实现类
 *
 * @author Li
 */
@Slf4j
@Service
@Validated
public class LogicModelServiceImpl implements LogicModelService {

    @Resource
    private LogicModelMapper modelMapper;

    @Resource
    private cn.iocoder.yudao.module.modeling.dal.mysql.model.ModelMaterializedMapper materializedMapper;

    @Resource
    private LogicRelationMapper relationMapper;

    @Resource
    private IndicatorModelMapper indicatorMapper;

    @Resource
    private ModelingDatabaseResolver databaseResolver;

    @Resource
    private ModelHierarchyResolver hierarchyResolver;

    @Resource
    private ModelTypeConfigValidator typeConfigValidator;

    @Resource
    private LogicModelColumnService columnService;

    @Resource
    private LogicModelDefinitionService definitionService;

    @Resource
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    /**
     * 创建逻辑模型（正向：手工字段；逆向：从数据源表导入字段），保存当前草稿
     *
     * @param createReqVO 创建参数
     * @return 生成或保存的记录编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createModel(LogicModelCreateReqVO createReqVO) {
        definitionService.normalize(createReqVO);
        validateNameUnique(createReqVO);
        // 归属可暂缺：存量模型、逆向接入的表先进来，发布时再强制补齐
        DwLayerDO layer = hierarchyResolver.validateLayer(createReqVO.getLayerId(), false, null);
        hierarchyResolver.validateSubjectDomain(createReqVO.getSubjectDomainId(), null, createReqVO.getDataDomainId());
        String layerCode = hierarchyResolver.resolveLayerCode(layer);
        hierarchyResolver.validateModelDataDomain(createReqVO.getDataDomainId());
        String tableType = hierarchyResolver.resolveTableType(createReqVO.getTableType(), layer);
        String mode = StrUtil.blankToDefault(createReqVO.getCreateMode(), LogicModelDO.MODE_FORWARD);
        List<ModelFieldItem> fields = createReqVO.getFields();
        // 逆向建模：页面已经带回字段时沿用用户确认的结果；否则再从源表导入。
        if (LogicModelDO.MODE_REVERSE.equals(mode) && (fields == null || fields.isEmpty())) {
            fields = databaseResolver.importFields(createReqVO.getDatabaseId(), createReqVO.getTableName());
        }
        List<ModelFieldItem> fieldList = normalizeFields(fields);
        typeConfigValidator.validateOnSave(createReqVO.getTypeConfig());
        typeConfigValidator.validateReferencesOnSave(createReqVO.getTypeConfig(), fieldList);
        validateInputModels(null, createReqVO.getTypeConfig(), false);
        LogicModelDO model = LogicModelDO.builder()
                .dataDomainId(createReqVO.getDataDomainId())
                .bizCategoryId(createReqVO.getBizCategoryId())
                .revision(0L).layoutRevision(0L).grain(createReqVO.getGrain())
                .name(createReqVO.getName())
                .code(createReqVO.getCode())
                .createMode(mode)
                .typeConfig(createReqVO.getTypeConfig() == null ? null : JsonUtils.toJsonString(createReqVO.getTypeConfig()))
                .status(LogicModelDO.STATUS_DRAFT)
                .modelStyle(StrUtil.blankToDefault(createReqVO.getModelStyle(), "RELATIONAL"))
                .warehouseLayer(layerCode)
                .layerId(createReqVO.getLayerId())
                .subjectDomainId(createReqVO.getSubjectDomainId())
                .tableType(tableType)
                .classificationStatus(hierarchyResolver
                        .resolveClassificationStatus(createReqVO.getLayerId(), createReqVO.getSubjectDomainId(),
                                createReqVO.getDataDomainId(), tableType, createReqVO.getBizCategoryId()))
                .tableNameNotation(createReqVO.getTableNameNotation())
                .sourceSystemId(createReqVO.getSourceSystemId())
                .datasourceId(createReqVO.getDatasourceId())
                .dbType(createReqVO.getDbType())
                .dbName(createReqVO.getDbName())
                .schemaName(createReqVO.getSchemaName())
                .entityId(createReqVO.getEntityId())
                .objType(createReqVO.getObjType())
                .businessType(createReqVO.getBusinessType())
                .dataTimeliness(createReqVO.getDataTimeliness())
                .statisticsStr(createReqVO.getStatisticsStr())
                .grainEntityIds(createReqVO.getGrainEntityIds())
                .description(createReqVO.getDescription())
                .tableCase(tableCaseOf(createReqVO.getTableCase()))
                .documentType(blankToNull(createReqVO.getDocumentType()))
                .documentId(createReqVO.getDocumentId())
                .contactUserId(createReqVO.getContactUserId())
                .contactNumber(blankToNull(createReqVO.getContactNumber()))
                .enabled(enabledOf(createReqVO.getEnabled()))
                .remark(blankToNull(createReqVO.getRemark()))
                .build();
        try {
            modelMapper.insert(model);
        } catch (org.springframework.dao.DuplicateKeyException failure) {
            throw duplicateDefinition(failure);
        }
        columnService.replaceByModelId(model.getId(), fieldList);
        return model.getId();
    }

    /**
     * 修改逻辑模型基础信息
     *
     * @param updateReqVO 更新参数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateModel(LogicModelCreateReqVO updateReqVO) {
        LogicModelDO model = lockModel(updateReqVO.getId(), updateReqVO.getExpectedRevision());
        validateNotDiscarded(model);
        definitionService.normalize(updateReqVO);
        validateNameUnique(updateReqVO);
        DwLayerDO layer = hierarchyResolver.validateLayer(updateReqVO.getLayerId(), false,
                model.getLayerId());
        hierarchyResolver.validateSubjectDomain(updateReqVO.getSubjectDomainId(), model.getSubjectDomainId(),
                updateReqVO.getDataDomainId());
        String layerCode = hierarchyResolver.resolveLayerCode(layer);
        hierarchyResolver.validateModelDataDomain(updateReqVO.getDataDomainId());
        String tableType = hierarchyResolver.resolveTableType(updateReqVO.getTableType(), layer);
        int updated;
        try {
            updated = modelMapper.update(null, Wrappers.lambdaUpdate(LogicModelDO.class)
                    .set(LogicModelDO::getRevision, revision(model) + 1)
                    .set(LogicModelDO::getStatus, LogicModelDO.STATUS_DRAFT)
                    .set(LogicModelDO::getPublishedBy, null).set(LogicModelDO::getPublishedTime, null)
                    .set(LogicModelDO::getGrain, updateReqVO.getGrain())
                    .set(LogicModelDO::getDataDomainId, updateReqVO.getDataDomainId())
                    .set(LogicModelDO::getBizCategoryId, updateReqVO.getBizCategoryId())
                    .set(LogicModelDO::getName, updateReqVO.getName())
                    .set(LogicModelDO::getCode, updateReqVO.getCode())
                    .set(LogicModelDO::getModelStyle, updateReqVO.getModelStyle())
                    .set(LogicModelDO::getWarehouseLayer, layerCode)
                    .set(LogicModelDO::getLayerId, updateReqVO.getLayerId())
                    .set(LogicModelDO::getSubjectDomainId, updateReqVO.getSubjectDomainId())
                    .set(LogicModelDO::getTableType, tableType)
                    .set(LogicModelDO::getClassificationStatus, hierarchyResolver
                            .resolveClassificationStatus(updateReqVO.getLayerId(), updateReqVO.getSubjectDomainId(),
                                    updateReqVO.getDataDomainId(), tableType, updateReqVO.getBizCategoryId()))
                    .set(LogicModelDO::getTableNameNotation, updateReqVO.getTableNameNotation())
                    .set(LogicModelDO::getSourceSystemId, updateReqVO.getSourceSystemId())
                    .set(LogicModelDO::getDatasourceId, updateReqVO.getDatasourceId())
                    .set(LogicModelDO::getDbType, updateReqVO.getDbType())
                    .set(LogicModelDO::getDbName, updateReqVO.getDbName())
                    .set(LogicModelDO::getSchemaName, updateReqVO.getSchemaName())
                    .set(LogicModelDO::getEntityId, updateReqVO.getEntityId())
                    .set(LogicModelDO::getObjType, updateReqVO.getObjType())
                    .set(LogicModelDO::getBusinessType, updateReqVO.getBusinessType())
                    .set(LogicModelDO::getDataTimeliness, updateReqVO.getDataTimeliness())
                    .set(LogicModelDO::getStatisticsStr, updateReqVO.getStatisticsStr())
                    .set(LogicModelDO::getGrainEntityIds, updateReqVO.getGrainEntityIds(),
                            "typeHandler=cn.iocoder.yudao.framework.mybatis.core.type.LongListTypeHandler")
                    .set(LogicModelDO::getDescription, updateReqVO.getDescription())
                    .set(LogicModelDO::getTableCase, tableCaseOf(updateReqVO.getTableCase()))
                    .set(LogicModelDO::getDocumentType, blankToNull(updateReqVO.getDocumentType()))
                    .set(LogicModelDO::getDocumentId, updateReqVO.getDocumentId())
                    .set(LogicModelDO::getContactUserId, updateReqVO.getContactUserId())
                    .set(LogicModelDO::getContactNumber, blankToNull(updateReqVO.getContactNumber()))
                    .set(LogicModelDO::getEnabled, enabledOf(updateReqVO.getEnabled()))
                    .set(LogicModelDO::getRemark, blankToNull(updateReqVO.getRemark()))
                    .eq(LogicModelDO::getId, updateReqVO.getId())
                    .eq(LogicModelDO::getRevision, revision(model)));
        } catch (org.springframework.dao.DuplicateKeyException failure) {
            throw duplicateDefinition(failure);
        }
        if (updated != 1) {
            throw exception(MODEL_DESIGN_REVISION_CONFLICT);
        }
    }

    /**
     * 删除逻辑模型
     *
     * @param id 记录编号
     * @param expectedRevision 预期修订号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteModel(Long id, Long expectedRevision) {
        lockModel(id, expectedRevision);
        requireNoIndicatorReferences(id);
        if (!relationMapper.selectListByModelId(id).isEmpty()) {
            throw exception(MODEL_DESIGN_IN_USE);
        }
        if (materializedMapper.selectCount(new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<cn.iocoder.yudao.module.modeling.dal.dataobject.model.ModelMaterializedDO>()
                .eq(cn.iocoder.yudao.module.modeling.dal.dataobject.model.ModelMaterializedDO::getModelId, id)
                .in(cn.iocoder.yudao.module.modeling.dal.dataobject.model.ModelMaterializedDO::getStatus,
                        "RUNNING", "UNKNOWN")) > 0) {
            throw exception(MODEL_DESIGN_IN_USE);
        }
        modelMapper.deleteById(id);
        columnService.deleteByModelId(id);
        relationMapper.deleteByModelId(id);
    }

    /**
     * 只切换启用/禁用，不改设计内容和生命周期状态。
     *
     * @param id 记录编号
     * @param enabled 启用状态
     * @param expectedRevision 预期修订号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateEnabled(Long id, Integer enabled, Long expectedRevision) {
        LogicModelDO model = lockModel(id, expectedRevision);
        int value = Integer.valueOf(1).equals(enabled) ? 1 : 0;
        int updated = modelMapper.update(null, Wrappers.lambdaUpdate(LogicModelDO.class)
                .set(LogicModelDO::getEnabled, value)
                .set(LogicModelDO::getRevision, revision(model) + 1)
                .eq(LogicModelDO::getId, id)
                .eq(LogicModelDO::getRevision, revision(model)));
        if (updated != 1) {
            throw exception(MODEL_DESIGN_REVISION_CONFLICT);
        }
    }

    /**
     * 获得逻辑模型
     *
     * @param id 记录编号
     * @return 逻辑模型
     */
    @Override
    public LogicModelDO getModel(Long id) {
        return validateModelExists(id);
    }

    /**
     * 逻辑模型分页（主题过滤含子主题）
     *
     * @param pageReqVO 分页查询条件
     * @return 逻辑模型分页（主题过滤含子主题）结果
     */
    @Override
    public PageResult<LogicModelDO> getModelPage(LogicModelPageReqVO pageReqVO) {
        return modelMapper.selectPage(pageReqVO);
    }

    /**
     * 原子保存统一模型草稿、字段与业务口径。
     *
     * @param reqVO 请求参数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveDraft(ModelDraftSaveReqVO reqVO) {
        LogicModelDO model = lockModel(reqVO.getModelId(), reqVO.getExpectedRevision());
        validateNotDiscarded(model);
        List<ModelFieldItem> previousFields = columnService.listByModelId(model.getId());
        reqVO.setId(reqVO.getModelId());
        definitionService.normalize(reqVO);
        if ("SUMMARY".equals(reqVO.getTableType())) {
            carryGeneratedFieldIdentities(reqVO.getFields(), previousFields);
        }
        List<ModelFieldItem> fields = normalizeDraftFields(reqVO.getFields(), previousFields);
        validateRelationReferences(model.getId(), fields);
        typeConfigValidator.validateOnSave(reqVO.getTypeConfig());
        typeConfigValidator.validateReferencesOnSave(reqVO.getTypeConfig(), fields);
        validateInputModels(model.getId(), reqVO.getTypeConfig(), false);
        reqVO.setId(reqVO.getModelId());
        // 外层真实事务中同时更新头、字段及口径，任何一步失败整体回滚。
        updateModel(reqVO);
        modelMapper.update(null, Wrappers.lambdaUpdate(LogicModelDO.class)
                .set(LogicModelDO::getTypeConfig, reqVO.getTypeConfig() == null ? null :
                        JsonUtils.toJsonString(reqVO.getTypeConfig()), JsonbStringTypeHandler.MAPPING)
                .eq(LogicModelDO::getId, model.getId()));
        columnService.replaceByModelId(model.getId(), fields);
    }

    /** 同一数据库读取快照内获得设计和修订标记。 */
    @Override
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ModelDesignRespVO getDesign(Long modelId) {
        LogicModelDO model = validateModelExists(modelId);
        ModelDesignRespVO result = new ModelDesignRespVO();
        result.setModelId(model.getId());
        result.setRevision(revision(model));
        result.setStatus(model.getStatus());
        result.setFields(columnService.listByModelId(modelId));
        result.setTypeConfig(typeConfigValidator.parse(model.getTypeConfig()));
        return result;
    }

    /** 外部校验不持有模型锁；重新入锁时检查完整候选设计与 CAS。 */
    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void publishModel(Long modelId, Long expectedRevision) {
        LogicModelDO model = validateModelExists(modelId);
        checkRevision(model, expectedRevision);
        validateNotDiscarded(model);
        if (!LogicModelDO.STATUS_DRAFT.equals(model.getStatus())) {
            throw exception(MODEL_DESIGN_REVISION_CONFLICT);
        }
        String candidateModel = designCandidate(model);
        String candidateRelations = JsonUtils.toJsonString(relationMapper.selectListByModelId(modelId));
        List<ModelFieldItem> currentFields = columnService.listByModelId(modelId);
        String candidateFields = JsonUtils.toJsonString(currentFields);
        hierarchyResolver.validateHierarchyOnPublish(model.getLayerId(), model.getSubjectDomainId(),
                model.getDataDomainId(), model.getTableType(), model.getBizCategoryId());
        List<ModelFieldItem> fields = normalizeFields(automaticSummaryFields(model, currentFields));
        validateRelationReferences(modelId, fields);
        if (fields.isEmpty()) {
            throw exception(LOGIC_MODEL_FIELDS_EMPTY);
        }
        typeConfigValidator.validateOnPublish(model.getTableType(),
                typeConfigValidator.parse(model.getTypeConfig()), fields);
        validateInputModels(modelId, typeConfigValidator.parse(model.getTypeConfig()), true);
        transactionTemplate.executeWithoutResult(transaction -> {
            LogicModelDO locked = lockModel(modelId, expectedRevision);
            if (!Objects.equals(candidateModel, designCandidate(locked))
                    || !Objects.equals(candidateFields, JsonUtils.toJsonString(columnService.listByModelId(modelId)))
                    || !Objects.equals(candidateRelations, JsonUtils.toJsonString(relationMapper.selectListByModelId(modelId)))) {
                throw exception(MODEL_PUBLISH_CONTENT_CHANGED);
            }
            hierarchyResolver.validateHierarchyOnPublish(locked.getLayerId(), locked.getSubjectDomainId(),
                    locked.getDataDomainId(), locked.getTableType(), locked.getBizCategoryId());
            validateCurrentDependencies(locked, fields);
            modelMapper.update(null, Wrappers.lambdaUpdate(LogicModelDO.class)
                    .set(LogicModelDO::getRevision, revision(locked) + 1)
                    .set(LogicModelDO::getStatus, LogicModelDO.STATUS_PUBLISHED)
                    .set(LogicModelDO::getPublishedBy,
                            cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId())
                    .set(LogicModelDO::getPublishedTime, java.time.LocalDateTime.now())
                    .eq(LogicModelDO::getId, modelId));
            columnService.replaceByModelId(modelId, fields);
        });
    }

    /** 发布和新部署共享同一当前依赖校验；历史任务恢复不走此路径。 */
    @Override
    public void validateCurrentDependencies(LogicModelDO model, List<ModelFieldItem> fields) {
        validateCurrentDependencies(model, fields, new java.util.HashSet<>());
    }

    private void validateCurrentDependencies(LogicModelDO model, List<ModelFieldItem> fields,
            java.util.Set<Long> path) {
        if (!path.add(model.getId())) {
            throw exception(MODEL_REFERENCE_INVALID, model.getId());
        }
        var config = typeConfigValidator.parse(model.getTypeConfig());
        validateInputModels(model.getId(), config, true);
        if (config != null && config.getSummary() != null && config.getSummary().getInputModelIds() != null) {
            for (Long inputId : config.getSummary().getInputModelIds()) {
                LogicModelDO input = validateModelExists(inputId);
                validateCurrentDependencies(input, columnService.listByModelId(inputId), path);
            }
        }
        if (!Objects.equals(JsonUtils.toJsonString(fields), JsonUtils.toJsonString(
                normalizeFields(automaticSummaryFields(model, fields))))) {
            throw exception(MODEL_PUBLISH_CONTENT_CHANGED);
        }
        path.remove(model.getId());
    }

    /**
     * 废弃逻辑模型
     *
     * @param modelId 模型编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void discardModel(Long modelId) {
        LogicModelDO model = modelMapper.selectByIdForUpdate(modelId);
        if (model == null) {
            throw exception(LOGIC_MODEL_NOT_EXISTS);
        }
        requireNoIndicatorReferences(modelId);
        modelMapper.update(null, Wrappers.lambdaUpdate(LogicModelDO.class)
                .set(LogicModelDO::getRevision, revision(model) + 1)
                .set(LogicModelDO::getStatus, LogicModelDO.STATUS_DISCARDED)
                .set(LogicModelDO::getPublishedBy, null).set(LogicModelDO::getPublishedTime, null)
                .eq(LogicModelDO::getId, modelId));
    }

    /**
     * 校验序号指标引用。
     *
     * @param modelId 模型编号
     */
    private void requireNoIndicatorReferences(Long modelId) {
        // 常规引用返回领域错误；并发新增引用由数据库共享引用守卫作最终保护。
        if (indicatorMapper.countSourceModelReferences(modelId) > 0) {
            throw exception(INDICATOR_SOURCE_REFERENCED);
        }
    }

    // ==================== ER 图 ====================

    /**
     * 获得主题 ER 图（主题及子主题下模型 + 当前字段 + 关系）
     *
     * @param layerId 分层编号
     * @return 逻辑ER 图响应数据
     */
    @Override
    public LogicErDiagramRespVO getErDiagram(Long layerId) {
        LogicErDiagramRespVO resp = new LogicErDiagramRespVO();
        resp.setLayerId(layerId);
        DwLayerDO selectedLayer = hierarchyResolver.findLayer(layerId);
        resp.setLayerName(selectedLayer == null ? null : selectedLayer.getLayerName());
        List<LogicModelDO> models = modelMapper.selectListByLayer(layerId);
        Map<Long, List<ModelFieldItem>> fieldsByModel = columnService.listByModelIds(
                convertSet(models, LogicModelDO::getId));
        resp.setModels(convertList(models, model -> {
            LogicErDiagramRespVO.Node node = BeanUtils.toBean(model, LogicErDiagramRespVO.Node.class);
            node.setFields(fieldsByModel.getOrDefault(model.getId(), List.of()));
            return node;
        }));
        // 两端模型均在当前分层筛选范围内的关系
        Map<Long, String> modelNames = convertMap(models, LogicModelDO::getId, LogicModelDO::getName);
        resp.setRelations(convertList(
                relationMapper.selectListByModelIds(convertSet(models, LogicModelDO::getId)),
                relation -> {
                    LogicRelationRespVO vo = convertRelation(relation);
                    vo.setSourceModelName(modelNames.get(relation.getSourceModelId()));
                    vo.setTargetModelName(modelNames.get(relation.getTargetModelId()));
                    return vo;
                }));
        return resp;
    }

    /**
     * 新增/修改逻辑模型关系（校验字段存在于两端模型当前设计）
     *
     * @param reqVO 请求参数
     * @return 生成或保存的记录编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveRelation(LogicRelationSaveReqVO reqVO) {
        LogicModelDO[] endpoints = lockRelationModels(reqVO.getSourceModelId(), reqVO.getTargetModelId());
        checkRevision(endpoints[0], reqVO.getSourceRevision());
        checkRevision(endpoints[1], reqVO.getTargetRevision());
        requireDraft(endpoints[0]);
        requireDraft(endpoints[1]);
        LogicRelationDO existing = reqVO.getId() == null ? null : validateRelationExists(reqVO.getId());
        if (existing != null && (!Objects.equals(existing.getSourceModelId(), reqVO.getSourceModelId())
                || !Objects.equals(existing.getTargetModelId(), reqVO.getTargetModelId())
                || !Objects.equals(existing.getRevision(), reqVO.getExpectedRevision()))) {
            throw exception(MODEL_RELATION_REVISION_CONFLICT);
        }
        List<ModelFieldItem> sourceFields = latestFields(endpoints[0].getId());
        List<ModelFieldItem> targetFields = latestFields(endpoints[1].getId());
        List<ModelRelationFieldPair> pairs = reqVO.getFieldPairs();
        if (pairs == null || pairs.isEmpty()) {
            ModelRelationFieldPair pair = new ModelRelationFieldPair();
            pair.setSourceFieldUid(findField(sourceFields, reqVO.getSourceField(), false).getFieldUid());
            pair.setTargetFieldUid(findField(targetFields, reqVO.getTargetField(), false).getFieldUid());
            pairs = List.of(pair);
        }
        java.util.Set<String> sourceKeys = new java.util.HashSet<>();
        java.util.Set<String> targetKeys = new java.util.HashSet<>();
        for (ModelRelationFieldPair pair : pairs) {
            findField(sourceFields, pair.getSourceFieldUid(), true);
            findField(targetFields, pair.getTargetFieldUid(), true);
            if (!sourceKeys.add(pair.getSourceFieldUid()) || !targetKeys.add(pair.getTargetFieldUid())) {
                throw exception(MODEL_FIELD_IDENTITY_INVALID);
            }
        }
        if (!java.util.Set.of(LogicRelationDO.TYPE_ONE_TO_ONE, LogicRelationDO.TYPE_ONE_TO_MANY,
                LogicRelationDO.TYPE_MANY_TO_MANY).contains(reqVO.getRelationType())) {
            throw exception(MODEL_FIELD_IDENTITY_INVALID);
        }
        String encoded = JsonUtils.toJsonString(pairs);
        for (LogicRelationDO relation : relationMapper.selectListByModelId(reqVO.getSourceModelId())) {
            if (!Objects.equals(relation.getId(), reqVO.getId())
                    && Objects.equals(relation.getSourceModelId(), reqVO.getSourceModelId())
                    && Objects.equals(relation.getTargetModelId(), reqVO.getTargetModelId())
                    && Objects.equals(parsePairs(relation.getFieldPairs()), pairs)) {
                throw exception(LOGIC_RELATION_DUPLICATE);
            }
        }
        LogicRelationDO relation = new LogicRelationDO();
        relation.setId(reqVO.getId());
        relation.setSourceModelId(reqVO.getSourceModelId());
        relation.setTargetModelId(reqVO.getTargetModelId());
        relation.setSourceField(findField(sourceFields, pairs.getFirst().getSourceFieldUid(), true).getFieldName());
        relation.setTargetField(findField(targetFields, pairs.getFirst().getTargetFieldUid(), true).getFieldName());
        relation.setFieldPairs(encoded);
        relation.setName(reqVO.getName());
        relation.setDescription(reqVO.getDescription());
        relation.setRelationType(reqVO.getRelationType());
        relation.setRevision(existing == null ? 0L : existing.getRevision() + 1);
        if (existing == null) {
            relationMapper.insert(relation);
        } else {
            relationMapper.updateById(relation);
        }
        advanceRevision(endpoints[0]);
        if (!Objects.equals(endpoints[0].getId(), endpoints[1].getId())) {
            advanceRevision(endpoints[1]);
        }
        return relation.getId();
    }

    /**
     * 删除逻辑模型关系
     *
     * @param id 记录编号
     * @param expectedRevision 预期修订号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRelation(Long id, Long expectedRevision) {
        LogicRelationDO initial = validateRelationExists(id);
        LogicModelDO[] endpoints = lockRelationModels(initial.getSourceModelId(), initial.getTargetModelId());
        requireDraft(endpoints[0]);
        requireDraft(endpoints[1]);
        LogicRelationDO current = validateRelationExists(id);
        if (expectedRevision == null || !Objects.equals(expectedRevision, current.getRevision())) {
            throw exception(MODEL_RELATION_REVISION_CONFLICT);
        }
        relationMapper.deleteById(id);
        advanceRevision(endpoints[0]);
        if (!Objects.equals(endpoints[0].getId(), endpoints[1].getId())) {
            advanceRevision(endpoints[1]);
        }
    }

    /**
     * 批量保存模型 ER 画布坐标
     *
     * @param reqVO 请求参数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveErLayout(LogicErLayoutSaveReqVO reqVO) {
        var positions = reqVO.getPositions().stream()
                .sorted(java.util.Comparator.comparing(LogicErLayoutSaveReqVO.Position::getModelId)).toList();
        java.util.Set<Long> unique = new java.util.HashSet<>();
        for (var position : positions) {
            if (!unique.add(position.getModelId())) {
                throw exception(MODEL_DESIGN_REVISION_CONFLICT);
            }
            LogicModelDO model = modelMapper.selectByIdForUpdate(position.getModelId());
            if (model == null) {
                throw exception(LOGIC_MODEL_NOT_EXISTS);
            }
            long layoutRevision = model.getLayoutRevision() == null ? 0 : model.getLayoutRevision();
            if (position.getExpectedLayoutRevision() == null || position.getExpectedLayoutRevision() != layoutRevision) {
                throw exception(MODEL_DESIGN_REVISION_CONFLICT);
            }
            modelMapper.update(null, Wrappers.lambdaUpdate(LogicModelDO.class)
                    .set(LogicModelDO::getPosX, position.getPosX())
                    .set(LogicModelDO::getPosY, position.getPosY())
                    .set(LogicModelDO::getLayoutRevision, layoutRevision + 1)
                    .eq(LogicModelDO::getId, position.getModelId()));
        }
    }

    // ==================== 影响分析 ====================

    /**
     * 影响分析：设计关系、部署目标
     *
     * @param id 记录编号
     * @return 逻辑影响响应数据
     */
    @Override
    public LogicImpactRespVO impactAnalysis(Long id) {
        LogicModelDO model = validateModelExists(id);
        LogicImpactRespVO resp = new LogicImpactRespVO();
        resp.setModelId(model.getId());
        resp.setModelName(model.getName());
        // 关联关系（源/目标均计入）
        List<LogicRelationDO> relations = relationMapper.selectListByModelId(id);
        java.util.Set<Long> otherModelIds = new java.util.HashSet<>();
        relations.forEach(relation -> {
            otherModelIds.add(relation.getSourceModelId());
            otherModelIds.add(relation.getTargetModelId());
        });
        otherModelIds.remove(id);
        Map<Long, String> otherNames = otherModelIds.isEmpty() ? Map.of()
                : convertMap(modelMapper.selectBatchIds(otherModelIds),
                LogicModelDO::getId, LogicModelDO::getName);
        resp.setRelations(convertList(relations, relation -> {
            LogicImpactRespVO.RelationImpact impact = new LogicImpactRespVO.RelationImpact();
            impact.setRelationId(relation.getId());
            boolean asSource = id.equals(relation.getSourceModelId());
            impact.setDirection(asSource ? "SOURCE" : "TARGET");
            Long otherId = asSource ? relation.getTargetModelId() : relation.getSourceModelId();
            impact.setOtherModelId(otherId);
            impact.setOtherModelName(id.equals(otherId) ? model.getName() : otherNames.get(otherId));
            impact.setSourceField(relation.getSourceField());
            impact.setTargetField(relation.getTargetField());
            impact.setRelationType(relation.getRelationType());
            return impact;
        }));
        // 生成的物理模型
        resp.setPhysicalModels(List.of());
        return resp;
    }

    // ==================== 内部方法 ====================

    /**
     * 获取逻辑模型的修订号。
     *
     * @param model 模型
     * @return 修订号数值
     */
    private long revision(LogicModelDO model) {
        return model.getRevision() == null ? 0 : model.getRevision();
    }

    /**
     * 锁定模型。
     *
     * @param id 记录编号
     * @param expected 预期值
     * @return 逻辑模型
     */
    private LogicModelDO lockModel(Long id, Long expected) {
        LogicModelDO model = modelMapper.selectByIdForUpdate(id);
        if (model == null) {
            throw exception(LOGIC_MODEL_NOT_EXISTS);
        }
        checkRevision(model, expected);
        return model;
    }

    /**
     * 检查修订号。
     *
     * @param model 模型
     * @param expected 预期值
     */
    private void checkRevision(LogicModelDO model, Long expected) {
        if (expected == null || expected.longValue() != revision(model)) {
            throw exception(MODEL_DESIGN_REVISION_CONFLICT);
        }
    }

    /**
     * 推进逻辑模型的修订号。
     *
     * @param model 模型
     */
    private void advanceRevision(LogicModelDO model) {
        modelMapper.update(null, Wrappers.lambdaUpdate(LogicModelDO.class)
                .set(LogicModelDO::getRevision, revision(model) + 1)
                .set(LogicModelDO::getStatus, LogicModelDO.STATUS_DRAFT)
                .set(LogicModelDO::getPublishedBy, null).set(LogicModelDO::getPublishedTime, null)
                .eq(LogicModelDO::getId, model.getId()));
    }

    /**
     * 校验草稿。
     *
     * @param model 模型
     */
    private void requireDraft(LogicModelDO model) {
        validateNotDiscarded(model);
    }

    /**
     * 规范化草稿字段。
     *
     * @param fields 字段定义
     * @param previous 先前
     * @return 规范化草稿字段结果集合
     */
    private List<ModelFieldItem> normalizeDraftFields(List<ModelFieldItem> fields, List<ModelFieldItem> previous) {
        if (fields != null) {
            for (ModelFieldItem field : fields) {
                if (field == null) {
                    throw exception(MODEL_FIELD_IDENTITY_INVALID);
                }
                previous.stream().filter(old -> Objects.equals(old.getFieldName(), field.getFieldName()))
                        .findFirst().ifPresent(old -> {
                            if (StrUtil.isNotBlank(old.getFieldUid())) {
                                if (StrUtil.isBlank(field.getFieldUid())) {
                                    field.setFieldUid(old.getFieldUid());
                                } else if (!Objects.equals(old.getFieldUid(), field.getFieldUid())) {
                                    throw exception(MODEL_FIELD_IDENTITY_INVALID);
                                }
                            }
                        });
            }
        }
        return normalizeFields(fields);
    }

    /**
     * 规范化逻辑模型的字段。
     *
     * @param fields 字段定义
     * @return 规范化逻辑模型的字段结果集合
     */
    private List<ModelFieldItem> normalizeFields(List<ModelFieldItem> fields) {
        List<ModelFieldItem> result = fields == null ? List.of() : fields;
        java.util.Set<String> names = new java.util.HashSet<>();
        java.util.Set<String> identities = new java.util.HashSet<>();
        for (ModelFieldItem field : result) {
            if (field == null || StrUtil.isBlank(field.getFieldName()) || StrUtil.isBlank(field.getDataType())
                    || field.getFieldName().length() > 255 || StrUtil.length(field.getCnName()) > 255
                    || !names.add(field.getFieldName().toLowerCase(java.util.Locale.ROOT))) {
                throw exception(MODEL_FIELD_IDENTITY_INVALID);
            }
        }
        ModelFieldUidUtils.ensureFieldUids(result);
        for (ModelFieldItem field : result) {
            if (field.getFieldUid().length() > 128 || !identities.add(field.getFieldUid())) {
                throw exception(MODEL_FIELD_IDENTITY_INVALID);
            }
        }
        return result;
    }

    /** 独立字段保存与发布也复用服务端生成路径，不能绕过汇总草稿表单防伪。 */
    private List<ModelFieldItem> automaticSummaryFields(LogicModelDO model, List<ModelFieldItem> fields) {
        if ("SUMMARY".equals(model.getTableType()) && (model.getGrainEntityIds() != null
                || StrUtil.isNotBlank(model.getDataTimeliness()))) {
            List<ModelFieldItem> generated = definitionService.summaryFields(model.getDataDomainId(),
                    model.getSubjectDomainId(),
                    model.getDataTimeliness(), model.getGrainEntityIds());
            carryGeneratedFieldIdentities(generated, fields);
            return generated;
        }
        return fields;
    }

    /** 服务端重算定义时继续使用存量同名字段身份，避免修订已有逻辑表时破坏引用。 */
    private void carryGeneratedFieldIdentities(List<ModelFieldItem> generated, List<ModelFieldItem> previous) {
        if (generated == null || previous == null) {
            return;
        }
        for (ModelFieldItem field : generated) {
            previous.stream().filter(old -> Objects.equals(old.getFieldName(), field.getFieldName()))
                    .filter(old -> StrUtil.isNotBlank(old.getFieldUid())).findFirst()
                    .ifPresent(old -> field.setFieldUid(old.getFieldUid()));
        }
    }

    /**
     * 解析键值对。
     *
     * @param json JSON 内容
     * @return 解析键值对结果集合
     */
    private List<ModelRelationFieldPair> parsePairs(String json) {
        return StrUtil.isBlank(json) ? List.of() : JsonUtils.parseObject(json,
                new TypeReference<List<ModelRelationFieldPair>>() {
                });
    }

    /** 输入模型引用需通过对象校验；冻结汇总口径时必须已有可用的已发布输入。 */
    private void validateInputModels(Long ownerId,
            cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelTypeConfigVO config,
            boolean publishing) {
        if (config == null || config.getSummary() == null || config.getSummary().getInputModelIds() == null) {
            return;
        }
        java.util.Set<Long> unique = new java.util.HashSet<>();
        for (Long id : config.getSummary().getInputModelIds()) {
            LogicModelDO input = id == null ? null : modelMapper.selectById(id);
            if (input == null || Objects.equals(ownerId, id) || !unique.add(id)
                    || LogicModelDO.STATUS_DISCARDED.equals(input.getStatus())
                    || (publishing && (!LogicModelDO.STATUS_PUBLISHED.equals(input.getStatus())
                    || !Integer.valueOf(1).equals(input.getEnabled())))) {
                throw exception(MODEL_REFERENCE_INVALID, id);
            }
        }
    }

    /** 当前字段表是唯一字段真相源。 */
    private List<ModelFieldItem> latestFields(Long modelId) {
        return columnService.listByModelId(modelId);
    }

    /**
     * 查找逻辑模型的字段。
     *
     * @param fields 字段定义
     * @param key 标识键
     * @param uid 用户标识
     * @return 模型字段项
     */
    private ModelFieldItem findField(List<ModelFieldItem> fields, String key, boolean uid) {
        return fields.stream().filter(f -> Objects.equals(uid ? f.getFieldUid() : f.getFieldName(), key))
                .findFirst().filter(f -> StrUtil.isNotBlank(f.getFieldUid()))
                .orElseThrow(() -> exception(LOGIC_RELATION_FIELD_NOT_EXISTS, key));
    }

    /**
     * 校验关系引用。
     *
     * @param modelId 模型编号
     * @param fields 字段定义
     */
    private void validateRelationReferences(Long modelId, List<ModelFieldItem> fields) {
        var uids = convertSet(fields, ModelFieldItem::getFieldUid);
        var names = convertSet(fields, ModelFieldItem::getFieldName);
        for (LogicRelationDO relation : relationMapper.selectListByModelId(modelId)) {
            boolean source = Objects.equals(modelId, relation.getSourceModelId());
            boolean target = Objects.equals(modelId, relation.getTargetModelId());
            var pairs = parsePairs(relation.getFieldPairs());
            if (pairs.isEmpty()) {
                if ((source && !names.contains(relation.getSourceField()))
                        || (target && !names.contains(relation.getTargetField()))) {
                    throw exception(MODEL_FIELD_REFERENCED, relation.getName());
                }
            } else {
                for (var pair : pairs) {
                    if ((source && !uids.contains(pair.getSourceFieldUid()))
                            || (target && !uids.contains(pair.getTargetFieldUid()))) {
                        throw exception(MODEL_FIELD_REFERENCED, relation.getName());
                    }
                }
            }
        }
    }

    /**
     * 锁定关系模型。
     *
     * @param source 来源数据
     * @param target 目标数据
     * @return 逻辑模型
     */
    private LogicModelDO[] lockRelationModels(Long source, Long target) {
        Long first = source.compareTo(target) <= 0 ? source : target;
        Long second = first.equals(source) ? target : source;
        LogicModelDO a = modelMapper.selectByIdForUpdate(first);
        LogicModelDO b = first.equals(second) ? a : modelMapper.selectByIdForUpdate(second);
        if (a == null || b == null) {
            throw exception(LOGIC_MODEL_NOT_EXISTS);
        }
        return first.equals(source) ? new LogicModelDO[]{a, b} : new LogicModelDO[]{b, a};
    }

    /** 空白当成未填，避免把空串写入标准类型、电话和备注。 */
    private static String blankToNull(String value) {
        return StrUtil.isBlank(value) ? null : value;
    }

    /** 未传或非法值按大写处理，避免草稿保存把大小写写成空。 */
    private static Integer tableCaseOf(Integer tableCase) {
        return Integer.valueOf(2).equals(tableCase) ? 2 : 1;
    }

    /** 未传或非法值按禁用处理，与建表单默认选中一致。 */
    private static Integer enabledOf(Integer enabled) {
        return Integer.valueOf(1).equals(enabled) ? 1 : 0;
    }

    /** 画布布局单独并发控制，不让拖动坐标使正在校验的发布失效。 */
    private String designCandidate(LogicModelDO model) {
        if (model == null) {
            return null;
        }
        Map<String, Object> values = JsonUtils.parseObject(JsonUtils.toJsonString(model),
                new TypeReference<Map<String, Object>>() {
                });
        for (String key : List.of("posX", "posY", "layoutRevision", "updateTime", "updater")) {
            values.remove(key);
        }
        return JsonUtils.toJsonString(values);
    }

    /**
     * 转换关系。
     *
     * @param relation 关系
     * @return 逻辑关系响应数据
     */
    private LogicRelationRespVO convertRelation(LogicRelationDO relation) {
        LogicRelationRespVO vo = new LogicRelationRespVO();
        BeanUtil.copyProperties(relation, vo, "fieldPairs");
        vo.setFieldPairs(parsePairs(relation.getFieldPairs()));
        if (!vo.getFieldPairs().isEmpty()) {
            var first = vo.getFieldPairs().getFirst();
            latestFields(relation.getSourceModelId()).stream()
                    .filter(f -> Objects.equals(f.getFieldUid(), first.getSourceFieldUid()))
                    .findFirst().ifPresent(f -> vo.setSourceField(f.getFieldName()));
            latestFields(relation.getTargetModelId()).stream()
                    .filter(f -> Objects.equals(f.getFieldUid(), first.getTargetFieldUid()))
                    .findFirst().ifPresent(f -> vo.setTargetField(f.getFieldName()));
        }
        return vo;
    }


    /**
     * 校验字段存在于模型当前字段中
     */
    private void validateRelationField(Long modelId, String fieldName) {
        List<ModelFieldItem> fields = latestFields(modelId);
        boolean exists = fields.stream()
                .anyMatch(field -> Objects.equals(field.getFieldName(), fieldName));
        if (!exists) {
            throw exception(LOGIC_RELATION_FIELD_NOT_EXISTS, fieldName);
        }
    }

    /**
     * 校验关系存在性。
     *
     * @param id 记录编号
     * @return 逻辑关系
     */
    private LogicRelationDO validateRelationExists(Long id) {
        LogicRelationDO relation = relationMapper.selectById(id);
        if (relation == null) {
            throw exception(LOGIC_RELATION_NOT_EXISTS);
        }
        return relation;
    }

    /**
     * 校验模型存在性。
     *
     * @param id 记录编号
     * @return 逻辑模型
     */
    private LogicModelDO validateModelExists(Long id) {
        LogicModelDO model = modelMapper.selectById(id);
        if (model == null) {
            throw exception(LOGIC_MODEL_NOT_EXISTS);
        }
        return model;
    }

    /**
     * 校验未已废弃。
     *
     * @param model 模型
     */
    private void validateNotDiscarded(LogicModelDO model) {
        if (LogicModelDO.STATUS_DISCARDED.equals(model.getStatus())) {
            throw exception(LOGIC_MODEL_DISCARDED);
        }
    }

    /**
     * 获取逻辑模型的重复定义。
     *
     * @param failure 失败原因
     * @return 运行时异常
     */
    private RuntimeException duplicateDefinition(org.springframework.dao.DuplicateKeyException failure) {
        if (Objects.toString(failure.getMostSpecificCause().getMessage(), "")
                .contains("uq_modeling_logic_entity_time_live")) {
            return exception(MODEL_TYPE_CONFIG_INVALID_VALUE, "当前业务实体与统计时效已创建逻辑表");
        }
        return exception(LOGIC_MODEL_NAME_DUPLICATE);
    }

    /**
     * 校验名称唯一性。
     *
     * @param request 请求参数
     */
    private void validateNameUnique(LogicModelCreateReqVO request) {
        // 五类逻辑表按完整表名唯一；名称与去前缀编码允许在不同分层中复用。
        if (java.util.Set.of("SOURCE", "DIMENSION", "DETAIL", "SUMMARY", "APPLICATION")
                .contains(Objects.toString(request.getTableType(), ""))) {
            Long count = modelMapper.selectCount(
                    new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<LogicModelDO>()
                            .apply("lower(table_name_notation) = lower({0})", request.getTableNameNotation())
                            .ne(request.getId() != null, LogicModelDO::getId, request.getId()));
            if (count > 0) {
                throw exception(LOGIC_MODEL_NAME_DUPLICATE);
            }
            return;
        }
        LogicModelDO exists = modelMapper.selectByNameOrCode(request.getName(), request.getCode());
        if (exists != null && !exists.getId().equals(request.getId())) {
            throw exception(LOGIC_MODEL_NAME_DUPLICATE);
        }
    }

}
