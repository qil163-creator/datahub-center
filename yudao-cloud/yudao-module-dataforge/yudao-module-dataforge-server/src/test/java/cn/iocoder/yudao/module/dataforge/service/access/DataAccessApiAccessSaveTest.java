package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.ApiAccessMappingVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.DataAccessSaveReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.dataapi.vo.DataApiSaveReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.dataapi.vo.ResponseColumnItem;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataAccessApiAccessSaveTest {

    @Test
    void createApiAccessPersistsWithoutSourceDatabaseId() {
        var mapper = mock(DataAccessMapper.class);
        var databaseMapper = mock(DatabaseMapper.class);
        when(databaseMapper.selectById(1L)).thenReturn(DatabaseDO.builder().id(1L).build());
        when(mapper.insert(any(DataAccessDO.class))).thenAnswer(invocation -> {
            DataAccessDO row = invocation.getArgument(0);
            row.setId(4L);
            return 1;
        });
        var service = new DataAccessServiceImpl();
        ReflectionTestUtils.setField(service, "dataAccessMapper", mapper);
        ReflectionTestUtils.setField(service, "databaseMapper", databaseMapper);

        var column = new ApiAccessMappingVO.ApiColumnMapVO();
        column.setSourceColumn("latitude");
        column.setTargetColumn("latitude");
        column.setImportFlg(1);
        var mapping = new ApiAccessMappingVO();
        var api = new DataApiSaveReqVO();
        api.setApiName("天气预报"); api.setRequestUrl("https://example.invalid/weather"); api.setRequestMethod("GET");
        var sourceColumn = new ResponseColumnItem(); sourceColumn.setFieldName("latitude");
        api.setDataColumns(List.of(sourceColumn));
        mapping.setApiConfig(api);
        mapping.setSourceApiName("天气预报");
        mapping.setTargetTable("ods_apiceshi");
        mapping.setColumnMaps(List.of(column));
        var reqVO = new DataAccessSaveReqVO();
        reqVO.setTaskName("测试接口");
        reqVO.setEngineType(3);
        reqVO.setTaskType(2);
        reqVO.setTargetDatabaseId(1L);
        reqVO.setApiMappings(List.of(mapping));

        assertEquals(4L, service.createDataAccess(reqVO));
        var captor = ArgumentCaptor.forClass(DataAccessDO.class);
        verify(mapper).insert(captor.capture());
        DataAccessDO saved = captor.getValue();
        assertNull(saved.getSourceDatabaseId());
        assertEquals(3, saved.getEngineType());
        assertEquals(1L, saved.getTargetDatabaseId());
        assertTrue(saved.getApiAccessJson().contains("ods_apiceshi"));
        assertTrue(saved.getApiAccessJson().contains("https://example.invalid/weather"));
        assertTrue(!saved.getApiAccessJson().contains("sourceId"));
        var response = new cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.DataAccessRespVO();
        ReflectionTestUtils.invokeMethod(service, "fillApiAccessConfig", response, saved.getApiAccessJson());
        assertEquals(api.getRequestUrl(), response.getApiMappings().getFirst().getApiConfig().getRequestUrl());
    }

    @Test
    void incompleteInlineConfigurationIsRejectedBeforeSaving() {
        var databaseMapper = mock(DatabaseMapper.class);
        when(databaseMapper.selectById(1L)).thenReturn(DatabaseDO.builder().id(1L).build());
        var mapper = mock(DataAccessMapper.class);
        var service = new DataAccessServiceImpl();
        ReflectionTestUtils.setField(service, "databaseMapper", databaseMapper);
        ReflectionTestUtils.setField(service, "dataAccessMapper", mapper);
        var request = new DataAccessSaveReqVO(); request.setEngineType(3); request.setTaskType(2); request.setTargetDatabaseId(1L);
        request.setApiMappings(List.of(new ApiAccessMappingVO()));
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> service.createDataAccess(request));
        org.mockito.Mockito.verifyNoInteractions(mapper);
    }

    @Test
    void incrementalSavingRequiresAllDeclaredPrimaryKeys() {
        var databaseMapper = mock(DatabaseMapper.class);
        when(databaseMapper.selectById(1L)).thenReturn(DatabaseDO.builder().id(1L).build());
        var mapper = mock(DataAccessMapper.class); var service = new DataAccessServiceImpl();
        ReflectionTestUtils.setField(service, "databaseMapper", databaseMapper);
        ReflectionTestUtils.setField(service, "dataAccessMapper", mapper);
        var api = new DataApiSaveReqVO(); api.setApiName("接口"); api.setRequestMethod("GET"); api.setRequestUrl("https://example.invalid/data");
        var key = new ResponseColumnItem(); key.setFieldName("id"); key.setPk(true);
        var otherKey = new ResponseColumnItem(); otherKey.setFieldName("part"); otherKey.setPk(true);
        api.setDataColumns(List.of(key, otherKey));
        var column = new ApiAccessMappingVO.ApiColumnMapVO(); column.setSourceColumn("id"); column.setTargetColumn("id"); column.setImportFlg(1);
        var mapping = new ApiAccessMappingVO(); mapping.setApiConfig(api); mapping.setTargetTable("output"); mapping.setColumnMaps(List.of(column));
        var request = new DataAccessSaveReqVO(); request.setEngineType(3); request.setTaskType(2); request.setTargetDatabaseId(1L);
        request.setTargetDataSync(true); request.setApiMappings(List.of(mapping));
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> service.createDataAccess(request));
        org.mockito.Mockito.verifyNoInteractions(mapper);
        api.setDataColumns(List.of(key)); assertTrue(mapping.hasCompletePrimaryKeyMapping());
        key.setPk(false); org.junit.jupiter.api.Assertions.assertFalse(mapping.hasCompletePrimaryKeyMapping());
    }
}
