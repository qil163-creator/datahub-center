package cn.iocoder.yudao.framework.dbswitch.dbwriter.olap;

import cn.iocoder.yudao.framework.dbswitch.common.entity.InvisibleDataSource;
import cn.iocoder.yudao.framework.dbswitch.driver.DriverNativeAccess;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLFeatureNotSupportedException;
import java.util.HexFormat;

/** 单个已观察的原生 Stream Load 配置，绝非整种产品的能力认证。 */
public final class OlapStreamLoadAdmission {

    private static final String DRIVER = "com.mysql.cj.jdbc.Driver";

    private static final String DRIVER_SHA = "0353648eaa1c91e0f4020c959abf756bc866ffd583df22ae6b6f6e0cbd43eb44";

    private static final ClassValue<Boolean> TESTED_DRIVER = new ClassValue<>() {
        /**
         * 根据当前规则和输入字段计算结果值。
         */
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                var source = type.getProtectionDomain().getCodeSource();
                if (!DRIVER.equals(type.getName()) || source == null || !"file".equals(
                        source.getLocation().getProtocol())) {
                    return false;
                }
                Path artifact = Path.of(source.getLocation().toURI());
                if (!Files.isRegularFile(artifact) || Files.size(artifact) > 16 * 1024 * 1024) {
                    return false;
                }
                var digest = MessageDigest.getInstance("SHA-256");
                try (var input = Files.newInputStream(artifact)) {
                    byte[] bytes = new byte[8192];
                    for (int count; (count = input.read(bytes)) != -1; ) {
                        digest.update(bytes, 0, count);
                    }
                }
                return DRIVER_SHA.equals(HexFormat.of().formatHex(digest.digest()));
            } catch (Exception | LinkageError invalid) {
                return false;
            }
        }
    };

    /**
     * 初始化 {@code OlapStreamLoadAdmission}。
     */
    private OlapStreamLoadAdmission() {
    }

    /** 借用连接或发现 HTTP 端点前，拒绝未知数据源适配器。 */
    public static void requireSource(DataSource source, String product) {
        if (Thread.currentThread().isInterrupted()) {
            throw new cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException(
                    "Stream Load cancelled before submission");
        }
        if (!(source instanceof HikariDataSource || source instanceof InvisibleDataSource)) {
            unsupported(product);
        }
    }

    /** 调用方拥有并关闭此连接。不改变会话或事务。 */
    public static void verify(Connection connection, String product, int timeout) {
        try {
            String version = OlapStreamLoadProfiles.of(product).admittedVersion();
            if (version == null) {
                throw new IllegalArgumentException();
            }
            if (timeout < 1 || !connection.getAutoCommit() || connection.isReadOnly()
                    || Thread.currentThread().isInterrupted()) {
                throw new IllegalArgumentException();
            }
            var metadata = connection.getMetaData();
            if (!"MySQL Connector/J".equals(metadata.getDriverName())
                    || !metadata.getDriverVersion().startsWith("mysql-connector-j-9.7.0 (Revision: ")) {
                throw new IllegalArgumentException();
            }
            Object nativeConnection = DriverNativeAccess.unwrap(connection, "com.mysql.cj.jdbc.JdbcConnection");
            if (nativeConnection == null) {
                throw new IllegalArgumentException();
            }
            Class<?> actual = nativeConnection.getClass();
            Class<?> driver = Class.forName(DRIVER, false, actual.getClassLoader());
            if (!TESTED_DRIVER.get(driver) || actual.getClassLoader() != driver.getClassLoader()
                    || actual.getProtectionDomain().getCodeSource() == null
                    || !actual.getProtectionDomain().getCodeSource().getLocation()
                    .equals(driver.getProtectionDomain().getCodeSource().getLocation())) {
                throw new IllegalArgumentException();
            }
            try (var query = connection.createStatement()) {
                query.setQueryTimeout(Math.min(timeout, 5));
                query.setMaxRows(257);
                for (String catalog : new String[]{"SHOW FRONTENDS", "SHOW BACKENDS"}) {
                    try (var rows = query.executeQuery(catalog)) {
                        int count = 0;
                        while (rows.next()) {
                            if (++count > 256 || !rows.getBoolean("Alive") || !version.equals(
                                    rows.getString("Version"))) {
                                throw new IllegalArgumentException();
                            }
                        }
                        if (count == 0) {
                            throw new IllegalArgumentException();
                        }
                    }
                }
            }
        } catch (Exception | LinkageError invalid) {
            unsupported(product);
        }
    }

    /**
     * 返回当前操作或类型不受支持的结果。
     */
    @SuppressWarnings("unchecked")
    public static <E extends Throwable> void unsupported(String product) throws E {
        throw (E) new SQLFeatureNotSupportedException(
                product + " WRITE_COPY runtime profile, driver or endpoint is not verified", "0A000");
    }
}
