package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import cn.iocoder.yudao.framework.dbswitch.driver.DriverNativeAccess;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 仅提供固定的 pgjdbc 构件和原生加载器证据，不授予服务器或表能力。 */
public final class PostgresCopyDriverProfile {

    private static final String DRIVER_PREFIX = "PostgreSQL JDBC Driver ";
    private static final Map<String, String> ARTIFACTS = Map.of(
            "42.7.13", "6e0e4cc2d8cae902084f8a2b18728b073a6fd9d1f87c9d8bff8f298c18185b93",
            "42.7.14", "73914527305a40cce504b0d3d90b23caf565136912d607ae8ae7c5895512332c");

    /**
     * 初始化 {@code PostgresCopyDriverProfile}。
     */
    private PostgresCopyDriverProfile() {
    }

    private static final ClassValue<Optional<String>> VERIFIED_VERSION = new ClassValue<>() {
        /**
         * 根据当前规则和输入字段计算结果值。
         */
        @Override
        protected Optional<String> computeValue(Class<?> driverClass) {
            try {
                if (!(driverClass.getMethod("getVersion").invoke(null) instanceof String name)
                        || !name.startsWith(DRIVER_PREFIX)) {
                    return Optional.empty();
                }
                String version = name.substring(DRIVER_PREFIX.length());
                String expectedDigest = ARTIFACTS.get(version);
                if (expectedDigest == null) {
                    return Optional.empty();
                }
                var source = driverClass.getProtectionDomain().getCodeSource();
                if (source == null || !"file".equals(source.getLocation().getProtocol())) {
                    return Optional.empty();
                }
                Path artifact = Path.of(source.getLocation().toURI());
                if (!Files.isRegularFile(artifact) || Files.size(artifact) > 16 * 1024 * 1024) {
                    return Optional.empty();
                }
                var digest = MessageDigest.getInstance("SHA-256");
                try (var input = Files.newInputStream(artifact)) {
                    byte[] buffer = new byte[8192];
                    for (int count; (count = input.read(buffer)) != -1; ) {
                        digest.update(buffer, 0, count);
                    }
                }
                return expectedDigest.equals(HexFormat.of().formatHex(digest.digest()))
                        ? Optional.of(version) : Optional.empty();
            } catch (Exception | LinkageError unavailable) {
                return Optional.empty();
            }
        }
    };


    /**
     * 返回当前驱动验证所对应的文件工件。
     */
    static boolean testedArtifact(Class<?> driver) {
        return VERIFIED_VERSION.get(driver).isPresent();
    }

    /**
     * 检查数据库连接是否满足当前操作的约定。
     */
    public static void verify(Connection connection) throws SQLException {
        verify(connection, ARTIFACTS.keySet());
    }

    /** 兼容产品只能使用已在该产品上验证过的驱动版本。 */
    public static void verify(Connection connection, Set<String> qualifiedVersions) throws SQLException {
        try {
            var metadata = connection.getMetaData();
            String reportedVersion = metadata.getDriverVersion();
            if (!"PostgreSQL".equals(metadata.getDatabaseProductName())
                    || !"PostgreSQL JDBC Driver".equals(metadata.getDriverName())
                    || !qualifiedVersions.contains(reportedVersion)) {
                throw new SQLException();
            }
            Object nativeConnection = DriverNativeAccess.unwrap(connection, "org.postgresql.PGConnection");
            if (nativeConnection == null) {
                throw new SQLException();
            }
            ClassLoader loader = nativeConnection.getClass().getClassLoader();
            Class<?> pg = Class.forName("org.postgresql.PGConnection", false, loader);
            Class<?> driver = Class.forName("org.postgresql.Driver", false, loader);
            if (!pg.isInstance(nativeConnection) || VERIFIED_VERSION.get(driver).filter(reportedVersion::equals).isEmpty()) {
                throw new SQLException();
            }
        } catch (SQLException | ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
            throw new SQLException("Binary COPY requires the tested pgjdbc artifact and native runtime interface",
                    "0A000");
        }
    }
}
