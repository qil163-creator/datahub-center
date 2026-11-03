package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.hutool.core.collection.CollUtil;
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
                // TODO 阶段 4：通过 BPM 适配器解析并冻结 processDefinitionId，流程升级不影响本任务
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
        return task.getId();
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
