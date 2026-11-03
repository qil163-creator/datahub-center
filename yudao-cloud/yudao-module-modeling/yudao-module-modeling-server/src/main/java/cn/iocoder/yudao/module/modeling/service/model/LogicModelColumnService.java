package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 逻辑模型字段表（真相源）读写
 */
public interface LogicModelColumnService {

    /** 按 sort、id 排序返回字段 */
    List<ModelFieldItem> listByModelId(Long modelId);

    /** 批量：modelId -> 字段列表（已按 sort 排） */
    Map<Long, List<ModelFieldItem>> listByModelIds(Collection<Long> modelIds);

    /**
     * 整单替换：软删该模型现有字段后按 field_uid 插入新列表。
     * 调用方须保证 fieldUid 已补齐且同模型内唯一。
     */
    void replaceByModelId(Long modelId, List<ModelFieldItem> fields);

    /** 随模型删除一并清理字段行 */
    void deleteByModelId(Long modelId);
}
