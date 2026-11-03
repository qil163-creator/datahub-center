package cn.iocoder.yudao.framework.dbswitch.dbwriter.uxdb;

import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.sql.SQLException;

/** 仅提供文本 COPY 传输机制；产品写入器负责运行时和表准入，以及事务管理。 */
public final class UxdbCopyProtocol {

    /**
     * 初始化 {@code UxdbCopyProtocol}。
     */
    private UxdbCopyProtocol() {
    }

    /**
     * 在当前数据库连接上执行给定 SQL 语句。
     */
    public static long execute(JdbcBatchExecutor.WriteContext context, Object manager, String sql, byte[] payload,
            long rows) throws Exception {
        var copy = manager.getClass().getMethod("copyIn", String.class, java.io.InputStream.class, int.class);
        if (copy.getReturnType() != long.class) {
            throw new SQLException("COPY driver receipt contract does not match");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("COPY interrupted before sending");
        }
        try (var input = new ByteArrayInputStream(payload)) {
            context.markWriteProtocolStarted();
            Object result;
            try {
                result = copy.invoke(manager, sql, input, 64 * 1024);
            } catch (InvocationTargetException reflected) {
                Throwable cause = reflected.getCause();
                if (cause instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                if (cause instanceof Exception error) {
                    throw error;
                }
                throw new SQLException("COPY driver invocation failed");
            }
            context.markWriteProtocolFinished();
            if (!(result instanceof Long count) || count != rows) {
                throw new SQLException("COPY acknowledgement does not match submitted row count");
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("COPY interrupted after sending");
            }
            return count;
        }
    }
}
