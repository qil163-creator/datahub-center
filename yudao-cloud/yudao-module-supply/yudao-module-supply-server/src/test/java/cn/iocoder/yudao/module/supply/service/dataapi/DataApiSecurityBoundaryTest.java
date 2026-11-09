package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.supply.dal.dataobject.app.SupplyAppDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.appapi.AppApiDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.controller.admin.app.SupplyAppController;
import cn.iocoder.yudao.module.supply.controller.admin.app.vo.SupplyAppSaveReqVO;
import cn.iocoder.yudao.module.supply.dal.mysql.apicategory.ApiCategoryMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.app.SupplyAppMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.AppApiMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiMapper;
import cn.iocoder.yudao.module.supply.framework.config.SupplyDataApiProperties;
import org.junit.jupiter.api.Test;
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

class DataApiSecurityBoundaryTest {

    @Test
    void createShouldAlwaysSaveOfflineEvenWhenRequestPassesOnline() {
        DataApiMapper dataApiMapper = mock(DataApiMapper.class);
        DataApiServiceImpl service = newDataApiService(dataApiMapper);
        var reqVO = new cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiSaveReqVO();
        reqVO.setCategoryId(1L);
        reqVO.setName("api");
        reqVO.setVersion("v1");
        reqVO.setPath("/orders");
        reqVO.setMethod("GET");
        reqVO.setResultFormat(DataApiDO.RESULT_FORMAT_LIST);
        reqVO.setConfigType(DataApiDO.CONFIG_TYPE_TABLE);
        reqVO.setStatus(DataApiDO.STATUS_ONLINE);

        service.createDataApi(reqVO);

        org.mockito.ArgumentCaptor<DataApiDO> captor = org.mockito.ArgumentCaptor.forClass(DataApiDO.class);
        verify(dataApiMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(DataApiDO.STATUS_OFFLINE);
    }

    @Test
    void updateShouldNotPersistOnlineStatusFromSaveRequest() {
        DataApiMapper dataApiMapper = mock(DataApiMapper.class);
        when(dataApiMapper.selectByIdForUpdate(9L)).thenReturn(DataApiDO.builder()
                .id(9L).status(DataApiDO.STATUS_OFFLINE).build());
        DataApiServiceImpl service = newDataApiService(dataApiMapper);
        var reqVO = new cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiSaveReqVO();
        reqVO.setId(9L);
        reqVO.setCategoryId(1L);
        reqVO.setName("api");
        reqVO.setVersion("v1");
        reqVO.setPath("/orders");
        reqVO.setMethod("GET");
        reqVO.setResultFormat(DataApiDO.RESULT_FORMAT_LIST);
        reqVO.setConfigType(DataApiDO.CONFIG_TYPE_TABLE);
        reqVO.setStatus(DataApiDO.STATUS_ONLINE);

        service.updateDataApi(reqVO);

        org.mockito.ArgumentCaptor<DataApiDO> captor = org.mockito.ArgumentCaptor.forClass(DataApiDO.class);
        verify(dataApiMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isNull();
    }

    @Test
    void updateShouldWriteDraftWhenExistingApiIsOnline() {
        DataApiMapper dataApiMapper = mock(DataApiMapper.class);
        when(dataApiMapper.selectByIdForUpdate(9L)).thenReturn(DataApiDO.builder()
                .id(9L).status(DataApiDO.STATUS_ONLINE).draftRevision(2).build());
        DataApiServiceImpl service = newDataApiService(dataApiMapper);
        var reqVO = new cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiSaveReqVO();
        reqVO.setId(9L);
        reqVO.setCategoryId(1L);
        reqVO.setName("api");
        reqVO.setVersion("v1");
        reqVO.setPath("/orders");
        reqVO.setMethod("GET");
        reqVO.setResultFormat(DataApiDO.RESULT_FORMAT_LIST);
        reqVO.setConfigType(DataApiDO.CONFIG_TYPE_TABLE);
        reqVO.setStatus(DataApiDO.STATUS_OFFLINE);

        service.updateDataApi(reqVO);

        org.mockito.ArgumentCaptor<DataApiDO> captor = org.mockito.ArgumentCaptor.forClass(DataApiDO.class);
        verify(dataApiMapper).updateById(captor.capture());
        assertThat(captor.getValue().getDraftJson()).isNotBlank();
        assertThat(captor.getValue().getDraftRevision()).isEqualTo(3);
        assertThat(captor.getValue().getPath()).isNull();
    }

    @Test
    void publishShouldRejectAnonymousForwardApi() {
        DataApiMapper dataApiMapper = mock(DataApiMapper.class);
        when(dataApiMapper.selectByIdForUpdate(7L)).thenReturn(DataApiDO.builder()
                .id(7L)
                .status(DataApiDO.STATUS_OFFLINE)
                .apiType(DataApiDO.API_TYPE_QUERY)
                .authType(DataApiDO.AUTH_TYPE_NONE)
                .configType(DataApiDO.CONFIG_TYPE_FORWARD)
                .forwardUrl("https://example.com/api")
                .build());
        DataApiServiceImpl service = newDataApiService(dataApiMapper);

        assertThatThrownBy(() -> service.publishDataApi(7L, 0))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("写入或转发接口不能使用 NONE 认证");
        verify(dataApiMapper, never()).updateById(any(DataApiDO.class));
    }

    @Test
    void gatewayShouldRejectAnonymousWriteAndForwardBeforeExecute() {
        DataApiMapper dataApiMapper = mock(DataApiMapper.class);
        DataApiExecutor executor = mock(DataApiExecutor.class);
        DataApiGatewayService service = new DataApiGatewayService();
        ReflectionTestUtils.setField(service, "dataApiMapper", dataApiMapper);
        ReflectionTestUtils.setField(service, "dataApiExecutor", executor);

        DataApiDO writeApi = DataApiDO.builder()
                .id(1L).status(DataApiDO.STATUS_ONLINE)
                .path("/orders").version("v1").method("POST")
                .apiType(DataApiDO.API_TYPE_INSERT).authType(DataApiDO.AUTH_TYPE_NONE)
                .configType(DataApiDO.CONFIG_TYPE_SQL).build();
        when(dataApiMapper.selectListByPathAndVersion("/orders", "v1")).thenReturn(List.of(writeApi));
        assertThatThrownBy(() -> service.invoke("v1", "/orders", "POST", Map.of(), null, null, "127.0.0.1", Map.of()))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> assertThat(((ServiceException) ex).getCode()).isEqualTo(1_500_040_013));

        DataApiDO forwardApi = DataApiDO.builder()
                .id(2L).status(DataApiDO.STATUS_ONLINE)
                .path("/forward").version("v1").method("GET")
                .apiType(DataApiDO.API_TYPE_QUERY).authType(DataApiDO.AUTH_TYPE_NONE)
                .configType(DataApiDO.CONFIG_TYPE_FORWARD).build();
        when(dataApiMapper.selectListByPathAndVersion("/forward", "v1")).thenReturn(List.of(forwardApi));
        assertThatThrownBy(() -> service.invoke("v1", "/forward", "GET", Map.of(), null, null, "127.0.0.1", Map.of()))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> assertThat(((ServiceException) ex).getCode()).isEqualTo(1_500_040_013));

        verify(executor, never()).execute(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(),
                anyBoolean(), any());
    }

    @Test
    void gatewayShouldRejectMissingAuthorizationRow() {
        DataApiMapper dataApiMapper = mock(DataApiMapper.class);
        SupplyAppMapper appMapper = mock(SupplyAppMapper.class);
        AppApiMapper appApiMapper = mock(AppApiMapper.class);
        DataApiExecutor executor = mock(DataApiExecutor.class);
        DataApiGatewayService service = new DataApiGatewayService();
        ReflectionTestUtils.setField(service, "dataApiMapper", dataApiMapper);
        ReflectionTestUtils.setField(service, "appMapper", appMapper);
        ReflectionTestUtils.setField(service, "appApiMapper", appApiMapper);
        ReflectionTestUtils.setField(service, "dataApiExecutor", executor);

        DataApiDO api = DataApiDO.builder()
                .id(1L).status(DataApiDO.STATUS_ONLINE)
                .path("/orders").version("v1").method("GET")
                .apiType(DataApiDO.API_TYPE_QUERY).authType(DataApiDO.AUTH_TYPE_APPKEY)
                .configType(DataApiDO.CONFIG_TYPE_SQL).build();
        SupplyAppDO app = SupplyAppDO.builder()
                .id(2L).appKey("app-key").build();
        AppApiDO appApi = AppApiDO.builder()
                .id(3L).appId(2L).apiId(1L).foreverFlag(1).build();
        when(dataApiMapper.selectListByPathAndVersion("/orders", "v1")).thenReturn(List.of(api));
        when(appMapper.selectByAppKey("app-key")).thenReturn(app);
        when(appApiMapper.selectByAppIdAndApiId(2L, 1L)).thenReturn(null);

        assertThatThrownBy(() -> service.invoke("v1", "/orders", "GET", Map.of(), null, null,
                "127.0.0.1", Map.of(DataApiGatewayService.HEADER_APP_KEY, "app-key")))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> assertThat(((ServiceException) ex).getCode()).isEqualTo(1_500_040_013));

        verify(executor, never()).execute(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(),
                anyBoolean(), any());
    }

    @Test
    void secretCipherShouldFailClosedWhenEncryptorConfigMissing() {
        assertThatThrownBy(() -> SupplyAppSecretCipher.decrypt("SM4:AAAA"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mybatis-plus.encryptor.password");
        assertThatThrownBy(() -> SupplyAppSecretCipher.encrypt("plain-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mybatis-plus.encryptor.password");
    }

    @Test
    void appSecretOneTimeResponseShouldNotBeRecordedInApiAccessLog() throws Exception {
        ApiAccessLog createLog = SupplyAppController.class
                .getMethod("createApp", SupplyAppSaveReqVO.class)
                .getAnnotation(ApiAccessLog.class);
        ApiAccessLog resetLog = SupplyAppController.class
                .getMethod("resetAppSecret", Long.class)
                .getAnnotation(ApiAccessLog.class);

        assertThat(createLog).isNotNull();
        assertThat(createLog.responseEnable()).isFalse();
        assertThat(resetLog).isNotNull();
        assertThat(resetLog.responseEnable()).isFalse();
    }

    @Test
    void gatewayPermissionSubjectMustNotFallBackToCreator() {
        AppApiMapper appApiMapper = mock(AppApiMapper.class);
        SupplyAppMapper supplyAppMapper = mock(SupplyAppMapper.class);
        DataApiExecutor executor = new DataApiExecutor();
        ReflectionTestUtils.setField(executor, "appApiMapper", appApiMapper);
        ReflectionTestUtils.setField(executor, "supplyAppMapper", supplyAppMapper);
        AppApiDO appApi = AppApiDO.builder().appId(2L).apiId(1L).permissionUserId(null).build();
        when(appApiMapper.selectByAppIdAndApiId(2L, 1L)).thenReturn(null);
        SupplyAppDO app = SupplyAppDO.builder().id(2L).build();
        app.setCreator("1");
        when(supplyAppMapper.selectById(2L)).thenReturn(app);

        Long subject = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                executor, "resolvePermissionUserId", 2L, 1L, false);
        assertThat(subject).isNull();
    }

    private DataApiServiceImpl newDataApiService(DataApiMapper dataApiMapper) {
        DataApiServiceImpl service = new DataApiServiceImpl();
        ReflectionTestUtils.setField(service, "dataApiMapper", dataApiMapper);
        ReflectionTestUtils.setField(service, "apiCategoryMapper", mock(ApiCategoryMapper.class));
        ReflectionTestUtils.setField(service, "appApiMapper", mock(AppApiMapper.class));
        SupplyDataApiProperties properties = new SupplyDataApiProperties();
        properties.setForwardAllowHosts(java.util.Set.of("example.com"));
        ReflectionTestUtils.setField(service, "dataApiProperties", properties);
        return service;
    }

}
