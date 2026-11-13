package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillTaskCloseRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillTaskGenerateReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillTaskPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillTaskProgressRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillTaskRespVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillPlanDO;

import java.time.LocalDateTime;

/**
 * 在线填报-周期任务 Service 接口
 *
 * @author Li
 */
public interface FillTaskService {

    /**
     * 按生成时点为计划生成周期任务及其下属组织填报实例与填报人
     *
     * 幂等：同一 (planId, periodKey) 已存在任务时直接返回既有任务编号（数据库唯一约束兜底）
     *
     * @param plan         采集计划（需携带完整配置）
     * @param generateTime 生成时点（补生成历史期次时传历史时点）
     * @return 任务编号
     */
    Long generateForPlan(FillPlanDO plan, LocalDateTime generateTime);

    /**
     * 手工生成/补生成任务（一次性任务重新生成、调度停机后的历史期次补生成）
     */
    Long manualGenerate(FillTaskGenerateReqVO reqVO);

    /**
     * 冻结任务的审批流程版本（仅当尚未冻结），返回最终冻结的流程定义编号
     */
    String freezeProcessDefinition(Long taskId, String processDefinitionKey);

    /**
     * 把任务冻结的审批流程切换到当前生效版本，并立即重新投递等待中的启动审批事件
     */
    String switchProcessDefinition(Long id);

    /**
     * 获得采集任务分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 采集任务分页结果
     */
    PageResult<FillTaskRespVO> getTaskPage(FillTaskPageReqVO pageReqVO);

    /**
     * 获得采集任务。
     *
     * @param id 记录编号
     * @return 填报任务响应数据
     */
    FillTaskRespVO getTask(Long id);

    /**
     * 任务进度：各状态工作项数量、逾期数、完成率
     */
    FillTaskProgressRespVO getTaskProgress(Long id);

    /**
     * 结束填报：取消尚未提交的组织实例，放弃进行中的更正；审批/合并中的实例继续走完，全部完成后任务自动结束
     *
     * @param id 任务编号
     * @return 结束结果
     */
    FillTaskCloseRespVO closeTask(Long id);

}
