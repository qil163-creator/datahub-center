package cn.iocoder.yudao.framework.dbswitch.driver;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.entity.InvisibleDataSource;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

/** Test-only manifest creation for explicitly selected local fixtures; never a production path bypass. */
public final class VerifiedDriverTestHandle implements AutoCloseable {
    private final Path artifact;
    private final ProductTypeEnum product;
    private final List<ConfiguredJdbcDrivers> owners = new ArrayList<>();
    private final List<InvisibleDataSource> sources = new ArrayList<>();
    public VerifiedDriverTestHandle(Path artifact, ProductTypeEnum product) { this.artifact = artifact; this.product = product; }
    public InvisibleDataSource dataSource(String url, String entry, String username, String password, Properties parameters) throws Exception {
        var spec = new Spec(artifact.toString(), sha256(artifact), entry);
        var owner = new ConfiguredJdbcDrivers(Map.of(product, spec)); owners.add(owner);
        var source = new InvisibleDataSource(owner.find(product).orElseThrow().acquire(), url, username, password, parameters);
        sources.add(source); return source;
    }
    public static String sha256(Path path) throws Exception {
        var hash = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = new byte[65536]; int size;
            while ((size = input.read(bytes)) != -1) hash.update(bytes, 0, size);
        }
        return HexFormat.of().formatHex(hash.digest());
    }
    @Override public void close() { sources.forEach(InvisibleDataSource::close); owners.forEach(ConfiguredJdbcDrivers::close); }
    public record Spec(String getPath, String getSha256, String getDriverClassName) implements DriverBundleSpec {
        @Override public String getBundleRoot() { return null; }
        @Override public List<? extends Artifact> getArtifacts() { return List.of(); }
        @Override public Lifecycle getLifecycle() { return Lifecycle.PROCESS; }
    }
}
