package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;

import java.sql.Connection;
import java.util.List;

/**
 * PostgreSQL 工具类,根据表的字段元数据与主键信息生成建表 DDL 语句。
 */
public final class PostgresUtils {

    /**
     * 生成指定表的建表 DDL。
     */
    public static String getTableDDL(Connection connection, String schema, String table) {
        AbstractDatabase db = new cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl();
        List<ColumnDescription> columnDescriptions = db.queryTableColumnMeta(connection, schema, table);
        List<String> pks = db.queryTablePrimaryKeys(connection, schema, table);
        return GenerateSqlUtils.getDDLCreateTableSQL(
                db, columnDescriptions, pks, schema, table, false);
    }

    /**
     * 校验构造参数并初始化 {@code PostgresUtils}。
     */
    private PostgresUtils() {
        throw new IllegalStateException();
    }
}
