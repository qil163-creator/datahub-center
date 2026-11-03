package cn.iocoder.yudao.module.governance.service.standardelement;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementEnumCheckRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementImpactRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementImportExcelVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementImportRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementMappedColumnRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementModelFieldRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementSaveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementStatisticsRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorydatameta.CategoryDatametaDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardMappingDO;
import cn.iocoder.yudao.module.governance.dal.mysql.categorydatameta.CategoryDatametaMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.doc.StandardDocMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTableMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeSetMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardMappingMapper;
import cn.iocoder.yudao.module.governance.enums.standard.StandardElementLifecycleEnum;
import cn.iocoder.yudao.module.governance.service.standardmapping.StandardMappingTargetSnapshot;
import cn.iocoder.yudao.module.governance.service.quality.StandardQualityRuleMaterializer;
import cn.iocoder.yudao.module.governance.service.standardconstraint.StandardConstraintCompilationService;
import cn.iocoder.yudao.module.governance.service.standardconstraint.StandardConstraintCompiler;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeitem.StandardCodeItemMapper;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeitem.StandardCodeItemDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeset.StandardCodeSetDO;
import cn.iocoder.yudao.module.modeling.api.model.ModelFieldApi;
import cn.iocoder.yudao.module.modeling.api.model.dto.ModelStandardFieldRespDTO;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import cn.iocoder.yudao.module.governance.service.standardelement.StandardCatalogService;
import jakarta.annotation.Resource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertMap;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertSet;

import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementDO;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;

import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardElementMapper;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/**
 * 数据标准--标准数据元 Service 实现类
 *
 * @author Li
 */
@Service
@Validated
@Slf4j
public class StandardElementServiceImpl implements StandardElementService {

    @Resource
    private StandardCatalogService catalogService;

    @Resource
    private cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardElementRuleMapper elementRuleMapper;

    /**
     * 数据元编码前缀，编码规则：DE + 6 位主键，例如 DE000001
     */
    private static final String CODE_PREFIX = "DE";

    private static final int CODE_SEQ_WIDTH = 6;

    @Resource
    private StandardElementMapper standardElementMapper;

    @Resource
    private CategoryDatametaMapper categoryDatametaMapper;

    @Resource
    private StandardCodeSetMapper standardCodeSetMapper;

    @Resource
    private StandardDocMapper standardDocMapper;

    @Resource
    private MetaColumnMapper metaColumnMapper;

    @Resource
    private MetaTableMapper metaTableMapper;

    @Resource
    private StandardMappingMapper mappingMapper;

    @Resource
    private StandardCodeItemMapper standardCodeItemMapper;

    @Resource
    private ModelFieldApi modelFieldApi;

    @Resource
    private StandardConstraintCompilationService standardConstraintCompilationService;

    @Resource
    private StandardQualityRuleMaterializer standardQualityRuleMaterializer;

    @Resource
    private PlatformTransactionManager transactionManager;

    /**
     * 创建数据标准--标准数据元
     *
     *  @param createReqVO 创建信息
     *  @return 编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer createStandardElement(StandardElementSaveReqVO createReqVO) {
        createReqVO.setContactNumber(catalogService.ownerContact(createReqVO.getPersonCharge()));
        catalogService.validate(createReqVO.getCategoryId(), createReqVO.getDocumentId(), createReqVO.getStandardType(),
                createReqVO.getDataLength(), createReqVO.getDataScale());
        createReqVO.setRevision(0L);
        // 校验唯一性与关联对象
        validateNameUnique(null, createReqVO.getName());
        validateEngNameUnique(null, createReqVO.getEngName());
        validateCategoryExists(createReqVO.getCategoryId());
        validateCodeSetExists(createReqVO.getCodeSetId());
        validateAndLockDoc(createReqVO.getDocumentId(), createReqVO.getStandardType());

        // 插入，数据元编码由后端按主键生成，忽略前端传入值；新建一律为草稿态
        StandardElementDO standardElement = BeanUtils.toBean(createReqVO, StandardElementDO.class);
        standardElement.setCode(null);
        standardElement.setLifecycleStatus(StandardElementLifecycleEnum.DRAFT.getStatus());
        standardElementMapper.insert(standardElement);
        standardElementMapper.updateById(StandardElementDO.builder()
                .id(standardElement.getId())
                .code(generateCode(standardElement.getId()))
                .build());
        return standardElement.getId();
    }

    /**
     * 更新数据标准--标准数据元
     *
     *  @param updateReqVO 更新信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStandardElement(StandardElementSaveReqVO updateReqVO) {
        updateReqVO.setContactNumber(catalogService.ownerContact(updateReqVO.getPersonCharge()));
        catalogService.validate(updateReqVO.getCategoryId(), updateReqVO.getDocumentId(), updateReqVO.getStandardType(),
                updateReqVO.getDataLength(), updateReqVO.getDataScale());
        StandardElementDO dbElement = lockStandardElement(updateReqVO.getId());
        StandardCatalogService.checkRevision(updateReqVO.getRevision(), dbElement.getRevision());
        updateReqVO.setRevision((dbElement.getRevision() == null ? 0L : dbElement.getRevision()) + 1);
        validateEditable(dbElement);
        // 与码表绑定路径先串行化同一数据元，再按触发器顺序锁定类目和标准文件。
        validateCategoryExists(updateReqVO.getCategoryId());
        validateAndLockDoc(updateReqVO.getDocumentId(), updateReqVO.getStandardType());
        boolean nameChanged = !Objects.equals(dbElement.getName(), updateReqVO.getName());
        boolean engNameChanged = !Objects.equals(dbElement.getEngName(), updateReqVO.getEngName());
        if (nameChanged) {
            validateNameUnique(updateReqVO.getId(), updateReqVO.getName());
        }
        if (engNameChanged) {
            validateEngNameUnique(updateReqVO.getId(), updateReqVO.getEngName());
        }
        validateCodeSetExists(updateReqVO.getCodeSetId());

        StandardElementDO updateObj = BeanUtils.toBean(updateReqVO, StandardElementDO.class);
        // 未改名时保留数据库原值，避免绑定/序列化层的隐式转换污染历史数据。
        if (!nameChanged) {
            updateObj.setName(dbElement.getName());
        }
        if (!engNameChanged) {
            updateObj.setEngName(dbElement.getEngName());
        }
        // 可编辑态允许修改英文名称；编码与生命周期仍只能由专用流程流转。
        updateObj.setCode(dbElement.getCode());
        updateObj.setLifecycleStatus(dbElement.getLifecycleStatus());
        standardElementMapper.updateById(updateObj);
    }

    /**
     * 删除数据标准--标准数据元
     *
     *  @param id 编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteStandardElement(Integer id) {
        // 校验存在，且已发布的标准必须先废止才能删除
        StandardElementDO element = lockStandardElement(id);
        validateNotPublished(element);
        // 校验未被元数据字段落标引用
        validateNotReferenced(List.of(id));
        // 删除
        elementRuleMapper.delete(
                new LambdaQueryWrapperX<cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementRuleDO>().eq(
                        cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementRuleDO::getElementId,
                        id));
        standardElementMapper.deleteById(id);
    }

    /**
     * 批量删除数据标准--标准数据元
     *
     *  @param ids 编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteStandardElementListByIds(List<Integer> ids) {
        if (CollUtil.isEmpty(ids)) {
            return;
        }
        // 已发布的标准必须先废止才能删除
        ids.stream().sorted().map(this::lockStandardElement).forEach(this::validateNotPublished);
        // 校验未被元数据字段落标引用
        validateNotReferenced(ids);
        // 删除
        elementRuleMapper.delete(
                new LambdaQueryWrapperX<cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementRuleDO>().in(
                        cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementRuleDO::getElementId,
                        ids));
        standardElementMapper.deleteByIds(ids);
    }

    /**
     * 绑定标准码表
     *
     *  @param id 数据元编号
     *  @param codeSetId 标准码表编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindCodeSet(Integer id, Integer codeSetId) {
        StandardElementDO element = lockStandardElement(id);
        validateEditable(element);
        validateCodeSetExists(codeSetId);
        standardElementMapper.updateById(StandardElementDO.builder()
                .id(id)
                .codeSetId(codeSetId)
                .build());
    }

    /**
     * 发布标准数据元（草稿 → 已发布）
     *
     *  @param id 数据元编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publishStandardElement(Integer id) {
        StandardElementDO element = lockStandardElement(id);
        if (!StandardElementLifecycleEnum.DRAFT.getStatus().equals(element.getLifecycleStatus())) {
            throw exception(STANDARD_ELEMENT_LIFECYCLE_TRANSITION_INVALID);
        }
        StandardConstraintCompiler.Compilation compilation =
                standardConstraintCompilationService.requireCompilable(element);
        element.setLifecycleStatus(StandardElementLifecycleEnum.PUBLISHED.getStatus());
        standardElementMapper.updateById(StandardElementDO.builder()
                .id(id)
                .lifecycleStatus(StandardElementLifecycleEnum.PUBLISHED.getStatus())
                .build());
        standardQualityRuleMaterializer.materialize(element, compilation);
    }

    /**
     * 标准数据元影响分析：直接落标表
     *
     *  @param id 数据元编号
     *  @return 影响分析结果
     */
    @Override
    public StandardElementImpactRespVO getImpactAnalysis(Integer id) {
        validateStandardElementExists(id);
        StandardElementImpactRespVO resp = new StandardElementImpactRespVO();
        List<MetaColumnDO> columns = metaColumnMapper.selectList(
                MetaColumnDO::getStandardElementId, id.longValue());
        resp.setMappedColumnCount(columns.size());
        resp.setDirectTables(new ArrayList<>());
        if (CollUtil.isEmpty(columns)) {
            return resp;
        }
        // 直接落标表 + 每表落标字段数
        Map<Long, Long> columnCountByTable = new LinkedHashMap<>();
        for (MetaColumnDO column : columns) {
            if (column.getTableId() != null) {
                columnCountByTable.merge(column.getTableId(), 1L, Long::sum);
            }
        }
        Map<Long, MetaTableDO> tableMap = convertMap(
                metaTableMapper.selectByIds(columnCountByTable.keySet()), MetaTableDO::getId);
        for (Map.Entry<Long, Long> entry : columnCountByTable.entrySet()) {
            MetaTableDO table = tableMap.get(entry.getKey());
            if (table == null) {
                continue;
            }
            StandardElementImpactRespVO.ImpactTable impactTable = new StandardElementImpactRespVO.ImpactTable();
            impactTable.setTableId(table.getId());
            impactTable.setTableName(table.getTableName());
            impactTable.setTableBusinessName(table.getBusinessName());
            impactTable.setMappedColumnCount(entry.getValue().intValue());
            resp.getDirectTables().add(impactTable);
        }
        return resp;
    }

    /**
     * 获得标准数据元的设计态落标（建模模块中绑定该标准的模型字段），
     *  并同步 MODEL_FIELD 类型的落标映射事实（按 fieldUid 对账）。
     *
     *  @param id 数据元编号
     *  @return 绑定该标准的模型字段列表
     */
    @Override
    public List<StandardElementModelFieldRespVO> getModelFields(Integer id) {
        validateStandardElementExists(id);
        List<ModelStandardFieldRespDTO> modelFields =
                modelFieldApi.getFieldListByStandardId(id.longValue()).getCheckedData();
        if (CollUtil.isEmpty(modelFields)) {
            return List.of();
        }

        Map<String, StandardMappingDO> activeByUrn = new HashMap<>();
        for (StandardMappingDO mapping : mappingMapper.selectListByElementId(id)) {
            if (StandardMappingDO.TARGET_TYPE_MODEL_FIELD.equals(mapping.getTargetType())
                    && (StandardMappingDO.STATUS_CONFIRMED.equals(mapping.getMappingStatus())
                    || StandardMappingDO.STATUS_PROPOSED.equals(mapping.getMappingStatus()))) {
                activeByUrn.putIfAbsent(mapping.getTargetUrn(), mapping);
            }
        }
        List<StandardElementModelFieldRespVO> result = new ArrayList<>(modelFields.size());
        for (ModelStandardFieldRespDTO field : modelFields) {
            StandardElementModelFieldRespVO vo = BeanUtils.toBean(field,
                    StandardElementModelFieldRespVO.class);
            if (StrUtil.isNotBlank(field.getFieldUid())) {
                String urn = String.format("urn:datahub:model-field:%d:%s", field.getModelId(), field.getFieldUid());
                StandardMappingDO mapping = activeByUrn.get(urn);
                if (mapping != null) {
                    vo.setMappingStatus(mapping.getMappingStatus());
                }
            }
            result.add(vo);
        }
        return result;
    }

    /**
     * 全局同步建模字段的 MODEL_FIELD 落标事实：
     *  补齐缺失事实，字段删除或改绑其它标准的活动事实置为 STALE。
     *
     *  @return 变更的事实数量
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int syncModelFieldMappings() {
        List<ModelStandardFieldRespDTO> fields;
        try {
            fields = modelFieldApi.getStandardBoundFieldList().getCheckedData();
        } catch (Exception ex) {
            log.warn("[syncModelFieldMappings][查询建模字段失败：{}]", ex.getMessage());
            return 0;
        }

        // 存量绑定：urn -> 字段（fieldUid 缺失的旧快照不建事实）
        Map<String, ModelStandardFieldRespDTO> liveByUrn = new HashMap<>();
        for (ModelStandardFieldRespDTO field : fields) {
            if (StrUtil.isBlank(field.getFieldUid()) || field.getStandardId() == null) {
                continue;
            }
            liveByUrn.put(String.format("urn:datahub:model-field:%d:%s", field.getModelId(), field.getFieldUid()),
                    field);
        }
        int changed = 0;
        Set<String> matchedUrns = new HashSet<>();
        // 1. 活动事实对账：字段已删除或改绑其它标准的置为 STALE
        for (StandardMappingDO mapping : mappingMapper.selectActiveListByTargetType(
                StandardMappingDO.TARGET_TYPE_MODEL_FIELD)) {
            ModelStandardFieldRespDTO live = liveByUrn.get(mapping.getTargetUrn());
            if (live != null && Objects.equals(live.getStandardId().intValue(), mapping.getElementId())) {
                matchedUrns.add(mapping.getTargetUrn());
                continue;
            }
            mappingMapper.updateById(StandardMappingDO.builder()
                    .id(mapping.getId())
                    .mappingStatus(StandardMappingDO.STATUS_STALE)
                    .build());
            changed++;
        }
        // 2. 补齐缺失事实
        for (Map.Entry<String, ModelStandardFieldRespDTO> entry : liveByUrn.entrySet()) {
            if (matchedUrns.contains(entry.getKey())) {
                continue;
            }
            ModelStandardFieldRespDTO field = entry.getValue();
            mappingMapper.insert(StandardMappingDO.builder()
                    .elementId(field.getStandardId().intValue())
                    .targetType(StandardMappingDO.TARGET_TYPE_MODEL_FIELD)
                    .targetUrn(entry.getKey())
                    .targetNameSnapshot(field.getTableName() + "." + field.getFieldName())
                    .mappingStatus(StandardMappingDO.STATUS_CONFIRMED)
                    .sourceType(StandardMappingDO.SOURCE_LEGACY_REFERENCE)
                    .confirmedBy("system")
                    .confirmedTime(LocalDateTime.now())
                    .build());
            changed++;
        }
        return changed;
    }

    /**
     * 为绑定码表的数据元生成落标字段的码值内容稽核 SQL
     *  （统计非法码值行数，可在质量模块或数据源直接执行）
     *
     *  @param id 数据元编号
     *  @return 每个落标字段一条稽核 SQL
     */
    @Override
    public List<StandardElementEnumCheckRespVO> getEnumCheckSqls(Integer id) {
        StandardElementDO element = validateStandardElementExists(id);
        if (element.getCodeSetId() == null) {
            return List.of();
        }
        StandardCodeSetDO codeSet = standardCodeSetMapper.selectById(element.getCodeSetId());
        if (codeSet == null) {
            return List.of();
        }
        List<StandardCodeItemDO> items = standardCodeItemMapper.selectListByDictType(codeSet.getType());
        if (CollUtil.isEmpty(items)) {
            return List.of();
        }
        List<String> allowedValues = items.stream().map(StandardCodeItemDO::getValue).toList();
        String inClause = String.join(", ", allowedValues.stream()
                .map(value -> "'" + value.replace("'", "''") + "'").toList());
        List<MetaColumnDO> columns = metaColumnMapper.selectList(
                MetaColumnDO::getStandardElementId, id.longValue());
        if (CollUtil.isEmpty(columns)) {
            return List.of();
        }
        Set<Long> tableIds = convertSet(columns, MetaColumnDO::getTableId);
        Map<Long, MetaTableDO> tableMap = convertMap(
                metaTableMapper.selectByIds(tableIds), MetaTableDO::getId);
        List<StandardElementEnumCheckRespVO> result = new ArrayList<>();
        for (MetaColumnDO column : columns) {
            MetaTableDO table = column.getTableId() == null ? null : tableMap.get(column.getTableId());
            if (table == null || StrUtil.isBlank(table.getTableName())
                    || StrUtil.isBlank(column.getColumnName())) {
                continue;
            }
            StandardElementEnumCheckRespVO vo = new StandardElementEnumCheckRespVO();
            vo.setColumnId(column.getId());
            vo.setTableName(table.getTableName());
            vo.setColumnName(column.getColumnName());
            vo.setCodeSetName(codeSet.getName());
            vo.setAllowedValues(allowedValues);
            vo.setCheckSql(String.format(
                    "SELECT COUNT(*) AS invalid_count FROM %s WHERE %s IS NOT NULL AND %s NOT IN (%s)",
                    table.getTableName(), column.getColumnName(), column.getColumnName(), inClause));
            result.add(vo);
        }
        return result;
    }

    /**
     * 废止标准数据元（已发布 → 已废止）
     *
     *  @param id 数据元编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deprecateStandardElement(Integer id) {
        StandardElementDO element = lockStandardElement(id);
        if (!StandardElementLifecycleEnum.PUBLISHED.getStatus().equals(element.getLifecycleStatus())) {
            throw exception(STANDARD_ELEMENT_LIFECYCLE_TRANSITION_INVALID);
        }
        standardElementMapper.updateById(StandardElementDO.builder()
                .id(id)
                .lifecycleStatus(StandardElementLifecycleEnum.DEPRECATED.getStatus())
                .build());
        // 标准退役联动：活动落标映射全部失效，并解除元数据字段的标准引用
        List<StandardMappingDO> mappings = mappingMapper.selectListByElementId(id);
        for (StandardMappingDO mapping : mappings) {
            if (StandardMappingDO.STATUS_CONFIRMED.equals(mapping.getMappingStatus())
                    || StandardMappingDO.STATUS_PROPOSED.equals(mapping.getMappingStatus())) {
                mappingMapper.updateById(StandardMappingDO.builder()
                        .id(mapping.getId())
                        .mappingStatus(StandardMappingDO.STATUS_STALE)
                        .deviationReason("标准已废止，映射自动失效")
                        .build());
            }
        }
        metaColumnMapper.update(null, new LambdaUpdateWrapper<MetaColumnDO>()
                .eq(MetaColumnDO::getStandardElementId, id.longValue())
                .set(MetaColumnDO::getStandardElementId, null));
        standardQualityRuleMaterializer.disableByElement(id);
    }

    /**
     * 获得标准数据元的落标字段列表（被哪些元数据字段引用）
     *
     *  @param id 数据元编号
     *  @return 落标字段列表
     */
    @Override
    public List<StandardElementMappedColumnRespVO> getMappedColumns(Integer id) {
        StandardElementDO element = validateStandardElementExists(id);
        List<MetaColumnDO> columns = metaColumnMapper.selectList(
                MetaColumnDO::getStandardElementId, id.longValue());
        Map<Long, StandardMappingDO> mappingMap = readMappings(element);
        if (CollUtil.isEmpty(columns)) {
            return List.of();
        }
        // 批量加载所属表信息，避免逐条查询
        Set<Long> tableIds = convertSet(columns, MetaColumnDO::getTableId);
        Map<Long, MetaTableDO> tableMap = convertMap(
                metaTableMapper.selectByIds(tableIds), MetaTableDO::getId);
        List<StandardElementMappedColumnRespVO> result = new ArrayList<>(columns.size());
        for (MetaColumnDO column : columns) {
            StandardElementMappedColumnRespVO vo = new StandardElementMappedColumnRespVO();
            vo.setColumnId(column.getId());
            vo.setColumnName(column.getColumnName());
            vo.setBusinessName(column.getBusinessName());
            vo.setDataType(column.getDataType());
            vo.setDataLength(column.getDataLength());
            vo.setNullableFlag(column.getNullableFlag());
            vo.setTableId(column.getTableId());
            MetaTableDO table = column.getTableId() == null ? null : tableMap.get(column.getTableId());
            if (table != null) {
                vo.setTableName(table.getTableName());
                vo.setTableBusinessName(table.getBusinessName());
                vo.setLayer(table.getLayer());
            }
            StandardMappingDO mapping = mappingMap.get(column.getId());
            if (mapping != null) {
                vo.setMappingId(mapping.getId());
                vo.setMappingStatus(mapping.getMappingStatus());
            }
            StandardComplianceChecker.ComplianceResult compliance =
                    StandardComplianceChecker.check(element, column);
            vo.setComplianceResult(compliance.result());
            vo.setComplianceIssues(compliance.issues());
            result.add(vo);
        }
        return result;
    }

    /** 查询只读：不再在 GET 中补写或失效落标事实。 */
    private Map<Long, StandardMappingDO> readMappings(StandardElementDO element) {
        List<StandardMappingDO> mappings = mappingMapper.selectListByElementId(element.getId());
        Map<Long, StandardMappingDO> activeByColumnId = new HashMap<>();
        for (StandardMappingDO mapping : mappings) {
            if (StandardMappingDO.STATUS_CONFIRMED.equals(mapping.getMappingStatus())
                    || StandardMappingDO.STATUS_PROPOSED.equals(mapping.getMappingStatus())) {
                activeByColumnId.putIfAbsent(mapping.getColumnId(), mapping);
            }
        }
        return activeByColumnId;
    }

    /**
     * 撤销落标映射（同时解除元数据字段与该标准的绑定）
     *
     *  @param mappingId 落标映射编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeMapping(Long mappingId) {
        StandardMappingDO mapping = mappingMapper.selectById(mappingId);
        if (mapping == null) {
            throw exception(STANDARD_MAPPING_NOT_EXISTS);
        }
        if (!StandardMappingDO.STATUS_CONFIRMED.equals(mapping.getMappingStatus())
                && !StandardMappingDO.STATUS_PROPOSED.equals(mapping.getMappingStatus())) {
            throw exception(STANDARD_MAPPING_STATUS_INVALID);
        }
        // 同一事务内：撤销映射事实 + 解除元数据字段的标准引用
        mappingMapper.updateById(StandardMappingDO.builder()
                .id(mappingId)
                .mappingStatus(StandardMappingDO.STATUS_REVOKED)
                .confirmedBy(String.valueOf(SecurityFrameworkUtils.getLoginUserId()))
                .confirmedTime(LocalDateTime.now())
                .build());
        metaColumnMapper.update(null, new LambdaUpdateWrapper<MetaColumnDO>()
                .eq(MetaColumnDO::getId, mapping.getColumnId())
                .eq(MetaColumnDO::getStandardElementId, mapping.getElementId().longValue())
                .set(MetaColumnDO::getStandardElementId, null));
    }

    /**
     * 批量导入标准数据元
     *
     *  @param importElements 导入数据元列表
     *  @param updateSupport 是否支持更新（按英文名称匹配已有数据元）
     *  @return 导入结果
     */
    @Override
    public StandardElementImportRespVO importStandardElementList(
            List<StandardElementImportExcelVO> importElements, boolean updateSupport) {
        if (CollUtil.isEmpty(importElements)) {
            throw exception(STANDARD_ELEMENT_IMPORT_LIST_EMPTY);
        }
        StandardElementImportRespVO respVO = StandardElementImportRespVO.builder()
                .createNames(new ArrayList<>())
                .updateNames(new ArrayList<>())
                .failureReasons(new LinkedHashMap<>())
                .build();
        for (int i = 0; i < importElements.size(); i++) {
            StandardElementImportExcelVO importVO = importElements.get(i);
            String rowKey = StrUtil.isNotBlank(importVO.getEngName())
                    ? importVO.getEngName() : "第 " + (i + 2) + " 行";
            try {
                TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                transaction.executeWithoutResult(status -> importStandardElement(importVO, updateSupport, respVO));
            } catch (ServiceException ex) {
                respVO.getFailureReasons().put(rowKey, ex.getMessage());
            }
        }
        return respVO;
    }

    /**
     * 导入标准数据元。
     */
    private void importStandardElement(StandardElementImportExcelVO importVO, boolean updateSupport,
            StandardElementImportRespVO respVO) {
        // 必填校验
        if (StrUtil.hasBlank(importVO.getName(), importVO.getEngName(), importVO.getColumnType())) {
            throw exception(STANDARD_ELEMENT_IMPORT_FIELD_MISSING);
        }
        // 类目名称解析为类目编号（要求唯一匹配）
        Long categoryId = resolveCategoryId(importVO.getCategoryName());

        StandardElementSaveReqVO saveReqVO = BeanUtils.toBean(importVO, StandardElementSaveReqVO.class);
        saveReqVO.setCategoryId(categoryId);
        saveReqVO.setSort(0);
        saveReqVO.setStatus((short) 0);

        StandardElementDO existing = standardElementMapper.selectByEngName(importVO.getEngName());
        if (existing == null) {
            createStandardElement(saveReqVO);
            respVO.getCreateNames().add(importVO.getEngName());
            return;
        }
        if (!updateSupport) {
            throw exception(STANDARD_ELEMENT_ENG_NAME_DUPLICATE);
        }
        saveReqVO.setId(existing.getId());
        saveReqVO.setStatus(existing.getStatus());
        saveReqVO.setSort(existing.getSort());
        saveReqVO.setRevision(existing.getRevision() == null ? 0L : existing.getRevision());
        updateStandardElement(saveReqVO);
        respVO.getUpdateNames().add(importVO.getEngName());
    }

    /**
     * 解析标准数据元的分类编号。
     */
    private Long resolveCategoryId(String categoryName) {
        if (StrUtil.isBlank(categoryName)) {
            return null;
        }
        List<CategoryDatametaDO> categories = categoryDatametaMapper.selectListByCategoryName(categoryName.trim());
        if (CollUtil.isEmpty(categories)) {
            throw exception(STANDARD_ELEMENT_CATEGORY_NOT_EXISTS);
        }
        if (categories.size() > 1) {
            throw exception(STANDARD_ELEMENT_IMPORT_CATEGORY_AMBIGUOUS);
        }
        return categories.get(0).getId();
    }

    /**
     * 获得标准数据元统计概览（总数、生命周期分布、落标率、码表绑定数）
     *
     *  @return 统计概览
     */
    @Override
    public StandardElementStatisticsRespVO getStatistics() {
        StandardElementStatisticsRespVO statistics = new StandardElementStatisticsRespVO();
        statistics.setTotalCount(standardElementMapper.selectCount());
        statistics.setDraftCount(standardElementMapper.selectCountByLifecycleStatus(
                StandardElementLifecycleEnum.DRAFT.getStatus()));
        statistics.setPublishedCount(standardElementMapper.selectCountByLifecycleStatus(
                StandardElementLifecycleEnum.PUBLISHED.getStatus()));
        statistics.setDeprecatedCount(standardElementMapper.selectCountByLifecycleStatus(
                StandardElementLifecycleEnum.DEPRECATED.getStatus()));
        statistics.setCodeSetBoundCount(standardElementMapper.selectCountByCodeSetBound());

        Long mappedCount = standardElementMapper.selectMappedElementCount();
        statistics.setMappedElementCount(mappedCount != null ? mappedCount : 0L);
        Long modelMappedCount = standardElementMapper.selectModelMappedElementCount();
        statistics.setModelMappedElementCount(modelMappedCount != null ? modelMappedCount : 0L);
        // 落标率 = 已落标 / 已发布，已发布为 0 时记 0，保留 1 位小数
        if (statistics.getPublishedCount() != null && statistics.getPublishedCount() > 0) {
            statistics.setMappingRate(BigDecimal.valueOf(statistics.getMappedElementCount() * 100L)
                    .divide(BigDecimal.valueOf(statistics.getPublishedCount()), 1, RoundingMode.HALF_UP));
        } else {
            statistics.setMappingRate(BigDecimal.ZERO);
        }
        return statistics;
    }

    /**
     * 校验标准数据元的非已发布，不满足约束时拒绝当前操作。
     */
    private void validateNotPublished(StandardElementDO element) {
        if (StandardElementLifecycleEnum.PUBLISHED.getStatus().equals(element.getLifecycleStatus())) {
            throw exception(STANDARD_ELEMENT_PUBLISHED_CANNOT_DELETE);
        }
    }

    /**
     * 校验数据元处于可编辑状态：已废止只读。
     */
    private void validateEditable(StandardElementDO element) {
        if (StandardElementLifecycleEnum.DEPRECATED.getStatus().equals(element.getLifecycleStatus())) {
            throw exception(STANDARD_ELEMENT_DEPRECATED_READONLY);
        }
    }

    /**
     * 校验标准数据元记录存在，不存在时抛出业务异常。
     */
    private StandardElementDO validateStandardElementExists(Integer id) {
        StandardElementDO element = standardElementMapper.selectById(id);
        if (element == null) {
            throw exception(STANDARD_ELEMENT_NOT_EXISTS);
        }
        return element;
    }

    /**
     * 校验标准数据元名称在指定范围内唯一。
     */
    private void validateNameUnique(Integer id, String name) {
        if (name == null) {
            return;
        }
        StandardElementDO element = standardElementMapper.selectByName(name);
        if (element != null && !Objects.equals(element.getId(), id)) {
            throw exception(STANDARD_ELEMENT_NAME_DUPLICATE);
        }
    }

    /**
     * 校验标准数据元的英文名称在指定范围内唯一。
     */
    private void validateEngNameUnique(Integer id, String engName) {
        if (engName == null) {
            return;
        }
        StandardElementDO element = standardElementMapper.selectByEngName(engName);
        if (element != null && !Objects.equals(element.getId(), id)) {
            throw exception(STANDARD_ELEMENT_ENG_NAME_DUPLICATE);
        }
    }

    /** 将所有可变生命周期操作与发布串行化。 */
    private StandardElementDO lockStandardElement(Integer id) {
        StandardElementDO element = standardElementMapper.selectByIdForUpdate(
                id);
        if (element == null) {
            throw exception(STANDARD_ELEMENT_NOT_EXISTS);
        }
        return element;
    }

    /**
     * 校验标准数据元的分类记录存在，不存在时抛出业务异常。
     */
    private void validateCategoryExists(Long categoryId) {
        if (categoryId == null) {
            return;
        }
        if (categoryDatametaMapper.selectByIdForUpdate(categoryId) == null) {
            throw exception(STANDARD_ELEMENT_CATEGORY_NOT_EXISTS);
        }
    }

    /**
     * 校验标准数据元的编码集合记录存在，不存在时抛出业务异常。
     */
    private void validateCodeSetExists(Integer codeSetId) {
        if (codeSetId == null) {
            return;
        }
        if (standardCodeSetMapper.selectById(codeSetId) == null) {
            throw exception(STANDARD_CODE_SET_NOT_EXISTS);
        }
    }

    /**
     * 校验并锁定被引用的标准文件。
     *
     * <p>按 {@code StandardDocMapper#selectByIdForUpdate} 的约定：写入 document_id 前，
     * 必须在同一事务内先锁定被引用标准，避免与标准文件删除并发冲突。</p>
     */
    private void validateAndLockDoc(Long documentId, String standardType) {
        if (documentId == null) {
            return;
        }
        var document = standardDocMapper.selectByIdForUpdate(documentId);
        if (document == null) {
            throw exception(DOC_NOT_EXISTS);
        }
        if (!Objects.equals(document.getStdType(), standardType)) {
            throw exception(STANDARD_CATALOG_INVALID, "标准文件不属于所选标准类型");
        }
    }

    /**
     * 校验数据元未被元数据字段（落标）引用。
     */
    private void validateNotReferenced(List<Integer> ids) {
        List<Long> elementIds = ids.stream()
                .filter(Objects::nonNull)
                .map(Integer::longValue)
                .toList();
        if (elementIds.isEmpty()) {
            return;
        }
        Long referenceCount = metaColumnMapper.selectCount(
                new LambdaQueryWrapperX<MetaColumnDO>()
                        .in(MetaColumnDO::getStandardElementId, elementIds));
        if (referenceCount != null && referenceCount > 0) {
            throw exception(STANDARD_ELEMENT_REFERENCED);
        }
    }

    /**
     * 生成编码。
     */
    private static String generateCode(Integer id) {
        return CODE_PREFIX + String.format("%0" + CODE_SEQ_WIDTH + "d", id);
    }

    /**
     * 切换标准启用状态。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Integer id, Short status, Long revision) {
        if (status == null || status < 0 || status > 1) {
            throw cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(
                    cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.STANDARD_CATALOG_INVALID,
                    "状态仅支持启用或停用");
        }
        var row = lockStandardElement(id);
        StandardCatalogService.checkRevision(revision, row.getRevision());
        standardElementMapper.updateById(StandardElementDO.builder().id(id).status(status)
                .revision((row.getRevision() == null ? 0L : row.getRevision()) + 1).build());
    }

    /**
     * 获得数据标准--标准数据元
     *
     *  @param id 编号
     *  @return 数据标准--标准数据元
     */
    @Override
    public StandardElementDO getStandardElement(Integer id) {
        return standardElementMapper.selectById(id);
    }

    /**
     * 获得数据标准--标准数据元分页
     *
     *  @param pageReqVO 分页查询
     *  @return 数据标准--标准数据元分页
     */
    @Override
    public PageResult<StandardElementDO> getStandardElementPage(StandardElementPageReqVO pageReqVO) {
        if (pageReqVO.getCategoryId() != null) {
            pageReqVO.setCategoryIds(catalogService.descendants(pageReqVO.getCategoryId()));
        }
        return standardElementMapper.selectPage(pageReqVO);
    }

}
