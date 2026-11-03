package cn.iocoder.yudao.framework.dbswitch.data.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.handler.MigrationHandler;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ThreadFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** JDBC protocol coverage; product-specific SQL execution is verified separately on real engines. */
class MigrationHookExecutionTest {
    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, mode = EnumSource.Mode.EXCLUDE,
            names = {"UNKNOWN", "MONGODB", "ELASTICSEARCH", "INFLUXDB3"})
    void allJdbcProductsExecuteBothSourceAndTargetHooksThroughTheMigrationService(ProductTypeEnum product)
            throws Exception {
        var request = request(product);
        request.getSource().getFirst().setBeforeSqlScripts("INSERT INTO fixture VALUES (1)");
        request.getSource().getFirst().setAfterSqlScripts("INSERT INTO fixture VALUES (2)");
        request.getTarget().setBeforeSqlScripts("INSERT INTO fixture VALUES (3)");
        request.getTarget().setAfterSqlScripts("INSERT INTO fixture VALUES (4)");
        var target = new Pool();
        var source = new Pool();
        var handler = mock(MigrationHandler.class);
        when(handler.get()).thenReturn(new DbSwitchTableResult());
        try (var factories = mockStatic(DataSourceUtils.class); var handlers = mockStatic(MigrationHandler.class)) {
            factories.when(() -> DataSourceUtils.createTargetDataSource(any(), any(ThreadFactory.class),
                    any(), any(), any(), any())).thenReturn(target.dataSource);
            factories.when(() -> DataSourceUtils.createSourceDataSource(any(), any(ThreadFactory.class),
                    any(), any(), any(), any())).thenReturn(source.dataSource);
            handlers.when(() -> MigrationHandler.createInstance(any(), eq(request), eq(0), any(), any(),
                    isNull(), any(), isNull())).thenReturn(handler);
            var service = new MigrationService(request, ignored -> { });
            assertTrue(service.run().getIfAllSuccess().get());
            assertEquals(List.of("target-before", "source-before", "source-after", "target-after"),
                    service.scriptExecutions().stream().map(MigrationService.ScriptExecution::phase).toList());
            assertTrue(service.scriptExecutions().stream().allMatch(evidence ->
                    evidence.state() == MigrationService.ScriptState.SUCCEEDED
                            && evidence.statements() == 1 && evidence.acknowledgedStatements() == 1
                            && evidence.inFlightStatement() == 0));
            verify(target.statement, times(2)).execute(anyString());
            verify(source.statement, times(2)).execute(anyString());
            verify(target.statement, times(2)).setQueryTimeout(60);
            verify(source.statement, times(2)).setQueryTimeout(60);
            verify(target.connection, never()).setAutoCommit(anyBoolean());
            verify(source.connection, never()).setAutoCommit(anyBoolean());
        }
    }

    @Test
    void newlySupportedProductDoesNotAcknowledgeLostResultOrStartHandlers() throws Exception {
        var request = request(ProductTypeEnum.SQLSERVER);
        request.getTarget().setBeforeSqlScripts("INSERT INTO fixture VALUES (1)");
        var target = new Pool();
        when(target.statement.getMoreResults(Statement.CLOSE_ALL_RESULTS))
                .thenThrow(new SQLException("fixture_sensitive_driver_message", "08006"));
        try (var factories = mockStatic(DataSourceUtils.class); var handlers = mockStatic(MigrationHandler.class)) {
            factories.when(() -> DataSourceUtils.createTargetDataSource(any(), any(ThreadFactory.class),
                    any(), any(), any(), any())).thenReturn(target.dataSource);
            var service = new MigrationService(request, ignored -> fail("No table handler may finish"));
            var failure = assertThrows(MigrationService.ScriptExecutionException.class, service::run);
            assertEquals(MigrationService.ScriptState.UNKNOWN, failure.execution().state());
            assertEquals(0, failure.execution().acknowledgedStatements());
            assertEquals(1, failure.execution().inFlightStatement());
            assertNull(failure.getCause());
            assertFalse(failure.toString().contains("fixture_sensitive_driver_message"));
            handlers.verifyNoInteractions();
            verify(target.statement).close();
            verify(target.connection, atLeastOnce()).close();
        }
    }

    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"MONGODB", "ELASTICSEARCH", "INFLUXDB3"})
    void nativeProtocolProductsRejectSqlHooksBeforeOpeningAStatement(ProductTypeEnum product) throws Exception {
        var request = request(product);
        request.getTarget().setBeforeSqlScripts("INSERT INTO fixture VALUES (1)");
        var target = new Pool();
        try (var factories = mockStatic(DataSourceUtils.class); var handlers = mockStatic(MigrationHandler.class)) {
            factories.when(() -> DataSourceUtils.createTargetDataSource(any(), any(ThreadFactory.class),
                    any(), any(), any(), any())).thenReturn(target.dataSource);
            var failure = assertThrows(MigrationService.ScriptExecutionException.class,
                    () -> new MigrationService(request, ignored -> { }).run());
            assertEquals(MigrationService.ScriptState.REJECTED, failure.execution().state());
            assertEquals(0, failure.execution().inFlightStatement());
            verify(target.connection, never()).createStatement();
            handlers.verifyNoInteractions();
        }
    }

    @Test
    void analyzeCancellationRemainsUnknownAndBlocksLaterMigrationPhases() throws Exception {
        var request = request(ProductTypeEnum.POSTGRESQL);
        request.getTarget().setBeforeSqlScripts("ANALYZE fixture_schema.fixture");
        request.getTarget().setAfterSqlScripts("SELECT 1");
        var target = new Pool();
        when(target.statement.execute("ANALYZE fixture_schema.fixture"))
                .thenThrow(new SQLException("sensitive_cancel_reason", "57014"));
        try (var factories = mockStatic(DataSourceUtils.class); var handlers = mockStatic(MigrationHandler.class)) {
            factories.when(() -> DataSourceUtils.createTargetDataSource(any(), any(ThreadFactory.class),
                    any(), any(), any(), any())).thenReturn(target.dataSource);
            var service = new MigrationService(request, ignored -> fail("No table handler may finish"));
            var failure = assertThrows(MigrationService.ScriptExecutionException.class, service::run);
            assertEquals(MigrationService.ScriptState.UNKNOWN, failure.execution().state());
            assertEquals("57014", failure.execution().sqlState());
            assertEquals(0, failure.execution().acknowledgedStatements());
            assertEquals(List.of("target-before"), service.scriptExecutions().stream()
                    .map(MigrationService.ScriptExecution::phase).toList());
            assertNull(failure.getCause());
            assertFalse(failure.toString().contains("sensitive_cancel_reason"));
            handlers.verifyNoInteractions();
            verify(target.statement, never()).execute("SELECT 1");
        }
    }

    private static MigrationRequest request(ProductTypeEnum product) {
        var request = new MigrationRequest();
        request.getTarget().setTargetProductType(product);
        var source = new SourceDataSourceProperties();
        source.setSourceProductType(product);
        source.setSourceType(2);
        source.setSourceSchema("fixture_schema");
        source.setSourceSql("SELECT 1");
        request.setSource(List.of(source));
        return request;
    }

    private static final class Pool {
        final HikariDataSource dataSource = mock(HikariDataSource.class);
        final Connection connection = mock(Connection.class);
        final Statement statement = mock(Statement.class);

        Pool() throws SQLException {
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.getAutoCommit()).thenReturn(true);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.getUpdateCount()).thenReturn(-1);
        }
    }
}
