package cn.iocoder.yudao.framework.dbswitch.dbsynch.dm;

import cn.iocoder.yudao.framework.dbswitch.dbsynch.AbstractDatabaseSynchronize;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.IDatabaseSynchronize;
import cn.iocoder.yudao.framework.dbswitch.core.util.DmSqlNames;
import cn.iocoder.yudao.framework.dbswitch.core.util.DmDriverCompatibility;
import cn.iocoder.yudao.framework.dbswitch.product.dm.DmTargetSideEffects;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import org.apache.commons.lang3.StringUtils;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * DM数据库DML同步实现类
 *
 * @author jrl
 */
public class DmDatabaseSyncImpl extends AbstractDatabaseSynchronize implements
        IDatabaseSynchronize {

    /**
     * 初始化 {@code DmDatabaseSyncImpl}及其父类状态。
     */
    public DmDatabaseSyncImpl(DataSource ds) {
        super(ds);
    }

    /**
     * 同步准备（主键比对模式）。
     */
    @Override
    public void prepare(String schema, String table, List<String> fields, List<String> keys) {
        schemaName = null;
        tableName = null;
        insertStatementSql = null;
        updateStatementSql = null;
        deleteStatementSql = null;
        DmDriverCompatibility.require(dataSource);
        try (var connection = dataSource.getConnection()) {
            DmTargetSideEffects.require(connection, schema, table, runtimeOptions.queryTimeoutSeconds());
        } catch (java.sql.SQLException | RuntimeException failure) {
            // 关闭或驱动异常可能暴露连接细节，不保留其原因链。
            throw new IllegalStateException(
                    "DM DIFF requires a target without generated values, triggers, referential actions or RLS");
        }
        super.prepare(schema, table, fields, keys);
    }

    /**
     * 获取查询列元信息的SQL语句。
     */
    @Override
    public String getColumnMetaDataSql(String schemaName, String tableName) {
        return "SELECT * FROM " + DmSqlNames.relation(schemaName, tableName) + " WHERE 1=2";
    }

    /**
     * 生成使用参数占位符的 INSERT 语句。
     */
    @Override
    public String getInsertPrepareStatementSql(String schemaName, String tableName,
            List<String> fieldNames) {
        List<String> placeHolders = Collections.nCopies(fieldNames.size(), "?");
        return "INSERT INTO " + DmSqlNames.relation(schemaName, tableName) + " (" + DmSqlNames.columns(fieldNames)
                + ") VALUES (" + StringUtils.join(placeHolders, ",") + ")";
    }

    /**
     * 生成Update操作的SQL语句。
     */
    @Override
    public String getUpdatePrepareStatementSql(String schemaName, String tableName,
            List<String> fieldNames, List<String> pks) {
        List<String> uf = fieldNames.stream()
                .filter(field -> !pks.contains(field))
                .map(field -> DmSqlNames.quote(field) + "=?")
                .collect(Collectors.toList());

        List<String> uw = pks.stream()
                .map(pk -> DmSqlNames.quote(pk) + "=?")
                .collect(Collectors.toList());

        if (uw.isEmpty()) {
            throw new IllegalArgumentException("DM update requires primary keys");
        }
        // 仅含键的关系仍需准备 INSERT/DELETE，但没有 UPDATE 语句。
        if (uf.isEmpty()) {
            return null;
        }
        return "UPDATE " + DmSqlNames.relation(schemaName, tableName) + " SET " + StringUtils.join(uf, " , ")
                + " WHERE " + StringUtils.join(uw, " AND ");
    }

    /**
     * 生成Delete操作的SQL语句。
     */
    @Override
    public String getDeletePrepareStatementSql(String schemaName, String tableName,
            List<String> pks) {
        List<String> uw = pks.stream()
                .map(pk -> DmSqlNames.quote(pk) + "=?")
                .collect(Collectors.toList());

        if (uw.isEmpty()) {
            throw new IllegalArgumentException("DM delete requires primary keys");
        }
        return "DELETE FROM " + DmSqlNames.relation(schemaName, tableName) + " WHERE " + StringUtils.join(uw, " AND ");
    }

    /**
     * 批量执行 UPDATE。
     */
    @Override
    public long executeUpdate(List<Object[]> records) {
        if (!records.isEmpty() && updateStatementSql == null) {
            throw new IllegalArgumentException("DM key-only table has no updatable values");
        }
        return super.executeUpdate(records);
    }

    /**
     * 每个 DIFF 批次先获取 ROW EXCLUSIVE 锁并在 DML 之前重新校验目标，因此即使之后创建触发器或策略，
     * {@link #prepare} 不能在批次内执行；复检失败会在发送任何语句之前报告。
     */
    @Override
    protected void batchOperator(List<Object[]> recordValues, String batchSql, int[] argTypes) {
        batchLimits.requireStatementParameters(argTypes.length);
        if (recordValues == null || batchSql == null) {
            throw new IllegalArgumentException("JDBC 批次参数不完整");
        }
        for (var row : recordValues) {
            if (row == null || row.length != argTypes.length) {
                throw new IllegalArgumentException("JDBC 批次字段数量不一致");
            }
        }
        if (recordValues.isEmpty()) {
            return;
        }
        String schema = schemaName, table = tableName;
        int timeout = runtimeOptions.queryTimeoutSeconds();
        JdbcBatchExecutor.execute(dataSource, context -> {
            DmTargetSideEffects.lock(context.connection(), schema, table, timeout);
            DmTargetSideEffects.require(context.connection(), schema, table, timeout);
            JdbcBatchExecutor.executeBatch(context, batchSql, recordValues, argTypes, timeout);
            return null;
        });
    }
}
