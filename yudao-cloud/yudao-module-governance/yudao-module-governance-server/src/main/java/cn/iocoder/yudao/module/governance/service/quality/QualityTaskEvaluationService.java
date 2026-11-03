package cn.iocoder.yudao.module.governance.service.quality;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.quality.core.audit.*;
import cn.iocoder.yudao.framework.quality.core.row.*;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.framework.dbswitch.data.query.OperationBudget;
import cn.iocoder.yudao.framework.dbswitch.data.query.QueryRuntime;
import cn.iocoder.yudao.framework.dbswitch.driver.ConnectionBudget;

import java.time.Duration;

import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.task.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.*;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

import java.util.*;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/** 任务领域配置与组件之间的薄适配：配置全平台可见，源数据读取仍按当前用户授权。 */
@Service
public class QualityTaskEvaluationService {

    @Resource
    private QualityTaskEvaluationMapper evaluations;

    @Resource
    private QualityCheckObjectMapper objects;

    @Resource
    private QualityTaskObjectMapper relations;

    @Resource
    private AuditSqlRegistry registry;

    @Resource
    private DbSwitchRuntime runtime;

    @Resource
    private DatabaseApi databaseApi;

    @Resource
    private MasterDatabaseResolver resolver;

    @Resource
    private StandardQualityRuleBindingService standardBindings;

    @Resource
    private cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeSetMapper codeSets;

    @Resource
    private cn.iocoder.yudao.module.governance.dal.mysql.standardcodeitem.StandardCodeItemMapper codeItems;

    public record CatalogItem(String ruleCode, String strategyKey, String ruleName, String qualityDim,
                              String description, boolean enabled, boolean sampleSupported) {

    }

    public record SampleResult(boolean passed, String message) {

    }

    public record SpotResult(List<String> columns, List<Map<String, String>> problemRows,
                             List<Map<String, String>> normalRows) {

    }

    public record FrozenRows(List<String> columns, List<Map<String, String>> rows,
                             boolean truncated) {

    }

    private static final List<CatalogItem> CATALOG = List.of(
            item("107", "CHARACTER_VALIDATION", "字段字符类型校验", "ACCURACY", "检查字段值是否为期望的字符组成", true),
            item("109", "DATE_FORMAT_VALIDATION", "日期时间格式校验", "COMPLETENESS", "检查格式和真实日历日期是否合法",
                    false),
            item("113", "MODEL_STRUCTURE_VALIDATION", "逻辑模型一致性校验", "COMPLETENESS",
                    "将真实表结构与冻结的预期模型逐列比较，计数单位为字段", false),
            item("114", "COLUMN_METADATA_COMPLETENESS", "字段元信息完整性校验", "COMPLETENESS",
                    "检查真实字段元信息是否齐全，计数单位为字段", false),
            item("116", "NUMERIC_RELATION_VALIDATION", "数值字段逻辑关系校验", "COMPLETENESS",
                    "检查多个数值字段之间的业务关系", false),
            item("117", "CONDITIONAL_REQUIRED_VALIDATION", "状态字段依赖项校验", "COMPLETENESS",
                    "检查指定状态下相关字段是否必填", false),
            item("119", "SENSITIVE_MASK_VALIDATION", "敏感字段脱敏校验", "COMPLETENESS",
                    "检查手机号、证件、银行卡或自定义脱敏格式", false),
            item("203", "NOT_NULL_VALIDATION", "必填字段非空校验", "ACCURACY", "检查必填字段是否缺失", false),
            item("204", "PLACEHOLDER_VALIDATION", "非法占位值识别", "ACCURACY", "识别配置的非法占位值", false),
            item("206", "RECORD_COMPLETENESS_VALIDATION", "整记录缺失检查校验", "ACCURACY",
                    "检查所选非主键业务字段是否全部为空", false),
            item("207", "CONDITIONAL_REQUIRED_VALIDATION", "条件字段完整性校验", "ACCURACY",
                    "检查条件命中时相关字段是否填写", false),
            item("306", "NUMERIC_RANGE_VALIDATION", "数值字段范围校验", "CONSISTENCY",
                    "检查字段值是否落在业务定义的合理区间", false),
            item("307", "NUMERIC_RANGE_VALIDATION", "数值字段逻辑值合法校验", "CONSISTENCY",
                    "检查数值是否符合业务上下边界", false),
            item("308", "NUMERIC_OUTLIER_VALIDATION", "数值字段异常值识别", "CONSISTENCY",
                    "按边界、异常值集或四分位距识别异常值", false),
            item("309", "PLACEHOLDER_VALIDATION", "脏数据识别", "CONSISTENCY", "识别配置的无意义或错误占位值", false),
            item("310", "UNIQUE_VALIDATION", "单字段唯一性校验", "CONSISTENCY", "检查单个字段值是否唯一", false),
            item("311", "COMPOSITE_UNIQUENESS_VALIDATION", "多字段组合唯一性校验", "CONSISTENCY",
                    "检查字段组合在全表中是否唯一", false),
            item("403", "CROSS_TABLE_CONSISTENCY", "跨表字段值一致性校验", "NORMALIZATION",
                    "按关联键比较两表字段值，包含空值一致性", false),
            item("404", "FOREIGN_KEY_VALIDATION", "外键有效性校验", "NORMALIZATION", "检查外键是否存在于引用对象",
                    false),
            item("405", "STATE_CONSISTENCY_VALIDATION", "状态字段一致性校验", "NORMALIZATION",
                    "检查状态对应的字段断言是否成立", false),
            item("504", "TIME_ORDER_VALIDATION", "时间字段先后顺序校验", "TIMELINESS", "检查时间字段的前后顺序", false),
            item("205", "GROUP_FIELD_COMPLETENESS", "字段组完整性校验", "CONSISTENCY",
                    "检查逻辑上成对出现的字段是否齐全", false),
            item("115", "TIME_ORDER_VALIDATION", "时间字段顺序逻辑校验", "COMPLETENESS",
                    "检查多个时间字段之间的前后逻辑", false),
            item("118", "MUTUAL_EXCLUSION_VALIDATION", "互斥字段冲突校验", "COMPLETENESS",
                    "检查不能同时存在的字段值是否冲突", false),
            item("108", "LENGTH_VALIDATION", "字段长度范围校验", "COMPLETENESS", "检查字段长度是否在定义范围内", false),
            item("110", "DECIMAL_PRECISION_VALIDATION", "数值字段精度校验", "COMPLETENESS",
                    "检查小数位数是否符合业务定义", false),
            item("111", "ENUM_VALIDATION", "枚举值校验", "COMPLETENESS", "检查字段值是否在字典或枚举集中", false),
            item("112", "FOREIGN_KEY_VALIDATION", "主数据引用一致性校验", "COMPLETENESS",
                    "检查引用字段是否在主数据中存在", false));

    /**
     * 创建包含策略编码、名称、维度和样例的评估目录项。
     */
    private static CatalogItem item(String code, String key, String name, String dimension, String description,
            boolean sample) {
        return new CatalogItem(code, key, name, dimension, description, true, sample);
    }

    /**
     * 返回质量任务评估的目录。
     */
    public List<CatalogItem> catalog() {
        return CATALOG;
    }

    /**
     * 按策略键读取评估规则显示名称，未匹配时保留策略键。
     */
    public static String ruleTypeName(String strategyKey) {
        return CATALOG.stream().filter(item -> item.strategyKey().equals(strategyKey))
                .map(CatalogItem::ruleName).findFirst().orElse(strategyKey);
    }

    /**
     * 校验质量任务评估配置满足当前业务约束。
     */
    public void validate(QualityTaskSaveReqVO request) {
        if (request.getObjects() == null && request.getEvaluations() == null) {
            return;
        }
        if (request.getObjects() == null || request.getEvaluations() == null
                || request.getObjects().isEmpty() || request.getEvaluations().isEmpty()) {
            throw exception(QUALITY_RUN_NOT_READY);
        }
        Map<String, QualityTaskObjectSaveReqVO> byKey = new LinkedHashMap<>();
        for (var object : request.getObjects()) {
            if (byKey.putIfAbsent(object.getClientKey(), object) != null) {
                throw invalid("稽查对象键重复");
            }
            validateTable(object.getTableName());
        }
        Set<String> names = new HashSet<>();
        for (var evaluation : request.getEvaluations()) {
            if (!names.add(evaluation.getEvaluationName().trim())) {
                throw invalid("评测名称重复");
            }
            var object = byKey.get(evaluation.getObjectKey());
            if (object == null) {
                throw invalid("评测对象不属于本任务");
            }
            bindReference(evaluation, object, byKey);
            bindCodeSet(evaluation);
            validateEvaluation(evaluation);
        }
    }

    /** 调用方的任务事务拥有全部写入；共享检查对象发生修改时复制，避免影响其它任务。 */
    public void save(Long taskId, QualityTaskSaveReqVO request) {
        if (request.getObjects() == null) {
            return;
        }
        Map<Long, QualityCheckObjectDO> previous = loadObjects(taskId).stream()
                .collect(Collectors.toMap(QualityCheckObjectDO::getId, value -> value));
        Map<String, Long> ids = new LinkedHashMap<>();
        for (var input : request.getObjects()) {
            QualityCheckObjectDO value = BeanUtils.toBean(input, QualityCheckObjectDO.class);
            if (input.getId() != null && !previous.containsKey(input.getId())) {
                throw invalid("不能修改未关联到当前任务的稽查对象");
            }
            var old = previous.get(input.getId());
            if (old == null || !sameObject(old, value)) {
                value.setId(null);
                objects.insert(value);
            }
            ids.put(input.getClientKey(), value.getId());
        }
        relations.deleteByTaskId(taskId);
        ids.values().stream().distinct().forEach(id -> relations.insert(QualityTaskObjectDO.builder()
                .taskId(taskId).objectId(id).build()));
        Map<Long, QualityTaskEvaluationDO> prior = evaluations.selectByTaskId(taskId).stream()
                .collect(Collectors.toMap(QualityTaskEvaluationDO::getId, value -> value));
        Set<Long> retained = new HashSet<>();
        for (var input : request.getEvaluations()) {
            if (input.getId() != null && (!prior.containsKey(input.getId()) || !retained.add(input.getId()))) {
                throw invalid("评测编号不属于当前任务或重复");
            }
        }
        // 评测修改创建新版本；先释放活动名称，支持同名替换与名称互换，历史实例使用原冻结快照。
        evaluations.deleteByTaskId(taskId);
        for (var input : request.getEvaluations()) {
            QualityTaskEvaluationDO row = new QualityTaskEvaluationDO();
            row.setId(null);
            row.setEvaluationName(input.getEvaluationName());
            row.setRuleCode(input.getRuleCode());
            row.setStrategyKey(input.getStrategyKey());
            row.setQualityDim(input.getQualityDim());
            row.setSeverity(input.getSeverity());
            row.setStatus(input.getStatus());
            row.setDescription(StrUtil.nullToEmpty(input.getDescription()));
            row.setErrorExample(StrUtil.nullToEmpty(input.getErrorExample()));
            row.setRepairAdvice(StrUtil.nullToEmpty(input.getRepairAdvice()));
            row.setWhereClause(StrUtil.nullToEmpty(input.getWhereClause()));
            row.setTaskId(taskId);
            row.setObjectId(ids.get(input.getObjectKey()));
            row.setColumnNames(JsonUtils.toJsonString(input.getColumnNames()));
            Map<String, Object> config = new LinkedHashMap<>(input.getConfig());
            if (config.get("referenceObjectKey") != null) {
                config.put("referenceObjectKey",
                        String.valueOf(ids.get(String.valueOf(config.get("referenceObjectKey")))));
            }
            row.setConfig(JsonUtils.toJsonString(config));
            row.setStatus(input.getStatus() == null ? 0 : input.getStatus());
            row.setSeverity(StrUtil.blankToDefault(input.getSeverity(), "CRITICAL"));
            evaluations.insert(row);
        }
    }

    /**
     * 比较质量任务评估的对象是否一致。
     */
    private static boolean sameObject(QualityCheckObjectDO a, QualityCheckObjectDO b) {
        return Objects.equals(a.getObjectName(), b.getObjectName())
                && Objects.equals(a.getDatasourceConnId(), b.getDatasourceConnId())
                && Objects.equals(a.getTableName(), b.getTableName())
                && Objects.equals(a.getTableId(), b.getTableId())
                && Objects.equals(a.getFormatName(), b.getFormatName())
                && Objects.equals(a.getDatasourceType(), b.getDatasourceType())
                && Objects.equals(a.getDatasourceInstance(), b.getDatasourceInstance())
                && Objects.equals(a.getRemark(), b.getRemark());
    }

    /**
     * 将质量任务评估关联信息补充到返回结果。
     */
    public void fill(QualityTaskRespVO response) {
        var taskObjects = loadObjects(response.getId());
        Map<Long, String> sourceNames = new HashMap<>();
        if (!taskObjects.isEmpty()) {
            // 精简列表不读取解密密码；只复制名称，连接信息不会进入管理端响应。
            var sources = databaseApi.getDatabaseList(null).getCheckedData();
            if (sources != null) {
                sources.forEach(source -> sourceNames.put(source.getId(), source.getName()));
            }
        }
        response.setObjects(taskObjects.stream().map(object -> {
            var value = BeanUtils.toBean(object, QualityTaskObjectSaveReqVO.class);
            value.setClientKey(String.valueOf(object.getId()));
            value.setDatasourceConnName(sourceNames.get(object.getDatasourceConnId()));
            return value;
        }).toList());
        response.setEvaluations(evaluations.selectByTaskId(response.getId()).stream().map(this::toRequest).toList());
        if (!response.getEvaluations().isEmpty()) {
            response.setRuleCount(response.getEvaluations().size());
        }
    }

    /**
     * 将任务中启用的评估配置转换为可执行质量规则。
     */
    public List<QualityRuleDO> executionRules(Long taskId) {
        return evaluations.selectByTaskId(taskId).stream().filter(value -> Objects.equals(value.getStatus(), 0))
                .map(value -> {
                    var input = toRequest(value);
                    Map<String, Object> parameters = normalizedConfig(input);
                    if (input.getConfig().get("referenceObjectKey") != null) {
                        parameters.put("referenceObjectKey", input.getConfig().get("referenceObjectKey"));
                    }
                    parameters.put("columns", input.getColumnNames());
                    if (!input.getColumnNames().isEmpty()) {
                        parameters.put("column", input.getColumnNames().getFirst());
                    }
                    if (StrUtil.isNotBlank(input.getWhereClause())) {
                        parameters.put("where", input.getWhereClause());
                    }
                    return QualityRuleDO.builder().executionEvaluationId(value.getId())
                            .executionObjectId(value.getObjectId())
                            .ruleName(value.getEvaluationName()).ruleCode(value.getRuleCode())
                            .strategyKey(value.getStrategyKey())
                            .ruleType(value.getStrategyKey()).qualityDim(value.getQualityDim())
                            .severity(value.getSeverity())
                            .status(value.getStatus()).description(value.getDescription())
                            .example(value.getErrorExample())
                            .remark(value.getRepairAdvice()).collectIssueDetail(1)
                            .paramSchema(JsonUtils.toJsonString(parameters)).build();
                }).toList();
    }

    /**
     * 统计指定任务关联的评估配置数量。
     */
    public long count(Long taskId) {
        return evaluations.selectByTaskId(taskId).size();
    }

    /**
     * 校验目标后删除质量任务评估记录。
     */
    public void delete(Long taskId) {
        evaluations.deleteByTaskId(taskId);
    }

    /**
     * 加载对象。
     */
    private List<QualityCheckObjectDO> loadObjects(Long taskId) {
        List<Long> ids = relations.selectListByTaskId(taskId).stream().map(QualityTaskObjectDO::getObjectId).toList();
        return ids.isEmpty() ? List.of() : objects.selectBatchIds(ids);
    }

    /**
     * 转换质量任务评估的请求。
     */
    private QualityTaskEvaluationSaveReqVO toRequest(QualityTaskEvaluationDO row) {
        var input = new QualityTaskEvaluationSaveReqVO();
        input.setId(row.getId());
        input.setEvaluationName(row.getEvaluationName());
        input.setRuleCode(row.getRuleCode());
        input.setStrategyKey(row.getStrategyKey());
        input.setQualityDim(row.getQualityDim());
        input.setSeverity(row.getSeverity());
        input.setStatus(row.getStatus());
        input.setDescription(row.getDescription());
        input.setErrorExample(row.getErrorExample());
        input.setRepairAdvice(row.getRepairAdvice());
        input.setWhereClause(row.getWhereClause());
        input.setObjectKey(String.valueOf(row.getObjectId()));
        input.setColumnNames(JsonUtils.parseObject(row.getColumnNames(), new TypeReference<List<String>>() {
        }));
        input.setConfig(new LinkedHashMap<>(JsonUtils.parseMap(row.getConfig())));
        return input;
    }

    /**
     * 校验质量任务评估的评估，不满足约束时拒绝当前操作。
     */
    private void validateEvaluation(QualityTaskEvaluationSaveReqVO input) {
        CatalogItem catalog = CATALOG.stream().filter(item -> item.strategyKey().equals(input.getStrategyKey())
                        && item.ruleCode().equals(input.getRuleCode()))
                .findFirst().orElseThrow(() -> invalid("不支持的评测类型"));
        if (!catalog.ruleCode().equals(input.getRuleCode()) || !catalog.qualityDim().equals(input.getQualityDim())) {
            throw invalid("稽查规则编号或质量维度不匹配");
        }
        boolean metadata = registry.getGenerator(input.getStrategyKey())
                .getExecutionMode() == AuditSqlGenerator.ExecutionMode.METADATA;
        boolean entireRecord = "RECORD_COMPLETENESS_VALIDATION".equals(input.getStrategyKey());
        if (input.getColumnNames() == null || !metadata && !entireRecord && input.getColumnNames().isEmpty()
                || new HashSet<>(input.getColumnNames()).size() != input.getColumnNames().size()) {
            throw invalid("请选择不重复的检查字段");
        }
        if (metadata && StrUtil.isNotBlank(input.getWhereClause())) {
            throw invalid("结构与元信息规则不支持记录过滤条件");
        }
        if (entireRecord && (!input.getColumnNames().isEmpty() || StrUtil.isNotBlank(input.getWhereClause()))) {
            throw invalid("整记录检查自动选择全部非主键字段，不接受指定字段或过滤条件");
        }
        if ("MODEL_STRUCTURE_VALIDATION".equals(input.getStrategyKey()) && !input.getColumnNames().isEmpty()) {
            throw invalid("模型一致性必须检查整张表结构");
        }
        if ("NUMERIC_RANGE_VALIDATION".equals(input.getStrategyKey())) {
            for (String key : List.of("minValue", "maxValue")) {
                Object value = input.getConfig().get(key);
                if (value != null && !(value instanceof String)) {
                    throw invalid("数值边界必须以十进制字符串提交");
                }
            }
        }
        if (input.getConfig().get("validValues") instanceof List<?> values && values.size() > 2000) {
            throw invalid("枚举值不能超过 2000 项");
        }
        try {
            context(ProductTypeEnum.POSTGRESQL, "quality_validation", input);
            if (StrUtil.isNotBlank(input.getWhereClause())) {
                try {
                    RestrictedSelectGuard.restrictProjectionWithRowFilters(ProductTypeEnum.POSTGRESQL,
                            "SELECT * FROM public.quality_validation", "public", "quality_validation",
                            input.getColumnNames(),
                            new HashSet<>(input.getColumnNames()), List.of(input.getWhereClause()));
                } catch (IllegalArgumentException | UnsupportedOperationException failure) {
                    throw invalid("Where 条件仅支持所选检查字段的基本比较和布尔组合，不支持函数、参数或子查询");
                }
            }
            var generator = registry.getGenerator(input.getStrategyKey());
            var validationContext = context(ProductTypeEnum.POSTGRESQL, "quality_validation", input);
            generator.validateConfig(validationContext);
            if (!metadata && !entireRecord) {
                generator.buildCountSql(validationContext);
            }
        } catch (IllegalArgumentException | UnsupportedOperationException failure) {
            throw invalid("规则参数或 Where 条件不合法");
        }
    }

    /**
     * 校验质量任务评估的表，不满足约束时拒绝当前操作。
     */
    private static void validateTable(String table) {
        try {
            RestrictedSelectGuard.parseTableReference(ProductTypeEnum.POSTGRESQL, table, "public");
        } catch (RuntimeException invalid) {
            throw invalid("表名称必须是单个表或 schema.table");
        }
    }

    /**
     * 绑定引用。
     */
    private static void bindReference(QualityTaskEvaluationSaveReqVO input, QualityTaskObjectSaveReqVO object,
            Map<String, QualityTaskObjectSaveReqVO> byKey) {
        if (!referencesTable(input.getStrategyKey())) {
            return;
        }
        Object referenceKey = input.getConfig().get("referenceObjectKey");
        var reference = referenceKey == null ? null : byKey.get(String.valueOf(referenceKey));
        if (reference == null || !Objects.equals(reference.getDatasourceConnId(), object.getDatasourceConnId())) {
            throw invalid("引用对象必须属于本任务且使用同一数据源");
        }
        Map<String, Object> config = new LinkedHashMap<>(input.getConfig());
        config.put("refTable", reference.getTableName());
        input.setConfig(config);
    }

    /**
     * 判断策略是否为需要引用其他表的外键或跨表检查。
     */
    private static boolean referencesTable(String strategy) {
        return "FOREIGN_KEY_VALIDATION".equals(strategy) || "CROSS_TABLE_CONSISTENCY".equals(strategy);
    }

    /** 码表的全部当前有效值在保存或抽查时冻结，不能将页面预览当作完整值域。 */
    private void bindCodeSet(QualityTaskEvaluationSaveReqVO input) {
        if (!"ENUM_VALIDATION".equals(input.getStrategyKey()) || input.getConfig().get("codeSetId") == null) {
            return;
        }
        Integer codeSetId;
        try {
            codeSetId = Integer.valueOf(String.valueOf(input.getConfig().get("codeSetId")));
        } catch (NumberFormatException failure) {
            throw invalid("码表编号不合法");
        }
        var codeSet = codeSets.selectById(codeSetId);
        if (codeSet == null || !Objects.equals(codeSet.getStatus(), (short) 0)) {
            throw invalid("码表不存在或未启用");
        }
        var items = codeItems.selectTree(codeSetId, java.time.LocalDateTime.now(), false);
        if (items.isEmpty() || items.size() > 2000) {
            throw invalid("码表有效值必须在 1 到 2000 项之间");
        }
        Map<String, Object> config = new LinkedHashMap<>(input.getConfig());
        config.put("validValues", items.stream().map(value -> value.getValue()).toList());
        config.put("valueLabels", items.stream().map(value -> Map.of("value", value.getValue(),
                "label", StrUtil.blankToDefault(value.getLabel(), value.getValue()))).toList());
        input.setConfig(config);
    }

    /**
     * 读取质量任务评估配置的样例数据。
     */
    public SampleResult sample(QualityTaskProbeReqVO request) {
        var evaluation = request.getEvaluation();
        if (!"CHARACTER_VALIDATION".equals(evaluation.getStrategyKey())) {
            throw invalid("此规则不支持独立样例监测");
        }
        if (request.getSampleData() == null || request.getSampleData().isEmpty()) {
            throw invalid("请添加样例数据");
        }
        Map<String, Object> config = normalizedConfig(evaluation);
        Map<String, Object> rowConfig = new LinkedHashMap<>();
        rowConfig.put("regex", config.get("regex"));
        rowConfig.put("ignoreNullValue", config.getOrDefault("ignoreNullValue", true));
        try {
            var plan = new QualityRowEvaluator().compile(
                    List.of(new QualityRowRule("sample", "REGEX_VALIDATION", "value", rowConfig, "ERROR")));
            boolean passed = plan.evaluate(Map.of("value", request.getSampleData())).isEmpty();
            return new SampleResult(passed, passed ? "数据监测成功" : "数据不符合规则");
        } catch (IllegalArgumentException | QualityRowEvaluationException failure) {
            throw invalid("正则格式不合法或超出样例监测限额");
        }
    }

    /**
     * 按质量任务评估配置执行样例抽查。
     */
    public SpotResult spotCheck(QualityTaskProbeReqVO request) {
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        if (actor == null || request.getObject() == null) {
            throw exception(QUALITY_ISSUE_ACCESS_FORBIDDEN);
        }
        var object = request.getObject();
        Map<String, QualityTaskObjectSaveReqVO> byKey = new LinkedHashMap<>();
        if (request.getObjects() != null) {
            request.getObjects().forEach(value -> byKey.put(value.getClientKey(), value));
        }
        if (referencesTable(request.getEvaluation().getStrategyKey())) {
            bindReference(request.getEvaluation(), object, byKey);
        }
        bindCodeSet(request.getEvaluation());
        validateEvaluation(request.getEvaluation());
        var connection = resolver.resolve(object.getDatasourceConnId(), QUALITY_CHECK_OBJECT_NOT_EXISTS);
        prepareReferenceTable(connection, object, request.getEvaluation(), byKey);
        var context = context(connection.getProductType(),
                physicalTable(connection.getProductType(), object.getTableName(), connection.getSchema()),
                request.getEvaluation());
        var generator = registry.getGenerator(request.getEvaluation().getStrategyKey());
        int limit = request.getLimit() == null ? 100 : Math.max(1, Math.min(100, request.getLimit()));
        var problems = generatedRows(connection, actor, generator, context, true, limit);
        var normal = generatedRows(connection, actor, generator, context, false, limit);
        return new SpotResult(problems.columns(), problems.rows(), normal.rows());
    }

    /** 只使用冻结模型重建封闭规则 SQL；历史 locationSql 从不参与执行。 */
    public FrozenRows readFrozenRows(QualityTaskExecutor.GateDefinition definition, QualityAuditLogDO audit,
            Long actor, int limit) {
        if (actor == null || audit.getEvaluationId() == null || definition == null || definition.getRules() == null
                || definition.getObjects() == null || definition.getEtlBatchScope() != null) {
            throw invalid("此历史记录缺少可核验的冻结评测，无法安全回源");
        }
        var matches = definition.getRules().stream()
                .filter(value -> Objects.equals(value.getExecutionEvaluationId(), audit.getEvaluationId())
                        && Objects.equals(value.getExecutionObjectId(), audit.getCheckObjectId())).toList();
        if (matches.size() != 1) {
            throw invalid("冻结评测与稽查记录不一致");
        }
        var rule = matches.getFirst();
        Map<String, QualityTaskObjectSaveReqVO> byKey = new LinkedHashMap<>();
        for (var frozen : definition.getObjects()) {
            var value = BeanUtils.toBean(frozen, QualityTaskObjectSaveReqVO.class);
            value.setClientKey(String.valueOf(frozen.getId()));
            if (byKey.putIfAbsent(value.getClientKey(), value) != null) {
                throw invalid("冻结对象身份重复");
            }
        }
        var object = byKey.get(String.valueOf(rule.getExecutionObjectId()));
        if (object == null) {
            throw invalid("冻结评测对象不存在");
        }
        var input = frozenInput(rule);
        bindReference(input, object, byKey);
        validateEvaluation(input);
        var connection = resolver.resolve(object.getDatasourceConnId(), QUALITY_CHECK_OBJECT_NOT_EXISTS);
        prepareReferenceTable(connection, object, input, byKey);
        var context = context(connection.getProductType(),
                physicalTable(connection.getProductType(), object.getTableName(), connection.getSchema()), input);
        int maximum = Math.max(1, Math.min(1000, limit));
        return generatedRows(connection, actor,
                registry.getGenerator(input.getStrategyKey()), context, true, maximum);
    }

    /**
     * 从冻结规则还原评估输入，分离字段、筛选条件和策略参数。
     */
    private static QualityTaskEvaluationSaveReqVO frozenInput(QualityRuleDO rule) {
        var input = new QualityTaskEvaluationSaveReqVO();
        input.setRuleCode(rule.getRuleCode());
        input.setStrategyKey(rule.getStrategyKey());
        input.setQualityDim(rule.getQualityDim());
        Map<String, Object> parameters = new LinkedHashMap<>(JsonUtils.parseMap(rule.getParamSchema()));
        Object columns = parameters.remove("columns");
        input.setColumnNames(
                columns instanceof List<?> values ? values.stream().map(String::valueOf).toList() : List.of());
        parameters.remove("column");
        Object where = parameters.remove("where");
        input.setWhereClause(where == null ? null : String.valueOf(where));
        if ("CHARACTER_VALIDATION".equals(rule.getStrategyKey())) {
            parameters.put("useRegex", true);
        }
        input.setConfig(parameters);
        return input;
    }

    /** 将引用表固定为当前连接下的精确物理名称，生成器仍只使用已校验规则。 */
    private void prepareReferenceTable(MasterDatabaseResolver.ConnectionInfo connection,
            QualityTaskObjectSaveReqVO object, QualityTaskEvaluationSaveReqVO input,
            Map<String, QualityTaskObjectSaveReqVO> byKey) {
        if (referencesTable(input.getStrategyKey())) {
            bindReference(input, object, byKey);
            var reference = byKey.get(String.valueOf(input.getConfig().get("referenceObjectKey")));
            var config = new LinkedHashMap<>(input.getConfig());
            config.put("refTable",
                    physicalTable(connection.getProductType(), reference.getTableName(), connection.getSchema()));
            input.setConfig(config);
        }
    }

    /** SQL 仅来自封闭生成器；结构检查只返回列名及结构问题，不返回源表记录。 */
    private FrozenRows generatedRows(MasterDatabaseResolver.ConnectionInfo connection, Long actor,
            AuditSqlGenerator generator, AuditRuleContext context, boolean errors, int limit) {
        String scope = "quality-generated:" + actor + ":" + UUID.randomUUID();
        var budget = new OperationBudget(Duration.ofSeconds(15), Duration.ofSeconds(15), Duration.ofSeconds(2),
                limit + 4L, 16L * 1024 * 1024, 1024L * 1024, Math.min(1000, limit + 1)); // 包括同连接模式验证行
        var operation = runtime.queries()
                .register(scope, "quality-read:" + actor, UUID.randomUUID().toString(), UUID.randomUUID(), budget);
        QueryRuntime.Page page;
        try {
            operation.bindWorker();
            try (var session = operation.openSession(resolver.queryConnectionSpec(connection),
                    ConnectionBudget.Role.WORKLOAD)) {
                AuditSqlDialect.validateConnection(session.connection(), connection.getProductType());
                populateMetadata(session.connection(), context, generator);
                String generatedSql = errors ? generator.buildErrorDataSql(context, limit + 1)
                        : generator.buildNormalDataSql(context, limit + 1);
                try (var cursor = session.query(scope, generatedSql, List.of())) {
                    page = cursor.fetch(scope, cursor.id(), Math.min(1000, limit + 1));
                    if (!page.exhausted() && page.rows().size() < limit + 1) {
                        var probe = cursor.fetch(scope, cursor.id(), limit + 1 - page.rows().size());
                        var combined = new ArrayList<>(page.rows());
                        combined.addAll(probe.rows());
                        page = new QueryRuntime.Page(page.columns(), List.copyOf(combined), probe.exhausted());
                    }
                }
            }
        } catch (java.sql.SQLException failure) {
            throw invalid("规则查询执行失败，请核验数据类型与规则配置");
        } finally {
            operation.workerExited();
            if (operation.awaitTermination() != QueryRuntime.Termination.CONFIRMED) {
                throw invalid("规则查询资源退出尚未确认，请稍后重试");
            }
        }
        List<String> columns = page.columns().stream().map(column -> column.getFieldName()).toList();
        if (new HashSet<>(columns).size() != columns.size()) {
            throw invalid("生成查询返回重复列");
        }
        List<Map<String, Object>> raw = page.rows().stream().map(row -> {
            Map<String, Object> values = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                values.put(columns.get(i), row.get(i));
            }
            return values;
        }).toList();
        List<Map<String, String>> rows = raw.stream().limit(limit).map(row -> {
            Map<String, String> values = new LinkedHashMap<>();
            row.forEach((key, value) -> values.put(key, value == null ? null : String.valueOf(value)));
            return values;
        }).toList();
        return new FrozenRows(columns, rows, page.rows().size() > limit || !page.exhausted());
    }

    /**
     * 根据质量任务评估输入推荐可用规则或配置。
     */
    public List<QualityTaskEvaluationSaveReqVO> suggest(List<QualityTaskObjectSaveReqVO> requested) {
        if (requested == null || requested.size() > 100) {
            throw invalid("稽查对象数量超出限制");
        }
        Map<Long, String> keys = new LinkedHashMap<>();
        List<QualityCheckObjectDO> definitions = new ArrayList<>();
        for (int i = 0; i < requested.size(); i++) {
            var object = BeanUtils.toBean(requested.get(i), QualityCheckObjectDO.class);
            object.setId((long) i + 1);
            var route = resolver.resolve(object.getDatasourceConnId(), QUALITY_CHECK_OBJECT_NOT_EXISTS);
            try {
                object.setTableName(physicalTable(route.getProductType(), object.getTableName(), route.getSchema()));
                var relation = RestrictedSelectGuard.parseTableReference(route.getProductType(), object.getTableName(),
                        route.getSchema());
                object.setTableId(standardBindings.findSuggestionTableId(object.getDatasourceConnId(),
                        relation.getSchemaName(), relation.getTableName()));
            } catch (IllegalArgumentException | UnsupportedOperationException ex) {
                throw invalid("建议规则需要精确 schema/table，请检查数据源默认 schema 或显式限定表名");
            }
            keys.put(object.getId(), requested.get(i).getClientKey());
            definitions.add(object);
        }
        List<QualityTaskEvaluationSaveReqVO> result = new ArrayList<>();
        for (var rule : standardBindings.resolve(new QualityTaskDO(), definitions, List.of())) {
            var catalog = CATALOG.stream().filter(item -> item.strategyKey().equals(rule.getStrategyKey())).findFirst();
            if (catalog.isEmpty()) {
                continue;
            }
            Map<String, Object> parameters = new LinkedHashMap<>(JsonUtils.parseMap(rule.getParamSchema()));
            var evaluation = new QualityTaskEvaluationSaveReqVO();
            evaluation.setObjectKey(keys.get(rule.getExecutionObjectId()));
            evaluation.setEvaluationName(rule.getRuleName());
            evaluation.setRuleCode(catalog.get().ruleCode());
            evaluation.setStrategyKey(rule.getStrategyKey());
            evaluation.setQualityDim(catalog.get().qualityDim());
            evaluation.setSeverity("CRITICAL");
            evaluation.setStatus(0);
            evaluation.setColumnNames(List.of(String.valueOf(parameters.remove("column"))));
            evaluation.setConfig(parameters);
            evaluation.setDescription(rule.getDescription());
            result.add(evaluation);
        }
        return result;
    }

    /** 将新任务的实际 schema 固定进快照，避免连接默认 schema 改变污染后续执行和报告定位。 */
    public void freezeObjects(QualityTaskExecutor.GateDefinition definition) {
        if (definition.getRules().stream().noneMatch(rule -> rule.getExecutionEvaluationId() != null)) {
            return;
        }
        for (var object : definition.getObjects()) {
            var route = resolver.resolve(object.getDatasourceConnId(), QUALITY_CHECK_OBJECT_NOT_EXISTS);
            object.setTableName(physicalTable(route.getProductType(), object.getTableName(), route.getSchema()));
            object.setDatasourceConnName(route.getName());
        }
        for (var rule : definition.getRules()) {
            if (rule.getExecutionEvaluationId() == null || !referencesTable(rule.getStrategyKey())) {
                continue;
            }
            Map<String, Object> params = new LinkedHashMap<>(JsonUtils.parseMap(rule.getParamSchema()));
            var reference = definition.getObjects().stream().filter(object -> String.valueOf(object.getId())
                            .equals(String.valueOf(params.get("referenceObjectKey")))).findFirst()
                    .orElseThrow(() -> invalid("引用对象不属于冻结任务"));
            params.put("refTable", reference.getTableName());
            rule.setParamSchema(JsonUtils.toJsonString(params));
        }
    }

    /**
     * 按数据库产品解析并正确引用物理表名称。
     */
    private static String physicalTable(ProductTypeEnum product, String table, String schema) {
        var relation = RestrictedSelectGuard.parseTableReference(product, table, schema);
        return relation.getSchemaName() == null ? RestrictedSelectGuard.quotePhysicalIdentifier(product,
                relation.getTableName())
                : RestrictedSelectGuard.qualifiedTableName(product, relation.getSchemaName(), relation.getTableName());
    }

    /** 仅将确定的领域配置交给质量组件，标识符由 DB Switch 统一引用。 */
    public AuditRuleContext context(ProductTypeEnum product, String table, QualityTaskEvaluationSaveReqVO input) {
        AuditRuleContext context = QualityRuleConfigAdapter.context(runtime.products(), product, table,
                input.getStrategyKey(), input.getColumnNames(), input.getConfig());
        context.setWhereClause(input.getWhereClause());
        return context;
    }

    /** 使用本轮已授权连接读取真实结构，永不接受客户端传入的实际结构快照。 */
    public void populateMetadata(java.sql.Connection connection, AuditRuleContext context, AuditSqlGenerator generator)
            throws java.sql.SQLException {
        boolean entireRecord = "RECORD_COMPLETENESS_VALIDATION".equals(generator.getStrategyKey());
        if (generator.getExecutionMode() != AuditSqlGenerator.ExecutionMode.METADATA && !entireRecord) {
            return;
        }
        var relation = RestrictedSelectGuard.parseTableReference(context.getProductType(), context.getTableName(),
                null);
        var columns = runtime.products().metadata(context.getProductType()).queryTableColumnMeta(connection,
                relation.getSchemaName(), relation.getTableName());
        if (entireRecord) {
            var keys = runtime.products().metadata(context.getProductType()).queryTablePrimaryKeys(connection,
                    relation.getSchemaName(), relation.getTableName());
            var nonKeys = columns.stream()
                    .map(cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription::getFieldName)
                    .filter(column -> !keys.contains(column))
                    .map(column -> RestrictedSelectGuard.quotePhysicalIdentifier(context.getProductType(), column))
                    .toList();
            if (nonKeys.isEmpty()) {
                throw new IllegalArgumentException("整记录检查需要至少一个非主键业务字段");
            }
            context.setColumns(nonKeys);
            context.setColumn(nonKeys.getFirst());
            return;
        }
        Set<String> selected = context.resolveColumns().stream().collect(Collectors.toSet());
        context.setColumnSnapshots(columns.stream().filter(column -> selected.isEmpty()
                        || selected.contains(
                        RestrictedSelectGuard.quotePhysicalIdentifier(context.getProductType(), column.getFieldName())))
                .map(column -> new AuditRuleContext.ColumnSnapshot(column.getFieldName(), column.getFieldTypeName(),
                        column.isNullable(), column.getLength(), column.getPrecisionSize(), column.getScaleSize(),
                        column.getRemarks()))
                .toList());
        if (!selected.isEmpty() && context.getColumnSnapshots().size() != selected.size()) {
            throw new IllegalArgumentException("所选检查字段不在真实表结构中");
        }
    }

    /**
     * 按评估策略规范化配置参数。
     */
    public static Map<String, Object> normalizedConfig(QualityTaskEvaluationSaveReqVO input) {
        return QualityRuleConfigAdapter.normalize(input.getStrategyKey(), input.getConfig());
    }

    /**
     * 构造质量任务评估输入校验失败时的业务异常。
     */
    private static cn.iocoder.yudao.framework.common.exception.ServiceException invalid(String reason) {
        return exception(AUDIT_RULE_CONFIG_INVALID, reason);
    }
}
