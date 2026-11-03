package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityWorkOrderDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface QualityWorkOrderMapper extends BaseMapperX<QualityWorkOrderDO> {

    /**
     * 按指定条件读取并锁定质量工单记录，供事务内一致性校验或修改使用。
     */
    @Select("SELECT * FROM dh_governance_quality_work_order "
            + "WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    QualityWorkOrderDO selectByIdForUpdate(@Param("id") Long id);

    /**
     * 查询可见分页。
     */
    default PageResult<QualityWorkOrderDO> selectVisiblePage(QualityWorkOrderPageReqVO reqVO,
            LambdaQueryWrapperX<QualityWorkOrderDO> scope) {
        return selectPage(reqVO, scope
                .likeIfPresent(QualityWorkOrderDO::getOrderName, reqVO.getOrderName())
                .likeIfPresent(QualityWorkOrderDO::getTaskName, reqVO.getTaskName())
                .eqIfPresent(QualityWorkOrderDO::getOrderStatus, reqVO.getOrderStatus())
                .eqIfPresent(QualityWorkOrderDO::getResponsibilityStatus, reqVO.getResponsibilityStatus())
                .eqIfPresent(QualityWorkOrderDO::getOwnerUserId, reqVO.getOwnerUserId())
                .eqIfPresent(QualityWorkOrderDO::getHandlingDeptId, reqVO.getHandlingDeptId())
                .eqIfPresent(QualityWorkOrderDO::getCloseType, reqVO.getCloseType())
                .eqIfPresent(QualityWorkOrderDO::getProblemType, reqVO.getProblemType())
                .eqIfPresent(QualityWorkOrderDO::getSeverity, reqVO.getSeverity())
                .betweenIfPresent(QualityWorkOrderDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(QualityWorkOrderDO::getId));
    }

    /**
     * 按所属人查询待办列表。
     */
    default List<QualityWorkOrderDO> selectTodoListByOwner(Long ownerUserId) {
        return selectList(new LambdaQueryWrapperX<QualityWorkOrderDO>()
                .eq(QualityWorkOrderDO::getOwnerUserId, ownerUserId)
                .in(QualityWorkOrderDO::getOrderStatus,
                        QualityWorkOrderDO.STATUS_PENDING,
                        QualityWorkOrderDO.STATUS_IN_PROGRESS,
                        QualityWorkOrderDO.STATUS_PENDING_RECHECK,
                        QualityWorkOrderDO.STATUS_RECHECKING,
                        QualityWorkOrderDO.STATUS_PENDING_CONFIRMATION)
                .orderByDesc(QualityWorkOrderDO::getId));
    }

}
