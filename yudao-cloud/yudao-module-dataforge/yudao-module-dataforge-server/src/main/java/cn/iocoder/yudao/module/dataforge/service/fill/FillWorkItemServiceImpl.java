package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillAssigneeRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillWorkItemAssigneeUpdateReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillWorkItemRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillWorkItemTransferReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillAssigneeDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillAssignmentLogDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillAssigneeMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillAssignmentLogMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillNotifyRecordMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillWorkItemMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillAssigneeSourceEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillAssigneeStateEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillAssignmentActionEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillNotifyTypeEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillOverduePolicyEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillWorkItemStatusEnum;
import cn.iocoder.yudao.module.system.api.dept.DeptApi;
import cn.iocoder.yudao.module.system.api.dept.dto.DeptRespDTO;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertMap;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertSet;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/**
 * 在线填报-组织填报实例（工作项）Service 实现类
 *
 * @author Li
 */
@Service
@Validated
public class FillWorkItemServiceImpl implements FillWorkItemService {

    @Resource
    private FillWorkItemMapper workItemMapper;

    @Resource
    private FillAssigneeMapper assigneeMapper;

    @Resource
    private FillAssignmentLogMapper assignmentLogMapper;

    @Resource
    private FillTaskMapper taskMapper;

    @Resource
    private FillNotifyRecordMapper notifyRecordMapper;

    @Resource
    private FillNotifyService notifyService;

    @Resource
    private DeptApi deptApi;

    @Resource
    private AdminUserApi adminUserApi;

    /** 催办频控间隔（分钟） */
    private static final int URGE_INTERVAL_MINUTES = 60;

    /**
     * 我的填报：当前用户作为有效填报人的全部工作项
     *
     * @return 当前用户工作项
     */
    @Override
    public List<FillWorkItemRespVO> getMyWorkItems() {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        List<FillAssigneeDO> assignments = assigneeMapper.selectValidListByUserId(userId);
        if (CollUtil.isEmpty(assignments)) {
            return Collections.emptyList();
        }
        List<FillWorkItemDO> items = workItemMapper.selectListByIds(
                convertSet(assignments, FillAssigneeDO::getWorkItemId));
        return assemble(items, true);
    }

    /**
     * 任务下的全部工作项（发起者进度视角，含当前有效填报人）
     *
     * @param taskId 任务编号
     * @return 任务下的全部工作项（发起者进度视角，含当前有效填报人）结果集合
     */
    @Override
    public List<FillWorkItemRespVO> getWorkItemListByTask(Long taskId) {
        return assemble(workItemMapper.selectListByTaskId(taskId), true);
    }

    /**
     * 工作项详情（校验查看权限：有效填报人 / 任务创建者 / 报送组织负责人）
     *
     * @param id 记录编号
     * @return 填报工作项响应数据
     */
    @Override
    public FillWorkItemRespVO getWorkItem(Long id) {
        FillWorkItemDO item = validateWorkItemExists(id);
        validateViewPermission(item);
        return CollUtil.getFirst(assemble(List.of(item), true));
    }

    /**
     * 部门负责人只读查看本部门工作项；deptId 为空时取当前用户所在部门
     *
     * @param deptId 部门编号
     * @return 部门负责人只读查看本部门工作项；deptId 为空时取当前用户所在部门结果集合
     */
    @Override
    public List<FillWorkItemRespVO> getDeptWorkItems(Long deptId) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (deptId == null) {
            AdminUserRespDTO me = adminUserApi.getUserMap(Set.of(userId)).get(userId);
            deptId = me != null ? me.getDeptId() : null;
        }
        DeptRespDTO dept = deptId == null ? null : deptApi.getDeptMap(Set.of(deptId)).get(deptId);
        // 部门负责人只读范围：仅其直接负责的组织，不默认穿透组织树（V1.0 §7.1）
        if (dept == null || !Objects.equals(dept.getLeaderUserId(), userId)) {
            throw exception(FILL_DEPT_VIEW_NOT_LEADER);
        }
        return assemble(workItemMapper.selectListByReportingOrgId(deptId), true);
    }

    /**
     * 发起者/授权管理员调整填报人（新增/移除），实例必须处于可调整状态且始终保留至少一名有效填报人
     *
     * @param reqVO 请求参数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateAssignees(FillWorkItemAssigneeUpdateReqVO reqVO) {
        FillWorkItemDO item = validateWorkItemExists(reqVO.getWorkItemId());
        validateAssignable(item);
        Long operator = SecurityFrameworkUtils.getLoginUserId();
        LocalDateTime now = LocalDateTime.now();

        // 先移除后新增；全程保证至少一名有效填报人
        if (CollUtil.isNotEmpty(reqVO.getRemoveUserIds())) {
            for (Long userId : reqVO.getRemoveUserIds()) {
                FillAssigneeDO assignee = assigneeMapper.selectByWorkItemIdAndUserId(item.getId(), userId);
                if (assignee == null || !FillAssigneeStateEnum.VALID.getState().equals(assignee.getState())) {
                    continue;
                }
                setAssigneeState(assignee.getId(), FillAssigneeStateEnum.INVALID);
                insertLog(item.getId(), FillAssignmentActionEnum.REMOVE, userId, null, operator, reqVO.getReason());
            }
        }
        if (CollUtil.isNotEmpty(reqVO.getAddUserIds())) {
            List<Long> addUserIds = reqVO.getAddUserIds().stream().distinct().toList();
            Map<Long, AdminUserRespDTO> userMap = adminUserApi.getUserMap(addUserIds);
            for (Long userId : addUserIds) {
                AdminUserRespDTO user = userMap.get(userId);
                if (user == null) {
                    throw exception(FILL_ASSIGNEE_TRANSFER_TARGET_INVALID);
                }
                grantAssignee(item, userId, user.getNickname(), user.getDeptId(),
                        FillAssigneeSourceEnum.INITIATOR, now);
                insertLog(item.getId(), FillAssignmentActionEnum.ADD, null, userId, operator, reqVO.getReason());
            }
        }
        if (assigneeMapper.selectValidCountByWorkItemId(item.getId()) < 1) {
            throw exception(FILL_ASSIGNEE_LAST_ONE);
        }
        // 通知新增人员；任务信息用于通知内容
        if (CollUtil.isNotEmpty(reqVO.getAddUserIds())) {
            FillTaskDO task = taskMapper.selectById(item.getTaskId());
            notifyService.sendToUsers(FillNotifyTypeEnum.ASSIGNED, task, item, reqVO.getAddUserIds(), null);
        }
    }

    /**
     * 填报人本人转派：只能转派自己的职责，默认限同组织内有效人员
     *
     * @param reqVO 请求参数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transfer(FillWorkItemTransferReqVO reqVO) {
        FillWorkItemDO item = validateWorkItemExists(reqVO.getWorkItemId());
        validateAssignable(item);
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        FillAssigneeDO mine = assigneeMapper.selectByWorkItemIdAndUserId(item.getId(), userId);
        if (mine == null || !FillAssigneeStateEnum.VALID.getState().equals(mine.getState())) {
            throw exception(FILL_WORK_ITEM_NOT_ASSIGNEE);
        }
        if (Objects.equals(reqVO.getTargetUserId(), userId)) {
            throw exception(FILL_ASSIGNEE_TRANSFER_TARGET_INVALID);
        }
        AdminUserRespDTO target = adminUserApi.getUserMap(Set.of(reqVO.getTargetUserId()))
                .get(reqVO.getTargetUserId());
        // 默认只能转给同一报送组织内的有效账号；科室挂在部门树下的放宽规则（父链判断）列入二期
        if (target == null || !Objects.equals(target.getDeptId(), item.getReportingOrgId())) {
            throw exception(FILL_ASSIGNEE_TRANSFER_TARGET_INVALID);
        }
        LocalDateTime now = LocalDateTime.now();
        // 接收人生效后原填报人才失效，保证实例始终有人处理
        grantAssignee(item, reqVO.getTargetUserId(), target.getNickname(), target.getDeptId(),
                FillAssigneeSourceEnum.TRANSFER, now);
        setAssigneeState(mine.getId(), FillAssigneeStateEnum.INVALID);
        insertLog(item.getId(), FillAssignmentActionEnum.TRANSFER, userId, reqVO.getTargetUserId(),
                userId, reqVO.getReason());

        FillTaskDO task = taskMapper.selectById(item.getTaskId());
        notifyService.sendToUsers(FillNotifyTypeEnum.TRANSFERRED, task, item,
                List.of(reqVO.getTargetUserId()), null);
    }

    // ==================== 催办 ====================

    /**
     * 催办单个工作项（通知当前有效填报人；1 小时频控内重复催办抛异常）
     *
     * @param id 记录编号
     */
    @Override
    public void urgeWorkItem(Long id) {
        FillWorkItemDO item = validateWorkItemExists(id);
        if (!FillWorkItemStatusEnum.EDITABLE_STATUSES.contains(item.getStatus())) {
            FillWorkItemStatusEnum status = FillWorkItemStatusEnum.of(item.getStatus());
            throw exception(FILL_WORK_ITEM_STATUS_INVALID, status != null ? status.getName() : item.getStatus());
        }
        if (!tryUrge(item)) {
            throw exception(FILL_URGE_TOO_FREQUENT, URGE_INTERVAL_MINUTES);
        }
    }

    /**
     * 任务级一键催办：对全部未完成（待填报/填报中/整改中）工作项发送催办，频控内的自动跳过
     *
     * @param taskId 任务编号
     * @return 催办按任务数值
     */
    @Override
    public int urgeByTask(Long taskId) {
        int urged = 0;
        for (FillWorkItemDO item : workItemMapper.selectListByTaskId(taskId)) {
            if (FillWorkItemStatusEnum.EDITABLE_STATUSES.contains(item.getStatus()) && tryUrge(item)) {
                urged++;
            }
        }
        return urged;
    }

    /** @return false = 频控间隔内已催办过，跳过 */
    private boolean tryUrge(FillWorkItemDO item) {
        long recent = notifyRecordMapper.selectCountSince(item.getId(),
                FillNotifyTypeEnum.URGE.getType(), LocalDateTime.now().minusMinutes(URGE_INTERVAL_MINUTES));
        if (recent > 0) {
            return false;
        }
        FillTaskDO task = taskMapper.selectById(item.getTaskId());
        List<Long> userIds = convertList(assigneeMapper.selectValidListByWorkItemId(item.getId()),
                FillAssigneeDO::getUserId);
        notifyService.sendToUsers(FillNotifyTypeEnum.URGE, task, item, userIds, null);
        return true;
    }

    // ==================== 供草稿/协同/提交等服务复用的校验能力 ====================

    /**
     * 获得工作项（不存在抛异常）
     *
     * @param id 记录编号
     * @return 填报工作项
     */
    @Override
    public FillWorkItemDO getWorkItemValidated(Long id) {
        return validateWorkItemExists(id);
    }

    /**
     * 校验查看权限：有效填报人 / 任务创建者 / 报送组织负责人
     *
     *  显式传 userId：WebSocket 消息线程没有 SecurityContext，协同层从会话中取用户后调用
     *
     * @param item 当前条目
     * @param userId 用户编号
     */
    @Override
    public void checkViewPermission(FillWorkItemDO item, Long userId) {
        if (!hasViewPermission(item, userId)) {
            throw exception(FILL_WORK_ITEM_NOT_ASSIGNEE);
        }
    }

    /**
     * 判断查看权限：有效填报人 / 任务创建者 / 报送组织负责人。
     *
     * @param item 当前条目
     * @param userId 用户编号
     * @return 是否可查看
     */
    @Override
    public boolean hasViewPermission(FillWorkItemDO item, Long userId) {
        if (item == null || userId == null) {
            return false;
        }
        FillAssigneeDO assignee = assigneeMapper.selectByWorkItemIdAndUserId(item.getId(), userId);
        if (assignee != null && FillAssigneeStateEnum.VALID.getState().equals(assignee.getState())) {
            return true;
        }
        FillTaskDO task = taskMapper.selectById(item.getTaskId());
        if (task != null && Objects.equals(task.getCreator(), String.valueOf(userId))) {
            return true;
        }
        DeptRespDTO dept = deptApi.getDeptMap(Set.of(item.getReportingOrgId())).get(item.getReportingOrgId());
        return dept != null && Objects.equals(dept.getLeaderUserId(), userId);
    }

    /**
     * 校验编辑权限：有效填报人 + 实例处于可编辑状态 + 硬截止未锁定
     *
     * @param item 当前条目
     * @param userId 用户编号
     */
    @Override
    public void checkEditPermission(FillWorkItemDO item, Long userId) {
        FillAssigneeDO assignee = assigneeMapper.selectByWorkItemIdAndUserId(item.getId(), userId);
        if (assignee == null || !FillAssigneeStateEnum.VALID.getState().equals(assignee.getState())) {
            throw exception(FILL_WORK_ITEM_NOT_ASSIGNEE);
        }
        if (!FillWorkItemStatusEnum.EDITABLE_STATUSES.contains(item.getStatus())) {
            FillWorkItemStatusEnum status = FillWorkItemStatusEnum.of(item.getStatus());
            throw exception(FILL_WORK_ITEM_STATUS_INVALID, status != null ? status.getName() : item.getStatus());
        }
        // 硬截止：超过截止时间后锁定编辑，由发起者延期或重新开放（软逾期只标记不拦截）
        if (item.getDeadlineTime() != null && LocalDateTime.now().isAfter(item.getDeadlineTime())) {
            FillTaskDO task = taskMapper.selectById(item.getTaskId());
            if (task != null && FillOverduePolicyEnum.HARD.getPolicy().equals(task.getOverduePolicy())) {
                throw exception(FILL_WORK_ITEM_OVERDUE_LOCKED);
            }
        }
    }

    // ---------------------------------------------------------------- 内部方法

    /**
     * 校验工作项存在性。
     *
     * @param id 记录编号
     * @return 填报工作项
     */
    private FillWorkItemDO validateWorkItemExists(Long id) {
        FillWorkItemDO item = workItemMapper.selectById(id);
        if (item == null) {
            throw exception(FILL_WORK_ITEM_NOT_EXISTS);
        }
        return item;
    }

    /**
     * 校验可赋值。
     *
     * @param item 当前条目
     */
    private void validateAssignable(FillWorkItemDO item) {
        if (!FillWorkItemStatusEnum.ASSIGNABLE_STATUSES.contains(item.getStatus())) {
            FillWorkItemStatusEnum status = FillWorkItemStatusEnum.of(item.getStatus());
            throw exception(FILL_WORK_ITEM_STATUS_INVALID, status != null ? status.getName() : item.getStatus());
        }
    }

    /**
     * 查看权限：有效填报人 / 任务创建者（发起者）/ 报送组织负责人
     */
    private void validateViewPermission(FillWorkItemDO item) {
        checkViewPermission(item, SecurityFrameworkUtils.getLoginUserId());
    }

    /** 授予填报权限：已有历史行则翻转状态，否则新增 */
    private void grantAssignee(FillWorkItemDO item, Long userId, String userName, Long deptId,
            FillAssigneeSourceEnum source, LocalDateTime effectiveTime) {
        FillAssigneeDO exists = assigneeMapper.selectByWorkItemIdAndUserId(item.getId(), userId);
        if (exists != null) {
            if (FillAssigneeStateEnum.VALID.getState().equals(exists.getState())) {
                return;
            }
            FillAssigneeDO update = new FillAssigneeDO();
            update.setId(exists.getId());
            update.setState(FillAssigneeStateEnum.VALID.getState());
            update.setSource(source.getSource());
            update.setEffectiveTime(effectiveTime);
            assigneeMapper.updateById(update);
            return;
        }
        assigneeMapper.insert(FillAssigneeDO.builder()
                .workItemId(item.getId())
                .taskId(item.getTaskId())
                .userId(userId)
                .userName(userName)
                .deptId(deptId)
                .state(FillAssigneeStateEnum.VALID.getState())
                .source(source.getSource())
                .effectiveTime(effectiveTime)
                .build());
    }

    /**
     * 设置填报人状态。
     *
     * @param assigneeId 填报人编号
     * @param state 状态
     */
    private void setAssigneeState(Long assigneeId, FillAssigneeStateEnum state) {
        FillAssigneeDO update = new FillAssigneeDO();
        update.setId(assigneeId);
        update.setState(state.getState());
        assigneeMapper.updateById(update);
    }

    /**
     * 新增填报工作项的日志。
     *
     * @param workItemId 工作项编号
     * @param action 待执行的操作
     * @param fromUserId 来源用户编号
     * @param toUserId 目标用户编号
     * @param operator 操作人
     * @param reason 操作原因
     */
    private void insertLog(Long workItemId, FillAssignmentActionEnum action, Long fromUserId, Long toUserId,
            Long operator, String reason) {
        assignmentLogMapper.insert(FillAssignmentLogDO.builder()
                .workItemId(workItemId)
                .action(action.getAction())
                .fromUserId(fromUserId)
                .toUserId(toUserId)
                .operatorUserId(operator)
                .reason(reason)
                .build());
    }

    /** 组装 RespVO：补任务名称/所属期与当前有效填报人 */
    private List<FillWorkItemRespVO> assemble(List<FillWorkItemDO> items, boolean withAssignees) {
        if (CollUtil.isEmpty(items)) {
            return Collections.emptyList();
        }
        Map<Long, FillTaskDO> taskMap = convertMap(
                taskMapper.selectByIds(convertSet(items, FillWorkItemDO::getTaskId)), FillTaskDO::getId);
        Map<Long, List<FillAssigneeDO>> assigneeMap = withAssignees
                ? assigneeMapper.selectValidListByWorkItemIds(convertSet(items, FillWorkItemDO::getId))
                .stream().collect(Collectors.groupingBy(FillAssigneeDO::getWorkItemId))
                : Collections.emptyMap();
        return convertList(items, item -> {
            FillWorkItemRespVO respVO = BeanUtils.toBean(item, FillWorkItemRespVO.class);
            FillTaskDO task = taskMap.get(item.getTaskId());
            if (task != null) {
                respVO.setTaskName(task.getName());
                respVO.setPeriodKey(task.getPeriodKey());
            }
            respVO.setAssignees(BeanUtils.toBean(
                    assigneeMap.getOrDefault(item.getId(), Collections.emptyList()), FillAssigneeRespVO.class));
            return respVO;
        });
    }

}
