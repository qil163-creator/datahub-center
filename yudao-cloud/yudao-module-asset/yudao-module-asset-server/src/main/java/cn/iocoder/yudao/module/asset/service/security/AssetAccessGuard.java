package cn.iocoder.yudao.module.asset.service.security;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.asset.service.market.MarketFieldScope;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import cn.iocoder.yudao.module.system.enums.permission.RoleCodeEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.DATA_ASSET_APPLY_REQUIRED;

/**
 * 已发布资产的消费出口：编目人员可运维预览，其他人必须有生效中的共享申请。
 */
@Component
public class AssetAccessGuard {

    @Resource
    private DataAssetMapper assetMapper;

    @Resource
    private MarketResourceApplyMapper applyMapper;

    @Resource
    private PermissionApi permissionApi;

    @Resource
    private AssetMetadataIdentityGuard metadataIdentityGuard;

    /**
     * 校验资产访问的已发布可读，不满足约束时拒绝当前操作。
     */
    public void assertPublishedReadable(DataAssetDO asset, Long userId) {
        assertPublishedHistoryReadable(asset, userId);
        metadataIdentityGuard.assertCurrent(asset);
    }

    /** 关联历史只复用对象授权，不要求历史资源仍可执行取数。 */
    public void assertPublishedHistoryReadable(DataAssetDO asset, Long userId) {
        if (!controlled(asset)) {
            return;
        }
        if (canBypassApply(asset, userId)) {
            return;
        }
        if (MarketFieldScope.supportsExistingGrant(asset) && hasEffectiveApply(userId, asset.getId())) {
            return;
        }
        throw exception(DATA_ASSET_APPLY_REQUIRED);
    }

    /** 没取得共享快照时必须证明独立运维权限，不能复用先前一次共享授权检查的结果。 */
    public void assertPublishedReadableWithoutApply(DataAssetDO asset, Long userId) {
        DataAssetDO current = asset == null ? null : assetMapper.selectById(asset.getId());
        if (current == null || (controlled(current) && !canBypassApply(current, userId))) {
            throw exception(DATA_ASSET_APPLY_REQUIRED);
        }
        metadataIdentityGuard.assertCurrent(current);
    }

    /** 仅检查解析后的精确物理关系，不把不同 schema 或大小写的表混为一张。 */
    public void assertPublishedTableReadable(Long datasourceId, String schemaName, String tableName,
            Long userId, boolean allowApply) {
        if (datasourceId == null || userId == null || StrUtil.isBlank(schemaName) || StrUtil.isBlank(tableName)) {
            throw exception(DATA_ASSET_APPLY_REQUIRED);
        }
        List<DataAssetDO> assets = assetMapper.selectExactTableBindings(datasourceId, schemaName, tableName);
        for (DataAssetDO asset : assets) {
            if (StrUtil.isBlank(asset.getSchemaName())) {
                throw exception(DATA_ASSET_APPLY_REQUIRED);
            }
            if (controlled(asset)
                    && !canBypassApply(asset, userId) && !(allowApply
                    && MarketFieldScope.supportsExistingGrant(asset) && hasEffectiveApply(userId, asset.getId()))) {
                throw exception(DATA_ASSET_APPLY_REQUIRED);
            }
        }
        metadataIdentityGuard.assertCurrent(assets);
    }

    /** 列表只隐藏明确无领域读取权的对象，RPC/数据库故障仍向上传递。 */
    public boolean canReadPublishedTable(Long datasourceId, String schemaName, String tableName, Long userId) {
        try {
            assertPublishedTableReadable(datasourceId, schemaName, tableName, userId, true);
            return true;
        } catch (cn.iocoder.yudao.framework.common.exception.ServiceException ex) {
            if (Objects.equals(ex.getCode(), DATA_ASSET_APPLY_REQUIRED.getCode())) {
                return false;
            }
            throw ex;
        }
    }

    /**
     * 判断当前资产是否需要通过申请授权才能读取。
     */
    private boolean controlled(DataAssetDO asset) {
        return asset != null && (asset.getPublishedCatalogVersionId() != null
                || Objects.equals(asset.getStatus(), DataAssetDO.STATUS_PUBLISHED)
                || Objects.equals(asset.getStatus(), DataAssetDO.STATUS_OFFLINE));
    }

    /**
     * 判断当前用户是否可访问资产访问的绕过申请。
     */
    private boolean canBypassApply(DataAssetDO asset, Long userId) {
        if (userId == null) {
            return false;
        }
        Boolean superAdmin = permissionApi.hasAnyRoles(userId, RoleCodeEnum.SUPER_ADMIN.getCode()).getCheckedData();
        if (Boolean.TRUE.equals(superAdmin)) {
            return true;
        }
        if (Objects.equals(asset.getTechLeaderUserId(), userId)
                || Objects.equals(asset.getBusinessLeaderUserId(), userId)
                || Objects.equals(String.valueOf(userId), StrUtil.blankToDefault(asset.getCreator(), ""))) {
            return true;
        }
        Boolean catalogOperator = permissionApi.hasAnyPermissions(userId,
                "asset:data-asset:update", "asset:data-asset:publish").getCheckedData();
        return Boolean.TRUE.equals(catalogOperator);
    }

    /**
     * 判断资产访问是否具有生效申请。
     */
    private boolean hasEffectiveApply(Long userId, Long assetId) {
        if (userId == null || assetId == null) {
            return false;
        }
        LocalDateTime now = applyMapper.databaseNow();
        if (now == null) {
            return false;
        }
        MarketResourceApplyDO apply = applyMapper.selectEffective(userId, assetId, now);
        return apply != null;
    }

    /**
     * 读取当前登录用户编号，拒绝缺失的登录身份。
     */
    public Long currentUserId() {
        return SecurityFrameworkUtils.getLoginUserId();
    }

}
