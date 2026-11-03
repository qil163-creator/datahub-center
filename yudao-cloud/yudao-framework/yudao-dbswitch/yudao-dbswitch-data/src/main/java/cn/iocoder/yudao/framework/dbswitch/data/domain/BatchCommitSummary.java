package cn.iocoder.yudao.framework.dbswitch.data.domain;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.TransactionCommitReceipt;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitRangeReceipt;
import cn.hutool.crypto.digest.DigestUtil;

import java.math.BigInteger;

import java.util.ArrayList;
import java.util.List;

/** 有界诊断清单，不是全表原子提交、恢复代际、字段相等或副本到数证明。 */
public record BatchCommitSummary(long totalBatches, long witnessedBatches, List<Entry> receipts,
                                 CommitRangeReceipt coverage) {

    /**
     * 使用给定参数初始化 {@code BatchCommitSummary}。
     */
    public BatchCommitSummary(long totalBatches, long witnessedBatches, List<Entry> receipts) {
        this(totalBatches, witnessedBatches, receipts, null);
    }

    public static final int MAX_RECEIPTS = 16;

    public record Entry(long batch, TransactionCommitReceipt receipt) {

    }

    /**
     * 校验构造参数并初始化 {@code BatchCommitSummary}。
     */
    public BatchCommitSummary {
        if (totalBatches < 0 || witnessedBatches < 0 || witnessedBatches > totalBatches || receipts == null
                || receipts.size() != Math.min(witnessedBatches, MAX_RECEIPTS)) {
            throw new IllegalArgumentException("Invalid batch commit summary");
        }
        receipts = List.copyOf(receipts);
        long previous = 0;
        for (var entry : receipts) {
            if (entry.batch() <= previous || entry.batch() > totalBatches || entry.receipt() == null) {
                throw new IllegalArgumentException("Invalid batch commit entry");
            }
            previous = entry.batch();
        }
        if (coverage != null) {
            if (totalBatches != witnessedBatches || totalBatches != coverage.batchCount()) {
                throw new IllegalArgumentException("Commit range coverage incomplete");
            }
            BigInteger last = BigInteger.ZERO;
            long ordinal = 0;
            for (var entry : receipts) {
                var xid = new BigInteger(entry.receipt().transactionId());
                if (entry.batch() != ++ordinal || !coverage.routeFingerprint()
                        .equals(entry.receipt().routeFingerprint())
                        || !java.util.Objects.equals(coverage.relationFingerprint(),
                        entry.receipt().relationFingerprint())
                        || xid.compareTo(last) <= 0 || xid.compareTo(
                        new BigInteger(coverage.lastTransactionId())) > 0) {
                    throw new IllegalArgumentException("Commit range sample invalid");
                }
                last = xid;
            }
            if (!receipts.getFirst().receipt().transactionId().equals(coverage.firstTransactionId())
                    || totalBatches <= MAX_RECEIPTS && !receipts.getLast().receipt().transactionId()
                    .equals(coverage.lastTransactionId())
                    || new BigInteger(coverage.lastTransactionId()).subtract(last)
                    .compareTo(BigInteger.valueOf(totalBatches - receipts.size())) < 0) {
                throw new IllegalArgumentException("Commit range sample bounds invalid");
            }
        }
    }

    /** 单个 PipelineBatchWriter 独占；只在全部批次收敛后发布不可变快照。 */
    public static final class Collector {

        private long total;

        private long witnessed;

        private final List<Entry> receipts = new ArrayList<>();

        private boolean complete = true;

        private long rows;

        private String first, last, route, relation;

        private String digest = DigestUtil.sha256Hex("PG_BATCH_COMMIT_CHAIN_V1");

        /**
         * 将当前值加入集合或累计统计。
         */
        public void add(TransactionCommitReceipt receipt) {
            // 旧调用没有确认行数，不能补造完整范围；仅保留旧清单行为。
            add(0, receipt);
        }

        /**
         * 将当前值加入集合或累计统计。
         */
        public void add(long acknowledgedRows, TransactionCommitReceipt receipt) {
            total = Math.addExact(total, 1);
            if (receipt != null) {
                witnessed = Math.addExact(witnessed, 1);
                if (receipts.size() < MAX_RECEIPTS) {
                    receipts.add(new Entry(total, receipt));
                }
            }
            if (!complete) {
                return;
            }
            if (receipt == null || acknowledgedRows < 1 || last != null
                    && (!route.equals(receipt.routeFingerprint())
                    || !java.util.Objects.equals(relation, receipt.relationFingerprint())
                    || new BigInteger(receipt.transactionId()).compareTo(new BigInteger(last)) <= 0)) {
                complete = false;
                return;
            }
            try {
                rows = Math.addExact(rows, acknowledgedRows);
            } catch (ArithmeticException overflow) {
                complete = false;
                return;
            }
            if (first == null) {
                first = receipt.transactionId();
                route = receipt.routeFingerprint();
                relation = receipt.relationFingerprint();
            }
            last = receipt.transactionId();
            digest = DigestUtil.sha256Hex(digest + "\0" + total + "\0" + acknowledgedRows + "\0"
                    + receipt.protocol() + "\0" + last + "\0" + route
                    + (relation == null ? "" : "\0PG_RELATION_STORAGE_V1\0" + relation));
        }

        /**
         * 返回快照。
         */
        public BatchCommitSummary snapshot() {
            return new BatchCommitSummary(total, witnessed, receipts, !complete || total == 0 ? null
                    : new CommitRangeReceipt(1, "POSTGRESQL_XID8_RANGE_V1", total, rows, first, last, route, digest,
                    relation));
        }
    }
}
