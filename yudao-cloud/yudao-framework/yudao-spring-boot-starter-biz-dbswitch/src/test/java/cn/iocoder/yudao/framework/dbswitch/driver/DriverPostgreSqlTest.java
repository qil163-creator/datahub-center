package cn.iocoder.yudao.framework.dbswitch.driver;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.entity.InvisibleDataSource;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.pg.PostgresCopyWriterImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.PGConnection;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL through the verified deployment binding, Hikari owner and native COPY bridge. */
@Timeout(40)
class DriverPostgreSqlTest {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static String url, user, password;
    @TempDir Path directory;
    String schema;
    Connection control;

    @BeforeAll static void database() {
        url = System.getProperty("datahub.test.postgres.url");
        if (url != null) {
            assertTrue(url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+"));
            user = System.getProperty("datahub.test.postgres.user", "dataforge_test"); password = "";
        } else {
            Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Requires a dedicated PostgreSQL instance");
            POSTGRES.start(); url = POSTGRES.getJdbcUrl(); user = POSTGRES.getUsername(); password = POSTGRES.getPassword();
        }
    }
    @AfterAll static void stop() { if (POSTGRES.isRunning()) POSTGRES.stop(); }
    @BeforeEach void setup() throws Exception {
        control = DriverManager.getConnection(url, user, password);
        schema = "drivercopy_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema);
        sql("CREATE TABLE " + schema + ".rows(id integer PRIMARY KEY, note text, payload bytea)");
    }
    @AfterEach void cleanup() throws Exception {
        if (control != null) try (var owned = control) {
            assertTrue(schema.matches("drivercopy_[a-f0-9]{32}"));
            sql("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test void isolatedDriverCopyCommitsAndPoolCloseDeregistersItsDriver() throws Exception {
        Path original = Path.of(org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(Files.isRegularFile(original), "Use the actual dependency JAR, not an application class directory");
        Path artifact = Files.copy(original, directory.resolve("postgresql.jar"));
        Path probeArtifact = registrationProbe();
        var spec = new Spec(directory.toString(), List.of(
                new Artifact("postgresql.jar", VerifiedDriverTestHandle.sha256(artifact)),
                new Artifact("probe.jar", VerifiedDriverTestHandle.sha256(probeArtifact))));
        var target = new TargetDataSourceProperties(); target.setTargetProductType(ProductTypeEnum.POSTGRESQL);
        target.setUrl(url); target.setUsername(user); target.setPassword(password); target.setPoolSize(2);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (var drivers = new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.POSTGRESQL, spec))) {
            Class<?> registrationProbe;
            InvisibleDataSource physical;
            try (var pool = DataSourceUtils.createTargetDataSource(target, null, null,
                    drivers.find(ProductTypeEnum.POSTGRESQL).orElseThrow())) {
                physical = (InvisibleDataSource) pool.getDataSource();
                Class<?> isolatedPg = physical.getClassLoader().loadClass("org.postgresql.PGConnection");
                Class<?> isolatedDriver = physical.getClassLoader().loadClass("org.postgresql.Driver");
                registrationProbe = physical.getClassLoader().loadClass("fixture.DriverRegistrationProbe");
                assertEquals(1, registrationProbe.getMethod("count").invoke(null));
                assertNotSame(PGConnection.class, isolatedPg);
                assertNotSame(org.postgresql.Driver.class, isolatedDriver);
                try (var connection = pool.getConnection()) {
                    Object nativeConnection = DriverNativeAccess.unwrap(connection, "org.postgresql.PGConnection");
                    assertTrue(isolatedPg.isInstance(nativeConnection));
                    assertFalse(PGConnection.class.isInstance(nativeConnection));
                    assertSame(physical.getClassLoader(), nativeConnection.getClass().getClassLoader());
                }
                var writer = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().writer(pool, ProductTypeEnum.POSTGRESQL, cn.iocoder.yudao.framework.dbswitch.spi.WriteMode.COPY);
                assertInstanceOf(PostgresCopyWriterImpl.class, writer);
                var fields = List.of("id", "note", "payload");
                writer.prepareWrite(schema, "rows", fields);
                var result = writer.writeWithReceipt(fields, List.of(
                        new Object[]{1, "隔离 COPY\n含分隔符\t", new byte[]{0, 1, -1}}, new Object[]{2, null, null}));
                assertEquals(2, result.acknowledgedRows()); assertNotNull(result.receipt());
                try (var statement = control.createStatement(); var rows = statement.executeQuery(
                        "SELECT id,note,payload,xmin::text FROM " + schema + ".rows ORDER BY id")) {
                    assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); assertEquals("隔离 COPY\n含分隔符\t", rows.getString(2));
                    assertArrayEquals(new byte[]{0, 1, -1}, rows.getBytes(3)); assertEquals(result.receipt().transactionId(), rows.getString(4));
                    assertTrue(rows.next()); assertEquals(2, rows.getInt(1)); assertNull(rows.getString(2)); assertNull(rows.getBytes(3));
                    assertFalse(rows.next());
                }
                assertSame(previous, Thread.currentThread().getContextClassLoader());
            }
            assertTrue(physical.hasExited());
            assertEquals(DriverRuntimeRegistry.State.CLOSED, drivers.runtimes().statuses().getFirst().state());
            assertEquals(0, registrationProbe.getMethod("count").invoke(null));
        }
        assertSame(previous, Thread.currentThread().getContextClassLoader());
    }

    private void sql(String statement) throws SQLException { try (var command = control.createStatement()) { command.execute(statement); } }
    private Path registrationProbe() throws Exception {
        Path source = Files.createDirectories(directory.resolve("source/fixture")).resolve("DriverRegistrationProbe.java");
        Path classes = Files.createDirectories(directory.resolve("classes"));
        Files.writeString(source, """
                package fixture;
                public class DriverRegistrationProbe {
                  public static int count() {
                    int count = 0; var entries = java.sql.DriverManager.getDrivers();
                    while (entries.hasMoreElements()) if (entries.nextElement().getClass().getClassLoader()
                        == DriverRegistrationProbe.class.getClassLoader()) count++;
                    return count;
                  }
                }
                """);
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", classes.toString(), source.toString()));
        Path artifact = directory.resolve("probe.jar");
        try (var output = new java.util.jar.JarOutputStream(Files.newOutputStream(artifact))) {
            output.putNextEntry(new java.util.jar.JarEntry("fixture/DriverRegistrationProbe.class"));
            Files.copy(classes.resolve("fixture/DriverRegistrationProbe.class"), output); output.closeEntry();
        }
        return artifact;
    }
    record Artifact(String getFile, String getSha256) implements DriverBundleSpec.Artifact { }
    record Spec(String getBundleRoot, List<DriverPostgreSqlTest.Artifact> getArtifacts) implements DriverBundleSpec {
        @Override public String getPath() { return null; }
        @Override public String getSha256() { return null; }
        @Override public String getDriverClassName() { return "org.postgresql.Driver"; }
        @Override public Lifecycle getLifecycle() { return Lifecycle.LEASED; }
    }
}
