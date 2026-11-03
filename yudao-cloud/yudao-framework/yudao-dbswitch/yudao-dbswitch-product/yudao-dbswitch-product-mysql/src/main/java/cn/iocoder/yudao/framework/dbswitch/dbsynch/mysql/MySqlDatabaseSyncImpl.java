package cn.iocoder.yudao.framework.dbswitch.dbsynch.mysql;

import cn.iocoder.yudao.framework.dbswitch.dbsynch.AbstractDatabaseSynchronize;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.IDatabaseSynchronize;
import org.apache.commons.lang3.StringUtils;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MySQL数据库DML同步实现类
 *
 * @author jrl
 */
public class MySqlDatabaseSyncImpl extends AbstractDatabaseSynchronize implements
        IDatabaseSynchronize {

    /**
     * 初始化 {@code MySqlDatabaseSyncImpl}及其父类状态。
     */
    public MySqlDatabaseSyncImpl(DataSource ds) {
        super(ds);
    }

    /**
     * 获取查询列元信息的SQL语句。
     */
    @Override
    public String getColumnMetaDataSql(String schemaName, String tableName) {
        return "SELECT * FROM " + relation(schemaName, tableName) + " WHERE 1=2";
    }

    /**
     * 生成使用参数占位符的 INSERT 语句。
     */
    @Override
    public String getInsertPrepareStatementSql(String schemaName, String tableName,
            List<String> fieldNames) {
        List<String> placeHolders = Collections.nCopies(fieldNames.size(), "?");
        return "INSERT INTO " + relation(schemaName, tableName) + " (" + fieldNames.stream()
                .map(MySqlDatabaseSyncImpl::quote)
                .collect(Collectors.joining(",")) + ") VALUES (" + String.join(",", placeHolders) + ")";
    }

    /**
     * 生成Update操作的SQL语句。
     */
    @Override
    public String getUpdatePrepareStatementSql(String schemaName, String tableName,
            List<String> fieldNames, List<String> pks) {
        List<String> uf = fieldNames.stream()
                .filter(field -> !pks.contains(field))
                .map(field -> quote(field) + "=?")
                .collect(Collectors.toList());

        List<String> uw = pks.stream()
                .map(pk -> quote(pk) + "=?")
                .collect(Collectors.toList());

        if (pks.isEmpty()) {
            throw new IllegalArgumentException("Difference writes require matching keys");
        }
        if (uf.isEmpty()) {
            uf.add(quote(pks.getFirst()) + "=" + quote(pks.getFirst()));
        }
        return "UPDATE " + relation(schemaName, tableName) + " SET " + String.join(",", uf) + " WHERE " + String.join(
                " AND ", uw);
    }

    /**
     * 生成Delete操作的SQL语句。
     */
    @Override
    public String getDeletePrepareStatementSql(String schemaName, String tableName,
            List<String> pks) {
        List<String> uw = pks.stream()
                .map(pk -> quote(pk) + "=?")
                .collect(Collectors.toList());

        if (pks.isEmpty()) {
            throw new IllegalArgumentException("Difference writes require matching keys");
        }
        return "DELETE FROM " + relation(schemaName, tableName) + " WHERE " + String.join(" AND ", uw);
    }

    /**
     * 按MySQL方言转义并引用标识符。
     */
    private static String quote(String name) {
        return cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard.quotePhysicalIdentifier(
                cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.MYSQL, name);
    }

    /**
     * 生成模式与表组成的限定关系名。
     */
    private static String relation(String schema, String table) {
        return quote(schema) + "." + quote(table);
    }
}
