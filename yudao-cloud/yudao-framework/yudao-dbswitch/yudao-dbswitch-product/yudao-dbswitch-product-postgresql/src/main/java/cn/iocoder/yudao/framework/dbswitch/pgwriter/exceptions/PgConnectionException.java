package cn.iocoder.yudao.framework.dbswitch.pgwriter.exceptions;

/**
 * PostgreSQL 连接异常,无法从 JDBC 连接解包出底层 PGConnection 时抛出。
 */
public class PgConnectionException extends RuntimeException {

    /**
     * 初始化 {@code PgConnectionException}及其父类状态。
     */
    public PgConnectionException(String message) {
        super(message);
    }

    /**
     * 初始化 {@code PgConnectionException}。
     */
    public PgConnectionException() {
    }

    /**
     * 初始化 {@code PgConnectionException}及其父类状态。
     */
    public PgConnectionException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 初始化 {@code PgConnectionException}及其父类状态。
     */
    public PgConnectionException(Throwable cause) {
        super(cause);
    }

    /**
     * 初始化 {@code PgConnectionException}及其父类状态。
     */
    public PgConnectionException(String message, Throwable cause, boolean enableSuppression,
            boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
