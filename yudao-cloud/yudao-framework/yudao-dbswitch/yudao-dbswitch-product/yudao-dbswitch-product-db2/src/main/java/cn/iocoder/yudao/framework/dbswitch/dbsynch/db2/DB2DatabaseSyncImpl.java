package cn.iocoder.yudao.framework.dbswitch.dbsynch.db2;

import cn.iocoder.yudao.framework.dbswitch.dbsynch.AbstractDatabaseSynchronize;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.IDatabaseSynchronize;
import cn.iocoder.yudao.framework.dbswitch.product.db2.Db2CursorMetadata;
import org.apache.commons.lang3.StringUtils;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * DB2数据库DML同步实现类
 *
 * @author jrl
 */
public class DB2DatabaseSyncImpl extends AbstractDatabaseSynchronize implements
        IDatabaseSynchronize {

    /**
     * 初始化 {@code DB2DatabaseSyncImpl}及其父类状态。
     */
    public DB2DatabaseSyncImpl(DataSource ds) {
        super(ds);
    }

    /**
     * 获取查询列元信息的SQL语句。
     */
    @Override
    public String getColumnMetaDataSql(String schemaName, String tableName) {
        return "SELECT * FROM " + Db2CursorMetadata.relation(schemaName, tableName) + " WHERE 1=2";
    }

    /**
     * 生成使用参数占位符的 INSERT 语句。
     */
    @Override
    public String getInsertPrepareStatementSql(String schemaName, String tableName,
            List<String> fieldNames) {
        List<String> placeHolders = Collections.nCopies(fieldNames.size(), "?");
        return "INSERT INTO " + Db2CursorMetadata.relation(schemaName, tableName) + " ("
                + Db2CursorMetadata.fields(fieldNames) + ") VALUES (" + String.join(",", placeHolders) + ")";
    }

    /**
     * 生成Update操作的SQL语句。
     */
    @Override
    public String getUpdatePrepareStatementSql(String schemaName, String tableName,
            List<String> fieldNames, List<String> pks) {
        List<String> uf = fieldNames.stream()
                .filter(field -> !pks.contains(field))
                .map(field -> Db2CursorMetadata.quote(field) + "=?")
                .collect(Collectors.toList());

        List<String> uw = pks.stream()
                .map(pk -> Db2CursorMetadata.quote(pk) + "=?")
                .collect(Collectors.toList());

        return "UPDATE " + Db2CursorMetadata.relation(schemaName, tableName) + " SET " + StringUtils.join(uf, " , ")
                + " WHERE " + StringUtils.join(uw, " AND ");
    }

    /**
     * 生成Delete操作的SQL语句。
     */
    @Override
    public String getDeletePrepareStatementSql(String schemaName, String tableName,
            List<String> pks) {
        List<String> uw = pks.stream()
                .map(pk -> Db2CursorMetadata.quote(pk) + "=?")
                .collect(Collectors.toList());

        return "DELETE FROM " + Db2CursorMetadata.relation(schemaName, tableName) + " WHERE " + StringUtils.join(uw,
                " AND ");
    }
}
