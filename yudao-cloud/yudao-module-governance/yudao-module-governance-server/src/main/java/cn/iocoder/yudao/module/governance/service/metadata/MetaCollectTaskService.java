package cn.iocoder.yudao.module.governance.service.metadata;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaCollectTaskPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaCollectTaskSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaCollectTaskDO;

/**
 * 数据治理-元数据采集任务 Service
 *
 * @author Li
 */
public interface MetaCollectTaskService {

    /**
     * 创建采集任务（默认下线、调度停止，由发布/调度开关显式开启）
     */
    Long createTask(MetaCollectTaskSaveReqVO createReqVO);

    /**
     * 更新采集任务
     */
    void updateTask(MetaCollectTaskSaveReqVO updateReqVO);

    /**
     * 删除采集任务（仅下线状态可删）
     */
    void deleteTask(Long id);

    /**
     * 获取采集任务
     */
    MetaCollectTaskDO getTask(Long id);

    /**
     * 采集任务分页
     */
    PageResult<MetaCollectTaskDO> getTaskPage(MetaCollectTaskPageReqVO pageReqVO);

    /**
     * 任务上下线（发布/取消发布任务）
     */
    void updateEnabled(Long id, Integer enabled);

    /**
     * 调度启停（开启时校验 cron 并推进下次执行时间）
     */
    void updateScheduleEnabled(Long id, Integer scheduleEnabled);

    /**
     * 手动执行一次
     */
    void runOnce(Long id);

    /**
     * 删除元数据采集任务的任务。
     */
    void deleteTasks(java.util.List<Long> ids);
}
