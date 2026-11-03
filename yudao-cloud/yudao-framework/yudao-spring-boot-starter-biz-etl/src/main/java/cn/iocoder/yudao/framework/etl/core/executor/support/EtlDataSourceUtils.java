package cn.iocoder.yudao.framework.etl.core.executor.support;

import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionInfo;
import com.zaxxer.hikari.HikariDataSource;

/**
 * ETL 节点数据源工具：按连接信息创建小型连接池
 *
 * @author Li
 */
public class EtlDataSourceUtils {

    /**
     * 初始化 {@code EtlDataSourceUtils}。
     */
    private EtlDataSourceUtils() {
    }

    /**
     * 根据给定参数创建 {@code HikariDataSource} 实例。
     */
    public static HikariDataSource create(EtlConnectionInfo conn) {
        return create(conn, null);
    }

    /**
     * 根据给定参数创建 {@code HikariDataSource} 实例。
     */
    public static HikariDataSource create(EtlConnectionInfo conn,
            cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime runtime) {
        TargetDataSourceProperties properties = new TargetDataSourceProperties();
        properties.setUrl(conn.getJdbcUrl());
        properties.setDriverClassName(conn.getProductType().getDriveClassName());
        properties.setTargetProductType(conn.getProductType());
        properties.setUsername(conn.getUsername());
        properties.setPassword(conn.getPassword());
        properties.setPoolSize(2);
        if (runtime == null) {
            return DataSourceUtils.createTargetDataSource(properties);
        }
        return DataSourceUtils.createTargetDataSource(properties, null, null,
                runtime.drivers().find(conn.getProductType(), conn.getDriverProfileId()).orElse(null),
                runtime.drivers().runtimes(), runtime.options());
    }

}
