package cn.iocoder.yudao.module.asset.dal.mysql.quality;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.asset.dal.dataobject.quality.AssetQualityTaskRuleDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

@Mapper
public interface AssetQualityTaskRuleMapper extends BaseMapperX<AssetQualityTaskRuleDO> {

    /**
     * 按任务编号查询资产质量任务规则列表。
     */
    default List<AssetQualityTaskRuleDO> selectListByTaskIds(Collection<Long> taskIds) {
        return selectList(new LambdaQueryWrapperX<AssetQualityTaskRuleDO>()
                .in(AssetQualityTaskRuleDO::getTaskId, taskIds)
                .orderByAsc(AssetQualityTaskRuleDO::getId));
    }

    /**
     * 按任务编号删除资产质量任务规则记录。
     */
    default void deleteByTaskId(Long taskId) {
        delete(AssetQualityTaskRuleDO::getTaskId, taskId);
    }

    /**
     * 按任务编号删除资产质量任务规则记录。
     */
    default void deleteByTaskIds(Collection<Long> taskIds) {
        deleteBatch(AssetQualityTaskRuleDO::getTaskId, taskIds);
    }

}
