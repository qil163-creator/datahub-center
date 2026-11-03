package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityTaskEvaluationDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface QualityTaskEvaluationMapper extends BaseMapperX<QualityTaskEvaluationDO> {

    /**
     * 按任务编号查询质量任务评估。
     */
    default List<QualityTaskEvaluationDO> selectByTaskId(Long taskId) {
        return selectList(new LambdaQueryWrapperX<QualityTaskEvaluationDO>()
                .eq(QualityTaskEvaluationDO::getTaskId, taskId).orderByAsc(QualityTaskEvaluationDO::getId));
    }

    /**
     * 按任务编号删除质量任务评估记录。
     */
    default void deleteByTaskId(Long taskId) {
        delete(new LambdaQueryWrapperX<QualityTaskEvaluationDO>().eq(QualityTaskEvaluationDO::getTaskId, taskId));
    }
}
