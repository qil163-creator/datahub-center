package cn.iocoder.yudao.module.supply.service.app;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.supply.controller.admin.app.vo.AppApiSaveReqVO;
import cn.iocoder.yudao.module.supply.dal.dataobject.app.SupplyAppDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.appapi.AppApiDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.dal.mysql.app.SupplyAppMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.AppApiMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.SupplyGrantOpMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupplyAppGrantSubjectTest {


    @Test
    void adminCreateIgnoresClientPermissionUserId() {
        SupplyAppMapper appMapper = mock(SupplyAppMapper.class);
        DataApiMapper dataApiMapper = mock(DataApiMapper.class);
        AppApiMapper appApiMapper = mock(AppApiMapper.class);
        when(appMapper.selectById(1L)).thenReturn(SupplyAppDO.builder().id(1L).build());
        when(dataApiMapper.selectById(2L)).thenReturn(DataApiDO.builder().id(2L).build());
        when(appApiMapper.selectByAppIdAndApiId(1L, 2L)).thenReturn(null);
        SupplyAppServiceImpl service = newService(appMapper, dataApiMapper, appApiMapper, mock(AdminUserApi.class));
        AppApiSaveReqVO reqVO = new AppApiSaveReqVO();
        reqVO.setAppId(1L);
        reqVO.setApiId(2L);
        reqVO.setForeverFlag(1);
        reqVO.setPermissionUserId(999L);

        service.createAppApi(reqVO);

        ArgumentCaptor<AppApiDO> captor = ArgumentCaptor.forClass(AppApiDO.class);
        verify(appApiMapper).insert(captor.capture());
        assertThat(captor.getValue().getPermissionUserId()).isNull();
    }

    @Test
    void rpcGrantRejectsMissingUser() {
        SupplyAppMapper appMapper = mock(SupplyAppMapper.class);
        DataApiMapper dataApiMapper = mock(DataApiMapper.class);
        AppApiMapper appApiMapper = mock(AppApiMapper.class);
        AdminUserApi adminUserApi = mock(AdminUserApi.class);
        when(appMapper.selectById(1L)).thenReturn(SupplyAppDO.builder().id(1L).build());
        when(dataApiMapper.selectById(2L)).thenReturn(DataApiDO.builder().id(2L).build());
        when(adminUserApi.getUser(8L)).thenReturn(CommonResult.success(null));
        SupplyAppServiceImpl service = newService(appMapper, dataApiMapper, appApiMapper, adminUserApi);
        AppApiSaveReqVO reqVO = new AppApiSaveReqVO();
        reqVO.setAppId(1L);
        reqVO.setApiId(2L);
        reqVO.setForeverFlag(1);
        reqVO.setPermissionUserId(8L);

        assertThatThrownBy(() -> service.grantAppApi(reqVO))
                .isInstanceOfSatisfying(ServiceException.class, ex -> assertThat(ex.getCode()).isEqualTo(1_500_042_013));
    }

    @Test
    void legacyRpcCannotGrantEvenWithVerifiedUser() {
        SupplyAppMapper appMapper = mock(SupplyAppMapper.class);
        DataApiMapper dataApiMapper = mock(DataApiMapper.class);
        AppApiMapper appApiMapper = mock(AppApiMapper.class);
        AdminUserApi adminUserApi = mock(AdminUserApi.class);
        when(appMapper.selectById(1L)).thenReturn(SupplyAppDO.builder().id(1L).build());
        when(dataApiMapper.selectById(2L)).thenReturn(DataApiDO.builder().id(2L).build());
        AdminUserRespDTO user = new AdminUserRespDTO();
        user.setId(8L);
        user.setStatus(0);
        when(adminUserApi.getUser(8L)).thenReturn(CommonResult.success(user));
        when(appApiMapper.selectByAppIdAndApiId(1L, 2L)).thenReturn(null);
        SupplyAppServiceImpl service = newService(appMapper, dataApiMapper, appApiMapper, adminUserApi);
        AppApiSaveReqVO reqVO = new AppApiSaveReqVO();
        reqVO.setAppId(1L);
        reqVO.setApiId(2L);
        reqVO.setForeverFlag(1);
        reqVO.setPermissionUserId(8L);

        assertThatThrownBy(() -> service.grantAppApi(reqVO))
                .isInstanceOfSatisfying(ServiceException.class, ex -> assertThat(ex.getCode()).isEqualTo(1_500_042_013));
    }

    private SupplyAppServiceImpl newService(SupplyAppMapper appMapper, DataApiMapper dataApiMapper,
                                            AppApiMapper appApiMapper, AdminUserApi adminUserApi) {
        when(appMapper.selectGrantParentForUpdate( 1L)).thenReturn(SupplyAppDO.builder()
                .id(1L).appStatus(1).build());
        when(dataApiMapper.selectGrantContractForShare( 2L)).thenReturn(DataApiDO.builder()
                .id(2L).status(1).contractHash("approved").version("v1").build());
        SupplyAppServiceImpl service = new SupplyAppServiceImpl();
        ReflectionTestUtils.setField(service, "etlGrantPreflight", mock(SupplyEtlGrantPreflight.class));
        ReflectionTestUtils.setField(service, "appMapper", appMapper);
        ReflectionTestUtils.setField(service, "dataApiMapper", dataApiMapper);
        ReflectionTestUtils.setField(service, "appApiMapper", appApiMapper);
        ReflectionTestUtils.setField(service, "adminUserApi", adminUserApi);
        SupplyGrantOpMapper grantOpMapper = mock(SupplyGrantOpMapper.class);
        ReflectionTestUtils.setField(service, "grantOpMapper", grantOpMapper);
        return service;
    }
}
