package cn.iocoder.yudao.framework.dbswitch.driver;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

/** 对显式有序制品列表进行有界校验；绝不扫描目录发现依赖。 */
public final class DriverBundleVerifier {

    private static final long MAX_JAR_BYTES = 256L * 1024 * 1024;

    private static final long MAX_BUNDLE_BYTES = 1024L * 1024 * 1024;

    private static final int MAX_ARTIFACTS = 32;

    private static final int MAX_ENTRIES = 100_000;

    private static final int MAX_INDEX_BYTES = 64 * 1024;

    /**
     * 初始化 {@code DriverBundleVerifier}。
     */
    private DriverBundleVerifier() {
    }

    /**
     * 检查数据库产品、规格是否满足当前操作的约定。
     */
    public static VerifiedDriverBundle verify(ProductTypeEnum product, DriverBundleSpec spec) {
        if (product == null || product == ProductTypeEnum.UNKNOWN || spec == null) {
            throw invalid("known product and configuration required");
        }
        String entry = hasText(spec.getDriverClassName()) ? spec.getDriverClassName()
                .trim() : product.getDriveClassName();
        if (entry == null || !entry.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")) {
            throw invalid("invalid driver class");
        }
        var artifacts = new ArrayList<VerifiedDriverBundle.Artifact>();
        try {
            var declared = spec.getArtifacts();
            if (declared != null && !declared.isEmpty()) {
                if (hasText(spec.getPath()) || hasText(spec.getSha256())) {
                    throw invalid("single-artifact and bundle inputs cannot be combined");
                }
                if (declared.size() > MAX_ARTIFACTS) {
                    throw invalid("too many artifacts");
                }
                Path root = absolutePath(spec.getBundleRoot()).toRealPath();
                if (!Files.isDirectory(root)) {
                    throw invalid("bundle root must be a directory");
                }
                for (var item : declared) {
                    if (item == null || !hasText(item.getFile())) {
                        throw invalid("artifact file is required");
                    }
                    Path relative = Path.of(item.getFile());
                    if (relative.isAbsolute() || relative.normalize()
                            .startsWith("..") || relative.getNameCount() == 0) {
                        throw invalid("artifact escapes its bundle root");
                    }
                    Path path = root.resolve(relative).normalize();
                    if (!path.startsWith(root) || !path.toRealPath().equals(path)) {
                        throw invalid("artifact path or symlink escapes its declaration");
                    }
                    artifacts.add(artifact(path, item.getSha256()));
                }
            } else {
                if (hasText(spec.getBundleRoot())) {
                    throw invalid("bundle root requires an artifact list");
                }
                Path path = absolutePath(spec.getPath()).normalize();
                if (!path.toRealPath().equals(path)) {
                    throw invalid("artifact symlinks are not permitted");
                }
                artifacts.add(artifact(path, spec.getSha256()));
            }
        } catch (IOException | java.nio.file.InvalidPathException failure) {
            throw invalid("driver file is unavailable");
        }
        verifyArtifacts(artifacts, entry);
        DriverBundleSpec.Lifecycle lifecycle = spec.getLifecycle() == null ? DriverBundleSpec.Lifecycle.PROCESS : spec.getLifecycle();
        var identity = new StringBuilder("DBSWITCH_BUNDLE_V2\0").append(product.name()).append('\0')
                .append(entry).append('\0').append(lifecycle).append("\0PLATFORM_PARENT_V1");
        for (var artifact : artifacts) {
            identity.append('\0').append(artifact.sha256());
        }
        return new VerifiedDriverBundle(product, entry, artifacts,
                digest(identity.toString().getBytes(StandardCharsets.UTF_8)), lifecycle);
    }

    /**
     * 将当前输入路径转换为规范的绝对路径。
     */
    private static Path absolutePath(String text) {
        if (!hasText(text)) {
            throw invalid("an absolute .jar path or bundle root is required");
        }
        Path path = Path.of(text);
        if (!path.isAbsolute()) {
            throw invalid("an absolute .jar path or bundle root is required");
        }
        return path;
    }

    /**
     * 根据当前输入构建并返回 {@code VerifiedDriverBundle.Artifact}。
     */
    private static VerifiedDriverBundle.Artifact artifact(Path path, String hash) throws IOException {
        if (hash == null || !hash.matches("[a-fA-F0-9]{64}")) {
            throw invalid("expected SHA-256 is required");
        }
        if (path.getFileName() == null || !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw invalid("an absolute standalone .jar path is required");
        }
        return new VerifiedDriverBundle.Artifact(path, hash.toLowerCase(Locale.ROOT), Files.size(path));
    }

    /**
     * 校验驱动包文件存在且其摘要与声明一致。
     */
    static void verifyArtifacts(List<VerifiedDriverBundle.Artifact> artifacts, String entryClass) {
        var paths = new HashSet<Path>();
        var classOwners = new HashMap<String, Path>();
        long totalBytes = 0, expandedBytes = 0;
        int entryCount = 0;
        boolean driverPresent = false;
        for (var artifact : artifacts) {
            if (!paths.add(artifact.path())) {
                throw invalid("duplicate artifact path");
            }
            totalBytes += verifyHash(artifact.path(), artifact.sha256());
            if (totalBytes > MAX_BUNDLE_BYTES) {
                throw invalid("bundle exceeds total size limit");
            }
            try (var jar = new JarFile(artifact.path().toFile(), false)) {
                var manifestEntry = jar.getJarEntry("META-INF/MANIFEST.MF");
                if (manifestEntry != null && (manifestEntry.getSize() < 0 || manifestEntry.getSize() > 1024 * 1024)) {
                    throw invalid("manifest exceeds size limit");
                }
                var manifest = jar.getManifest();
                String classPath = manifest == null ? null : manifest.getMainAttributes()
                        .getValue(Attributes.Name.CLASS_PATH);
                if (hasText(classPath)) {
                    throw invalid("external JAR dependencies are not pinned");
                }
                verifySelfIndex(jar, artifact.path().getFileName().toString());
                boolean multiRelease = manifest != null && "true".equalsIgnoreCase(
                        manifest.getMainAttributes().getValue("Multi-Release"));
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    var item = entries.nextElement();
                    if (++entryCount > MAX_ENTRIES) {
                        throw invalid("bundle exceeds ZIP entry limit");
                    }
                    if (item.isDirectory()) {
                        continue;
                    }
                    if (item.getSize() < 0 || item.getSize() > MAX_JAR_BYTES) {
                        throw invalid("invalid expanded entry size");
                    }
                    expandedBytes += item.getSize();
                    if (expandedBytes > 2 * MAX_BUNDLE_BYTES) {
                        throw invalid("bundle exceeds expanded size limit");
                    }
                    String name = item.getName();
                    if (name.startsWith("/") || name.contains("../") || name.contains("\\")) {
                        throw invalid("invalid JAR entry path");
                    }
                    if (!name.endsWith(".class")) {
                        continue;
                    }
                    if (name.startsWith("META-INF/versions/")) {
                        // 经过依赖打包的普通 JAR 可能保留依赖版本目录，但未
                        // 启用多版本选择。JVM 将它们视为资源：
                        // 这些目录不能提供驱动入口，也不能遮蔽根类。上方哈希、ZIP
                        // 路径和展开大小校验仍覆盖其内容。
                        if (!multiRelease) {
                            continue;
                        }
                        String[] parts = name.split("/", 4);
                        if (parts.length != 4 || !parts[2].matches("[0-9]{1,3}")) {
                            throw invalid("invalid multi-release class");
                        }
                        int version = Integer.parseInt(parts[2]);
                        if (version < 9) {
                            throw invalid("invalid multi-release class version");
                        }
                        if (version > Runtime.version().feature()) {
                            continue;
                        }
                        name = parts[3];
                    }
                    if (name.equals("module-info.class")) {
                        continue;
                    }
                    if (name.startsWith("java/") || name.startsWith("javax/sql/") || name.startsWith("jdk/")
                            || name.startsWith("cn/iocoder/yudao/framework/dbswitch/driver/")
                            || name.startsWith("cn/iocoder/yudao/framework/dbswitch/spi/")) {
                        throw invalid("bundle duplicates a shared API class");
                    }
                    Path previous = classOwners.putIfAbsent(name, artifact.path());
                    if (previous != null && !previous.equals(artifact.path())) {
                        throw invalid("duplicate class across artifacts");
                    }
                    if (name.equals(entryClass.replace('.', '/') + ".class")) {
                        driverPresent = true;
                    }
                }
            } catch (IOException failure) {
                throw invalid("driver is not a readable JAR");
            }
        }
        if (!driverPresent) {
            throw invalid("driver class is absent from the pinned JAR bundle");
        }
    }

    /** 原始厂商 JAR 可以索引自身，但绝不能扩展已验证的 URL 列表。 */
    private static void verifySelfIndex(JarFile jar, String fileName) throws IOException {
        var entry = jar.getJarEntry("META-INF/INDEX.LIST");
        if (entry == null) {
            return;
        }
        if (entry.getSize() < 0 || entry.getSize() > MAX_INDEX_BYTES) {
            throw invalid("JAR index exceeds size limit");
        }
        byte[] bytes;
        try (var input = jar.getInputStream(entry)) {
            bytes = input.readNBytes(MAX_INDEX_BYTES + 1);
        }
        if (bytes.length > MAX_INDEX_BYTES) {
            throw invalid("JAR index exceeds size limit");
        }
        String index = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        String[] lines = index.split("\\r?\\n", -1);
        if (lines.length < 4 || !lines[0].equals("JarIndex-Version: 1.0") || !lines[1].isEmpty()
                || !fileName.matches("[A-Za-z0-9_$.-]+\\.jar") || !lines[2].equals(fileName)) {
            throw invalid("JAR index must describe only its own artifact");
        }
        boolean ended = false;
        for (int i = 3; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                ended = true;
                continue;
            }
            // 第二行 .jar 即表示新的搜索 URL，即使中间没有空行分隔。
            if (ended || line.toLowerCase(Locale.ROOT).endsWith(".jar")
                    || !line.matches("[A-Za-z0-9_$.-]+(/[A-Za-z0-9_$.-]+)*")) {
                throw invalid("external or malformed JAR index dependency");
            }
            for (String part : line.split("/")) {
                if (part.equals(".") || part.equals("..")) {
                    throw invalid("invalid JAR index path");
                }
            }
        }
    }

    /**
     * 校验哈希。
     */
    public static long verifyHash(Path path, String expected) {
        try {
            if (!Files.isRegularFile(path) || !Files.isReadable(path) || Files.size(path) > MAX_JAR_BYTES) {
                throw invalid("driver must be a readable file no larger than 256 MiB");
            }
            MessageDigest hash = sha256();
            long bytes = 0;
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    bytes += count;
                    if (bytes > MAX_JAR_BYTES) {
                        throw invalid("driver exceeds size limit");
                    }
                    hash.update(buffer, 0, count);
                }
            }
            if (!expected.equals(HexFormat.of().formatHex(hash.digest()))) {
                throw invalid("driver SHA-256 mismatch");
            }
            return bytes;
        } catch (IOException failure) {
            throw invalid("driver file is unavailable");
        }
    }

    /**
     * 根据当前内容计算摘要，供一致性比较使用。
     */
    public static String digest(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    /**
     * 计算输入内容的 SHA-256 摘要。
     */
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    /**
     * 判断是否具有文本。
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("Configured JDBC driver: " + reason);
    }
}
