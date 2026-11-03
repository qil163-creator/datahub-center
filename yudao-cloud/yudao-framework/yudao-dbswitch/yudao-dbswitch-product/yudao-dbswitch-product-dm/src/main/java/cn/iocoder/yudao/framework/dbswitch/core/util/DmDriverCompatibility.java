package cn.iocoder.yudao.framework.dbswitch.core.util;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.regex.Pattern;

/** 排除已知有缺陷的 DM 驱动；通过此检查不代表认证其他版本。 */
public final class DmDriverCompatibility {

    private static final Pattern BROKEN_VERSION = Pattern.compile("(?<![0-9.])8\\.1\\.0\\.147(?![0-9.])");

    /**
     * 初始化 {@code DmDriverCompatibility}。
     */
    private DmDriverCompatibility() {
    }

    /** 调用方保留其连接的所有权，包括失败路径。 */
    public static void require(Connection connection) throws SQLException {
        String version;
        try {
            version = connection.getMetaData().getDriverVersion();
        } catch (SQLException | RuntimeException failure) {
            throw new SQLException("DM migration driver metadata is unavailable", "08001");
        }
        if (version != null && BROKEN_VERSION.matcher(version).find()) {
            throw new SQLException(
                    "DM driver 8.1.0.147 cannot perform lossless migration; use a validated driver bundle", "0A000");
        }
    }

    /** 持有一个短生命周期连接；驱动异常及被抑制的关闭失败均保持私有。 */
    public static void require(DataSource source) {
        try (var connection = source.getConnection()) {
            require(connection);
        } catch (SQLException | RuntimeException failure) {
            throw new IllegalStateException("DM migration driver preflight failed");
        }
    }
}
