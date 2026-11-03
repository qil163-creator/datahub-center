package cn.iocoder.yudao.module.modeling.service.model;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelColumnDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelColumnMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 逻辑模型字段表读写：创建/编辑/详情/发布读字段的真相源
 */
@Slf4j
@Service
@Validated
public class LogicModelColumnServiceImpl implements LogicModelColumnService {

    @Resource
    private LogicModelColumnMapper columnMapper;

    /**
     * 按 sort、id 排序返回字段
     *
     * @param modelId 模型编号
     * @return 按 sort、id 排序返回字段结果集合
     */
    @Override
    public List<ModelFieldItem> listByModelId(Long modelId) {
        if (modelId == null) {
            return List.of();
        }
        return convertList(columnMapper.selectListByModelId(modelId));
    }

    /**
     * 批量：modelId -> 字段列表（已按 sort 排）
     *
     * @param modelIds 模型编号集合
     * @return 批量：modelId -> 字段列表（已按 sort 排）结果集合
     */
    @Override
    public Map<Long, List<ModelFieldItem>> listByModelIds(Collection<Long> modelIds) {
        if (CollUtil.isEmpty(modelIds)) {
            return Collections.emptyMap();
        }
        Map<Long, List<ModelFieldItem>> result = new LinkedHashMap<>();
        for (Long id : modelIds) {
            if (id != null) {
                result.put(id, new ArrayList<>());
            }
        }
        for (LogicModelColumnDO row : columnMapper.selectListByModelIds(result.keySet())) {
            result.computeIfAbsent(row.getModelId(), k -> new ArrayList<>()).add(toItem(row));
        }
        return result;
    }

    /**
     * 整单替换：软删该模型现有字段后按 field_uid 插入新列表。
     *  调用方须保证 fieldUid 已补齐且同模型内唯一。
     *
     * @param modelId 模型编号
     * @param fields 字段定义
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceByModelId(Long modelId, List<ModelFieldItem> fields) {
        Objects.requireNonNull(modelId, "modelId");
        List<ModelFieldItem> safe = fields == null ? List.of() : fields;
        // 同模型重复 field_uid：保留第一条并打日志
        Set<String> seen = new LinkedHashSet<>();
        List<ModelFieldItem> deduped = new ArrayList<>(safe.size());
        for (ModelFieldItem item : safe) {
            if (item == null || StrUtil.isBlank(item.getFieldUid())) {
                continue;
            }
            if (!seen.add(item.getFieldUid())) {
                log.warn("[logic-model-column] duplicate field_uid skipped, modelId={}, fieldUid={}, fieldName={}",
                        modelId, item.getFieldUid(), item.getFieldName());
                continue;
            }
            deduped.add(item);
        }
        columnMapper.deleteByModelId(modelId);
        int sort = 0;
        for (ModelFieldItem item : deduped) {
            LogicModelColumnDO row = toDO(modelId, item, item.getSort() != null ? item.getSort() : sort);
            columnMapper.insert(row);
            sort++;
        }
    }

    /**
     * 随模型删除一并清理字段行
     *
     * @param modelId 模型编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByModelId(Long modelId) {
        if (modelId == null) {
            return;
        }
        columnMapper.deleteByModelId(modelId);
    }

    /**
     * 转换逻辑模型列的列表。
     *
     * @param rows 待处理的行数据
     * @return 转换逻辑模型列的列表结果集合
     */
    private List<ModelFieldItem> convertList(List<LogicModelColumnDO> rows) {
        if (CollUtil.isEmpty(rows)) {
            return List.of();
        }
        return rows.stream().map(this::toItem).collect(Collectors.toList());
    }

    /**
     * 转换为项。
     *
     * @param row 当前行数据
     * @return 模型字段项
     */
    private ModelFieldItem toItem(LogicModelColumnDO row) {
        ModelFieldItem item = new ModelFieldItem();
        item.setFieldUid(row.getFieldUid());
        item.setSourceAttrUid(row.getSourceAttrUid());
        item.setFieldName(row.getFieldName());
        item.setCnName(row.getCnName());
        item.setDataType(row.getDataType());
        item.setDataLength(row.getDataLength());
        item.setDataPrecision(row.getDataPrecision());
        item.setDataScale(row.getDataScale());
        item.setDataFormat(row.getDataFormat());
        item.setMeasureUnit(row.getMeasureUnit());
        item.setValueRange(row.getValueRange());
        item.setDefaultValue(row.getDefaultValue());
        item.setPkFlag(Integer.valueOf(1).equals(row.getPkFlag()));
        item.setNullableFlag(!Integer.valueOf(0).equals(row.getNullableFlag()));
        item.setSort(row.getSort());
        item.setComment(row.getComment());
        item.setRemark(row.getRemark());
        item.setAggType(row.getAggType());
        item.setStandardId(row.getStandardId());
        item.setStandardCode(row.getStandardCode());
        item.setStandardName(row.getStandardName());
        item.setStandardColumnType(row.getStandardColumnType());
        item.setFieldCategory(row.getFieldCategory());
        item.setDimensionId(row.getDimensionId());
        item.setDimensionName(row.getDimensionName());
        item.setDataClassificationId(row.getDataClassificationId());
        item.setDataClassificationName(row.getDataClassificationName());
        item.setDataGrade(row.getDataGrade());
        item.setCodeTableId(row.getCodeTableId());
        item.setCodeTableName(row.getCodeTableName());
        item.setUniqueFlag(Integer.valueOf(1).equals(row.getUniqueFlag()));
        item.setIndicatorId(row.getIndicatorId());
        item.setAtomicIndicatorName(row.getAtomicIndicatorName());
        item.setBusinessCondition(row.getBusinessCondition());
        item.setStatisticsPeriod(row.getStatisticsPeriod());
        item.setSourceModelName(row.getSourceModelName());
        return item;
    }

    /**
     * 转换为逻辑模型列。
     *
     * @param modelId 模型编号
     * @param item 当前条目
     * @param sortFallback 排序回退
     * @return 逻辑模型列
     */
    private LogicModelColumnDO toDO(Long modelId, ModelFieldItem item, int sortFallback) {
        return LogicModelColumnDO.builder()
                .modelId(modelId)
                .fieldUid(item.getFieldUid())
                .sourceAttrUid(blankToNull(item.getSourceAttrUid()))
                .fieldName(item.getFieldName())
                .cnName(blankToNull(item.getCnName()))
                .dataType(item.getDataType())
                .dataLength(item.getDataLength())
                .dataPrecision(item.getDataPrecision())
                .dataScale(item.getDataScale())
                .dataFormat(blankToNull(item.getDataFormat()))
                .measureUnit(blankToNull(item.getMeasureUnit()))
                .valueRange(blankToNull(item.getValueRange()))
                .defaultValue(blankToNull(item.getDefaultValue()))
                .pkFlag(Boolean.TRUE.equals(item.getPkFlag()) ? 1 : 0)
                .nullableFlag(Boolean.FALSE.equals(item.getNullableFlag()) ? 0 : 1)
                .sort(item.getSort() != null ? item.getSort() : sortFallback)
                .comment(blankToNull(item.getComment()))
                .remark(blankToNull(item.getRemark()))
                .aggType(blankToNull(item.getAggType()))
                .standardId(item.getStandardId())
                .standardCode(blankToNull(item.getStandardCode()))
                .standardName(blankToNull(item.getStandardName()))
                .standardColumnType(blankToNull(item.getStandardColumnType()))
                .fieldCategory(item.getFieldCategory())
                .dimensionId(item.getDimensionId())
                .dimensionName(item.getDimensionName())
                .dataClassificationId(item.getDataClassificationId())
                .dataClassificationName(item.getDataClassificationName())
                .dataGrade(item.getDataGrade())
                .codeTableId(item.getCodeTableId())
                .codeTableName(item.getCodeTableName())
                .uniqueFlag(Boolean.TRUE.equals(item.getUniqueFlag()) ? 1 : 0)
                .indicatorId(item.getIndicatorId())
                .atomicIndicatorName(item.getAtomicIndicatorName())
                .businessCondition(item.getBusinessCondition())
                .statisticsPeriod(item.getStatisticsPeriod())
                .sourceModelName(item.getSourceModelName())
                .build();
    }

    /**
     * 将空白文本转换为空值，其余文本保留。
     *
     * @param value 待处理的值
     * @return 将空白文本转换为空值，其余文本保留文本
     */
    private static String blankToNull(String value) {
        return StrUtil.isBlank(value) ? null : value;
    }
}
