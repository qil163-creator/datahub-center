package cn.iocoder.yudao.module.governance.service.standardelement;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.quality.core.audit.*;
import cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionRegistry;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.*;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.*;
import cn.iocoder.yudao.module.governance.dal.mysql.qualityrule.*;
import cn.iocoder.yudao.module.governance.dal.mysql.categorycleanrule.CategoryCleanRuleMapper;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorycleanrule.CategoryCleanRuleDO;
import cn.iocoder.yudao.module.governance.service.qualityrule.AuditRuleDimensionService;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeitem.StandardCodeItemMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeSetMapper;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeitem.StandardCodeItemDO;
import cn.iocoder.yudao.module.governance.service.quality.QualityRuleConfigAdapter;
import cn.iocoder.yudao.module.modeling.api.model.ModelRuleContextApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/** 规则实例属于数据元，规则模板不被修改。 */
@Service
@RequiredArgsConstructor
public class StandardElementRuleService {

    private static final Set<String> MODEL_BOUND_AUDITS = Set.of("TIME_ORDER_VALIDATION", "NUMERIC_RELATION_VALIDATION",
            "MUTUAL_EXCLUSION_VALIDATION", "GROUP_FIELD_COMPLETENESS", "COMPOSITE_UNIQUENESS_VALIDATION",
            "CONDITIONAL_REQUIRED_VALIDATION", "STATE_CONSISTENCY_VALIDATION", "FOREIGN_KEY_VALIDATION",
            "CROSS_TABLE_CONSISTENCY");

    private final StandardElementRuleMapper rules;

    private final StandardElementMapper elements;

    private final AuditRuleMapper audits;

    private final CleanRuleMapper cleans;

    private final CategoryCleanRuleMapper cleanCategories;

    private final AuditRuleDimensionService auditDimensions;

    private final AuditSqlRegistry auditRegistry;

    private final CleanFunctionRegistry cleanRegistry;

    private final DbSwitchRuntime runtime;

    private final StandardCatalogService catalog;

    private final ModelRuleContextApi modelFields;

    private final SecurityFrameworkService security;

    private final StandardCodeItemMapper codeItems;

    private final StandardCodeSetMapper codeSets;

    /**
     * 查询全部关联规则模板及适用状态。
     */
    public List<StandardElementRuleTemplateRespVO> templates(String kind) {
        if (!List.of("AUDIT", "CLEAN").contains(kind)) {
            throw invalid("规则类型无效");
        }
        if ("AUDIT".equals(kind)) {
            var auditRules = audits.selectList();
            Map<Long, String> dimensionNames = auditDimensions.getDimensionNameMap(auditRules.stream()
                    .map(row -> row.getDimensionId()).filter(Objects::nonNull).collect(Collectors.toSet()));
            return auditRules.stream().map(row -> {
                var result = BeanUtils.toBean(row, StandardElementRuleTemplateRespVO.class);
                result.setKind(kind);
                result.setCategoryId(row.getDimensionId());
                result.setCategoryName(row.getDimensionId() == null ? null : dimensionNames.get(row.getDimensionId()));
                result.setDimensionName(result.getCategoryName());
                result.setEngineSupported(auditRegistry.contains(row.getStrategyKey()));
                result.setEnabled(Objects.equals(row.getStatus(), 0) && result.isEngineSupported());
                result.setDisabledReason(result.isEnabled() ? null : "该模板未启用、尚未接入引擎或不适用于数据元");
                result.setConfigExample(result.isEngineSupported() ? auditRegistry.getGenerator(row.getStrategyKey())
                        .getConfigExample() : null);
                result.setConfig(Map.of());
                return result;
            }).sorted(Comparator.comparing(StandardElementRuleTemplateRespVO::getCode)).toList();
        }
        // 一次读取类目，按父链归入顶层组；坏链不影响其余模板展示。
        Map<Long, CategoryCleanRuleDO> categories = cleanCategories.selectList().stream()
                .collect(Collectors.toMap(CategoryCleanRuleDO::getId, row -> row));
        return cleans.selectList().stream().map(row -> {
            var result = BeanUtils.toBean(row, StandardElementRuleTemplateRespVO.class);
            result.setKind(kind);
            var category = cleanRoot(row.getCategoryId(), categories);
            result.setCategoryId(category == null ? null : category.getId());
            result.setCategoryName(category == null ? "未分类" : category.getCategoryName());
            result.setEngineSupported(cleanRegistry.contains(row.getStrategyKey()));
            result.setEnabled(Objects.equals(row.getStatus(), 0) && result.isEngineSupported());
            result.setDisabledReason(result.isEnabled() ? null : "该模板未启用、尚未接入引擎或不适用于数据元");
            result.setConfigExample(result.isEngineSupported() ? cleanRegistry.getFunction(row.getStrategyKey())
                    .getConfigExample() : null);
            result.setConfig(parse(row.getConfigJson()));
            return result;
        }).sorted(Comparator.comparing(StandardElementRuleTemplateRespVO::getCode)).toList();
    }

    /**
     * 清理词根。
     */
    private static CategoryCleanRuleDO cleanRoot(Long id, Map<Long, CategoryCleanRuleDO> categories) {
        Set<Long> visited = new HashSet<>();
        while (id != null && visited.add(id)) {
            var row = categories.get(id);
            if (row == null) {
                return null;
            }
            if (row.getParentId() == null || CategoryCleanRuleDO.PARENT_ID_ROOT.equals(row.getParentId())) {
                return row;
            }
            id = row.getParentId();
        }
        return null;
    }

    /**
     * 查询当前用户可用的模型字段上下文。
     */
    public StandardElementRuleContextRespVO context(Integer elementId) {
        requireElement(elementId, false);
        if (!security.hasPermission("modeling:model:query")) {
            return new StandardElementRuleContextRespVO(List.of(), List.of(), "需要模型查询权限才能配置关联字段");
        }
        var fields = modelFields.getRuleContextFieldList(elementId.longValue()).getCheckedData();
        var grouped = fields.stream()
                .collect(Collectors.groupingBy(row -> row.getModelId(), LinkedHashMap::new, Collectors.toList()));
        var objects = grouped.entrySet().stream()
                .map(entry -> new StandardElementRuleContextRespVO.ReferenceObject(entry.getKey(),
                        entry.getValue().getFirst().getModelName(),
                        entry.getValue().stream().map(field -> new StandardElementRuleContextRespVO.Field(
                                field.getFieldName() + (field.getCnName() == null ? "" : "/" + field.getCnName()),
                                field.getFieldName(), field.getModelId())).distinct().toList())).toList();
        var all = new LinkedHashMap<String, StandardElementRuleContextRespVO.Field>();
        objects.forEach(object -> object.fields().forEach(field -> all.putIfAbsent(field.value(), field)));
        return new StandardElementRuleContextRespVO(List.copyOf(all.values()), objects,
                objects.isEmpty() ? "当前数据元尚未被可查询模型引用，暂无关联字段" : null);
    }

    /**
     * 创建关联配置。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(StandardElementRuleSaveReqVO request) {
        // 模型 RPC 与授权先于本地持锁写入；字段上下文仅用于配置，不执行模型数据。
        Map<String, Object> config = checkedConfig(request);
        editable(request.getElementId());
        unique(request, null);
        var row = BeanUtils.toBean(request, StandardElementRuleDO.class);
        row.setId(null);
        row.setRevision(0L);
        row.setConfigJson(JsonUtils.toJsonString(config));
        rules.insert(row);
        return row.getId();
    }

    /**
     * 更新关联配置。
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(StandardElementRuleSaveReqVO request) {
        Map<String, Object> config = checkedConfig(request);
        editable(request.getElementId());
        var prior = require(request.getElementId(), request.getId());
        if (!Objects.equals(prior.getKind(), request.getKind()) || request.getRevision() == null) {
            throw exception(STANDARD_CATALOG_CONFLICT);
        }
        StandardCatalogService.checkRevision(request.getRevision(), prior.getRevision());
        unique(request, prior.getId());
        var row = BeanUtils.toBean(request, StandardElementRuleDO.class);
        row.setRevision(prior.getRevision() + 1);
        row.setConfigJson(JsonUtils.toJsonString(config));
        rules.updateById(row);
    }

    /**
     * 删除关联配置。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Integer elementId, Long id) {
        editable(elementId);
        require(elementId, id);
        rules.deleteById(id);
    }

    /**
     * 查询关联配置详情。
     */
    public StandardElementRuleRespVO get(Integer elementId, Long id) {
        return convert(List.of(require(elementId, id))).getFirst();
    }

    /**
     * 查询关联配置分页。
     */
    public PageResult<StandardElementRuleRespVO> page(StandardElementRulePageReqVO request) {
        requireElement(request.getElementId(), false);
        var result = rules.selectPage(request, new LambdaQueryWrapperX<StandardElementRuleDO>()
                .eq(StandardElementRuleDO::getElementId, request.getElementId())
                .eqIfPresent(StandardElementRuleDO::getKind, request.getKind())
                .likeIfPresent(StandardElementRuleDO::getName, request.getName())
                .eqIfPresent(StandardElementRuleDO::getStatus, request.getStatus())
                .orderByDesc(StandardElementRuleDO::getCreateTime).orderByDesc(StandardElementRuleDO::getId));
        return new PageResult<>(convert(result.getList()), result.getTotal());
    }

    public record SampleResult(boolean valid, String message) {

    }

    /**
     * 检测字段字符样例。
     */
    public SampleResult sample(StandardElementRuleSampleReqVO request) {
        requireElement(request.getElementId(), false);
        var template = template("AUDIT", request.getTemplateId());
        if (!"CHARACTER_VALIDATION".equals(template.getStrategyKey())) {
            throw invalid("此规则不支持字符样例检测");
        }
        try {
            var config = QualityRuleConfigAdapter.normalize(template.getStrategyKey(), request.getConfig());
            String regex = Objects.toString(config.get("regex"), "");
            if (regex.isEmpty() || regex.length() > 1024) {
                throw invalid("正则表达式长度须为 1 至 1024");
            }
            // 与质量任务的 Java 正则样例语义一致，输入有明确大小上限。
            boolean valid = Pattern.compile(regex).matcher(request.getSample()).matches();
            return new SampleResult(valid, valid ? "数据检测成功" : "数据不符合规则");
        } catch (IllegalArgumentException ex) {
            throw invalid("正则表达式无效：" + ex.getMessage());
        }
    }

    /**
     * 解析并校验标准数据元规则的配置。
     */
    private Map<String, Object> checkedConfig(StandardElementRuleSaveReqVO request) {
        var element = requireElement(request.getElementId(), false);
        var template = template(request.getKind(), request.getTemplateId());
        Map<String, Object> input = new LinkedHashMap<>(request.getConfig() == null ? Map.of() : request.getConfig());
        if (JsonUtils.toJsonString(input).length() > 65536) {
            throw invalid("规则配置过长");
        }
        try {
            if ("CLEAN".equals(request.getKind())) {
                if (input.containsKey("columns")) {
                    throw invalid("数据元规则不接受外部字段绑定");
                }
                return cleanRegistry.normalizeConfig(template.getStrategyKey(), input);
            }
            String strategy = template.getStrategyKey();
            if ("NUMERIC_RANGE_VALIDATION".equals(strategy) && (input.get("minValue") instanceof Number || input.get(
                    "maxValue") instanceof Number)) {
                throw invalid("数值边界必须以十进制字符串提交");
            }
            Map<String, Object> parameters = new LinkedHashMap<>(input);
            parameters.remove("objectKey");
            parameters.remove("columnNames");
            Map<String, Object> config = QualityRuleConfigAdapter.normalize(strategy, parameters);
            List<String> columns = new ArrayList<>(List.of(element.getEngName()));
            var generator = auditRegistry.getGenerator(strategy);
            if (generator.getExecutionMode() == AuditSqlGenerator.ExecutionMode.METADATA || "RECORD_COMPLETENESS_VALIDATION".equals(
                    strategy)) {
                if (request.getWhereCondition() != null && !request.getWhereCondition().isBlank()) {
                    throw invalid("结构与整记录规则不接受记录过滤条件");
                }
                if (input.containsKey("columnNames") && (!(input.get(
                        "columnNames") instanceof List<?> selected) || !selected.isEmpty())) {
                    throw invalid("结构与整记录规则只接受空字段数组");
                }
                columns = List.of();
            }
            if (MODEL_BOUND_AUDITS.contains(strategy)) {
                var candidates = context(request.getElementId());
                if (candidates.referenceObjects().isEmpty()) {
                    throw invalid(candidates.message());
                }
                columns = boundColumns(strategy, input, config);
                if (Set.of("FOREIGN_KEY_VALIDATION", "CROSS_TABLE_CONSISTENCY").contains(strategy)) {
                    String sourceKey = Objects.toString(input.get("objectKey"), "");
                    var source = candidates.referenceObjects().stream()
                            .filter(row -> row.id().toString().equals(sourceKey)).findFirst()
                            .orElseThrow(() -> invalid("请选择可查询的评测模型"));
                    final List<String> selected = columns;
                    if (!source.fields().stream().map(StandardElementRuleContextRespVO.Field::value).toList()
                            .containsAll(selected)) {
                        throw invalid("评测字段不属于所选模型");
                    }
                    String key = Objects.toString(input.get("referenceObjectKey"), "");
                    String column = Objects.toString(input.get("refColumn"), "");
                    var target = candidates.referenceObjects().stream().filter(row -> row.id().toString().equals(key))
                            .findFirst().orElseThrow(() -> invalid("请选择可查询的引用模型"));
                    if (target.fields().stream().noneMatch(field -> field.value().equals(column))) {
                        throw invalid("引用字段不属于所选模型");
                    }
                    if (config.containsKey("comparisons")) {
                        for (var comparison : AuditConfigSupport.objects(config.get("comparisons"), "comparisons", 1,
                                32)) {
                            String right = AuditConfigSupport.text(comparison.get("rightColumn"), "rightColumn", 256);
                            if (target.fields().stream().noneMatch(field -> field.value().equals(right))) {
                                throw invalid("比较字段不属于引用模型");
                            }
                        }
                    }
                    parameters.put("refTable", "reference_value");
                    // 持久化模型身份，物理表名不接受客户端输入。
                    input.remove("refTable");
                } else {
                    final List<String> selected = columns;
                    String key = Objects.toString(input.get("objectKey"), "");
                    boolean sameModel = candidates.referenceObjects().stream()
                            .filter(row -> key.isEmpty() || row.id().toString().equals(key))
                            .anyMatch(row -> row.fields().stream().map(StandardElementRuleContextRespVO.Field::value)
                                    .toList().containsAll(selected));
                    if (!sameModel || columns.isEmpty()) {
                        throw invalid("条件字段必须来自同一可查询模型");
                    }
                }
            }
            if ("ENUM_VALIDATION".equals(strategy) && input.get("codeSetId") != null) {
                int id = Integer.parseInt(input.get("codeSetId").toString());
                var codeSet = codeSets.selectById(id);
                if (codeSet == null || !Objects.equals(codeSet.getStatus(), (short) 0)) {
                    throw invalid("代码表不存在或已停用");
                }
                var values = codeItems.selectTree(id, java.time.LocalDateTime.now(), false);
                if (values.isEmpty() || values.size() > 2000) {
                    throw invalid("代码表有效码值必须为 1 至 2000 项");
                }
                config.put("validValues", values.stream().map(StandardCodeItemDO::getValue).toList());
                input.put("validValues", config.get("validValues"));
                parameters.put("validValues", config.get("validValues"));
            }
            var ctx = QualityRuleConfigAdapter.context(runtime.products(), ProductTypeEnum.POSTGRESQL,
                    "standard_value", strategy, columns, parameters);
            // 保存只校验配置与授权绑定；真实元数据和整记录字段在执行阶段由服务端读取。
            generator.validateConfig(ctx);
            if ("CHARACTER_VALIDATION".equals(strategy)) {
                Pattern.compile(Objects.toString(ctx.getConfig().get("regex"), ""));
            }
            return input;
        } catch (IllegalArgumentException ex) {
            throw invalid(ex.getMessage());
        }
    }

    /**
     * 读取标准数据元规则已绑定的元数据字段。
     */
    private static List<String> boundColumns(String strategy, Map<String, Object> input, Map<String, Object> config) {
        var selected = new LinkedHashSet<String>();
        if (Set.of("TIME_ORDER_VALIDATION", "NUMERIC_RELATION_VALIDATION").contains(strategy)) {
            for (var condition : AuditConfigSupport.objects(config.get("conditions"), "conditions", 1, 32)) {
                selected.add(AuditConfigSupport.text(condition.get("leftField"), "leftField", 256));
                selected.add(AuditConfigSupport.text(condition.get("rightField"), "rightField", 256));
            }
        } else if (Set.of("CONDITIONAL_REQUIRED_VALIDATION", "STATE_CONSISTENCY_VALIDATION").contains(strategy)) {
            selected.add((String) config.get("conditionColumn"));
            if ("CONDITIONAL_REQUIRED_VALIDATION".equals(strategy)) {
                selected.addAll(AuditConfigSupport.texts(config.get("requiredColumns"), "requiredColumns", 1, 32));
            } else {
                for (var assertion : AuditConfigSupport.objects(config.get("assertions"), "assertions", 1, 32)) {
                    selected.add((String) assertion.get("column"));
                }
            }
        } else {
            int minimum = Set.of("MUTUAL_EXCLUSION_VALIDATION", "GROUP_FIELD_COMPLETENESS",
                    "COMPOSITE_UNIQUENESS_VALIDATION").contains(strategy) ? 2 : 1;
            int maximum = "FOREIGN_KEY_VALIDATION".equals(strategy) ? 1 : 128;
            List<String> fields = AuditConfigSupport.texts(input.get("columnNames"), "columnNames", minimum, maximum);
            selected.addAll(fields);
            if (selected.size() != fields.size()) {
                throw invalid("检查字段不能重复");
            }
            if ("CROSS_TABLE_CONSISTENCY".equals(strategy)) {
                if (config.containsKey("leftColumn")) {
                    selected.add(AuditConfigSupport.text(config.get("leftColumn"), "leftColumn", 256));
                }
                if (config.containsKey("comparisons")) {
                    for (var comparison : AuditConfigSupport.objects(config.get("comparisons"), "comparisons", 1, 32)) {
                        selected.add((String) comparison.get("leftColumn"));
                    }
                }
            }
        }
        return List.copyOf(selected);
    }

    /**
     * 读取并校验标准数据元规则关联模板。
     */
    private StandardElementRuleTemplateRespVO template(String kind, Long id) {
        return templates(kind).stream().filter(row -> row.getId().equals(id) && row.isEnabled()).findFirst()
                .orElseThrow(() -> invalid("规则模板不存在、停用或不适用于数据元"));
    }

    /**
     * 校验标准数据元规则的数据元，不满足约束时拒绝当前操作。
     */
    private StandardElementDO requireElement(Integer id, boolean lock) {
        var row = id == null ? null : lock ? elements.selectByIdForUpdate(id) : elements.selectById(id);
        if (row == null) {
            throw exception(STANDARD_ELEMENT_NOT_EXISTS);
        }
        return row;
    }

    /**
     * 校验标准数据元规则当前状态允许修改。
     */
    private void editable(Integer id) {
        var row = requireElement(id, true);
        if ("DEPRECATED".equals(row.getLifecycleStatus())) {
            throw exception(STANDARD_ELEMENT_LIFECYCLE_TRANSITION_INVALID);
        }
    }

    /**
     * 读取并校验必要的标准数据元规则上下文或记录。
     */
    private StandardElementRuleDO require(Integer elementId, Long id) {
        var row = id == null ? null : rules.selectById(id);
        if (row == null || !Objects.equals(elementId, row.getElementId())) {
            throw exception(STANDARD_ELEMENT_RULE_NOT_EXISTS);
        }
        return row;
    }

    /**
     * 校验标准数据元规则集合中不存在重复标识。
     */
    private void unique(StandardElementRuleSaveReqVO request, Long id) {
        if (rules.selectByOwner(request.getElementId()).stream().anyMatch(
                row -> row.getKind().equals(request.getKind()) && row.getName()
                        .equals(request.getName()) && !Objects.equals(row.getId(), id))) {
            throw exception(STANDARD_ELEMENT_RULE_DUPLICATE);
        }
    }

    /**
     * 将标准数据元规则记录转换为展示响应。
     */
    private List<StandardElementRuleRespVO> convert(List<StandardElementRuleDO> rows) {
        var users = catalog.userMap(rows.stream().map(StandardElementRuleDO::getCreator).toList(), List.of());
        return rows.stream().map(row -> {
            var result = BeanUtils.toBean(row, StandardElementRuleRespVO.class);
            result.setConfig(parse(row.getConfigJson()));
            fillTemplate(result,
                    templates(row.getKind()).stream().filter(item -> item.getId().equals(row.getTemplateId()))
                            .findFirst().orElse(null));
            result.setCreatorName(StandardCatalogService.name(users, row.getCreator()));
            return result;
        }).toList();
    }

    /**
     * 补充标准数据元规则的模板。
     */
    private static void fillTemplate(StandardElementRuleRespVO target, StandardElementRuleTemplateRespVO template) {
        if (template == null) {
            return;
        }
        target.setTemplateCode(template.getCode());
        target.setTemplateName(template.getName());
        target.setStrategyKey(template.getStrategyKey());
        target.setDimensionName(template.getDimensionName());
    }

    /**
     * 解析标准数据元规则持久化 JSON 证据或配置。
     */
    private static Map<String, Object> parse(String value) {
        return value == null || value.isBlank() ? Map.of() : JsonUtils.parseObject(value, new TypeReference<>() {
        });
    }

    /**
     * 构造标准数据元规则输入校验失败时的业务异常。
     */
    private static cn.iocoder.yudao.framework.common.exception.ServiceException invalid(String reason) {
        return exception(STANDARD_CATALOG_INVALID, reason);
    }
}
