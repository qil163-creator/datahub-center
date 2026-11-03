package cn.iocoder.yudao.framework.dbswitch.dbwriter.influxdb;

import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.AbstractDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.product.influxdb3.*;

import javax.sql.DataSource;
import java.util.List;

/** 原生数据点写入器；准备阶段绝不创建或清空表。 */
public class InfluxdbWriterImpl extends AbstractDatabaseWriter {

    private InfluxNativeCatalog.Table prepared;

    private List<String> preparedFields;

    /**
     * 初始化 {@code InfluxdbWriterImpl}及其父类状态。
     */
    public InfluxdbWriterImpl(DataSource source) {
        super(source);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "InfluxDB3";
    }

    /**
     * 写入准备：执行一条 WHERE 1=2 的探测查询（不取数据）， 从结果集元数据中缓存目标表“字段名 -> JDBC 类型”映射，供 write 阶段绑定参数类型使用； 查询失败或表无字段时抛出运行时异常。
     */
    @Override
    public void prepareWrite(String schema, String table, List<String> fields) {
        prepared = null;
        preparedFields = null;
        InfluxNativeCatalog.schema(schema);
        final InfluxNativeCatalog.Table nativeTable;
        try (var connection = dataSource.getConnection()) {
            var session = InfluxNativeSession.open(dataSource, connection, runtimeOptions.maxCacheBytes(),
                    runtimeOptions.queryTimeoutSeconds());
            nativeTable = InfluxNativeCatalog.require(session, table);
            InfluxLineProtocol.fields(nativeTable, fields);
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            prepared = null;
            preparedFields = null;
            throw new IllegalStateException("Influx native target metadata could not be prepared");
        }
        prepared = nativeTable;
        preparedFields = List.copyOf(fields);
    }

    /**
     * 批量数据写入。
     */
    @Override
    public long write(List<String> fields, List<Object[]> input) {
        if (prepared == null || !preparedFields.equals(fields)) {
            throw new IllegalArgumentException("Influx write fields disagree with their prepared native schema");
        }
        List<Object[]> rows = BatchValueConverter.convert(input, fields.size(), batchLimits);
        if (rows.isEmpty()) {
            return 0;
        }
        final byte[] payload;
        try {
            payload = InfluxLineProtocol.batch(prepared, fields, rows, batchLimits.maxBatchBytes());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "InfluxDB3 batch has an unsupported value, timestamp or size; no write was submitted");
        }
        boolean attempted = false;
        try (var connection = dataSource.getConnection()) {
            var session = InfluxNativeSession.open(dataSource, connection, runtimeOptions.maxCacheBytes(),
                    runtimeOptions.queryTimeoutSeconds());
            if (!prepared.equals(InfluxNativeCatalog.require(session, prepared.name()))) {
                throw new IllegalStateException("Influx target structure changed after preparation");
            }
            attempted = true;
            session.write(payload);
            return rows.size();
        } catch (Exception failure) {
            if (attempted) {
                throw new UnconfirmedWriteException();
            }
            throw new IllegalStateException("Influx write failed before submitting the batch");
        }
    }
}
