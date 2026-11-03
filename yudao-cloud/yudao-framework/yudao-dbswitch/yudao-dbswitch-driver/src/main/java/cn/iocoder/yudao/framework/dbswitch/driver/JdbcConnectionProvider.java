package cn.iocoder.yudao.framework.dbswitch.driver;

import java.sql.Connection;
import java.sql.SQLException;

/** 显式运行时连接依赖。实现必须保留物理关闭所有权。 */
@FunctionalInterface
public interface JdbcConnectionProvider {

    /**
     * 根据当前驱动及连接配置建立数据库连接。
     */
    Connection open(String jdbcUrl, String username, String password) throws SQLException;
}
