package cn.iocoder.yudao.framework.dbswitch.core.service;

/** 提交、回滚与连接清理结果分别表达；不保存驱动消息或数据内容。 */
public record GuardedMutationResult(Status status, Failure failure, boolean cleanupConfirmed) {

    public enum Status {APPLIED, NOT_APPLIED, UNKNOWN}

    public enum Failure {NONE, CONFLICT, UNSUPPORTED, WRITE_FAILED}

    /**
     * 使用给定参数初始化 {@code GuardedMutationResult}。
     */
    public GuardedMutationResult(Status status, Failure failure) {
        this(status, failure, true);
    }

    /**
     * 校验构造参数并初始化 {@code GuardedMutationResult}。
     */
    public GuardedMutationResult {
        java.util.Objects.requireNonNull(status);
        java.util.Objects.requireNonNull(failure);
    }
}
