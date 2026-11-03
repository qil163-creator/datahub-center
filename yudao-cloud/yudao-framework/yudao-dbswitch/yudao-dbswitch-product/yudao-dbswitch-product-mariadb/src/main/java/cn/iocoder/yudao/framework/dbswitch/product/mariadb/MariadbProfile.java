package cn.iocoder.yudao.framework.dbswitch.product.mariadb;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.product.mysql.MysqlCompatibleProfile;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.IntUnaryOperator;

/** 共享 MySQL 协议读取器、游标目录凭据及原生 UPSERT 所使用的 MariaDB 事实。 */
public final class MariadbProfile extends MysqlCompatibleProfile {

    public static final MariadbProfile INSTANCE = new MariadbProfile();

    /**
     * 初始化 {@code MariadbProfile}及其父类状态。
     */
    private MariadbProfile() {
        super(ProductTypeEnum.MARIADB, "MariaDB");
    }

    /** MariaDB Connector/J 3.x 拒绝负取数大小，报 "invalid fetch size"，且默认采用流式读取。 */
    @Override
    public int convertFetchSize(int fetchSize, IntUnaryOperator operatorDefault) {
        return Math.max(fetchSize, 0);
    }

    /**
     * 核对连接实际产品与当前实现的产品要求。
     */
    @Override
    public void verifyProduct(Connection connection, int timeout) throws SQLException {
        var metadata = connection.getMetaData();
        if (!"MariaDB".equals(metadata.getDatabaseProductName()) || metadata.getDatabaseMajorVersion() < 10) {
            throw new SQLException("Cursor requires the configured MySQL or MariaDB server product");
        }
    }

    /**
     * 将 {@code schema/table} 映射到物理表编号的 InnoDB 字典视图。
     */
    @Override
    protected String innodbTablesView() {
        return "INNODB_SYS_TABLES";
    }

    /** MariaDB 的 STATISTICS 视图没有函数键组成部分的表达式列。 */
    @Override
    public String statisticsExpressionSql() {
        return "NULL";
    }
}
