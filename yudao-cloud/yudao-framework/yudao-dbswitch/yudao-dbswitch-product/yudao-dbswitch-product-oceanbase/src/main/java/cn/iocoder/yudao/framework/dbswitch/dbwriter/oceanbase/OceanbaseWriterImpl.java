package cn.iocoder.yudao.framework.dbswitch.dbwriter.oceanbase;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.util.OceanbaseUtils;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.product.oceanbase.OceanbaseTemporalColumns;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.BatchWriteResult;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.mysql.MySqlWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.oracle.OracleWriterImpl;

import javax.sql.DataSource;
import java.util.List;

/**
 * OceanBase 写入器：按兼容模式委托 MySQL 或 Oracle 写入器。
 */
public class OceanbaseWriterImpl implements IDatabaseWriter {

    private final IDatabaseWriter delegate;

    private boolean prepared;

    /**
     * 初始化 {@code OceanbaseWriterImpl}。
     */
    public OceanbaseWriterImpl(DataSource dataSource) {
        this.delegate = OceanbaseUtils.isOceanBaseUseMysqlMode(dataSource)
                ? new MysqlModeWriter(dataSource)
                : new OracleWriterImpl(dataSource);
    }

    /** 将原生 TIME 绑定为文本；否则 OceanBase 驱动会将时长回绕为时钟时间。 */
    private static final class MysqlModeWriter extends MySqlWriterImpl {

        private java.util.Map<String, OceanbaseTemporalColumns.Column> temporal = java.util.Map.of();

        /**
         * 初始化 {@code MysqlModeWriter}及其父类状态。
         */
        private MysqlModeWriter(DataSource source) {
            super(source);
        }

        /**
         * 写入准备：执行一条 WHERE 1=2 的探测查询（不取数据）， 从结果集元数据中缓存目标表“字段名 -> JDBC 类型”映射，供 write 阶段绑定参数类型使用； 查询失败或表无字段时抛出运行时异常。
         */
        @Override
        public void prepareWrite(String schema, String table, List<String> fields) {
            this.schemaName = null;
            this.tableName = null;
            this.columnType = null;
            temporal = java.util.Map.of();
            super.prepareWrite(schema, table, fields);
            temporal = OceanbaseTemporalColumns.prepare(dataSource, schema, table, columnType,
                    runtimeOptions.queryTimeoutSeconds());
            columnType.replaceAll((name, type) -> type == java.sql.Types.TIME ? java.sql.Types.VARCHAR : type);
        }

        /**
         * 批量数据写入。
         */
        @Override
        public long write(List<String> fields, List<Object[]> records) {
            return super.write(fields,
                    BatchValueConverter.convert(records, fields.size(), batchLimits, (value, index) -> {
                        var column = temporal.get(fields.get(index));
                        return column == null ? value : column.exactValue(value);
                    }));
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
     * 设置当前操作使用的配置，并检查配置阶段仍允许修改。
     */
    @Override
    public void configure(DbSwitchOptions options) {
        delegate.configure(options);
    }

    /**
     * prepare/write 前配置操作实例；提供方必须显式接受自定义限额。
     */
    @Override
    public void configureBatchLimits(BatchLimits limits) {
        delegate.configureBatchLimits(limits);
    }

    /**
     * 批量写入预处理。
     */
    @Override
    public synchronized void prepareWrite(String schemaName, String tableName, List<String> fieldNames) {
        prepared = false;
        delegate.prepareWrite(schemaName, tableName, fieldNames);
        prepared = true;
    }

    /**
     * 批量数据写入。
     */
    @Override
    public synchronized long write(List<String> fieldNames, List<Object[]> recordValues) {
        requirePrepared();
        return delegate.write(fieldNames, recordValues);
    }

    /**
     * 可选原生单批提交见证；旧实现继续工作，但 null 不能解释为已取得提交/到数证明。
     */
    @Override
    public synchronized BatchWriteResult writeWithReceipt(List<String> fieldNames, List<Object[]> recordValues) {
        requirePrepared();
        return delegate.writeWithReceipt(fieldNames, recordValues);
    }

    /**
     * 校验并取得已准备。
     */
    private void requirePrepared() {
        if (!prepared) {
            throw new IllegalStateException("OceanBase writer requires successful target preparation");
        }
    }
}
