package cn.iocoder.yudao.framework.dbswitch.product.clickhouse;

import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.ClickHouseNativeCatalog;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * ClickHouse 25.8.33.6 列目录的产品入口。
 * {@code system.columns} 查询及行映射由 {@link ClickHouseNativeCatalog} 提供。
 * 此类型不增加事实，核心模块也不得依赖此模块读取事实。
 */
public final class ClickHouseCatalogColumns {

    public static final String SQL = ClickHouseNativeCatalog.SQL;

    /**
     * 初始化 {@code ClickHouseCatalogColumns}。
     */
    private ClickHouseCatalogColumns() {
    }

    /** 将一行 {@code system.columns} 覆盖到 JDBC 列外壳。不设置主键。 */
    public static void apply(ColumnDescription column, ResultSet rows, MetadataProvenance source) throws SQLException {
        ClickHouseNativeCatalog.apply(column, rows, source);
    }
}
