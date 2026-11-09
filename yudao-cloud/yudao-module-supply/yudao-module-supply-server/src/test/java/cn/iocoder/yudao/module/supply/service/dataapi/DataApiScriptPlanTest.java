package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.JdbcSelectResult;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedDmlTarget;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DataApiScriptPlanTest {
    @Test void malformedWriteIsRejectedBeforeDurableClaimOrTargetConnection() {
        for (String sql : List.of("update example set value=#{requiredValue}", "create table example(id int)")) {
            Fixture fixture = new Fixture();
            DataApiOperationService operations = mock(DataApiOperationService.class);
            ReflectionTestUtils.setField(fixture.executor, "operationService", operations);
            fixture.api.setApiType("UPDATE");
            fixture.api.setConfigType("SQL");
            fixture.api.setSqlText(sql);
            assertThrows(RuntimeException.class, () -> fixture.executor.execute(fixture.api,
                    Map.of("_operationKey", "permit-case-001"), 1, 10, "192.0.2.2", false, false,
                    null, false, DataApiExecuteMode.APP_GATEWAY));
            verifyNoInteractions(operations, fixture.metadata);
            verify(fixture.runtime, never()).jdbcMetadata(any());
        }
    }

    @Test void exactPlanAndOrderedParametersExecuteWithoutGovernanceRewrites() {
        Fixture fixture = new Fixture();
        when(fixture.metadata.executeAuthorizedDmlPlan(anyString(), anyString(), anyString(), any(), anyList(), anyList(), any(), anyInt()))
                .thenAnswer(call -> {
                    SqlScriptPlan plan = call.getArgument(3);
                    assertEquals(List.of("left;right", 7), call.getArgument(4));
                    assertEquals(2, plan.steps().size());
                    assertEquals(List.of(new RestrictedDmlTarget(1,"public","example"), new RestrictedDmlTarget(2,"public","example")),call.getArgument(5));
                    assertTrue(plan.steps().stream().allMatch(step -> step.sql().contains("\"public\".\"example\"")));
                    assertFalse(plan.steps().getLast().sql().contains("district_code"));
                    return success();
                });
        fixture.execute("insert into example(value,district_code) values (#{value},'370102'); update example set value='literal;value' where id=#{id};",
                Map.of("value", "left;right", "id", 7));
        verify(fixture.metadata).executeAuthorizedDmlPlan(anyString(), anyString(), anyString(), any(), anyList(), anyList(), any(), eq(30));
    }

    @Test void unsupportedLaterStatementPreventsAllExecution() {
        Fixture fixture = new Fixture();
        assertThrows(IllegalArgumentException.class, () -> fixture.execute(
                "delete from first_table; delete from example where id in (select id from another_table)", Map.of()));
        verifyNoInteractions(fixture.metadata);
        verify(fixture.runtime, never()).jdbcMetadata(any());
    }

    @Test void ddlQueryOrPlaceholderInsideLiteralFailsBeforeConnection() {
        for (String sql : List.of("create table example(id int)", "select 1", "insert into example(value) values ('#{value}')")) {
            Fixture fixture = new Fixture();
            assertThrows(RuntimeException.class, () -> fixture.execute(sql, Map.of("value", "v")));
            verifyNoInteractions(fixture.metadata);
            verify(fixture.runtime, never()).jdbcMetadata(any());
        }
    }

    @Test void singleWriteUsesBoundPlanAndProductDefaultDriver() {
        Fixture fixture = new Fixture();
        fixture.api.setApiType("update");
        fixture.api.setSqlText("update example set value=#{value} where id=7");
        when(fixture.metadata.executeAuthorizedDmlPlan(anyString(), anyString(), anyString(), any(), anyList(), anyList(), any(), anyInt()))
                .thenReturn(success());
        ReflectionTestUtils.invokeMethod(fixture.executor, "executeSql", fixture.api, Map.of("value", "left;right"),
                1, 10, new DataApiExecutor.ExecuteContext(7L, false));
        var planned = org.mockito.ArgumentCaptor.forClass(SqlScriptPlan.class);
        verify(fixture.metadata).executeAuthorizedDmlPlan(anyString(), anyString(), anyString(), planned.capture(), eq(List.of("left;right")), eq(List.of(new RestrictedDmlTarget(1,"public","example"))), any(), anyInt());
        assertTrue(planned.getValue().steps().getFirst().sql().contains("\"public\".\"example\""));
        assertFalse(planned.getValue().steps().getFirst().sql().contains("district_code"));
        verify(fixture.runtime).jdbcMetadata(ProductTypeEnum.POSTGRESQL);
    }

    @Test void decimalNullAndLosslessIdBindingsRemainExact() {
        Fixture fixture = new Fixture();
        when(fixture.metadata.executeAuthorizedDmlPlan(anyString(),anyString(),anyString(),any(),anyList(),anyList(),any(),anyInt())).thenReturn(success());
        var decimal = new java.math.BigDecimal("12345678901234567890.12345678901234567890");
        Map<String,Object> parameters = new java.util.LinkedHashMap<>(); parameters.put("amount",decimal);parameters.put("id",9007199254740993L);parameters.put("value",null);
        fixture.execute("UPDATE example SET amount=#{amount} WHERE id=#{id};UPDATE example SET value=#{value} WHERE id=#{id}",parameters);
        verify(fixture.metadata).executeAuthorizedDmlPlan(anyString(),anyString(),anyString(),any(),eq(java.util.Arrays.asList(decimal,9007199254740993L,null,9007199254740993L)),anyList(),any(),anyInt());
    }

    @Test void approximateNumbersAndStaleConfigurationNeverReachExecution() {
        Fixture approximate = new Fixture();
        assertThrows(RuntimeException.class,()->approximate.execute("UPDATE example SET amount=#{amount}",Map.of("amount",0.1d)));
        verifyNoInteractions(approximate.metadata);
        Fixture replaced = new Fixture();
        var changed = connection();changed.setConfigurationRevision("r2");
        when(replaced.resolver.resolve(42L)).thenReturn(replaced.connection,changed);
        assertThrows(RuntimeException.class,()->replaced.execute("DELETE FROM example WHERE id=#{id}",Map.of("id",1)));
        verifyNoInteractions(replaced.metadata);
    }

    @Test void crossSchemaAndExecutableExpressionsRemainRejectedBeforeConnection() {
        for (String script : List.of("DELETE FROM other.example", "UPDATE example SET value=pg_sleep(1)",
                "INSERT INTO example(value) SELECT value FROM another_table")) {
            Fixture fixture = new Fixture();
            assertThrows(RuntimeException.class, () -> fixture.execute(script, Map.of()));
            verifyNoInteractions(fixture.metadata);
        }
    }

    private static SupplyDatabaseResolver.ConnectionInfo connection() {
        var connection = new SupplyDatabaseResolver.ConnectionInfo();connection.setDatabaseId(42L);connection.setConfigurationRevision("r1");
        connection.setProductType(ProductTypeEnum.POSTGRESQL);connection.setSchema("public");
        connection.setJdbcUrl("jdbc:test");connection.setUsername("test");connection.setPassword("");return connection;
    }

    private static JdbcSelectResult success() {
        var result = new JdbcSelectResult(); result.setSuccess(true);
        result.setTransactionOutcome(JdbcSelectResult.TransactionOutcome.COMMITTED);
        var statement = new JdbcSelectResult(); statement.setCount(1);
        result.setResults(List.of(statement, statement)); return result;
    }
    private static final class Fixture {
        final DataApiExecutor executor = new DataApiExecutor();
        final DbSwitchRuntime runtime = mock(DbSwitchRuntime.class);
        final MetaDataByJdbcServiceImpl metadata = mock(MetaDataByJdbcServiceImpl.class);
        final DataApiDO api = DataApiDO.builder().databaseId(42L).build();
        final SupplyDatabaseResolver resolver = mock(SupplyDatabaseResolver.class);
        final SupplyDatabaseResolver.ConnectionInfo connection = connection();
        Fixture() {
            when(resolver.resolve(42L)).thenReturn(connection);
            when(runtime.jdbcMetadata(ProductTypeEnum.POSTGRESQL)).thenReturn(metadata);
            when(runtime.products()).thenReturn(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover());
            ReflectionTestUtils.setField(executor, "databaseResolver", resolver);
            ReflectionTestUtils.setField(executor, "dbSwitchRuntime", runtime);
            ReflectionTestUtils.setField(executor, "dataApiProperties",
                    new cn.iocoder.yudao.module.supply.framework.config.SupplyDataApiProperties());
        }
        void execute(String script, Map<String, Object> parameters) {
            ReflectionTestUtils.invokeMethod(executor, "executeMultiSql", api, script, parameters,
                    new DataApiExecutor.ExecuteContext(7L, false));
        }
    }
}
