package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionInfo;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlPreviewReqVO;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import cn.iocoder.yudao.module.dataforge.service.security.DatabaseQuerySupport;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EtlDesignPreviewPostgreSqlTest {
    static PostgreSQLContainer<?> container;
    static String url, user, password;
    @BeforeAll static void postgres() {
        url = System.getProperty("datahub.test.postgres.url");
        if (url == null || url.isBlank()) {
            container = new cn.iocoder.yudao.module.dataforge.support.DataforgePostgreSqlContainer(); container.start();
            url = container.getJdbcUrl(); user = container.getUsername(); password = container.getPassword();
        } else {
            user = System.getProperty("datahub.test.postgres.user");
            password = System.getProperty("datahub.test.postgres.password", "");
        }
    }
    @AfterAll static void stop() { if (container != null) container.stop(); }
    private final EtlDesignService service = new EtlDesignService();
    private final EtlNodeAccessService access = new EtlNodeAccessService();
    private final DatabaseQuerySupport queries = mock(DatabaseQuerySupport.class);
    private final ExecutionAccessService databases = mock(ExecutionAccessService.class);
    private final EtlPolicyPlanService policies = mock(EtlPolicyPlanService.class);
    private final EtlEnvironmentService environments = mock(EtlEnvironmentService.class);
    private final EtlConnectionResolverAdapter connections = mock(EtlConnectionResolverAdapter.class);
    private DbSwitchRuntime runtime;
    private Connection control;
    private String schema;

    @BeforeEach void setup() throws Exception {
        TenantContextHolder.setTenantId(7L);
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(55L).setTenantId(7L), new MockHttpServletRequest());
        control = DriverManager.getConnection(url, user, password);
        schema = "design_preview_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema);
        sql("CREATE TABLE " + schema + ".\"Source.Rows\"(id integer, secret text)");
        sql("INSERT INTO " + schema + ".\"Source.Rows\" SELECT i,'private-fixture' FROM generate_series(1,1000) i");
        var info = new EtlConnectionInfo(); info.setDatabaseId(1L); info.setSchema("public");
        info.setJdbcUrl(url); info.setUsername(user); info.setPassword(password);
        info.setConfigurationRevision("fixture-revision-1");
        info.setProductType(ProductTypeEnum.POSTGRESQL);
        when(connections.resolve(1L)).thenAnswer(inv -> { return info; });
        runtime = DbSwitchRuntime.standalone();
        ReflectionTestUtils.setField(access, "executionAccessService", databases);
        ReflectionTestUtils.setField(access, "queries", queries);
        ReflectionTestUtils.setField(access, "connectionResolverAdapter", connections);
        ReflectionTestUtils.setField(service, "connections", connections);
        ReflectionTestUtils.setField(service, "nodeAccess", access);
        ReflectionTestUtils.setField(service, "databaseRuntime", runtime);
        ReflectionTestUtils.setField(service, "plans", policies);
        ReflectionTestUtils.setField(service, "environments", environments);
        when(environments.freeze(any(), anyList(), any())).thenAnswer(inv -> new EtlEnvironmentService.Resolved(inv.getArgument(1), Map.of()));
        when(policies.freeze(anyList(), eq(55L), any())).thenReturn(Map.of());
        when(queries.normalizeSelect(eq(1L), anyString())).thenAnswer(inv -> inv.getArgument(1));
    }

    @AfterEach void close() throws Exception {
        TenantContextHolder.clear(); SecurityContextHolder.clearContext();
        if (control != null) { sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); control.close(); }
        if (runtime != null) runtime.close();
    }

    @Test void previewPreservesRawRowsAndCurrentActorObjectAccess() {
        var result = service.preview(request());
        assertTrue(result.success(), result.issues().toString()); assertEquals(10, result.rows().size()); assertTrue(result.truncated());
        assertTrue(result.rows().stream().allMatch(row -> "private-fixture".equals(row.get("secret"))));
        assertEquals(11, result.inputRows());
        verify(databases, atLeastOnce()).requireDatabase(1L); verify(queries, atLeastOnce()).normalizeSelect(eq(1L), contains("\"Source.Rows\""));
        verify(policies).createPort(Map.of(), null);
    }

    @Test void selectedEnvironmentIsResolvedOnlyForTargetAncestorsAndDraftRemainsUnchanged() {
        var request = request(); request.setEnvironmentId(88L);
        request.setNodes(List.of(graphNode("source", "read-db", Map.of("logicalDatabaseKey", "warehouse", "tableName", "Source.Rows")),
                graphNode("unrelated", "managed-db-output", Map.of("logicalDatabaseKey", "denied"))));
        when(environments.freeze(eq(88L), anyList(), eq(55L))).thenAnswer(inv -> {
            List<EtlNodeDef> nodes = inv.getArgument(1); assertEquals(List.of("source"), nodes.stream().map(EtlNodeDef::getNo).toList());
            var bound = new EtlNodeDef(); bound.setNo("source"); bound.setName("source"); bound.setToolCode("read-db"); bound.setToolType(1);
            bound.setNodeJson(JSONUtil.toJsonStr(Map.of("databaseId", 1, "schemaName", schema, "tableName", "\"" + schema + "\".\"Source.Rows\"")));
            return new EtlEnvironmentService.Resolved(List.of(bound), Map.of());
        });
        String before = JSONUtil.toJsonStr(request.getNodes()); var result = service.preview(request);
        assertTrue(result.success(), result.issues().toString()); assertEquals(before, JSONUtil.toJsonStr(request.getNodes()));
        var order = inOrder(environments, databases, connections, queries);
        order.verify(environments).freeze(eq(88L), anyList(), eq(55L));
        order.verify(databases).requireDatabase(1L);
        order.verify(connections, calls(1)).resolve(1L);
        order.verify(queries, atLeastOnce()).normalizeSelect(eq(1L), contains("\"Source.Rows\""));
        var resolvedIds = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(connections, atLeastOnce()).resolve(resolvedIds.capture());
        assertTrue(resolvedIds.getAllValues().stream().allMatch(id -> id.equals(1L)),
                "Unrelated denied nodes must not be resolved during preflight, execution or return-time rechecks");
        assertEquals(0, runtime.queries().activeOperations());
    }

    @Test void missingActorAndObjectDenialCannotFallBackToUnrestrictedRead() {
        SecurityContextHolder.clearContext();
        var missingActor = assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                () -> service.preview(request()));
        assertEquals(cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.OPERATION_PERMISSION_DENIED.getCode(), missingActor.getCode());
        verifyNoInteractions(connections, databases, queries);
        assertEquals(0, runtime.queries().activeOperations());
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(55L).setTenantId(7L), new MockHttpServletRequest());
        doThrow(new SecurityException("denied")).when(databases).requireDatabase(1L);
        var denied = assertThrows(SecurityException.class, () -> service.preview(request()));
        assertEquals("denied", denied.getMessage());
        verify(databases).requireDatabase(1L);
        verifyNoInteractions(connections, queries);
        assertEquals(0, runtime.queries().activeOperations());
    }

    @Test void synchronousComparisonCapturesOriginalInputBeforeSort() {
        var request = sortedRequest();
        var result = service.preview(request);
        assertTrue(result.success(), result.issues().toString());
        assertEquals(11, result.targetInputRows());
        var input = result.inputSamples().getFirst();
        assertEquals("source", input.sourceNodeNo()); assertEquals(11, input.rowCount()); assertTrue(input.truncated());
        var wire = tools.jackson.databind.json.JsonMapper.builder().build().readTree(
                cn.iocoder.yudao.framework.etl.core.executor.support.EtlJsonValues.write(result));
        assertTrue(wire.get("inputSamples").get(0).get("rows").get(0).get("id").isNumber());
        assertEquals(1L, wire.get("inputSamples").get(0).get("rows").get(0).get("id").longValue());
        assertTrue(wire.get("rows").get(0).get("id").isNumber());
        assertEquals(11L, wire.get("rows").get(0).get("id").longValue());
        assertTrue(input.rows().stream().allMatch(row -> "private-fixture".equals(row.get("secret"))));
        assertTrue(cn.iocoder.yudao.framework.etl.core.executor.support.EtlJsonValues.write(result).contains("private-fixture"));
        assertEquals("INDEPENDENT_SAMPLES", result.comparisonMode());
    }

    private EtlPreviewReqVO sortedRequest() {
        var request = request();
        var source = request.getNodes().getFirst();
        request.setTargetNodeId("sorted");
        request.setNodes(List.of(source, Map.of("id", "sorted", "properties", Map.of("toolCode", "sort-records", "toolType", 2,
                "nodeJson", "{\"sorts\":[{\"field\":\"id\",\"order\":\"DESC\"}]}"))));
        request.setEdges(List.of(Map.of("sourceNodeId", "source", "targetNodeId", "sorted", "targetPort", "in")));
        return request;
    }

    private EtlPreviewReqVO request() {
        var request = new EtlPreviewReqVO(); request.setTargetNodeId("source"); request.setLimit(10); request.setParameters(Map.of());
        request.setNodes(List.of(graphNode("source", "read-db", Map.of("databaseId", 1, "schemaName", schema,
                "tableName", "\"" + schema + "\".\"Source.Rows\"")))); request.setEdges(List.of()); return request;
    }
    private static Map<String, Object> graphNode(String id, String code, Map<String, Object> config) {
        return Map.of("id", id, "properties", Map.of("toolCode", code, "toolType", 1, "nodeJson", JSONUtil.toJsonStr(config)));
    }
    private void sql(String sql) throws Exception { try (var statement = control.createStatement()) { statement.execute(sql); } }
}
