package cn.iocoder.yudao.module.dataforge.dal.mysql.fill;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillTaskPageReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 在线填报-周期任务 Mapper
 *
 * @author Li
 */
@Mapper
public interface FillTaskMapper extends BaseMapperX<FillTaskDO> {

    /**
     * 按查询条件分页读取填报任务。
     *
     * @param reqVO 请求参数
     * @return 按查询条件分页读取填报任务结果
     */
    default PageResult<FillTaskDO> selectPage(FillTaskPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<FillTaskDO>()
                .eqIfPresent(FillTaskDO::getPlanId, reqVO.getPlanId())
                .likeIfPresent(FillTaskDO::getName, reqVO.getName())
                .eqIfPresent(FillTaskDO::getStatus, reqVO.getStatus())
                .eqIfPresent(FillTaskDO::getPeriodKey, reqVO.getPeriodKey())
                .orderByDesc(FillTaskDO::getId));
    }

    /**
     * 按计划编号与所属期标识查询填报任务。
     *
     * @param planId 计划编号
     * @param periodKey 所属期标识
     * @return 填报任务
     */
    default FillTaskDO selectByPlanIdAndPeriodKey(Long planId, String periodKey) {
        return selectOne(new LambdaQueryWrapperX<FillTaskDO>()
                .eq(FillTaskDO::getPlanId, planId)
                .eq(FillTaskDO::getPeriodKey, periodKey));
    }

}
