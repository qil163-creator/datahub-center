package cn.iocoder.yudao.module.supply.service.app;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.supply.dal.dataobject.app.SupplyAppDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.appapi.AppApiDO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 授权时效：validFrom ≤ now &lt; validTo。永久授权必须有 foreverFlag=1，不能靠两个空时间隐式永久。
 */
public final class SupplyGrantValidity {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public static final int STATUS_PAUSED = 0;

    public static final int STATUS_ACTIVE = 1;

    public static final int STATUS_REVOKED = 3;

    public static final int APP_PAUSED = 0;

    public static final int APP_ENABLED = 1;

    public static final int APP_REVOKED = 2;

    public static final String ADMIN_ETL_BATCH = "ADMIN_ETL_BATCH";

    /**
     * 限制供应授权有效性工具的实例化，统一通过静态能力使用。
     */
    private SupplyGrantValidity() {
    }

    /**
     * 判断是否满足ETL管理端授权条件。
     *
     * @param grant 授权
     * @return 判断结果
     */
    public static boolean isEtlAdminGrant(AppApiDO grant) {
        return grant != null && Integer.valueOf(0).equals(grant.getProtocolVersion())
                && ADMIN_ETL_BATCH.equals(grant.getSourceType()) && grant.getPermissionUserId() != null
                && grant.getPermissionUserId() > 0 && StrUtil.isNotBlank(grant.getApprovedContractHash());
    }

    /**
     * 判断是否满足独立管理端授权条件。
     *
     * @param grant 授权
     * @return 判断结果
     */
    public static boolean isIndependentAdminGrant(AppApiDO grant) {
        return isEtlAdminGrant(grant) || (grant != null && Integer.valueOf(0).equals(grant.getProtocolVersion())
                && "ADMIN".equals(grant.getSourceType()) && grant.getPermissionUserId() == null);
    }

    /** 沿用网关既有合同规则：无当前合同时保持旧兼容，非空合同必须精确匹配原批准。 */
    public static boolean matchesCurrentContract(AppApiDO grant, String currentContract) {
        return grant != null && (StrUtil.isBlank(currentContract)
                || currentContract.equals(grant.getApprovedContractHash()));
    }

    /**
     * 判断是否满足生效条件。
     *
     * @param app 调用应用信息
     * @param grant 授权
     * @param now 当前时间
     * @return 判断结果
     */
    public static boolean isEffective(SupplyAppDO app, AppApiDO grant, LocalDateTime now) {
        if (app == null || grant == null || now == null) {
            return false;
        }
        if (Boolean.TRUE.equals(grant.getAggregateDisabled())) {
            return false;
        }
        if (app.getAppStatus() != null && app.getAppStatus() != APP_ENABLED) {
            return false;
        }
        if (grant.getGrantStatus() != null && grant.getGrantStatus() != STATUS_ACTIVE) {
            return false;
        }
        LocalDateTime from = effectiveFrom(grant);
        LocalDateTime to = effectiveTo(grant);
        boolean forever = grant.getForeverFlag() != null && grant.getForeverFlag() == 1;
        if (!forever && from == null && to == null) {
            return false;
        }
        if (from != null && now.isBefore(from)) {
            return false;
        }
        return to == null || now.isBefore(to);
    }

    /**
     * 获取供应授权有效性的展示状态。
     *
     * @param app 调用应用信息
     * @param grant 授权
     * @param now 当前时间
     * @return 展示状态数值
     */
    public static int displayStatus(SupplyAppDO app, AppApiDO grant, LocalDateTime now) {
        if (grant == null) {
            return STATUS_REVOKED;
        }
        if (Boolean.TRUE.equals(grant.getAggregateDisabled())) {
            return STATUS_PAUSED;
        }
        if (grant.getGrantStatus() != null && grant.getGrantStatus() == STATUS_REVOKED) {
            return STATUS_REVOKED;
        }
        if (grant.getGrantStatus() != null && grant.getGrantStatus() == STATUS_PAUSED) {
            return STATUS_PAUSED;
        }
        if (app != null && app.getAppStatus() != null && app.getAppStatus() != APP_ENABLED) {
            return STATUS_PAUSED;
        }
        return isEffective(app, grant, now) ? STATUS_ACTIVE : 0;
    }

    /**
     * 获取供应授权有效性的生效来源。
     *
     * @param grant 授权
     * @return 本地日期时间
     */
    public static LocalDateTime effectiveFrom(AppApiDO grant) {
        if (grant.getValidFrom() != null) {
            return grant.getValidFrom();
        }
        LocalDate start = grant.getStartDate();
        return start == null ? null : start.atStartOfDay();
    }

    /**
     * 获取供应授权有效性的生效目标。
     *
     * @param grant 授权
     * @return 本地日期时间
     */
    public static LocalDateTime effectiveTo(AppApiDO grant) {
        if (grant.getValidTo() != null) {
            return grant.getValidTo();
        }
        if (grant.getForeverFlag() != null && grant.getForeverFlag() == 1) {
            return null;
        }
        LocalDate end = grant.getEndDate();
        return end == null ? null : end.plusDays(1).atStartOfDay();
    }

    /**
     * 补充时间点边界。
     *
     * @param grant 授权
     */
    public static void fillInstantBounds(AppApiDO grant) {
        if (grant.getValidFrom() == null) {
            grant.setValidFrom(effectiveFrom(grant));
        }
        if (grant.getValidTo() == null && (grant.getForeverFlag() == null || grant.getForeverFlag() != 1)) {
            grant.setValidTo(effectiveTo(grant));
        }
        if (grant.getForeverFlag() != null && grant.getForeverFlag() == 1) {
            grant.setValidTo(null);
        }
        if (grant.getValidFrom() != null && grant.getValidTo() != null
                && !grant.getValidFrom().isBefore(grant.getValidTo())) {
            throw new IllegalArgumentException("授权起点必须早于终点");
        }
    }
}
