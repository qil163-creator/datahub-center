package cn.iocoder.yudao.framework.dbswitch.dbwriter;

/**
 * 外部写入已经尝试，但响应不足以确认远端执行结果及资源终止。
 * 调用方必须保留未知结果，不得自动回退、重写或释放执行位。
 * 不携带响应正文或原始原因链，避免驱动/网关错误中的凭据和数据进入日志。
 */
public final class UnconfirmedWriteException extends RuntimeException {

    private static final String MESSAGE = "远端写入结果未经确认，禁止自动重投或回退写入，请核查外部执行与产出";

    private final String requestLabel;

    private final ReceiptObservation receiptObservation;

    /**
     * 使用给定参数初始化 {@code UnconfirmedWriteException}。
     */
    public UnconfirmedWriteException() {
        this(null, ReceiptObservation.NO_RECEIPT);
    }

    /** 仅包含有界对账元数据；观测绝不能证明提交或物理退出。 */
    public UnconfirmedWriteException(String requestLabel, ReceiptObservation receiptObservation) {
        super(MESSAGE, null, false, true);
        if (requestLabel != null && !requestLabel.matches("[A-Za-z0-9_:-]{1,128}")
                || receiptObservation == null || requestLabel == null && receiptObservation != ReceiptObservation.NO_RECEIPT) {
            throw new IllegalArgumentException("Invalid unconfirmed write reference");
        }
        this.requestLabel = requestLabel;
        this.receiptObservation = receiptObservation;
    }

    /** 用于显式对账的不透明请求标识，绝不是重试指令。 */
    public String requestLabel() {
        return requestLabel;
    }

    /**
     * 返回回执观测结果。
     */
    public ReceiptObservation receiptObservation() {
        return receiptObservation;
    }

    public enum ReceiptObservation {
        NO_RECEIPT,
        UNRECOGNIZED,
        REPORTED_FAILURE,
        LABEL_ALREADY_USED,
        COMMITTED_NOT_VISIBLE,
        VISIBLE_INCOMPLETE,
        VISIBLE
    }
}
