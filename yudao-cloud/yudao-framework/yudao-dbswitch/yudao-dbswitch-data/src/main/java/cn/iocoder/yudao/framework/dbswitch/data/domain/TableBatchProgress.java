package cn.iocoder.yudao.framework.dbswitch.data.domain;

import cn.iocoder.yudao.framework.dbswitch.common.entity.RecordTransformDefinition;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;

/**
 * 批次流转事实：FULL 模式下 readRows 统计源端行，DIFF 模式下统计选中的变更记录，
 * 不统计差异计算器检查的全部行；已确认或有提交凭据的输入行不能证明数据可见。
 * 缓冲字节数包含等待入队的生产者；terminated=false 表示之后仍可能发生写入。
 */
public record TableBatchProgress(long readRows, long readBytes, long submittedRows, long acknowledgedRows,
                                 long commitWitnessedInputRows, long rejectedRows, long unknownRows,
                                 long submittedBatches, long acknowledgedBatches, long bufferedBytes,
                                 long peakBufferedBytes, boolean terminated, RecordTransformDefinition transformation) {

    public static final class Collector {

        private long read, bytes, submitted, acknowledged, witnessed, rejected, unknown, batches, completed, buffered, peak;

        private final RecordTransformDefinition transformation;

        /**
         * 初始化 {@code Collector}。
         */
        public Collector(RecordTransformDefinition transformation) {
            this.transformation = transformation;
        }

        /**
         * 累计已读取行数及行字节数，同时检查统计值溢出。
         */
        public synchronized void read(long rowBytes) {
            read = Math.addExact(read, 1);
            bytes = Math.addExact(bytes, rowBytes);
        }

        /**
         * 累计已提交写入的数据行数。
         */
        public synchronized void submitted(long rows) {
            submitted = Math.addExact(submitted, rows);
            batches = Math.addExact(batches, 1);
        }

        /**
         * 累计已确认写入成功的数据行数。
         */
        public synchronized void acknowledged(long rows, boolean hasCommitReceipt) {
            acknowledged = Math.addExact(acknowledged, rows);
            completed = Math.addExact(completed, 1);
            if (hasCommitReceipt) {
                witnessed = Math.addExact(witnessed, rows);
            }
        }

        /**
         * 记录当前批次或任务的失败状态。
         */
        public synchronized void failed(long rows, Throwable failure) {
            if (failure instanceof cn.iocoder.yudao.framework.dbswitch.dbwriter.PartialBatchWriteException partial
                    && partial.inputRows() == rows) {
                acknowledged = Math.addExact(acknowledged, partial.acknowledgedRows());
                rejected = Math.addExact(rejected, partial.rejectedRows());
                unknown = Math.addExact(unknown, partial.unknownRows());
            } else if (failure instanceof BatchRejectedException) {
                rejected = Math.addExact(rejected, rows);
            } else {
                unknown = Math.addExact(unknown, rows);
            }
        }

        /**
         * 记录读取侧拒绝处理的数据行数或原因。
         */
        public synchronized void rejectedRead() {
            rejected = Math.addExact(rejected, 1);
        }

        /**
         * 累加当前缓冲占用并刷新峰值统计，同时检查计数溢出。
         */
        public synchronized void reserve(long value) {
            buffered = Math.addExact(buffered, value);
            peak = Math.max(peak, buffered);
        }

        /**
         * 释放当前作用域占用的连接、内存或容量配额。
         */
        public synchronized void release(long value) {
            buffered = Math.max(0, buffered - value);
        }

        /**
         * 返回快照。
         */
        public synchronized TableBatchProgress snapshot(boolean terminated) {
            return new TableBatchProgress(read, bytes, submitted, acknowledged, witnessed, rejected, unknown,
                    batches, completed, buffered, peak, terminated, transformation);
        }
    }
}
