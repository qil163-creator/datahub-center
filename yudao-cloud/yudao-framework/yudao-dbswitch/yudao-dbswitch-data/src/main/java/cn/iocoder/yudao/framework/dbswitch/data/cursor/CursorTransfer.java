package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursor;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.data.domain.BatchCommitSummary;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.domain.TableBatchProgress;
import cn.iocoder.yudao.framework.dbswitch.data.domain.TableTransferSummary;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 现有数据执行层的顺序游标模式；JDBC 提交仍由对应产品的写入器管理。 */
public final class CursorTransfer {

    /**
     * 初始化 {@code CursorTransfer}。
     */
    private CursorTransfer() {
    }

    /**
     * 按顺序游标读取源表并按键写入目标，汇总已确认进度及提交回执。
     */
    public static void execute(DbSwitchRuntime runtime, DataSource source, DataSource target,
            String sourceSchema, String sourceTable, String targetSchema, String targetTable,
            List<String> sourceFields, List<String> targetFields, List<String> sourceKeys, List<String> targetKeys,
            String watermark, BatchLimits limits, int fetchSize, CursorRunBinding binding,
            DbSwitchTableResult result, Runnable stopCheck) throws Exception {
        execute(runtime, source, target, ProductTypeEnum.POSTGRESQL, ProductTypeEnum.POSTGRESQL,
                sourceSchema, sourceTable, targetSchema, targetTable, sourceFields, targetFields, sourceKeys,
                targetKeys,
                watermark, limits, fetchSize, binding, result, stopCheck);
    }

    /**
     * 按顺序游标读取源表并按键写入目标，汇总已确认进度及提交回执。
     */
    public static void execute(DbSwitchRuntime runtime, DataSource source, DataSource target,
            ProductTypeEnum sourceProduct, ProductTypeEnum targetProduct,
            String sourceSchema, String sourceTable, String targetSchema, String targetTable,
            List<String> sourceFields, List<String> targetFields, List<String> sourceKeys, List<String> targetKeys,
            String watermark, BatchLimits limits, int fetchSize, CursorRunBinding binding,
            DbSwitchTableResult result, Runnable stopCheck) throws Exception {
        var previous = binding.previous();
        if (previous != null && (previous.sourceProduct() != sourceProduct || previous.targetProduct() != targetProduct)) {
            throw new IllegalArgumentException("Cursor source or target product changed");
        }
        runtime.products().product(sourceProduct)
                .require(cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation.SYNC_CURSOR);
        runtime.products().product(targetProduct)
                .require(cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation.WRITE_UPSERT);
        var request = new CursorReadRequest(sourceSchema, sourceTable, sourceFields, watermark, sourceKeys,
                previous == null ? null : previous.applied(),
                previous == null || previous.complete() ? null : previous.fixedUpper(),
                previous == null ? null : previous.schemaFingerprint(), limits,
                previous != null && !previous.complete(), binding.sourceGenerationPin());
        var reader = runtime.reader(source, sourceProduct);
        reader.setFetchSize(fetchSize);
        var writer = runtime.upsertWriter(target, targetProduct);
        writer.configureBatchLimits(limits);
        writer.configureCursorTargetGeneration(binding.targetGenerationPin());
        var progress = new TableBatchProgress.Collector(null);
        var commits = new BatchCommitSummary.Collector();
        long sequence = previous == null ? 0 : previous.sequence();
        CursorCheckpoint checkpoint = null;
        long buffered = 0;
        var cursor = reader.openCursor(request);
        try {
            writer.prepareUpsert(targetSchema, targetTable, targetFields, targetKeys);
            var sourceContract = cursor.keyContract();
            var targetContract = writer.keyContract();
            if (sourceContract.product() != sourceProduct || targetContract.product() != targetProduct) {
                throw new IllegalArgumentException("Cursor factory product does not match its key evidence");
            }
            sourceContract.requireReplayCompatible(targetContract);
            if (previous != null && !previous.targetSchemaFingerprint().equals(writer.targetSchemaFingerprint())) {
                throw new IllegalArgumentException("Cursor target schema or relation changed");
            }
            checkpoint = new CursorCheckpoint(2, binding.fingerprint(), cursor.schemaFingerprint(),
                    writer.targetSchemaFingerprint(), cursor.upperBound(),
                    previous == null ? null : previous.applied(), sequence, false,
                    previous == null ? null : previous.lastCommit(),
                    previous == null ? cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement.NONE : previous.lastCommitAcknowledgement(),
                    sourceProduct, targetProduct);
            persist(binding, checkpoint); // 首次写入目标前，冻结源端快照的上界。
            List<Object[]> batch = new ArrayList<>();
            long started = System.nanoTime();
            TypedCursor last = null;
            while (cursor.next()) {
                stopCheck.run();
                Object[] row = cursor.row();
                long bytes = BatchValueConverter.estimatedRowBytes(row);
                if (!batch.isEmpty() && (bytes > limits.maxBatchBytes() - buffered
                        || TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= limits.maxBatchAgeMillis())) {
                    checkpoint = flush(writer, targetFields, batch, last, checkpoint, binding, progress, commits,
                            stopCheck);
                    progress.release(buffered);
                    buffered = 0;
                    batch = new ArrayList<>();
                    started = System.nanoTime();
                }
                batch.add(row);
                last = cursor.position();
                buffered = Math.addExact(buffered, bytes);
                progress.read(bytes);
                progress.reserve(bytes);
                result.getSyncBytes().addAndGet(bytes);
                if (batch.size() >= Math.min(fetchSize, limits.maxRows()) || buffered >= limits.maxBatchBytes()) {
                    checkpoint = flush(writer, targetFields, batch, last, checkpoint, binding, progress, commits,
                            stopCheck);
                    progress.release(buffered);
                    buffered = 0;
                    batch = new ArrayList<>();
                    started = System.nanoTime();
                }
                publish(result, progress, commits, sourceFields.size(), false);
            }
            if (!batch.isEmpty()) {
                checkpoint = flush(writer, targetFields, batch, last, checkpoint, binding, progress, commits,
                        stopCheck);
            }
            progress.release(buffered);
            buffered = 0;
        } finally {
            boolean sourceClosed = false;
            try {
                cursor.close();
                sourceClosed = true;
            } finally {
                progress.release(buffered);
                publish(result, progress, commits, sourceFields.size(), sourceClosed);
            }
        }
        // 源端关闭操作已实际返回；绝不能因 Future.cancel 或关闭失败而发布完成状态。
        persist(binding, new CursorCheckpoint(2, checkpoint.bindingFingerprint(), checkpoint.schemaFingerprint(),
                checkpoint.targetSchemaFingerprint(),
                checkpoint.fixedUpper(), checkpoint.applied(), checkpoint.sequence(), true, checkpoint.lastCommit(),
                checkpoint.lastCommitAcknowledgement(), sourceProduct, targetProduct));
    }

    /**
     * 将缓冲区中已积累的数据提交给当前输出端。
     */
    private static CursorCheckpoint flush(cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseUpsertWriter writer,
            List<String> fields, List<Object[]> rows, TypedCursor last, CursorCheckpoint previous,
            CursorRunBinding binding,
            TableBatchProgress.Collector progress, BatchCommitSummary.Collector commits, Runnable stopCheck) {
        stopCheck.run();
        int expected = rows.size();
        progress.submitted(expected);
        cn.iocoder.yudao.framework.dbswitch.dbwriter.BatchWriteResult receipt;
        try {
            receipt = writer.writeWithReceipt(fields, rows);
            if (receipt == null || receipt.acknowledgedRows() != expected
                    || receipt.commitAcknowledgement() == cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement.NONE) {
                throw new UnconfirmedWriteException();
            }
        } catch (RuntimeException | Error failure) {
            progress.failed(expected, failure);
            throw failure;
        }
        progress.acknowledged(expected, receipt.receipt() != null);
        commits.add(expected, receipt.receipt());
        CursorCheckpoint next = new CursorCheckpoint(2, previous.bindingFingerprint(), previous.schemaFingerprint(),
                previous.targetSchemaFingerprint(),
                previous.fixedUpper(), last, Math.addExact(previous.sequence(), 1), false, receipt.receipt(),
                receipt.commitAcknowledgement(), previous.sourceProduct(), previous.targetProduct());
        persist(binding, next);
        return next;
    }

    /**
     * 将当前状态或恢复材料保存到持久存储。
     */
    private static void persist(CursorRunBinding binding, CursorCheckpoint checkpoint) {
        if (!binding.store().compareAndSet(checkpoint)) {
            throw new IllegalStateException(
                    "Cursor checkpoint ownership or revision changed after the last confirmed target batch");
        }
    }

    /**
     * 将已确认写入行数、批次进度和提交回执汇总到本次搬运结果。
     */
    private static void publish(DbSwitchTableResult result, TableBatchProgress.Collector progress,
            BatchCommitSummary.Collector commits,
            int columns, boolean terminated) {
        var snapshot = progress.snapshot(terminated);
        result.setBatchProgress(snapshot);
        result.getSyncCount().set(snapshot.acknowledgedRows());
        result.setTransferSummary(new TableTransferSummary(TableTransferSummary.Operation.CURSOR_UPSERT,
                snapshot.readRows(), snapshot.acknowledgedRows(), columns, columns, 0, false, false, false,
                commits.snapshot()));
    }
}
