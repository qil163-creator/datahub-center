package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import cn.iocoder.yudao.framework.dbswitch.driver.DriverNativeAccess;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;

/** 仅提供固定的 pgjdbc 构件和原生加载器证据，不授予服务器或表能力。 */
public final class PostgresCopyDriverProfile {

    private static final String DRIVER_VERSION = "42.7.13";

    private static final String DRIVER_SHA256 = "6e0e4cc2d8cae902084f8a2b18728b073a6fd9d1f87c9d8bff8f298c18185b93";

    /**
     * 初始化 {@code PostgresCopyDriverProfile}。
     */
    private PostgresCopyDriverProfile() {
    }

    private static final ClassValue<Boolean> TESTED_DRIVER = new ClassValue<>() {
        /**
         * 根据当前规则和输入字段计算结果值。
         */
        @Override
        protected Boolean computeValue(Class<?> driverClass) {
            try {
                if (!("PostgreSQL JDBC Driver " + DRIVER_VERSION).equals(
                        driverClass.getMethod("getVersion").invoke(null))) {
                    return false;
                }
                var source = driverClass.getProtectionDomain().getCodeSource();
                if (source == null || !"file".equals(source.getLocation().getProtocol())) {
                    return false;
                }
                Path artifact = Path.of(source.getLocation().toURI());
                if (!Files.isRegularFile(artifact) || Files.size(artifact) > 16 * 1024 * 1024) {
                    return false;
                }
                var digest = MessageDigest.getInstance("SHA-256");
                try (var input = Files.newInputStream(artifact)) {
                    byte[] buffer = new byte[8192];
                    for (int count; (count = input.read(buffer)) != -1; ) {
                        digest.update(buffer, 0, count);
                    }
                }
                return DRIVER_SHA256.equals(HexFormat.of().formatHex(digest.digest()));
            } catch (Exception | LinkageError unavailable) {
                return false;
            }
        }
    };


    /**
     * 返回当前驱动验证所对应的文件工件。
     */
    static boolean testedArtifact(Class<?> driver) {
        return TESTED_DRIVER.get(driver);
    }

    /**
     * 检查数据库连接是否满足当前操作的约定。
     */
    public static void verify(Connection connection) throws SQLException {
        try {
            var metadata = connection.getMetaData();
            if (!"PostgreSQL".equals(metadata.getDatabaseProductName())
                    || !"PostgreSQL JDBC Driver".equals(metadata.getDriverName())
                    || !DRIVER_VERSION.equals(metadata.getDriverVersion())) {
                throw new SQLException();
            }
            Object nativeConnection = DriverNativeAccess.unwrap(connection, "org.postgresql.PGConnection");
            if (nativeConnection == null) {
                throw new SQLException();
            }
            ClassLoader loader = nativeConnection.getClass().getClassLoader();
            Class<?> pg = Class.forName("org.postgresql.PGConnection", false, loader);
            Class<?> driver = Class.forName("org.postgresql.Driver", false, loader);
            if (!pg.isInstance(nativeConnection) || !TESTED_DRIVER.get(driver)) {
                throw new SQLException();
            }
        } catch (SQLException | ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
            throw new SQLException("Binary COPY requires the tested pgjdbc artifact and native runtime interface",
                    "0A000");
        }
    }
}
