package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeResolveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeLogRespVO;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.MASTER_DISTRIBUTE_REVIEW_FORBIDDEN;

/** 平台内核查，独立权限；证据真实性由授权核查人负责，不伪装成外部自动对账。 */
@Service
@Validated
public class MasterDistributeRunReviewService {

    @Resource
    private PermissionApi permissionApi;

    @Resource
    private AdminUserApi userApi;

    @Resource
    private MasterDistributeRunCoordinator coordinator;

    /**
     * 在事务外核验人工对账权限，再委托协调器保存分发运行处置结果。
     */
    public MasterDistributeLogRespVO resolve(@NotNull @Valid MasterDistributeResolveReqVO request) {
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        if (actor == null || actor <= 0) {
            throw exception(MASTER_DISTRIBUTE_REVIEW_FORBIDDEN);
        }
        // 身份与权限 RPC 在本地持锁事务之前执行；内部调用也不能绕过 Controller 鉴权。
        userApi.validateUser(actor);
        if (!Boolean.TRUE.equals(
                permissionApi.hasAnyPermissions(actor, "governance:master-distribute:resolve").getCheckedData())) {
            throw exception(MASTER_DISTRIBUTE_REVIEW_FORBIDDEN);
        }
        return BeanUtils.toBean(coordinator.resolve(request, actor), MasterDistributeLogRespVO.class);
    }
}
