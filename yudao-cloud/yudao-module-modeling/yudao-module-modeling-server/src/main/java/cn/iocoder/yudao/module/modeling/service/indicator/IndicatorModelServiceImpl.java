package cn.iocoder.yudao.module.modeling.service.indicator;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.*;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import cn.iocoder.yudao.module.modeling.dal.dataobject.indicator.IndicatorModelDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.*;
import cn.iocoder.yudao.module.modeling.dal.mysql.indicator.IndicatorModelMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.*;
import cn.iocoder.yudao.module.modeling.dal.dataobject.businessentity.BusinessEntityDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.businessentity.BusinessEntityMapper;
import cn.iocoder.yudao.module.modeling.service.model.ModelHierarchyResolver;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;

/**
 * 指标定义领域服务：平台内按资源权限可见，关联冻结任务输入，不执行表达式。
 * 复用 MyBatis、安全、用户 API 与模型归属解析；状态和引用约束属于建模领域。
 */
@Service
@Validated
public class IndicatorModelServiceImpl implements IndicatorModelService {

    @Resource
    private IndicatorModelMapper mapper;

    @Resource
    private LogicModelMapper modelMapper;

    @Resource
    private cn.iocoder.yudao.module.modeling.service.model.LogicModelColumnService columns;

    @Resource
    private ModelHierarchyResolver hierarchy;

    @Resource
    private AdminUserApi userApi;

    @Resource
    private SecurityFrameworkService security;

    @Resource
    private TransactionTemplate transactions;

    @Resource
    private BusinessEntityMapper entityMapper;

    @Resource
    private IndicatorDependencyResolver dependencies;

    /**
     * 创建指标定义。
     *
     * @param request 请求参数
     * @return 生成或保存的记录编号
     */
    @Override
    public Long create(IndicatorModelSaveReqVO request) {
        validateOwner(request.getOwnerUserId());
        return transactions.execute(transaction -> {
            mapper.lockReferences();
            IndicatorModelDO row = prepare(request);
            row.setId(null);
            row.setStatus(desiredStatus(request.getStatus()));
            row.setRevision(0L);
            validateDefinition(row, true);
            uniqueCode(row);
            try {
                mapper.insert(row);
            } catch (DuplicateKeyException ignored) {
                throw exception(INDICATOR_CODE_DUPLICATE);
            }
            return row.getId();
        });
    }

    /**
     * 更新草稿指标定义。
     *
     * @param request 请求参数
     */
    @Override
    public void update(IndicatorModelSaveReqVO request) {
        validateOwner(request.getOwnerUserId());
        transactions.executeWithoutResult(transaction -> {
            mapper.lockReferences();
            IndicatorModelDO old = lock(request.getId(), request.getExpectedRevision());
            requireDraft(old);
            requireUnreferenced(old.getId());
            IndicatorModelDO row = prepare(request);
            if (!old.getIndicatorType().equals(row.getIndicatorType())) {
                throw exception(INDICATOR_TYPE_IMMUTABLE);
            }
            row.setStatus(desiredStatus(request.getStatus()));
            row.setRevision(old.getRevision() + 1);
            validateDefinition(row, true);
            uniqueCode(row);
            try {
                mapper.updateById(row);
            } catch (DuplicateKeyException ignored) {
                throw exception(INDICATOR_CODE_DUPLICATE);
            }
        });
    }

    /**
     * 删除未被引用的草稿指标。
     *
     * @param id 记录编号
     * @param expectedRevision 预期修订号
     */
    @Override
    public void delete(Long id, Long expectedRevision) {
        transactions.executeWithoutResult(transaction -> {
            mapper.lockReferences();
            IndicatorModelDO row = lock(id, expectedRevision);
            requireDraft(row);
            requireUnreferenced(id);
            mapper.deleteById(id);
        });
    }

    /**
     * 启用或停用指标定义。
     *
     * @param request 请求参数
     */
    @Override
    public void updateStatus(IndicatorModelStatusReqVO request) {
        transactions.executeWithoutResult(transaction -> {
            mapper.lockReferences();
            IndicatorModelDO row = lock(request.getId(), request.getExpectedRevision());
            if (Objects.equals(row.getStatus(), request.getStatus())) {
                return;
            }
            if (IndicatorModelDO.ACTIVE.equals(request.getStatus())) {
                validateDefinition(row, false);
            } else {
                requireUnreferenced(row.getId());
            }
            row.setStatus(request.getStatus());
            row.setRevision(row.getRevision() + 1);
            // 读取出的旧审计字段不能阻止 MyBatis 写入本次操作时间。
            row.setUpdateTime(null);
            row.setUpdater(null);
            mapper.updateById(row);
        });
    }

    /**
     * 获得指标定义详情。
     *
     * @param id 记录编号
     * @return 指标模型响应数据
     */
    @Override
    public IndicatorModelRespVO get(Long id) {
        return responses(List.of(require(id))).getFirst();
    }

    /**
     * 分页查询指标定义。
     *
     * @param request 请求参数
     * @return 分页查询指标定义结果
     */
    @Override
    public PageResult<IndicatorModelRespVO> page(IndicatorModelPageReqVO request) {
        PageResult<IndicatorModelDO> page = mapper.selectPage(request);
        return new PageResult<>(responses(page.getList()), page.getTotal());
    }

    /**
     * 获得可引用的启用原子指标。
     *
     * @return 可引用的启用原子指标结果集合
     */
    @Override
    public List<IndicatorModelRespVO> atomicOptions() {
        return responses(mapper.selectAtomicOptions());
    }

    /** 读取已启用、已发布的当前字段，不允许读取历史定义。 */
    @Override
    public List<IndicatorSourceFieldRespVO> sourceFields(Long modelId) {
        requireSourceModel(modelId);
        return fields(modelId).stream()
                .map(field -> BeanUtils.toBean(field, IndicatorSourceFieldRespVO.class)).toList();
    }

    /** 校验当前来源模型和字段。 */
    @Override
    public void validateSource(Long modelId) {
        requireSourceModel(modelId);
        fields(modelId);
    }

    /**
     * 校验业务实体与来源已发布当前设计的相同归属，返回业务实体名称。
     *
     * @param modelId 模型编号
     * @param modelId 模型编号
     * @param domainId 域编号
     * @param subjectId 主体编号
     * @param entityId 实体编号
     * @param timeliness 时效性
     * @return 校验业务实体与来源已发布当前设计的相同归属，返回业务实体名称文本
     */
    @Override
    public String validateScopedSource(Long modelId, Long domainId, Long subjectId, Long entityId,
            String timeliness) {
        BusinessEntityDO entity = entityId == null ? null : entityMapper.selectById(entityId);
        if (entity == null || !Integer.valueOf(0).equals(entity.getStatus()) || subjectId == null
                || !Objects.equals(entity.getDataDomainId(), domainId) || !Objects.equals(entity.getSubjectDomainId(),
                subjectId)
                || !Set.of("1", "2", "3").contains(Objects.toString(timeliness, ""))) {
            throw exception(INDICATOR_DEFINITION_INVALID, "请选择同板块、主题域下已启用的业务实体和有效统计时效");
        }
        LogicModelDO model = requireSourceModel(modelId);
        fields(modelId);
        if (!Set.of("DIMENSION", "DETAIL").contains(Objects.toString(model.getTableType(), ""))
                || !Objects.equals(model.getDataDomainId(), domainId) || !Objects.equals(model.getSubjectDomainId(),
                subjectId)
                || !Objects.equals(model.getEntityId(), entityId) || !Objects.equals(model.getDataTimeliness(),
                timeliness)) {
            throw exception(INDICATOR_DEFINITION_INVALID,
                    "来源必须是相同板块、主题域、业务实体和统计时效下启用的维度或事实逻辑表");
        }
        return entity.getName();
    }

    /** 复用正式原子定义校验，防止保留UID但改变类型后继续生成错误指标。 */
    @Override
    public boolean isAtomicSourceUsable(IndicatorModelDO indicator) {
        if (indicator == null || !IndicatorModelDO.ATOMIC.equals(indicator.getIndicatorType())
                || !IndicatorModelDO.ACTIVE.equals(indicator.getStatus())) {
            return false;
        }
        try {
            validateDefinition(indicator, false);
            return true;
        } catch (cn.iocoder.yudao.framework.common.exception.ServiceException invalid) {
            return false;
        }
    }

    /**
     * 获取指标模型的预期状态。
     *
     * @param status 目标状态
     * @return 指标模型的预期状态文本
     */
    private String desiredStatus(String status) {
        String value = StrUtil.blankToDefault(status, IndicatorModelDO.DRAFT);
        if (!Set.of(IndicatorModelDO.DRAFT, IndicatorModelDO.ACTIVE).contains(value)) {
            throw exception(INDICATOR_DEFINITION_INVALID, "指标状态不合法");
        }
        return value;
    }

    /**
     * 校验所有者。
     *
     * @param userId 用户编号
     */
    private void validateOwner(Long userId) {
        // RPC 位于本地事务和引用锁之外，失败时不会写入任何指标。
        if (userId != null) {
            userApi.validateUserList(List.of(userId)).checkError();
        }
    }

    /**
     * 准备指标模型所需的配置和状态。
     *
     * @param request 请求参数
     * @return 指标模型
     */
    private IndicatorModelDO prepare(IndicatorModelSaveReqVO request) {
        IndicatorModelDO row = BeanUtils.toBean(request, IndicatorModelDO.class);
        row.setCode(request.getCode().trim());
        row.setName(request.getName().trim());
        row.setTimeFieldUid(StrUtil.blankToDefault(row.getTimeFieldUid(), null));
        row.setFormula(StrUtil.blankToDefault(StrUtil.trim(row.getFormula()), null));
        return row;
    }

    /**
     * 校验定义。
     *
     * @param row 当前行数据
     * @param checkSourcePermission 检查来源权限
     */
    private void validateDefinition(IndicatorModelDO row, boolean checkSourcePermission) {
        hierarchy.validateModelDataDomain(row.getDataDomainId());
        hierarchy.validateSubjectDomain(row.getSubjectDomainId(), null, row.getDataDomainId());
        if (IndicatorModelDO.ATOMIC.equals(row.getIndicatorType())) {
            if (checkSourcePermission && !security.hasPermission("modeling:model:query")) {
                throw exception(INDICATOR_SOURCE_FORBIDDEN);
            }
            validateScopedSource(row.getSourceModelId(), row.getDataDomainId(),
                    row.getSubjectDomainId(), row.getEntityId(), row.getDataFreshness());
            requireSourceModel(row.getSourceModelId());
            List<ModelFieldItem> fields = fields(row.getSourceModelId());
            ModelFieldItem value = requireField(fields, row.getValueFieldUid());
            if (StrUtil.isBlank(row.getAggregation()) || StrUtil.isBlank(row.getResultDataType())) {
                throw exception(INDICATOR_DEFINITION_INVALID, "原子指标必须指定聚合方式和结果数据类型");
            }
            if (Set.of("SUM", "AVG").contains(row.getAggregation()) && !isNumeric(value.getDataType())) {
                throw exception(INDICATOR_DEFINITION_INVALID, "SUM/AVG 只能关联已确认的数值字段");
            }
            if (Set.of("COUNT", "COUNT_DISTINCT").contains(row.getAggregation())
                    && !Set.of("TINYINT", "SMALLINT", "INT", "INTEGER", "BIGINT", "INT2", "INT4", "INT8")
                    .contains(row.getResultDataType())) {
                throw exception(INDICATOR_DEFINITION_INVALID, "计数聚合的结果类型必须为整数类型");
            }
            if (Set.of("SUM", "AVG").contains(row.getAggregation())
                    && !isNumeric(row.getResultDataType())) {
                throw exception(INDICATOR_DEFINITION_INVALID, "数值聚合的结果类型必须为数值类型");
            }
            if (StrUtil.isNotBlank(row.getTimeFieldUid())) {
                ModelFieldItem time = requireField(fields, row.getTimeFieldUid());
                if (!isTemporal(time.getDataType())) {
                    throw exception(INDICATOR_DEFINITION_INVALID, "统计时间字段必须为日期或时间类型");
                }
            }
            row.setBaseIndicatorId(null);
            row.setTimePeriod(null);
            row.setBusinessCondition(null);
            row.setFormula(null);
        } else if (IndicatorModelDO.DERIVED.equals(row.getIndicatorType())) {
            if (row.getBaseIndicatorId() == null || Objects.equals(row.getBaseIndicatorId(), row.getId())) {
                throw exception(INDICATOR_BASE_INVALID);
            }
            IndicatorModelDO base = mapper.selectForUpdate(row.getBaseIndicatorId());
            if (base == null || !IndicatorModelDO.ATOMIC.equals(base.getIndicatorType())
                    || !IndicatorModelDO.ACTIVE.equals(base.getStatus())) {
                throw exception(INDICATOR_BASE_INVALID);
            }
            // 派生来源仍需是可用的当前设计，不能沿历史启用原子指标绕过停用或草稿状态。
            validateDefinition(base, false);
            if (!Objects.equals(base.getDataDomainId(), row.getDataDomainId())) {
                throw exception(INDICATOR_DEFINITION_INVALID, "派生指标必须与原子指标属于同一数据域");
            }
            if (StrUtil.isBlank(base.getTimeFieldUid())) {
                throw exception(INDICATOR_DEFINITION_INVALID, "按周期派生前请为原子指标配置统计时间字段");
            }
            if (StrUtil.isBlank(row.getTimePeriod()) || StrUtil.isBlank(row.getBusinessCondition())) {
                throw exception(INDICATOR_DEFINITION_INVALID, "派生指标必须指定统计周期和业务限定");
            }
            row.setSourceModelId(null);
            row.setValueFieldUid(null);
            row.setTimeFieldUid(null);
            row.setAggregation(null);
            row.setResultDataType(base.getResultDataType());
            row.setUnit(base.getUnit());
            row.setEntityId(base.getEntityId());
            row.setDataFreshness(base.getDataFreshness());
            row.setFormula(null);
        } else if (IndicatorModelDO.COMPOSITE.equals(row.getIndicatorType())) {
            validateComposite(row);
        } else {
            throw exception(INDICATOR_DEFINITION_INVALID, "指标类型不合法");
        }
    }

    /**
     * 判断是否满足数值条件。
     *
     * @param type 类型标识
     * @return 判断结果
     */
    private boolean isNumeric(String type) {
        String value = StrUtil.blankToDefault(type, "").toUpperCase(Locale.ROOT).split("\\(", 2)[0].trim();
        return Set.of("TINYINT", "SMALLINT", "INT", "INTEGER", "BIGINT", "INT2", "INT4", "INT8",
                "DECIMAL", "NUMERIC", "NUMBER", "REAL", "FLOAT", "FLOAT4", "FLOAT8", "DOUBLE",
                "DOUBLE PRECISION", "MONEY").contains(value);
    }

    /**
     * 判断是否满足时间条件。
     *
     * @param type 类型标识
     * @return 判断结果
     */
    private boolean isTemporal(String type) {
        String value = StrUtil.blankToDefault(type, "").trim().toUpperCase(Locale.ROOT);
        return value.matches("^(DATE|DATETIME|TIMESTAMP|TIME)(?:\\b|\\().*");
    }

    /**
     * 校验并获取指标模型。
     *
     * @param id 记录编号
     * @return 指标模型
     */
    private IndicatorModelDO require(Long id) {
        IndicatorModelDO row = id == null ? null : mapper.selectById(id);
        if (row == null) {
            throw exception(INDICATOR_NOT_EXISTS);
        }
        return row;
    }

    /**
     * 锁定指标模型当前记录。
     *
     * @param id 记录编号
     * @param revision 修订号
     * @return 指标模型
     */
    private IndicatorModelDO lock(Long id, Long revision) {
        IndicatorModelDO row = id == null ? null : mapper.selectForUpdate(id);
        if (row == null) {
            throw exception(INDICATOR_NOT_EXISTS);
        }
        if (revision == null || !Objects.equals(row.getRevision(), revision)) {
            throw exception(INDICATOR_REVISION_CONFLICT);
        }
        return row;
    }

    /**
     * 校验草稿。
     *
     * @param row 当前行数据
     */
    private void requireDraft(IndicatorModelDO row) {
        if (!IndicatorModelDO.DRAFT.equals(row.getStatus())) {
            throw exception(INDICATOR_NOT_DRAFT);
        }
    }

    /**
     * 校验未被引用。
     *
     * @param id 记录编号
     */
    private void requireUnreferenced(Long id) {
        if (mapper.countReferences(id) > 0) {
            throw exception(INDICATOR_REFERENCED);
        }
        Long composites = mapper.countCompositeReferences(id);
        if (composites != null && composites > 0) {
            throw exception(INDICATOR_COMPOSITE_REFERENCED);
        }
    }

    /**
     * 校验复合指标：公式白名单语法、引用启用且同数据域、DAG 无环；结果类型固定 DECIMAL。
     * 引用保存为规范编码，使引用计数可按 {编码} 精确匹配。调用方已持有全局引用锁。
     *
     * @param row 当前行数据
     */
    private void validateComposite(IndicatorModelDO row) {
        IndicatorFormula formula = IndicatorDependencyResolver.parse(row.getFormula());
        IndicatorDependencyResolver.Node tree = dependencies.resolve(row);
        Map<String, String> canonical = new HashMap<>();
        tree.children().forEach(child -> canonical.put(child.indicator().getCode().toLowerCase(Locale.ROOT),
                child.indicator().getCode()));
        String normalized = formula.normalize(code -> canonical.get(code.toLowerCase(Locale.ROOT)));
        if (normalized.length() > IndicatorFormula.MAX_LENGTH) {
            throw exception(INDICATOR_FORMULA_INVALID, "公式长度不能超过 " + IndicatorFormula.MAX_LENGTH + " 个字符");
        }
        row.setFormula(normalized);
        row.setSourceModelId(null);
        row.setValueFieldUid(null);
        row.setTimeFieldUid(null);
        row.setAggregation(null);
        row.setBaseIndicatorId(null);
        row.setTimePeriod(null);
        row.setBusinessCondition(null);
        row.setEntityId(null);
        row.setDataFreshness(null);
        row.setResultDataType("DECIMAL");
    }

    /**
     * 实时校验复合指标公式并返回依赖树；不写库，校验失败以结果返回而非异常。
     *
     * @param request 请求参数
     * @return 校验结果
     */
    @Override
    public IndicatorFormulaValidateRespVO validateFormula(IndicatorFormulaValidateReqVO request) {
        IndicatorFormulaValidateRespVO response = new IndicatorFormulaValidateRespVO();
        response.setReferences(List.of());
        response.setDependencies(List.of());
        IndicatorModelDO draft = new IndicatorModelDO();
        draft.setId(request.getId());
        draft.setCode(StrUtil.blankToDefault(StrUtil.trim(request.getCode()), null));
        if (draft.getCode() == null && request.getId() != null) {
            IndicatorModelDO saved = mapper.selectById(request.getId());
            draft.setCode(saved == null ? null : saved.getCode());
        }
        draft.setDataDomainId(request.getDataDomainId());
        draft.setIndicatorType(IndicatorModelDO.COMPOSITE);
        draft.setFormula(request.getFormula().trim());
        try {
            validateComposite(draft);
            IndicatorDependencyResolver.Node tree = dependencies.resolve(draft);
            response.setValid(true);
            response.setNormalizedFormula(draft.getFormula());
            response.setReferences(IndicatorFormula.parse(draft.getFormula()).references());
            response.setDependencies(tree.children().stream().map(this::dependencyNode).toList());
        } catch (cn.iocoder.yudao.framework.common.exception.ServiceException invalid) {
            response.setValid(false);
            response.setMessage(invalid.getMessage());
        }
        return response;
    }

    /**
     * 启用指标轻量选项，供公式编辑器与指标查询选择。
     *
     * @param dataDomainId 数据域，可为空
     * @return 启用指标
     */
    @Override
    public List<IndicatorOptionRespVO> activeOptions(Long dataDomainId) {
        return mapper.selectActiveOptions(dataDomainId).stream().map(row -> {
            IndicatorOptionRespVO option = BeanUtils.toBean(row, IndicatorOptionRespVO.class);
            option.setTimeConfigured(StrUtil.isNotBlank(row.getTimeFieldUid()));
            return option;
        }).toList();
    }

    /**
     * 组装依赖树节点。
     */
    private IndicatorDependencyNodeRespVO dependencyNode(IndicatorDependencyResolver.Node node) {
        IndicatorDependencyNodeRespVO result = new IndicatorDependencyNodeRespVO();
        IndicatorModelDO row = node.indicator();
        result.setId(row.getId());
        result.setCode(row.getCode());
        result.setName(row.getName());
        result.setIndicatorType(row.getIndicatorType());
        result.setStatus(row.getStatus());
        result.setFormula(row.getFormula());
        result.setTimePeriod(row.getTimePeriod());
        result.setChildren(node.children().stream().map(this::dependencyNode).toList());
        return result;
    }

    /**
     * 校验唯一性编码。
     *
     * @param row 当前行数据
     */
    private void uniqueCode(IndicatorModelDO row) {
        IndicatorModelDO existing = mapper.selectByCodeIgnoreCase(row.getCode());
        if (existing != null && !Objects.equals(existing.getId(), row.getId())) {
            throw exception(INDICATOR_CODE_DUPLICATE);
        }
    }

    /**
     * 校验来源模型。
     *
     * @param id 记录编号
     * @return 逻辑模型
     */
    private LogicModelDO requireSourceModel(Long id) {
        LogicModelDO model = id == null ? null : modelMapper.selectById(id);
        if (model == null || !LogicModelDO.STATUS_PUBLISHED.equals(model.getStatus())
                || !Integer.valueOf(1).equals(model.getEnabled())) {
            throw exception(INDICATOR_SOURCE_INVALID);
        }
        return model;
    }

    /** 当前字段必须有稳定UID；缺失时拒绝使用，不回退到旧定义。 */
    private List<ModelFieldItem> fields(Long modelId) {
        List<ModelFieldItem> fields = columns.listByModelId(modelId);
        if (fields == null || fields.isEmpty()
                || fields.stream().anyMatch(field -> StrUtil.isBlank(field.getFieldUid()))) {
            throw exception(INDICATOR_SOURCE_INVALID);
        }
        return fields;
    }

    /**
     * 校验字段。
     *
     * @param fields 字段定义
     * @param uid 用户标识
     * @return 模型字段项
     */
    private ModelFieldItem requireField(List<ModelFieldItem> fields, String uid) {
        return fields.stream().filter(field -> Objects.equals(field.getFieldUid(), uid)).findFirst()
                .orElseThrow(() -> exception(INDICATOR_FIELD_INVALID));
    }

    /**
     * 获取指标模型的响应。
     *
     * @param rows 待处理的行数据
     * @return 指标模型的响应结果集合
     */
    private List<IndicatorModelRespVO> responses(List<IndicatorModelDO> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        var modelIds = rows.stream().map(IndicatorModelDO::getSourceModelId).filter(Objects::nonNull).distinct()
                .toList();
        var baseIds = rows.stream().map(IndicatorModelDO::getBaseIndicatorId).filter(Objects::nonNull).distinct()
                .toList();
        var models = new HashMap<Long, LogicModelDO>();
        var bases = new HashMap<Long, IndicatorModelDO>();
        var entities = new HashMap<Long, BusinessEntityDO>();
        if (!modelIds.isEmpty()) {
            modelMapper.selectBatchIds(modelIds).forEach(item -> models.put(item.getId(), item));
        }
        if (!baseIds.isEmpty()) {
            mapper.selectBatchIds(baseIds).forEach(item -> bases.put(item.getId(), item));
        }
        var entityIds = rows.stream().map(IndicatorModelDO::getEntityId).filter(Objects::nonNull).distinct().toList();
        if (!entityIds.isEmpty()) {
            entityMapper.selectByIds(entityIds).forEach(item -> entities.put(item.getId(), item));
        }
        var counts = new HashMap<Long, Long>();
        mapper.selectDerivedReferenceCounts(rows.stream().map(IndicatorModelDO::getId).toList())
                .forEach(item -> counts.put(item.getBaseIndicatorId(), item.getTotal()));
        var compositeCounts = new HashMap<Long, Long>();
        var compositeRows = mapper.selectCompositeReferenceCounts(rows.stream().map(IndicatorModelDO::getId).toList());
        if (compositeRows != null) {
            compositeRows.forEach(item -> compositeCounts.put(item.getBaseIndicatorId(), item.getTotal()));
        }
        var userIds = new HashSet<Long>();
        rows.forEach(row -> {
            if (row.getOwnerUserId() != null) {
                userIds.add(row.getOwnerUserId());
            }
            addAuditUser(userIds, row.getCreator());
            addAuditUser(userIds, row.getUpdater());
        });
        Map<Long, AdminUserRespDTO> users = userIds.isEmpty() ? Map.of() : userApi.getUserMap(userIds);
        return rows.stream().map(row -> {
            var result = response(row, models, bases);
            var entity = entities.get(row.getEntityId());
            if (entity != null) {
                result.setEntityName(entity.getName());
            }
            result.setDerivedIndicatorCount(counts.getOrDefault(row.getId(), 0L));
            result.setCompositeReferenceCount(compositeCounts.getOrDefault(row.getId(), 0L));
            var owner = row.getOwnerUserId() == null ? null : users.get(row.getOwnerUserId());
            if (owner != null) {
                result.setOwnerUserName(owner.getNickname());
                result.setOwnerUserPhone(owner.getMobile());
            }
            var creator = auditUser(users, row.getCreator());
            if (creator != null) {
                result.setCreatorName(creator.getNickname());
                result.setCreatorPhone(creator.getMobile());
            }
            var updater = auditUser(users, row.getUpdater());
            if (updater != null) {
                result.setUpdaterName(updater.getNickname());
                result.setUpdaterPhone(updater.getMobile());
            }
            return result;
        }).toList();
    }

    /**
     * 添加审计用户。
     *
     * @param ids 记录编号集合
     * @param value 待处理的值
     */
    private void addAuditUser(Set<Long> ids, String value) {
        if (value != null && value.matches("[0-9]{1,19}")) {
            try {
                ids.add(Long.parseLong(value));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    /**
     * 获取指标模型的审计用户。
     *
     * @param users 用户
     * @param value 待处理的值
     * @return 管理端用户响应数据
     */
    private AdminUserRespDTO auditUser(Map<Long, AdminUserRespDTO> users, String value) {
        if (value == null || !value.matches("[0-9]{1,19}")) {
            return null;
        }
        try {
            return users.get(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * 组装指标模型响应数据。
     *
     * @param row 当前行数据
     * @param models 模型
     *
     * @param bases 基类
     * @return 指标模型响应数据
     */
    private IndicatorModelRespVO response(IndicatorModelDO row, Map<Long, LogicModelDO> models,
            Map<Long, IndicatorModelDO> bases) {
        IndicatorModelRespVO response = BeanUtils.toBean(row, IndicatorModelRespVO.class);
        if (IndicatorModelDO.ATOMIC.equals(row.getIndicatorType())) {
            LogicModelDO model = models.get(row.getSourceModelId());
            if (model != null) {
                response.setSourceModelName(model.getName());
                response.setSourceModelCode(model.getCode());
            }
            if (model != null) {
                List<ModelFieldItem> fields = columns.listByModelId(model.getId());
                fields.stream().filter(field -> Objects.equals(field.getFieldUid(), row.getValueFieldUid()))
                        .findFirst().ifPresent(field -> response.setValueFieldName(field.getFieldName()));
                fields.stream().filter(field -> Objects.equals(field.getFieldUid(), row.getTimeFieldUid()))
                        .findFirst().ifPresent(field -> response.setTimeFieldName(field.getFieldName()));
            }
            String field = StrUtil.blankToDefault(response.getValueFieldName(), row.getValueFieldUid());
            response.setExpression("COUNT_DISTINCT".equals(row.getAggregation())
                    ? "COUNT(DISTINCT " + field + ")" : row.getAggregation() + "(" + field + ")");
        } else if (IndicatorModelDO.COMPOSITE.equals(row.getIndicatorType())) {
            response.setExpression(row.getFormula());
        } else {
            IndicatorModelDO base = bases.get(row.getBaseIndicatorId());
            response.setBaseIndicatorName(base == null ? null : base.getName());
            response.setExpression((base == null ? "原子指标" : base.getName())
                    + " · " + row.getTimePeriod() + " · " + row.getBusinessCondition());
        }
        return response;
    }
}

