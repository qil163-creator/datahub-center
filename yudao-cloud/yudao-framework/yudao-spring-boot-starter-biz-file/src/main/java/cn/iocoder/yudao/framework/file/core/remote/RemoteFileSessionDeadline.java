package cn.iocoder.yudao.framework.file.core.remote;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 套接字读超时之外的会话硬上限，主动关闭连接以打断可能阻塞的写操作。 */
final class RemoteFileSessionDeadline implements AutoCloseable {
    private static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().daemon().name("remote-file-deadline").factory());
    static { TIMER.setRemoveOnCancelPolicy(true); }
    private final ScheduledFuture<?> pending;
    private volatile boolean expired;

    RemoteFileSessionDeadline(Runnable abort) { this(Duration.ofMinutes(2), abort); }

    RemoteFileSessionDeadline(Duration timeout, Runnable abort) {
        pending = TIMER.schedule(() -> {
            expired = true;
            try { abort.run(); } catch (RuntimeException ignored) {
                // 连接断开失败不把远端异常或凭据交给调度线程的默认日志。
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    void check() throws IOException {
        if (expired) throw new IOException("文件操作超过会话时间上限");
    }

    @Override public void close() { pending.cancel(false); }
}
