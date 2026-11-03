package cn.iocoder.yudao.module.dataforge.service.push;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/**
 * 源库查询只读：JDBC URL 提示 + 连接 {@code setReadOnly}。
 * 不替代 SQL 校验，只降低自定义 SELECT 被绕过时的写风险。
 *
 * @author Li
 */
public final class DataPushReadOnlySupport {

    /**
     * 限制数据推送只读支持工具的实例化，统一通过静态能力使用。
     */
    private DataPushReadOnlySupport() {
    }

    /**
     * 应用URL。
     *
     * @param productType 产品类型
     * @param jdbcUrl JDBCURL
     * @return 应用URL文本
     */
    public static String applyUrl(ProductTypeEnum productType, String jdbcUrl) {
        if (StrUtil.isBlank(jdbcUrl)) {
            return jdbcUrl;
        }
        String url = jdbcUrl.trim();
        String lower = url.toLowerCase(Locale.ROOT);
        if (productType != null && productType.isLikePostgres()) {
            return appendParam(url, lower, "readonly=true", "readonly=");
        }
        if (productType != null && (productType.isLikeMysql() || productType == ProductTypeEnum.OCEANBASE)) {
            if (lower.contains("transaction_read_only")) {
                return url;
            }
            return appendParam(url, lower, "sessionVariables=transaction_read_only=ON", "transaction_read_only");
        }
        return url;
    }

    /**
     * 包装数据推送只读支持。
     *
     * @param dataSource 数据源
     * @return 数据源
     */
    public static DataSource wrap(DataSource dataSource) {
        if (dataSource == null) {
            return null;
        }
        return new DelegatingDataSource(dataSource) {
            /**
             * 读取连接。
             *
             * @return 连接
             */
            @Override
            public Connection getConnection() throws SQLException {
                return markReadOnly(super.getConnection());
            }

            /**
             * 读取连接。
             *
             * @param username 用户名
             * @param password 密码
             * @return 连接
             */
            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                return markReadOnly(super.getConnection(username, password));
            }
        };
    }

    /**
     * 标记只读。
     *
     * @param connection 数据库连接
     * @return 连接
     */
    public static Connection markReadOnly(Connection connection) {
        if (connection == null) {
            return null;
        }
        try {
            connection.setReadOnly(true);
        } catch (Exception ignored) {
            // 部分驱动不支持，仍依赖 URL 提示与 SELECT 校验
        }
        return connection;
    }

    /**
     * 追加参数。
     *
     * @param url URL
     * @param lower 小写
     * @param param 参数
     * @param existPrefix 存在性前缀
     * @return 追加参数文本
     */
    static String appendParam(String url, String lower, String param, String existPrefix) {
        if (lower.contains(existPrefix)) {
            return url;
        }
        if (lower.startsWith("jdbc:sqlserver:")) {
            return url.endsWith(";") ? url + param : url + ";" + param;
        }
        if (url.contains("?")) {
            return url.endsWith("?") || url.endsWith("&") ? url + param : url + "&" + param;
        }
        return url + "?" + param;
    }

}
