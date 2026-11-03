package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityTaskRuleDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 数据治理-数据质量-任务规则关联 Mapper
 *
 * @author Li
 */
@Mapper
public interface QualityTaskRuleMapper extends BaseMapperX<QualityTaskRuleDO> {

    /**
     * 按任务编号查询质量任务规则列表。
     */
    default List<QualityTaskRuleDO> selectListByTaskId(Long taskId) {
        return selectList(new LambdaQueryWrapperX<QualityTaskRuleDO>()
                .eq(QualityTaskRuleDO::getTaskId, taskId)
                .orderByAsc(QualityTaskRuleDO::getId));
    }

    /**
     * 按任务编号查询质量任务规则列表。
     */
    default List<QualityTaskRuleDO> selectListByTaskIds(java.util.Collection<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapperX<QualityTaskRuleDO>()
                .in(QualityTaskRuleDO::getTaskId, taskIds));
    }

    /**
     * 按任务编号删除质量任务规则记录。
     */
    default void deleteByTaskId(Long taskId) {
        delete(new LambdaQueryWrapperX<QualityTaskRuleDO>()
                .eq(QualityTaskRuleDO::getTaskId, taskId));
    }

    /**
     * 按规则编号查询质量任务规则数量。
     */
    default Long selectCountByRuleId(Long ruleId) {
        return selectCount(new LambdaQueryWrapperX<QualityTaskRuleDO>()
                .eq(QualityTaskRuleDO::getRuleId, ruleId));
    }

    /**
     * 按任务编号与规则编号查询质量任务规则数量。
     */
    default Long selectCountByTaskIdAndRuleId(Long taskId, Long ruleId) {
        return selectCount(new LambdaQueryWrapperX<QualityTaskRuleDO>()
                .eq(QualityTaskRuleDO::getTaskId, taskId)
                .eq(QualityTaskRuleDO::getRuleId, ruleId));
    }

}
