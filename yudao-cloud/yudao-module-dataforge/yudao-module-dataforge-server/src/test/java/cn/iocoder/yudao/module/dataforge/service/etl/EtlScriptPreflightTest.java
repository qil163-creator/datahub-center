package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionInfo;
import cn.iocoder.yudao.framework.etl.core.engine.EtlSqlScriptBinding;
import cn.iocoder.yudao.framework.etl.core.model.EtlDag;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlRecordDO;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import cn.iocoder.yudao.module.dataforge.service.security.DatabaseQuerySupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EtlScriptPreflightTest {
    @Test void preflightReturnsTheImmutableValidatedPlanIncludingLiteralSemicolons() {
        Fixture fixture = new Fixture("{\"databaseId\":42,\"sql\":\"insert into example(value) values ('left;right'); update example set value='next';\"}");
        Map<String, EtlSqlScriptBinding> bindings = fixture.preflight();
        var plan = bindings.get("script").plan();
        assertEquals(42L, bindings.get("script").databaseId());
        assertEquals(2, plan.steps().size());
        assertTrue(plan.steps().getFirst().sql().contains("'left;right'"));
        verifyNoInteractions(fixture.queries);
        assertThrows(UnsupportedOperationException.class, bindings::clear);
    }

    @Test void malformedTailFailsBeforeAnyStatementExecution() {
        Fixture fixture = new Fixture("{\"databaseId\":42,\"sql\":\"delete from example; select 'unfinished\"}");
        assertThrows(IllegalArgumentException.class, fixture::preflight);
        verifyNoInteractions(fixture.queries);
    }
    private static final class Fixture {
        final EtlPipelineExecutor executor = new EtlPipelineExecutor();
        final DatabaseQuerySupport queries = mock(DatabaseQuerySupport.class);
        final EtlRecordDO record = new EtlRecordDO();
        final EtlDag dag;
        Fixture(String config) {
            record.setExecutionUserId(7L); record.setConfigJson("{\"parameters\":[]}");
            var node = new EtlNodeDef(); node.setNo("script"); node.setName("script");
            node.setToolCode("sql-script"); node.setToolType(EtlNodeDef.TOOL_TYPE_SCRIPT); node.setNodeJson(config);
            dag = EtlDag.build(List.of(node), List.of());
            var resolver = mock(EtlConnectionResolverAdapter.class);
            var connection = new EtlConnectionInfo(); connection.setProductType(ProductTypeEnum.POSTGRESQL);
            when(resolver.resolve(42L)).thenReturn(connection);
            ReflectionTestUtils.setField(executor, "executionAccessService", mock(ExecutionAccessService.class));
            ReflectionTestUtils.setField(executor, "connectionResolverAdapter", resolver);
            var nodeAccess = new EtlNodeAccessService();
            ReflectionTestUtils.setField(nodeAccess, "executionAccessService", mock(ExecutionAccessService.class));
            ReflectionTestUtils.setField(nodeAccess, "queries", queries);
            ReflectionTestUtils.setField(nodeAccess, "connectionResolverAdapter", resolver);
            ReflectionTestUtils.setField(executor, "nodeAccessService", nodeAccess);
            ReflectionTestUtils.setField(executor, "environmentService", mock(EtlEnvironmentService.class));
        }
        Map<String, EtlSqlScriptBinding> preflight() {
            return ReflectionTestUtils.invokeMethod(executor, "assertNodePermissions", record, dag);
        }
    }
}
