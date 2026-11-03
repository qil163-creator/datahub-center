package cn.iocoder.yudao.framework.dbswitch.pgwriter.exceptions;

/**
 * 实体保存失败异常,COPY 批量写入时按列序列化实体出错时抛出。
 */
public class SaveEntityFailedException extends RuntimeException {

    /**
     * 初始化 {@code SaveEntityFailedException}及其父类状态。
     */
    public SaveEntityFailedException(String message) {
        super(message);
    }

    /**
     * 初始化 {@code SaveEntityFailedException}。
     */
    public SaveEntityFailedException() {
    }

    /**
     * 初始化 {@code SaveEntityFailedException}及其父类状态。
     */
    public SaveEntityFailedException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 初始化 {@code SaveEntityFailedException}及其父类状态。
     */
    public SaveEntityFailedException(Throwable cause) {
        super(cause);
    }

    /**
     * 初始化 {@code SaveEntityFailedException}及其父类状态。
     */
    public SaveEntityFailedException(String message, Throwable cause, boolean enableSuppression,
            boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
