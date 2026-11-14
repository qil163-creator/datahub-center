package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillAssigneeRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillChildActionReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillChildrenRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillDispatchCandidateRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillDispatchReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillRollupRespVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillAssigneeDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillDispatchTemplateDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillRollupSourceDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillSubmissionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillSubmissionRowDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTableVersionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillAssigneeMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillDispatchTemplateMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillRollupSourceMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillSubmissionMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillSubmissionRowMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillWorkItemMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillApprovalStatusEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillAssigneeSourceEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillAssigneeStateEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillNotifyTypeEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillSubmissionTypeEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillTaskStatusEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillWorkItemStatusEnum;
import cn.iocoder.yudao.module.system.api.dept.DeptApi;
import cn.iocoder.yudao.module.system.api.dept.dto.DeptRespDTO;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/**
 * 在线填报-多级下发与逐级汇总
 *
 * <p>组织实例构成一棵树：发起者直接下发的是顶层实例，中间单位可以继续向本单位部门子树内的组织下发。</p>
 * <ul>
 *     <li>下级提交后不启动审批流程，由上级实例的有效填报人审核（review_mode=2）；通过后下级进入“已汇入上级”，
 *     数据不单独写入正式表。</li>
 *     <li>上级“汇总下级数据”时，把审核通过的下级提交写入自己的草稿（明细汇集保留来源组织，分组合计按模板规则计算），
 *     汇总行只读；下级数据之后再变化，汇总标记为过期，重新汇总前不能上报。</li>
 *     <li>只有顶层实例走计划配置的审批流程，审批通过后按每行的来源组织合并到正式表。</li>
 * </ul>
 * <p>数据范围：下发、审核、退回、撤销、汇总、催办都要求操作人是上级实例的有效填报人；查看下级还允许任务发起者，
 * 见 {@link FillWorkItemService#hasViewPermission}。远程调用（部门、用户）在写事务之外完成。</p>
 *
 * @author Li
 */
@Slf4j
@Service
public class FillHierarchyService {

    /** 最多下发的层数（顶层为 0） */
    static final int MAX_LEVEL = 5;

    static final String MODE_DETAIL = "DETAIL";
    static final String MODE_GROUP = "GROUP";

    static final Set<String> GROUP_FUNCS = Set.of("SUM", "MAX", "MIN", "COUNT", "FIRST");

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Resource
    private FillWorkItemMapper workItemMapper;
    @Resource
    private FillAssigneeMapper assigneeMapper;
    @Resource
    private FillTaskMapper taskMapper;
    @Resource
    private FillSubmissionMapper submissionMapper;
    @Resource
    private FillSubmissionRowMapper submissionRowMapper;
    @Resource
    private FillDispatchTemplateMapper dispatchTemplateMapper;
    @Resource
    private FillRollupSourceMapper rollupSourceMapper;
    @Resource
    private FillWorkItemService workItemService;
    @Resource
    private FillDraftService draftService;
    @Resource
    private FillTableVersionService tableVersionService;
    @Resource
    private FillNotifyService notifyService;
    @Resource
    private FillCollabService collabService;
    @Resource
    private DeptApi deptApi;
    @Resource
    private AdminUserApi adminUserApi;
    @Resource
    private PlatformTransactionManager transactionManager;

    // ================================================================ 查询

    /**
     * 直接下级与汇总状态。
     *
     * @param parentId 上级实例编号
     * @return 下级与汇总状态
     */
    public FillChildrenRespVO getChildren(Long parentId) {
        FillWorkItemDO parent = workItemService.getWorkItemValidated(parentId);
        workItemService.checkViewPermission(parent, SecurityFrameworkUtils.getLoginUserId());
        List<FillWorkItemDO> children = workItemMapper.selectListByParentId(parentId);
        Map<Long, Long> sources = sourceMap(parentId);
        Map<Long, FillSubmissionDO> submissions = new HashMap<>();
        List<Long> submissionIds = children.stream().map(FillWorkItemDO::getCurrentSubmissionId)
                .filter(Objects::nonNull).toList();
        if (!submissionIds.isEmpty()) {
            submissionMapper.selectByIds(submissionIds).forEach(s -> submissions.put(s.getId(), s));
        }
        Map<Long, List<FillAssigneeDO>> assignees = children.isEmpty() ? Map.of()
                : assigneeMapper.selectValidListByWorkItemIds(convertList(children, FillWorkItemDO::getId)).stream()
                .collect(Collectors.groupingBy(FillAssigneeDO::getWorkItemId));
        Map<Long, Long> grandChildCounts = new HashMap<>();
        for (FillWorkItemDO child : children) {
            grandChildCounts.put(child.getId(), (long) workItemMapper.selectListByParentId(child.getId()).size());
        }
        List<FillChildrenRespVO.Child> result = new ArrayList<>();
        for (FillWorkItemDO child : children) {
            FillChildrenRespVO.Child vo = new FillChildrenRespVO.Child();
            vo.setWorkItemId(child.getId());
            vo.setReportingOrgId(child.getReportingOrgId());
            vo.setOrgName(child.getOrgName());
            vo.setLevel(child.getLevel());
            vo.setStatus(child.getStatus());
            vo.setOverdue(child.getOverdue());
            vo.setDeadlineTime(child.getDeadlineTime());
            vo.setDraftRowCount(child.getDraftRowCount());
            vo.setCurrentSubmissionId(child.getCurrentSubmissionId());
            FillSubmissionDO submission = child.getCurrentSubmissionId() == null ? null
                    : submissions.get(child.getCurrentSubmissionId());
            if (submission != null) {
                vo.setApprovalStatus(submission.getApprovalStatus());
                vo.setSubmitTime(submission.getSubmitTime());
            }
            vo.setAwaitingReview(isAwaitingReview(child, submission));
            vo.setIncluded(FillWorkItemStatusEnum.ROLLED_UP.getStatus().equals(child.getStatus())
                    && Objects.equals(sources.get(child.getId()), child.getCurrentSubmissionId()));
            vo.setChildCount(grandChildCounts.getOrDefault(child.getId(), 0L).intValue());
            vo.setAssignees(BeanUtils.toBean(assignees.getOrDefault(child.getId(), List.of()),
                    FillAssigneeRespVO.class));
            result.add(vo);
        }
        FillChildrenRespVO resp = new FillChildrenRespVO();
        resp.setChildren(result);
        resp.setRollup(rollupState(parent, children, sources));
        return resp;
    }

    /**
     * 可下发的下级组织：本单位部门子树内的组织（不含本单位），标出已在本期任务中的组织，并带出默认下发名单。
     *
     * @param parentId 上级实例编号
     * @return 候选组织
     */
    public List<FillDispatchCandidateRespVO> getDispatchCandidates(Long parentId) {
        FillWorkItemDO parent = workItemService.getWorkItemValidated(parentId);
        requireAssignee(parent, SecurityFrameworkUtils.getLoginUserId());
        List<DeptRespDTO> depts = nullToEmpty(deptApi.getChildDeptList(parent.getReportingOrgId()).getCheckedData());
        Set<Long> inTask = workItemMapper.selectListByTaskId(parent.getTaskId()).stream()
                .map(FillWorkItemDO::getReportingOrgId).collect(Collectors.toSet());
        Map<Long, FillDispatchTemplateDO> defaults = new HashMap<>();
        dispatchTemplateMapper.selectListByPlanAndParent(parent.getPlanId(), parent.getReportingOrgId())
                .forEach(template -> defaults.put(template.getReportingOrgId(), template));
        List<FillDispatchCandidateRespVO> result = new ArrayList<>();
        for (DeptRespDTO dept : depts) {
            if (dept.getId() == null || Objects.equals(dept.getId(), parent.getReportingOrgId())
                    || !Integer.valueOf(0).equals(dept.getStatus())) {
                continue;
            }
            FillDispatchCandidateRespVO vo = new FillDispatchCandidateRespVO();
            vo.setDeptId(dept.getId());
            vo.setName(dept.getName());
            vo.setParentId(dept.getParentId());
            vo.setInTask(inTask.contains(dept.getId()));
            FillDispatchTemplateDO template = defaults.get(dept.getId());
            vo.setDefaultAssignees(template == null ? List.of() : convertList(parseAssignees(template.getAssigneeUserIds()),
                    assignee -> {
                        FillDispatchCandidateRespVO.Assignee item = new FillDispatchCandidateRespVO.Assignee();
                        item.setUserId(assignee.userId());
                        item.setUserName(assignee.userName());
                        return item;
                    }));
            vo.setDefaultOffsetHours(template == null ? null : template.getDeadlineOffsetHours());
            result.add(vo);
        }
        return result;
    }

    // ================================================================ 下发

    /**
     * 向下级组织下发。
     *
     * <p>部门、用户校验（远程调用）在写事务之外完成；写入在独立事务中进行，同一任务内一个组织只出现一次
     * （数据库唯一约束兜底并发）。</p>
     *
     * @param parentId 上级实例编号
     * @param reqVO 下发内容
     * @return 新下发的实例数
     */
    public int dispatch(Long parentId, FillDispatchReqVO reqVO) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        FillWorkItemDO parent = workItemService.getWorkItemValidated(parentId);
        workItemService.checkEditPermission(parent, userId);
        int level = parent.getLevel() == null ? 0 : parent.getLevel();
        if (level >= MAX_LEVEL) {
            throw exception(FILL_DISPATCH_NOT_ALLOWED, "最多下发 " + MAX_LEVEL + " 级");
        }
        FillTaskDO task = taskMapper.selectById(parent.getTaskId());
        if (task == null || (!FillTaskStatusEnum.RUNNING.getStatus().equals(task.getStatus())
                && !FillTaskStatusEnum.PENDING.getStatus().equals(task.getStatus()))) {
            throw exception(FILL_DISPATCH_NOT_ALLOWED, "任务已结束");
        }
        // 1. 远程校验：组织在本单位子树内，填报人属于该组织子树且为启用状态
        Map<Long, DeptRespDTO> scope = new HashMap<>();
        nullToEmpty(deptApi.getChildDeptList(parent.getReportingOrgId()).getCheckedData())
                .forEach(dept -> scope.put(dept.getId(), dept));
        Set<Long> inTask = workItemMapper.selectListByTaskId(parent.getTaskId()).stream()
                .map(FillWorkItemDO::getReportingOrgId).collect(Collectors.toSet());
        Set<Long> userIds = new LinkedHashSet<>();
        reqVO.getOrgs().forEach(org -> userIds.addAll(org.getAssigneeUserIds()));
        Map<Long, AdminUserRespDTO> users = new HashMap<>();
        nullToEmpty(adminUserApi.getUserList(userIds).getCheckedData()).forEach(user -> users.put(user.getId(), user));
        LocalDateTime now = LocalDateTime.now();
        List<PlannedChild> planned = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (FillDispatchReqVO.Org org : reqVO.getOrgs()) {
            DeptRespDTO dept = scope.get(org.getReportingOrgId());
            if (dept == null || Objects.equals(dept.getId(), parent.getReportingOrgId())
                    || !Integer.valueOf(0).equals(dept.getStatus())
                    || inTask.contains(dept.getId()) || !seen.add(dept.getId())) {
                throw exception(FILL_DISPATCH_ORG_INVALID, dept != null ? dept.getName() : org.getReportingOrgId());
            }
            validateDeadline(parent, org.getDeadlineTime(), now);
            Set<Long> orgScope = new HashSet<>();
            orgScope.add(dept.getId());
            scope.values().stream().filter(candidate -> isDescendant(candidate, dept.getId(), scope))
                    .forEach(candidate -> orgScope.add(candidate.getId()));
            List<AssigneeRef> assignees = new ArrayList<>();
            for (Long assigneeId : new LinkedHashSet<>(org.getAssigneeUserIds())) {
                AdminUserRespDTO user = users.get(assigneeId);
                if (user == null || !Integer.valueOf(0).equals(user.getStatus())
                        || user.getDeptId() == null || !orgScope.contains(user.getDeptId())) {
                    throw exception(FILL_DISPATCH_NOT_ALLOWED,
                            "填报人（" + assigneeId + "）须是【" + dept.getName() + "】或其下属部门的启用用户");
                }
                assignees.add(new AssigneeRef(user.getId(), StrUtil.blankToDefault(user.getNickname(),
                        String.valueOf(user.getId())), user.getDeptId()));
            }
            planned.add(new PlannedChild(dept.getId(), dept.getName(), null,
                    StrUtil.nullToEmpty(parent.getOrgPath()) + "/" + dept.getId(), org.getDeadlineTime(), assignees));
        }
        // 2. 写入
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        Integer created = transaction.execute(status -> {
            List<FillWorkItemDO> items = new ArrayList<>();
            for (PlannedChild child : planned) {
                items.add(createChild(task, parent, child, FillAssigneeSourceEnum.PARENT_DISPATCH));
            }
            if (Boolean.TRUE.equals(reqVO.getSaveAsDefault())) {
                saveDefaults(parent);
            }
            return items.size();
        });
        notifyChildrenChanged(parentId);
        log.info("[dispatch][实例({}) 用户({}) 下发 {} 个下级]", parentId, userId, created);
        return created == null ? 0 : created;
    }

    /**
     * 任务生成时按默认下发名单自动下发（递归到各级），在任务生成事务内执行，不做远程调用：
     * 组织与填报人使用名单里的快照。名单中的组织已在任务中（例如发起者直接下发）时跳过。
     *
     * @param task 任务
     * @param topItem 顶层实例
     */
    public void applyDispatchTemplates(FillTaskDO task, FillWorkItemDO topItem) {
        Set<Long> inTask = workItemMapper.selectListByTaskId(task.getId()).stream()
                .map(FillWorkItemDO::getReportingOrgId).collect(Collectors.toCollection(HashSet::new));
        Deque<FillWorkItemDO> queue = new ArrayDeque<>();
        queue.add(topItem);
        while (!queue.isEmpty()) {
            FillWorkItemDO parent = queue.poll();
            if ((parent.getLevel() == null ? 0 : parent.getLevel()) >= MAX_LEVEL) {
                continue;
            }
            for (FillDispatchTemplateDO template : dispatchTemplateMapper.selectListByPlanAndParent(
                    task.getPlanId(), parent.getReportingOrgId())) {
                if (!inTask.add(template.getReportingOrgId())) {
                    continue;
                }
                List<AssigneeRef> assignees = parseAssignees(template.getAssigneeUserIds());
                if (assignees.isEmpty()) {
                    continue;
                }
                LocalDateTime deadline = parent.getDeadlineTime() == null ? null
                        : parent.getDeadlineTime().minusHours(Math.max(0, template.getDeadlineOffsetHours() == null
                        ? 0 : template.getDeadlineOffsetHours()));
                if (deadline != null && task.getOpenTime() != null && !deadline.isAfter(task.getOpenTime())) {
                    deadline = parent.getDeadlineTime();
                }
                queue.add(createChild(task, parent, new PlannedChild(template.getReportingOrgId(),
                        template.getOrgName(), template.getOrgCode(), template.getOrgPath(), deadline, assignees),
                        FillAssigneeSourceEnum.PARENT_DISPATCH));
            }
        }
    }

    // ================================================================ 审核、退回、撤销、截止时间

    /**
     * 上级单位审核下级提交：通过 → 已汇入上级；驳回 → 整改中。
     *
     * @param reqVO 审核内容
     */
    @Transactional(rollbackFor = Exception.class)
    public void review(FillChildActionReqVO reqVO) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        FillWorkItemDO child = workItemService.getWorkItemValidated(reqVO.getChildWorkItemId());
        FillWorkItemDO parent = requireEditableParent(child, userId);
        FillSubmissionDO submission = child.getCurrentSubmissionId() == null ? null
                : submissionMapper.selectById(child.getCurrentSubmissionId());
        if (!isAwaitingReview(child, submission)) {
            throw exception(FILL_CHILD_STATUS_INVALID, "没有等待审核的提交");
        }
        boolean approve = Boolean.TRUE.equals(reqVO.getApprove());
        if (!approve && StrUtil.isBlank(reqVO.getReason())) {
            throw exception(FILL_CHILD_STATUS_INVALID, "驳回时请填写原因");
        }
        FillApprovalStatusEnum result = approve ? FillApprovalStatusEnum.APPROVED : FillApprovalStatusEnum.REJECTED;
        if (submissionMapper.markReviewed(submission.getId(), result.getStatus(), userId,
                StrUtil.trimToNull(reqVO.getReason()), LocalDateTime.now()) == 0) {
            throw exception(FILL_CHILD_STATUS_INVALID, "该提交已被处理");
        }
        Integer target = approve ? FillWorkItemStatusEnum.ROLLED_UP.getStatus()
                : FillWorkItemStatusEnum.REWORKING.getStatus();
        if (workItemMapper.compareAndSetStatus(child.getId(), FillWorkItemStatusEnum.APPROVING.getStatus(), target) == 0) {
            throw exception(FILL_CHILD_STATUS_INVALID, "下级状态已变化，请刷新");
        }
        FillTaskDO task = taskMapper.selectById(child.getTaskId());
        notifyService.sendToUsers(approve ? FillNotifyTypeEnum.APPROVED : FillNotifyTypeEnum.REJECTED, task, child,
                validAssignees(child.getId()), approve ? null : Map.of("reason", reqVO.getReason()));
        broadcastAfterCommit(child.getId(), target);
        notifyChildrenChanged(parent.getId());
    }

    /**
     * 逐级退回：上级把已汇入的下级退回整改（例如上级被驳回后需要下级修改）。
     *
     * @param reqVO 退回内容
     */
    @Transactional(rollbackFor = Exception.class)
    public void returnChild(FillChildActionReqVO reqVO) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (StrUtil.isBlank(reqVO.getReason())) {
            throw exception(FILL_CHILD_STATUS_INVALID, "退回时请填写原因");
        }
        FillWorkItemDO child = workItemService.getWorkItemValidated(reqVO.getChildWorkItemId());
        FillWorkItemDO parent = requireEditableParent(child, userId);
        if (workItemMapper.compareAndSetStatus(child.getId(), FillWorkItemStatusEnum.ROLLED_UP.getStatus(),
                FillWorkItemStatusEnum.REWORKING.getStatus()) == 0) {
            throw exception(FILL_CHILD_STATUS_INVALID, "只能退回已汇入的下级");
        }
        FillTaskDO task = taskMapper.selectById(child.getTaskId());
        notifyService.sendToUsers(FillNotifyTypeEnum.RETURNED_BY_PARENT, task, child, validAssignees(child.getId()),
                Map.of("reason", reqVO.getReason()));
        broadcastAfterCommit(child.getId(), FillWorkItemStatusEnum.REWORKING.getStatus());
        notifyChildrenChanged(parent.getId());
    }

    /**
     * 撤销下发：未提交的下级（及其再往下的未提交实例）改为已取消。已提交、已汇入的下级须先驳回或退回。
     *
     * @param reqVO 撤销内容
     * @return 取消的实例数
     */
    @Transactional(rollbackFor = Exception.class)
    public int cancelChild(FillChildActionReqVO reqVO) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        FillWorkItemDO child = workItemService.getWorkItemValidated(reqVO.getChildWorkItemId());
        requireEditableParent(child, userId);
        if (!FillWorkItemStatusEnum.EDITABLE_STATUSES.contains(child.getStatus())) {
            throw exception(FILL_CHILD_STATUS_INVALID, "已提交或已汇入的下级不能撤销，请先驳回或退回");
        }
        int cancelled = 0;
        Deque<FillWorkItemDO> queue = new ArrayDeque<>();
        queue.add(child);
        while (!queue.isEmpty()) {
            FillWorkItemDO item = queue.poll();
            if (FillWorkItemStatusEnum.EDITABLE_STATUSES.contains(item.getStatus())
                    && workItemMapper.compareAndSetStatus(item.getId(), item.getStatus(),
                    FillWorkItemStatusEnum.CANCELLED.getStatus()) > 0) {
                cancelled++;
                broadcastAfterCommit(item.getId(), FillWorkItemStatusEnum.CANCELLED.getStatus());
            }
            queue.addAll(workItemMapper.selectListByParentId(item.getId()));
        }
        log.info("[cancelChild][用户({}) 撤销下级实例({})，共取消 {} 个，原因：{}]", userId, child.getId(), cancelled,
                reqVO.getReason());
        return cancelled;
    }

    /**
     * 调整下级截止时间（不能晚于本单位截止时间）；清除逾期标记。
     *
     * @param reqVO 调整内容
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateChildDeadline(FillChildActionReqVO reqVO) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        FillWorkItemDO child = workItemService.getWorkItemValidated(reqVO.getChildWorkItemId());
        FillWorkItemDO parent = requireEditableParent(child, userId);
        if (!FillWorkItemStatusEnum.EDITABLE_STATUSES.contains(child.getStatus())) {
            throw exception(FILL_CHILD_STATUS_INVALID, "下级已提交");
        }
        if (reqVO.getDeadlineTime() == null) {
            throw exception(FILL_CHILD_STATUS_INVALID, "请选择新的截止时间");
        }
        validateDeadline(parent, reqVO.getDeadlineTime(), LocalDateTime.now());
        FillWorkItemDO update = new FillWorkItemDO();
        update.setId(child.getId());
        update.setDeadlineTime(reqVO.getDeadlineTime());
        update.setOverdue(0);
        workItemMapper.updateById(update);
        FillTaskDO task = taskMapper.selectById(child.getTaskId());
        notifyService.sendToUsers(FillNotifyTypeEnum.DEADLINE_EXTENDED, task, child, validAssignees(child.getId()),
                Map.of("deadline", TIME_FORMAT.format(reqVO.getDeadlineTime())));
    }

    /**
     * 催办未完成的直接下级。
     *
     * @param parentId 上级实例编号
     * @return 催办的实例数
     */
    public int urgeChildren(Long parentId) {
        FillWorkItemDO parent = workItemService.getWorkItemValidated(parentId);
        requireAssignee(parent, SecurityFrameworkUtils.getLoginUserId());
        return workItemService.urgeChildren(parent, workItemMapper.selectListByParentId(parentId));
    }

    // ================================================================ 汇总

    /**
     * 汇总下级数据：把审核通过的下级提交写入本单位草稿（替换上一次汇总的行），并记录汇总来源。
     *
     * @param parentId 上级实例编号
     * @param expectedVersion 客户端已同步到的服务端版本
     * @return 汇总结果
     */
    @Transactional(rollbackFor = Exception.class)
    public FillRollupRespVO rollup(Long parentId, long expectedVersion) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        FillWorkItemDO parent = workItemService.getWorkItemValidated(parentId);
        workItemService.checkEditPermission(parent, userId);
        List<FillWorkItemDO> children = workItemMapper.selectListByParentId(parentId).stream()
                .filter(child -> FillWorkItemStatusEnum.ROLLED_UP.getStatus().equals(child.getStatus())
                        && child.getCurrentSubmissionId() != null)
                .toList();
        List<ChildRows> inputs = new ArrayList<>();
        for (FillWorkItemDO child : children) {
            FillSubmissionDO submission = submissionMapper.selectById(child.getCurrentSubmissionId());
            if (submission == null || !FillApprovalStatusEnum.APPROVED.getStatus().equals(submission.getApprovalStatus())) {
                continue;
            }
            List<FillSubmissionRowDO> rows = FillSubmissionTypeEnum.EMPTY.getType().equals(submission.getSubmissionType())
                    ? List.of() : submissionRowMapper.selectListBySubmissionId(submission.getId());
            inputs.add(new ChildRows(child.getId(), child.getReportingOrgId(), submission.getId(), rows));
        }
        RollupRule rule = parseRule(tableVersionService.getTableVersion(parent.getTableVersionId()));
        List<FillDraftService.RollupRow> rows = buildRollupRows(rule, parent.getId(), parent.getReportingOrgId(), inputs);
        int draftRowCount = draftService.replaceRollupRows(parent, rows, expectedVersion, userId);
        rollupSourceMapper.deleteByWorkItemId(parentId);
        LocalDateTime now = LocalDateTime.now();
        for (ChildRows input : inputs) {
            rollupSourceMapper.insert(FillRollupSourceDO.builder().workItemId(parentId)
                    .childWorkItemId(input.childWorkItemId()).childSubmissionId(input.submissionId())
                    .rolledAt(now).build());
        }
        FillRollupRespVO resp = new FillRollupRespVO();
        resp.setChildCount(inputs.size());
        resp.setRollupRowCount(rows.size());
        resp.setDraftRowCount(draftRowCount);
        return resp;
    }

    /**
     * 上报前检查：存在未取消的下级时，必须全部审核通过且已按最新数据汇总。
     *
     * @param item 待提交的实例
     */
    public void checkReadyToSubmit(FillWorkItemDO item) {
        List<FillWorkItemDO> children = workItemMapper.selectListByParentId(item.getId());
        if (children.isEmpty()) {
            return;
        }
        FillChildrenRespVO.RollupState state = rollupState(item, children, sourceMap(item.getId()));
        if (!Boolean.TRUE.equals(state.getReadyToSubmit())) {
            throw exception(FILL_ROLLUP_NOT_READY, state.getBlockReason());
        }
    }

    /**
     * 实例及其全部下级（各层）的报送组织：合并时清理范围替换的旧数据。
     *
     * @param item 顶层实例
     * @return 组织编号（含自身）
     */
    public Set<Long> subtreeOrgIds(FillWorkItemDO item) {
        Map<Long, List<FillWorkItemDO>> byParent = workItemMapper.selectListByTaskId(item.getTaskId()).stream()
                .filter(other -> other.getParentWorkItemId() != null)
                .collect(Collectors.groupingBy(FillWorkItemDO::getParentWorkItemId));
        Set<Long> orgIds = new LinkedHashSet<>();
        Deque<FillWorkItemDO> queue = new ArrayDeque<>();
        queue.add(item);
        Set<Long> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            FillWorkItemDO current = queue.poll();
            if (!visited.add(current.getId())) {
                continue;
            }
            orgIds.add(current.getReportingOrgId());
            queue.addAll(byParent.getOrDefault(current.getId(), List.of()));
        }
        return orgIds;
    }

    /**
     * 实例及其全部下级的实例编号（附件、标签解析时认可下级实例上传的附件）。
     *
     * @param item 实例
     * @return 实例编号（含自身）
     */
    public Set<Long> subtreeItemIds(FillWorkItemDO item) {
        Map<Long, List<FillWorkItemDO>> byParent = workItemMapper.selectListByTaskId(item.getTaskId()).stream()
                .filter(other -> other.getParentWorkItemId() != null)
                .collect(Collectors.groupingBy(FillWorkItemDO::getParentWorkItemId));
        Set<Long> ids = new LinkedHashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(item.getId());
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            if (ids.add(current)) {
                byParent.getOrDefault(current, List.of()).forEach(child -> queue.add(child.getId()));
            }
        }
        return ids;
    }

    // ================================================================ 汇总计算（纯函数，便于测试）

    /** 下级审核通过的提交 */
    record ChildRows(Long childWorkItemId, Long childOrgId, Long submissionId, List<FillSubmissionRowDO> rows) {
    }

    /** 汇总规则 */
    record RollupRule(String mode, List<String> groupFields, Map<String, String> funcs) {
    }

    /** 解析模板版本固化的汇总规则；未配置时为明细汇集 */
    @SuppressWarnings("unchecked")
    static RollupRule parseRule(FillTableVersionDO version) {
        if (version == null || StrUtil.isBlank(version.getRollupSchema())) {
            return new RollupRule(MODE_DETAIL, List.of(), Map.of());
        }
        Map<String, Object> schema = JsonUtils.parseObject(version.getRollupSchema(), Map.class);
        if (schema == null || !MODE_GROUP.equals(schema.get("mode"))) {
            return new RollupRule(MODE_DETAIL, List.of(), Map.of());
        }
        List<String> groupFields = schema.get("groupFields") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        Map<String, String> funcs = new LinkedHashMap<>();
        if (schema.get("funcs") instanceof Map<?, ?> map) {
            map.forEach((key, value) -> funcs.put(String.valueOf(key), String.valueOf(value)));
        }
        return new RollupRule(MODE_GROUP, groupFields, funcs);
    }

    /**
     * 由下级提交生成汇总行。
     *
     * <ul>
     *     <li>明细汇集：逐行复制，来源组织与原始录入位置沿用下级行上的记录（下级自己录入的行即为下级本身）；</li>
     *     <li>分组合计：按分组字段聚合全部下级行，SUM/MAX/MIN 按精确小数计算，COUNT 对明细行计数、对下级的合计行累加其计数，
     *     FIRST 取第一个非空值；来源组织为汇总单位。</li>
     * </ul>
     */
    static List<FillDraftService.RollupRow> buildRollupRows(RollupRule rule, Long parentItemId, Long parentOrgId,
                                                            List<ChildRows> children) {
        List<FillDraftService.RollupRow> result = new ArrayList<>();
        if (MODE_DETAIL.equals(rule.mode())) {
            for (ChildRows child : children) {
                for (FillSubmissionRowDO row : child.rows()) {
                    result.add(new FillDraftService.RollupRow(parseRow(row.getData()), FillSubmissionRowDO.SOURCE_DETAIL,
                            row.getSourceOrgId() != null ? row.getSourceOrgId() : child.childOrgId(),
                            row.getSourceWorkItemId() != null ? row.getSourceWorkItemId() : child.childWorkItemId(),
                            child.submissionId(),
                            row.getSourceRowId() != null ? row.getSourceRowId() : row.getRowId()));
                }
            }
            return result;
        }
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        Map<String, Map<String, BigDecimal>> numbers = new LinkedHashMap<>();
        for (ChildRows child : children) {
            for (FillSubmissionRowDO row : child.rows()) {
                Map<String, Object> data = parseRow(row.getData());
                String key = rule.groupFields().stream()
                        .map(field -> data.get(field) == null ? "" : String.valueOf(data.get(field)).trim())
                        .collect(Collectors.joining("\u0001"));
                Map<String, Object> group = groups.computeIfAbsent(key, ignored -> {
                    Map<String, Object> initial = new LinkedHashMap<>();
                    rule.groupFields().forEach(field -> initial.put(field, data.get(field)));
                    return initial;
                });
                Map<String, BigDecimal> totals = numbers.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
                boolean aggregated = Integer.valueOf(FillSubmissionRowDO.SOURCE_GROUP).equals(row.getSourceKind());
                rule.funcs().forEach((field, func) -> accumulate(func, field, data.get(field), aggregated, group, totals));
            }
        }
        groups.forEach((key, group) -> {
            Map<String, BigDecimal> totals = numbers.get(key);
            rule.funcs().forEach((field, func) -> {
                if (!"FIRST".equals(func)) {
                    BigDecimal value = totals.get(field);
                    group.put(field, value == null ? ("COUNT".equals(func) ? "0" : null)
                            : value.stripTrailingZeros().toPlainString());
                }
            });
            result.add(new FillDraftService.RollupRow(group, FillSubmissionRowDO.SOURCE_GROUP, parentOrgId, parentItemId,
                    null, DigestUtil.md5Hex(key)));
        });
        return result;
    }

    private static void accumulate(String func, String field, Object value, boolean aggregated,
                                   Map<String, Object> group, Map<String, BigDecimal> totals) {
        boolean blank = value == null || StrUtil.isBlank(String.valueOf(value));
        switch (func) {
            case "FIRST" -> {
                if (!blank && group.get(field) == null) {
                    group.put(field, value);
                }
            }
            case "COUNT" -> {
                BigDecimal increment = aggregated ? toDecimal(value) : (blank ? null : BigDecimal.ONE);
                if (increment != null) {
                    totals.merge(field, increment, BigDecimal::add);
                }
            }
            default -> {
                BigDecimal number = toDecimal(value);
                if (number == null) {
                    return;
                }
                switch (func) {
                    case "SUM" -> totals.merge(field, number, BigDecimal::add);
                    case "MAX" -> totals.merge(field, number, BigDecimal::max);
                    case "MIN" -> totals.merge(field, number, BigDecimal::min);
                    default -> {
                        // 未知计算方式在发布时已被拒绝
                    }
                }
            }
        }
    }

    private static BigDecimal toDecimal(Object value) {
        if (value == null || StrUtil.isBlank(String.valueOf(value))) {
            return null;
        }
        try {
            return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 汇总状态：下级是否都已审核通过，最近一次汇总是否与当前审核通过的数据一致。
     */
    static FillChildrenRespVO.RollupState rollupState(String mode, List<FillWorkItemDO> children,
                                                      Map<Long, Long> sources, LocalDateTime lastRolledAt) {
        List<FillWorkItemDO> active = children.stream()
                .filter(child -> !FillWorkItemStatusEnum.CANCELLED.getStatus().equals(child.getStatus())).toList();
        Map<Long, Long> current = new HashMap<>();
        int reviewing = 0;
        for (FillWorkItemDO child : active) {
            if (FillWorkItemStatusEnum.ROLLED_UP.getStatus().equals(child.getStatus())) {
                current.put(child.getId(), child.getCurrentSubmissionId());
            } else if (FillWorkItemStatusEnum.APPROVING.getStatus().equals(child.getStatus())) {
                reviewing++;
            }
        }
        int unfinished = active.size() - current.size();
        boolean stale = !current.equals(sources);
        FillChildrenRespVO.RollupState state = new FillChildrenRespVO.RollupState();
        state.setMode(mode);
        state.setTotalCount(active.size());
        state.setRolledUpCount(current.size());
        state.setReviewingCount(reviewing);
        state.setUnfinishedCount(unfinished);
        state.setStale(stale);
        state.setLastRolledAt(lastRolledAt);
        if (unfinished > 0) {
            state.setReadyToSubmit(false);
            state.setBlockReason(reviewing > 0
                    ? "还有 " + unfinished + " 个下级未审核通过（其中 " + reviewing + " 个等待审核）"
                    : "还有 " + unfinished + " 个下级未提交或未审核通过");
        } else if (stale) {
            state.setReadyToSubmit(false);
            state.setBlockReason("下级数据有变化，请先“汇总下级数据”");
        } else {
            state.setReadyToSubmit(true);
        }
        return state;
    }

    // ================================================================ 内部

    private FillChildrenRespVO.RollupState rollupState(FillWorkItemDO parent, List<FillWorkItemDO> children,
                                                       Map<Long, Long> sources) {
        LocalDateTime lastRolledAt = rollupSourceMapper.selectListByWorkItemId(parent.getId()).stream()
                .map(FillRollupSourceDO::getRolledAt).filter(Objects::nonNull).max(LocalDateTime::compareTo)
                .orElse(null);
        String mode = parseRule(tableVersionService.getTableVersion(parent.getTableVersionId())).mode();
        return rollupState(mode, children, sources, lastRolledAt);
    }

    private Map<Long, Long> sourceMap(Long parentId) {
        Map<Long, Long> sources = new HashMap<>();
        rollupSourceMapper.selectListByWorkItemId(parentId)
                .forEach(source -> sources.put(source.getChildWorkItemId(), source.getChildSubmissionId()));
        return sources;
    }

    static boolean isAwaitingReview(FillWorkItemDO child, FillSubmissionDO submission) {
        return submission != null && child.getParentWorkItemId() != null
                && FillWorkItemStatusEnum.APPROVING.getStatus().equals(child.getStatus())
                && Integer.valueOf(FillSubmissionDO.REVIEW_PARENT).equals(submission.getReviewMode())
                && FillApprovalStatusEnum.APPROVING.getStatus().equals(submission.getApprovalStatus());
    }

    /** 操作人须是该实例的有效填报人 */
    private void requireAssignee(FillWorkItemDO item, Long userId) {
        FillAssigneeDO assignee = userId == null ? null : assigneeMapper.selectByWorkItemIdAndUserId(item.getId(), userId);
        if (assignee == null || !FillAssigneeStateEnum.VALID.getState().equals(assignee.getState())) {
            throw exception(FILL_CHILD_NOT_IN_SCOPE);
        }
    }

    /** 操作下级：操作人须是直接上级实例的有效填报人，且上级仍可编辑（尚未上报） */
    private FillWorkItemDO requireEditableParent(FillWorkItemDO child, Long userId) {
        if (child.getParentWorkItemId() == null) {
            throw exception(FILL_CHILD_NOT_IN_SCOPE);
        }
        FillWorkItemDO parent = workItemService.getWorkItemValidated(child.getParentWorkItemId());
        requireAssignee(parent, userId);
        if (!FillWorkItemStatusEnum.EDITABLE_STATUSES.contains(parent.getStatus())) {
            throw exception(FILL_CHILD_STATUS_INVALID, "本单位已上报，需等被退回后再处理下级");
        }
        return parent;
    }

    private static void validateDeadline(FillWorkItemDO parent, LocalDateTime deadline, LocalDateTime now) {
        if (deadline == null || !deadline.isAfter(now)) {
            throw exception(FILL_DISPATCH_NOT_ALLOWED, "截止时间必须晚于当前时间");
        }
        if (parent.getDeadlineTime() != null && deadline.isAfter(parent.getDeadlineTime())) {
            throw exception(FILL_DISPATCH_DEADLINE_INVALID, TIME_FORMAT.format(parent.getDeadlineTime()));
        }
    }

    /** dept 是否在 ancestorId 之下（沿 parentId 向上查找，只在已知子树内查找） */
    private static boolean isDescendant(DeptRespDTO dept, Long ancestorId, Map<Long, DeptRespDTO> scope) {
        Long parentId = dept.getParentId();
        for (int depth = 0; parentId != null && depth < 32; depth++) {
            if (Objects.equals(parentId, ancestorId)) {
                return true;
            }
            DeptRespDTO parent = scope.get(parentId);
            parentId = parent == null ? null : parent.getParentId();
        }
        return false;
    }

    /** 计划中的下级 */
    private record PlannedChild(Long orgId, String orgName, String orgCode, String orgPath, LocalDateTime deadline,
                                List<AssigneeRef> assignees) {
    }

    /** 填报人快照 */
    record AssigneeRef(Long userId, String userName, Long deptId) {
    }

    private FillWorkItemDO createChild(FillTaskDO task, FillWorkItemDO parent, PlannedChild child,
                                       FillAssigneeSourceEnum source) {
        FillWorkItemDO item = FillWorkItemDO.builder()
                .taskId(task.getId())
                .planId(parent.getPlanId())
                .tableVersionId(parent.getTableVersionId())
                .reportingOrgId(child.orgId())
                .orgName(child.orgName())
                .orgCode(child.orgCode())
                .orgPath(child.orgPath())
                .status(FillWorkItemStatusEnum.PENDING.getStatus())
                .overdue(0)
                .submissionType(FillSubmissionTypeEnum.NORMAL.getType())
                .serverVersion(0L)
                .attemptCount(0)
                .draftRowCount(0)
                .deadlineTime(child.deadline())
                .parentWorkItemId(parent.getId())
                .level((parent.getLevel() == null ? 0 : parent.getLevel()) + 1)
                .build();
        try {
            workItemMapper.insert(item);
        } catch (DuplicateKeyException ex) {
            throw exception(FILL_DISPATCH_ORG_INVALID, child.orgName());
        }
        LocalDateTime now = LocalDateTime.now();
        for (AssigneeRef assignee : child.assignees()) {
            assigneeMapper.insert(FillAssigneeDO.builder()
                    .workItemId(item.getId())
                    .taskId(task.getId())
                    .userId(assignee.userId())
                    .userName(assignee.userName())
                    .deptId(assignee.deptId())
                    .state(FillAssigneeStateEnum.VALID.getState())
                    .source(source.getSource())
                    .effectiveTime(now)
                    .build());
        }
        notifyService.sendToUsers(FillNotifyTypeEnum.ASSIGNED, task, item,
                convertList(child.assignees(), AssigneeRef::userId), null);
        return item;
    }

    /** 以本单位当前全部未取消的下级覆盖默认下发名单 */
    private void saveDefaults(FillWorkItemDO parent) {
        dispatchTemplateMapper.deleteByPlanAndParent(parent.getPlanId(), parent.getReportingOrgId());
        int sort = 0;
        for (FillWorkItemDO child : workItemMapper.selectListByParentId(parent.getId())) {
            if (FillWorkItemStatusEnum.CANCELLED.getStatus().equals(child.getStatus())) {
                continue;
            }
            List<Map<String, String>> assignees = convertList(assigneeMapper.selectValidListByWorkItemId(child.getId()),
                    assignee -> {
                        Map<String, String> ref = new LinkedHashMap<>();
                        ref.put("userId", String.valueOf(assignee.getUserId()));
                        ref.put("userName", StrUtil.nullToEmpty(assignee.getUserName()));
                        ref.put("deptId", assignee.getDeptId() == null ? "" : String.valueOf(assignee.getDeptId()));
                        return ref;
                    });
            long hours = parent.getDeadlineTime() == null || child.getDeadlineTime() == null ? 0
                    : Math.max(0, Duration.between(child.getDeadlineTime(), parent.getDeadlineTime()).toHours());
            dispatchTemplateMapper.insert(FillDispatchTemplateDO.builder()
                    .planId(parent.getPlanId())
                    .parentOrgId(parent.getReportingOrgId())
                    .reportingOrgId(child.getReportingOrgId())
                    .orgName(StrUtil.nullToEmpty(child.getOrgName()))
                    .orgCode(StrUtil.nullToEmpty(child.getOrgCode()))
                    .orgPath(StrUtil.nullToEmpty(child.getOrgPath()))
                    .assigneeUserIds(JsonUtils.toJsonString(assignees))
                    .deadlineOffsetHours((int) Math.min(720, hours))
                    .sort(sort++)
                    .build());
        }
    }

    @SuppressWarnings("unchecked")
    static List<AssigneeRef> parseAssignees(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        List<?> raw;
        try {
            raw = JsonUtils.parseObject(json, List.class);
        } catch (RuntimeException ex) {
            return List.of();
        }
        List<AssigneeRef> result = new ArrayList<>();
        for (Object item : raw == null ? List.of() : raw) {
            if (item instanceof Map<?, ?> map && map.get("userId") != null
                    && cn.hutool.core.util.NumberUtil.isLong(String.valueOf(map.get("userId")))) {
                Object dept = map.get("deptId");
                result.add(new AssigneeRef(Long.valueOf(String.valueOf(map.get("userId"))),
                        map.get("userName") == null ? "" : String.valueOf(map.get("userName")),
                        dept != null && cn.hutool.core.util.NumberUtil.isLong(String.valueOf(dept))
                                ? Long.valueOf(String.valueOf(dept)) : null));
            }
        }
        return result;
    }

    private List<Long> validAssignees(Long workItemId) {
        return convertList(assigneeMapper.selectValidListByWorkItemId(workItemId), FillAssigneeDO::getUserId);
    }

    /**
     * 事务提交后提示上级工作台刷新“下级单位”。
     *
     * @param parentId 上级实例编号
     */
    public void notifyChildrenChanged(Long parentId) {
        if (parentId == null) {
            return;
        }
        afterCommit(() -> collabService.broadcast(parentId, FillSubmissionServiceImpl.WS_CHILDREN_CHANGED,
                Map.of("workItemId", parentId)));
    }

    private void broadcastAfterCommit(Long workItemId, Integer status) {
        afterCommit(() -> collabService.broadcast(workItemId, FillSubmissionServiceImpl.WS_STATUS_CHANGED,
                Map.of("workItemId", workItemId, "status", status)));
    }

    private void afterCommit(Runnable work) {
        Runnable send = () -> {
            try {
                work.run();
            } catch (Exception ex) {
                log.warn("[afterCommit][协同广播失败：{}]", ex.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseRow(String json) {
        if (StrUtil.isBlank(json)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> data = JsonUtils.parseObject(json, Map.class);
        return data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

}
