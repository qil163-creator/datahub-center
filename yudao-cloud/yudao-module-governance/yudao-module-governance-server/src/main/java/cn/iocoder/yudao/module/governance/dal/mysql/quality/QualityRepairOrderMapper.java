package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.repair.QualityRepairOrderPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRepairOrderDO;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 数据治理-数据质量-修复工单 Mapper
 *
 * @author Li
 */
@Mapper
public interface QualityRepairOrderMapper extends BaseMapperX<QualityRepairOrderDO> {

    /**
     * 查询质量修复工单分页。
     */
    default PageResult<QualityRepairOrderDO> selectPage(QualityRepairOrderPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<QualityRepairOrderDO>()
                .likeIfPresent(QualityRepairOrderDO::getOrderTitle, reqVO.getOrderTitle())
                .eqIfPresent(QualityRepairOrderDO::getRepairType, reqVO.getRepairType())
                .eqIfPresent(QualityRepairOrderDO::getApproveStatus, reqVO.getApproveStatus())
                .eqIfPresent(QualityRepairOrderDO::getExecStatus, reqVO.getExecStatus())
                .eqIfPresent(QualityRepairOrderDO::getVerifyStatus, reqVO.getVerifyStatus())
                .orderByDesc(QualityRepairOrderDO::getId));
    }

    /**
     * 按对象编号查询成功。
     */
    default List<QualityRepairOrderDO> selectSuccessByObjectIds(Collection<Long> objectIds) {
        if (objectIds == null || objectIds.isEmpty()) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapperX<QualityRepairOrderDO>()
                .in(QualityRepairOrderDO::getObjectId, objectIds)
                .eq(QualityRepairOrderDO::getExecStatus, QualityRepairOrderDO.EXEC_SUCCESS));
    }

    /**
     * 按指定条件读取并锁定质量修复工单记录，供事务内一致性校验或修改使用。
     */
    default QualityRepairOrderDO selectForUpdate(Long id) {
        return selectOne(new LambdaQueryWrapperX<QualityRepairOrderDO>()
                .eq(QualityRepairOrderDO::getId, id)
                .last("FOR UPDATE"));
    }

    /**
     * 领取执行。
     */
    default int claimExecution(Long id, String token, String operationId, LocalDateTime leaseUntil) {
        return update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaUpdate(QualityRepairOrderDO.class)
                .set(QualityRepairOrderDO::getExecStatus, QualityRepairOrderDO.EXEC_RUNNING)
                .set(QualityRepairOrderDO::getExecutionToken, token)
                .set(QualityRepairOrderDO::getOperationId, operationId)
                .set(QualityRepairOrderDO::getLeaseUntil, leaseUntil)
                .set(QualityRepairOrderDO::getExecResult, null)
                .eq(QualityRepairOrderDO::getId, id)
                .eq(QualityRepairOrderDO::getApproveStatus, QualityRepairOrderDO.APPROVE_PASSED)
                .in(QualityRepairOrderDO::getExecStatus,
                        QualityRepairOrderDO.EXEC_PENDING, QualityRepairOrderDO.EXEC_FAILED_CONFIRMED)
                .isNull(QualityRepairOrderDO::getExecutionToken));
    }

    /**
     * 完成若令牌匹配。
     */
    default int completeIfTokenMatches(Long id, String token, String execStatus, String execResult,
            LocalDateTime execTime) {
        return update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaUpdate(QualityRepairOrderDO.class)
                .set(QualityRepairOrderDO::getExecStatus, execStatus)
                .set(QualityRepairOrderDO::getExecResult, execResult)
                .set(QualityRepairOrderDO::getExecTime, execTime)
                .set(QualityRepairOrderDO::getExecutionToken, null)
                .set(QualityRepairOrderDO::getLeaseUntil, null)
                .eq(QualityRepairOrderDO::getId, id)
                .eq(QualityRepairOrderDO::getExecutionToken, token)
                .eq(QualityRepairOrderDO::getExecStatus, QualityRepairOrderDO.EXEC_RUNNING));
    }

}
