package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityEtlBatchRunDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

@Mapper
public interface QualityEtlBatchRunMapper extends BaseMapperX<QualityEtlBatchRunDO> {

    /**
     * 按任务与目标批次查询质量ETL批次运行。
     */
    default QualityEtlBatchRunDO selectByTaskAndTargetBatch(Long taskId, Long targetBatchId) {
        return selectOne(new LambdaQueryWrapperX<QualityEtlBatchRunDO>()
                .eq(QualityEtlBatchRunDO::getQualityTaskId, taskId)
                .eq(QualityEtlBatchRunDO::getTargetBatchId, targetBatchId));
    }

    /**
     * 按目标批次编号查询质量ETL批次运行。
     */
    default QualityEtlBatchRunDO selectByTargetBatchId(Long targetBatchId) {
        return selectOne(new LambdaQueryWrapperX<QualityEtlBatchRunDO>()
                .eq(QualityEtlBatchRunDO::getTargetBatchId, targetBatchId));
    }

    /**
     * 按内联报告编号查询质量ETL批次运行。
     */
    default java.util.List<QualityEtlBatchRunDO> selectByInlineReportId(Long reportId) {
        return selectList(new LambdaQueryWrapperX<QualityEtlBatchRunDO>()
                .eq(QualityEtlBatchRunDO::getInlineReportId, reportId)
                .orderByAsc(QualityEtlBatchRunDO::getTargetBatchId));
    }

    /**
     * 按回放所属批次编号查询质量ETL批次运行。
     */
    default java.util.List<QualityEtlBatchRunDO> selectByReplayOfBatchId(Long targetBatchId) {
        return selectList(new LambdaQueryWrapperX<QualityEtlBatchRunDO>()
                .eq(QualityEtlBatchRunDO::getReplayOfBatchId, targetBatchId)
                .orderByAsc(QualityEtlBatchRunDO::getTargetBatchId));
    }

    /** 按治理执行实例编号查询绑定的 ETL 批次；instance_id 具有唯一约束，最多一条。 */
    default QualityEtlBatchRunDO selectByInstanceId(Long instanceId) {
        if (instanceId == null) {
            return null;
        }
        return selectOne(new LambdaQueryWrapperX<QualityEtlBatchRunDO>()
                .eq(QualityEtlBatchRunDO::getInstanceId, instanceId));
    }

    /** 按一批治理执行实例编号查询绑定的 ETL 批次，供实例分页回填来源信息。 */
    default List<QualityEtlBatchRunDO> selectListByInstanceIds(Collection<Long> instanceIds) {
        if (CollUtil.isEmpty(instanceIds)) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapperX<QualityEtlBatchRunDO>()
                .in(QualityEtlBatchRunDO::getInstanceId, instanceIds));
    }
}
