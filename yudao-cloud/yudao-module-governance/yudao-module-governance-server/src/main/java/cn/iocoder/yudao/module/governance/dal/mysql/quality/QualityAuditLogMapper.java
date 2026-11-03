package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.auditlog.QualityAuditLogPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityAuditLogDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 数据治理-数据质量-检测结果明细 Mapper
 *
 * @author Li
 */
@Mapper
public interface QualityAuditLogMapper extends BaseMapperX<QualityAuditLogDO> {

    /**
     * 查询质量审计日志分页。
     */
    default PageResult<QualityAuditLogDO> selectPage(QualityAuditLogPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<QualityAuditLogDO>()
                .eqIfPresent(QualityAuditLogDO::getInstanceId, reqVO.getInstanceId())
                .eqIfPresent(QualityAuditLogDO::getTaskId, reqVO.getTaskId())
                .likeIfPresent(QualityAuditLogDO::getTableName, reqVO.getTableName())
                .likeIfPresent(QualityAuditLogDO::getRuleName, reqVO.getRuleName())
                .eqIfPresent(QualityAuditLogDO::getResultType, reqVO.getResultType())
                .eqIfPresent(QualityAuditLogDO::getGroupName, reqVO.getGroupName())
                .betweenIfPresent(QualityAuditLogDO::getCheckTime, reqVO.getCheckTime())
                .orderByDesc(QualityAuditLogDO::getId));
    }

    /**
     * 按实例编号查询质量审计日志列表。
     */
    default List<QualityAuditLogDO> selectListByInstanceId(Long instanceId) {
        return selectList(new LambdaQueryWrapperX<QualityAuditLogDO>()
                .eq(QualityAuditLogDO::getInstanceId, instanceId)
                .orderByAsc(QualityAuditLogDO::getId));
    }

}
