package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.supply.dal.dataobject.app.SupplyAppDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.appapi.AppApiDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiReleaseDO;
import cn.iocoder.yudao.module.supply.dal.mysql.app.SupplyAppMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.AppApiMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiReleaseMapper;
import cn.iocoder.yudao.module.supply.service.app.SupplyGrantValidity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

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

class DataApiSnapshotAcceptanceTest {

    @Test
    void t21GatewayUsesSnapshotNotUnpublishedDraft() {
        DataApiGatewayService gateway = gateway();
        DataApiDO api = onlineApi();
        api.setSqlText("SELECT secret FROM t");
        api.setDraftJson("{\"sqlText\":\"SELECT secret FROM t\",\"tableName\":\"draft\"}");
        api.setActiveReleaseId(99L);
        api.setContractHash("hash-a");
        DataApiReleaseDO release = DataApiReleaseDO.builder()
                .id(99L).apiId(1L).contractHash("hash-a")
                .configJson(JsonUtils.toJsonString(Map.of(
                        "sqlText", "SELECT id FROM published",
                        "tableName", "published",
                        "configType", "SQL",
                        "apiType", "QUERY",
                        "method", "GET",
                        "path", "/orders",
                        "version", "v1")))
                .build();
        DataApiReleaseMapper releaseMapper = mock(DataApiReleaseMapper.class);
        ReflectionTestUtils.setField(gateway, "dataApiReleaseMapper", releaseMapper);
        when(releaseMapper.selectById(99L)).thenReturn(release);
        stubAuth(gateway, api, enabledApp(), activeGrant("hash-a"));
        DataApiExecutor executor = (DataApiExecutor) ReflectionTestUtils.getField(gateway, "dataApiExecutor");
        when(executor.execute(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(),
                anyBoolean(), any())).thenReturn(new cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiExecuteRespVO());

        gateway.invoke("v1", "/orders", "GET", Map.of(), null, null,
                "127.0.0.1", Map.of(DataApiGatewayService.HEADER_APP_KEY, "app-key"));

        ArgumentCaptor<DataApiDO> captor = ArgumentCaptor.forClass(DataApiDO.class);
        verify(executor).execute(captor.capture(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(),
                anyBoolean(), any());
        assertThat(captor.getValue().getSqlText()).isEqualTo("SELECT id FROM published");
        assertThat(captor.getValue().getTableName()).isEqualTo("published");
    }

    @Test
    void t23ExpandedContractDoesNotInheritOldGrant() {
        DataApiGatewayService gateway = gateway();
        DataApiDO api = onlineApi();
        api.setContractHash("hash-new");
        stubAuth(gateway, api, enabledApp(), activeGrant("hash-old"));
        assertThatThrownBy(() -> gateway.invoke("v1", "/orders", "GET", Map.of(), null, null,
                "127.0.0.1", Map.of(DataApiGatewayService.HEADER_APP_KEY, "app-key")))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> assertThat(((ServiceException) ex).getCode()).isEqualTo(1_500_040_021));
        verify((DataApiExecutor) ReflectionTestUtils.getField(gateway, "dataApiExecutor"), never())
                .execute(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(),
                        anyBoolean(), any());
    }

    @Test
    void t24RollbackDoesNotRestoreRevokedGrant() {
        DataApiGatewayService gateway = gateway();
        DataApiDO api = onlineApi();
        api.setContractHash("hash-a");
        AppApiDO revoked = activeGrant("hash-a");
        revoked.setGrantStatus(SupplyGrantValidity.STATUS_REVOKED);
        stubAuth(gateway, api, enabledApp(), revoked);
        assertThatThrownBy(() -> gateway.invoke("v1", "/orders", "GET", Map.of(), null, null,
                "127.0.0.1", Map.of(DataApiGatewayService.HEADER_APP_KEY, "app-key")))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> assertThat(((ServiceException) ex).getCode()).isEqualTo(1_500_040_013));
    }

    @Test
    void t16RevokedAppRejectsEvenWithValidSecret() {
        DataApiGatewayService gateway = gateway();
        DataApiDO api = onlineApi();
        SupplyAppDO app = enabledApp();
        app.setAppStatus(SupplyGrantValidity.APP_REVOKED);
        stubAuth(gateway, api, app, activeGrant(null));
        assertThatThrownBy(() -> gateway.invoke("v1", "/orders", "GET", Map.of(), null, null,
                "127.0.0.1", Map.of(DataApiGatewayService.HEADER_APP_KEY, "app-key")))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> assertThat(((ServiceException) ex).getCode()).isEqualTo(1_500_042_005));
    }

    @Test
    void t13SecondConsumerWithoutGrantIsRejected() {
        DataApiGatewayService gateway = gateway();
        DataApiDO api = onlineApi();
        SupplyAppDO other = enabledApp();
        other.setId(99L);
        other.setAppKey("other-key");
        DataApiMapper dataApiMapper = (DataApiMapper) ReflectionTestUtils.getField(gateway, "dataApiMapper");
        SupplyAppMapper appMapper = (SupplyAppMapper) ReflectionTestUtils.getField(gateway, "appMapper");
        AppApiMapper appApiMapper = (AppApiMapper) ReflectionTestUtils.getField(gateway, "appApiMapper");
        when(dataApiMapper.selectListByPathAndVersion("/orders", "v1")).thenReturn(List.of(api));
        when(appMapper.selectByAppKey("other-key")).thenReturn(other);
        when(appApiMapper.selectByAppIdAndApiId(99L, 1L)).thenReturn(null);
        assertThatThrownBy(() -> gateway.invoke("v1", "/orders", "GET", Map.of(), null, null,
                "127.0.0.1", Map.of(DataApiGatewayService.HEADER_APP_KEY, "other-key")))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> assertThat(((ServiceException) ex).getCode()).isEqualTo(1_500_040_013));
    }

    private static DataApiDO onlineApi() {
        return DataApiDO.builder()
                .id(1L).status(DataApiDO.STATUS_ONLINE)
                .path("/orders").version("v1").method("GET")
                .apiType(DataApiDO.API_TYPE_QUERY).authType(DataApiDO.AUTH_TYPE_APPKEY)
                .configType(DataApiDO.CONFIG_TYPE_SQL)
                .build();
    }

    private static SupplyAppDO enabledApp() {
        return SupplyAppDO.builder()
                .id(2L).appKey("app-key")
                .appStatus(SupplyGrantValidity.APP_ENABLED).build();
    }

    private static AppApiDO activeGrant(String hash) {
        return AppApiDO.builder()
                .id(3L).appId(2L).apiId(1L)
                .grantStatus(SupplyGrantValidity.STATUS_ACTIVE).foreverFlag(1)
                .approvedContractHash(hash)
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

    private static void stubAuth(DataApiGatewayService service, DataApiDO api, SupplyAppDO app, AppApiDO grant) {
        DataApiMapper dataApiMapper = (DataApiMapper) ReflectionTestUtils.getField(service, "dataApiMapper");
        SupplyAppMapper appMapper = (SupplyAppMapper) ReflectionTestUtils.getField(service, "appMapper");
        AppApiMapper appApiMapper = (AppApiMapper) ReflectionTestUtils.getField(service, "appApiMapper");
        when(dataApiMapper.selectListByPathAndVersion("/orders", "v1")).thenReturn(List.of(api));
        when(appMapper.selectByAppKey(app.getAppKey())).thenReturn(app);
        when(appApiMapper.selectByAppIdAndApiId(app.getId(), api.getId())).thenReturn(grant);
    }
}
