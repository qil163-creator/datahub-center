package cn.iocoder.yudao.framework.dbswitch.pgwriter.exceptions;

/**
 * COPY 二进制写入失败异常,向 PostgreSQL 二进制流写入字段值出错时抛出。
 */
public class BinaryWriteFailedException extends RuntimeException {

    /**
     * 初始化 {@code BinaryWriteFailedException}及其父类状态。
     */
    public BinaryWriteFailedException(String message) {
        super(message);
    }

    /**
     * 初始化 {@code BinaryWriteFailedException}。
     */
    public BinaryWriteFailedException() {
    }

    /**
     * 初始化 {@code BinaryWriteFailedException}及其父类状态。
     */
    public BinaryWriteFailedException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 初始化 {@code BinaryWriteFailedException}及其父类状态。
     */
    public BinaryWriteFailedException(Throwable cause) {
        super(cause);
    }

    /**
     * 初始化 {@code BinaryWriteFailedException}及其父类状态。
     */
    public BinaryWriteFailedException(String message, Throwable cause, boolean enableSuppression,
            boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
