package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableSchemaMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionInfo;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.schema.EtlSchemaService;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlPipelineSaveReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlSchemaReqVO;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EtlSchemaAccessServiceTest {
    private final EtlPipelineServiceImpl service = new EtlPipelineServiceImpl();
    private final EtlNodeAccessService access = new EtlNodeAccessService();
    private final ExecutionAccessService databases = mock(ExecutionAccessService.class);
    private final EtlConnectionResolverAdapter connections = mock(EtlConnectionResolverAdapter.class);
    private final EtlEnvironmentService environments = mock(EtlEnvironmentService.class);
    private final DbSwitchRuntime runtime = mock(DbSwitchRuntime.class);
    private final MetaDataByJdbcServiceImpl metadata = mock(MetaDataByJdbcServiceImpl.class);
    private EtlConnectionInfo info;

    @BeforeEach void setup() {
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(7L), new MockHttpServletRequest());
        info = new EtlConnectionInfo(); info.setDatabaseId(42L); info.setProductType(ProductTypeEnum.POSTGRESQL);
        info.setJdbcUrl("jdbc:test"); info.setUsername("fixture"); info.setPassword(""); info.setSchema("actual");
        when(connections.resolve(42L)).thenReturn(info);
        when(runtime.jdbcMetadata(ProductTypeEnum.POSTGRESQL, null)).thenReturn(metadata);
        when(environments.freeze(any(), anyList(), any())).thenAnswer(call ->
                new EtlEnvironmentService.Resolved(call.getArgument(1), Map.of()));
        var column = new ColumnDescription(); column.setFieldName("id"); column.setFieldTypeName("int4");
        when(metadata.querySqlColumnMeta(anyString(), anyString(), anyString(), anyString())).thenReturn(List.of(column));
        when(metadata.queryTableSchema(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new TableSchemaMetadata(List.of(column), List.of()));
        ReflectionTestUtils.setField(access, "executionAccessService", databases);
        ReflectionTestUtils.setField(access, "connectionResolverAdapter", connections);
        ReflectionTestUtils.setField(service, "nodeAccessService", access);
        ReflectionTestUtils.setField(service, "connectionResolverAdapter", connections);
        ReflectionTestUtils.setField(service, "executionAccessService", databases);
        ReflectionTestUtils.setField(service, "environmentService", environments);
        ReflectionTestUtils.setField(service, "schemaService", new EtlSchemaService(runtime));
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @Test void typedParametersBindEverySchemaNodeBeforeEnvironmentResolutionWithoutChangingDraft() {
        var req = request("generate-records", Map.of("fields", List.of(Map.of("name", "${params.field}", "type", "String"))));
        var field = parameter("field", "STRING");
        String actual = "quoted\"\\field\nvalue";
        req.setParameterDefinitions(List.of(field)); req.setParameters(Map.of("field", actual));
        String before = JSONUtil.toJsonStr(req.getNodes());
        when(environments.freeze(any(), anyList(), eq(7L))).thenAnswer(call -> {
            List<EtlNodeDef> nodes = call.getArgument(1);
            assertEquals(actual, JSONUtil.parseObj(nodes.getFirst().getNodeJson()).getJSONArray("fields").getJSONObject(0).getStr("name"));
            return new EtlEnvironmentService.Resolved(nodes, Map.of());
        });

        var result = service.resolveSchema(req);

        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        assertEquals(actual, result.getNodes().get("source").getOutputFields().getFirst().getName());
        assertEquals(before, JSONUtil.toJsonStr(req.getNodes()));
        verifyNoInteractions(databases, connections, metadata);
    }

    @Test void databaseAccessAndActualSchemaAreResolvedBeforeFetchingMetadata() {
        var req = request("read-db", Map.of("databaseId", "${params.database}", "tableName", "${params.table}"));
        req.setParameterDefinitions(List.of(parameter("database", "INTEGER"), parameter("table", "STRING")));
        req.setParameters(Map.of("database", "42", "table", "Source.Rows"));

        var result = service.resolveSchema(req);

        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        var order = inOrder(databases, connections, runtime, metadata);
        order.verify(databases).requireDatabase(42L);
        order.verify(connections).resolve(42L);
        order.verify(databases).requireDatabase(42L);
        order.verify(runtime).jdbcMetadata(ProductTypeEnum.POSTGRESQL, null);
        order.verify(metadata).querySqlColumnMeta(eq("jdbc:test"), eq("fixture"), eq(""), contains("\"actual\".\"Source.Rows\""));
        verify(metadata, never()).queryTableSchema(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test void explicitSqlProjectionIsBoundBeforeMetadata() {
        var result = service.resolveSchema(request("read-db", Map.of("databaseId", 42, "ifSql", 1, "sql", "select id from source_rows")));
        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        assertEquals("id", result.getNodes().get("source").getOutputFields().getFirst().getName());
        var order = inOrder(metadata);
        order.verify(metadata).querySqlColumnMeta(eq("jdbc:test"), eq("fixture"), eq(""), contains("\"actual\".\"source_rows\""));
    }

    @Test void metadataUsesTheFrozenConnectionIdentityDespiteSourceReconfiguration() {
        var replaced = new EtlConnectionInfo(); replaced.setProductType(ProductTypeEnum.POSTGRESQL);
        replaced.setJdbcUrl("jdbc:replaced"); replaced.setUsername("replacement"); replaced.setPassword(""); replaced.setSchema("denied");
        when(connections.resolve(42L)).thenReturn(info, replaced);

        var result = service.resolveSchema(request("read-db", Map.of("databaseId", 42, "tableName", "source_rows")));

        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        verify(connections, times(1)).resolve(42L);
        verify(metadata).querySqlColumnMeta(eq("jdbc:test"), eq("fixture"), eq(""), contains("\"actual\".\"source_rows\""));
    }

    @Test void wildcardPreservesRequestedProjectionWithoutFetchingFullTableMetadata() {
        for (Map<String, Object> config : List.of(
                Map.<String, Object>of("databaseId", 42, "tableName", "source_rows"),
                Map.<String, Object>of("databaseId", 42, "ifSql", 1, "sql", "SELECT * FROM source_rows"))) {
            var result = service.resolveSchema(request("read-db", config));
            assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
            assertEquals(List.of("id"), result.getNodes().get("source").getOutputFields().stream().map(field -> field.getName()).toList());
            var field = result.getNodes().get("source").getOutputFields().getFirst();
            assertNull(field.getNullable()); assertNull(field.getPrecision()); assertNull(field.getScale());
        }
        verify(metadata, times(2)).querySqlColumnMeta(eq("jdbc:test"), eq("fixture"), eq(""), contains("SELECT *"));
        verify(metadata, never()).queryTableSchema(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test void multiStatementWriteCteFunctionsAndUnverifiedProductsFailClosed() {
        for (String sql : List.of("delete from source_rows; select id from source_rows",
                "with erased as (delete from source_rows returning id) select id from erased",
                "select pg_sleep(60) from source_rows", "select id from source_rows for update",
                "select id from source_rows union select id from other_rows")) {
            var failure = assertThrows(ServiceException.class, () -> service.resolveSchema(request("read-db",
                    Map.of("databaseId", 42, "ifSql", 1, "sql", sql))));
            assertEquals(ETL_PIPELINE_GRAPH_INVALID.getCode(), failure.getCode());
        }
        info.setProductType(ProductTypeEnum.ORACLE);
        var failure = assertThrows(ServiceException.class, () -> service.resolveSchema(request("read-db",
                Map.of("databaseId", 42, "ifSql", 1, "sql", "select id from source_rows"))));
        assertEquals(ETL_PIPELINE_GRAPH_INVALID.getCode(), failure.getCode());
        verifyNoInteractions(runtime, metadata);
    }

    @Test void missingActorAndDisabledSourceCannotResolveCredentialsOrMetadata() {
        SecurityContextHolder.clearContext();
        assertThrows(ServiceException.class, () -> service.resolveSchema(request("read-db", Map.of("databaseId", 42, "tableName", "source_rows"))));
        verifyNoInteractions(databases, connections, runtime, metadata);
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(7L), new MockHttpServletRequest());
        doThrow(cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(DATABASE_UNAVAILABLE)).when(databases).requireDatabase(42L);
        var failure = assertThrows(ServiceException.class, () -> service.resolveSchema(request("read-db", Map.of("databaseId", 42, "tableName", "source_rows"))));
        assertEquals(DATABASE_UNAVAILABLE.getCode(), failure.getCode());
        verifyNoInteractions(connections, runtime, metadata);
    }

    private static EtlPipelineSaveReqVO.ParamItem parameter(String name, String type) {
        var item = new EtlPipelineSaveReqVO.ParamItem(); item.setName(name); item.setType(type); return item;
    }
    private static EtlSchemaReqVO request(String code, Map<String, Object> config) {
        var req = new EtlSchemaReqVO(); req.setNodes(List.of(Map.of("id", "source", "properties",
                Map.of("toolCode", code, "toolType", 1, "nodeJson", config)))); req.setEdges(List.of()); return req;
    }
}
