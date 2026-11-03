package cn.iocoder.yudao.module.modeling.dal.mysql.model;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicRelationDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * 数据建模-逻辑模型关系 Mapper
 *
 * @author Li
 */
@Mapper
public interface LogicRelationMapper extends BaseMapperX<LogicRelationDO> {

    /**
     * 查询两端模型均在给定集合内的关系
     */
    default List<LogicRelationDO> selectListByModelIds(Collection<Long> modelIds) {
        if (modelIds == null || modelIds.isEmpty()) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapperX<LogicRelationDO>()
                .in(LogicRelationDO::getSourceModelId, modelIds)
                .in(LogicRelationDO::getTargetModelId, modelIds)
                .orderByAsc(LogicRelationDO::getId));
    }

    /**
     * 按模型字段查询逻辑关系。
     *
     * @param sourceModelId 来源模型编号
     * @param targetModelId 目标模型编号
     * @param sourceField 来源字段
     * @param targetField 目标字段
     * @return 逻辑关系
     */
    default LogicRelationDO selectByModelFields(Long sourceModelId, Long targetModelId,
            String sourceField, String targetField) {
        return selectOne(new LambdaQueryWrapperX<LogicRelationDO>()
                .eq(LogicRelationDO::getSourceModelId, sourceModelId)
                .eq(LogicRelationDO::getTargetModelId, targetModelId)
                .eq(LogicRelationDO::getSourceField, sourceField)
                .eq(LogicRelationDO::getTargetField, targetField)
                .last("LIMIT 1"));
    }

    /**
     * 查询与模型相关的全部关系（源或目标）
     */
    default List<LogicRelationDO> selectListByModelId(Long modelId) {
        return selectList(new LambdaQueryWrapperX<LogicRelationDO>()
                .and(wrapper -> wrapper.eq(LogicRelationDO::getSourceModelId, modelId)
                        .or().eq(LogicRelationDO::getTargetModelId, modelId)));
    }

    /**
     * 删除与模型相关的全部关系（源或目标）
     */
    default void deleteByModelId(Long modelId) {
        delete(new LambdaQueryWrapperX<LogicRelationDO>()
                .and(wrapper -> wrapper.eq(LogicRelationDO::getSourceModelId, modelId)
                        .or().eq(LogicRelationDO::getTargetModelId, modelId)));
    }

}
