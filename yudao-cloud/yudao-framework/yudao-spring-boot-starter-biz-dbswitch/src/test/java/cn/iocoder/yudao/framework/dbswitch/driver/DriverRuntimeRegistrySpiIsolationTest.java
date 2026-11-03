package cn.iocoder.yudao.framework.dbswitch.driver;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each probe needs a new JVM: DriverManager's service discovery is a process-wide one-time action. */
@Timeout(90)
public class DriverRuntimeRegistrySpiIsolationTest {
    @TempDir Path directory;

    @Test void leasedIsolationPreservesApplicationSpiAndForeignRegistration() throws Exception {
        probe("leased");
    }

    @Test void failedIsolatedConstructorPreservesApplicationSpiAndCallerContext() throws Exception {
        probe("failure");
    }

    @Test void concurrentRegistriesInitializeApplicationSpiOnceAndRestoreEveryCallerContext() throws Exception {
        probe("concurrent");
    }

    private void probe(String mode) throws Exception {
        Path child = childBundle();
        Path service = directory.resolve("application-spi.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(service))) {
            output.putNextEntry(new JarEntry("META-INF/services/java.sql.Driver"));
            output.write((ParentSpiDriver.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        Path arguments = directory.resolve(mode + ".args");
        Files.writeString(arguments, String.join("\n", "-cp", quote(service + java.io.File.pathSeparator
                + System.getProperty("java.class.path")), quote(Probe.class.getName()), mode, quote(child.toString())) + "\n");
        Path log = directory.resolve(mode + ".log");
        Path executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        var process = new ProcessBuilder(executable.toString(), "@" + arguments)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new AssertionError("Fresh JDBC SPI probe timed out: " + mode);
        }
        String evidence = Files.readString(log);
        assertEquals(0, process.exitValue(), evidence);
        assertTrue(evidence.contains("SPI_PROBE_OK " + mode), evidence);
    }

    private static String quote(String value) {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) throw new IllegalArgumentException("Unexpected argument newline");
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private Path childBundle() throws Exception {
        Path source = directory.resolve("src/isolated/spi");
        Files.createDirectories(source);
        Files.writeString(source.resolve("ChildDriver.java"), """
                package isolated.spi;
                public class ChildDriver implements java.sql.Driver {
                    public static volatile boolean DEREGISTERED;
                    public static final boolean REGISTERED;
                    static {
                        try {
                            java.sql.DriverManager.registerDriver(new ChildDriver(), () -> {
                                DEREGISTERED = true;
                                System.setProperty("dbswitch.spi.child.deregistered", "true");
                            });
                            REGISTERED = true;
                        } catch (java.sql.SQLException failure) { throw new ExceptionInInitializerError(failure); }
                    }
                    public ChildDriver() { }
                    public static long ownedDriverCount() {
                        return java.sql.DriverManager.drivers().filter(driver ->
                            driver.getClass().getClassLoader() == ChildDriver.class.getClassLoader()).count();
                    }
                    public java.sql.Connection connect(String url, java.util.Properties props) { return null; }
                    public boolean acceptsURL(String url) { return false; }
                    public java.sql.DriverPropertyInfo[] getPropertyInfo(String url, java.util.Properties props) {
                        return new java.sql.DriverPropertyInfo[0];
                    }
                    public int getMajorVersion() { return 1; }
                    public int getMinorVersion() { return 0; }
                    public boolean jdbcCompliant() { return false; }
                    public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
                }
                """);
        Files.writeString(source.resolve("FailingDriver.java"), """
                package isolated.spi;
                public class FailingDriver extends ChildDriver {
                    public FailingDriver() { throw new IllegalStateException("Deliberate fixture constructor failure"); }
                }
                """);
        Path classes = directory.resolve("child-classes");
        Files.createDirectories(classes);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null, "A JDK is required for the isolated driver fixture");
        assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(),
                source.resolve("ChildDriver.java").toString(), source.resolve("FailingDriver.java").toString()));
        Path jar = directory.resolve("child.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                output.write(Files.readAllBytes(file));
                output.closeEntry();
            }
        }
        return jar;
    }

    public static final class Probe {
        public static void main(String[] args) throws Exception {
            require(System.getProperty("jdbc.drivers") == null, "The probe must use standard SPI without a driver-list override");
            Driver foreign = new ForeignDriver();
            // Registration does not initialize DriverManager's service discovery.
            DriverManager.registerDriver(foreign);
            Path jar = Path.of(args[1]);
            if (args[0].equals("failure")) {
                withForeignContext(() -> {
                    try (var registry = new DriverRuntimeRegistry()) {
                        boolean rejected = false;
                        try { registry.acquire(bundle(jar, "isolated.spi.FailingDriver")); }
                        catch (IllegalArgumentException expected) { rejected = true; }
                        require(rejected && registry.statuses().isEmpty(), "A failing constructor must not publish a runtime");
                        require("true".equals(System.getProperty("dbswitch.spi.child.deregistered")), "Failed child registration must be removed");
                    }
                });
            } else if (args[0].equals("concurrent")) {
                var start = new CountDownLatch(1);
                try (var workers = Executors.newFixedThreadPool(2)) {
                    var first = workers.submit(() -> { start.await(); withForeignContext(() -> leased(jar)); return true; });
                    var second = workers.submit(() -> { start.await(); withForeignContext(() -> leased(jar)); return true; });
                    start.countDown();
                    require(first.get(20, TimeUnit.SECONDS) && second.get(20, TimeUnit.SECONDS), "Concurrent probes must finish");
                }
            } else {
                withForeignContext(() -> leased(jar));
            }
            Driver parent;
            try { parent = DriverManager.getDriver(ParentSpiDriver.URL); }
            catch (SQLException failure) { throw new AssertionError("Application SPI driver missing after isolated runtime: " + args[0], failure); }
            require(parent.getClass().getClassLoader() == DriverRuntimeRegistry.class.getClassLoader(), "Parent driver ownership must remain application-scoped");
            require(ParentSpiDriver.initializationContext == DriverRuntimeRegistry.class.getClassLoader(), "SPI must initialize with the fixed application loader");
            require(ParentSpiDriver.initializations == 1, "SPI driver must initialize once");
            require(DriverManager.drivers().anyMatch(driver -> driver == foreign), "Cleanup must retain foreign registered drivers");
            System.out.println("SPI_PROBE_OK " + args[0] + " parent=present child=removed foreign=retained context=restored bootstrap=absent");
        }

        private static void leased(Path jar) throws Exception {
            try (var registry = new DriverRuntimeRegistry()) {
                var lease = registry.acquire(bundle(jar, "isolated.spi.ChildDriver"));
                Class<?> child = lease.classLoader().loadClass("isolated.spi.ChildDriver");
                require(lease.classLoader().getParent() == ClassLoader.getPlatformClassLoader(), "The vendor loader must remain isolated");
                require(child.getField("REGISTERED").getBoolean(null), "The child fixture must actually register a driver");
                lease.close();
                require(registry.statuses().getFirst().state() == DriverRuntimeRegistry.State.CLOSED, "Owned runtime must close after its last lease");
                require(child.getField("DEREGISTERED").getBoolean(null), "Child DriverAction must confirm deregistration");
                require(((Long) child.getMethod("ownedDriverCount").invoke(null)) == 0, "No owned child registration may remain");
            }
        }

        private static void withForeignContext(Checked action) throws Exception {
            var thread = Thread.currentThread();
            ClassLoader original = thread.getContextClassLoader();
            try (var foreign = new URLClassLoader(new URL[0], ClassLoader.getPlatformClassLoader())) {
                thread.setContextClassLoader(foreign);
                try { action.run(); }
                finally { require(thread.getContextClassLoader() == foreign, "Every success/failure path must restore the caller TCCL"); }
            } finally { thread.setContextClassLoader(original); }
        }

        private static VerifiedDriverBundle bundle(Path jar, String entry) throws Exception {
            String digest = DriverBundleVerifier.digest(Files.readAllBytes(jar));
            return DriverBundleVerifier.verify(ProductTypeEnum.HIVE, new DriverBundleSpec() {
                @Override public String getPath() { return jar.toString(); }
                @Override public String getSha256() { return digest; }
                @Override public String getDriverClassName() { return entry; }
                @Override public String getBundleRoot() { return null; }
                @Override public List<? extends Artifact> getArtifacts() { return null; }
                @Override public Lifecycle getLifecycle() { return Lifecycle.LEASED; }
            });
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
        @FunctionalInterface private interface Checked { void run() throws Exception; }
    }

    public static class ForeignDriver implements Driver {
        @Override public Connection connect(String url, Properties info) { return null; }
        @Override public boolean acceptsURL(String url) { return false; }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
        @Override public int getMajorVersion() { return 1; }
        @Override public int getMinorVersion() { return 0; }
        @Override public boolean jdbcCompliant() { return false; }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
    }

    public static final class ParentSpiDriver extends ForeignDriver {
        public static final String URL = "jdbc:dbswitch-parent-spi:probe";
        public static final ClassLoader initializationContext;
        public static final int initializations;
        static {
            initializationContext = Thread.currentThread().getContextClassLoader();
            initializations = 1;
            try { DriverManager.registerDriver(new ParentSpiDriver()); }
            catch (SQLException failure) { throw new ExceptionInInitializerError(failure); }
        }
        public ParentSpiDriver() { }
        @Override public boolean acceptsURL(String url) { return URL.equals(url); }
    }
}
