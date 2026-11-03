package cn.iocoder.yudao.framework.etl.core.engine;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.JdbcSelectResult;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionInfo;
import cn.iocoder.yudao.framework.etl.core.executor.builtin.SqlScriptExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlDag;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunListener;
import cn.iocoder.yudao.framework.etl.core.variable.EtlVariableContext;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EtlSqlScriptPlanTest {
    @Test void executionUsesTheExactFrozenAuthorizedPlanWithTheSelectedDriverProfile() {
        var plan = SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL,
                "insert into example(value) values ('left;right'); update example set value='approved'");
        Fixture fixture = new Fixture(Map.of("script", new EtlSqlScriptBinding(42L, plan)));
        fixture.node.setNodeJson("{\"databaseId\":42,\"sql\":\"delete from unapproved_table\"}");
        var success = new JdbcSelectResult(); success.setSuccess(true);
        when(fixture.metadata.executePlan(anyString(), anyString(), anyString(), same(plan), anyList(), eq(0), eq(1), isNull(), any()))
                .thenReturn(success);
        fixture.execute();
        assertTrue(fixture.context.getNodeFuture("script").join());
        verify(fixture.runtime).jdbcMetadata(ProductTypeEnum.POSTGRESQL, "approved-profile");
        verify(fixture.metadata).executePlan(anyString(), anyString(), anyString(), same(plan), eq(List.of()), eq(0), eq(1), isNull(), any());
    }

    @Test void missingOrMismatchedPreflightBindingNeverExecutes() {
        var plan = SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, "delete from example");
        for (Map<String, EtlSqlScriptBinding> binding : List.of(Map.<String, EtlSqlScriptBinding>of(),
                Map.of("script", new EtlSqlScriptBinding(99L, plan)))) {
            Fixture fixture = new Fixture(binding);
            fixture.execute();
            assertFalse(fixture.context.getNodeFuture("script").join());
            verifyNoInteractions(fixture.metadata);
        }
    }

    private static final class Fixture {
        final DbSwitchRuntime runtime = mock(DbSwitchRuntime.class);
        final MetaDataByJdbcServiceImpl metadata = mock(MetaDataByJdbcServiceImpl.class);
        final EtlNodeDef node = new EtlNodeDef();
        final EtlRunContext context;
        Fixture(Map<String, EtlSqlScriptBinding> bindings) {
            when(runtime.options()).thenReturn(cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions.defaults());
            node.setNo("script"); node.setName("script"); node.setToolCode("sql-script");
            node.setToolType(EtlNodeDef.TOOL_TYPE_SCRIPT);
            node.setNodeJson("{\"databaseId\":42,\"sql\":\"delete from example\"}");
            var connection = new EtlConnectionInfo(); connection.setDatabaseId(42L);
            connection.setProductType(ProductTypeEnum.POSTGRESQL); connection.setDriverProfileId("approved-profile");
            connection.setJdbcUrl("jdbc:test"); connection.setUsername("test"); connection.setPassword("");
            when(runtime.jdbcMetadata(ProductTypeEnum.POSTGRESQL, "approved-profile")).thenReturn(metadata);
            context = new EtlRunContext(EtlDag.build(List.of(node), List.of()), ignored -> connection,
                    new EtlRunListener() { }, new EtlVariableContext(Map.of()), runtime);
            context.setLimits(EtlRunOptions.builder().sqlScriptPlans(bindings).build());
        }
        void execute() { new SqlScriptExecutor(node, context).run(); }
    }
}
