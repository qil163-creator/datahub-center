package cn.iocoder.yudao.framework.dbswitch.driver;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.entity.JarClassLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;

class DriverBundleVerifierTest {
    @TempDir Path directory;

    @Test void dependencyClassCollisionIsRejectedBeforeLoading() throws Exception {
        jar("one.jar", null, "fixture/Driver.class", "fixture/Helper.class");
        jar("two.jar", null, "fixture/Helper.class");
        var failure = assertThrows(IllegalArgumentException.class, () -> verify("one.jar", "two.jar"));
        assertTrue(failure.getMessage().contains("duplicate class"));
    }

    @Test void manifestCannotAddAnUnverifiedDependency() throws Exception {
        var manifest = manifest(); manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, "unverified.jar");
        jar("driver.jar", manifest, "fixture/Driver.class");
        assertThrows(IllegalArgumentException.class, () -> verify("driver.jar"));
    }

    @Test void malformedJarIndexAndSharedApiClassesAreRejected() throws Exception {
        jar("index.jar", null, "fixture/Driver.class", "META-INF/INDEX.LIST");
        jar("shared.jar", null, "fixture/Driver.class", "java/sql/Connection.class");
        assertThrows(IllegalArgumentException.class, () -> verify("index.jar"));
        assertThrows(IllegalArgumentException.class, () -> verify("shared.jar"));
    }

    @Test void selfIndexIsAcceptedButCannotAddAnotherSearchLocation() throws Exception {
        indexedJar("self.jar", "JarIndex-Version: 1.0\n\nself.jar\nMETA-INF\nfixture\n\n");
        assertNotNull(verify("self.jar"));
        for (String illegal : List.of("other.jar\nfixture", "../self.jar\nfixture", "file:/self.jar\nfixture",
                "self.jar\nfixture\n\nother.jar\nother", "self.jar\nother.jar\nother",
                "self.jar\n../outside", "self.jar\nfixture\\outside", "self.jar\nfixture/./outside",
                "self.jar\nfixture\u0000", "self.jar\n\nfixture", "self.jar\n" + "a".repeat(65536))) {
            indexedJar("self.jar", "JarIndex-Version: 1.0\n\n" + illegal + "\n");
            assertThrows(IllegalArgumentException.class, () -> verify("self.jar"));
        }
    }

    private void indexedJar(String name, String index) throws Exception {
        try (var output = new JarOutputStream(Files.newOutputStream(directory.resolve(name)))) {
            putEntry(output, "fixture/Driver.class", new byte[]{0});
            putEntry(output, "META-INF/INDEX.LIST", index.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test void relativeEscapeAndDuplicateArtifactsAreRejected() throws Exception {
        jar("driver.jar", null, "fixture/Driver.class");
        var hash = VerifiedDriverTestHandle.sha256(directory.resolve("driver.jar"));
        var escape = new Spec(directory.toString(), List.of(new Artifact("../driver.jar", hash)));
        assertThrows(IllegalArgumentException.class, () -> DriverBundleVerifier.verify(ProductTypeEnum.HIVE, escape));
        assertThrows(IllegalArgumentException.class, () -> verify("driver.jar", "driver.jar"));
    }

    @Test void activeMultiReleaseCannotHideClassCollisionsOrSharedApis() throws Exception {
        var manifest = manifest(); manifest.getMainAttributes().putValue("Multi-Release", "true");
        jar("valid.jar", manifest, "fixture/Driver.class", "META-INF/versions/9/fixture/Driver.class");
        assertNotNull(verify("valid.jar"), "One JAR may legitimately have base and versioned classes");
        jar("other.jar", null, "fixture/Driver.class");
        assertThrows(IllegalArgumentException.class, () -> verify("valid.jar", "other.jar"));
        jar("shared.jar", manifest, "fixture/Driver.class", "META-INF/versions/9/javax/sql/DataSource.class");
        assertThrows(IllegalArgumentException.class, () -> verify("shared.jar"));
        jar("invalid.jar", manifest, "fixture/Driver.class", "META-INF/versions/8/fixture/Driver.class");
        assertThrows(IllegalArgumentException.class, () -> verify("invalid.jar"));
        jar("malformed.jar", manifest, "fixture/Driver.class", "META-INF/versions/not-a-version/fixture/Driver.class");
        assertThrows(IllegalArgumentException.class, () -> verify("malformed.jar"));
    }

    @Test void inactiveVersionResourcesCannotSupplyAnEntryOrCauseFalseClassCollisions() throws Exception {
        jar("only-resource.jar", null, "META-INF/versions/9/fixture/Driver.class");
        assertThrows(IllegalArgumentException.class, () -> verify("only-resource.jar"));
        jar("ordinary.jar", null, "fixture/Driver.class", "META-INF/versions/9/fixture/Helper.class");
        jar("helper.jar", null, "fixture/Helper.class");
        assertNotNull(verify("ordinary.jar", "helper.jar"));
        jar("collision.jar", null, "fixture/Driver.class");
        assertThrows(IllegalArgumentException.class, () -> verify("ordinary.jar", "collision.jar"));
    }

    @Test void inactiveVersionResourcesStillUndergoZipPathAndHashChecks() throws Exception {
        jar("traversal.jar", null, "fixture/Driver.class", "META-INF/versions/9/../fixture/Helper.class");
        assertThrows(IllegalArgumentException.class, () -> verify("traversal.jar"));
        jar("ordinary.jar", null, "fixture/Driver.class", "META-INF/versions/9/fixture/Helper.class");
        assertThrows(IllegalArgumentException.class, () -> DriverBundleVerifier.verify(ProductTypeEnum.HIVE,
                new Spec(directory.toString(), List.of(new Artifact("ordinary.jar", "0".repeat(64))))));
    }

    @Test void actualLoaderUsesTheRootClassAndPlatformApiNotDormantVersionedBytes() throws Exception {
        byte[] base = compiledMarkerClass();
        try (var output = new JarOutputStream(Files.newOutputStream(directory.resolve("ordinary.jar")))) {
            putEntry(output, "fixture/Driver.class", base);
            putEntry(output, "META-INF/versions/9/fixture/Driver.class", new byte[]{0});
            putEntry(output, "META-INF/versions/9/javax/sql/DataSource.class", new byte[]{0});
            putEntry(output, "META-INF/versions/9/fixture/OnlyVersioned.class", base);
        }
        assertNotNull(verify("ordinary.jar"));
        try (var loader = new JarClassLoader(List.of(directory.resolve("ordinary.jar")))) {
            assertEquals("base", loader.loadClass("fixture.Driver").getMethod("marker").invoke(null));
            assertSame(javax.sql.DataSource.class, loader.loadClass("javax.sql.DataSource"));
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("fixture.OnlyVersioned"));
        }
    }

    @Test void activeRootClassStillUndergoesJvmClassVersionValidation() throws Exception {
        byte[] future = compiledMarkerClass();
        int unsupportedMajor = Runtime.version().feature() + 45;
        future[6] = (byte) (unsupportedMajor >>> 8); future[7] = (byte) unsupportedMajor;
        try (var output = new JarOutputStream(Files.newOutputStream(directory.resolve("future.jar")))) {
            putEntry(output, "fixture/Driver.class", future);
            putEntry(output, "META-INF/versions/9/fixture/Driver.class", new byte[]{0});
        }
        assertNotNull(verify("future.jar"));
        try (var loader = new JarClassLoader(List.of(directory.resolve("future.jar")))) {
            assertThrows(UnsupportedClassVersionError.class, () -> loader.loadClass("fixture.Driver"));
        }
    }

    @Test void missingEntryAndWrongHashAreRejected() throws Exception {
        jar("helper.jar", null, "fixture/Helper.class");
        assertThrows(IllegalArgumentException.class, () -> verify("helper.jar"));
        jar("driver.jar", null, "fixture/Driver.class");
        assertThrows(IllegalArgumentException.class, () -> DriverBundleVerifier.verify(ProductTypeEnum.HIVE,
                new Spec(directory.toString(), List.of(new Artifact("driver.jar", "0".repeat(64))))));
    }

    @Test void failedRuntimeInitializationDoesNotPublishAHandleAndRestoresContextLoader() throws Exception {
        jar("driver.jar", null, "fixture/Driver.class");
        var bundle = verify("driver.jar");
        var previous = Thread.currentThread().getContextClassLoader();
        try (var registry = new DriverRuntimeRegistry()) {
            assertThrows(IllegalArgumentException.class, () -> registry.acquire(bundle));
            assertTrue(registry.statuses().isEmpty());
            assertSame(previous, Thread.currentThread().getContextClassLoader());
        }
    }

    private VerifiedDriverBundle verify(String... names) throws Exception {
        var artifacts = new ArrayList<Artifact>();
        for (String name : names) artifacts.add(new Artifact(name, VerifiedDriverTestHandle.sha256(directory.resolve(name))));
        return DriverBundleVerifier.verify(ProductTypeEnum.HIVE, new Spec(directory.toString(), artifacts));
    }
    private static Manifest manifest() { var value = new Manifest(); value.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0"); return value; }
    private byte[] compiledMarkerClass() throws Exception {
        Path source = directory.resolve("source/fixture/Driver.java"); Files.createDirectories(source.getParent());
        Files.writeString(source, "package fixture; public final class Driver { public static String marker() { return \"base\"; } }");
        Path classes = Files.createDirectories(directory.resolve("classes"));
        var compiler = ToolProvider.getSystemJavaCompiler(); assertNotNull(compiler, "The test requires the configured JDK");
        assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(), source.toString()));
        return Files.readAllBytes(classes.resolve("fixture/Driver.class"));
    }
    private static void putEntry(JarOutputStream output, String path, byte[] bytes) throws Exception {
        output.putNextEntry(new JarEntry(path)); output.write(bytes); output.closeEntry();
    }
    private void jar(String name, Manifest manifest, String... entries) throws Exception {
        try (var stream = Files.newOutputStream(directory.resolve(name));
             var output = manifest == null ? new JarOutputStream(stream) : new JarOutputStream(stream, manifest)) {
            for (String entry : entries) { output.putNextEntry(new JarEntry(entry)); output.write(new byte[]{0}); output.closeEntry(); }
        }
    }
    record Artifact(String getFile, String getSha256) implements DriverBundleSpec.Artifact { }
    record Spec(String getBundleRoot, List<DriverBundleVerifierTest.Artifact> getArtifacts) implements DriverBundleSpec {
        @Override public String getPath() { return null; }
        @Override public String getSha256() { return null; }
        @Override public String getDriverClassName() { return "fixture.Driver"; }
        @Override public Lifecycle getLifecycle() { return Lifecycle.LEASED; }
    }
}
