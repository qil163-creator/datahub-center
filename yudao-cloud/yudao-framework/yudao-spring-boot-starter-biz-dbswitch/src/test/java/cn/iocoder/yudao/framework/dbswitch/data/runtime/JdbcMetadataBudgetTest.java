package cn.iocoder.yudao.framework.dbswitch.data.runtime;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.driver.ConnectionBudget;
import cn.iocoder.yudao.framework.dbswitch.driver.DriverConnections;
import cn.iocoder.yudao.framework.dbswitch.driver.JdbcConnectionProvider;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JdbcMetadataBudgetTest {
    private static final ProductTypeEnum PRODUCT = ProductTypeEnum.POSTGRESQL;
    private static final String URL = "jdbc:fixture:metadata-budget";

    @ParameterizedTest
    @ValueSource(strings = {"sqlColumns", "tableData", "sqlData", "schemaSqlData", "page", "plan", "script",
            "testSql", "executeSql", "boundSql", "tableMax", "sqlMax", "tableExists", "schemaTableExists", "repeat", "drop"})
    void dataAndExecutionMethodsNeverUseTheMetadataProvider(String method) throws Exception {
        var metadata = mock(JdbcConnectionProvider.class); var workload = mock(JdbcConnectionProvider.class);
        var products = mock(DbSwitchProductRegistry.class);
        when(products.metadata(PRODUCT)).thenReturn(mock(AbstractDatabase.class));
        when(workload.open(URL, null, null)).thenThrow(new SQLTimeoutException("Workload budget exhausted"));
        var facade = new MetaDataByJdbcServiceImpl(PRODUCT, products, metadata, workload);
        try {
            switch (method) {
                case "sqlColumns" -> facade.querySqlColumnMeta(URL, null, null, "SELECT 1");
                case "tableData" -> facade.queryTableData(URL, null, null, "public", "fixture", 10);
                case "sqlData" -> facade.queryTableDataBySql(URL, null, null, "SELECT 1", 10);
                case "schemaSqlData" -> facade.queryTableDataBySql(URL, null, null, "public", "SELECT 1", 10);
                case "page" -> facade.pageDataBySql(URL, null, null, "SELECT 1", List.of(), 1, 10, null);
                case "plan" -> facade.executePlan(URL, null, null, SqlScriptPlan.parse(PRODUCT, "SELECT 1"), List.of(), 0, 10, null, ignored -> { });
                case "script" -> facade.executeBySql(URL, "POSTGRESQL", null, null, "SELECT 1", 0, 10, null);
                case "testSql" -> facade.testQuerySQL(URL, null, null, "SELECT 1");
                case "executeSql" -> facade.executeSql(URL, null, null, "SELECT 1");
                case "boundSql" -> facade.executeSql(URL, null, null, "SELECT ?", List.of(1));
                case "tableMax" -> facade.queryMaxVal(URL, null, null, "public", "fixture", "id");
                case "sqlMax" -> facade.queryMaxVal(URL, null, null, "SELECT id FROM fixture", "id");
                case "tableExists" -> assertFalse(facade.tableExist(URL, null, null, "fixture"));
                case "schemaTableExists" -> assertFalse(facade.tableExist(URL, null, null, "public", "fixture"));
                case "repeat" -> facade.checkRepeat(URL, null, null, "public", "fixture", "id", 1);
                case "drop" -> facade.dropTable(URL, null, null, "public", "fixture");
                default -> fail("Unknown method");
            }
        } catch (RuntimeException expectedBudgetFailure) { /* Existing facade exception contracts differ. */ }
        verify(workload).open(URL, null, null);
        verifyNoInteractions(metadata);
    }

    @Test void runtimeExecutionCannotConsumeTheReservationWhenWorkloadIsFull() throws Exception {
        var quota = new ConnectionBudget(2, 1);
        var products = mock(DbSwitchProductRegistry.class); var database = mock(AbstractDatabase.class);
        when(products.metadata(PRODUCT)).thenReturn(database);
        try (var drivers = new ConfiguredJdbcDrivers(Map.of(), Map.of(), quota);
             var runtime = new DbSwitchRuntime(products, DbSwitchOptions.defaults(), drivers);
             var bridge = mockStatic(DriverConnections.class);
             var occupied = quota.acquire(ConnectionBudget.Role.WORKLOAD, Duration.ofMillis(20))) {
            occupied.connected();
            bridge.when(() -> DriverConnections.open(eq(PRODUCT), isNull(), same(drivers.runtimes()),
                    eq(URL), isNull(), isNull(), any(ConnectionBudget.Role.class))).thenAnswer(call -> {
                var permit = quota.acquire(call.getArgument(6), Duration.ofMillis(20));
                permit.connected();
                var connection = mock(Connection.class);
                doAnswer(close -> { permit.close(); return null; }).when(connection).close();
                return connection;
            });
            when(database.querySchemaList(any())).thenAnswer(call -> {
                assertEquals(1, quota.snapshot().metadata()); assertEquals(1, quota.snapshot().workload());
                return List.of("public");
            });
            var facade = runtime.jdbcMetadata(PRODUCT);
            var denied = assertThrows(RuntimeException.class, () -> facade.executeSql(URL, null, null, "SELECT 1"));
            assertInstanceOf(SQLTimeoutException.class, denied.getCause());
            assertEquals(List.of("public"), facade.querySchemaList(URL, null, null));
            assertEquals(0, quota.snapshot().metadata()); assertEquals(1, quota.snapshot().workload());
            verify(database, never()).executeSql(any(), anyString());
        }
        assertEquals(0, quota.snapshot().active());
    }

    @Test void retainedFacadeCannotOpenAfterRuntimeCloseButBorrowedConnectionCanDrain() throws Exception {
        var products = mock(DbSwitchProductRegistry.class); when(products.metadata(PRODUCT)).thenReturn(mock(AbstractDatabase.class));
        try (var drivers = ConfiguredJdbcDrivers.empty();
             var runtime = new DbSwitchRuntime(products, DbSwitchOptions.defaults(), drivers);
             var bridge = mockStatic(DriverConnections.class)) {
            var connection = mock(Connection.class);
            bridge.when(() -> DriverConnections.open(eq(PRODUCT), isNull(), same(drivers.runtimes()),
                    eq(URL), isNull(), isNull(), eq(ConnectionBudget.Role.WORKLOAD))).thenReturn(connection);
            var facade = runtime.jdbcMetadata(PRODUCT);
            var borrowed = runtime.openConnection(PRODUCT, null, URL, null, null);
            runtime.close();
            verify(connection, never()).close();
            bridge.clearInvocations();
            assertThrows(IllegalStateException.class, () -> facade.querySchemaList(URL, null, null));
            assertThrows(IllegalStateException.class, () -> facade.executeSql(URL, null, null, "SELECT 1"));
            assertThrows(IllegalStateException.class, () -> runtime.jdbcMetadata(PRODUCT));
            bridge.verifyNoInteractions();
            borrowed.close(); verify(connection).close();
        }
    }

    @ParameterizedTest @EnumSource(ConnectionBudget.Role.class)
    void connectionThatArrivesAfterCloseIsCleanedInsteadOfExposed(ConnectionBudget.Role role) throws Exception {
        var products = mock(DbSwitchProductRegistry.class); var database = mock(AbstractDatabase.class);
        when(products.metadata(PRODUCT)).thenReturn(database);
        try (var drivers = ConfiguredJdbcDrivers.empty();
             var runtime = new DbSwitchRuntime(products, DbSwitchOptions.defaults(), drivers);
             var bridge = mockStatic(DriverConnections.class)) {
            var connection = mock(Connection.class);
            bridge.when(() -> DriverConnections.open(eq(PRODUCT), isNull(), same(drivers.runtimes()),
                    eq(URL), isNull(), isNull(), eq(role))).thenAnswer(call -> { runtime.close(); return connection; });
            var facade = runtime.jdbcMetadata(PRODUCT);
            var failure = assertThrows(RuntimeException.class, () -> {
                if (role == ConnectionBudget.Role.METADATA) facade.querySchemaList(URL, null, null);
                else facade.executeSql(URL, null, null, "SELECT 1");
            });
            assertInstanceOf(SQLException.class, failure.getCause());
            verify(connection).close(); verifyNoInteractions(database);
        }
    }
}
