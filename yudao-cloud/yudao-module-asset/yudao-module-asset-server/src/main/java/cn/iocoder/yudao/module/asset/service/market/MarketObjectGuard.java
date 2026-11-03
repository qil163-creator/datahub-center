package cn.iocoder.yudao.module.asset.service.market;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketApplyPageReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDemandDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_FORBIDDEN;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_DEMAND_FORBIDDEN;

/**
 * 集市对象范围：登录身份决定可见集合，客户端 ifSelf/ifOwner/status 只能缩小。
 */
@Component
public class MarketObjectGuard {

    public static final String PERM_APPLY_QUERY = "asset:market:query";

    public static final String PERM_APPLY_CHECK = "asset:market:check";

    public static final String PERM_APPLY_AUTH = "asset:market:auth";

    public static final String PERM_DEMAND_QUERY = "asset:demand:query";

    public static final String PERM_DEMAND_MATCH = "asset:demand:match";

    @Resource
    private PermissionApi permissionApi;

    @Resource
    private DataAssetMapper assetMapper;

    /**
     * 读取并校验当前登录用户编号。
     */
    public Long requireUserId() {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (userId == null) {
            throw exception(MARKET_APPLY_FORBIDDEN);
        }
        return userId;
    }

    /**
     * 读取当前登录用户所属部门编号。
     */
    public Long currentDeptId() {
        return SecurityFrameworkUtils.getLoginUserDeptId();
    }

    /**
     * 判断市场对象是否具有权限。
     */
    public boolean hasPermission(String permission) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (userId == null || permission == null) {
            return false;
        }
        Boolean allowed = permissionApi.hasAnyPermissions(userId, permission).getCheckedData();
        return Boolean.TRUE.equals(allowed);
    }

    /**
     * 判断市场对象是否提供者。
     */
    public boolean isProvider(DataAssetDO asset, Long userId) {
        if (asset == null || userId == null) {
            return false;
        }
        if (Objects.equals(asset.getTechLeaderUserId(), userId)
                || Objects.equals(asset.getBusinessLeaderUserId(), userId)) {
            return true;
        }
        Long deptId = currentDeptId();
        return deptId != null && Objects.equals(asset.getOwnerDeptId(), deptId);
    }

    /**
     * 查询当前用户作为提供方可管理的资产编号。
     */
    public List<Long> providerAssetIds(Long userId) {
        Set<Long> ids = new LinkedHashSet<>(assetMapper.selectIdsByLeaderUserId(userId));
        Long deptId = currentDeptId();
        if (deptId != null) {
            ids.addAll(assetMapper.selectIdsByOwnerDeptId(deptId));
        }
        return new ArrayList<>(ids);
    }

    /**
     * 解析市场对象的申请分页。
     */
    public ApplyPageScope resolveApplyPage(MarketApplyPageReqVO reqVO) {
        Long userId = requireUserId();
        boolean checker = hasPermission(PERM_APPLY_CHECK) || hasPermission(PERM_APPLY_AUTH);
        List<Long> providerIds = checker ? providerAssetIds(userId) : List.of();
        boolean ownOnly = !checker || Objects.equals(reqVO.getIfSelf(), 1);
        boolean providerOnly = checker && !ownOnly && Objects.equals(reqVO.getIfOwner(), 1);
        return new ApplyPageScope(userId, ownOnly, providerOnly, providerIds);
    }

    /**
     * 核验当前用户对市场对象的视图申请权限。
     */
    public void assertCanViewApply(MarketResourceApplyDO apply, DataAssetDO asset) {
        Long userId = requireUserId();
        if (Objects.equals(apply.getApplyUserId(), userId)) {
            return;
        }
        if ((hasPermission(PERM_APPLY_CHECK) || hasPermission(PERM_APPLY_AUTH)) && isProvider(asset, userId)) {
            return;
        }
        throw exception(MARKET_APPLY_FORBIDDEN);
    }

    /**
     * 核验当前用户对市场对象的检查申请权限。
     */
    public void assertCanCheckApply(MarketResourceApplyDO apply, DataAssetDO asset) {
        requireUserId();
        if (!hasPermission(PERM_APPLY_CHECK)) {
            throw exception(MARKET_APPLY_FORBIDDEN);
        }
        if (!isProvider(asset, requireUserId())) {
            throw exception(MARKET_APPLY_FORBIDDEN);
        }
    }

    /**
     * 核验当前用户对市场对象的认证申请权限。
     */
    public void assertCanAuthApply(MarketResourceApplyDO apply, DataAssetDO asset) {
        requireUserId();
        if (!hasPermission(PERM_APPLY_AUTH)) {
            throw exception(MARKET_APPLY_FORBIDDEN);
        }
        if (!isProvider(asset, requireUserId())) {
            throw exception(MARKET_APPLY_FORBIDDEN);
        }
    }

    /**
     * 提供方决定的末次复验：权限 RPC 已在锁外完成，调用方须在真实事务内、申请 CAS 之后调用。
     * 对资产加共享锁持有到事务结束，按锁内当前行重新判断当前操作人仍是提供方；开通类还须仍匹配冻结绑定，
     * 防止权限查询期间资产换责任人、换归属部门、删除或换绑后旧判断继续提交。锁后不做远端调用。
     */
    public void lockProviderAsset(MarketResourceApplyDO apply, boolean activating) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("提供方决定的资产复验必须在业务事务内执行");
        }
        Long userId = requireUserId();
        DataAssetDO current = apply == null || apply.getAssetId() == null ? null
                : assetMapper.selectForMatching(apply.getAssetId());
        if (current == null || Boolean.TRUE.equals(current.getDeleted()) || !isProvider(current, userId)) {
            throw exception(MARKET_APPLY_FORBIDDEN);
        }
        if (activating) {
            if (!MarketFieldScope.supportsExistingGrant(current)) {
                throw exception(MARKET_APPLY_FORBIDDEN);
            }
            MarketFieldScope.requireMatchesAsset(apply, current);
        }
    }

    /**
     * 核验当前用户对市场对象的视图需求权限。
     */
    public void assertCanViewDemand(MarketDemandDO demand) {
        Long userId = requireUserId();
        if (Objects.equals(demand.getApplyUserId(), userId)) {
            return;
        }
        if (demand.getStatus() == MarketDemandDO.STATUS_DRAFT) {
            throw exception(MARKET_DEMAND_FORBIDDEN);
        }
        if (!hasPermission(PERM_DEMAND_QUERY) && !hasPermission(PERM_DEMAND_MATCH)) {
            throw exception(MARKET_DEMAND_FORBIDDEN);
        }
    }

    public record ApplyPageScope(Long userId, boolean ownOnly, boolean providerOnly, List<Long> providerAssetIds) {

        /**
         * 判断提供方范围是否已收敛为空，避免放宽为空条件查询。
         */
        public boolean emptyProviderFilter() {
            return providerOnly && CollUtil.isEmpty(providerAssetIds);
        }
    }
}
