package cn.iocoder.yudao.framework.dbswitch.data.diagnostic;

import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** 仅供 DNS、建连和清理使用；超时任务不释放工作线程，迟到资源由原线程关闭。 */
final class DiagnosticIo implements AutoCloseable {

    private final ThreadPoolExecutor executor;

    /**
     * 初始化 {@code DiagnosticIo}。
     */
    DiagnosticIo(int workers, int queueSize) {
        var factory = Thread.ofPlatform().daemon(true).inheritInheritableThreadLocals(false)
                .name("dbswitch-diagnostic-io-", 0).factory();
        ThreadFactory neutral = task -> {
            var thread = factory.newThread(task);
            thread.setContextClassLoader(DiagnosticIo.class.getClassLoader());
            return thread;
        };
        executor = new ThreadPoolExecutor(workers, workers, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueSize), neutral, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
    }

    /**
     * 在有限等待时间内运行诊断 I/O 回调，并交付结果或超时失败。
     */
    <T> T call(long timeoutMillis, Callable<T> operation, Consumer<T> lateCleanup) throws Exception {
        if (timeoutMillis < 1) {
            throw new SQLTimeoutException("诊断预算已耗尽");
        }
        var delivery = new Delivery<T>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            executor.execute(() -> {
                synchronized (delivery) {
                    // 已在队列中到期的任务不再建立连接或启动网络访问。
                    if (delivery.abandoned || System.nanoTime() >= deadline) {
                        delivery.failure = new SQLTimeoutException("诊断排队已超时");
                        delivery.done = true;
                        delivery.notifyAll();
                        return;
                    }
                }
                T value;
                try {
                    value = operation.call();
                } catch (Exception | LinkageError failure) {
                    synchronized (delivery) {
                        delivery.failure = failure;
                        delivery.done = true;
                        delivery.notifyAll();
                    }
                    return;
                }
                synchronized (delivery) {
                    if (!delivery.abandoned) {
                        delivery.value = value;
                        delivery.done = true;
                        delivery.notifyAll();
                        return;
                    }
                }
                // 交接与放弃在同一监视器内决定，不会泄漏超时后创建的物理连接。
                lateCleanup.accept(value);
            });
        } catch (RejectedExecutionException failure) {
            throw new SQLTransientConnectionException("诊断工作容量已满");
        }
        synchronized (delivery) {
            while (!delivery.done) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    delivery.abandoned = true;
                    throw new SQLTimeoutException("诊断操作已超时，后台清理结果尚未确认");
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(delivery, remaining);
                } catch (InterruptedException failure) {
                    delivery.abandoned = true;
                    Thread.currentThread().interrupt();
                    throw new SQLTimeoutException("诊断操作已中断，后台清理结果尚未确认");
                }
            }
            if (delivery.failure instanceof Exception failure) {
                throw failure;
            }
            if (delivery.failure != null) {
                throw new IllegalStateException("诊断驱动不可用");
            }
            return delivery.value;
        }
    }

    /**
     * 关闭 {@code DiagnosticIo} 当前持有的资源。
     */
    @Override
    public void close() {
        executor.shutdown();
    }

    private static final class Delivery<T> {

        private boolean done, abandoned;

        private T value;

        private Throwable failure;
    }
}
