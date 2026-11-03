package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiReleaseDO;
import cn.iocoder.yudao.module.supply.dal.mysql.apicategory.ApiCategoryMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.AppApiMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiReleaseMapper;
import cn.iocoder.yudao.module.supply.framework.config.SupplyDataApiProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataApiPublishServiceTest {

    @Test
    void publishConflictWhenRowVersionMismatches() {
        DataApiMapper dataApiMapper = mock(DataApiMapper.class);
        when(dataApiMapper.selectByIdForUpdate(1L)).thenReturn(onlineApi(3));
        DataApiServiceImpl service = newService(dataApiMapper, mock(DataApiReleaseMapper.class));

        assertThatThrownBy(() -> service.publishDataApi(1L, 2))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> assertThat(((ServiceException) ex).getCode()).isEqualTo(1_500_040_019));
    }

    @Test
    void publishCreatesImmutableReleaseAndClearsDraft() {
        DataApiMapper dataApiMapper = mock(DataApiMapper.class);
        DataApiReleaseMapper releaseMapper = mock(DataApiReleaseMapper.class);
        DataApiDO locked = onlineApi(0);
        locked.setDraftJson("{\"path\":\"/draft\",\"method\":\"GET\",\"configType\":\"TABLE\",\"apiType\":\"QUERY\",\"databaseId\":1,\"tableName\":\"t\"}");
        when(dataApiMapper.selectByIdForUpdate(1L)).thenReturn(locked);
        when(releaseMapper.selectMaxRevision(1L)).thenReturn(1);
        DataApiServiceImpl service = newService(dataApiMapper, releaseMapper);

        service.publishDataApi(1L, 0);

        ArgumentCaptor<DataApiReleaseDO> captor = ArgumentCaptor.forClass(DataApiReleaseDO.class);
        verify(releaseMapper).insert(captor.capture());
        assertThat(captor.getValue().getRevision()).isEqualTo(2);
        assertThat(captor.getValue().getConfigJson()).contains("/draft");
        ArgumentCaptor<DataApiDO> published = ArgumentCaptor.forClass(DataApiDO.class);
        verify(dataApiMapper).updateById(published.capture());
        assertThat(published.getValue().getRowVersion()).isEqualTo(1);
    }

    @Test
    void reenablingApiAdvancesPublishControlVersion() {
        DataApiMapper dataApiMapper = mock(DataApiMapper.class);
        DataApiDO locked = onlineApi(3);
        locked.setStatus(DataApiDO.STATUS_OFFLINE);
        when(dataApiMapper.selectByIdForUpdate(1L)).thenReturn(locked);
        DataApiServiceImpl service = newService(dataApiMapper, mock(DataApiReleaseMapper.class));

        service.updateDataApiStatus(1L, DataApiDO.STATUS_ONLINE, 3);

        ArgumentCaptor<DataApiDO> published = ArgumentCaptor.forClass(DataApiDO.class);
        verify(dataApiMapper).updateById(published.capture());
        assertThat(published.getValue().getRowVersion()).isEqualTo(4);
        assertThat(published.getValue().getStatus()).isEqualTo(DataApiDO.STATUS_ONLINE);
    }

    @Test
    void missingOrStaleVersionCannotPublish() {
        DataApiMapper mapper = mock(DataApiMapper.class);
        when(mapper.selectByIdForUpdate(1L)).thenReturn(onlineApi(3));
        DataApiServiceImpl service = newService(mapper, mock(DataApiReleaseMapper.class));
        assertThatThrownBy(() -> service.publishDataApi(1L, null)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.updateDataApiStatus(1L, 0, 2)).isInstanceOf(ServiceException.class);
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).updateById(org.mockito.ArgumentMatchers.any(DataApiDO.class));
    }

    @Test
    void offliningAdvancesVersionAndBothEntrypointsRequirePublishPermission() throws Exception {
        var method = cn.iocoder.yudao.module.supply.controller.admin.dataapi.DataApiController.class
                .getMethod("updateDataApiStatus", cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiUpdateStatusReqVO.class);
        assertThat(method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value())
                .isEqualTo("@ss.hasPermission('supply:data-api:publish')");
        DataApiMapper mapper = mock(DataApiMapper.class);
        when(mapper.selectByIdForUpdate(1L)).thenReturn(onlineApi(3));
        newService(mapper, mock(DataApiReleaseMapper.class)).updateDataApiStatus(1L, 0, 3);
        var change = ArgumentCaptor.forClass(DataApiDO.class);
        verify(mapper).updateById(change.capture());
        assertThat(change.getValue().getStatus()).isZero();
        assertThat(change.getValue().getRowVersion()).isEqualTo(4);
    }

    @Test
    void rollbackAdvancesControlVersionInsteadOfRestoringOldVersion() {
        DataApiMapper dataApiMapper = mock(DataApiMapper.class);
        DataApiReleaseMapper releaseMapper = mock(DataApiReleaseMapper.class);
        when(dataApiMapper.selectByIdForUpdate(1L)).thenReturn(onlineApi(3));
        when(releaseMapper.selectById(9L)).thenReturn(DataApiReleaseDO.builder().id(9L).apiId(1L)
                .configJson(cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(
                        DataApiContract.snapshot(onlineApi(1)))).contractHash("original").build());
        DataApiServiceImpl service = newService(dataApiMapper, releaseMapper);

        service.rollbackDataApi(1L, 9L, 3);

        ArgumentCaptor<DataApiDO> published = ArgumentCaptor.forClass(DataApiDO.class);
        verify(dataApiMapper).updateById(published.capture());
        assertThat(published.getValue().getRowVersion()).isEqualTo(4);
        assertThat(published.getValue().getActiveReleaseId()).isEqualTo(9L);
    }

    @Test
    void rollbackRejectsSnapshotOfAnotherApi() {
        DataApiMapper dataApiMapper = mock(DataApiMapper.class);
        DataApiReleaseMapper releaseMapper = mock(DataApiReleaseMapper.class);
        when(dataApiMapper.selectByIdForUpdate(1L)).thenReturn(onlineApi(0));
        when(releaseMapper.selectById(9L)).thenReturn(DataApiReleaseDO.builder()
                .id(9L).apiId(2L).configJson("{\"configType\":\"TABLE\"}").build());
        DataApiServiceImpl service = newService(dataApiMapper, releaseMapper);

        assertThatThrownBy(() -> service.rollbackDataApi(1L, 9L, 0))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> assertThat(((ServiceException) ex).getCode()).isEqualTo(1_500_040_020));
    }

    private static DataApiDO onlineApi(int rowVersion) {
        return DataApiDO.builder()
                .id(1L)
                .status(DataApiDO.STATUS_ONLINE)
                .apiType(DataApiDO.API_TYPE_QUERY)
                .authType(DataApiDO.AUTH_TYPE_APPKEY)
                .configType(DataApiDO.CONFIG_TYPE_TABLE)
                .databaseId(1L)
                .tableName("t")
                .path("/orders")
                .method("GET")
                .version("v1")
                .rowVersion(rowVersion)
                .providerDeptId(1L)
                .ownerUserId(1L)
                .build();
    }

    private static DataApiServiceImpl newService(DataApiMapper dataApiMapper, DataApiReleaseMapper releaseMapper) {
        DataApiServiceImpl service = new DataApiServiceImpl();
        ReflectionTestUtils.setField(service, "dataApiMapper", dataApiMapper);
        ReflectionTestUtils.setField(service, "dataApiReleaseMapper", releaseMapper);
        ReflectionTestUtils.setField(service, "apiCategoryMapper", mock(ApiCategoryMapper.class));
        ReflectionTestUtils.setField(service, "appApiMapper", mock(AppApiMapper.class));
        SupplyDataApiProperties properties = new SupplyDataApiProperties();
        properties.setForwardAllowHosts(java.util.Set.of("example.com"));
        ReflectionTestUtils.setField(service, "dataApiProperties", properties);
        return service;
    }
}
