package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import java.math.BigInteger;

/**
 * 串行写入器对全部已确认批次的常量空间摘要。闭区间可含其他会话的事务，不代表整表原子提交。
 * digest 绑定批次顺序和确认行数，不是签名、值摘要或恢复代际证明；只能消费可信执行路径的结果。
 * 可选 relationFingerprint 必须在每批相同（包括有/无身份），并参与新摘要链；旧无身份链字节不变。
 */
public record CommitRangeReceipt(int schemaVersion, String protocol, long batchCount, long acknowledgedRows,
                                 String firstTransactionId, String lastTransactionId,
                                 String routeFingerprint, String digest, String relationFingerprint) {

    /**
     * 使用给定参数初始化 {@code CommitRangeReceipt}。
     */
    public CommitRangeReceipt(int schemaVersion, String protocol, long batchCount, long acknowledgedRows,
            String firstTransactionId, String lastTransactionId, String routeFingerprint, String digest) {
        this(schemaVersion, protocol, batchCount, acknowledgedRows, firstTransactionId, lastTransactionId,
                routeFingerprint, digest, null);
    }

    /**
     * 校验构造参数并初始化 {@code CommitRangeReceipt}。
     */
    public CommitRangeReceipt {
        if (schemaVersion != 1 || !"POSTGRESQL_XID8_RANGE_V1".equals(protocol)
                || batchCount < 1 || acknowledgedRows < batchCount || digest == null || !digest.matches(
                "[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid commit range receipt");
        }
        // 复用单事务契约，尤其不能经过 long/double 丢失无符号 xid8 精度。
        new TransactionCommitReceipt(1, "POSTGRESQL_XID8_V1", firstTransactionId, routeFingerprint,
                relationFingerprint);
        new TransactionCommitReceipt(1, "POSTGRESQL_XID8_V1", lastTransactionId, routeFingerprint);
        var first = new BigInteger(firstTransactionId);
        var last = new BigInteger(lastTransactionId);
        if (first.compareTo(last) > 0 || last.subtract(first).add(BigInteger.ONE)
                .compareTo(BigInteger.valueOf(batchCount)) < 0
                || batchCount == 1 && !first.equals(last)) {
            throw new IllegalArgumentException("Invalid commit range bounds");
        }
    }
}
