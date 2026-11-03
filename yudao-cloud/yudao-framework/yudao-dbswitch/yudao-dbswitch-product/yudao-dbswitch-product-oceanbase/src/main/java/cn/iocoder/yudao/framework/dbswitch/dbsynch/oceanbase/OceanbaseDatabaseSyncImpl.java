package cn.iocoder.yudao.framework.dbswitch.dbsynch.oceanbase;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.util.OceanbaseUtils;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.product.oceanbase.OceanbaseTemporalColumns;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.IDatabaseSynchronize;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.mysql.MySqlDatabaseSyncImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.oracle.OracleDatabaseSyncImpl;

import javax.sql.DataSource;
import java.util.List;

/**
 * OceanBase DML 同步：按兼容模式委托 MySQL 或 Oracle 同步器。
 */
public class OceanbaseDatabaseSyncImpl implements IDatabaseSynchronize {

    private final IDatabaseSynchronize delegate;

    private boolean prepared;

    /**
     * 初始化 {@code OceanbaseDatabaseSyncImpl}。
     */
    public OceanbaseDatabaseSyncImpl(DataSource ds) {
        this.delegate = OceanbaseUtils.isOceanBaseUseMysqlMode(ds)
                ? new MysqlModeSynchronizer(ds)
                : new OracleDatabaseSyncImpl(ds);
    }

    /** 服务器转换精确有符号 TIME 时长；JDBC 的 TIME 转换会丢失大时数及符号。 */
    private static final class MysqlModeSynchronizer extends MySqlDatabaseSyncImpl {

        private java.util.Map<String, OceanbaseTemporalColumns.Column> temporal = java.util.Map.of();

        /**
         * 初始化 {@code MysqlModeSynchronizer}及其父类状态。
         */
        private MysqlModeSynchronizer(DataSource source) {
            super(source);
        }

        /**
         * 同步准备（主键比对模式）。
         */
        @Override
        public void prepare(String schema, String table, List<String> fields, List<String> keys) {
            insertStatementSql = null;
            updateStatementSql = null;
            deleteStatementSql = null;
            insertArgsType = null;
            updateArgsType = null;
            deleteArgsType = null;
            temporal = java.util.Map.of();
            super.prepare(schema, table, fields, keys);
            var typesByField = new java.util.LinkedHashMap<String, Integer>();
            for (int i = 0; i < fieldOrders.size(); i++) {
                typesByField.put(fieldOrders.get(i), insertArgsType[i]);
            }
            temporal = OceanbaseTemporalColumns.prepare(dataSource, schema, table, typesByField,
                    runtimeOptions.queryTimeoutSeconds());
            for (int[] types : List.of(insertArgsType, updateArgsType, deleteArgsType)) {
                for (int i = 0; i < types.length; i++) {
                    if (types[i] == java.sql.Types.TIME) {
                        types[i] = java.sql.Types.VARCHAR;
                    }
                }
            }
        }

        /**
         * 读取当前关系中的数据行并保留受支持类型的精确值。
         */
        private List<Object[]> exactRows(List<Object[]> records, boolean keysOnly) {
            return BatchValueConverter.convert(records, fieldOrders.size(), batchLimits, (value, index) -> {
                String field = fieldOrders.get(index);
                var column = temporal.get(field);
                return column == null || (keysOnly && !pksOrders.contains(field)) ? value : column.exactValue(value);
            });
        }

        /**
         * 执行参数化数据插入操作。
         */
        @Override
        public long executeInsert(List<Object[]> records) {
            return super.executeInsert(exactRows(records, false));
        }

        /**
         * 批量执行 UPDATE。
         */
        @Override
        public long executeUpdate(List<Object[]> records) {
            return super.executeUpdate(exactRows(records, false));
        }

        /**
         * 批量执行 DELETE：从完整记录中按主键顺序抽取主键值作为 WHERE 条件参数，再批量执行。
         */
        @Override
        public long executeDelete(List<Object[]> records) {
            return super.executeDelete(exactRows(records, true));
        }
    }

    /**
     * 获取数据源对象。
     */
    @Override
    public DataSource getDataSource() {
        return delegate.getDataSource();
    }

    /**
     * 设置写入批次的行数、字节数及绑定参数限制。
     */
    @Override
    public void configureBatchLimits(BatchLimits limits) {
        delegate.configureBatchLimits(limits);
    }

    /**
     * 在 prepare 前配置新操作；自定义选项不得被静默忽略。
     */
    @Override
    public void configure(cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions options) {
        delegate.configure(options);
    }

    /**
     * 校验目标表及列映射并准备批次写入资源。
     */
    @Override
    public synchronized void prepare(String schemaName, String tableName, List<String> fieldNames, List<String> pks) {
        prepared = false;
        delegate.prepare(schemaName, tableName, fieldNames, pks);
        prepared = true;
    }

    /**
     * 执行参数化数据插入操作。
     */
    @Override
    public synchronized long executeInsert(List<Object[]> records) {
        requirePrepared();
        return delegate.executeInsert(records);
    }

    /**
     * 批量数据Update。
     */
    @Override
    public synchronized long executeUpdate(List<Object[]> records) {
        requirePrepared();
        return delegate.executeUpdate(records);
    }

    /**
     * 批量数据Delete。
     */
    @Override
    public synchronized long executeDelete(List<Object[]> records) {
        requirePrepared();
        return delegate.executeDelete(records);
    }

    /**
     * 校验并取得已准备。
     */
    private void requirePrepared() {
        if (!prepared) {
            throw new IllegalStateException("OceanBase synchronizer requires successful target preparation");
        }
    }
}
