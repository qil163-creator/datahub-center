package cn.iocoder.yudao.framework.dbswitch.dbsynch.oscar;


import cn.iocoder.yudao.framework.dbswitch.dbsynch.AbstractDatabaseSynchronize;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.IDatabaseSynchronize;
import cn.iocoder.yudao.framework.dbswitch.product.oscar.OscarSqlNames;
import org.apache.commons.lang3.StringUtils;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 神通数据库DML同步实现类
 *
 * @author tang
 */
public class OscarDatabaseSyncImpl extends AbstractDatabaseSynchronize implements
        IDatabaseSynchronize {

    /**
     * 初始化 {@code OscarDatabaseSyncImpl}及其父类状态。
     */
    public OscarDatabaseSyncImpl(DataSource ds) {
        super(ds);
    }

    /**
     * 获取查询列元信息的SQL语句。
     */
    @Override
    public String getColumnMetaDataSql(String schemaName, String tableName) {
        return "SELECT * FROM " + OscarSqlNames.relation(schemaName, tableName) + " WHERE 1=2";
    }

    /**
     * 生成使用参数占位符的 INSERT 语句。
     */
    @Override
    public String getInsertPrepareStatementSql(String schemaName, String tableName,
            List<String> fieldNames) {
        List<String> placeHolders = Collections.nCopies(fieldNames.size(), "?");
        return "INSERT INTO " + OscarSqlNames.relation(schemaName, tableName) + " (" + OscarSqlNames.fields(fieldNames)
                + ") VALUES (" + String.join(",", placeHolders) + ")";
    }

    /**
     * 生成Update操作的SQL语句。
     */
    @Override
    public String getUpdatePrepareStatementSql(String schemaName, String tableName,
            List<String> fieldNames, List<String> pks) {
        List<String> uf = fieldNames.stream()
                .filter(field -> !pks.contains(field))
                .map(field -> OscarSqlNames.quote(field) + "=?")
                .collect(Collectors.toList());

        List<String> uw = pks.stream()
                .map(pk -> OscarSqlNames.quote(pk) + "=?")
                .collect(Collectors.toList());

        return String.format("UPDATE %s SET %s WHERE %s",
                OscarSqlNames.relation(schemaName, tableName), StringUtils.join(uf, " , "),
                StringUtils.join(uw, " AND "));
    }

    /**
     * 生成Delete操作的SQL语句。
     */
    @Override
    public String getDeletePrepareStatementSql(String schemaName, String tableName,
            List<String> pks) {
        List<String> uw = pks.stream()
                .map(pk -> OscarSqlNames.quote(pk) + "=?")
                .collect(Collectors.toList());

        return String.format("DELETE FROM %s WHERE %s ",
                OscarSqlNames.relation(schemaName, tableName), StringUtils.join(uw, "  AND  "));
    }
}
