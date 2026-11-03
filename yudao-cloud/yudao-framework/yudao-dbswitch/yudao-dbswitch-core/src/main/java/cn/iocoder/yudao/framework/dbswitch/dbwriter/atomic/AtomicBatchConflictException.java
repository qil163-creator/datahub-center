package cn.iocoder.yudao.framework.dbswitch.dbwriter.atomic;

/** 已提交的键对应不同不可变载荷或目标。绝不修改内容后重试。 */
public final class AtomicBatchConflictException extends RuntimeException {

    /**
     * 初始化 {@code AtomicBatchConflictException}及其父类状态。
     */
    public AtomicBatchConflictException() {
        super("Batch key is already bound to different content or target");
    }
}
