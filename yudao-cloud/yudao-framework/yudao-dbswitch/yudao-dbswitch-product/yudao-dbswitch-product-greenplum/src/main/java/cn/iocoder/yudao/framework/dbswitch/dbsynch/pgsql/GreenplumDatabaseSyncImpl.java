package cn.iocoder.yudao.framework.dbswitch.dbsynch.pgsql;

import cn.iocoder.yudao.framework.dbswitch.dbsynch.IDatabaseSynchronize;
import cn.iocoder.yudao.framework.dbswitch.product.greenplum.GreenplumNativeTables;

import java.util.List;
import java.util.Collections;
import java.util.stream.Collectors;

import javax.sql.DataSource;

/**
 * Greenplum数据库DML同步实现类
 *
 * @author jrl
 */
public class GreenplumDatabaseSyncImpl extends PostgresqlDatabaseSyncImpl implements
        IDatabaseSynchronize {

    /**
     * 初始化 {@code GreenplumDatabaseSyncImpl}及其父类状态。
     */
    public GreenplumDatabaseSyncImpl(DataSource ds) {
        super(ds);
    }

    /**
     * 获取查询列元信息的SQL语句。
     */
    @Override
    public String getColumnMetaDataSql(String schema, String table) {
        return "SELECT * FROM " + GreenplumNativeTables.table(schema, table) + " WHERE 1=2";
    }

    /**
     * 生成使用参数占位符的 INSERT 语句。
     */
    @Override
    public String getInsertPrepareStatementSql(String schema, String table, List<String> fields) {
        return "INSERT INTO " + GreenplumNativeTables.table(schema, table) + " (" + GreenplumNativeTables.fields(
                fields) + ") VALUES ("
                + String.join(",", Collections.nCopies(fields.size(), "?")) + ")";
    }

    /**
     * 生成Update操作的SQL语句。
     */
    @Override
    public String getUpdatePrepareStatementSql(String schema, String table, List<String> fields, List<String> keys) {
        String assignments = fields.stream().filter(field -> !keys.contains(field))
                .map(field -> GreenplumNativeTables.quote(field) + "=?").collect(Collectors.joining(","));
        if (assignments.isEmpty()) {
            throw new IllegalArgumentException("Greenplum update requires a non-key field");
        }
        return "UPDATE " + GreenplumNativeTables.table(schema, table) + " SET " + assignments + " WHERE " + where(keys);
    }

    /**
     * 生成Delete操作的SQL语句。
     */
    @Override
    public String getDeletePrepareStatementSql(String schema, String table, List<String> keys) {
        return "DELETE FROM " + GreenplumNativeTables.table(schema, table) + " WHERE " + where(keys);
    }

    /**
     * 返回WHERE 条件。
     */
    private static String where(List<String> keys) {
        GreenplumNativeTables.fields(keys);
        return keys.stream().map(key -> GreenplumNativeTables.quote(key) + "=?").collect(Collectors.joining(" AND "));
    }

}
