package cn.iocoder.yudao.module.supply.service.app;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.supply.controller.admin.app.vo.AppApiSaveReqVO;
import cn.iocoder.yudao.module.supply.dal.dataobject.app.SupplyAppDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.appapi.AppApiDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.appapi.SupplyGrantOpDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.dal.mysql.app.SupplyAppMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.AppApiMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.SupplyGrantOpMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupplyGrantAcceptanceTest {


    @Test
    void t15OldEndDateLastDayRemainsInclusiveUntilNextMidnight() {
        AppApiDO grant = AppApiDO.builder()
                .foreverFlag(0)
                .endDate(LocalDate.of(2026, 9, 16))
                .grantStatus(SupplyGrantValidity.STATUS_ACTIVE)
                .build();
        SupplyGrantValidity.fillInstantBounds(grant);
        SupplyAppDO app = SupplyAppDO.builder().appStatus(SupplyGrantValidity.APP_ENABLED).build();
        assertThat(SupplyGrantValidity.isEffective(app, grant,
                LocalDateTime.of(2026, 9, 16, 23, 59, 59))).isTrue();
        assertThat(SupplyGrantValidity.isEffective(app, grant,
                LocalDateTime.of(2026, 9, 17, 0, 0))).isFalse();
    }

    @Test
    void t17OwnerChangeDoesNotRewritePermissionSubject() {
        SupplyAppServiceImpl service = newService();
        AppApiSaveReqVO reqVO = baseGrant();
        reqVO.setPermissionUserId(null);
        service.createAppApi(reqVO);
        ArgumentCaptor<AppApiDO> captor = ArgumentCaptor.forClass(AppApiDO.class);
        verify((AppApiMapper) ReflectionTestUtils.getField(service, "appApiMapper")).insert(captor.capture());
        assertThat(captor.getValue().getPermissionUserId()).isNull();
    }

    @Test
    void t18HistoricalReceiptRemainsQueryableAfterLegacyWritesClose() {
        SupplyAppServiceImpl service = newService();
        SupplyGrantOpMapper opMapper = (SupplyGrantOpMapper) ReflectionTestUtils.getField(service, "grantOpMapper");
        AppApiSaveReqVO reqVO = baseGrant();
        reqVO.setOperationId("op-1");
        reqVO.setSourceSystem("asset");
        when(opMapper.selectSourceOperation( "asset", "op-1")).thenReturn(SupplyGrantOpDO.builder()
                .grantId(88L).payloadHash("historical").resultStatus(SupplyGrantOpDO.DELIVERED).build());
        assertThat(service.getGrantOperation("asset", "op-1").getGrantId()).isEqualTo(88L);
    }

    @Test
    void t18LegacyWriteCannotReuseHistoricalReceiptToBypassProtocol() {
        SupplyAppServiceImpl service = newService();
        SupplyGrantOpMapper opMapper = (SupplyGrantOpMapper) ReflectionTestUtils.getField(service, "grantOpMapper");
        AppApiSaveReqVO reqVO = baseGrant();
        reqVO.setOperationId("op-1");
        reqVO.setSourceSystem("asset");
        when(opMapper.selectByOperation("asset", "op-1")).thenReturn(SupplyGrantOpDO.builder()
                .grantId(88L).payloadHash("other").resultStatus(SupplyGrantOpDO.DELIVERED).build());
        assertThatThrownBy(() -> service.grantAppApi(reqVO))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> assertThat(((ServiceException) ex).getCode()).isEqualTo(1_500_042_013));
    }

    @Test
    void t19DelayedGrantCannotResurrectLaterRevoke() {
        SupplyAppServiceImpl service = newService();
        AppApiMapper appApiMapper = (AppApiMapper) ReflectionTestUtils.getField(service, "appApiMapper");
        when(appApiMapper.selectByAppIdAndApiId(1L, 2L)).thenReturn(AppApiDO.builder()
                .id(9L).appId(1L).apiId(2L)
                .grantStatus(SupplyGrantValidity.STATUS_REVOKED)
                .grantRevision(5)
                .build());
        AppApiSaveReqVO reqVO = baseGrant();
        reqVO.setGrantRevision(4);
        reqVO.setOperationId("late-grant");
        reqVO.setSourceSystem("asset");
        assertThatThrownBy(() -> service.grantAppApi(reqVO))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> assertThat(((ServiceException) ex).getCode()).isEqualTo(1_500_042_013));
    }

    private static AppApiSaveReqVO baseGrant() {
        AppApiSaveReqVO reqVO = new AppApiSaveReqVO();
        reqVO.setAppId(1L);
        reqVO.setApiId(2L);
        reqVO.setForeverFlag(1);
        return reqVO;
    }

    private static SupplyAppServiceImpl newService() {
        SupplyAppMapper appMapper = mock(SupplyAppMapper.class);
        DataApiMapper dataApiMapper = mock(DataApiMapper.class);
        AppApiMapper appApiMapper = mock(AppApiMapper.class);
        SupplyGrantOpMapper grantOpMapper = mock(SupplyGrantOpMapper.class);
        when(appMapper.selectGrantParentForUpdate( 1L)).thenReturn(SupplyAppDO.builder()
                .id(1L).appStatus(SupplyGrantValidity.APP_ENABLED).build());
        when(dataApiMapper.selectGrantContractForShare( 2L)).thenReturn(DataApiDO.builder()
                .id(2L).status(1).contractHash("hash-a").version("v1").build());
        when(appApiMapper.selectByAppIdAndApiId(1L, 2L)).thenReturn(null);
        when(grantOpMapper.selectByOperation(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(null);
        SupplyAppServiceImpl service = new SupplyAppServiceImpl();
        ReflectionTestUtils.setField(service, "etlGrantPreflight", mock(SupplyEtlGrantPreflight.class));
        ReflectionTestUtils.setField(service, "appMapper", appMapper);
        ReflectionTestUtils.setField(service, "dataApiMapper", dataApiMapper);
        ReflectionTestUtils.setField(service, "appApiMapper", appApiMapper);
        ReflectionTestUtils.setField(service, "grantOpMapper", grantOpMapper);
        ReflectionTestUtils.setField(service, "adminUserApi", mock(AdminUserApi.class));
        return service;
    }
}
