package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillEventOutboxMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillEventTypeEnum;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillTaskGenerateReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillTaskPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillTaskProgressRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillTaskRespVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillAssigneeDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillPlanDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillPlanOrgAssigneeDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillPlanOrgDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillAssigneeMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillPlanMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillPlanOrgAssigneeMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillPlanOrgMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillWorkItemMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillAssigneeSourceEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillAssigneeStateEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillNotifyTypeEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillPlanStatusEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillSubmissionTypeEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillTaskStatusEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillWorkItemStatusEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/**
 * 在线填报-周期任务 Service 实现类
 *
 * @author Li
 */
@Slf4j
@Service
@Validated
public class FillTaskServiceImpl implements FillTaskService {

    @Resource
    private FillTaskMapper taskMapper;

    @Resource
    private FillWorkItemMapper workItemMapper;

    @Resource
    private FillAssigneeMapper assigneeMapper;

    @Resource
    private FillPlanMapper planMapper;

    @Resource
    private FillPlanOrgMapper planOrgMapper;

    @Resource
    private FillPlanOrgAssigneeMapper planOrgAssigneeMapper;

    @Resource
    private FillNotifyService notifyService;

    @Resource
    private FillBpmAdapter bpmAdapter;

    @Resource
    private FillEventOutboxMapper outboxMapper;

    /**
     * 按生成时点为计划生成周期任务及其下属组织填报实例与填报人
     *
     *  幂等：同一 (planId, periodKey) 已存在任务时直接返回既有任务编号（数据库唯一约束兜底）
     *
     * @param plan 计划
     * @param generateTime 生成时间
     * @return 生成或保存的记录编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long generateForPlan(FillPlanDO plan, LocalDateTime generateTime) {
        FillPeriodConfig config = FillPeriodCalculator.parseConfig(plan.getPeriodConfig());
        FillPeriodCalculator.PeriodInfo period = FillPeriodCalculator.buildPeriod(
                plan.getId(), plan.getPeriodType(), config, generateTime);
        // 幂等：同期任务已存在直接返回
        FillTaskDO exists = taskMapper.selectByPlanIdAndPeriodKey(plan.getId(), period.periodKey());
        if (exists != null) {
            return exists.getId();
        }
        FillTaskDO task = FillTaskDO.builder()
                .planId(plan.getId())
                .name(plan.getName() + "（" + period.periodKey() + "）")
                .tableId(plan.getTableId())
                .tableVersionId(plan.getTableVersionId())
                .processDefinitionKey(plan.getProcessDefinitionKey())
                // processDefinitionId 在本事务提交后冻结（见 registerProcessDefinitionFreeze），不在持锁事务中调用 BPM
                .periodKey(period.periodKey())
                .periodStart(period.periodStart())
                .periodEnd(period.periodEnd())
                .openTime(period.openTime())
                .deadlineTime(period.deadlineTime())
                .overduePolicy(plan.getOverduePolicy())
                .status(period.openTime().isAfter(LocalDateTime.now())
                        ? FillTaskStatusEnum.PENDING.getStatus() : FillTaskStatusEnum.RUNNING.getStatus())
                .totalOrgCount(0)
                .mergedOrgCount(0)
                .build();
        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException ex) {
            // 并发生成被唯一约束 (plan_id, period_key) 拦截，返回既有任务
            log.info("[generateForPlan][计划({}) 期次({}) 并发生成被唯一约束拦截]", plan.getId(), period.periodKey());
            return taskMapper.selectByPlanIdAndPeriodKey(plan.getId(), period.periodKey()).getId();
        }

        // 按计划组织快照生成工作项与填报人
        List<FillPlanOrgDO> orgs = planOrgMapper.selectListByPlanId(plan.getId());
        Map<Long, List<FillPlanOrgAssigneeDO>> assigneesByOrg = planOrgAssigneeMapper
                .selectListByPlanId(plan.getId()).stream()
                .collect(Collectors.groupingBy(FillPlanOrgAssigneeDO::getPlanOrgId));
        LocalDateTime now = LocalDateTime.now();
        for (FillPlanOrgDO org : orgs) {
            FillWorkItemDO workItem = FillWorkItemDO.builder()
                    .taskId(task.getId())
                    .planId(plan.getId())
                    .tableVersionId(plan.getTableVersionId())
                    .reportingOrgId(org.getReportingOrgId())
                    .orgName(org.getOrgName())
                    .orgCode(org.getOrgCode())
                    .orgPath(org.getOrgPath())
                    .status(FillWorkItemStatusEnum.PENDING.getStatus())
                    .overdue(0)
                    .submissionType(FillSubmissionTypeEnum.NORMAL.getType())
                    .serverVersion(0L)
                    .attemptCount(0)
                    .draftRowCount(0)
                    .deadlineTime(period.deadlineTime())
                    .build();
            workItemMapper.insert(workItem);

            List<FillPlanOrgAssigneeDO> defaults = assigneesByOrg.getOrDefault(org.getId(), List.of());
            for (FillPlanOrgAssigneeDO defaultAssignee : defaults) {
                assigneeMapper.insert(FillAssigneeDO.builder()
                        .workItemId(workItem.getId())
                        .taskId(task.getId())
                        .userId(defaultAssignee.getUserId())
                        .userName(defaultAssignee.getUserName())
                        .deptId(defaultAssignee.getDeptId())
                        .state(FillAssigneeStateEnum.VALID.getState())
                        .source(FillAssigneeSourceEnum.PLAN_DEFAULT.getSource())
                        .effectiveTime(now)
                        .build());
            }
            notifyService.sendToUsers(FillNotifyTypeEnum.ASSIGNED, task, workItem,
                    convertList(defaults, FillPlanOrgAssigneeDO::getUserId), null);
        }
        FillTaskDO countUpdate = new FillTaskDO();
        countUpdate.setId(task.getId());
        countUpdate.setTotalOrgCount(orgs.size());
        taskMapper.updateById(countUpdate);
        registerProcessDefinitionFreeze(task.getId(), task.getProcessDefinitionKey());
        return task.getId();
    }

    /**
     * 任务生成事务提交后，冻结审批流程当前生效版本；此后流程升级不影响本任务。
     *
     * <p>远程查询放在事务提交之后，避免在发布计划等持锁事务中调用 BPM。冻结失败（BPM 不可用、流程未部署）
     * 不影响任务生成：首个提交启动审批时由投递 Job 再次冻结，同样只写入尚未冻结的任务。</p>
     */
    private void registerProcessDefinitionFreeze(Long taskId, String processDefinitionKey) {
        if (StrUtil.isBlank(processDefinitionKey)) {
            return;
        }
        Runnable freeze = () -> {
            try {
                freezeProcessDefinition(taskId, processDefinitionKey);
            } catch (Exception ex) {
                log.warn("[registerProcessDefinitionFreeze][任务({}) 冻结审批流程({})版本失败，将在首次启动审批时重试]",
                        taskId, processDefinitionKey, ex);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    freeze.run();
                }
            });
        } else {
            freeze.run();
        }
    }

    /**
     * 冻结任务的审批流程版本（仅当尚未冻结），返回任务最终生效的冻结版本。
     *
     * @param taskId 任务编号
     * @param processDefinitionKey 流程定义标识
     * @return 冻结的流程定义编号
     */
    @Override
    public String freezeProcessDefinition(Long taskId, String processDefinitionKey) {
        FillTaskDO current = taskMapper.selectById(taskId);
        if (current != null && StrUtil.isNotBlank(current.getProcessDefinitionId())) {
            return current.getProcessDefinitionId();
        }
        String activeId = bpmAdapter.getActiveProcessDefinitionId(processDefinitionKey);
        if (StrUtil.isBlank(activeId)) {
            throw exception(FILL_TASK_PROCESS_NO_ACTIVE_VERSION, processDefinitionKey);
        }
        taskMapper.freezeProcessDefinitionIfAbsent(taskId, activeId);
        // 并发冻结时以先写入者为准，重新读取最终值
        FillTaskDO frozen = taskMapper.selectById(taskId);
        return frozen == null ? activeId : frozen.getProcessDefinitionId();
    }

    /**
     * 管理员把任务冻结的审批流程切换到当前生效版本；该任务下因旧版本停用而等待重试的启动审批事件立即重新投递。
     * 已发起的审批实例不受影响。
     *
     * @param id 任务编号
     * @return 切换后的流程定义编号
     */
    @Override
    public String switchProcessDefinition(Long id) {
        FillTaskDO task = taskMapper.selectById(id);
        if (task == null) {
            throw exception(FILL_TASK_NOT_EXISTS);
        }
        if (StrUtil.isBlank(task.getProcessDefinitionKey())) {
            throw exception(FILL_TASK_PROCESS_NOT_CONFIGURED);
        }
        // 远程查询在事务外执行；本地只有一条 CAS 更新与一条重排期更新
        String activeId = bpmAdapter.getActiveProcessDefinitionId(task.getProcessDefinitionKey());
        if (StrUtil.isBlank(activeId)) {
            throw exception(FILL_TASK_PROCESS_NO_ACTIVE_VERSION, task.getProcessDefinitionKey());
        }
        if (!activeId.equals(task.getProcessDefinitionId())
                && taskMapper.switchProcessDefinition(id, task.getProcessDefinitionId(), activeId) == 0) {
            throw exception(FILL_TASK_STATUS_INVALID);
        }
        int rescheduled = outboxMapper.rescheduleFailedByTask(id,
                FillEventTypeEnum.FILL_BPM_START_REQUESTED.getType(), LocalDateTime.now());
        log.info("[switchProcessDefinition][任务({}) 审批流程版本 {} -> {}，重新投递启动事件 {} 个]",
                id, task.getProcessDefinitionId(), activeId, rescheduled);
        return activeId;
    }

    /**
     * 手工生成/补生成任务（一次性任务重新生成、调度停机后的历史期次补生成）
     *
     * @param reqVO 请求参数
     * @return 手工生成数值
     */
    @Override
    public Long manualGenerate(FillTaskGenerateReqVO reqVO) {
        FillPlanDO plan = planMapper.selectById(reqVO.getPlanId());
        if (plan == null) {
            throw exception(FILL_PLAN_NOT_EXISTS);
        }
        if (!FillPlanStatusEnum.ENABLED.getStatus().equals(plan.getStatus())) {
            throw exception(FILL_PLAN_STATUS_INVALID);
        }
        LocalDateTime generateTime = reqVO.getGenerateTime() != null
                ? reqVO.getGenerateTime() : LocalDateTime.now();
        return generateForPlan(plan, generateTime);
    }

    /**
     * 获得采集任务分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 采集任务分页结果
     */
    @Override
    public PageResult<FillTaskRespVO> getTaskPage(FillTaskPageReqVO pageReqVO) {
        PageResult<FillTaskDO> pageResult = taskMapper.selectPage(pageReqVO);
        return new PageResult<>(BeanUtils.toBean(pageResult.getList(), FillTaskRespVO.class),
                pageResult.getTotal());
    }

    /**
     * 获得采集任务。
     *
     * @param id 记录编号
     * @return 填报任务响应数据
     */
    @Override
    public FillTaskRespVO getTask(Long id) {
        FillTaskDO task = taskMapper.selectById(id);
        if (task == null) {
            throw exception(FILL_TASK_NOT_EXISTS);
        }
        return BeanUtils.toBean(task, FillTaskRespVO.class);
    }

    /**
     * 任务进度：各状态工作项数量、逾期数、完成率
     *
     * @param id 记录编号
     * @return 填报任务进度响应数据
     */
    @Override
    public FillTaskProgressRespVO getTaskProgress(Long id) {
        FillTaskDO task = taskMapper.selectById(id);
        if (task == null) {
            throw exception(FILL_TASK_NOT_EXISTS);
        }
        List<FillWorkItemDO> items = workItemMapper.selectListByTaskId(id);
        FillTaskProgressRespVO respVO = new FillTaskProgressRespVO();
        respVO.setTaskId(id);
        respVO.setTotalOrgCount(items.size());
        respVO.setStatusCounts(items.stream()
                .collect(Collectors.groupingBy(FillWorkItemDO::getStatus, Collectors.counting())));
        int merged = (int) items.stream()
                .filter(item -> FillWorkItemStatusEnum.MERGED.getStatus().equals(item.getStatus())).count();
        respVO.setMergedCount(merged);
        respVO.setOverdueCount((int) items.stream()
                .filter(item -> Integer.valueOf(1).equals(item.getOverdue())).count());
        respVO.setCompletionRate(CollUtil.isEmpty(items) ? 0 : merged * 100 / items.size());
        return respVO;
    }

}
