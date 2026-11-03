package cn.iocoder.yudao.framework.dbswitch.data.service;

import cn.iocoder.yudao.framework.dbswitch.common.entity.InvisibleDataSource;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.handler.MigrationHandler;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import cn.iocoder.yudao.framework.dbswitch.driver.DriverBundleSpec;
import cn.iocoder.yudao.framework.dbswitch.driver.DriverBundleVerifier;
import cn.iocoder.yudao.framework.dbswitch.driver.DriverPoolFactory;
import cn.iocoder.yudao.framework.dbswitch.driver.DriverRuntimeRegistry;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.SQLException;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ThreadFactory;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(15)
class MigrationTerminationTest {

    @ParameterizedTest
    @ValueSource(strings = {"direct", "cause", "suppressed"})
    void localCleanupCannotConfirmAnUnknownExternalWrite(String wrapping) throws Exception {
        var properties = properties();
        var source = new SourceDataSourceProperties();
        source.setSourceType(2);
        source.setSourceProductType(ProductTypeEnum.POSTGRESQL);
        source.setSourceSchema("public");
        source.setSourceSql("SELECT 1");
        properties.setSource(List.of(source));
        var callback = new AtomicReference<DbSwitchTableResult>();
        var service = new MigrationService(properties, callback::set);
        RuntimeException failure = new UnconfirmedWriteException();
        if ("cause".equals(wrapping)) failure = new IllegalStateException("writer failed", failure);
        if ("suppressed".equals(wrapping)) {
            var outer = new IllegalStateException("cleanup failed");
            outer.addSuppressed(failure);
            failure = outer;
        }
        try (var ignored = dataSources(); var handlers = mockStatic(MigrationHandler.class)) {
            var handler = mock(MigrationHandler.class);
            when(handler.get()).thenThrow(failure);
            handlers.when(() -> MigrationHandler.createInstance(any(TableDescription.class),
                    eq(properties), eq(0), any(), any(), isNull(), any(), isNull())).thenReturn(handler);
            var result = service.run();
            assertEquals(1, result.getTotalTableFailCount().get());
            assertNotNull(callback.get());
            assertFalse(callback.get().getIfSuccess().get());
            assertEquals("UNCONFIRMED", termination(service));
            assertEquals("UNCONFIRMED", result.getTermination().name());
        }
    }

    @Test
    void cancellationDuringOneScriptStatementPreventsLaterWrites() throws Exception {
        var properties = properties();
        properties.getTarget().setTargetProductType(ProductTypeEnum.POSTGRESQL);
        properties.getTarget().setBeforeSqlScripts("SELECT 'first; statement'; -- comment\nINSERT INTO target VALUES (1);");
        var service = new MigrationService(properties, ignored -> { });
        var executed = new ArrayList<String>();
        var statement = mock(Statement.class);
        when(statement.getUpdateCount()).thenReturn(-1);
        when(statement.execute(anyString())).thenAnswer(call -> {
            executed.add(call.getArgument(0));
            service.interrupt();
            return false;
        });
        var connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.createStatement()).thenReturn(statement);
        var target = mock(HikariDataSource.class);
        when(target.getConnection()).thenReturn(connection);
        try (var factory = mockStatic(DataSourceUtils.class)) {
            factory.when(() -> DataSourceUtils.createTargetDataSource(any(), any(ThreadFactory.class), any(), any(), any(), any())).thenReturn(target);
            try { service.run(); } catch (java.util.concurrent.CancellationException expected) { }
            assertEquals(List.of("SELECT 'first; statement'"), executed);
            assertEquals("CONFIRMED", termination(service));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void normalReturnConfirmsClosedResources() throws Exception {
        var service = new MigrationService(properties(), ignored -> { });
        try (var ignored = dataSources()) {
            DbSwitchResult result = service.run();
            assertEquals("CONFIRMED", termination(service));
            assertEquals("CONFIRMED", result.getClass().getMethod("getTermination").invoke(result).toString());
        }
    }

    @Test
    void failureStillExposesTerminationEvidence() throws Exception {
        var service = new MigrationService(properties(), ignored -> { });
        try (var factory = mockStatic(DataSourceUtils.class)) {
            factory.when(() -> DataSourceUtils.createTargetDataSource(any(), any(ThreadFactory.class), any(), any(), any(), any()))
                    .thenThrow(new IllegalStateException("test connection failure"));
            assertThrows(IllegalStateException.class, service::run);
            assertEquals("CONFIRMED", termination(service));
        }
    }

    @Test
    void cancellationBeforeStartNeverOpensAConnection() throws Exception {
        var service = new MigrationService(properties(), ignored -> { });
        service.interrupt();
        try (var factory = mockStatic(DataSourceUtils.class)) {
            assertThrows(java.util.concurrent.CancellationException.class, service::run);
            factory.verifyNoInteractions();
            assertEquals("CONFIRMED", termination(service));
        }
    }

    @Test
    void closeFailureMustNotClaimConfirmedTermination() throws Exception {
        var service = new MigrationService(properties(), ignored -> { });
        HikariDataSource target = mock(HikariDataSource.class);
        when(target.getConnection()).thenReturn(mock(Connection.class));
        doThrow(new IllegalStateException("test cleanup failure")).when(target).close();
        try (var factory = mockStatic(DataSourceUtils.class)) {
            factory.when(() -> DataSourceUtils.createTargetDataSource(any(), any(ThreadFactory.class), any(), any(), any(), any())).thenReturn(target);
            assertThrows(IllegalStateException.class, service::run);
            assertEquals("UNCONFIRMED", termination(service));
        }
    }

    @Test
    void interruptedCoordinatorCannotConfirmAnUncooperativeCallbackExited() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var handlerEntered = new CountDownLatch(1);
        var releaseHandler = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var properties = properties();
        var source = new SourceDataSourceProperties();
        source.setSourceType(2);
        source.setSourceProductType(ProductTypeEnum.POSTGRESQL);
        source.setSourceSchema("public");
        source.setSourceSql("SELECT 1");
        properties.setSource(List.of(source));
        var service = new MigrationService(properties, ignored -> {
            entered.countDown();
            try {
                while (release.getCount() != 0) {
                    try { release.await(); } catch (InterruptedException ignoredInterrupt) { }
                }
            } finally {
                exited.countDown();
            }
        });
        // Replace only the external database work; service scheduling and callbacks remain real.
        var coordinator = new Thread(() -> {
            try (var ignored = dataSources(); var handlers = mockStatic(MigrationHandler.class)) {
                var handler = mock(MigrationHandler.class);
                when(handler.get()).thenAnswer(call -> {
                    handlerEntered.countDown();
                    assertTrue(releaseHandler.await(5, SECONDS));
                    return new DbSwitchTableResult();
                });
                handlers.when(() -> MigrationHandler.createInstance(any(TableDescription.class),
                        eq(properties), eq(0), any(), any(), isNull(), any(), isNull())).thenReturn(handler);
                service.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "migration-termination-test");
        try {
            coordinator.start();
            assertTrue(handlerEntered.await(5, SECONDS));
            // CompletableFuture non-async callbacks can run on the registering thread.
            // Release only after the coordinator is waiting for the registered callback.
            org.awaitility.Awaitility.await().atMost(5, SECONDS).until(() ->
                    java.util.Arrays.stream(coordinator.getStackTrace()).anyMatch(frame ->
                            frame.getClassName().equals("java.util.concurrent.CompletableFuture")
                                    && frame.getMethodName().equals("get")));
            releaseHandler.countDown();
            assertTrue(entered.await(5, SECONDS));
            assertEquals("RUNNING", termination(service));
            service.interrupt();
            coordinator.join(5000);
            assertFalse(coordinator.isAlive());
            assertNotNull(failure.get());
            assertEquals(1, exited.getCount());
            assertEquals("UNCONFIRMED", termination(service));
        } finally {
            releaseHandler.countDown();
            release.countDown();
            coordinator.interrupt();
            coordinator.join(5000);
            assertTrue(exited.await(5, SECONDS));
        }
    }

    @Test
    void cleanupClearsInterruptWhileClosingAndRestoresItAfterwards() throws Exception {
        var service = new MigrationService(properties(), ignored -> { });
        var target = mock(HikariDataSource.class);
        var closeInterrupted = new AtomicReference<Boolean>();
        when(target.getConnection()).thenAnswer(call -> {
            Thread.currentThread().interrupt();
            return mock(Connection.class);
        });
        doAnswer(call -> {
            closeInterrupted.set(Thread.currentThread().isInterrupted());
            return null;
        }).when(target).close();
        try (var factory = mockStatic(DataSourceUtils.class)) {
            factory.when(() -> DataSourceUtils.createTargetDataSource(any(), any(ThreadFactory.class), any(), any(), any(), any())).thenReturn(target);
            assertThrows(java.util.concurrent.CancellationException.class, service::run);
            assertEquals(false, closeInterrupted.get());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals("CONFIRMED", termination(service));
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @CsvSource({"true,false,UNCONFIRMED", "false,false,CONFIRMED", "false,true,UNCONFIRMED"})
    @Timeout(30)
    void hikariCloseTimeoutIsNotPhysicalConnectionExitEvidence(boolean blockedClose, boolean closeFailure,
                                                               String expectedTermination) throws Exception {
        var closeEntered = new CountDownLatch(1);
        var releaseClose = new CountDownLatch(blockedClose ? 1 : 0);
        var closed = new AtomicBoolean();
        var physical = mock(Connection.class);
        when(physical.isValid(anyInt())).thenReturn(true);
        when(physical.isClosed()).thenAnswer(call -> closed.get());
        doAnswer(call -> {
            closeEntered.countDown();
            while (releaseClose.getCount() != 0) {
                try { releaseClose.await(); } catch (InterruptedException ignored) { }
            }
            if (closeFailure) {
                throw new SQLException("physical connection did not close");
            }
            closed.set(true);
            return null;
        }).when(physical).close();
        var database = mock(javax.sql.DataSource.class);
        when(database.getConnection()).thenReturn(physical);
        var service = new MigrationService(properties(), ignored -> { });
        try (var factory = mockStatic(DataSourceUtils.class, call -> {
            if (!call.getMethod().getName().equals("createTargetDataSource")) {
                return call.callRealMethod();
            }
            var config = new HikariConfig();
            config.setDataSource(database);
            config.setMinimumIdle(0);
            config.setMaximumPoolSize(1);
            config.setConnectionTimeout(250);
            config.setValidationTimeout(250);
            config.setInitializationFailTimeout(-1);
            if (call.getArguments().length >= 2) {
                config.setThreadFactory((ThreadFactory) call.getArgument(1));
            }
            if (call.getArguments().length >= 3) {
                // Use the real datasource adapter; only the physical database is replaced.
                var createPool = DataSourceUtils.class.getDeclaredMethod("createPool", HikariConfig.class,
                        java.util.function.Consumer.class);
                createPool.setAccessible(true);
                return createPool.invoke(null, config, call.getArgument(2));
            }
            return new HikariDataSource(config);
        })) {
            service.run();
            assertEquals(0, closeEntered.getCount());
            assertEquals(!blockedClose && !closeFailure, closed.get());
            assertEquals(expectedTermination, termination(service));
        } finally {
            releaseClose.countDown();
            if (!closeFailure) {
                org.awaitility.Awaitility.await().atMost(5, SECONDS).untilTrue(closed);
            }
        }
    }

    @Test
    void physicalExitWaitsForAnUncooperativeWorkerWithoutRewritingTermination() throws Exception {
        var handlerEntered = new CountDownLatch(1);
        var releaseHandler = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var properties = sqlSourceProperties();
        var service = new MigrationService(properties, ignored -> {
            entered.countDown();
            try {
                while (release.getCount() != 0) {
                    try { release.await(); } catch (InterruptedException ignoredInterrupt) { }
                }
            } finally {
                exited.countDown();
            }
        });
        var coordinator = new Thread(() -> {
            try (var ignored = dataSources(); var receipts = ownedReceipts(); var handlers = mockStatic(MigrationHandler.class)) {
                var handler = mock(MigrationHandler.class);
                when(handler.get()).thenAnswer(call -> {
                    handlerEntered.countDown();
                    assertTrue(releaseHandler.await(5, SECONDS));
                    return new DbSwitchTableResult();
                });
                handlers.when(() -> MigrationHandler.createInstance(any(TableDescription.class),
                        eq(properties), eq(0), any(), any(), isNull(), any(), isNull())).thenReturn(handler);
                service.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "migration-physical-exit-test");
        try {
            coordinator.start();
            assertTrue(handlerEntered.await(5, SECONDS));
            org.awaitility.Awaitility.await().atMost(5, SECONDS).until(() ->
                    java.util.Arrays.stream(coordinator.getStackTrace()).anyMatch(frame ->
                            frame.getClassName().equals("java.util.concurrent.CompletableFuture")
                                    && frame.getMethodName().equals("get")));
            releaseHandler.countDown();
            assertTrue(entered.await(5, SECONDS));
            service.interrupt();
            coordinator.join(5000);
            assertFalse(coordinator.isAlive());
            assertNotNull(failure.get());
            assertEquals("UNCONFIRMED", termination(service));
            var exit = physicalExit(service);
            assertFalse(exit.isDone(), "run() returned and pools closed, but a table worker is still executing");
            release.countDown();
            exit.get(5, SECONDS);
            assertEquals("UNCONFIRMED", termination(service), "the cached conservative termination is never rewritten");
        } finally {
            releaseHandler.countDown();
            release.countDown();
            coordinator.interrupt();
            coordinator.join(5000);
            assertTrue(exited.await(5, SECONDS));
        }
    }

    @Test
    void pipelineWriterOutlivingItsTableWorkerDelaysPhysicalExitUntilItsExitEvent() throws Exception {
        var active = new AtomicBoolean(true);
        var observer = new AtomicReference<Runnable>();
        var properties = sqlSourceProperties();
        var service = new MigrationService(properties, ignored -> { });
        var handler = mock(MigrationHandler.class, withSettings().defaultAnswer(call -> switch (call.getMethod().getName()) {
            case "hasActiveWriters" -> active.get();
            case "observeWriterExit" -> { observer.set(call.getArgument(0)); yield null; }
            case "get" -> new DbSwitchTableResult();
            default -> RETURNS_DEFAULTS.answer(call);
        }));
        try (var ignored = dataSources(); var receipts = ownedReceipts(); var handlers = mockStatic(MigrationHandler.class)) {
            handlers.when(() -> MigrationHandler.createInstance(any(TableDescription.class),
                    eq(properties), eq(0), any(), any(), isNull(), any(), isNull())).thenReturn(handler);
            service.run();
            assertEquals("UNCONFIRMED", termination(service));
            var exit = physicalExit(service);
            assertFalse(exit.isDone(), "a cancelled writer may still be writing after its table worker returned");
            active.set(false);
            assertNotNull(observer.get(), "the engine must observe its writers' actual exit");
            observer.get().run();
            assertTrue(exit.isDone() && !exit.isCompletedExceptionally());
            assertEquals("UNCONFIRMED", termination(service));
        }
    }

    @Test
    @Timeout(30)
    void lateConnectionCreationKeepsPhysicalExitPendingAfterThePoolExitWaitGaveUp(@TempDir Path directory) throws Exception {
        // A retiring isolated runtime must not be this JVM's first DriverManager caller: its context loader would hide
        // application drivers from later tests.
        java.sql.DriverManager.getDrivers();
        var service = new MigrationService(properties(), ignored -> { });
        try (var registry = new DriverRuntimeRegistry()) {
            var lease = registry.acquire(DriverBundleVerifier.verify(ProductTypeEnum.HIVE, lateDriver(directory)));
            Class<?> driver = lease.classLoader().loadClass("fixture.late.LateDriver");
            var allowConnect = (CountDownLatch) driver.getField("allowConnect").get(null);
            ((AtomicBoolean) driver.getField("blockConnect").get(null)).set(true);
            try (var factory = mockStatic(DataSourceUtils.class, call -> {
                if (!call.getMethod().getName().equals("createTargetDataSource")) return call.callRealMethod();
                var config = new HikariConfig();
                config.setDataSource(new InvisibleDataSource(lease, "jdbc:exit:fixture", null, null, new java.util.Properties()));
                config.setMinimumIdle(0);
                config.setMaximumPoolSize(1);
                config.setConnectionTimeout(250);
                config.setValidationTimeout(250);
                config.setInitializationFailTimeout(-1);
                config.setThreadFactory((ThreadFactory) call.getArgument(1));
                return DriverPoolFactory.createConfigured(config, call.getArgument(2));
            })) {
                assertThrows(SQLException.class, service::run, "the connectivity probe gives up while the physical creation is blocked");
                assertTrue(((CountDownLatch) driver.getField("connectEntered").get(null)).await(1, SECONDS));
                assertEquals("UNCONFIRMED", termination(service));
                var exit = physicalExit(service);
                assertFalse(exit.isDone(), "Hikari close and the one-second exit wait do not prove the late creation exited");
                allowConnect.countDown();
                exit.get(5, SECONDS);
                assertEquals("UNCONFIRMED", termination(service), "the cached conservative termination is never rewritten");
            } finally {
                allowConnect.countDown();
            }
        }
    }

    @Test
    void poolWithoutControlledDriverEvidenceCannotConfirmPhysicalExit() throws Exception {
        var service = new MigrationService(properties(), ignored -> { });
        try (var ignored = dataSources()) {
            service.run();
            assertEquals("CONFIRMED", termination(service));
            assertTrue(physicalExit(service).isCompletedExceptionally());
        }
    }

    @Test
    void callersCannotCompletePhysicalExitAndAnInterruptedUnstartedServiceHasNothingToExit() throws Exception {
        var service = new MigrationService(properties(), ignored -> { });
        var exit = physicalExit(service);
        assertFalse(exit.isDone(), "an unstarted service may still start");
        exit.complete(null);
        assertFalse(physicalExit(service).isDone(), "only the engine's own exit evidence can finish the receipt");
        service.interrupt();
        var sealed = physicalExit(service);
        assertTrue(sealed.isDone() && !sealed.isCompletedExceptionally());
        try (var factory = mockStatic(DataSourceUtils.class)) {
            assertThrows(java.util.concurrent.CancellationException.class, service::run);
            factory.verifyNoInteractions();
        }
    }

    private static MigrationRequest sqlSourceProperties() {
        var properties = properties();
        var source = new SourceDataSourceProperties();
        source.setSourceType(2);
        source.setSourceProductType(ProductTypeEnum.POSTGRESQL);
        source.setSourceSchema("public");
        source.setSourceSql("SELECT 1");
        properties.setSource(List.of(source));
        return properties;
    }

    // Reflection lets the original RED execute as a missing-contract assertion, not a compilation error.
    private static java.util.concurrent.CompletableFuture<?> physicalExit(MigrationService service) throws Exception {
        var method = assertDoesNotThrow(() -> MigrationService.class.getMethod("physicalExit"));
        return ((java.util.concurrent.CompletionStage<?>) method.invoke(service)).toCompletableFuture();
    }

    /** Mock pools stand in for owned driver pools whose physical receipts have already completed. */
    private static MockedStatic<DriverPoolFactory> ownedReceipts() {
        var receipts = mockStatic(DriverPoolFactory.class);
        receipts.when(() -> DriverPoolFactory.physicalTermination(any()))
                .thenReturn(java.util.concurrent.CompletableFuture.<Void>completedFuture(null).minimalCompletionStage());
        return receipts;
    }

    /** A real controlled JDBC driver bundle; only the physical connect timing is scripted. */
    private static DriverBundleSpec lateDriver(Path directory) throws Exception {
        Path source = Files.createDirectories(directory.resolve("source/fixture/late"));
        Path classes = Files.createDirectories(directory.resolve("classes"));
        Files.writeString(source.resolve("LateDriver.java"), LATE_DRIVER);
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(),
                source.resolve("LateDriver.java").toString()));
        Path jar = directory.resolve("late-driver.jar");
        try (var output = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new java.util.jar.JarEntry("fixture/late/LateDriver.class"));
            Files.copy(classes.resolve("fixture/late/LateDriver.class"), output);
            output.closeEntry();
        }
        String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        return new DriverBundleSpec() {
            public String getPath() { return jar.toString(); }
            public String getSha256() { return sha; }
            public String getDriverClassName() { return "fixture.late.LateDriver"; }
            public String getBundleRoot() { return null; }
            public List<? extends Artifact> getArtifacts() { return List.of(); }
            public Lifecycle getLifecycle() { return Lifecycle.LEASED; }
        };
    }

    private static final String LATE_DRIVER = """
            package fixture.late;
            import java.sql.*; import java.util.*; import java.util.concurrent.*; import java.util.concurrent.atomic.*;
            import java.lang.reflect.Proxy; import java.util.logging.Logger;
            public final class LateDriver implements Driver {
                public static final AtomicBoolean blockConnect=new AtomicBoolean();
                public static final CountDownLatch connectEntered=new CountDownLatch(1), allowConnect=new CountDownLatch(1);
                public Connection connect(String url, Properties p) {
                    connectEntered.countDown();
                    if(blockConnect.get()) while(allowConnect.getCount()!=0) try { allowConnect.await(); } catch(InterruptedException ignored) { }
                    var closed=new AtomicBoolean();
                    return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
                        switch(method.getName()) {
                            case "close": closed.set(true); return null;
                            case "isClosed": return closed.get();
                            case "isValid": return !closed.get();
                            case "getAutoCommit": return true;
                            case "getTransactionIsolation": return Connection.TRANSACTION_READ_COMMITTED;
                            case "unwrap": if(((Class<?>)args[0]).isInstance(proxy))return proxy; throw new SQLException("unsupported");
                            case "isWrapperFor": return ((Class<?>)args[0]).isInstance(proxy);
                            case "toString": return "LateFixtureConnection";
                        }
                        if(method.getReturnType()==boolean.class)return false;
                        if(method.getReturnType()==int.class)return 0;
                        if(method.getReturnType()==long.class)return 0L;
                        return null;
                    });
                }
                public boolean acceptsURL(String url){return url.startsWith("jdbc:exit:");}
                public DriverPropertyInfo[] getPropertyInfo(String u,Properties p){return new DriverPropertyInfo[0];}
                public int getMajorVersion(){return 1;} public int getMinorVersion(){return 0;}
                public boolean jdbcCompliant(){return false;} public Logger getParentLogger(){return Logger.getGlobal();}
            }
            """;

    private static MigrationRequest properties() {
        var properties = new MigrationRequest();
        properties.getTarget().setTargetProductType(ProductTypeEnum.POSTGRESQL);
        return properties;
    }

    // Reflection lets the original RED execute as a missing-contract assertion, not a compilation error.
    private static String termination(MigrationService service) throws Exception {
        var method = assertDoesNotThrow(() -> MigrationService.class.getMethod("termination"));
        return method.invoke(service).toString();
    }

    private static MockedStatic<DataSourceUtils> dataSources() throws Exception {
        var target = mock(HikariDataSource.class);
        var source = mock(HikariDataSource.class);
        when(target.getConnection()).thenReturn(mock(Connection.class));
        when(source.getConnection()).thenReturn(mock(Connection.class));
        var factory = mockStatic(DataSourceUtils.class);
        factory.when(() -> DataSourceUtils.createTargetDataSource(any(), any(ThreadFactory.class), any(), any(), any(), any())).thenReturn(target);
        factory.when(() -> DataSourceUtils.createSourceDataSource(any(), any(ThreadFactory.class), any(), any(), any(), any())).thenReturn(source);
        return factory;
    }
}
