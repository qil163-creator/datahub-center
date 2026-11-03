package cn.iocoder.yudao.framework.dbswitch.dbwriter.mongodb;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.AbstractDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoBulkExecutor;
import cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoPreparedTarget;

import javax.sql.DataSource;
import java.util.List;

/** 有界原生 BSON 插入。准备阶段观测标识，绝不创建、删除或清空。 */
public class MongoWriterImpl extends AbstractDatabaseWriter {

    private MongoPreparedTarget prepared;

    /**
     * 初始化 {@code MongoWriterImpl}及其父类状态。
     */
    public MongoWriterImpl(DataSource source) {
        super(source);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "MongoDB";
    }

    /**
     * 写入准备：执行一条 WHERE 1=2 的探测查询（不取数据）， 从结果集元数据中缓存目标表“字段名 -> JDBC 类型”映射，供 write 阶段绑定参数类型使用； 查询失败或表无字段时抛出运行时异常。
     */
    @Override
    public void prepareWrite(String schema, String table, List<String> fields) {
        prepared = null;
        var target = MongoPreparedTarget.prepare(getDataSource(), schema, table, fields);
        this.schemaName = schema;
        this.tableName = table;
        this.columnType = java.util.Collections.emptyMap();
        prepared = target;
    }

    /**
     * 批量数据写入。
     */
    @Override
    public long write(List<String> fields, List<Object[]> rows) {
        if (prepared == null || !prepared.fields().equals(fields)) {
            throw new IllegalArgumentException("MongoDB fields do not match the prepared native target");
        }
        return MongoBulkExecutor.execute(getDataSource(), prepared, rows, MongoBulkExecutor.Operation.INSERT,
                batchLimits, runtimeOptions);
    }
}
