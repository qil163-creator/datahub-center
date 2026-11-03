package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import java.math.BigInteger;

/**
 * PostgreSQL 顶层 xid8，十进制字符串避免 JSON 精度损失。routeFingerprint 不含原始连接信息，
 * 但不是集群身份/恢复代际证明；克隆、PITR、同端点替换可能复用它。不能单独作为到数/重试凭证。
 * 仅由正常提交、服务端 committed 复核、连接清理全部成功的路径交付。
 * relationFingerprint 为可选 PG_RELATION_STORAGE_V1 物理关系摘要，必须联合路由比较；不是值或恢复代际。
 */
public record TransactionCommitReceipt(int schemaVersion, String protocol, String transactionId,
                                       String routeFingerprint, String relationFingerprint) {

    /** 旧回执没有对象绑定，不补造身份。 */
    public TransactionCommitReceipt(int schemaVersion, String protocol, String transactionId, String routeFingerprint) {
        this(schemaVersion, protocol, transactionId, routeFingerprint, null);
    }

    /**
     * 校验构造参数并初始化 {@code TransactionCommitReceipt}。
     */
    public TransactionCommitReceipt {
        if (schemaVersion != 1 || !"POSTGRESQL_XID8_V1".equals(protocol)
                || transactionId == null || !transactionId.matches("[1-9][0-9]{0,19}")
                || new BigInteger(transactionId).compareTo(BigInteger.valueOf(3)) < 0
                || new BigInteger(transactionId).bitLength() > 64
                || routeFingerprint == null || !routeFingerprint.matches("[a-f0-9]{64}")
                || relationFingerprint != null && !relationFingerprint.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid transaction commit receipt");
        }
    }
}
