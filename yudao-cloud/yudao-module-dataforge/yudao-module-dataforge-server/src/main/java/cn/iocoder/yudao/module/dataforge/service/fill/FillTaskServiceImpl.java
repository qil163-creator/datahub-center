package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillEventOutboxMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillEventTypeEnum;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillTaskCloseRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillTaskGenerateReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillTaskPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillTaskProgressRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillTaskRespVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillAssigneeDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillPlanDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillPlanOrgAssigneeDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillPlanOrgDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillSubmissionDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillSubmissionMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillApprovalStatusEnum;
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
import cn.iocoder.yudao.module.dataforge.enums.fill.FillPeriodTypeEnum;
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
import java.util.ArrayList;
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

    @Resource
    private FillCorrectionService correctionService;

    @Resource
    private FillDraftService draftService;

    @Resource
    private FillCollabService collabService;

    @Resource
    private FillTaskAccessGuard accessGuard;

    @Resource
    private FillPreviousPeriodService previousPeriodService;

    @Resource
    private FillHierarchyService hierarchyService;

    @Resource
    private FillSubmissionMapper submissionMapper;

    /** 单次扫描开放的任务数上限 */
    private static final int OPEN_SCAN_LIMIT = 200;

    /** 结束填报时单个实例遇到并发状态迁移的最大重读次数 */
    private static final int CLOSE_MAX_ATTEMPTS = 3;

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

        // 上期数据带入：周期计划开启开关时，按组织带入上一期已审批通过的数据
        FillTaskDO previousTask = shouldPrefill(plan, task)
                ? taskMapper.selectPreviousTask(plan.getId(), task.getPeriodStart(), task.getId()) : null;
        Long prefillOperator = previousTask == null ? null : resolvePrefillOperator(plan);

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
                    .level(0)
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
            if (previousTask != null) {
                prefillLastPeriod(previousTask, workItem, prefillOperator);
            }
            notifyService.sendToUsers(FillNotifyTypeEnum.ASSIGNED, task, workItem,
                    convertList(defaults, FillPlanOrgAssigneeDO::getUserId), null);
        }
        // 多级下发：各中间单位保存过默认下发名单的，按名单自动下发到下一级（逐级递归）
        for (FillWorkItemDO top : workItemMapper.selectListByTaskId(task.getId())) {
            if (top.getParentWorkItemId() == null) {
                hierarchyService.applyDispatchTemplates(task, top);
            }
        }
        FillTaskDO countUpdate = new FillTaskDO();
        countUpdate.setId(task.getId());
        countUpdate.setTotalOrgCount(orgs.size());
        taskMapper.updateById(countUpdate);
        registerProcessDefinitionFreeze(task.getId(), task.getProcessDefinitionKey());
        return task.getId();
    }

    /** 只有周期计划开启"上期数据带入"且任务有所属期起点时才带入 */
    private boolean shouldPrefill(FillPlanDO plan, FillTaskDO task) {
        return Integer.valueOf(1).equals(plan.getPrefillLastPeriod())
                && !FillPeriodTypeEnum.ONCE.getType().equals(plan.getPeriodType())
                && task.getPeriodStart() != null;
    }

    /** 上期带入记入操作日志的操作人：计划创建人（即任务发起者），无法识别时记为系统 0 */
    private Long resolvePrefillOperator(FillPlanDO plan) {
        String creator = StrUtil.trim(plan.getCreator());
        return StrUtil.isNotBlank(creator) && cn.hutool.core.util.NumberUtil.isLong(creator)
                ? Long.valueOf(creator) : 0L;
    }

    /**
     * 把同一组织上一期最新一次审批通过的快照带入本期草稿；零上报、未通过或上期未纳入该组织时跳过。
     */
    private void prefillLastPeriod(FillTaskDO previousTask, FillWorkItemDO workItem, Long operatorUserId) {
        FillSubmissionDO submission = previousPeriodService.findApprovedSubmission(previousTask,
                workItem.getReportingOrgId());
        if (submission == null || FillSubmissionTypeEnum.EMPTY.getType().equals(submission.getSubmissionType())) {
            return;
        }
        int rows = draftService.prefillFromSubmission(workItem, submission.getId(), operatorUserId);
        if (rows > 0) {
            log.info("[prefillLastPeriod][工作项({}) 从上期任务({}) 快照({}) 带入 {} 行]",
                    workItem.getId(), previousTask.getId(), submission.getId(), rows);
        }
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
        accessGuard.checkInitiator(task);
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
        // 完成率只按顶层实例计算：下级数据随上级一起上报，不单独计入
        List<FillWorkItemDO> all = workItemMapper.selectListByTaskId(id);
        List<FillWorkItemDO> items = all.stream().filter(item -> item.getParentWorkItemId() == null).toList();
        FillTaskProgressRespVO respVO = new FillTaskProgressRespVO();
        respVO.setTaskId(id);
        respVO.setChildOrgCount(all.size() - items.size());
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

    // ==================== 到期开放 ====================

    /**
     * 把已到开放时间的“待开始”任务转为进行中，并通知填报人任务开始。
     *
     * <p>状态迁移走 CAS，多实例并发时每个任务只开放、只通知一次。生成时就已开放的任务不经过这里，
     * 填报人已在生成时收到“被指定为填报人”通知。</p>
     *
     * @param now 当前时间
     * @return 本次开放的任务数
     */
    @Override
    public int openDueTasks(LocalDateTime now) {
        int opened = 0;
        for (FillTaskDO task : taskMapper.selectListToOpen(now, OPEN_SCAN_LIMIT)) {
            if (taskMapper.compareAndSetStatus(task.getId(), FillTaskStatusEnum.PENDING.getStatus(),
                    FillTaskStatusEnum.RUNNING.getStatus()) == 0) {
                continue;
            }
            opened++;
            task.setStatus(FillTaskStatusEnum.RUNNING.getStatus());
            for (FillWorkItemDO item : workItemMapper.selectListByTaskId(task.getId())) {
                if (!FillWorkItemStatusEnum.EDITABLE_STATUSES.contains(item.getStatus())) {
                    continue;
                }
                try {
                    notifyService.sendToUsers(FillNotifyTypeEnum.TASK_STARTED, task, item,
                            convertList(assigneeMapper.selectValidListByWorkItemId(item.getId()),
                                    FillAssigneeDO::getUserId), null);
                } catch (Exception ex) {
                    log.warn("[openDueTasks][任务({}) 工作项({}) 任务开始通知失败：{}]",
                            task.getId(), item.getId(), ex.getMessage());
                }
            }
        }
        return opened;
    }

    // ==================== 结束填报 ====================

    /**
     * 结束填报：发起者提前结束本期采集。
     *
     * <ul>
     *     <li>待填报/填报中/整改中 → 已取消，未提交的草稿保留但不再可编辑；</li>
     *     <li>已合并后发起的更正（整改中）→ 撤销更正、恢复为已合并，正式数据保持不变；</li>
     *     <li>提交校验中/待启动审批/审批中/待合并/合并中/合并失败 → 不打断，走完后由合并成功回调结束任务；</li>
     *     <li>没有仍在进行中的实例时立即结束：有已合并组织为"已完成"，否则为"已取消"。</li>
     * </ul>
     * 审批中的实例结束后若被驳回会回到整改中，可再次执行本操作取消。
     * 数据范围：按钮权限之外，仅任务发起者（计划创建人）或超级管理员可操作，见 {@link FillTaskAccessGuard}。
     *
     * @param id 任务编号
     * @return 结束结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public FillTaskCloseRespVO closeTask(Long id) {
        FillTaskDO task = taskMapper.selectById(id);
        if (task == null) {
            throw exception(FILL_TASK_NOT_EXISTS);
        }
        accessGuard.checkInitiator(task);
        if (!FillTaskStatusEnum.PENDING.getStatus().equals(task.getStatus())
                && !FillTaskStatusEnum.RUNNING.getStatus().equals(task.getStatus())) {
            throw exception(FILL_TASK_STATUS_INVALID);
        }

        int merged = 0;
        int inFlight = 0;
        List<FillWorkItemDO> cancelled = new ArrayList<>();
        List<FillWorkItemDO> restored = new ArrayList<>();
        List<FillWorkItemDO> items = workItemMapper.selectListByTaskId(id);
        // 下级实例：未提交的取消；等待上级审核的终止审核并取消；已汇入上级的保持不变。下级不计入任务完成判断
        for (FillWorkItemDO item : items) {
            if (item.getParentWorkItemId() == null) {
                continue;
            }
            Integer before = item.getStatus();
            Integer finalStatus = closeChildWorkItem(item);
            if (FillWorkItemStatusEnum.CANCELLED.getStatus().equals(finalStatus)
                    && !FillWorkItemStatusEnum.CANCELLED.getStatus().equals(before)) {
                item.setStatus(finalStatus);
                cancelled.add(item);
            }
        }
        for (FillWorkItemDO item : items) {
            if (item.getParentWorkItemId() != null) {
                continue;
            }
            Integer finalStatus = closeWorkItem(item);
            if (FillWorkItemStatusEnum.MERGED.getStatus().equals(finalStatus)) {
                merged++;
                if (item.getStatus() != null && !FillWorkItemStatusEnum.MERGED.getStatus().equals(item.getStatus())) {
                    restored.add(item);
                }
            } else if (FillWorkItemStatusEnum.CANCELLED.getStatus().equals(finalStatus)) {
                if (!FillWorkItemStatusEnum.CANCELLED.getStatus().equals(item.getStatus())) {
                    cancelled.add(item);
                }
            } else {
                inFlight++;
            }
            item.setStatus(finalStatus);
        }

        FillTaskDO update = new FillTaskDO();
        update.setId(id);
        update.setMergedOrgCount(merged);
        Integer taskStatus = task.getStatus();
        if (inFlight == 0) {
            taskStatus = merged > 0 ? FillTaskStatusEnum.FINISHED.getStatus() : FillTaskStatusEnum.CANCELLED.getStatus();
        } else if (FillTaskStatusEnum.PENDING.getStatus().equals(taskStatus)) {
            taskStatus = FillTaskStatusEnum.RUNNING.getStatus();
        }
        update.setStatus(taskStatus);
        taskMapper.updateById(update);

        for (FillWorkItemDO item : cancelled) {
            notifyClosed(task, item);
        }
        for (FillWorkItemDO item : restored) {
            notifyClosed(task, item);
        }
        log.info("[closeTask][任务({}) 结束填报：取消 {} 个、恢复已合并 {} 个、进行中 {} 个，任务状态 {}]",
                id, cancelled.size(), restored.size(), inFlight, taskStatus);

        FillTaskCloseRespVO respVO = new FillTaskCloseRespVO();
        respVO.setCancelledCount(cancelled.size());
        respVO.setRestoredCount(restored.size());
        respVO.setInFlightCount(inFlight);
        respVO.setTaskStatus(taskStatus);
        return respVO;
    }

    /**
     * 按当前状态结束单个工作项；状态迁移全部走 CAS，遇并发迁移时重读后再判定。
     *
     * @param item 工作项（读取时的状态）
     * @return 处理后的状态：已合并 / 已取消 / 其他（仍在审批或合并中）
     */
    private Integer closeWorkItem(FillWorkItemDO item) {
        Integer status = item.getStatus();
        for (int attempt = 0; attempt < CLOSE_MAX_ATTEMPTS; attempt++) {
            if (FillWorkItemStatusEnum.MERGED.getStatus().equals(status)
                    || FillWorkItemStatusEnum.CANCELLED.getStatus().equals(status)) {
                return status;
            }
            if (FillWorkItemStatusEnum.REWORKING.getStatus().equals(status)
                    && correctionService.abandonForTaskClose(item.getId())) {
                return FillWorkItemStatusEnum.MERGED.getStatus();
            }
            if (!FillWorkItemStatusEnum.EDITABLE_STATUSES.contains(status)) {
                return status;
            }
            if (workItemMapper.compareAndSetStatus(item.getId(), status,
                    FillWorkItemStatusEnum.CANCELLED.getStatus()) > 0) {
                return FillWorkItemStatusEnum.CANCELLED.getStatus();
            }
            FillWorkItemDO latest = workItemMapper.selectById(item.getId());
            if (latest == null) {
                return FillWorkItemStatusEnum.CANCELLED.getStatus();
            }
            status = latest.getStatus();
        }
        return status;
    }

    /**
     * 结束填报时处理下级实例。
     *
     * @param item 下级实例
     * @return 处理后的状态
     */
    private Integer closeChildWorkItem(FillWorkItemDO item) {
        Integer status = item.getStatus();
        if (FillWorkItemStatusEnum.APPROVING.getStatus().equals(status) && item.getCurrentSubmissionId() != null) {
            FillSubmissionDO submission = submissionMapper.selectById(item.getCurrentSubmissionId());
            if (submission != null && Integer.valueOf(FillSubmissionDO.REVIEW_PARENT).equals(submission.getReviewMode())
                    && submissionMapper.markReviewed(submission.getId(), FillApprovalStatusEnum.TERMINATED.getStatus(),
                    null, "发起者结束填报", LocalDateTime.now()) > 0
                    && workItemMapper.compareAndSetStatus(item.getId(), status,
                    FillWorkItemStatusEnum.CANCELLED.getStatus()) > 0) {
                return FillWorkItemStatusEnum.CANCELLED.getStatus();
            }
            return status;
        }
        if (FillWorkItemStatusEnum.EDITABLE_STATUSES.contains(status)
                && workItemMapper.compareAndSetStatus(item.getId(), status,
                FillWorkItemStatusEnum.CANCELLED.getStatus()) > 0) {
            return FillWorkItemStatusEnum.CANCELLED.getStatus();
        }
        return status;
    }

    /** 通知受影响实例的有效填报人，并提示打开的工作台重新加载 */
    private void notifyClosed(FillTaskDO task, FillWorkItemDO item) {
        List<Long> userIds = convertList(assigneeMapper.selectValidListByWorkItemId(item.getId()),
                FillAssigneeDO::getUserId);
        notifyService.sendToUsers(FillNotifyTypeEnum.TASK_CLOSED, task, item, userIds, null);
        try {
            collabService.broadcast(item.getId(), FillSubmissionServiceImpl.WS_STATUS_CHANGED,
                    Map.of("workItemId", item.getId(), "status", item.getStatus()));
        } catch (Exception ex) {
            log.warn("[notifyClosed][工作项({}) 广播失败：{}]", item.getId(), ex.getMessage());
        }
    }

}
