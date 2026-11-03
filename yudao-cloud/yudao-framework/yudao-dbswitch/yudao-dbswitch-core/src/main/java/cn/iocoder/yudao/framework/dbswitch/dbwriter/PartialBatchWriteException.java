package cn.iocoder.yudao.framework.dbswitch.dbwriter;

/**
 * 非事务批次失败，包含逐项确认事实。确认
 * 不等于原子提交、变更行数或可见性证明。绝不重试整批。
 * 计数必须完整划分提交的输入；不保留载荷、厂商消息或原因。
 */
public final class PartialBatchWriteException extends RuntimeException {

    private final long acknowledgedRows;

    private final long rejectedRows;

    private final long unknownRows;

    private final boolean terminationConfirmed;

    /**
     * 校验构造参数并初始化 {@code PartialBatchWriteException}及其父类状态。
     */
    public PartialBatchWriteException(long acknowledgedRows, long rejectedRows, long unknownRows,
            boolean terminationConfirmed) {
        super("批次未完整成功，必须依据逐项确认结果核查，禁止自动整批重投");
        if (acknowledgedRows < 0 || rejectedRows < 0 || unknownRows < 0
                || Math.addExact(Math.addExact(acknowledgedRows, rejectedRows), unknownRows) < 1
                || rejectedRows == 0 && unknownRows == 0 && terminationConfirmed) {
            throw new IllegalArgumentException("Invalid partial batch acknowledgement");
        }
        this.acknowledgedRows = acknowledgedRows;
        this.rejectedRows = rejectedRows;
        this.unknownRows = unknownRows;
        this.terminationConfirmed = terminationConfirmed;
    }

    /**
     * 返回已确认写入行数。
     */
    public long acknowledgedRows() {
        return acknowledgedRows;
    }

    /**
     * 返回已拒绝数据行。
     */
    public long rejectedRows() {
        return rejectedRows;
    }

    /**
     * 返回未知数据行。
     */
    public long unknownRows() {
        return unknownRows;
    }

    /**
     * 返回终止已确认。
     */
    public boolean terminationConfirmed() {
        return terminationConfirmed;
    }

    /**
     * 计算当前批次已经确认和未完成的数据行总数。
     */
    public long inputRows() {
        return Math.addExact(Math.addExact(acknowledgedRows, rejectedRows), unknownRows);
    }
}
