package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.supply.dal.dataobject.app.SupplyAppDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.appapi.AppApiDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.dal.mysql.app.SupplyAppMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.AppApiMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiMapper;
import cn.iocoder.yudao.module.supply.service.app.SupplyGrantValidity;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataApiGatewayGrantTest {

    @Test
    void rejectsDisabledApp() {
        DataApiGatewayService service = gateway();
        DataApiDO api = onlineApi("hash-a");
        SupplyAppDO app = SupplyAppDO.builder()
                .id(2L).appKey("app-key")
                .appStatus(SupplyGrantValidity.APP_PAUSED).build();
        AppApiDO grant = AppApiDO.builder()
                .id(3L).appId(2L).apiId(1L)
                .grantStatus(SupplyGrantValidity.STATUS_ACTIVE).foreverFlag(1)
                .approvedContractHash("hash-a").build();
        stub(service, api, app, grant);

        assertThatThrownBy(() -> invoke(service))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> assertThat(((ServiceException) ex).getCode()).isEqualTo(1_500_042_005));
        verify((DataApiExecutor) ReflectionTestUtils.getField(service, "dataApiExecutor"),
                never()).execute(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(),
                anyBoolean(), any());
    }

    @Test
    void rejectsContractMismatch() {
        DataApiGatewayService service = gateway();
        DataApiDO api = onlineApi("hash-new");
        SupplyAppDO app = SupplyAppDO.builder()
                .id(2L).appKey("app-key")
                .appStatus(SupplyGrantValidity.APP_ENABLED).build();
        AppApiDO grant = AppApiDO.builder()
                .id(3L).appId(2L).apiId(1L)
                .grantStatus(SupplyGrantValidity.STATUS_ACTIVE).foreverFlag(1)
                .approvedContractHash("hash-old")
                .validFrom(LocalDateTime.of(2020, 1, 1, 0, 0))
                .build();
        stub(service, api, app, grant);

        assertThatThrownBy(() -> invoke(service))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> assertThat(((ServiceException) ex).getCode()).isEqualTo(1_500_040_021));
    }

    private static DataApiDO onlineApi(String contractHash) {
        return DataApiDO.builder()
                .id(1L).status(DataApiDO.STATUS_ONLINE)
                .path("/orders").version("v1").method("GET")
                .apiType(DataApiDO.API_TYPE_QUERY).authType(DataApiDO.AUTH_TYPE_APPKEY)
                .configType(DataApiDO.CONFIG_TYPE_SQL)
                .contractHash(contractHash)
                .build();
    }

    private static DataApiGatewayService gateway() {
        DataApiGatewayService service = new DataApiGatewayService();
        ReflectionTestUtils.setField(service, "dataApiMapper", mock(DataApiMapper.class));
        ReflectionTestUtils.setField(service, "appMapper", mock(SupplyAppMapper.class));
        ReflectionTestUtils.setField(service, "appApiMapper", mock(AppApiMapper.class));
        ReflectionTestUtils.setField(service, "dataApiExecutor", mock(DataApiExecutor.class));
        return service;
    }

    private static void stub(DataApiGatewayService service, DataApiDO api, SupplyAppDO app, AppApiDO grant) {
        DataApiMapper dataApiMapper = (DataApiMapper) ReflectionTestUtils.getField(service, "dataApiMapper");
        SupplyAppMapper appMapper = (SupplyAppMapper) ReflectionTestUtils.getField(service, "appMapper");
        AppApiMapper appApiMapper = (AppApiMapper) ReflectionTestUtils.getField(service, "appApiMapper");
        when(dataApiMapper.selectListByPathAndVersion("/orders", "v1")).thenReturn(List.of(api));
        when(appMapper.selectByAppKey("app-key")).thenReturn(app);
        when(appApiMapper.selectByAppIdAndApiId(2L, 1L)).thenReturn(grant);
    }

    private static void invoke(DataApiGatewayService service) {
        service.invoke("v1", "/orders", "GET", Map.of(), null, null,
                "127.0.0.1", Map.of(DataApiGatewayService.HEADER_APP_KEY, "app-key"));
    }
}
