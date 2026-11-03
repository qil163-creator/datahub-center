package cn.iocoder.yudao.module.modeling.dal.mysql.model;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelColumnDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Mapper
public interface LogicModelColumnMapper extends BaseMapperX<LogicModelColumnDO> {

    /**
     * 按标准编号查询逻辑模型列列表。
     *
     * @param standardId 标准编号
     * @return 按标准编号查询逻辑模型列列表结果集合
     */
    default List<LogicModelColumnDO> selectListByStandardId(Long standardId) {
        return selectList(new LambdaQueryWrapperX<LogicModelColumnDO>()
                .eq(LogicModelColumnDO::getStandardId, standardId));
    }

    /**
     * 按模型编号查询逻辑模型列列表。
     *
     * @param modelId 模型编号
     * @return 按模型编号查询逻辑模型列列表结果集合
     */
    default List<LogicModelColumnDO> selectListByModelId(Long modelId) {
        return selectList(new LambdaQueryWrapperX<LogicModelColumnDO>()
                .eq(LogicModelColumnDO::getModelId, modelId)
                .orderByAsc(LogicModelColumnDO::getSort)
                .orderByAsc(LogicModelColumnDO::getId));
    }

    /**
     * 按模型编号集合查询逻辑模型列列表。
     *
     * @param modelIds 模型编号集合
     * @return 按模型编号集合查询逻辑模型列列表结果集合
     */
    default List<LogicModelColumnDO> selectListByModelIds(Collection<Long> modelIds) {
        if (modelIds == null || modelIds.isEmpty()) {
            return Collections.emptyList();
        }
        return selectList(new LambdaQueryWrapperX<LogicModelColumnDO>()
                .in(LogicModelColumnDO::getModelId, modelIds)
                .orderByAsc(LogicModelColumnDO::getSort)
                .orderByAsc(LogicModelColumnDO::getId));
    }

    /**
     * 按模型编号删除逻辑模型列。
     *
     * @param modelId 模型编号
     */
    default void deleteByModelId(Long modelId) {
        delete(new LambdaQueryWrapperX<LogicModelColumnDO>()
                .eq(LogicModelColumnDO::getModelId, modelId));
    }
}
