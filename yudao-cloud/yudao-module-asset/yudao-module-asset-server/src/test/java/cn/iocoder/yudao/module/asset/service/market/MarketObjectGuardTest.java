package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketApplyPageReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDemandDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class MarketObjectGuardTest {

    private final MarketObjectGuard guard = new MarketObjectGuard();
    private final PermissionApi permissionApi = mock(PermissionApi.class);
    private final DataAssetMapper assetMapper = mock(DataAssetMapper.class);
    private MockedStatic<SecurityFrameworkUtils> security;

    @BeforeEach
    void setup() {
        security = mockStatic(SecurityFrameworkUtils.class);
        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(11L);
        security.when(SecurityFrameworkUtils::getLoginUserDeptId).thenReturn(100L);
        ReflectionTestUtils.setField(guard, "permissionApi", permissionApi);
        ReflectionTestUtils.setField(guard, "assetMapper", assetMapper);
    }

    @AfterEach
    void close() {
        security.close();
    }

    @Test
    void queryUserCannotExpandApplyPageWithIfSelfFalse() {
        when(permissionApi.hasAnyPermissions(11L, MarketObjectGuard.PERM_APPLY_CHECK))
                .thenReturn(CommonResult.success(false));
        when(permissionApi.hasAnyPermissions(11L, MarketObjectGuard.PERM_APPLY_AUTH))
                .thenReturn(CommonResult.success(false));
        MarketApplyPageReqVO req = new MarketApplyPageReqVO();
        req.setIfSelf(0);
        MarketObjectGuard.ApplyPageScope scope = guard.resolveApplyPage(req);
        assertTrue(scope.ownOnly());
    }

    @Test
    void checkerWithoutProviderRelationCannotCheck() {
        when(permissionApi.hasAnyPermissions(11L, MarketObjectGuard.PERM_APPLY_CHECK))
                .thenReturn(CommonResult.success(true));
        DataAssetDO asset = new DataAssetDO();
        asset.setTechLeaderUserId(99L);
        asset.setOwnerDeptId(200L);
        MarketResourceApplyDO apply = new MarketResourceApplyDO();
        apply.setApplyUserId(12L);
        assertThrows(ServiceException.class, () -> guard.assertCanCheckApply(apply, asset));
    }

    @Test
    void draftDemandIsHiddenFromOthers() {
        MarketDemandDO demand = new MarketDemandDO();
        demand.setApplyUserId(12L);
        demand.setStatus(MarketDemandDO.STATUS_DRAFT);
        assertThrows(ServiceException.class, () -> guard.assertCanViewDemand(demand));
    }

    @Test
    void providerDeptCanCheck() {
        when(permissionApi.hasAnyPermissions(11L, MarketObjectGuard.PERM_APPLY_CHECK))
                .thenReturn(CommonResult.success(true));
        DataAssetDO asset = new DataAssetDO();
        asset.setOwnerDeptId(100L);
        MarketResourceApplyDO apply = new MarketResourceApplyDO();
        apply.setApplyUserId(12L);
        guard.assertCanCheckApply(apply, asset);
    }
}
