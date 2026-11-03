package cn.iocoder.yudao.module.governance.service.statictask;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.statictask.vo.SafetyStaticTaskLogPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.statictask.vo.SafetyStaticTaskPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.statictask.vo.SafetyStaticTaskSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.statictask.SafetyStaticTaskDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.statictask.SafetyStaticTaskLogDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 静态脱敏任务 Service 接口
 *
 * @author Li
 */
public interface SafetyStaticTaskService {

    /**
     * 创建静态脱敏任务
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createTask(@Valid SafetyStaticTaskSaveReqVO createReqVO);

    /**
     * 更新静态脱敏任务
     *
     * @param updateReqVO 更新信息
     */
    void updateTask(@Valid SafetyStaticTaskSaveReqVO updateReqVO);

    /**
     * 删除静态脱敏任务
     *
     * @param id 编号
     */
    void deleteTask(Long id);

    /**
     * 批量删除静态脱敏任务
     *
     * @param ids 编号列表
     */
    void deleteTaskListByIds(List<Long> ids);

    /**
     * 获得静态脱敏任务
     *
     * @param id 编号
     * @return 任务
     */
    SafetyStaticTaskDO getTask(Long id);

    /**
     * 获得静态脱敏任务分页
     *
     * @param pageReqVO 分页查询
     * @return 任务分页
     */
    PageResult<SafetyStaticTaskDO> getTaskPage(SafetyStaticTaskPageReqVO pageReqVO);

    /**
     * 更新任务上下线状态
     *
     * @param id     编号
     * @param status 状态：0-上线、1-下线
     */
    void updateTaskStatus(Long id, Integer status);

    /**
     * 手动执行任务（异步）
     *
     * @param id 任务编号
     */
    void executeTask(Long id);

    /**
     * 调度触发执行任务（异步，运行中则跳过）
     *
     * @param id 任务编号
     */
    void executeTaskBySchedule(Long id);

    /**
     * 获得任务执行记录分页
     *
     * @param pageReqVO 分页查询
     * @return 执行记录分页
     */
    PageResult<SafetyStaticTaskLogDO> getTaskLogPage(SafetyStaticTaskLogPageReqVO pageReqVO);

}
