package cn.iocoder.yudao.framework.dbswitch.config;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.entity.InvisibleDataSource;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.*;

class ConfiguredJdbcDriversTest {
    @TempDir Path directory;

    @Test void emptyDeploymentKeepsAllClasspathDefaults() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(YudaoDbSwitchAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ConfiguredJdbcDrivers.class);
                    for (var product : ProductTypeEnum.values()) {
                        assertThat(context.getBean(ConfiguredJdbcDrivers.class).find(product)).isEmpty();
                    }
                });
    }

    @Test void propertyBindingCreatesAnImmutableDeploymentSnapshot() throws Exception {
        var config = fixture("driver.jar", "one", false);
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(YudaoDbSwitchAutoConfiguration.class))
                .withPropertyValues("yudao.dbswitch.drivers.HIVE.path=" + config.getPath(),
                        "yudao.dbswitch.drivers.HIVE.sha256=" + config.getSha256(),
                        "yudao.dbswitch.drivers.HIVE.driver-class-name=" + config.getDriverClassName())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var registry = context.getBean(ConfiguredJdbcDrivers.class);
                    var binding = registry.find(ProductTypeEnum.HIVE).orElseThrow();
                    context.getBean(DbSwitchProperties.class).getDrivers().clear();
                    assertThat(registry.find(ProductTypeEnum.HIVE)).containsSame(binding);
                    assertThat(binding.toString()).doesNotContain(directory.toString());
                });
    }

    @Test void invalidDeploymentFailsAtStartupWithoutFallback() throws Exception {
        var config = fixture("driver.jar", "one", false);
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(YudaoDbSwitchAutoConfiguration.class))
                .withPropertyValues("yudao.dbswitch.drivers.HIVE.path=" + config.getPath(),
                        "yudao.dbswitch.drivers.HIVE.sha256=" + "0".repeat(64),
                        "yudao.dbswitch.drivers.HIVE.driver-class-name=" + config.getDriverClassName())
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void fingerprintIsContentIdentityNotNodeLocalPath() throws Exception {
        var first = fixture("node-a.jar", "one", false);
        Path copy = directory.resolve("node-b.jar");
        Files.copy(Path.of(first.getPath()), copy);
        var second = config(copy);
        assertThat(binding(first).fingerprint()).isEqualTo(binding(second).fingerprint());
        assertThat(binding(first).fingerprint()).isNotEqualTo(binding(fixture("new.jar", "two", false)).fingerprint());
        assertThat(new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.MYSQL, first))
                .find(ProductTypeEnum.MYSQL).orElseThrow().fingerprint()).isNotEqualTo(binding(first).fingerprint());
        second.setDriverClassName(OtherDriver.class.getName());
        assertThat(binding(first).fingerprint()).isNotEqualTo(binding(second).fingerprint());
    }

    @Test void laterConfigurationMutationDoesNotAlterBinding() throws Exception {
        var config = fixture("driver.jar", "one", false);
        var original = binding(config);
        String fingerprint = original.fingerprint();
        config.setDriverClassName("untrusted.Driver"); config.setPath("untrusted.jar"); config.setSha256("wrong");
        original.verifyUnchanged();
        assertThat(original.fingerprint()).isEqualTo(fingerprint);
        assertThat(original.driverClassName()).isEqualTo(StubDriver.class.getName());
    }

    @ParameterizedTest @ValueSource(strings = {"", "1234", "g000000000000000000000000000000000000000000000000000000000000000"})
    void checksumIsMandatory(String value) throws Exception {
        var config = fixture("driver.jar", "one", false); config.setSha256(value);
        assertThatIllegalArgumentException().isThrownBy(() -> binding(config)).withMessageContaining("SHA-256");
    }

    @Test void uppercaseDigestIsCanonicalized() throws Exception {
        var config = fixture("driver.jar", "one", false);
        String fingerprint = binding(config).fingerprint();
        config.setSha256(config.getSha256().toUpperCase(java.util.Locale.ROOT));
        assertThat(binding(config).fingerprint()).isEqualTo(fingerprint);
    }

    @Test void relativeDirectoryMissingAndRootPathsAreRejectedWithoutLeakingPaths() throws Exception {
        var config = fixture("driver.jar", "one", false);
        for (String path : new String[]{"driver.jar", directory.toString(), directory.resolve("absent.jar").toString(),
                directory.getRoot().toString()}) {
            config.setPath(path);
            assertThatIllegalArgumentException().isThrownBy(() -> binding(config))
                    .withMessageNotContaining(directory.toString());
        }
    }

    @Test void unknownProductAndNullConfigurationAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ConfiguredJdbcDrivers(null));
        assertThatIllegalArgumentException().isThrownBy(() -> new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.UNKNOWN,
                new DbSwitchProperties.Driver())));
        var map = new HashMap<ProductTypeEnum, DbSwitchProperties.Driver>(); map.put(null, null);
        assertThatIllegalArgumentException().isThrownBy(() -> new ConfiguredJdbcDrivers(map));
    }

    @Test void changedOrDeletedArtifactCannotSilentlyFallBack() throws Exception {
        var config = fixture("driver.jar", "one", false);
        var original = binding(config);
        Files.write(Path.of(config.getPath()), new byte[]{1, 2, 3});
        assertThatIllegalArgumentException().isThrownBy(original::verifyUnchanged).withMessageContaining("mismatch");
        assertThatIllegalArgumentException().isThrownBy(() -> DataSourceUtils.createTargetDataSource(
                target(ProductTypeEnum.HIVE), null, null, original));
        Files.delete(Path.of(config.getPath()));
        assertThatIllegalArgumentException().isThrownBy(original::verifyUnchanged);
    }

    @Test void manifestClasspathCannotIntroduceUnpinnedDependencies() throws Exception {
        var config = fixture("driver.jar", "one", true);
        assertThatIllegalArgumentException().isThrownBy(() -> binding(config)).withMessageContaining("not pinned");
    }

    @Test void missingClassMalformedClassAndInvalidJarAreRejected() throws Exception {
        var config = fixture("driver.jar", "one", false);
        config.setDriverClassName("bad/name");
        assertThatIllegalArgumentException().isThrownBy(() -> binding(config)).withMessageContaining("invalid driver class");
        config.setDriverClassName("no.such.Driver");
        assertThatIllegalArgumentException().isThrownBy(() -> binding(config)).withMessageContaining("absent");
        Files.write(Path.of(config.getPath()), new byte[]{0, 1, 2});
        var malformed = config(Path.of(config.getPath()));
        assertThatIllegalArgumentException().isThrownBy(() -> binding(malformed)).withMessageContaining("readable JAR");
    }

    @Test void matchingPinnedPoolUsesIsolatedClassAndReusesItsLoader() throws Exception {
        var driver = binding(fixture("driver.jar", "one", false));
        try (var first = DataSourceUtils.createTargetDataSource(target(ProductTypeEnum.HIVE), null, null, driver);
             var second = DataSourceUtils.createTargetDataSource(target(ProductTypeEnum.HIVE), null, null, driver)) {
            var physical = (InvisibleDataSource) first.getDataSource();
            var other = (InvisibleDataSource) second.getDataSource();
            assertThat(physical.getClassLoader()).isSameAs(other.getClassLoader());
            assertThat(physical.getClassLoader().getParent()).isSameAs(ClassLoader.getPlatformClassLoader());
            assertThat(physical.getClassLoader().loadClass(driver.driverClassName())).isNotEqualTo(StubDriver.class);
        }
        assertThatIllegalArgumentException().isThrownBy(() -> DataSourceUtils.createTargetDataSource(
                target(ProductTypeEnum.MYSQL), null, null, driver)).withMessageContaining("product mismatch");
    }

    @Test void postgresPinnedModeCannotLoadAnUnpinnedClasspathDriver() throws Exception {
        Path original = Path.of(Class.forName("org.postgresql.Driver").getProtectionDomain().getCodeSource().getLocation().toURI());
        var config = config(Files.copy(original, directory.resolve("postgres.jar")));
        config.setDriverClassName("org.postgresql.Driver");
        // The old path mode shares a parent for PG COPY; the pinned overload must not do so.
        var driver = new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.POSTGRESQL, config))
                .find(ProductTypeEnum.POSTGRESQL).orElseThrow();
        try (var pool = DataSourceUtils.createTargetDataSource(target(ProductTypeEnum.POSTGRESQL), null, null, driver)) {
            assertThat(((InvisibleDataSource) pool.getDataSource()).getClassLoader().getParent())
                    .isSameAs(ClassLoader.getPlatformClassLoader());
        }
    }

    @Test void differentPinnedContentAtSamePathCannotReusePreviousLoader() throws Exception {
        var original = binding(fixture("driver.jar", "one", false));
        java.net.URLClassLoader firstLoader;
        try (var pool = DataSourceUtils.createTargetDataSource(target(ProductTypeEnum.HIVE), null, null, original)) {
            firstLoader = (java.net.URLClassLoader) ((InvisibleDataSource) pool.getDataSource()).getClassLoader();
        }
        var replacement = binding(fixture("driver.jar", "two", false));
        try (var pool = DataSourceUtils.createTargetDataSource(target(ProductTypeEnum.HIVE), null, null, replacement)) {
            var loader = (java.net.URLClassLoader) ((InvisibleDataSource) pool.getDataSource()).getClassLoader();
            assertThat(loader).isNotSameAs(firstLoader);
        }
        assertThatIllegalArgumentException().isThrownBy(original::verifyUnchanged);
    }

    private ConfiguredJdbcDrivers.Binding binding(DbSwitchProperties.Driver config) {
        return new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.HIVE, config)).find(ProductTypeEnum.HIVE).orElseThrow();
    }

    private DbSwitchProperties.Driver fixture(String name, String marker, boolean externalDependency) throws Exception {
        Path path = directory.resolve(name);
        var manifest = new Manifest(); manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (externalDependency) { manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, "unverified.jar"); }
        try (var out = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            for (Class<?> driver : new Class<?>[]{StubDriver.class, OtherDriver.class}) {
                String entry = driver.getName().replace('.', '/') + ".class";
                out.putNextEntry(new JarEntry(entry));
                try (var input = driver.getResourceAsStream("/" + entry)) { input.transferTo(out); }
                out.closeEntry();
            }
            out.putNextEntry(new JarEntry("marker")); out.write(marker.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return config(path);
    }

    private DbSwitchProperties.Driver config(Path path) throws Exception {
        var config = new DbSwitchProperties.Driver(); config.setPath(path.toString());
        config.setSha256(DigestUtil.sha256Hex(Files.readAllBytes(path))); config.setDriverClassName(StubDriver.class.getName());
        config.setLifecycle(cn.iocoder.yudao.framework.dbswitch.driver.DriverBundleSpec.Lifecycle.LEASED);
        return config;
    }

    private TargetDataSourceProperties target(ProductTypeEnum product) {
        var target = new TargetDataSourceProperties(); target.setTargetProductType(product);
        target.setUrl("jdbc:stub:test"); target.setDriverClassName("ignored.Untrusted"); target.setPoolSize(1);
        return target;
    }

    public static class StubDriver implements Driver {
        public Connection connect(String url, Properties properties) { return null; }
        public boolean acceptsURL(String url) { return false; }
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties properties) { return new DriverPropertyInfo[0]; }
        public int getMajorVersion() { return 1; }
        public int getMinorVersion() { return 0; }
        public boolean jdbcCompliant() { return false; }
        public Logger getParentLogger() { return Logger.getGlobal(); }
    }

    public static class OtherDriver extends StubDriver { }
}
