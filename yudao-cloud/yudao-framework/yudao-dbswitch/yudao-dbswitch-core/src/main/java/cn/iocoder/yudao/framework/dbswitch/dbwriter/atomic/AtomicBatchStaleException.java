package cn.iocoder.yudao.framework.dbswitch.dbwriter.atomic;

/** 其他操作已在调用方预期快照之后替换该批次；没有改变任何数据。 */
public final class AtomicBatchStaleException extends RuntimeException {

    /**
     * 初始化 {@code AtomicBatchStaleException}及其父类状态。
     */
    public AtomicBatchStaleException() {
        super("Batch current payload does not match the expected revision hash");
    }
}
