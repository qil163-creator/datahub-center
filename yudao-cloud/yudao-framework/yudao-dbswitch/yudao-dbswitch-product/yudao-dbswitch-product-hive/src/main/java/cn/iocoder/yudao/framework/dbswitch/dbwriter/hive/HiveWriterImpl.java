package cn.iocoder.yudao.framework.dbswitch.dbwriter.hive;

import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.NonTransactionalWriteExecution;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.AbstractDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.product.hive.HiveJdbcWriteSupport;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Hive 使用有界多 VALUES 语句和服务器确认，不使用 MySQL 事务。 */
public class HiveWriterImpl extends AbstractDatabaseWriter {

    private Map<String, ColumnDescription> nativeColumns = Map.of();

    /**
     * 初始化 {@code HiveWriterImpl}及其父类状态。
     */
    public HiveWriterImpl(DataSource source) {
        super(source);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "Hive";
    }

    /**
     * 写入准备：执行一条 WHERE 1=2 的探测查询（不取数据）， 从结果集元数据中缓存目标表“字段名 -> JDBC 类型”映射，供 write 阶段绑定参数类型使用； 查询失败或表无字段时抛出运行时异常。
     */
    @Override
    public void prepareWrite(String schema, String table, List<String> fields) {
        try (var connection = dataSource.getConnection()) {
            nativeColumns = HiveJdbcWriteSupport.metadata(connection, schema, table, fields);
        } catch (Exception failure) {
            throw new IllegalStateException("Hive target metadata preflight failed");
        }
        schemaName = schema;
        tableName = table;
        columnType = nativeColumns.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getFieldType()));
    }

    /**
     * 批量数据写入。
     */
    @Override
    public long write(List<String> fields, List<Object[]> records) {
        if (records.isEmpty()) {
            return 0;
        }
        if (columnType == null || !columnType.keySet().containsAll(fields)) {
            throw new IllegalArgumentException("Hive writer is not prepared");
        }
        batchLimits.maxMultiValueRows(fields.size(), 0);
        var rows = HiveJdbcWriteSupport.convert(records, fields, nativeColumns, batchLimits);
        return NonTransactionalWriteExecution.execute(dataSource, (connection, attempt) -> {
            HiveJdbcWriteSupport.session(connection, runtimeOptions.queryTimeoutSeconds());
            return HiveJdbcWriteSupport.insert(connection, attempt, schemaName, tableName, fields, nativeColumns,
                    rows, batchLimits, runtimeOptions.queryTimeoutSeconds());
        });
    }
}
