package cn.iocoder.yudao.framework.dbswitch.dbsynch.oracle;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.oracle.OracleLobBatch;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.AbstractDatabaseSynchronize;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.IDatabaseSynchronize;
import cn.iocoder.yudao.framework.dbswitch.core.util.OracleSqlNames;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Oracle数据库DML同步实现类
 *
 * @author jrl
 */
public class OracleDatabaseSyncImpl extends AbstractDatabaseSynchronize implements
        IDatabaseSynchronize {

    /**
     * 初始化 {@code OracleDatabaseSyncImpl}及其父类状态。
     */
    public OracleDatabaseSyncImpl(DataSource ds) {
        super(ds);
    }

    /**
     * 获取查询列元信息的SQL语句。
     */
    @Override
    public String getColumnMetaDataSql(String schema, String table) {
        return "SELECT * FROM " + OracleSqlNames.relation(schema, table) + " WHERE 1=2";
    }

    /**
     * 生成使用参数占位符的 INSERT 语句。
     */
    @Override
    public String getInsertPrepareStatementSql(String schema, String table, List<String> fields) {
        return "INSERT INTO " + OracleSqlNames.relation(schema, table) + "(" + OracleSqlNames.columns(
                fields) + ") VALUES("
                + String.join(",", Collections.nCopies(fields.size(), "?")) + ")";
    }

    /**
     * 生成Update操作的SQL语句。
     */
    @Override
    public String getUpdatePrepareStatementSql(String schema, String table, List<String> fields, List<String> keys) {
        String updates = fields.stream().filter(field -> !keys.contains(field))
                .map(field -> OracleSqlNames.quote(field) + "=?").collect(Collectors.joining(","));
        return "UPDATE " + OracleSqlNames.relation(schema, table) + " SET " + updates + " WHERE " + predicates(keys);
    }

    /**
     * 生成Delete操作的SQL语句。
     */
    @Override
    public String getDeletePrepareStatementSql(String schema, String table, List<String> keys) {
        return "DELETE FROM " + OracleSqlNames.relation(schema, table) + " WHERE " + predicates(keys);
    }

    /**
     * 生成当前查询范围所需的条件表达式集合。
     */
    private static String predicates(List<String> keys) {
        return keys.stream().map(key -> OracleSqlNames.quote(key) + "=?").collect(Collectors.joining(" AND "));
    }

    /**
     * 执行参数化数据插入操作。
     */
    @Override
    public long executeInsert(List<Object[]> records) {
        if (records.isEmpty()) {
            return 0;
        }
        try (OracleLobBatch batch = new OracleLobBatch(records, this.insertArgsType)) {
            return super.executeInsert(batch.rows());
        }
    }

    /**
     * 批量执行 UPDATE。
     */
    @Override
    public long executeUpdate(List<Object[]> records) {
        if (records.isEmpty()) {
            return 0;
        }
        // 输入行仍按 fieldOrders 排列，基类随后重排 SET/PK 参数。
        try (OracleLobBatch batch = new OracleLobBatch(records, this.insertArgsType)) {
            return super.executeUpdate(batch.rows());
        }
    }
}
