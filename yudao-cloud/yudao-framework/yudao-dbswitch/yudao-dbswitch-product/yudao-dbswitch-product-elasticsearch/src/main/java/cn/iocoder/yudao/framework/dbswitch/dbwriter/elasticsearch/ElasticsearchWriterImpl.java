package cn.iocoder.yudao.framework.dbswitch.dbwriter.elasticsearch;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.AbstractDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchMapping;
import cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchNativeSession;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/** 精确的逐项 bulk 确认。prepare 时不修改，不自动重试或回退。 */
public class ElasticsearchWriterImpl extends AbstractDatabaseWriter {

    private List<String> preparedFields;

    private Map<String, ElasticsearchMapping.Field> mapping;

    /**
     * 初始化 {@code ElasticsearchWriterImpl}及其父类状态。
     */
    public ElasticsearchWriterImpl(DataSource source) {
        super(source);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "Elasticsearch";
    }

    /**
     * 写入准备：执行一条 WHERE 1=2 的探测查询（不取数据）， 从结果集元数据中缓存目标表“字段名 -> JDBC 类型”映射，供 write 阶段绑定参数类型使用； 查询失败或表无字段时抛出运行时异常。
     */
    @Override
    public void prepareWrite(String schema, String table, List<String> fields) {
        preparedFields = null;
        mapping = null;
        ElasticsearchNativeSession.index(table);
        if (fields == null || fields.isEmpty() || new HashSet<>(fields).size() != fields.size()) {
            throw new IllegalArgumentException("Distinct Elasticsearch write fields are required");
        }
        try (Connection connection = getDataSource().getConnection()) {
            var resolved = ElasticsearchMapping.read(
                    ElasticsearchNativeSession.open(connection, runtimeOptions.maxCacheBytes(),
                            runtimeOptions.queryTimeoutSeconds()), table);
            if (!resolved.keySet().containsAll(fields)) {
                throw new IllegalArgumentException("Write fields must exist in the target mapping");
            }
            mapping = resolved;
            preparedFields = List.copyOf(fields);
            schemaName = schema;
            tableName = table;
            columnType = Collections.emptyMap();
        } catch (SQLException failure) {
            throw new IllegalStateException("Elasticsearch target mapping could not be verified");
        }
    }

    /**
     * 批量数据写入。
     */
    @Override
    public long write(List<String> fields, List<Object[]> values) {
        if (preparedFields == null || !preparedFields.equals(fields)) {
            throw new IllegalArgumentException("Elasticsearch fields differ from the prepared target");
        }
        return cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchBulkExecutor.execute(
                getDataSource(), tableName, fields, mapping, values,
                cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchBulkExecutor.Operation.CREATE,
                batchLimits, runtimeOptions);
    }
}
