package cn.iocoder.yudao.framework.dbswitch.dbsynch.olap;

import lombok.extern.slf4j.Slf4j;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.mysql.MySqlDatabaseSyncImpl;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * MySQL协议兼容的OLAP数据库DML同步基础实现。
 */
@Slf4j
public abstract class MysqlCompatibleOlapDatabaseSyncImpl extends MySqlDatabaseSyncImpl {

    /**
     * 初始化 {@code MysqlCompatibleOlapDatabaseSyncImpl}及其父类状态。
     */
    public MysqlCompatibleOlapDatabaseSyncImpl(DataSource ds) {
        super(ds);
    }

    /**
     * 执行参数化数据插入操作。
     */
    @Override
    public long executeInsert(List<Object[]> records) {
        if (records.isEmpty()) {
            return 0;
        }
        records = BatchValueConverter.convert(records, fieldOrders.size(), batchLimits);
        try {
            jdbcTemplate.execute("set enable_insert_strict = true");
        } catch (Exception e) {
            log.error("{} [set enable_insert_strict] error", getDatabaseProductName(), e);
        }
        return super.executeInsert(records);
    }

    /**
     * 返回当前实现使用的数据库产品名称。
     */
    protected abstract String getDatabaseProductName();

    /**
     * 批量删除（默认复用通用批处理实现，子类可覆盖）。
     */
    @Override
    protected void batchDelete(List<Object[]> recordValues, String batchSql, int[] argTypes) {
        udOperator(recordValues, batchSql, argTypes);
    }

    /**
     * 批量更新（默认复用通用批处理实现，子类可覆盖）。
     */
    @Override
    protected void batchUpdate(List<Object[]> recordValues, String batchSql, int[] argTypes) {
        udOperator(recordValues, batchSql, argTypes);
    }

    /**
     * 按绑定类型执行目标表的更新或删除参数化操作。
     */
    private void udOperator(List<Object[]> recordValues, String batchSql, int[] argTypes) {
        batchLimits.requireStatementParameters(argTypes.length);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(batchSql)) {
            for (Object[] recordValue : recordValues) {
                for (int j = 0; j < argTypes.length; j++) {
                    ps.setObject(j + 1, recordValue[j], argTypes[j]);
                }
                ps.executeUpdate();
            }
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }
}
