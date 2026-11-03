package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.etl.core.connection.*;
import cn.iocoder.yudao.framework.etl.core.engine.EtlPreviewEngine;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlExecutionControl;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlPreviewReqVO;
import org.junit.jupiter.api.*;
import org.mockito.MockedConstruction;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EtlDesignSessionSourceTest {
    EtlDesignService service; EtlConnectionResolverAdapter connections; EtlConnectionInfo connection;
    @BeforeEach void setup() {
        TenantContextHolder.setTenantId(1L); service = new EtlDesignService();
        connections = mock(EtlConnectionResolverAdapter.class); connection = new EtlConnectionInfo();
        connection.setConfigurationRevision("version-1"); when(connections.resolve(1L)).thenReturn(connection);
        var policies = mock(EtlPolicyPlanService.class); when(policies.freeze(any(), any(), any())).thenReturn(Map.of());
        var access = mock(EtlNodeAccessService.class); when(access.createReadPort(7L)).thenReturn(mock(EtlReadPolicyPort.class));
        ReflectionTestUtils.setField(service, "plans", policies); ReflectionTestUtils.setField(service, "subflows", new EtlSubflowService());
        ReflectionTestUtils.setField(service, "environments", new EtlEnvironmentService()); ReflectionTestUtils.setField(service, "connections", connections);
        ReflectionTestUtils.setField(service, "nodeAccess", access); ReflectionTestUtils.setField(service, "databaseRuntime", mock(DbSwitchRuntime.class));
    }
    @AfterEach void clear() { TenantContextHolder.clear(); }
    @Test void cachedSourceRevisionMustStillMatchBeforeAnyResultCanBeRead() {
        var guard = new EtlPreviewResultGuard();
        try (MockedConstruction<EtlPreviewEngine> engines = mockConstruction(EtlPreviewEngine.class, (engine, context) ->
                when(engine.preview(anyList(), anyList(), anyString(), anyInt(), anyMap(), nullable(EtlRowPolicyPort.class),
                        any(EtlPreviewEngine.DatabaseSource.class), any(EtlExecutionControl.class)))
                        .thenReturn(new EtlPreviewEngine.Result(true, List.of(), List.of(), 0, 0, List.of(), false)))) {
            service.preview(request(), 7L, new EtlExecutionControl(), guard);
            assertDoesNotThrow(guard::verify);
            connection.setConfigurationRevision("version-2");
            assertThrows(IllegalStateException.class, guard::verify);
            verify(engines.constructed().getFirst()).preview(anyList(), anyList(), anyString(), anyInt(), anyMap(), nullable(EtlRowPolicyPort.class), any(), any());
        }
    }
    @Test void missingSourceVersionFailsBeforeCreatingAnEngine() {
        connection.setConfigurationRevision(null);
        assertThrows(IllegalStateException.class, () -> service.preview(request(), 7L, new EtlExecutionControl(), new EtlPreviewResultGuard()));
    }
    private EtlPreviewReqVO request() {
        var request = new EtlPreviewReqVO(); request.setTargetNodeId("source"); request.setParameters(Map.of());
        request.setNodes(List.of(Map.of("id", "source", "properties", Map.of("toolCode", "read-db", "toolType", 1,
                "nodeJson", Map.of("databaseId", 1, "tableName", "source", "ifSql", 0))))); request.setEdges(List.of()); return request;
    }
}
