package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillPlanPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillPlanRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillPlanSaveReqVO;

/**
 * 在线填报-采集计划 Service 接口
 *
 * @author Li
 */
public interface FillPlanService {

    /**
     * 创建采集计划。
     *
     * @param createReqVO 创建参数
     * @return 生成或保存的记录编号
     */
    Long createPlan(FillPlanSaveReqVO createReqVO);

    /**
     * 更新计划；启用中的计划不允许变更模板版本与周期类型（只影响未来任务的字段可改）
     */
    void updatePlan(FillPlanSaveReqVO updateReqVO);

    /**
     * 删除计划（仅草稿/停用状态）
     */
    void deletePlan(Long id);

    /**
     * 获得采集计划（含目标组织与填报人）。
     *
     * @param id 记录编号
     * @return 填报计划响应数据
     */
    FillPlanRespVO getPlan(Long id);

    /**
     * 获得采集计划分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 采集计划分页结果
     */
    PageResult<FillPlanRespVO> getPlanPage(FillPlanPageReqVO pageReqVO);

    /**
     * 发布（启用）计划：完整性校验（模板版本有效、组织与填报人齐备、目标表写入冲突检测）、
     * 登记目标表写入范围、计算首次生成时点；一次性计划发布即生成任务
     */
    void publishPlan(Long id);

    /**
     * 停用计划：停止未来任务生成并释放目标表写入登记；已生成任务不受影响
     */
    void disablePlan(Long id);

}
