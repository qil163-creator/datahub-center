package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.instance.QualityTaskInstancePageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityTaskInstanceDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 数据治理-数据质量-任务实例 Mapper
 *
 * @author Li
 */
@Mapper
public interface QualityTaskInstanceMapper extends BaseMapperX<QualityTaskInstanceDO> {

    /**
     * 查询质量任务实例分页。
     */
    default PageResult<QualityTaskInstanceDO> selectPage(QualityTaskInstancePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<QualityTaskInstanceDO>()
                .eqIfPresent(QualityTaskInstanceDO::getTaskId, reqVO.getTaskId())
                .likeIfPresent(QualityTaskInstanceDO::getInstanceName, reqVO.getInstanceName())
                .eqIfPresent(QualityTaskInstanceDO::getInstanceStatus, reqVO.getInstanceStatus())
                .eqIfPresent(QualityTaskInstanceDO::getRunType, reqVO.getRunType())
                .betweenIfPresent(QualityTaskInstanceDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(QualityTaskInstanceDO::getId));
    }

    /**
     * 按任务编号查询运行中数量。
     */
    default Long selectRunningCountByTaskId(Long taskId) {
        return selectCount(new LambdaQueryWrapperX<QualityTaskInstanceDO>()
                .eq(QualityTaskInstanceDO::getTaskId, taskId)
                .eq(QualityTaskInstanceDO::getInstanceStatus, QualityTaskInstanceDO.STATUS_RUNNING));
    }

    /**
     * 按任务编号查询质量任务实例数量。
     */
    default Long selectCountByTaskId(Long taskId) {
        return selectCount(new LambdaQueryWrapperX<QualityTaskInstanceDO>()
                .eq(QualityTaskInstanceDO::getTaskId, taskId));
    }

    /**
     * 按任务编号查询末个。
     */
    default QualityTaskInstanceDO selectLastByTaskId(Long taskId) {
        List<QualityTaskInstanceDO> list = selectList(new LambdaQueryWrapperX<QualityTaskInstanceDO>()
                .eq(QualityTaskInstanceDO::getTaskId, taskId)
                .orderByDesc(QualityTaskInstanceDO::getId)
                .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

}
