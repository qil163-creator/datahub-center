package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.api.database.PublishedQueryApi;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.framework.config.SupplyDataApiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.DATA_API_PARAM_REQUIRED;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataApiNullableParameterTest {
    private final DataApiExecutor executor = new DataApiExecutor();
    private final PublishedQueryApi rpc = mock(PublishedQueryApi.class);
    private final DataApiDO api = DataApiDO.builder().databaseId(10L).configType("SQL").apiType("QUERY")
            .authType("NONE").resultFormat("PAGE").build();

    DataApiNullableParameterTest() {
        ReflectionTestUtils.setField(executor, "publishedQueryApi", rpc);
        ReflectionTestUtils.setField(executor, "dataApiProperties", new SupplyDataApiProperties());
    }

    @Test void explicitAndMissingNullableParametersBindSqlNullAtEveryStaticPosition() {
        api.setRequestParamsJson("""
                [{"name":"value","nullable":1,"defaultValue":"fallback"},{"name":"missing","nullable":1}]
                """);
        api.setSqlText("SELECT #{value}, #{missing}, #{value}");
        var sqlNull = PublishedQueryApi.Bind.from(null);
        expectQuery(Collections.singletonMap("value", null), "SELECT ?, ?, ?", List.of(sqlNull, sqlNull, sqlNull));
        expectQuery(Map.of(), "SELECT ?, ?, ?", List.of(PublishedQueryApi.Bind.from("fallback"), sqlNull,
                PublishedQueryApi.Bind.from("fallback")));
    }

    @Test void dynamicTagsStillDistinguishNullAndTypedNonNullValues() {
        api.setRequestParamsJson("""
                [{"name":"n","nullable":1,"paramType":"INTEGER"}]
                """);
        api.setSqlText("SELECT 1<if test=\"n != null\">, #{n}</if><if test=\"n == null\">, 0</if>");
        expectQuery(Map.of(), "SELECT 1, 0", List.of());
        expectQuery(Map.of("n", "7"), "SELECT 1, ?", List.of(PublishedQueryApi.Bind.from(7L)));
    }

    @Test void optionalTableParametersStillOmitFiltersWhileDefaultsRemainEffective() {
        api.setConfigType("TABLE");
        api.setTableName("example");
        api.setRequestParamsJson("""
                [{"name":"note","nullable":1},{"name":"n","paramType":"INTEGER","nullable":1,"defaultValue":"5"}]
                """);
        expectQuery(Collections.singletonMap("note", null), "SELECT * FROM example WHERE n = ?",
                List.of(PublishedQueryApi.Bind.from(5L)));
    }

    @Test void requiredAndUndeclaredSqlParametersStillFail() {
        api.setSqlText("SELECT #{n}");
        for (String definition : List.of("[]", "[{\"name\":\"n\",\"nullable\":0}]")) {
            api.setRequestParamsJson(definition);
            var failure = assertThrows(ServiceException.class,
                    () -> execute(Collections.singletonMap("n", null)));
            assertEquals(DATA_API_PARAM_REQUIRED.getCode(), failure.getCode());
        }
        verifyNoInteractions(rpc);
    }

    @Test void nullBindingRoundTripsAcrossJsonAndRejectsInconsistentPayloads() {
        var decoded = JsonUtils.parseObject(JsonUtils.toJsonString(PublishedQueryApi.Bind.from(null)),
                PublishedQueryApi.Bind.class);
        assertEquals(PublishedQueryApi.Bind.Type.NULL, decoded.type());
        assertNull(decoded.jdbcValue());
        assertEquals("null", PublishedQueryApi.Bind.from("null").jdbcValue());
        assertThrows(IllegalArgumentException.class, () -> new PublishedQueryApi.Bind(PublishedQueryApi.Bind.Type.NULL, "null"));
        assertThrows(IllegalArgumentException.class, () -> new PublishedQueryApi.Bind(PublishedQueryApi.Bind.Type.STRING, null));
    }

    private void expectQuery(Map<String, Object> input, String sql, List<PublishedQueryApi.Bind> bindings) {
        var query = new PublishedQueryApi.Query(10L, sql, bindings, 1, 10, 30, null);
        when(rpc.query(query)).thenReturn(CommonResult.success(new PageResult<>(List.of(), 0L)));
        execute(input);
        verify(rpc).query(query);
    }

    private void execute(Map<String, Object> input) {
        executor.execute(api, input, 1, 10, "127.0.0.1", false, false, null, false, DataApiExecuteMode.APP_GATEWAY);
    }
}
