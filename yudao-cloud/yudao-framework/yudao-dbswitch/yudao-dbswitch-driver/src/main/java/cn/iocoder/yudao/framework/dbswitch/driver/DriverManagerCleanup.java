package cn.iocoder.yudao.framework.dbswitch.driver;

import java.sql.DriverManager;
import java.sql.SQLException;

/** 在隔离类加载器内再次定义，使 DriverManager 的调用方类加载器检查具有实际意义。 */
public final class DriverManagerCleanup {

    /**
     * 初始化 {@code DriverManagerCleanup}。
     */
    private DriverManagerCleanup() {
    }

    /**
     * 注销当前类加载器或运行时拥有的 JDBC 驱动。
     */
    public static void deregisterOwnedDrivers() throws SQLException {
        ClassLoader owner = DriverManagerCleanup.class.getClassLoader();
        var drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            var driver = drivers.nextElement();
            if (driver.getClass().getClassLoader() == owner) {
                DriverManager.deregisterDriver(driver);
            }
        }
    }
}
