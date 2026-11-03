package cn.iocoder.yudao.module.asset.service.dataasset;

import lombok.Getter;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;

import java.util.Objects;

/** 当前同步调用的维护权限证据，仅服务端可构造，不属于任何 HTTP 请求或响应。 */
@Getter
public final class AssetMaintenanceAuthorization {

    private final Long userId;

    private final Long deptId;

    private final boolean superAdmin;

    private final boolean operator;

    /**
     * 使用给定用户编号、部门编号、父类管理初始化资产维护授权。
     */
    AssetMaintenanceAuthorization(Long userId, Long deptId, boolean superAdmin, boolean operator) {
        this.userId = userId;
        this.deptId = deptId;
        this.superAdmin = superAdmin;
        this.operator = operator;
    }

    /** 维护范围只取服务端身份；没有部门身份时不能匹配未设置归属部门的资产。 */
    public boolean canMaintain(DataAssetDO asset) {
        return asset != null && userId != null && userId > 0 && (superAdmin || operator
                || Objects.equals(asset.getTechLeaderUserId(), userId)
                || Objects.equals(asset.getBusinessLeaderUserId(), userId)
                || (deptId != null && (Objects.equals(asset.getOwnerDeptId(), deptId)
                || Objects.equals(asset.getProviderDeptId(), deptId)))
                || Objects.equals(String.valueOf(userId), asset.getCreator()));
    }
}
