package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTableVersionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable.FillTableColumnDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable.FillTableDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillTableVersionMapper;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable.FillTableRuleDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.filltable.FillTableMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.filltable.FillTableRuleMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillMergeStrategyEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillWorkflowModeEnum;
import cn.iocoder.yudao.module.system.api.dict.DictDataApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/**
 * 在线填报-模板版本 Service 实现类
 *
 * 填报表发布时固化配置，供采集计划和填报任务使用。
 *
 * @author Li
 */
@Service
@Validated
public class FillTableVersionServiceImpl implements FillTableVersionService {

    /** 当前锁定的前端表格引擎版本，随依赖升级同步修改 */
    private static final String UNIVER_SDK_VERSION = "0.25.1";

    private static final String ADAPTER_PROTOCOL_VERSION = "1.0";

    private static final String SNAPSHOT_SCHEMA_VERSION = "1.0";

    private static final Integer VERSION_STATUS_RELEASED = 1;

    private static final Integer VERSION_STATUS_DISABLED = 2;

    private static final Integer TABLE_STATUS_RELEASED = 1;

    @Resource
    private FillTableMapper fillTableMapper;

    @Resource
    private DictDataApi dictDataApi;

    @Resource
    private FillTableVersionMapper versionMapper;

    @Resource
    private FillTableRuleMapper ruleMapper;

    @Resource
    private FillReferenceOptionLoader referenceOptionLoader;

    @Override
    public FillTableVersionDO buildReleaseSnapshot(FillTableDO table, List<FillTableColumnDO> columns) {
        columns.forEach(column -> {
            if (StrUtil.isBlank(column.getFieldCode())) {
                throw exception(FILL_TABLE_FIELD_CODE_MISSING, column.getCnName());
            }
            String keyError = generatedKeyError(column.getPk(), column.getPkType(),
                    column.getFieldType(), column.getFieldLength());
            if (keyError != null) {
                throw exception(FILL_TABLE_COLUMN_RULE_INVALID, "字段【" + column.getCnName() + "】" + keyError);
            }
        });

        // 未配置时按组织和期次替换，支持在原填报范围内更正提交
        FillMergeStrategyEnum strategy = resolveMergeStrategy(table.getMergeStrategy());
        String mergeStrategy = strategy.getStrategy();
        String businessKeyFields = strategy == FillMergeStrategyEnum.UPSERT
                ? validateBusinessKeys(table.getBusinessKeyFields(), columns) : null;
        String formulaSchemaJson = JsonUtils.toJsonString(buildFormulaSchema(columns));
        validateControls(columns);
        Map<String, FillReferenceOptionLoader.Options> refs = loadReferenceOptions(columns);
        String fieldSchemaJson = JsonUtils.toJsonString(buildFieldSchema(columns, refs));
        String targetMappingJson = JsonUtils.toJsonString(buildTargetMapping(table, columns));
        List<FillTableRuleDO> rules = table.getId() == null || ruleMapper == null
                ? List.of() : ruleMapper.selectListByTableId(table.getId());
        String validationSchemaJson = JsonUtils.toJsonString(buildValidationSchema(columns, rules, refs));
        String layoutSnapshotJson = JsonUtils.toJsonString(buildLayoutSnapshot(table, columns));
        String rollupSchemaJson = buildRollupSchema(table, columns);
        String contentHash = DigestUtil.sha256Hex(fieldSchemaJson + "|" + targetMappingJson + "|"
                + validationSchemaJson + "|" + mergeStrategy + "|" + StrUtil.nullToEmpty(businessKeyFields)
                + (FORMULA_SCHEMA_EMPTY.equals(formulaSchemaJson) ? "" : "|" + formulaSchemaJson)
                + (LAYOUT_SNAPSHOT_EMPTY.equals(layoutSnapshotJson) ? "" : "|" + layoutSnapshotJson)
                + (rollupSchemaJson == null ? "" : "|" + rollupSchemaJson));

        return FillTableVersionDO.builder()
                .tableId(table.getId())
                .name(table.getTableCnName())
                .status(VERSION_STATUS_RELEASED)
                .fieldSchema(fieldSchemaJson)
                .formulaSchema(formulaSchemaJson)
                .layoutSnapshot(layoutSnapshotJson)
                .validationSchema(validationSchemaJson)
                .targetMapping(targetMappingJson)
                .mergeStrategy(mergeStrategy)
                .businessKeyFields(businessKeyFields)
                .rollupSchema(rollupSchemaJson)
                .contentHash(contentHash)
                .univerSdkVersion(UNIVER_SDK_VERSION)
                .adapterProtocolVersion(ADAPTER_PROTOCOL_VERSION)
                .snapshotSchemaVersion(SNAPSHOT_SCHEMA_VERSION)
                .build();
    }

    /** 参与 SUM/MAX/MIN 的字段类型：小数、整数、长整数 */
    private static final java.util.Set<Integer> ROLLUP_NUMERIC_TYPES = java.util.Set.of(
            FillValueConverter.TYPE_DECIMAL, FillValueConverter.TYPE_INTEGER, FillValueConverter.TYPE_LONG);

    /**
     * 多级汇总规则：明细汇集不固化（返回 null，保持历史版本的内容摘要不变）；分组合计校验分组字段与计算方式后固化。
     *
     * @param table 填报表
     * @param columns 字段
     * @return 规则 JSON，明细汇集为 null
     */
    static String buildRollupSchema(FillTableDO table, List<FillTableColumnDO> columns) {
        if (!"GROUP".equals(table.getRollupMode())) {
            return null;
        }
        Map<String, FillTableColumnDO> byCode = new LinkedHashMap<>();
        columns.forEach(column -> byCode.put(column.getFieldCode(), column));
        List<String> groupFields = StrUtil.splitTrim(StrUtil.nullToEmpty(table.getRollupGroupFields()), ',')
                .stream().distinct().toList();
        if (groupFields.isEmpty()) {
            throw exception(FILL_ROLLUP_CONFIG_INVALID, "分组合计需要至少一个分组字段");
        }
        for (String code : groupFields) {
            FillTableColumnDO column = byCode.get(code);
            if (column == null || Integer.valueOf(1).equals(column.getFillHide())) {
                throw exception(FILL_ROLLUP_CONFIG_INVALID, "分组字段 " + code + " 不存在或已隐藏");
            }
        }
        Map<String, String> funcs = new LinkedHashMap<>();
        for (FillTableColumnDO column : columns) {
            String func = StrUtil.trimToEmpty(column.getRollupFunc());
            if (func.isEmpty()) {
                continue;
            }
            if (groupFields.contains(column.getFieldCode())) {
                throw exception(FILL_ROLLUP_CONFIG_INVALID, "分组字段【" + column.getCnName() + "】不需要设置计算方式");
            }
            if (!FillHierarchyService.GROUP_FUNCS.contains(func)) {
                throw exception(FILL_ROLLUP_CONFIG_INVALID, "字段【" + column.getCnName() + "】的计算方式 " + func + " 不支持");
            }
            if (("SUM".equals(func) || "MAX".equals(func) || "MIN".equals(func))
                    && !ROLLUP_NUMERIC_TYPES.contains(column.getFieldType())) {
                throw exception(FILL_ROLLUP_CONFIG_INVALID, "字段【" + column.getCnName() + "】不是数值，不能" + func);
            }
            funcs.put(column.getFieldCode(), func);
        }
        if (funcs.isEmpty()) {
            throw exception(FILL_ROLLUP_CONFIG_INVALID, "分组合计需要至少一个字段设置计算方式");
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("mode", "GROUP");
        schema.put("groupFields", groupFields);
        schema.put("funcs", funcs);
        return JsonUtils.toJsonString(schema);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void completeTableRelease(FillTableVersionDO snapshot) {
        Long tableId = snapshot.getTableId();
        FillTableDO table = fillTableMapper.selectByIdForUpdate(tableId);
        if (table == null) {
            throw exception(FILL_TABLE_NOT_EXISTS);
        }
        if (TABLE_STATUS_RELEASED.equals(table.getStatus()) && table.getCurrentVersionId() != null) {
            return;
        }
        FillTableVersionDO latest = versionMapper.selectLatestByTableId(tableId);
        LocalDateTime releaseTime = LocalDateTime.now();
        Long releaseUserId = SecurityFrameworkUtils.getLoginUserId();
        Long versionId;
        if (latest != null && VERSION_STATUS_RELEASED.equals(latest.getStatus())
                && Objects.equals(latest.getContentHash(), snapshot.getContentHash())
                && Objects.equals(latest.getName(), snapshot.getName())
                && Objects.equals(latest.getFormulaSchema(), snapshot.getFormulaSchema())
                && Objects.equals(latest.getUniverSdkVersion(), snapshot.getUniverSdkVersion())
                && Objects.equals(latest.getAdapterProtocolVersion(), snapshot.getAdapterProtocolVersion())
                && Objects.equals(latest.getSnapshotSchemaVersion(), snapshot.getSnapshotSchemaVersion())) {
            versionId = latest.getId();
        } else {
            snapshot.setVersionNo(latest == null ? 1 : latest.getVersionNo() + 1);
            snapshot.setReleaseTime(releaseTime);
            snapshot.setReleaseUserId(releaseUserId);
            versionMapper.insert(snapshot);
            versionId = snapshot.getId();
        }

        fillTableMapper.updateById(FillTableDO.builder()
                .id(tableId)
                .status(TABLE_STATUS_RELEASED)
                .releaseTime(releaseTime)
                .releaseUserId(releaseUserId)
                .currentVersionId(versionId)
                .workflowMode(FillWorkflowModeEnum.APPROVAL_FLOW.getMode())
                .build());
    }

    /**
     * 获得模板版本（不存在抛异常）
     *
     * @param id 记录编号
     * @return 填报表版本
     */
    @Override
    public FillTableVersionDO getTableVersion(Long id) {
        FillTableVersionDO version = versionMapper.selectById(id);
        if (version == null) {
            throw exception(FILL_TABLE_VERSION_NOT_EXISTS);
        }
        return version;
    }

    /**
     * 获得模板版本，并校验可被新任务引用（不存在或已停用抛异常）
     *
     * @param id 记录编号
     * @return 填报表版本
     */
    @Override
    public FillTableVersionDO getActiveTableVersion(Long id) {
        FillTableVersionDO version = getTableVersion(id);
        if (VERSION_STATUS_DISABLED.equals(version.getStatus())) {
            throw exception(FILL_TABLE_VERSION_DISABLED);
        }
        return version;
    }

    /**
     * 获得填报表的版本列表（按版本号倒序）
     *
     * @param tableId 表编号
     * @return 填报表的版本列表（按版本号倒序）结果集合
     */
    @Override
    public List<FillTableVersionDO> getTableVersionListByTableId(Long tableId) {
        return versionMapper.selectListByTableId(tableId);
    }

    // ---------------------------------------------------------------- schema 构建

    /**
     * 构造字段结构。
     *
     * @param columns 列集合
     * @return 构造字段结构结果集合
     */
    private List<Map<String, Object>> buildFieldSchema(List<FillTableColumnDO> columns,
                                                       Map<String, FillReferenceOptionLoader.Options> refs) {
        List<Map<String, Object>> fields = new ArrayList<>(columns.size());
        for (FillTableColumnDO column : columns) {
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("fieldCode", column.getFieldCode());
            field.put("name", column.getCnName());
            field.put("targetColumnName", column.getName());
            field.put("fieldType", column.getFieldType());
            field.put("fillType", column.getFillType());
            if (column.getFillType() != null && column.getFillType() == FILL_TYPE_MULTIPLE) {
                field.put("multiple", true);
            }
            field.put("dictOptions", resolveOptions(column, refs));
            putControl(field, column, refs);
            field.put("hidden", isTrue(column.getFillHide()) || isGeneratedUuidKey(column.getPk(), column.getPkType()));
            boolean generatedKey = isGeneratedUuidKey(column.getPk(), column.getPkType());
            boolean formula = StrUtil.isNotBlank(column.getFormulaExpr());
            // 系统生成的 UUID 主键：填报端不显示、不填写，合并入库时由系统生成
            field.put("required", !generatedKey && !formula
                    && column.getNullable() != null && column.getNullable() == 0);
            if (formula) {
                field.put("formula", column.getFormulaExpr().trim());
            }
            if (column.getColumnWidth() != null) {
                field.put("width", column.getColumnWidth());
            }
            if (StrUtil.isNotBlank(column.getHeaderGroup())) {
                field.put("headerGroup", column.getHeaderGroup().trim());
            }
            if (generatedKey) {
                field.put("generated", GENERATED_UUID);
            }
            field.put("pk", isTrue(column.getPk()));
            field.put("pkType", column.getPkType());
            field.put("length", column.getFieldLength());
            field.put("scale", column.getFieldScale());
            // 取值范围、格式、唯一与规则级别随版本固化，提交和导入按此校验
            field.put("min", StrUtil.emptyToNull(StrUtil.trim(column.getMinValue())));
            field.put("max", StrUtil.emptyToNull(StrUtil.trim(column.getMaxValue())));
            field.put("regex", StrUtil.emptyToNull(StrUtil.trim(column.getRegexPattern())));
            field.put("regexMessage", StrUtil.emptyToNull(StrUtil.trim(column.getRegexMessage())));
            field.put("unique", isTrue(column.getUniqueFlag()));
            field.put("ruleSeverity", column.getRuleSeverity() == null
                    ? FillFieldRuleValidator.SEVERITY_ERROR : column.getRuleSeverity());
            field.put("sort", column.getSort());
            // 一期字段均为输入字段；系统生成的主键只读，公式列（editable=false）由公式 schema 标注后覆盖
            field.put("editable", !generatedKey && !formula);
            fields.add(field);
        }
        return fields;
    }

    /**
     * 构造目标映射。
     *
     * @param table 表
     * @param columns 列集合
     * @return 处理后的键值映射
     */
    private Map<String, Object> buildTargetMapping(FillTableDO table, List<FillTableColumnDO> columns) {
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("databaseId", table.getDatabaseId());
        mapping.put("tableName", table.getTableEngName());
        mapping.put("columns", columns.stream().map(column -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fieldCode", column.getFieldCode());
            item.put("targetColumnName", column.getName());
            return item;
        }).collect(Collectors.toList()));
        return mapping;
    }

    /** 空公式结构（未配置任何公式的模板保持与历史版本相同的内容摘要） */
    private static final String FORMULA_SCHEMA_EMPTY = "{\"engineVersion\":\"1.0\",\"formulas\":[]}";

    /** 空布局（未配置列宽、表头分组与冻结列） */
    private static final String LAYOUT_SNAPSHOT_EMPTY = "{}";

    /**
     * 公式定义：按依赖顺序排列，编译检查语法、引用字段与循环引用。
     */
    private Map<String, Object> buildFormulaSchema(List<FillTableColumnDO> columns) {
        List<String> fieldCodes = columns.stream().map(FillTableColumnDO::getFieldCode).toList();
        Map<String, FillTableColumnDO> byCode = new LinkedHashMap<>();
        Map<String, FillExpression> compiled = new LinkedHashMap<>();
        for (FillTableColumnDO column : columns) {
            if (StrUtil.isBlank(column.getFormulaExpr())) {
                continue;
            }
            String label = "字段【" + column.getCnName() + "】的公式";
            if (isTrue(column.getPk())) {
                throw exception(FILL_TABLE_EXPRESSION_INVALID, label, "主键字段不能设置公式");
            }
            FillExpression expression = FillTemplateExpressions.compile(column.getFormulaExpr().trim(),
                    FillExpression.Scope.FORMULA, fieldCodes, label);
            if (expression.fieldRefs().contains(column.getFieldCode())) {
                throw exception(FILL_TABLE_EXPRESSION_INVALID, label, "不能引用字段自身");
            }
            byCode.put(column.getFieldCode(), column);
            compiled.put(column.getFieldCode(), expression);
        }
        List<String> order;
        try {
            order = FillExpression.evaluationOrder(compiled);
        } catch (FillExpression.DefinitionException ex) {
            throw exception(FILL_TABLE_EXPRESSION_INVALID, "公式", ex.getMessage());
        }
        List<Map<String, Object>> formulas = new ArrayList<>();
        for (String code : order) {
            FillTableColumnDO column = byCode.get(code);
            Map<String, Object> formula = new LinkedHashMap<>();
            formula.put("fieldCode", code);
            formula.put("expression", column.getFormulaExpr().trim());
            formula.put("dependsOn", List.copyOf(compiled.get(code).fieldRefs()));
            formula.put("fieldType", column.getFieldType());
            formula.put("scale", column.getFieldScale());
            formulas.add(formula);
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("engineVersion", "1.0");
        schema.put("formulas", formulas);
        return schema;
    }

    /** 录入页面布局：冻结列数（列宽与表头分组随字段固化在 fieldSchema 中） */
    private Map<String, Object> buildLayoutSnapshot(FillTableDO table, List<FillTableColumnDO> columns) {
        Map<String, Object> layout = new LinkedHashMap<>();
        Integer freeze = table.getFreezeColumnCount();
        if (freeze != null && freeze > 0) {
            layout.put("freezeColumnCount", Math.min(freeze, columns.size()));
        }
        return layout;
    }

    /** 从字段配置推导基础校验规则，并固化模板上配置的行级、表级、跨期表达式规则 */
    private Map<String, Object> buildValidationSchema(List<FillTableColumnDO> columns, List<FillTableRuleDO> tableRules,
                                                      Map<String, FillReferenceOptionLoader.Options> refs) {
        List<Map<String, Object>> rules = new ArrayList<>();
        for (FillTableColumnDO column : columns) {
            if (isGeneratedUuidKey(column.getPk(), column.getPkType()) || StrUtil.isNotBlank(column.getFormulaExpr())) {
                // 系统生成的主键、公式计算列不经填报人录入，不参与必填等录入校验
                continue;
            }
            if (column.getNullable() != null && column.getNullable() == 0) {
                rules.add(rule("REQUIRED", column.getFieldCode(), null));
            }
            List<String> dictOptions = resolveOptions(column, refs);
            if (CollUtil.isNotEmpty(dictOptions)) {
                rules.add(rule("DICT", column.getFieldCode(), dictOptions));
            }
            if (StrUtil.isNotBlank(column.getMinValue())) {
                rules.add(rule("MIN_VALUE", column.getFieldCode(), column.getMinValue().trim()));
            }
            if (StrUtil.isNotBlank(column.getMaxValue())) {
                rules.add(rule("MAX_VALUE", column.getFieldCode(), column.getMaxValue().trim()));
            }
            if (StrUtil.isNotBlank(column.getRegexPattern())) {
                rules.add(rule("REGEX", column.getFieldCode(), column.getRegexPattern().trim()));
            }
            if (isTrue(column.getUniqueFlag())) {
                rules.add(rule("UNIQUE", column.getFieldCode(), null));
            }
            if (column.getFieldLength() != null && column.getFieldLength() > 0) {
                rules.add(rule("MAX_LENGTH", column.getFieldCode(), column.getFieldLength()));
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("rules", rules);
        List<Map<String, Object>> expressionRules = buildExpressionRules(columns, tableRules);
        if (!expressionRules.isEmpty()) {
            schema.put("expressionRules", expressionRules);
        }
        return schema;
    }

    /** 启用中的表达式规则：重新编译校验（字段可能在规则保存后被删除或改名） */
    private List<Map<String, Object>> buildExpressionRules(List<FillTableColumnDO> columns,
                                                           List<FillTableRuleDO> tableRules) {
        List<String> fieldCodes = columns.stream().map(FillTableColumnDO::getFieldCode).toList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (FillTableRuleDO rule : tableRules) {
            if (rule.getEnabled() != null && rule.getEnabled() == 0) {
                continue;
            }
            FillTemplateExpressions.compile(rule.getExpression(), FillExpression.Scope.valueOf(rule.getScope()),
                    fieldCodes, "规则【" + rule.getName() + "】的");
            if (StrUtil.isNotBlank(rule.getFieldCode()) && !fieldCodes.contains(rule.getFieldCode())) {
                throw exception(FILL_TABLE_EXPRESSION_INVALID, "规则【" + rule.getName() + "】的",
                        "定位字段 " + rule.getFieldCode() + " 不存在");
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ruleCode", "EXPR_" + rule.getId());
            item.put("name", rule.getName());
            item.put("scope", rule.getScope());
            item.put("expression", rule.getExpression());
            item.put("message", rule.getMessage());
            item.put("severity", rule.getSeverity() == null ? FillFieldRuleValidator.SEVERITY_ERROR : rule.getSeverity());
            item.put("fieldCode", StrUtil.emptyToNull(rule.getFieldCode()));
            result.add(item);
        }
        return result;
    }

    /**
     * 读取填报表版本规则。
     *
     * @param ruleCode 规则编码
     * @param fieldCode 字段编码
     * @param param 参数
     * @return 处理后的键值映射
     */
    private Map<String, Object> rule(String ruleCode, String fieldCode, Object param) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("ruleCode", ruleCode);
        rule.put("fieldCode", fieldCode);
        if (param != null) {
            rule.put("param", param);
        }
        return rule;
    }

    /**
     * 解析选择值：绑定系统字典时固化当前启用的字典项名称，否则使用手工维护的选择值。
     */
    private List<String> resolveOptions(FillTableColumnDO column, Map<String, FillReferenceOptionLoader.Options> refs) {
        FillReferenceOptionLoader.Options reference = refs.get(column.getFieldCode());
        if (reference != null) {
            return reference.values();
        }
        if (StrUtil.isBlank(column.getDictType())) {
            return parseDictOptions(column);
        }
        var items = dictDataApi.getDictDataList(column.getDictType().trim()).getCheckedData();
        List<String> labels = items == null ? List.of() : items.stream()
                .filter(item -> item.getStatus() != null && item.getStatus() == 0)
                .map(item -> StrUtil.trim(item.getLabel()))
                .filter(StrUtil::isNotEmpty)
                .distinct()
                .collect(Collectors.toList());
        if (labels.isEmpty()) {
            throw exception(FILL_TABLE_COLUMN_DICT_INVALID, column.getDictType());
        }
        return labels;
    }

    /**
     * 解析字典选项。
     *
     * @param column 列
     * @return 解析字典选项结果集合
     */
    private List<String> parseDictOptions(FillTableColumnDO column) {
        if (column.getFillType() == null || (column.getFillType() != 2 && column.getFillType() != FILL_TYPE_MULTIPLE)
                || StrUtil.isBlank(column.getFillValues())) {
            return List.of();
        }
        return StrUtil.split(column.getFillValues(), ',').stream()
                .map(String::trim).filter(StrUtil::isNotEmpty).collect(Collectors.toList());
    }

    /**
     * 判断是否满足真值条件。
     *
     * @param value 待处理的值
     * @return 判断结果
     */
    /** fieldSchema 中标记“合并时由系统生成”的字段 */
    public static final String GENERATED_UUID = "UUID";

    /** 系统生成的 UUID 主键长度（32 位十六进制，不含连字符） */
    public static final int GENERATED_UUID_LENGTH = 32;

    /** 录入类型：3-多选（取值为英文逗号分隔的多个可选项） */
    public static final int FILL_TYPE_MULTIPLE = 3;

    /** 录入类型 4~7：人员、部门、附件、图片（存编号） → fieldSchema.refKind */
    private static final Map<Integer, String> REF_KINDS = Map.of(4, "USER", 5, "DEPT", 6, "ATTACHMENT", 7, "IMAGE");

    private static String refKind(Integer fillType) {
        return fillType == null ? null : REF_KINDS.get(fillType);
    }

    /** 附件、图片字段存多个附件编号（英文逗号分隔）所需的最小字段长度 */
    private static final int ATTACHMENT_MIN_LENGTH = 200;

    /** 人员、部门字段存编号所需的最小字段长度 */
    private static final int ID_MIN_LENGTH = 20;

    /**
     * 控件配置校验：人员、部门、附件、图片存编号，必须是足够长的字符串字段；级联上级必须是本表的选择字段。
     */
    private void validateControls(List<FillTableColumnDO> columns) {
        Map<String, FillTableColumnDO> byCode = new LinkedHashMap<>();
        columns.forEach(column -> byCode.put(column.getFieldCode(), column));
        for (FillTableColumnDO column : columns) {
            String kind = refKind(column.getFillType());
            if (kind != null) {
                int minLength = kind.equals("USER") || kind.equals("DEPT") ? ID_MIN_LENGTH : ATTACHMENT_MIN_LENGTH;
                if (column.getFieldType() == null
                        || column.getFieldType() != cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData.TYPE_STRING
                        || (column.getFieldLength() != null && column.getFieldLength() < minLength)) {
                    throw exception(FILL_TABLE_COLUMN_RULE_INVALID, "字段【" + column.getCnName() + "】存编号，必须是长度不小于 "
                            + minLength + " 的字符串字段");
                }
            }
            if (StrUtil.isNotBlank(column.getRefParentField())) {
                FillTableColumnDO parent = byCode.get(column.getRefParentField());
                if (parent == null || parent == column || column.getRefDatabaseId() == null
                        || parent.getFillType() == null || parent.getFillType() != 2) {
                    throw exception(FILL_TABLE_REF_SOURCE_INVALID, column.getCnName(),
                            "级联上级必须是本表另一个下拉选择字段");
                }
            }
        }
    }

    /** 发布时一次性读取全部数据表下拉的可选项（同一份结果供字段结构与校验规则使用） */
    private Map<String, FillReferenceOptionLoader.Options> loadReferenceOptions(List<FillTableColumnDO> columns) {
        Map<String, FillReferenceOptionLoader.Options> refs = new LinkedHashMap<>();
        for (FillTableColumnDO column : columns) {
            boolean select = column.getFillType() != null && (column.getFillType() == 2 || column.getFillType() == 3);
            if (select && column.getRefDatabaseId() != null && referenceOptionLoader != null) {
                refs.put(column.getFieldCode(), referenceOptionLoader.load(column));
            }
        }
        return refs;
    }

    /** 控件类型：级联、人员/部门（含选择范围）、附件/图片 */
    private void putControl(Map<String, Object> field, FillTableColumnDO column,
                            Map<String, FillReferenceOptionLoader.Options> refs) {
        FillReferenceOptionLoader.Options reference = refs.get(column.getFieldCode());
        if (reference != null && StrUtil.isNotBlank(column.getRefParentField())) {
            field.put("cascadeParent", column.getRefParentField());
            field.put("cascadeOptions", reference.byParent());
        }
        String kind = refKind(column.getFillType());
        if (kind != null) {
            field.put("refKind", kind);
            if (kind.equals("USER") || kind.equals("DEPT")) {
                field.put("refScope", "ALL".equals(column.getRefScope()) ? "ALL" : "ORG");
            } else {
                field.put("multiple", true);
            }
        }
    }

    /** 主键类型：1-UUID（应用生成） */
    private static final int PK_TYPE_UUID = 1;

    /**
     * 是否为系统生成的 UUID 主键。
     */
    public static boolean isGeneratedUuidKey(Integer pk, Integer pkType) {
        return pk != null && pk == 1 && pkType != null && pkType == PK_TYPE_UUID;
    }

    /**
     * 系统生成主键的字段定义校验。
     *
     * @return 错误说明；合法时返回 null
     */
    public static String generatedKeyError(Integer pk, Integer pkType, Integer fieldType, Integer fieldLength) {
        if (!isGeneratedUuidKey(pk, pkType)) {
            return null;
        }
        if (fieldType == null || fieldType != cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData.TYPE_STRING) {
            return "是系统生成的 UUID 主键，必须是字符串类型";
        }
        if (fieldLength != null && fieldLength < GENERATED_UUID_LENGTH) {
            return "是系统生成的 UUID 主键，长度不能小于 " + GENERATED_UUID_LENGTH;
        }
        return null;
    }

    /** 未配置时默认范围替换；无法识别的值拒绝发布 */
    private FillMergeStrategyEnum resolveMergeStrategy(String configured) {
        if (StrUtil.isBlank(configured)) {
            return FillMergeStrategyEnum.REPLACE_SCOPE;
        }
        FillMergeStrategyEnum strategy = FillMergeStrategyEnum.of(configured.trim());
        if (strategy == null) {
            throw exception(FILL_TABLE_MERGE_CONFIG_INVALID, "未知合并策略 " + configured);
        }
        return strategy;
    }

    /**
     * 更新插入的业务键：必须是本模板的必填、非系统生成字段；组织与所属期由系统自动并入业务键。
     *
     * @return 规整后的 fieldCode 列表（英文逗号分隔，保持配置顺序）
     */
    private String validateBusinessKeys(String configured, List<FillTableColumnDO> columns) {
        List<String> keys = StrUtil.split(StrUtil.nullToEmpty(configured), ',', true, true)
                .stream().distinct().toList();
        if (keys.isEmpty()) {
            throw exception(FILL_TABLE_MERGE_CONFIG_INVALID, "更新插入策略必须至少选择一个业务键字段");
        }
        Map<String, FillTableColumnDO> byCode = columns.stream()
                .collect(Collectors.toMap(FillTableColumnDO::getFieldCode, column -> column, (a, b) -> a));
        for (String key : keys) {
            FillTableColumnDO column = byCode.get(key);
            if (column == null) {
                throw exception(FILL_TABLE_MERGE_CONFIG_INVALID, "业务键字段【" + key + "】不存在");
            }
            if (isGeneratedUuidKey(column.getPk(), column.getPkType())) {
                throw exception(FILL_TABLE_MERGE_CONFIG_INVALID,
                        "业务键字段【" + column.getCnName() + "】是系统生成的主键，不能作为业务键");
            }
            if (column.getNullable() == null || column.getNullable() != 0) {
                throw exception(FILL_TABLE_MERGE_CONFIG_INVALID,
                        "业务键字段【" + column.getCnName() + "】必须设为必填");
            }
        }
        return String.join(",", keys);
    }

    private boolean isTrue(Integer value) {
        return value != null && value == 1;
    }

}
