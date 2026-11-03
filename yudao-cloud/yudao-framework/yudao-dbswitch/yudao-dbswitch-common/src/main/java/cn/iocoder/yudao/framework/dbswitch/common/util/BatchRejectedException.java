package cn.iocoder.yudao.framework.dbswitch.common.util;

/** 在进入写入协议前，本地校验或转换已拒绝该批次。不保留任何源值。 */
public final class BatchRejectedException extends IllegalArgumentException {

    /**
     * 初始化 {@code BatchRejectedException}及其父类状态。
     */
    public BatchRejectedException(String message) {
        super(message);
    }
}
