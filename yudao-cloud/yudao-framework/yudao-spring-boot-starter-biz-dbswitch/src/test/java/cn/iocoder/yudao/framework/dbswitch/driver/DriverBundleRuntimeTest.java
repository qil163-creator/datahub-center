package cn.iocoder.yudao.framework.dbswitch.driver;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.entity.InvisibleDataSource;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class DriverBundleRuntimeTest {
    @TempDir Path directory;

    @Test void realConnectResolvesTheSecondJarAndPropertiesArePrivateSnapshots() throws Exception {
        var spec = fixture("one", "version-one");
        var defaults = new Properties(); defaults.setProperty("inherited", "default-value");
        var original = new Properties(defaults); original.setProperty("visible", "snapshot");
        try (var drivers = configured(spec)) {
            var binding = drivers.find(ProductTypeEnum.HIVE).orElseThrow();
            try (var source = new InvisibleDataSource(binding.acquire(), "jdbc:bundle:test", "fixture-user", "fixture-password", original)) {
                original.setProperty("visible", "caller-mutation"); defaults.setProperty("inherited", "caller-default-mutation");
                source.getProperties().setProperty("visible", "getter-mutation");
                for (int i = 0; i < 2; i++) try (var connection = source.getConnection()) {
                    assertEquals("version-one", connection.getClientInfo("version"));
                    assertEquals("snapshot", connection.getClientInfo("visible"));
                    assertEquals("default-value", connection.getClientInfo("inherited"));
                    assertEquals("fixture-user", connection.getClientInfo("user"));
                    assertNotSame(getClass().getClassLoader(), DriverNativeAccess.unwrap(connection, "java.sql.Connection").getClass().getClassLoader());
                }
            }
            assertEquals(DriverRuntimeRegistry.State.CLOSED, drivers.runtimes().statuses().getFirst().state());
        }
    }

    @Test void originalSelfIndexedArtifactsSurviveSnapshotAndRemainIsolated() throws Exception {
        var spec = fixture("self-index", "indexed", true);
        var bundle = DriverBundleVerifier.verify(ProductTypeEnum.HIVE, spec);
        try (var registry = new DriverRuntimeRegistry(); var lease = registry.acquire(bundle)) {
            assertEquals("indexed", lease.classLoader().loadClass("fixture.bundle.Helper").getMethod("marker").invoke(null));
            assertThrows(ClassNotFoundException.class, () -> lease.classLoader().loadClass("org.junit.jupiter.api.Test"));
            // Deployment files are replaceable; only the private verified snapshot may execute.
            Files.write(Path.of(spec.getBundleRoot()).resolve("helper.jar"), new byte[]{0});
            try (var connection = lease.connect("jdbc:bundle:test", new Properties())) {
                assertEquals("indexed", connection.getClientInfo("version"));
            }
        }
    }

    @Test void sourceAndTargetUseTheSameVerifiedRuntimeAndPoolCloseReleasesLeases() throws Exception {
        try (var drivers = configured(fixture("pool", "v1"))) {
            var binding = drivers.find(ProductTypeEnum.HIVE).orElseThrow();
            var input = new SourceDataSourceProperties(); input.setSourceProductType(ProductTypeEnum.HIVE);
            input.setUrl("jdbc:bundle:test"); input.setPoolSize(1);
            var output = new TargetDataSourceProperties(); output.setTargetProductType(ProductTypeEnum.HIVE);
            output.setUrl("jdbc:bundle:test"); output.setPoolSize(1);
            try (var source = DataSourceUtils.createSourceDataSource(input, null, null, binding);
                 var target = DataSourceUtils.createTargetDataSource(output, null, null, binding)) {
                assertSame(((InvisibleDataSource) source.getDataSource()).getClassLoader(),
                        ((InvisibleDataSource) target.getDataSource()).getClassLoader());
                try (var first = source.getConnection(); var second = target.getConnection()) {
                    assertEquals("v1", first.getClientInfo("version")); assertEquals("v1", second.getClientInfo("version"));
                }
                assertEquals(2, drivers.runtimes().statuses().getFirst().leases());
            }
            assertEquals(DriverRuntimeRegistry.State.CLOSED, drivers.runtimes().statuses().getFirst().state());
        }
    }

    @Test void closeWithoutPhysicalExitKeepsTheRuntimeDraining() throws Exception {
        try (var drivers = configured(fixture("drain", "v1"))) {
            var binding = drivers.find(ProductTypeEnum.HIVE).orElseThrow();
            var source = new InvisibleDataSource(binding.acquire(), "jdbc:bundle:test", null, null, new Properties());
            Connection connection = source.getConnection();
            connection.setClientInfo("block-close", "true");
            var worker = Executors.newSingleThreadExecutor();
            try {
                var closing = worker.submit(() -> { connection.close(); return null; });
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3))
                        .until(() -> "true".equals(connection.getClientInfo("close-entered")));
                source.close();
                assertFalse(source.hasExited());
                assertEquals(1, drivers.runtimes().connections().snapshot().quarantined());
                assertEquals(DriverRuntimeRegistry.State.DRAINING, drivers.runtimes().statuses().getFirst().state());
                assertThrows(IllegalStateException.class, binding::acquire);
                connection.setClientInfo("block-close", "false");
                closing.get(3, TimeUnit.SECONDS);
                assertTrue(source.hasExited());
                assertEquals(0, drivers.runtimes().connections().snapshot().quarantined());
                assertEquals(DriverRuntimeRegistry.State.CLOSED, drivers.runtimes().statuses().getFirst().state());
            } finally {
                connection.setClientInfo("block-close", "false"); connection.close(); source.close(); worker.shutdownNow();
            }
        }
    }

    @Test void concurrentAcquisitionPublishesOneRuntimeAndVersionsRemainIsolated() throws Exception {
        var first = DriverBundleVerifier.verify(ProductTypeEnum.HIVE, fixture("first", "one"));
        var second = DriverBundleVerifier.verify(ProductTypeEnum.HIVE, fixture("second", "two"));
        try (var registry = new DriverRuntimeRegistry(); var pool = Executors.newFixedThreadPool(4)) {
            var calls = new ArrayList<Future<DriverRuntimeRegistry.Lease>>();
            for (int i = 0; i < 8; i++) calls.add(pool.submit(() -> registry.acquire(first)));
            var leases = new ArrayList<DriverRuntimeRegistry.Lease>();
            for (var call : calls) leases.add(call.get());
            try (var other = registry.acquire(second)) {
                assertEquals(2, registry.statuses().size());
                for (var lease : leases) assertSame(leases.getFirst().classLoader(), lease.classLoader());
                assertNotSame(leases.getFirst().classLoader(), other.classLoader());
                try (var one = leases.getFirst().connect("jdbc:bundle:test", new Properties());
                     var two = other.connect("jdbc:bundle:test", new Properties())) {
                    assertEquals("one", one.getClientInfo("version")); assertEquals("two", two.getClientInfo("version"));
                }
            } finally { leases.forEach(DriverRuntimeRegistry.Lease::close); }
            assertTrue(registry.statuses().stream().allMatch(status -> status.state() == DriverRuntimeRegistry.State.CLOSED));
        }
    }

    @Test void jdbcDeadlineCannotReleaseAPhysicalPermitWithoutExitConfirmation() throws Exception {
        try (var drivers = configured(fixture("deadline-accounting", "v1"));
             var source = new InvisibleDataSource(drivers.find(ProductTypeEnum.HIVE).orElseThrow().acquire(),
                     "jdbc:bundle:test", null, null, new Properties());
             var connection = source.getConnection()) {
            // This real isolated fixture's abort method returns without closing the physical connection.
            try (var deadline = new cn.iocoder.yudao.framework.dbswitch.core.util.JdbcReadDeadline(connection, 100)) {
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3))
                        .until(() -> drivers.runtimes().connections().snapshot().quarantined() == 1);
                assertThrows(java.sql.SQLTimeoutException.class, deadline::check);
                assertEquals(1, drivers.runtimes().connections().snapshot().workload());
                assertFalse(connection.isClosed());
            }
            connection.close();
            assertEquals(0, drivers.runtimes().connections().snapshot().workload());
        }
    }

    @Test void fingerprintsFollowOrderedContentAndTamperingCannotReuseTheRuntime() throws Exception {
        var spec = fixture("identity", "v1");
        var binding = DriverBundleVerifier.verify(ProductTypeEnum.HIVE, spec);
        Path moved = Files.createDirectories(directory.resolve("node-b"));
        Files.copy(Path.of(spec.getBundleRoot()).resolve("driver.jar"), moved.resolve("driver.jar"));
        Files.copy(Path.of(spec.getBundleRoot()).resolve("helper.jar"), moved.resolve("helper.jar"));
        assertEquals(binding.fingerprint(), DriverBundleVerifier.verify(ProductTypeEnum.HIVE,
                new Spec(moved.toString(), spec.getArtifacts(), spec.getLifecycle())).fingerprint());
        var reverse = new ArrayList<>(spec.getArtifacts()); Collections.reverse(reverse);
        assertNotEquals(binding.fingerprint(), DriverBundleVerifier.verify(ProductTypeEnum.HIVE,
                new Spec(spec.getBundleRoot(), reverse, spec.getLifecycle())).fingerprint());
        try (var runtime = new DriverRuntimeRegistry(); var lease = runtime.acquire(binding)) {
            Files.write(Path.of(spec.getBundleRoot()).resolve("helper.jar"), new byte[]{0});
            assertThrows(IllegalArgumentException.class, () -> runtime.acquire(binding));
            try (var old = lease.connect("jdbc:bundle:test", new Properties())) {
                assertEquals("v1", old.getClientInfo("version"), "Active leases use the verified private snapshot");
            }
        }
    }

    @Test void unverifiedPathsAndGlobalTimeoutChangesAreRejected() throws Exception {
        var input = new SourceDataSourceProperties(); input.setDriverPath(directory.toString());
        assertThrows(IllegalArgumentException.class, () -> DataSourceUtils.createSourceDataSource(input));
        var output = new TargetDataSourceProperties(); output.setDriverPath(directory.toString());
        assertThrows(IllegalArgumentException.class, () -> DataSourceUtils.createTargetDataSource(output));
        try (var owner = configured(fixture("timeout", "v1"));
             var source = new InvisibleDataSource(owner.find(ProductTypeEnum.HIVE).orElseThrow().acquire(), "jdbc:bundle:test", null, null, new Properties())) {
            int original = java.sql.DriverManager.getLoginTimeout();
            assertThrows(java.sql.SQLFeatureNotSupportedException.class, () -> source.setLoginTimeout(original + 1));
            assertEquals(original, java.sql.DriverManager.getLoginTimeout());
        }
    }

    @Test void driverConnectionFailuresHideSecretsAndRestoreContextAndQuota() throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (var drivers = configured(fixture("failure", "v1"));
             var source = new InvisibleDataSource(drivers.find(ProductTypeEnum.HIVE).orElseThrow().acquire(),
                     "jdbc:bundle:fail", "fixture-user", "fixture-password", new Properties())) {
            SQLException failure = assertThrows(SQLException.class, source::getConnection);
            assertEquals("08006", failure.getSQLState()); assertEquals(17, failure.getErrorCode());
            assertFalse(failure.toString().contains("fixture-secret")); assertNull(failure.getCause());
            assertEquals(0, drivers.runtimes().connections().snapshot().workload());
            assertSame(previous, Thread.currentThread().getContextClassLoader());
        }
    }

    @Test void physicalConnectionOutlivesItsLeaseWithoutUnloadingItsRuntime() throws Exception {
        var bundle = DriverBundleVerifier.verify(ProductTypeEnum.HIVE, fixture("live-physical", "v1"));
        try (var registry = new DriverRuntimeRegistry()) {
            var lease = registry.acquire(bundle);
            var connection = lease.connect("jdbc:bundle:test", new Properties());
            try {
                lease.close();
                assertEquals(DriverRuntimeRegistry.State.DRAINING, registry.statuses().getFirst().state());
                assertEquals(1, registry.connections().snapshot().active());
                assertThrows(IllegalStateException.class, () -> registry.acquire(bundle));
                assertEquals("v1", connection.getClientInfo("version"));
            } finally { connection.close(); lease.close(); }
            assertEquals(DriverRuntimeRegistry.State.CLOSED, registry.statuses().getFirst().state());
            assertEquals(0, registry.connections().snapshot().active());
        }
    }

    @Test void metadataReservationAndSharedPhysicalLimitCoverBothRoles() throws Exception {
        var bundle = DriverBundleVerifier.verify(ProductTypeEnum.HIVE, fixture("quota", "v1"));
        var quota = new ConnectionBudget(2, 1);
        try (var registry = new DriverRuntimeRegistry(quota);
             var workload = registry.acquire(bundle, ConnectionBudget.Role.WORKLOAD);
             var metadata = registry.acquire(bundle, ConnectionBudget.Role.METADATA);
             var first = workload.connect("jdbc:bundle:test", new Properties())) {
            assertThrows(java.sql.SQLTimeoutException.class, () -> quota.acquire(ConnectionBudget.Role.WORKLOAD,
                    java.time.Duration.ofMillis(10)));
            try (var second = metadata.connect("jdbc:bundle:test", new Properties())) {
                assertEquals(2, quota.snapshot().active()); assertEquals(1, quota.snapshot().metadata());
                assertThrows(java.sql.SQLTimeoutException.class, () -> quota.acquire(ConnectionBudget.Role.METADATA,
                        java.time.Duration.ofMillis(10)));
            }
            assertEquals(1, quota.snapshot().active());
        }
        assertEquals(0, quota.snapshot().active());
    }

    @Test void omittedDependencyFailsAtRealConnectWithoutApplicationClasspathFallback() throws Exception {
        var complete = fixture("missing-dependency", "v1");
        var missing = new Spec(complete.getBundleRoot(), List.of(complete.getArtifacts().getFirst()), complete.getLifecycle());
        try (var drivers = configured(missing);
             var source = new InvisibleDataSource(drivers.find(ProductTypeEnum.HIVE).orElseThrow().acquire(),
                     "jdbc:bundle:test", null, null, new Properties())) {
            assertThrows(SQLException.class, source::getConnection);
            assertEquals(0, drivers.runtimes().connections().snapshot().workload());
        }
    }

    private ConfiguredJdbcDrivers configured(Spec spec) { return new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.HIVE, spec)); }
    private Spec fixture(String label, String marker) throws Exception {
        return fixture(label, marker, false);
    }
    private Spec fixture(String label, String marker, boolean selfIndex) throws Exception {
        Path root = Files.createDirectories(directory.resolve(label)); Path classes = Files.createDirectories(root.resolve("classes"));
        Path source = Files.createDirectories(root.resolve("source/fixture/bundle"));
        Files.writeString(source.resolve("Helper.java"), "package fixture.bundle; public class Helper { public static String marker(){ return \"" + marker + "\"; } }");
        Files.writeString(source.resolve("FixtureDriver.java"), DRIVER_SOURCE);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(),
                source.resolve("Helper.java").toString(), source.resolve("FixtureDriver.java").toString()));
        jar(root.resolve("driver.jar"), classes, "fixture/bundle/FixtureDriver.class", selfIndex);
        jar(root.resolve("helper.jar"), classes, "fixture/bundle/Helper.class", selfIndex);
        return new Spec(root.toString(), List.of(new Artifact("driver.jar", VerifiedDriverTestHandle.sha256(root.resolve("driver.jar"))),
                new Artifact("helper.jar", VerifiedDriverTestHandle.sha256(root.resolve("helper.jar")))), DriverBundleSpec.Lifecycle.LEASED);
    }
    private static void jar(Path target, Path classes, String entry, boolean selfIndex) throws Exception {
        try (var output = new JarOutputStream(Files.newOutputStream(target))) {
            output.putNextEntry(new JarEntry(entry)); Files.copy(classes.resolve(entry), output); output.closeEntry();
            if (selfIndex) {
                output.putNextEntry(new JarEntry("META-INF/INDEX.LIST"));
                output.write(("JarIndex-Version: 1.0\n\n" + target.getFileName() + "\nfixture/bundle\n\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }
    record Artifact(String getFile, String getSha256) implements DriverBundleSpec.Artifact { }
    record Spec(String getBundleRoot, List<DriverBundleRuntimeTest.Artifact> getArtifacts, DriverBundleSpec.Lifecycle getLifecycle) implements DriverBundleSpec {
        @Override public String getPath() { return null; }
        @Override public String getSha256() { return null; }
        @Override public String getDriverClassName() { return "fixture.bundle.FixtureDriver"; }
    }
    private static final String DRIVER_SOURCE = """
            package fixture.bundle;
            import java.sql.*; import java.util.*; import java.lang.reflect.*; import java.util.concurrent.*; import java.util.logging.Logger;
            public class FixtureDriver implements Driver {
              public Connection connect(String url, Properties properties) throws SQLException {
                if (!acceptsURL(url)) return null;
                if (url.endsWith(":fail")) throw new SQLException("fixture-secret user and connection URL", "08006", 17);
                var values = new ConcurrentHashMap<String,String>();
                for (String key : properties.stringPropertyNames()) values.put(key, properties.getProperty(key));
                values.put("version", Helper.marker()); properties.setProperty("visible", "driver-mutation");
                boolean[] closed = {false};
                return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Connection.class}, (proxy, method, args) -> {
                  switch(method.getName()) {
                    case "getClientInfo": return args == null ? new Properties() : values.get((String)args[0]);
                    case "setClientInfo": values.put((String)args[0], (String)args[1]); return null;
                    case "close": values.put("close-entered", "true"); while("true".equals(values.get("block-close"))) Thread.sleep(5); closed[0] = true; return null;
                    case "isClosed": return closed[0];
                    case "isValid": return !closed[0];
                    case "getAutoCommit": return true;
                    case "getTransactionIsolation": return Connection.TRANSACTION_READ_COMMITTED;
                    case "unwrap": if (((Class)args[0]).isInstance(proxy)) return proxy; throw new SQLException("unsupported");
                    case "isWrapperFor": return ((Class)args[0]).isInstance(proxy);
                    case "toString": return "FixtureConnection";
                    case "createStatement": return Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Statement.class}, (s,m,a) -> { if(m.getReturnType()==boolean.class)return false; if(m.getReturnType()==int.class)return -1; return null; });
                  }
                  if(method.getReturnType()==boolean.class)return false; if(method.getReturnType()==int.class)return 0;
                  if(method.getReturnType()==long.class)return 0L; return null;
                });
              }
              public boolean acceptsURL(String url){return url.startsWith("jdbc:bundle:");}
              public DriverPropertyInfo[] getPropertyInfo(String url,Properties info){return new DriverPropertyInfo[0];}
              public int getMajorVersion(){return 1;} public int getMinorVersion(){return 0;}
              public boolean jdbcCompliant(){return false;} public Logger getParentLogger(){return Logger.getGlobal();}
            }
            """;
}
