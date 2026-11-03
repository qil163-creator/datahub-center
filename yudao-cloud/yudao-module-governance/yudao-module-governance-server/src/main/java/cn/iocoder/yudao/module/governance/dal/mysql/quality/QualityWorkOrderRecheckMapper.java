package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityWorkOrderRecheckDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityWorkOrderSubmissionDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface QualityWorkOrderRecheckMapper extends BaseMapperX<QualityWorkOrderRecheckDO> {

    /** 调用方必须已持有所属工单行锁，编号与实例/命令回执在同一事务中提交。 */
    default int nextAttemptNo(Long submissionId) {
        QualityWorkOrderRecheckDO latest = selectOne(new LambdaQueryWrapperX<QualityWorkOrderRecheckDO>()
                .eq(QualityWorkOrderRecheckDO::getSubmissionId, submissionId)
                .orderByDesc(QualityWorkOrderRecheckDO::getAttemptNo).last("LIMIT 1"));
        return latest == null ? 1 : Math.addExact(latest.getAttemptNo(), 1);
    }

    /**
     * 查询运行中。
     */
    default List<QualityWorkOrderRecheckDO> selectRunning(int limit) {
        return selectList(new LambdaQueryWrapperX<QualityWorkOrderRecheckDO>()
                .eq(QualityWorkOrderRecheckDO::getStatus, QualityWorkOrderSubmissionDO.RECHECK_RUNNING)
                .orderByAsc(QualityWorkOrderRecheckDO::getId).last("LIMIT " + Math.clamp(limit, 1, 50)));
    }

    /**
     * 按提交记录查询质量工单复检。
     */
    default List<QualityWorkOrderRecheckDO> selectBySubmission(Long submissionId) {
        return selectList(new LambdaQueryWrapperX<QualityWorkOrderRecheckDO>()
                .eq(QualityWorkOrderRecheckDO::getSubmissionId, submissionId)
                .orderByDesc(QualityWorkOrderRecheckDO::getAttemptNo));
    }

    /**
     * 按请求查询质量工单复检。
     */
    default QualityWorkOrderRecheckDO selectByRequest(Long workOrderId, String requestId) {
        return selectOne(new LambdaQueryWrapperX<QualityWorkOrderRecheckDO>()
                .eq(QualityWorkOrderRecheckDO::getWorkOrderId, workOrderId)
                .eq(QualityWorkOrderRecheckDO::getRequestId, requestId));
    }

    /**
     * 按实例查询质量工单复检。
     */
    default QualityWorkOrderRecheckDO selectByInstance(Long instanceId) {
        if (instanceId == null) {
            return null;
        }
        return selectOne(QualityWorkOrderRecheckDO::getInstanceId, instanceId);
    }
}
