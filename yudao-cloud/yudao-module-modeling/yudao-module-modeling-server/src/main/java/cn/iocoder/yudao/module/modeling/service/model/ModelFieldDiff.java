package cn.iocoder.yudao.module.modeling.service.model;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.version.ModelDiffRespVO;

import java.util.*;
import java.util.function.Function;

/**
 * 模型结构比对：表头属性与字段按稳定标识对齐后逐属性比较。
 * <p>
 * 字段先按 {@code fieldUid} 对齐，未对齐的再按字段英文名（忽略大小写）对齐，其余视为新增或删除。
 * 版本比对、版本变化摘要和源表结构同步预览共用同一规则。无状态，线程安全。
 */
public final class ModelFieldDiff {

    /** 全部字段比对属性：键 → 展示名称，顺序即展示顺序。 */
    public static final Map<String, String> FIELD_ATTRIBUTES;

    /** 源表结构同步只关心的结构属性。 */
    public static final Map<String, String> STRUCTURE_ATTRIBUTES;

    /** 表头比对属性：键 → 展示名称。 */
    public static final Map<String, String> HEADER_ATTRIBUTES;

    private static final Map<String, Function<ModelFieldItem, Object>> GETTERS = new HashMap<>();

    static {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("fieldName", "英文名称");
        fields.put("cnName", "中文名称");
        fields.put("dataType", "数据类型");
        fields.put("dataLength", "长度");
        fields.put("dataPrecision", "总精度");
        fields.put("dataScale", "小数位数");
        fields.put("pkFlag", "主键");
        fields.put("nullableFlag", "可为空");
        fields.put("uniqueFlag", "唯一");
        fields.put("defaultValue", "默认值");
        fields.put("comment", "描述");
        fields.put("fieldCategory", "字段类别");
        fields.put("standardName", "关联标准");
        fields.put("codeTableName", "代码表");
        fields.put("dataClassificationName", "数据分类");
        fields.put("dataGrade", "数据分级");
        fields.put("dimensionName", "对应对象/指标");
        fields.put("indicatorId", "引用指标");
        fields.put("aggType", "聚合方式");
        fields.put("measureUnit", "计量单位");
        fields.put("dataFormat", "数据格式");
        FIELD_ATTRIBUTES = Collections.unmodifiableMap(fields);

        Map<String, String> structure = new LinkedHashMap<>();
        for (String key : List.of("fieldName", "dataType", "dataLength", "dataPrecision", "dataScale", "pkFlag",
                "nullableFlag", "defaultValue", "comment")) {
            structure.put(key, fields.get(key));
        }
        STRUCTURE_ATTRIBUTES = Collections.unmodifiableMap(structure);

        Map<String, String> header = new LinkedHashMap<>();
        header.put("name", "中文名称");
        header.put("tableNameNotation", "表名");
        header.put("code", "英文名");
        header.put("description", "描述");
        header.put("dataTimeliness", "数据时效");
        header.put("layerId", "数仓分层");
        header.put("dataDomainId", "业务板块");
        header.put("subjectDomainId", "主题域");
        header.put("entityId", "业务实体");
        header.put("contactUserId", "责任人");
        header.put("contactNumber", "联系电话");
        header.put("documentType", "标准类型");
        header.put("documentId", "标准登记");
        header.put("enabled", "启用状态");
        header.put("tableCase", "表名大小写");
        header.put("datasourceId", "数据源");
        header.put("dbName", "数据库");
        header.put("schemaName", "Schema");
        HEADER_ATTRIBUTES = Collections.unmodifiableMap(header);

        GETTERS.put("fieldName", ModelFieldItem::getFieldName);
        GETTERS.put("cnName", ModelFieldItem::getCnName);
        GETTERS.put("dataType", item -> item.getDataType() == null ? null
                : item.getDataType().trim().toUpperCase(Locale.ROOT));
        GETTERS.put("dataLength", ModelFieldItem::getDataLength);
        GETTERS.put("dataPrecision", ModelFieldItem::getDataPrecision);
        GETTERS.put("dataScale", ModelFieldItem::getDataScale);
        GETTERS.put("pkFlag", item -> Boolean.TRUE.equals(item.getPkFlag()));
        GETTERS.put("nullableFlag", item -> !Boolean.FALSE.equals(item.getNullableFlag()));
        GETTERS.put("uniqueFlag", item -> Boolean.TRUE.equals(item.getUniqueFlag()));
        GETTERS.put("defaultValue", ModelFieldItem::getDefaultValue);
        GETTERS.put("comment", ModelFieldItem::getComment);
        GETTERS.put("fieldCategory", ModelFieldItem::getFieldCategory);
        GETTERS.put("standardName", ModelFieldItem::getStandardName);
        GETTERS.put("codeTableName", ModelFieldItem::getCodeTableName);
        GETTERS.put("dataClassificationName", ModelFieldItem::getDataClassificationName);
        GETTERS.put("dataGrade", ModelFieldItem::getDataGrade);
        GETTERS.put("dimensionName", ModelFieldItem::getDimensionName);
        GETTERS.put("indicatorId", ModelFieldItem::getIndicatorId);
        GETTERS.put("aggType", ModelFieldItem::getAggType);
        GETTERS.put("measureUnit", ModelFieldItem::getMeasureUnit);
        GETTERS.put("dataFormat", ModelFieldItem::getDataFormat);
    }

    private ModelFieldDiff() {
    }

    /**
     * 比较两份模型结构。
     *
     * @param beforeHeader    基准表头，为空时不比较表头
     * @param before          基准字段
     * @param afterHeader     目标表头，为空时不比较表头
     * @param after           目标字段
     * @param fieldAttributes 参与比较的字段属性，取 {@link #FIELD_ATTRIBUTES} 或 {@link #STRUCTURE_ATTRIBUTES}
     * @return 比对结果
     */
    public static ModelDiffRespVO compare(Map<String, Object> beforeHeader, List<ModelFieldItem> before,
            Map<String, Object> afterHeader, List<ModelFieldItem> after, Map<String, String> fieldAttributes) {
        ModelDiffRespVO result = new ModelDiffRespVO();
        if (beforeHeader != null && afterHeader != null) {
            for (Map.Entry<String, String> attribute : HEADER_ATTRIBUTES.entrySet()) {
                String left = text(beforeHeader.get(attribute.getKey()));
                String right = text(afterHeader.get(attribute.getKey()));
                if (!left.equals(right)) {
                    result.getHeaderChanges().add(change(attribute.getKey(), attribute.getValue(), left, right));
                }
            }
        }
        List<ModelFieldItem> left = before == null ? List.of() : before;
        List<ModelFieldItem> right = after == null ? List.of() : after;
        Map<String, Integer> byUid = new HashMap<>();
        Map<String, Integer> byName = new HashMap<>();
        for (int index = 0; index < left.size(); index++) {
            ModelFieldItem item = left.get(index);
            if (StrUtil.isNotBlank(item.getFieldUid())) {
                byUid.putIfAbsent(item.getFieldUid(), index);
            }
            if (StrUtil.isNotBlank(item.getFieldName())) {
                byName.putIfAbsent(normalizeName(item.getFieldName()), index);
            }
        }
        boolean[] used = new boolean[left.size()];
        for (ModelFieldItem target : right) {
            Integer matched = null;
            if (StrUtil.isNotBlank(target.getFieldUid())) {
                Integer candidate = byUid.get(target.getFieldUid());
                if (candidate != null && !used[candidate]) {
                    matched = candidate;
                }
            }
            if (matched == null && StrUtil.isNotBlank(target.getFieldName())) {
                Integer candidate = byName.get(normalizeName(target.getFieldName()));
                if (candidate != null && !used[candidate]) {
                    matched = candidate;
                }
            }
            ModelDiffRespVO.FieldChange change = new ModelDiffRespVO.FieldChange();
            change.setAfter(target);
            change.setFieldUid(target.getFieldUid());
            change.setFieldName(target.getFieldName());
            change.setCnName(target.getCnName());
            if (matched == null) {
                change.setChangeType(ModelDiffRespVO.ADDED);
                result.getSummary().setAdded(result.getSummary().getAdded() + 1);
            } else {
                used[matched] = true;
                ModelFieldItem source = left.get(matched);
                change.setBefore(source);
                if (StrUtil.isBlank(change.getFieldUid())) {
                    change.setFieldUid(source.getFieldUid());
                }
                for (Map.Entry<String, String> attribute : fieldAttributes.entrySet()) {
                    Function<ModelFieldItem, Object> getter = GETTERS.get(attribute.getKey());
                    if (getter == null) {
                        continue;
                    }
                    String oldValue = text(getter.apply(source));
                    String newValue = text(getter.apply(target));
                    if (!oldValue.equals(newValue)) {
                        change.getChanges().add(change(attribute.getKey(), attribute.getValue(), oldValue,
                                newValue));
                    }
                }
                if (change.getChanges().isEmpty()) {
                    change.setChangeType(ModelDiffRespVO.UNCHANGED);
                    result.getSummary().setUnchanged(result.getSummary().getUnchanged() + 1);
                } else {
                    change.setChangeType(ModelDiffRespVO.MODIFIED);
                    result.getSummary().setModified(result.getSummary().getModified() + 1);
                }
            }
            result.getFields().add(change);
        }
        for (int index = 0; index < left.size(); index++) {
            if (used[index]) {
                continue;
            }
            ModelFieldItem source = left.get(index);
            ModelDiffRespVO.FieldChange change = new ModelDiffRespVO.FieldChange();
            change.setChangeType(ModelDiffRespVO.REMOVED);
            change.setBefore(source);
            change.setFieldUid(source.getFieldUid());
            change.setFieldName(source.getFieldName());
            change.setCnName(source.getCnName());
            result.getFields().add(change);
            result.getSummary().setRemoved(result.getSummary().getRemoved() + 1);
        }
        result.getSummary().setHeaderChanged(result.getHeaderChanges().size());
        return result;
    }

    /**
     * 变化摘要，用于版本列表展示。
     *
     * @param diff 比对结果
     * @return 新增、删除、修改字段数及表头变化数
     */
    public static Map<String, Object> summaryOf(ModelDiffRespVO diff) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("added", diff.getSummary().getAdded());
        summary.put("removed", diff.getSummary().getRemoved());
        summary.put("modified", diff.getSummary().getModified());
        summary.put("headerChanged", diff.getSummary().getHeaderChanged());
        return summary;
    }

    private static String normalizeName(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static ModelDiffRespVO.AttributeChange change(String key, String label, String before, String after) {
        ModelDiffRespVO.AttributeChange change = new ModelDiffRespVO.AttributeChange();
        change.setKey(key);
        change.setLabel(label);
        change.setBefore(before);
        change.setAfter(after);
        return change;
    }

    /** 统一展示文本：空值为空串，布尔转是/否，其余按字符串比较，避免整数与长整数被判为不同。 */
    private static String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Boolean flag) {
            return flag ? "是" : "否";
        }
        return value.toString().trim();
    }
}
