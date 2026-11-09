package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskVersionDO;

import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevRecordDO;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.debug.DevTableColumnRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.DevTaskPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.DevTaskSaveReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.DevTaskScheduleReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskVersionDO;

import java.util.List;

import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.DevTaskRunReqVO;

/**
 * 数据开发-开发任务 Service 接口
 *
 * @author Li
 */
public interface DevTaskService {

    /**
     * 创建开发任务。
     *
     * @param createReqVO 创建参数
     * @return 生成或保存的记录编号
     */
    Long createTask(DevTaskSaveReqVO createReqVO);

    /**
     * 保存开发任务并立即生成当前运行版本。
     *
     * @param updateReqVO 更新参数
     */
    void updateTask(DevTaskSaveReqVO updateReqVO);

    /**
     * 删除开发任务。
     *
     * @param id 记录编号
     */
    void deleteTask(Long id);

    /**
     * 获得开发任务。
     *
     * @param id 记录编号
     * @return 开发任务
     */
    DevTaskDO getTask(Long id);

    /**
     * 获得开发任务分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 开发任务分页结果
     */
    PageResult<DevTaskDO> getTaskPage(DevTaskPageReqVO pageReqVO);

    /**
     * 获得当前平台的数据开发运行统计。
     *
     * @return 统计
     */
    default cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.DevTaskStatisticsRespVO getStatistics() {
        return getStatistics(null);
    }

    /** 按运行模式统计；空值保留全量工作台的统计契约。 */
    cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.DevTaskStatisticsRespVO getStatistics(
            String workloadMode);

    /**
     * 预览 Cron 接下来五次运行时间。
     *
     * @param cronExpression Cron表达式
     * @return  Cron 接下来五次运行时间预览结果集合
     */
    List<java.time.LocalDateTime> previewCron(String cronExpression);

    /**
     * 按目录获得开发任务列表（资源树用，含子目录）。
     *
     * @param dirId 目录编号
     * @return 按目录获得开发任务列表（资源树用，含子目录）结果集合
     */
    default List<DevTaskDO> getTaskListByDir(Long dirId) {
        return getTaskListByDir(dirId, null);
    }

    /** 资源树按运行模式分流，空值保留原有工作台范围。 */
    List<DevTaskDO> getTaskListByDir(Long dirId, String workloadMode);

    /**
     * 更新已保存任务的启用状态。
     *
     * @param id 记录编号
     * @param status 目标状态
     */
    void updateTaskStatus(Long id, Integer status);

    /**
     * 更新任务调度设置。
     *
     * @param reqVO 请求参数
     */
    void updateTaskSchedule(DevTaskScheduleReqVO reqVO);

    /**
     * 手动运行任务（生成执行实例）
     */
    Long runTask(DevTaskRunReqVO reqVO);

    /**
     * 补数据发起前按“执行一次”的同等护栏校验当前登录人，返回将被批次冻结的当前保存版本。
     *
     * @param taskId 任务编号
     * @return 当前保存版本
     */
    DevTaskVersionDO validateBackfill(Long taskId);

    /**
     * 补数据推进时按冻结版本登记一个业务日期的运行意图（无登录上下文，使用版本持久化的执行身份）。
     * 任务当前版本已不是冻结版本时抛出 {@code DEV_BACKFILL_VERSION_CHANGED}。
     *
     * @param taskId 任务编号
     * @param versionId 冻结版本
     * @param runKey 运行幂等键
     * @param businessDate 业务日期
     * @return 运行实例编号
     */
    Long enqueueBackfill(Long taskId, Long versionId, String runKey, java.time.LocalDate businessDate);

    /** 纯读检查当前本人保存的开发版本及完整运行护栏，不创建记录或外部定义。 */
    cn.iocoder.yudao.module.dataforge.model.dev.DevJobNode previewJobNode(Long taskId);

    /** 无登录上下文的作业派发使用已持久化的实际执行人，不能借用负责人。 */
    cn.iocoder.yudao.module.dataforge.model.dev.DevJobNode previewJobNodeForActor(Long taskId, Long actor);

    /** 后台作业只准备入队时冻结的版本，不切换当前新版本。 */
    cn.iocoder.yudao.module.dataforge.model.dev.DevJobNode prepareJobNodeForActor(Long taskId, Long expectedVersionId, String runKey, java.time.LocalDate businessDate, Long actor);

    /** 对本作业冻结执行人核验开发记录的运行、原生权限与冻结项目。 */
    DevRecordDO authorizeJobNode(Long recordId, Long actor);

    /** 受理停止或抢占时按发起人检查旧冻结版本，不借用原执行人权限。 */
    void authorizeJobTaskStop(Long taskId, Long versionId, Long caller);

    /** 对已创建记录检查发起人的运行、原生权限及冻结项目边界。 */
    DevRecordDO authorizeJobNodeForCaller(Long recordId, Long caller);


    /** 作业抢占使用旧运行的冻结执行人请求取消，终态仍由引擎确认。 */
    void stopJobNode(Long recordId, Long actor);


    /** 为一次已授权作业创建专属冻结运行及受管子工作流，不独立触发执行。 */
    cn.iocoder.yudao.module.dataforge.model.dev.DevJobNode prepareJobNode(Long taskId, Long expectedVersionId,
            String runKey, java.time.LocalDate businessDate);

    /** 绑定由作业核实父子关系的真实实例；仍校验开发记录的冻结项目与工作流。 */
    cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevRecordDO bindJobNode(Long recordId, Long projectCode,
            Long workflowCode, Integer instanceId);

    /** 复用开发任务的原生进程终态与质量门禁观察。 */
    cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevRecordDO observeJobNode(Long recordId);

    /** 仅在确认父流程从未发送触发请求时释放准备的子运行。 */
    void cancelPreparedJobNode(Long recordId);


    /**
     * 导入真实 DS 周期触发实例并持久化运行意图
     */
    Long runTaskScheduled(cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevScheduleDO binding, Long instanceId,
            java.time.LocalDateTime scheduledTime);

    /** 登记已核验的失败周期标记，仅持久化失败记录，不投递业务程序。 */
    Long recordTaskScheduledFailure(cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevScheduleDO binding,
            Long instanceId, java.time.LocalDateTime scheduledTime, String markerStatus);

    /**
     * 查询表字段元数据（编辑器补全用）
     */
    List<DevTableColumnRespVO> getTableColumns(Long databaseId, String tableName);

    /**
     * 获得任务版本列表。
     *
     * @param taskId 任务编号
     * @return 任务版本列表结果集合
     */
    List<DevTaskVersionDO> getTaskVersionList(Long taskId);

    /**
     * 回滚到指定版本（版本内容写回任务并生成新版本）
     */
    void rollbackVersion(Long versionId, Integer expectedRevision);

}
