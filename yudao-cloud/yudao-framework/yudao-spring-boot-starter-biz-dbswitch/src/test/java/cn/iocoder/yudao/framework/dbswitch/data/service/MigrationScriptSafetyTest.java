package cn.iocoder.yudao.framework.dbswitch.data.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.domain.MigrationTermination;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.handler.MigrationHandler;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import com.zaxxer.hikari.HikariDataSource;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ThreadFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class MigrationScriptSafetyTest {
    @Test void unknownFirstSourceDoesNotStartTheNextSourceBeyondReadOnlyTaskPlanning() throws Exception {
        var properties = properties();
        properties.getTarget().setTargetSchema("public"); properties.getTarget().setTargetTable("first_target");
        var second = new SourceDataSourceProperties(); second.setSourceProductType(ProductTypeEnum.MYSQL);
        second.setSourceType(1); second.setSourceSchema("public");
        second.setSourceTableNames(List.of("second_target")); second.setIncludeOrExclude(1);
        second.setBeforeSqlScripts("INSERT INTO fixture VALUES (2)");
        properties.setSource(List.of(properties.getSource().getFirst(), second));
        var target = new Pool(); var source = new Pool();
        var runtime = spy(cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime.standalone());
        var metadata = mock(cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByDataSourceServiceImpl.class);
        var reader = mock(cn.iocoder.yudao.framework.dbswitch.dbcommon.database.IDatabaseOperator.class);
        var table = new TableDescription(); table.setSchemaName("public"); table.setTableName("second_target"); table.setTableType("TABLE");
        when(metadata.queryTableList("public")).thenReturn(List.of(table));
        when(reader.planMigrationSources(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        doReturn(metadata).when(runtime).metadata(any(),eq(ProductTypeEnum.MYSQL));
        doReturn(reader).when(runtime).reader(any(),eq(ProductTypeEnum.MYSQL));
        var handler = mock(MigrationHandler.class);
        when(handler.get()).thenThrow(new cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException());
        var service = new MigrationService(properties, ignored -> { }, null, runtime);
        try (var factories = factories(target, source); var handlers = mockStatic(MigrationHandler.class)) {
            handlers.when(() -> MigrationHandler.createInstance(any(TableDescription.class), eq(properties),
                    anyInt(), any(), any(), isNull(), any(), isNull())).thenReturn(handler);
            var result = service.run();
            assertFalse(result.getIfAllSuccess().get());
            assertEquals(1, result.getTotalTableFailCount().get());
            assertEquals(MigrationTermination.UNCONFIRMED, result.getTermination());
            // Task-wide read-only target reservation still runs before the first write.
            factories.verify(() -> DataSourceUtils.createSourceDataSource(eq(second), any(ThreadFactory.class),
                    any(), any(), any(), any()), times(1));
            verify(handler, times(1)).get();
            verify(source.statement, never()).execute(anyString());
            assertTrue(service.scriptExecutions().isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void unknownTableWriteBlocksBothAfterScriptsButKnownFailureStillAllowsCleanup(boolean unknown) throws Exception {
        var properties = properties();
        properties.getSource().getFirst().setAfterSqlScripts("INSERT INTO fixture VALUES (1)");
        properties.getTarget().setAfterSqlScripts("INSERT INTO fixture VALUES (2)");
        var target = new Pool(); var source = new Pool();
        var handler = mock(MigrationHandler.class);
        when(handler.get()).thenThrow(unknown
                ? new cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException(
                    "fixture_label", cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException.ReceiptObservation.NO_RECEIPT)
                : new IllegalArgumentException("Known pre-submit rejection"));
        var service = new MigrationService(properties, ignored -> { });
        try (var factories = factories(target, source); var handlers = mockStatic(MigrationHandler.class)) {
            handlers.when(() -> MigrationHandler.createInstance(any(TableDescription.class), eq(properties),
                    eq(0), any(), any(), isNull(), any(), isNull())).thenReturn(handler);
            var result = service.run();
            assertFalse(result.getIfAllSuccess().get());
            assertEquals(1, result.getTotalTableFailCount().get());
            verify(source.statement, times(unknown ? 0 : 1)).execute(anyString());
            verify(target.statement, times(unknown ? 0 : 1)).execute(anyString());
            assertEquals(unknown ? 0 : 2, service.scriptExecutions().size());
            assertEquals(unknown ? MigrationTermination.UNCONFIRMED : MigrationTermination.CONFIRMED, result.getTermination());
        }
    }

    @Test void unknownSourceAfterScriptBlocksTargetAfterWithoutErasingCommittedTable() throws Exception {
        var properties = properties();
        properties.getSource().getFirst().setAfterSqlScripts("INSERT INTO fixture VALUES (1)");
        properties.getTarget().setAfterSqlScripts("INSERT INTO fixture VALUES (2)");
        var target = new Pool(); var source = new Pool();
        when(source.statement.execute(anyString())).thenThrow(new SQLException("Unconfirmed source script", "08006"));
        var committed = new DbSwitchTableResult(); committed.getSyncCount().set(23);
        var handler = mock(MigrationHandler.class); when(handler.get()).thenReturn(committed);
        var service = new MigrationService(properties, ignored -> { });
        try (var factories = factories(target, source); var handlers = mockStatic(MigrationHandler.class)) {
            handlers.when(() -> MigrationHandler.createInstance(any(TableDescription.class), eq(properties),
                    eq(0), any(), any(), isNull(), any(), isNull())).thenReturn(handler);
            var result = service.run();
            assertFalse(result.getIfAllSuccess().get());
            assertSame(committed, result.getTableResultList().getFirst());
            assertEquals(23, result.getTotalRowCount().get());
            assertEquals(MigrationTermination.UNCONFIRMED, result.getTermination());
            assertEquals(1, service.scriptExecutions().size());
            assertEquals("source-after", service.scriptExecutions().getFirst().phase());
            assertEquals(MigrationService.ScriptState.UNKNOWN, service.scriptExecutions().getFirst().state());
            verify(target.statement, never()).execute(anyString());
        }
    }

    @ParameterizedTest
    @CsvSource({
            "POSTGRESQL, execute, 57014, CANCELLED, CONFIRMED",
            "POSTGRESQL, execute, 08006, UNKNOWN, UNCONFIRMED",
            "MYSQL, execute, 57014, UNKNOWN, UNCONFIRMED",
            "POSTGRESQL, drain, 57014, UNKNOWN, UNCONFIRMED",
            "POSTGRESQL, close, 57014, CANCELLED, UNCONFIRMED",
            "POSTGRESQL, second, 57014, PARTIAL, CONFIRMED"
    })
    void onlyPostgresExecuteCancellationIsStatementRollbackEvidence(ProductTypeEnum product, String stage,
            String sqlState, MigrationService.ScriptState expectedState, MigrationTermination expectedTermination)
            throws Exception {
        var properties = properties();
        properties.getTarget().setTargetProductType(product);
        properties.getTarget().setBeforeSqlScripts("INSERT INTO fixture VALUES (1); INSERT INTO fixture VALUES (2)");
        var target = new Pool(); var source = new Pool();
        var failure = new SQLException("fixture_sensitive_error", sqlState);
        if (stage.equals("drain")) when(target.statement.getResultSet()).thenThrow(failure);
        else if (stage.equals("second")) when(target.statement.execute(anyString())).thenReturn(false).thenThrow(failure);
        else when(target.statement.execute(anyString())).thenThrow(failure);
        if (stage.equals("close")) doThrow(new SQLException("fixture_sensitive_close", "08006"))
                .when(target.statement).close();
        var service = new MigrationService(properties, ignored -> fail("No handler may finish"));
        try (var factories = factories(target, source); var handlers = mockStatic(MigrationHandler.class)) {
            var error = assertThrows(MigrationService.ScriptExecutionException.class, service::run);
            assertEquals(expectedState, error.execution().state());
            assertEquals(stage.equals("second") ? 1 : 0, error.execution().acknowledgedStatements());
            assertEquals(stage.equals("second") ? 2 : 1, error.execution().inFlightStatement());
            assertEquals(expectedTermination, service.termination());
            assertNull(error.getCause());
            assertFalse(error.toString().contains("fixture_sensitive"));
            handlers.verifyNoInteractions();
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void beforeFailureStopsHandlersAndPreservesAcknowledgedSteps(boolean targetBefore) throws Exception {
        var properties = properties();
        String script = "INSERT INTO fixture VALUES (1); INSERT INTO fixture VALUES (2); INSERT INTO fixture VALUES (3)";
        if (targetBefore) properties.getTarget().setBeforeSqlScripts(script);
        else properties.getSource().getFirst().setBeforeSqlScripts(script);
        var target = new Pool(); var source = new Pool();
        Pool scripted = targetBefore ? target : source;
        when(scripted.statement.execute(anyString())).thenReturn(false)
                .thenThrow(new SQLException("fixture_secret_token embedded by driver", "08006", 7));
        var service = new MigrationService(properties, ignored -> fail("No handler may finish"));
        try (var factories = factories(target, source); var handlers = mockStatic(MigrationHandler.class)) {
            var error = assertThrows(MigrationService.ScriptExecutionException.class, service::run);
            assertEquals(1, error.execution().acknowledgedStatements());
            assertEquals(2, error.execution().inFlightStatement());
            assertEquals(MigrationService.ScriptState.UNKNOWN, error.execution().state());
            assertEquals("08006", error.execution().sqlState());
            assertNull(error.getCause());
            assertFalse(error.toString().contains("fixture_secret_token"));
            assertEquals(MigrationTermination.UNCONFIRMED, service.termination());
            verify(scripted.statement, times(2)).execute(anyString());
            handlers.verifyNoInteractions();
            if (targetBefore) factories.verify(() -> DataSourceUtils.createSourceDataSource(any(), any(ThreadFactory.class), any(), any(), any(), any()), never());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void afterFailureMarksOverallFailureWithoutErasingCommittedTableResult(boolean targetAfter) throws Exception {
        var properties = properties();
        if (targetAfter) properties.getTarget().setAfterSqlScripts("INSERT INTO fixture VALUES (2)");
        else properties.getSource().getFirst().setAfterSqlScripts("INSERT INTO fixture VALUES (2)");
        var target = new Pool(); var source = new Pool();
        when((targetAfter ? target : source).statement.execute(anyString()))
                .thenThrow(new SQLException("fixture failure", "08006"));
        var committedTable = new DbSwitchTableResult(); committedTable.getSyncCount().set(23);
        var handler = mock(MigrationHandler.class); when(handler.get()).thenReturn(committedTable);
        var service = new MigrationService(properties, ignored -> { });
        try (var factories = factories(target, source); var handlers = mockStatic(MigrationHandler.class)) {
            handlers.when(() -> MigrationHandler.createInstance(any(TableDescription.class), eq(properties),
                    eq(0), any(), any(), isNull(), any(), isNull())).thenReturn(handler);
            var result = service.run();
            assertFalse(result.getIfAllSuccess().get());
            assertEquals(23, result.getTotalRowCount().get());
            assertEquals(1, result.getTotalTableSuccessCount().get());
            assertEquals(0, result.getTotalTableFailCount().get());
            assertSame(committedTable, result.getTableResultList().getFirst());
            assertEquals(MigrationTermination.UNCONFIRMED, result.getTermination());
            assertEquals(targetAfter ? "target-after" : "source-after", service.scriptExecutions().getFirst().phase());
        }
    }

    @Test void preflightRejectsEntireUnsupportedScriptWithoutExecutingItsValidPrefix() throws Exception {
        var properties = properties();
        properties.getTarget().setBeforeSqlScripts("INSERT INTO fixture VALUES (1); DO $$ BEGIN END $$;");
        var target = new Pool(); var source = new Pool();
        var service = new MigrationService(properties, ignored -> { });
        try (var factories = factories(target, source); var handlers = mockStatic(MigrationHandler.class)) {
            var error = assertThrows(MigrationService.ScriptExecutionException.class, service::run);
            assertEquals(MigrationService.ScriptState.REJECTED, error.execution().state());
            assertEquals(0, error.execution().acknowledgedStatements());
            verify(target.connection, never()).createStatement();
            handlers.verifyNoInteractions();
            assertEquals(MigrationTermination.CONFIRMED, service.termination());
        }
    }

    @Test void logsNeverContainSqlLiteralsOrVendorErrorMessages() throws Exception {
        var properties = properties();
        properties.getTarget().setBeforeSqlScripts("INSERT INTO fixture VALUES ('fixture_secret_token')");
        var target = new Pool(); var source = new Pool();
        when(target.statement.execute(anyString())).thenThrow(new SQLException("fixture_secret_token", "42000"));
        Logger logger = (Logger) LoggerFactory.getLogger(MigrationService.class);
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try (var factories = factories(target, source)) {
            assertThrows(MigrationService.ScriptExecutionException.class,
                    () -> new MigrationService(properties, ignored -> { }).run());
            assertTrue(appender.list.stream().anyMatch(event -> event.getFormattedMessage().contains("digest=")));
            assertTrue(appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains("fixture_secret_token")));
            assertTrue(appender.list.stream().allMatch(event -> event.getThrowableProxy() == null));
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    private static MigrationRequest properties() {
        var properties = new MigrationRequest();
        properties.getTarget().setTargetProductType(ProductTypeEnum.POSTGRESQL);
        var source = new SourceDataSourceProperties(); source.setSourceProductType(ProductTypeEnum.POSTGRESQL);
        source.setSourceType(2); source.setSourceSchema("public"); source.setSourceSql("SELECT 1");
        properties.setSource(List.of(source)); return properties;
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

    private static MockedStatic<DataSourceUtils> factories(Pool target, Pool source) {
        var factory = mockStatic(DataSourceUtils.class);
        factory.when(() -> DataSourceUtils.createTargetDataSource(any(), any(ThreadFactory.class), any(), any(), any(), any())).thenReturn(target.dataSource);
        factory.when(() -> DataSourceUtils.createSourceDataSource(any(), any(ThreadFactory.class), any(), any(), any(), any())).thenReturn(source.dataSource);
        return factory;
    }
}
