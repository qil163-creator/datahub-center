package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.data.domain.BatchCommitSummary;
import cn.iocoder.yudao.framework.dbswitch.data.domain.TableBatchProgress;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.IDatabaseSynchronize;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.BatchWriteResult;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.WriteMode;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionInfo;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlDataSourceUtils;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;
import com.zaxxer.hikari.HikariDataSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 有界数据库写入端。原生 UPSERT 必须使用经过验证的显式唯一键；不提供先读后写的回退方案。 */
public class InsertDbExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "insert-db";

    private static final int INSERT = 1, UPSERT = 2, UPDATE = 3;

    private final TableBatchProgress.Collector progress = new TableBatchProgress.Collector(null);

    private final BatchCommitSummary.Collector commits = new BatchCommitSummary.Collector();

    /**
     * 初始化 {@code InsertDbExecutor}及其父类状态。
     */
    public InsertDbExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        Long databaseId = config().getLong("databaseId");
        if (databaseId == null) {
            throw new IllegalArgumentException("数据库写入节点缺少数据源");
        }
        String tableName = requireConfig("tableName", "目标表");
        int syncType = config().getInt("dataSyncType", INSERT);
        if (syncType < INSERT || syncType > UPDATE) {
            throw new IllegalArgumentException("未知数据库写入模式");
        }
        int requestedRows = config().getInt("batchSize", 1000);
        if (requestedRows < 1) {
            throw new IllegalArgumentException("批次行数必须大于零");
        }
        var limits = context.getDatabaseBatchLimits();
        int batchSize = Math.min(requestedRows, limits.maxRows());
        List<String> primaryKeys = StrUtil.split(config().getStr("sourcePrimaryKeys", ""), ',', true, true);
        if (syncType != INSERT && (primaryKeys.isEmpty() || new HashSet<>(primaryKeys).size() != primaryKeys.size())) {
            throw new IllegalArgumentException("更新模式必须配置不重复的唯一键字段");
        }
        WriteMode mode = WriteMode.valueOf(config().getStr("writeMode", "INSERT").toUpperCase(java.util.Locale.ROOT));
        if (syncType != INSERT && mode != WriteMode.INSERT) {
            throw new IllegalArgumentException("COPY 仅用于插入模式");
        }
        boolean truncate = config().getBool("targetTruncate", false);
        if (truncate && syncType != INSERT) {
            throw new IllegalArgumentException("更新及 UPSERT 模式不允许清空目标表");
        }
        EtlConnectionInfo conn = context.getConnectionResolver().resolve(databaseId);
        var runtime = context.getDatabaseRuntime();
        DatabaseOperation operation = syncType == UPSERT ? DatabaseOperation.WRITE_UPSERT
                : syncType == UPDATE ? DatabaseOperation.SYNC_DIFF
                : mode == WriteMode.COPY ? DatabaseOperation.WRITE_COPY : DatabaseOperation.WRITE_INSERT;
        runtime.products().product(conn.getProductType()).require(operation);
        long buffered = 0;
        try (HikariDataSource source = EtlDataSourceUtils.create(conn, runtime)) {
            String schema = conn.getSchema();
            if (schema == null || schema.isBlank()) {
                try (var connection = source.getConnection()) {
                    schema = connection.getSchema();
                    if (schema == null || schema.isBlank()) {
                        schema = connection.getCatalog();
                    }
                }
            }
            List<String> fields = null;
            IDatabaseWriter writer = null;
            IDatabaseSynchronize synchronizer = null;
            List<Object[]> batch = new ArrayList<>(batchSize);
            long started = System.nanoTime();
            Map<String, Object> row;
            while ((row = readNextRow()) != null) {
                if (fields == null) {
                    fields = List.copyOf(row.keySet());
                    if (fields.isEmpty() || !fields.containsAll(primaryKeys)) {
                        throw new BatchRejectedException("输入字段缺少配置的唯一键");
                    }
                    if (syncType == INSERT) {
                        writer = runtime.writer(source, conn.getProductType(), mode);
                        writer.configureBatchLimits(limits);
                        writer.prepareWrite(schema, tableName, fields);
                    } else if (syncType == UPSERT) {
                        var upsert = runtime.upsertWriter(source, conn.getProductType());
                        upsert.configureBatchLimits(limits);
                        upsert.prepareUpsert(schema, tableName, fields, primaryKeys);
                        writer = upsert;
                    } else {
                        synchronizer = runtime.synchronizer(source, conn.getProductType());
                        synchronizer.configureBatchLimits(limits);
                        synchronizer.prepare(schema, tableName, fields, primaryKeys);
                    }
                    // 仅在请求的数据库产品操作及输入结构通过预检后，才允许执行破坏性操作。
                    if (truncate) {
                        runtime.reader(source, conn.getProductType()).truncateTableData(schema, tableName);
                    }
                }
                Object[] values;
                try {
                    if (!row.keySet().equals(new HashSet<>(fields))) {
                        throw new BatchRejectedException("批次输入字段集合发生变化");
                    }
                    Object[] raw = new Object[fields.size()];
                    for (int i = 0; i < fields.size(); i++) {
                        raw[i] = row.get(fields.get(i));
                    }
                    values = BatchValueConverter.convert(List.<Object[]>of(raw), fields.size(), limits).getFirst();
                } catch (BatchRejectedException rejected) {
                    progress.rejectedRead();
                    throw rejected;
                }
                long bytes = BatchValueConverter.estimatedRowBytes(values);
                if (!batch.isEmpty() && (bytes > limits.maxBatchBytes() - buffered
                        || TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= limits.maxBatchAgeMillis())) {
                    flush(writer, synchronizer, fields, batch);
                    progress.release(buffered);
                    batch = new ArrayList<>(batchSize);
                    buffered = 0;
                    started = System.nanoTime();
                }
                batch.add(values);
                buffered = Math.addExact(buffered, bytes);
                progress.read(bytes);
                progress.reserve(bytes);
                publish(false);
                if (batch.size() >= batchSize || buffered >= limits.maxBatchBytes()) {
                    flush(writer, synchronizer, fields, batch);
                    progress.release(buffered);
                    batch = new ArrayList<>(batchSize);
                    buffered = 0;
                    started = System.nanoTime();
                }
            }
            if (!batch.isEmpty()) {
                flush(writer, synchronizer, fields, batch);
                progress.release(buffered);
                buffered = 0;
            }
        } finally {
            progress.release(buffered);
            publish(true);
        }
        logLine(String.format("数据库写入完成，已确认输入 %s 行", writeCount.get()));
    }

    /**
     * 将缓冲区中已积累的数据提交给当前输出端。
     */
    private void flush(IDatabaseWriter writer, IDatabaseSynchronize synchronizer, List<String> fields,
            List<Object[]> batch) {
        checkStop();
        int expected = batch.size();
        progress.submitted(expected);
        publish(false);
        try {
            BatchWriteResult result = writer != null ? writer.writeWithReceipt(fields, batch)
                    : new BatchWriteResult(synchronizer.executeUpdate(batch), null);
            if (result == null || result.acknowledgedRows() != expected) {
                throw new UnconfirmedWriteException();
            }
            progress.acknowledged(expected, result.receipt() != null);
            commits.add(expected, result.receipt());
            writeCount.addAndGet(expected);
        } catch (RuntimeException | Error failure) {
            progress.failed(expected, failure);
            throw failure;
        } finally {
            publish(false);
        }
    }

    /**
     * 将当前已汇总的数据或状态发布给结果接收方。
     */
    private void publish(boolean terminated) {
        context.getDatabaseProgress().put(node.getNo(), progress.snapshot(terminated));
        context.getDatabaseCommits().put(node.getNo(), commits.snapshot());
    }
}
