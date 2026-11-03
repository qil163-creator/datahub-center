package cn.iocoder.yudao.module.modeling.service.model;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelTypeConfigVO;
import cn.iocoder.yudao.module.modeling.enums.ModelTableTypeEnum;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;

/**
 * 表类型专属配置校验器
 *
 * 草稿阶段允许配置不完整（先把表建出来、字段填好，再慢慢补口径），
 * 发布是对外承诺，按表类型检查最低完整性：
 * <ul>
 *   <li>贴源表：承接模式、来源业务键；CDC 模式还要事件序列字段</li>
 *   <li>维度表：业务键、历史策略；SCD2 还要生效/失效时间字段</li>
 *   <li>明细表：组织方式、粒度描述、粒度字段、业务时间字段</li>
 *   <li>汇总表：输出粒度字段、时间粒度、业务时间口径、统计口径说明</li>
 *   <li>应用表：消费场景、输出粒度描述</li>
 * </ul>
 * 所有引用到的字段名都必须真的存在于当前设计的字段列表里。
 *
 * @author Li
 */
@Component
public class ModelTypeConfigValidator {

    private static final Set<String> INGEST_MODES = Set.of("CURRENT_STATE", "PERIODIC_SNAPSHOT", "CDC_EVENT");

    private static final Set<String> DELETE_STRATEGIES = Set.of("PHYSICAL", "LOGICAL_FLAG", "KEEP_EVENT");

    private static final Set<String> HISTORY_STRATEGIES = Set.of("FIXED", "OVERWRITE", "SCD2");

    private static final Set<String> UNKNOWN_VALUE_STRATEGIES = Set.of("UNKNOWN_ROW", "NULL", "REJECT");

    private static final Set<String> ORGANIZATION_MODES = Set.of("TRANSACTION", "PERIODIC_SNAPSHOT",
            "ACCUMULATING_SNAPSHOT");

    private static final Set<String> LATE_ARRIVAL_STRATEGIES = Set.of("OVERWRITE", "APPEND_CORRECTION", "IGNORE");

    private static final Set<String> TIME_GRANULARITIES = Set.of("DAY", "WEEK", "MONTH", "QUARTER", "YEAR",
            "ROLLING_WINDOW", "NONE");

    private static final Set<String> WRITE_MODES = Set.of("OVERWRITE_RANGE", "UPSERT_BY_KEY", "APPEND_INCREMENT");

    private static final Set<String> STALE_GROUP_STRATEGIES = Set.of("DELETE", "KEEP", "MARK");

    /**
     * 解析当前模型保存的配置 JSON
     */
    public ModelTypeConfigVO parse(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        return JsonUtils.parseObject(json, ModelTypeConfigVO.class);
    }

    /**
     * 保存时的轻校验：只查取值是否在枚举内，不要求完整
     *
     * @param config 配置，可空
     */
    public void validateOnSave(ModelTypeConfigVO config) {
        if (config == null) {
            return;
        }
        if (config.getSource() != null) {
            checkEnum("承接模式", config.getSource().getIngestMode(), INGEST_MODES);
            checkEnum("删除处理", config.getSource().getDeleteStrategy(), DELETE_STRATEGIES);
        }
        if (config.getDimension() != null) {
            checkEnum("历史策略", config.getDimension().getHistoryStrategy(), HISTORY_STRATEGIES);
            checkEnum("未知值处理", config.getDimension().getUnknownValueStrategy(), UNKNOWN_VALUE_STRATEGIES);
        }
        if (config.getDetail() != null) {
            checkEnum("组织方式", config.getDetail().getOrganizationMode(), ORGANIZATION_MODES);
            checkEnum("迟到处理", config.getDetail().getLateArrivalStrategy(), LATE_ARRIVAL_STRATEGIES);
        }
        if (config.getSummary() != null) {
            checkEnum("时间粒度", config.getSummary().getTimeGranularity(), TIME_GRANULARITIES);
            checkEnum("写入方式", config.getSummary().getWriteMode(), WRITE_MODES);
            checkEnum("失效分组处理", config.getSummary().getStaleGroupStrategy(), STALE_GROUP_STRATEGIES);
        }
    }

    /**
     * 发布时的完整性校验
     *
     * @param tableType 模型表类型
     * @param config    当前设计的专属配置，可空
     * @param fields    当前字段，用于校验字段引用
     */
    public void validateOnPublish(String tableType, ModelTypeConfigVO config, List<ModelFieldItem> fields) {
        ModelTableTypeEnum type = ModelTableTypeEnum.of(tableType);
        if (type == null) {
            return;
        }
        List<String> missing = new ArrayList<>();
        Set<String> referencedFields = new LinkedHashSet<>();
        switch (type) {
            case SOURCE -> collectSource(config == null ? null : config.getSource(), missing, referencedFields);
            case DIMENSION ->
                    collectDimension(config == null ? null : config.getDimension(), missing, referencedFields);
            case DETAIL -> collectDetail(config == null ? null : config.getDetail(), missing, referencedFields);
            case SUMMARY -> collectSummary(config == null ? null : config.getSummary(), missing, referencedFields);
            case APPLICATION -> collectApplication(config == null ? null : config.getApplication(), missing);
        }
        if (!missing.isEmpty()) {
            throw exception(MODEL_TYPE_CONFIG_INCOMPLETE, type.getName(), String.join("、", missing));
        }
        validateOnSave(config);
        validateFieldReferences(referencedFields, fields);
    }

    /** 草稿可缺项，但已选择的字段引用必须属于本次同时保存的字段集合。 */
    public void validateReferencesOnSave(ModelTypeConfigVO config, List<ModelFieldItem> fields) {
        if (config == null) {
            return;
        }
        List<String> ignored = new ArrayList<>();
        Set<String> refs = new LinkedHashSet<>();
        if (config.getSource() != null) {
            collectSource(config.getSource(), ignored, refs);
        }
        if (config.getDimension() != null) {
            collectDimension(config.getDimension(), ignored, refs);
        }
        if (config.getDetail() != null) {
            collectDetail(config.getDetail(), ignored, refs);
        }
        if (config.getSummary() != null) {
            collectSummary(config.getSummary(), ignored, refs);
        }
        validateFieldReferences(refs, fields);
    }

    // ==================== 各类最低完整性 ====================

    /**
     * 收集来源。
     *
     * @param source 来源数据
     * @param missing 缺失
     * @param refs 引用集合
     */
    private void collectSource(ModelTypeConfigVO.SourceConfig source, List<String> missing, Set<String> refs) {
        if (source == null) {
            missing.addAll(List.of("承接模式", "来源业务键"));
            return;
        }
        requireText(source.getIngestMode(), "承接模式", missing);
        requireList(source.getSourceKeyFields(), "来源业务键", missing, refs);
        if ("CDC_EVENT".equals(source.getIngestMode())) {
            requireField(source.getEventSequenceField(), "事件序列字段（CDC 模式）", missing, refs);
        }
    }

    /**
     * 收集维度。
     *
     * @param dimension 维度
     * @param missing 缺失
     * @param refs 引用集合
     */
    private void collectDimension(ModelTypeConfigVO.DimensionConfig dimension, List<String> missing, Set<String> refs) {
        if (dimension == null) {
            missing.addAll(List.of("业务键", "历史策略"));
            return;
        }
        requireList(dimension.getBusinessKeyFields(), "业务键", missing, refs);
        requireText(dimension.getHistoryStrategy(), "历史策略", missing);
        if ("SCD2".equals(dimension.getHistoryStrategy())) {
            requireField(dimension.getEffectiveFromField(), "生效时间字段（保留历史版本）", missing, refs);
            requireField(dimension.getEffectiveToField(), "失效时间字段（保留历史版本）", missing, refs);
        }
        addRef(dimension.getCurrentFlagField(), refs);
    }

    /**
     * 收集详情。
     *
     * @param detail 详情
     * @param missing 缺失
     * @param refs 引用集合
     */
    private void collectDetail(ModelTypeConfigVO.DetailConfig detail, List<String> missing, Set<String> refs) {
        if (detail == null) {
            missing.addAll(List.of("组织方式", "粒度描述", "粒度字段", "业务时间字段"));
            return;
        }
        requireText(detail.getOrganizationMode(), "组织方式", missing);
        requireText(detail.getGrainDescription(), "粒度描述", missing);
        requireList(detail.getGrainFields(), "粒度字段", missing, refs);
        requireField(detail.getBusinessTimeField(), "业务时间字段", missing, refs);
        addRefs(detail.getDedupFields(), refs);
    }

    /**
     * 收集汇总。
     *
     * @param summary 汇总
     * @param missing 缺失
     * @param refs 引用集合
     */
    private void collectSummary(ModelTypeConfigVO.SummaryConfig summary, List<String> missing, Set<String> refs) {
        if (summary == null) {
            missing.addAll(List.of("输出粒度字段", "时间粒度", "业务时间口径", "统计口径说明"));
            return;
        }
        requireList(summary.getGrainFields(), "输出粒度字段", missing, refs);
        requireText(summary.getTimeGranularity(), "时间粒度", missing);
        requireText(summary.getBusinessTimeField(), "业务时间口径", missing);
        requireText(summary.getMeasureDefinition(), "统计口径说明", missing);
        if ("ROLLING_WINDOW".equals(summary.getTimeGranularity())) {
            requireText(summary.getRollingWindowNote(), "滚动窗口说明", missing);
        }
        // 覆盖重算必须说明失效分组怎么处理，否则撤销的数据会留下旧汇总值
        if ("OVERWRITE_RANGE".equals(summary.getWriteMode())) {
            requireText(summary.getStaleGroupStrategy(), "重算时失效分组处理", missing);
        }
    }

    /**
     * 收集应用。
     *
     * @param application 应用
     * @param missing 缺失
     */
    private void collectApplication(ModelTypeConfigVO.ApplicationConfig application, List<String> missing) {
        if (application == null) {
            missing.addAll(List.of("消费场景", "输出粒度描述"));
            return;
        }
        requireText(application.getConsumerScene(), "消费场景", missing);
        requireText(application.getGrainDescription(), "输出粒度描述", missing);
    }

    // ==================== 工具 ====================

    /**
     * 校验文本。
     *
     * @param value 待处理的值
     * @param label 标签
     * @param missing 缺失
     */
    private void requireText(String value, String label, List<String> missing) {
        if (StrUtil.isBlank(value)) {
            missing.add(label);
        }
    }

    /**
     * 校验字段。
     *
     * @param fieldName 字段名
     * @param label 标签
     * @param missing 缺失
     * @param refs 引用集合
     */
    private void requireField(String fieldName, String label, List<String> missing, Set<String> refs) {
        if (StrUtil.isBlank(fieldName)) {
            missing.add(label);
            return;
        }
        addRef(fieldName, refs);
    }

    /**
     * 校验列表。
     *
     * @param fieldNames 字段名集合
     * @param label 标签
     * @param missing 缺失
     * @param refs 引用集合
     */
    private void requireList(List<String> fieldNames, String label, List<String> missing, Set<String> refs) {
        if (CollUtil.isEmpty(fieldNames) || fieldNames.stream().allMatch(StrUtil::isBlank)) {
            missing.add(label);
            return;
        }
        addRefs(fieldNames, refs);
    }

    /**
     * 添加引用。
     *
     * @param fieldName 字段名
     * @param refs 引用集合
     */
    private void addRef(String fieldName, Set<String> refs) {
        if (StrUtil.isNotBlank(fieldName)) {
            refs.add(fieldName.trim().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * 添加引用集合。
     *
     * @param fieldNames 字段名集合
     * @param refs 引用集合
     */
    private void addRefs(Collection<String> fieldNames, Set<String> refs) {
        if (fieldNames != null) {
            fieldNames.forEach(name -> addRef(name, refs));
        }
    }

    /**
     * 检查模型类型配置校验器。
     *
     * @param label 标签
     * @param value 待处理的值
     * @param allowed 允许
     */
    private void checkEnum(String label, String value, Set<String> allowed) {
        if (StrUtil.isNotBlank(value) && !allowed.contains(value)) {
            throw exception(MODEL_TYPE_CONFIG_INVALID_VALUE,
                    label + "=" + value + "，允许值：" + String.join("/", new java.util.TreeSet<>(allowed)));
        }
    }

    /**
     * 校验字段引用。
     *
     * @param referencedFields 被引用字段
     * @param fields 字段定义
     */
    private void validateFieldReferences(Set<String> referencedFields, List<ModelFieldItem> fields) {
        if (referencedFields.isEmpty()) {
            return;
        }
        Set<String> existing = new LinkedHashSet<>();
        if (fields != null) {
            fields.stream()
                    .map(ModelFieldItem::getFieldName)
                    .filter(StrUtil::isNotBlank)
                    .forEach(name -> existing.add(name.trim().toLowerCase(Locale.ROOT)));
        }
        List<String> notFound = referencedFields.stream()
                .filter(name -> !existing.contains(name))
                .toList();
        if (!notFound.isEmpty()) {
            throw exception(MODEL_TYPE_CONFIG_FIELD_NOT_EXISTS, String.join("、", notFound));
        }
    }

}
