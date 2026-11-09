package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.JdbcSelectResult;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiSaveReqVO;
import cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiTestReqVO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.dal.mysql.apilog.ApiLogMapper;
import cn.iocoder.yudao.module.supply.framework.config.SupplyDataApiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 查询直接消费数据库返回值，应用配额仍限制实际取数行数。 */
class DataApiQueryExecutionTest {
    @Test void draftClassifiesTheExpandedAndBoundSqlThatReachesJdbc() {
        var runtime = mock(DbSwitchRuntime.class);
        var metadata = mock(MetaDataByJdbcServiceImpl.class);
        when(runtime.jdbcMetadata(ProductTypeEnum.POSTGRESQL)).thenReturn(metadata);
        var jdbc = new JdbcSelectResult();
        jdbc.setSuccess(true);
        jdbc.setRowData(List.of(Map.of("id", 7)));
        when(metadata.pageDataBySql(anyString(), anyString(), anyString(), anyString(), anyList(), anyInt(), anyInt(), isNull(), anyInt()))
                .thenReturn(jdbc);
        var service = draftService(runtime);
        var request = templateRequest("SELECT id FROM public.example "
                + "<where><if test=\"name != null\">AND name=#{name}</if></where>");
        assertEquals(List.of(Map.of("id", 7)), service.testDataApi(request, "127.0.0.1").getRows());
        verify(metadata).pageDataBySql(eq("jdbc:test"), eq("test"), eq(""),
                argThat(sql -> sql.contains("WHERE name=?") && !sql.contains("<if") && !sql.contains("#{")),
                eq(List.of("测站' OR 1=1 --")), eq(1), eq(10), isNull(), eq(30));
    }

    @Test void expandedDynamicWriteIsRejectedBeforeJdbcEvenForQueryType() {
        var runtime = mock(DbSwitchRuntime.class);
        var service = draftService(runtime);
        var request = templateRequest("SELECT id FROM public.example "
                + "<if test=\"name != null\">; DELETE FROM public.example</if>");
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                () -> service.testDataApi(request, "127.0.0.1"));
        verifyNoInteractions(runtime);
    }

    @Test void queryKeepsSqlBindingsAndRowsWhileApplyingApplicationQuota() throws Exception {
        var executor = new DataApiExecutor();
        ReflectionTestUtils.setField(executor, "dataApiProperties", new SupplyDataApiProperties());
        var resolver = mock(SupplyDatabaseResolver.class);
        var runtime = mock(DbSwitchRuntime.class);
        var metadata = mock(MetaDataByJdbcServiceImpl.class);
        var connection = new SupplyDatabaseResolver.ConnectionInfo();
        connection.setProductType(ProductTypeEnum.POSTGRESQL);
        connection.setJdbcUrl("jdbc:test"); connection.setUsername("test"); connection.setPassword("");
        when(resolver.resolve(10L)).thenReturn(connection);
        when(runtime.jdbcMetadata(ProductTypeEnum.POSTGRESQL)).thenReturn(metadata);
        ReflectionTestUtils.setField(executor, "databaseResolver", resolver);
        ReflectionTestUtils.setField(executor, "dbSwitchRuntime", runtime);
        var jdbc = new JdbcSelectResult();
        jdbc.setSuccess(true); jdbc.setRowData(List.of(Map.of("id", 7, "payload", "原始值")));
        when(metadata.pageDataBySql(anyString(), anyString(), anyString(), anyString(), anyList(), anyInt(), anyInt(), isNull(), anyInt()))
                .thenReturn(jdbc);
        var api = DataApiDO.builder().databaseId(10L).build();
        String sql = "SELECT id,payload FROM public.example WHERE id=?";
        var result = executor.queryDatabase(api, sql, List.of(7), 1, 20,
                new DataApiExecutor.ExecuteContext(9L, false, 5));
        verify(metadata).pageDataBySql("jdbc:test", "test", "", sql, List.of(7), 1, 5, null, 30);
        assertEquals(List.of(Map.of("id", 7, "payload", "原始值")), result.getRows());
    }

    private DataApiServiceImpl draftService(DbSwitchRuntime runtime) {
        var connection = new SupplyDatabaseResolver.ConnectionInfo();
        connection.setProductType(ProductTypeEnum.POSTGRESQL);
        connection.setJdbcUrl("jdbc:test");
        connection.setUsername("test");
        connection.setPassword("");
        var resolver = mock(SupplyDatabaseResolver.class);
        when(resolver.resolve(10L)).thenReturn(connection);
        var executor = new DataApiExecutor();
        ReflectionTestUtils.setField(executor, "databaseResolver", resolver);
        ReflectionTestUtils.setField(executor, "dbSwitchRuntime", runtime);
        ReflectionTestUtils.setField(executor, "dataApiProperties", new SupplyDataApiProperties());
        ReflectionTestUtils.setField(executor, "apiLogMapper", mock(ApiLogMapper.class));
        var service = new DataApiServiceImpl();
        ReflectionTestUtils.setField(service, "dataApiExecutor", executor);
        return service;
    }

    private DataApiTestReqVO templateRequest(String sql) {
        var parameter = new DataApiSaveReqVO.ParamItem();
        parameter.setName("name");
        parameter.setParamType("STRING");
        parameter.setNullable(0);
        var api = new DataApiSaveReqVO();
        api.setDatabaseId(10L);
        api.setConfigType("SQL");
        api.setApiType("QUERY");
        api.setResultFormat("PAGE");
        api.setSqlText(sql);
        api.setRequestParams(List.of(parameter));
        var request = new DataApiTestReqVO();
        request.setApi(api);
        request.setParams(Map.of("name", "测站' OR 1=1 --"));
        request.setPageNo(1);
        request.setPageSize(10);
        return request;
    }
}
