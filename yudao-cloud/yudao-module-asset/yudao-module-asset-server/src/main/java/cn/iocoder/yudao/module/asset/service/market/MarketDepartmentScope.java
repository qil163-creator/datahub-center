package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_FORBIDDEN;

/** 申请资产的精确同部门读取范围；不读取客户端或登录缓存中的部门，也不扩展子部门。 */
@Component
public class MarketDepartmentScope {

    @Resource
    private AdminUserApi adminUserApi;

    /**
     * 解析当前请求对应的市场部门范围上下文。
     */
    public Scope current() {
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        if (actor == null || actor <= 0) {
            throw exception(MARKET_APPLY_FORBIDDEN);
        }
        AdminUserRespDTO current = adminUserApi.getUser(actor).getCheckedData();
        if (current == null || !actor.equals(current.getId())
                || !Objects.equals(current.getStatus(), CommonStatusEnum.ENABLE.getStatus())
                || current.getDeptId() == null || current.getDeptId() <= 0) {
            throw exception(MARKET_APPLY_FORBIDDEN);
        }
        Long dept = current.getDeptId();
        List<AdminUserRespDTO> members = adminUserApi.getUserListByDeptIds(List.of(dept)).getCheckedData();
        if (members == null) {
            throw exception(MARKET_APPLY_FORBIDDEN);
        }
        var ids = new LinkedHashSet<Long>();
        ids.add(actor);
        // 即使提供方意外包含子部门或其他部门，也不能扩大本领域的精确同部门范围。
        for (AdminUserRespDTO member : members) {
            if (member != null && Objects.equals(dept, member.getDeptId())
                    && member.getId() != null && member.getId() > 0) {
                ids.add(member.getId());
            }
        }
        return new Scope(actor, dept, List.copyOf(ids));
    }

    public record Scope(Long actorId, Long deptId, List<Long> userIds) {

    }
}
