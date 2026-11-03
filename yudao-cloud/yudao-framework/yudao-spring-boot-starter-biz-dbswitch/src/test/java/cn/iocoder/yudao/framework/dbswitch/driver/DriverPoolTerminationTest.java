package cn.iocoder.yudao.framework.dbswitch.driver;

import cn.iocoder.yudao.framework.dbswitch.common.entity.InvisibleDataSource;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

/** The pool shutdown flag is deliberately separate from physical exit. No database timing assumptions. */
@Timeout(20)
class DriverPoolTerminationTest {
    @TempDir Path directory;

    @Test void realSqlitePoolReceiptRetainsObserverAndCannotBeCompletedByItsCaller() throws Exception {
        var observed = new AtomicInteger();
        try (var runtimes = new DriverRuntimeRegistry()) {
            var options = new JdbcPoolOptions(ProductTypeEnum.SQLITE3, "jdbc:sqlite:" + directory.resolve("receipt.db"),
                    null, null, null, new Properties(), 1, 30_000, 1000, 1000, 10_000, 0, 0, null,
                    Thread.ofPlatform().daemon(true).factory(), c -> observed.incrementAndGet());
            var pool = DriverPoolFactory.create(options, null, runtimes);
            var receipt = DriverPoolFactory.physicalTermination(pool);
            try {
                assertFalse(receipt.toCompletableFuture().isDone());
                receipt.toCompletableFuture().complete(null);
                receipt.toCompletableFuture().cancel(false);
                assertFalse(receipt.toCompletableFuture().isDone(), "Only owned physical evidence can finish the receipt");
                try (var connection = pool.getConnection(); var statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE sample (id INTEGER PRIMARY KEY)");
                }
                assertEquals(1, observed.get());
                assertFalse(receipt.toCompletableFuture().isDone(), "Returning a logical connection leaves its physical pool connection open");
            } finally { pool.close(); }
            receipt.toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(0, runtimes.connections().snapshot().workload());
            assertEquals(0, runtimes.statuses().getFirst().leases());
            pool.close();
            assertTrue(DriverPoolFactory.physicalTermination(pool).toCompletableFuture().isDone());
        }
    }

    @Test void unknownAndExternallyConfiguredPoolsCannotInventAnExitReceipt() throws Exception {
        try (var pool = new HikariDataSource()) {
            assertThrows(SQLFeatureNotSupportedException.class, () -> DriverPoolFactory.physicalTermination(pool));
        }
        DataSource external = org.mockito.Mockito.mock(DataSource.class);
        var config = config(external);
        try (var pool = DriverPoolFactory.createConfigured(config, null)) {
            assertThrows(SQLFeatureNotSupportedException.class, () -> DriverPoolFactory.physicalTermination(pool));
        }
        assertThrows(SQLFeatureNotSupportedException.class, () -> DriverPoolFactory.physicalTermination(null));
    }

    @Test void closingAnUnusedOwnedPoolConfirmsNoPhysicalConnectionCanArriveLater() throws Exception {
        try (var fixture = fixture(ProductTypeEnum.HIVE)) {
            var receipt = DriverPoolFactory.physicalTermination(fixture.pool);
            fixture.pool.close();
            receipt.toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertThrows(SQLException.class, fixture.source::getConnection);
            assertEquals(0, fixture.registry.connections().snapshot().workload());
        }
    }

    @Test void lateConnectionCreationAndItsCloseMustBothExitAfterPoolShutdown() throws Exception {
        try (var fixture = fixture(ProductTypeEnum.HIVE)) {
            fixture.flag("blockConnect").set(true); fixture.flag("blockClose").set(true);
            var worker = Executors.newSingleThreadExecutor();
            var receipt = DriverPoolFactory.physicalTermination(fixture.pool);
            Future<Connection> connecting = worker.submit((Callable<Connection>) fixture.source::getConnection);
            try {
                assertTrue(fixture.signal("connectEntered").await(3, TimeUnit.SECONDS));
                fixture.pool.close();
                assertTrue(fixture.pool.isClosed());
                assertFalse(receipt.toCompletableFuture().isDone(), "No observer has seen the still-creating connection");
                assertEquals(1, fixture.registry.connections().snapshot().creating());
                fixture.signal("allowConnect").countDown();
                assertTrue(fixture.signal("closeEntered").await(3, TimeUnit.SECONDS));
                assertFalse(receipt.toCompletableFuture().isDone(), "A late-created connection must also finish closing");
                fixture.signal("allowClose").countDown();
                var rejected = assertThrows(ExecutionException.class, () -> connecting.get(3, TimeUnit.SECONDS));
                assertInstanceOf(SQLException.class, rejected.getCause());
                receipt.toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(0, fixture.registry.connections().snapshot().workload());
            } finally {
                fixture.signal("allowConnect").countDown(); fixture.signal("allowClose").countDown();
                worker.shutdownNow(); assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
            }
        }
    }

    @Test void unconfirmedPhysicalCloseKeepsReceiptPendingDespiteClosedHikari() throws Exception {
        try (var fixture = fixture(ProductTypeEnum.HIVE)) {
            Connection physical = fixture.source.getConnection();
            var receipt = DriverPoolFactory.physicalTermination(fixture.pool);
            try {
                fixture.flag("failClose").set(true);
                assertThrows(SQLException.class, physical::close);
                fixture.pool.close();
                assertTrue(fixture.pool.isClosed());
                assertFalse(receipt.toCompletableFuture().isDone());
                assertEquals(1, fixture.registry.connections().snapshot().quarantined());
            } finally { fixture.flag("failClose").set(false); physical.close(); }
            receipt.toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(0, fixture.registry.connections().snapshot().workload());
        }
    }

    @Test void nativeDeadlineDoesNotConfirmExitWhileAnInterruptedWorkerContinues() throws Exception {
        try (var fixture = fixture(ProductTypeEnum.MONGODB)) {
            Connection physical = fixture.source.getConnection();
            var receipt = DriverPoolFactory.physicalTermination(fixture.pool);
            var entered = new CountDownLatch(1); var finish = new CountDownLatch(1);
            try {
                assertThrows(SQLTimeoutException.class, () -> DriverNativeAccess.invoke(physical, 150, () -> {
                    entered.countDown();
                    while (finish.getCount() != 0) try { finish.await(); } catch (InterruptedException ignored) { }
                    return null;
                }));
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertTrue(fixture.signal("closeEntered").await(3, TimeUnit.SECONDS));
                fixture.pool.close();
                assertFalse(receipt.toCompletableFuture().isDone(), "A cancelled future and closed socket do not prove native worker exit");
                assertEquals(1, fixture.registry.connections().snapshot().quarantined());
            } finally { finish.countDown(); }
            receipt.toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(0, fixture.registry.connections().snapshot().workload());
        }
    }

    private Fixture fixture(ProductTypeEnum product) throws Exception {
        Path root = Files.createDirectories(directory.resolve(UUID.randomUUID().toString()));
        Path source = Files.createDirectories(root.resolve("source/fixture/exit"));
        Path classes = Files.createDirectories(root.resolve("classes"));
        Files.writeString(source.resolve("ExitDriver.java"), DRIVER);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.resolve("ExitDriver.java").toString()));
        Path jar = root.resolve("driver.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("fixture/exit/ExitDriver.class"));
            Files.copy(classes.resolve("fixture/exit/ExitDriver.class"), output); output.closeEntry();
        }
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        var spec = new DriverBundleSpec() {
            public String getPath() { return jar.toString(); }
            public String getSha256() { return sha; }
            public String getDriverClassName() { return "fixture.exit.ExitDriver"; }
            public String getBundleRoot() { return null; }
            public List<? extends Artifact> getArtifacts() { return List.of(); }
            public Lifecycle getLifecycle() { return Lifecycle.LEASED; }
        };
        var registry = new DriverRuntimeRegistry();
        var lease = registry.acquire(DriverBundleVerifier.verify(product, spec));
        var physical = new InvisibleDataSource(lease, "jdbc:exit:fixture", null, null, new Properties());
        var pool = DriverPoolFactory.createConfigured(config(physical), null);
        return new Fixture(registry, physical, pool, lease.classLoader().loadClass("fixture.exit.ExitDriver"));
    }

    private static HikariConfig config(DataSource physical) {
        var config = new HikariConfig(); config.setDataSource(physical); config.setMinimumIdle(0);
        config.setMaximumPoolSize(1); config.setInitializationFailTimeout(-1); config.setConnectionTimeout(500);
        return config;
    }
    private record Fixture(DriverRuntimeRegistry registry, InvisibleDataSource source, HikariDataSource pool,
                           Class<?> driver) implements AutoCloseable {
        AtomicBoolean flag(String name) throws ReflectiveOperationException { return (AtomicBoolean) driver.getField(name).get(null); }
        CountDownLatch signal(String name) throws ReflectiveOperationException { return (CountDownLatch) driver.getField(name).get(null); }
        public void close() { pool.close(); registry.close(); }
    }
    private static final String DRIVER = """
            package fixture.exit;
            import java.sql.*; import java.util.*; import java.util.concurrent.*; import java.util.concurrent.atomic.*;
            import java.lang.reflect.Proxy; import java.util.logging.Logger;
            public final class ExitDriver implements Driver {
                public static final AtomicBoolean blockConnect=new AtomicBoolean(), blockClose=new AtomicBoolean(), failClose=new AtomicBoolean();
                public static final CountDownLatch connectEntered=new CountDownLatch(1), allowConnect=new CountDownLatch(1), closeEntered=new CountDownLatch(1), allowClose=new CountDownLatch(1);
                private static void await(CountDownLatch latch) { while(latch.getCount()!=0) try { latch.await(); } catch(InterruptedException ignored) { } }
                public Connection connect(String url, Properties p) {
                    connectEntered.countDown(); if(blockConnect.get()) await(allowConnect);
                    var closed=new AtomicBoolean();
                    return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
                        switch(method.getName()) {
                            case "close": closeEntered.countDown(); if(blockClose.get()) await(allowClose); if(failClose.get()) throw new SQLException("close unconfirmed"); closed.set(true); return null;
                            case "isClosed": return closed.get();
                            case "isValid": return !closed.get();
                            case "getAutoCommit": return true;
                            case "getTransactionIsolation": return Connection.TRANSACTION_READ_COMMITTED;
                            case "unwrap": if(((Class<?>)args[0]).isInstance(proxy))return proxy; throw new SQLException("unsupported");
                            case "isWrapperFor": return ((Class<?>)args[0]).isInstance(proxy);
                            case "toString": return "ExitFixtureConnection";
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
}
