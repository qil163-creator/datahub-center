package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillPlanDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillPlanMapper;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import cn.iocoder.yudao.module.system.enums.permission.RoleCodeEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.FILL_TASK_NOT_INITIATOR;

/**
 * 在线填报-任务写操作的对象级数据范围
 *
 * 延期、结束填报、催办、调整填报人、切换审批流程版本等改变任务进展的操作，
 * 在按钮权限之外还要求操作人是任务发起者（计划创建人，手工补生成的任务也认任务创建人）或超级管理员。
 * 角色查询失败时按拒绝处理（fail closed）。
 *
 * @author Li
 */
@Component
public class FillTaskAccessGuard {

    @Resource
    private FillPlanMapper planMapper;

    @Resource
    private PermissionApi permissionApi;

    /**
     * 校验当前登录用户是计划创建人（发起者）或超级管理员。
     *
     * @param plan 计划
     */
    public void checkPlanInitiator(FillPlanDO plan) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (userId == null || plan == null) {
            throw exception(FILL_TASK_NOT_INITIATOR);
        }
        if (Objects.equals(plan.getCreator(), String.valueOf(userId))) {
            return;
        }
        if (!Boolean.TRUE.equals(permissionApi.hasAnyRoles(userId, RoleCodeEnum.SUPER_ADMIN.getCode())
                .getCheckedData())) {
            throw exception(FILL_TASK_NOT_INITIATOR);
        }
    }

    /**
     * 校验当前登录用户可以改变该任务的进展。
     *
     * @param task 任务
     */
    public void checkInitiator(FillTaskDO task) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (userId == null || task == null) {
            throw exception(FILL_TASK_NOT_INITIATOR);
        }
        String user = String.valueOf(userId);
        if (Objects.equals(task.getCreator(), user)) {
            return;
        }
        FillPlanDO plan = task.getPlanId() == null ? null : planMapper.selectById(task.getPlanId());
        if (plan != null && Objects.equals(plan.getCreator(), user)) {
            return;
        }
        if (!Boolean.TRUE.equals(permissionApi.hasAnyRoles(userId, RoleCodeEnum.SUPER_ADMIN.getCode())
                .getCheckedData())) {
            throw exception(FILL_TASK_NOT_INITIATOR);
        }
    }

}
