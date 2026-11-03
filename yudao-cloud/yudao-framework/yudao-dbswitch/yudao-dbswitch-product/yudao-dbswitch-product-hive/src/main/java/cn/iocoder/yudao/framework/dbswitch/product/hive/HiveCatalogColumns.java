package cn.iocoder.yudao.framework.dbswitch.product.hive;

import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.HiveNativeCatalog;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Hive 4.1.0 TEXTFILE 目录入口。读取器为 {@link HiveNativeCatalog}。 */
public final class HiveCatalogColumns {

    public static final String VERSION = HiveNativeCatalog.VERSION;

    /**
     * 初始化 {@code HiveCatalogColumns}。
     */
    private HiveCatalogColumns() {
    }

    /**
     * 读取Hive原生目录并构建表结构元数据。
     */
    public static List<ColumnDescription> read(Connection connection, String schema, String table, int timeoutSeconds)
            throws SQLException {
        return HiveNativeCatalog.read(connection, schema, table, timeoutSeconds);
    }
}
