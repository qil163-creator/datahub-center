package cn.iocoder.yudao.framework.dbswitch.pgwriter.nativecopy;

import java.io.IOException;
import java.io.OutputStream;
import java.sql.SQLException;
import java.util.Objects;

/** 自身不进行缓冲；写入或刷新失败后，绝不能再发送成功结束标记。 */
public final class CopySessionOutputStream extends OutputStream {

    private final CopySession session;

    private boolean failed;

    private boolean closed;

    /**
     * 校验构造参数并初始化 {@code CopySessionOutputStream}。
     */
    public CopySessionOutputStream(CopySession session) {
        this.session = Objects.requireNonNull(session);
    }

    /**
     * 按输出格式将数据写入当前文件或输出端口。
     */
    @Override
    public void write(int value) throws IOException {
        write(new byte[]{(byte) value}, 0, 1);
    }

    /**
     * 按输出格式将数据写入当前文件或输出端口。
     */
    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        ensureWritable();
        try {
            session.writeToCopy(bytes, offset, length);
        } catch (SQLException failure) {
            failed = true;
            throw new IOException("COPY write failed", failure);
        } catch (RuntimeException | Error failure) {
            failed = true;
            throw failure;
        }
    }

    /**
     * 将缓冲区中已积累的数据提交给当前输出端。
     */
    @Override
    public void flush() throws IOException {
        ensureWritable();
        try {
            session.flushCopy();
        } catch (SQLException failure) {
            failed = true;
            throw new IOException("COPY flush failed", failure);
        } catch (RuntimeException | Error failure) {
            failed = true;
            throw failure;
        }
    }

    /**
     * 关闭 {@code CopySessionOutputStream} 当前持有的资源。
     */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        if (failed) {
            throw new IOException("Cannot finish a failed COPY stream");
        }
        try {
            session.endCopy();
        } catch (SQLException failure) {
            failed = true;
            throw new IOException("COPY completion failed", failure);
        } catch (RuntimeException | Error failure) {
            failed = true;
            throw failure;
        }
    }

    /**
     * 确保可写状态。
     */
    private void ensureWritable() throws IOException {
        if (closed || failed) {
            throw new IOException("COPY stream is no longer writable");
        }
    }
}
