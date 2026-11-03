package cn.iocoder.yudao.framework.dbswitch.dbwriter;

/** 单批回执；receipt 仅证明该写事务收到提交确认，不证明表/字段范围、恢复代际或读取可见性。 */
public record BatchWriteResult(long acknowledgedRows, TransactionCommitReceipt receipt,
                               CommitAcknowledgement commitAcknowledgement) {

    /**
     * 使用给定参数初始化 {@code BatchWriteResult}。
     */
    public BatchWriteResult(long acknowledgedRows, TransactionCommitReceipt receipt) {
        this(acknowledgedRows, receipt, receipt == null ? CommitAcknowledgement.NONE
                : CommitAcknowledgement.POSTGRESQL_XID8_VERIFIED);
    }

    /**
     * 校验构造参数并初始化 {@code BatchWriteResult}。
     */
    public BatchWriteResult {
        if (acknowledgedRows < 0 || commitAcknowledgement == null
                || (receipt != null) != (commitAcknowledgement == CommitAcknowledgement.POSTGRESQL_XID8_VERIFIED)) {
            throw new IllegalArgumentException("Inconsistent batch commit evidence");
        }
    }
}
