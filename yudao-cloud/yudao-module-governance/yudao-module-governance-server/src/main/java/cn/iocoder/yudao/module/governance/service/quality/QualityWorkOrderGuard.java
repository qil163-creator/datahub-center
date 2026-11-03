package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityWorkOrderDO;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_NOT_EXISTS;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_RAW_FORBIDDEN;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_UNASSIGNED;

@Component
public class QualityWorkOrderGuard {

    public static final String PERM_QUERY = "governance:quality-work-order:query";

    public static final String PERM_DELETE = "governance:quality-work-order:delete";

    public static final String PERM_ASSIGN = "governance:quality-work-order:assign";

    public static final String PERM_CLAIM = "governance:quality-work-order:claim";

    public static final String PERM_PROCESS = "governance:quality-work-order:process";

    public static final String PERM_CONFIRM = "governance:quality-work-order:confirm";

    public static final String PERM_COORDINATE = "governance:quality-work-order:coordinate";

    public static final String PERM_REOPEN = "governance:quality-work-order:reopen";

    public static final String PERM_RAW = "governance:quality-work-order:raw-query";

    @Resource
    private PermissionApi permissionApi;

    @Resource
    private AdminUserApi adminUserApi;

    /**
     * 读取并校验当前登录用户编号。
     */
    public Long requireUserId() {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (userId == null) {
            throw exception(QUALITY_WORK_ORDER_NOT_EXISTS);
        }
        return userId;
    }

    /**
     * 判断当前用户是否具有指定质量工单操作权限。
     */
    public boolean has(Long userId, String permission) {
        Boolean ok = permissionApi.hasAnyPermissions(userId, permission).getCheckedData();
        return Boolean.TRUE.equals(ok);
    }

    /**
     * 读取当前质量工单操作人的部门编号。
     */
    public Long deptId(Long userId) {
        AdminUserRespDTO user = adminUserApi.getUser(userId).getCheckedData();
        return user == null ? null : user.getDeptId();
    }

    /** 与 assertVisible 相同的对象范围，在分页/聚合之前应用；权限服务失败不降级为全量或空数据。 */
    public void applyVisibleScope(LambdaQueryWrapperX<QualityWorkOrderDO> query, Long userId) {
        Objects.requireNonNull(userId, "Authenticated user required");
        if (has(userId, PERM_ASSIGN)) {
            return;
        }
        query.isNotNull(QualityWorkOrderDO::getOwnerUserId)
                .and(q -> q.ne(QualityWorkOrderDO::getResponsibilityStatus, QualityWorkOrderDO.RESP_UNASSIGNED)
                        .or().isNull(QualityWorkOrderDO::getResponsibilityStatus));
        if (has(userId, PERM_COORDINATE)) {
            return;
        }
        Long department = deptId(userId);
        query.and(q -> {
            q.eq(QualityWorkOrderDO::getOwnerUserId, userId)
                    .or().eq(QualityWorkOrderDO::getCreatorUserId, userId)
                    .or().eq(QualityWorkOrderDO::getConfirmerUserId, userId);
            if (department != null) {
                q.or().eq(QualityWorkOrderDO::getHandlingDeptId, department);
            }
        });
    }

    /**
     * 校验质量工单的可见，不满足约束时拒绝当前操作。
     */
    public void assertVisible(QualityWorkOrderDO order, Long userId) {
        if (isUnassigned(order)) {
            if (!has(userId, PERM_ASSIGN)) {
                throw exception(QUALITY_WORK_ORDER_NOT_EXISTS);
            }
            return;
        }
        if (Objects.equals(order.getOwnerUserId(), userId)
                || Objects.equals(order.getCreatorUserId(), userId)
                || Objects.equals(order.getConfirmerUserId(), userId)
                || (order.getHandlingDeptId() != null && Objects.equals(order.getHandlingDeptId(), deptId(userId)))
                || has(userId, PERM_ASSIGN)
                || has(userId, PERM_COORDINATE)) {
            return;
        }
        throw exception(QUALITY_WORK_ORDER_NOT_EXISTS);
    }

    /**
     * 校验质量工单的可分派，不满足约束时拒绝当前操作。
     */
    public void assertAssignable(QualityWorkOrderDO order, Long userId) {
        assertVisible(order, userId);
        if (!has(userId, PERM_ASSIGN)) {
            throw exception(QUALITY_WORK_ORDER_UNASSIGNED);
        }
    }

    /**
     * 校验质量工单的处理，不满足约束时拒绝当前操作。
     */
    public void assertHandle(QualityWorkOrderDO order, Long userId) {
        assertVisible(order, userId);
        if (isUnassigned(order)) {
            throw exception(QUALITY_WORK_ORDER_UNASSIGNED);
        }
        if (Objects.equals(order.getOwnerUserId(), userId)
                || (order.getHandlingDeptId() != null && Objects.equals(order.getHandlingDeptId(), deptId(userId)))
                || has(userId, PERM_PROCESS)) {
            return;
        }
        throw exception(QUALITY_WORK_ORDER_NOT_EXISTS);
    }

    /**
     * 校验质量工单的确认，不满足约束时拒绝当前操作。
     */
    public void assertConfirm(QualityWorkOrderDO order, Long userId) {
        assertVisible(order, userId);
        if (!has(userId, PERM_CONFIRM)) {
            throw exception(QUALITY_WORK_ORDER_NOT_EXISTS);
        }
    }

    /**
     * 校验质量工单的原始，不满足约束时拒绝当前操作。
     */
    public void assertRaw(QualityWorkOrderDO order, Long userId) {
        assertVisible(order, userId);
        if (!has(userId, PERM_RAW)) {
            throw exception(QUALITY_WORK_ORDER_RAW_FORBIDDEN);
        }
    }

    /**
     * 判断质量工单是否未分派。
     */
    public boolean isUnassigned(QualityWorkOrderDO order) {
        return QualityWorkOrderDO.RESP_UNASSIGNED.equals(order.getResponsibilityStatus())
                || order.getOwnerUserId() == null;
    }

}
