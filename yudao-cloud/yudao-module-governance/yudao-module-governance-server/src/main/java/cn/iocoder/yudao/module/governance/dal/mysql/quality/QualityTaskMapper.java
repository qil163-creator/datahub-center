package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.task.QualityTaskPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityTaskDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 数据治理-数据质量-质量任务 Mapper
 *
 * @author Li
 */
@Mapper
public interface QualityTaskMapper extends BaseMapperX<QualityTaskDO> {

    /**
     * 查询质量任务分页。
     */
    default PageResult<QualityTaskDO> selectPage(QualityTaskPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<QualityTaskDO>()
                .likeIfPresent(QualityTaskDO::getTaskName, reqVO.getTaskName())
                .likeIfPresent(QualityTaskDO::getTaskCategory, reqVO.getTaskCategory())
                .eqIfPresent(QualityTaskDO::getScheduleType, reqVO.getScheduleType())
                .eqIfPresent(QualityTaskDO::getTaskStatus, reqVO.getTaskStatus())
                .eqIfPresent(QualityTaskDO::getRuleSetId, reqVO.getRuleSetId())
                .orderByDesc(QualityTaskDO::getId));
    }

    /**
     * 按名称查询质量任务。
     */
    default QualityTaskDO selectByName(String taskName) {
        return selectOne(new LambdaQueryWrapperX<QualityTaskDO>().eq(QualityTaskDO::getTaskName, taskName));
    }

    /**
     * 按任务编码查询质量任务。
     */
    default QualityTaskDO selectByTaskCode(String taskCode) {
        return selectOne(new LambdaQueryWrapperX<QualityTaskDO>().eq(QualityTaskDO::getTaskCode, taskCode));
    }

    /** 已上线的定时任务列表（调度扫描用） */
    default List<QualityTaskDO> selectTimerOnlineList() {
        return selectList(new LambdaQueryWrapperX<QualityTaskDO>()
                .eq(QualityTaskDO::getScheduleType, QualityTaskDO.SCHEDULE_TIMER)
                .eq(QualityTaskDO::getTaskStatus, QualityTaskDO.STATUS_ONLINE)
                .isNotNull(QualityTaskDO::getCronExpression));
    }

    /** 按任务编码前缀查询（模板物化任务：TPL-{模板ID}-*） */
    default List<QualityTaskDO> selectListByCodePrefix(String codePrefix) {
        return selectList(new LambdaQueryWrapperX<QualityTaskDO>()
                .likeRight(QualityTaskDO::getTaskCode, codePrefix)
                .eq(QualityTaskDO::getTaskStatus, QualityTaskDO.STATUS_ONLINE));
    }

}
