package cn.iocoder.yudao.framework.dbswitch.dbwriter.atomic;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.TransactionCommitReceipt;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 不可变持久确认。它证明该批次已提交，不证明后续写入器保留了其行。 */
public record AtomicBatchReceipt(AtomicBatchTarget target, AtomicBatchKey key, List<String> fields,
                                 String payloadHash, long rowCount, UUID receiptId, Instant committedAt,
                                 TransactionCommitReceipt transaction, String materializedHash) {

    /** 旧回执仍可查询，但没有物化依据就不能授权替换或消费。 */
    public AtomicBatchReceipt(AtomicBatchTarget target, AtomicBatchKey key, List<String> fields, String payloadHash,
            long rowCount, UUID receiptId, Instant committedAt, TransactionCommitReceipt transaction) {
        this(target, key, fields, payloadHash, rowCount, receiptId, committedAt, transaction, null);
    }

    /**
     * 校验构造参数并初始化 {@code AtomicBatchReceipt}。
     */
    public AtomicBatchReceipt {
        Objects.requireNonNull(target);
        Objects.requireNonNull(key);
        fields = List.copyOf(fields);
        if (fields.isEmpty() || rowCount < 0 || payloadHash == null || !payloadHash.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid batch receipt");
        }
        Objects.requireNonNull(receiptId);
        Objects.requireNonNull(committedAt);
        Objects.requireNonNull(transaction);
        if (materializedHash != null && !materializedHash.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid materialized batch hash");
        }
    }
}
