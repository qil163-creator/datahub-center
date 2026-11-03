package cn.iocoder.yudao.framework.dbswitch.dbwriter.tdengine;

import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.NonTransactionalWriteExecution;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.AbstractDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.product.tdengine.TdengineJdbcSupport;

import javax.sql.DataSource;
import java.util.List;
import java.util.stream.Collectors;

/** 原生 WebSocket 写入保留时间戳和值，不假装拥有事务。 */
public class TdengineWriterImpl extends AbstractDatabaseWriter {

    private TdengineJdbcSupport.Table metadata;

    /**
     * 初始化 {@code TdengineWriterImpl}及其父类状态。
     */
    public TdengineWriterImpl(DataSource source) {
        super(source);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "TDengine";
    }

    /**
     * 写入准备：执行一条 WHERE 1=2 的探测查询（不取数据）， 从结果集元数据中缓存目标表“字段名 -> JDBC 类型”映射，供 write 阶段绑定参数类型使用； 查询失败或表无字段时抛出运行时异常。
     */
    @Override
    public void prepareWrite(String schema, String table, List<String> fields) {
        TdengineJdbcSupport.columns(fields);
        try (var connection = dataSource.getConnection()) {
            metadata = TdengineJdbcSupport.metadata(connection, schema, table, runtimeOptions.queryTimeoutSeconds());
            fields.forEach(metadata::column);
            if (!fields.containsAll(metadata.keys())) {
                throw new IllegalArgumentException("TDengine requires all stable key columns");
            }
        } catch (Exception failure) {
            throw new IllegalStateException("TDengine WebSocket metadata/key preflight failed");
        }
        schemaName = schema;
        tableName = table;
        columnType = metadata.columns().stream()
                .collect(Collectors.toMap(TdengineJdbcSupport.Column::name, TdengineJdbcSupport.Column::jdbcType));
    }

    /**
     * 批量数据写入。
     */
    @Override
    public long write(List<String> fields, List<Object[]> records) {
        if (records.isEmpty()) {
            return 0;
        }
        if (metadata == null) {
            throw new IllegalStateException("TDengine writer is not prepared");
        }
        var rows = TdengineJdbcSupport.convert(records, fields, metadata, batchLimits);
        return NonTransactionalWriteExecution.execute(dataSource, (connection, attempt) -> TdengineJdbcSupport.insert(
                connection, attempt, schemaName, tableName, fields, metadata, rows,
                runtimeOptions.queryTimeoutSeconds()));
    }
}

