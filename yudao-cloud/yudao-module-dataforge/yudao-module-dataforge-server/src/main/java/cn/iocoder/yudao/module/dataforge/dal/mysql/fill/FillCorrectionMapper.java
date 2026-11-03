package cn.iocoder.yudao.module.dataforge.dal.mysql.fill;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillCorrectionDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 在线填报-数据更正实例 Mapper
 *
 * @author Li
 */
@Mapper
public interface FillCorrectionMapper extends BaseMapperX<FillCorrectionDO> {

    /** 工作项上进行中的更正（草拟/审批中） */
    default FillCorrectionDO selectActiveByWorkItemId(Long workItemId) {
        return selectOne(new LambdaQueryWrapperX<FillCorrectionDO>()
                .eq(FillCorrectionDO::getWorkItemId, workItemId)
                .in(FillCorrectionDO::getStatus, 0, 1)
                .orderByDesc(FillCorrectionDO::getId)
                .last("LIMIT 1"));
    }

    /**
     * 按更正提交编号查询填报更正。
     *
     * @param submissionId 提交编号
     * @return 填报更正
     */
    default FillCorrectionDO selectByCorrectionSubmissionId(Long submissionId) {
        return selectOne(FillCorrectionDO::getCorrectionSubmissionId, submissionId);
    }

    /**
     * 按查询条件分页读取填报更正。
     *
     * @param pageParam 分页查询条件
     * @param taskId 任务编号
     * @param status 目标状态
     * @return 按查询条件分页读取填报更正结果
     */
    default PageResult<FillCorrectionDO> selectPage(PageParam pageParam, Long taskId, Integer status) {
        return selectPage(pageParam, new LambdaQueryWrapperX<FillCorrectionDO>()
                .eqIfPresent(FillCorrectionDO::getTaskId, taskId)
                .eqIfPresent(FillCorrectionDO::getStatus, status)
                .orderByDesc(FillCorrectionDO::getId));
    }

    /**
     * 按工作项编号查询填报更正列表。
     *
     * @param workItemId 工作项编号
     * @return 按工作项编号查询填报更正列表结果集合
     */
    default List<FillCorrectionDO> selectListByWorkItemId(Long workItemId) {
        return selectList(new LambdaQueryWrapperX<FillCorrectionDO>()
                .eq(FillCorrectionDO::getWorkItemId, workItemId)
                .orderByDesc(FillCorrectionDO::getId));
    }

}
