package cn.iocoder.yudao.framework.dbswitch.product.sqlite3;

import cn.iocoder.yudao.framework.dbswitch.core.model.TableSchemaMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.SqliteNativeCatalog;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 已验证的 SQLite 文件目录，委托给 {@link SqliteNativeCatalog}；公开入口仍为
 * {@link #read(Connection, String, String, int)}。
 */
public final class Sqlite3CatalogColumns {

    /**
     * 初始化 {@code Sqlite3CatalogColumns}。
     */
    private Sqlite3CatalogColumns() {
    }

    /**
     * 读取单张普通表。不提交，也不保留连接。
     * Xerial 3.31 的 {@code setQueryTimeout} 会覆盖 {@code PRAGMA busy_timeout}；之后恢复原值。
     */
    public static TableSchemaMetadata read(Connection connection, String schema, String table, int timeoutSeconds)
            throws SQLException {
        return SqliteNativeCatalog.read(connection, schema, table, timeoutSeconds);
    }
}
