package cn.iocoder.yudao.framework.dbswitch.driver;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 上下文持有的有界原生调用。超时工作线程继续占用额度，直到自身退出且
 * 物理连接已关闭。仅取消 Future 绝不能证明终止。 */
final class NativeCallControl implements AutoCloseable {

    private final ThreadPoolExecutor operations;

    private final ThreadPoolExecutor closures;

    private final Semaphore capacity;

    /**
     * 校验构造参数并初始化 {@code NativeCallControl}。
     */
    NativeCallControl(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Native operation limit is required");
        }
        var factory = Thread.ofPlatform().daemon(true).inheritInheritableThreadLocals(false).name("dbswitch-native-", 0)
                .factory();
        ThreadFactory neutral = task -> {
            var thread = factory.newThread(task);
            thread.setContextClassLoader(NativeCallControl.class.getClassLoader());
            return thread;
        };
        capacity = new Semaphore(limit);
        operations = new ThreadPoolExecutor(limit, limit, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(limit),
                neutral, new ThreadPoolExecutor.AbortPolicy());
        operations.allowCoreThreadTimeOut(true);
        closures = new ThreadPoolExecutor(Math.min(4, limit), Math.min(4, limit), 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(limit), neutral, new ThreadPoolExecutor.AbortPolicy());
        closures.allowCoreThreadTimeOut(true);
    }

    /**
     * 将当前值绑定到预编译语句参数。
     */
    Session bind(Connection connection, ClassLoader loader, Runnable quarantine, Runnable terminated) {
        return new Session(connection, loader, quarantine, terminated);
    }

    /**
     * 关闭 {@code NativeCallControl} 当前持有的资源。
     */
    @Override
    public void close() {
        operations.shutdown();
        closures.shutdown();
    }

    final class Session {

        private final Connection connection;

        private final ClassLoader loader;

        private final Runnable quarantine, terminated;

        private final AtomicBoolean released = new AtomicBoolean();

        private final CompletableFuture<Void> closed = new CompletableFuture<>();

        private boolean active, closing, physicallyClosed, expired;

        private long closeWaitMillis = 30_000;

        private FutureTask<?> current;

        private Thread operationThread;

        private long operationDeadlineNanos;

        /**
         * 校验构造参数并初始化 {@code Session}。
         */
        Session(Connection connection, ClassLoader loader, Runnable quarantine, Runnable terminated) {
            this.connection = Objects.requireNonNull(connection);
            this.loader = Objects.requireNonNull(loader);
            this.quarantine = Objects.requireNonNull(quarantine);
            this.terminated = Objects.requireNonNull(terminated);
        }

        /**
         * 调用当前受控运行时的目标方法并传播调用结果。
         */
        Object invoke(Callable<?> callable, long timeoutMillis) throws SQLException {
            if (timeoutMillis < 1 || callable == null) {
                throw new IllegalArgumentException("Native deadline and operation are required");
            }
            long started = System.nanoTime();
            boolean nested;
            synchronized (this) {
                nested = active && operationThread == Thread.currentThread();
                if (nested) {
                    if (closing || expired || started >= operationDeadlineNanos) {
                        throw new SQLTimeoutException("Enclosing native operation deadline has expired");
                    }
                    if (TimeUnit.MILLISECONDS.toNanos(timeoutMillis) < operationDeadlineNanos - started) {
                        throw new SQLTimeoutException("Nested native operation requires the enclosing deadline");
                    }
                }
            }
            // 同一受控工作线程的产品适配器复用外层额度和截止时间。
            // 不持有监视器执行：外层到期必须仍能隔离并关闭物理连接。
            if (nested) {
                try {
                    return withLoader(callable);
                } catch (Exception | LinkageError failure) {
                    throw safeFailure(failure);
                }
            }
            FutureTask<Object> task;
            CompletableFuture<Object> completed = new CompletableFuture<>();
            synchronized (this) {
                if (closing || physicallyClosed) {
                    throw new SQLException("Native connection is closing");
                }
                if (active) {
                    throw new SQLTransientConnectionException(
                            "Concurrent native operations on one connection are not supported");
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new SQLException("Native operation interrupted before submission");
                }
                if (!capacity.tryAcquire()) {
                    throw new SQLTransientConnectionException("Native operation capacity is exhausted");
                }
                active = true;
                closeWaitMillis = timeoutMillis;
                operationDeadlineNanos = started + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
                task = new FutureTask<>(() -> {
                    synchronized (Session.this) {
                        operationThread = Thread.currentThread();
                    }
                    try {
                        return withLoader(callable);
                    } finally {
                        synchronized (Session.this) {
                            operationThread = null;
                        }
                    }
                });
                current = task;
            }
            try {
                operations.execute(() -> {
                    Object result = null;
                    Throwable failure = null;
                    try {
                        task.run();
                        result = task.get();
                    } catch (Exception | LinkageError invalid) {
                        failure = invalid;
                    } finally {
                        synchronized (Session.this) {
                            active = false;
                            current = null;
                        }
                        capacity.release();
                        confirmTermination();
                    }
                    if (failure == null) {
                        completed.complete(result);
                    } else {
                        completed.completeExceptionally(failure);
                    }
                });
            } catch (RejectedExecutionException rejected) {
                synchronized (this) {
                    active = false;
                    current = null;
                }
                capacity.release();
                confirmTermination();
                throw new SQLTransientConnectionException("Native operation capacity is exhausted");
            }
            try {
                long remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis) - (System.nanoTime() - started);
                if (remaining <= 0) {
                    throw new TimeoutException();
                }
                return completed.get(remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException timeout) {
                expire(task);
                throw new SQLTimeoutException("Native operation exceeded its wall-clock deadline; outcome is unknown");
            } catch (InterruptedException interrupted) {
                expire(task);
                Thread.currentThread().interrupt();
                throw new SQLTimeoutException("Native operation was interrupted; outcome is unknown");
            } catch (CancellationException cancelled) {
                expire(task);
                throw new SQLTimeoutException("Native operation was cancelled; outcome is unknown");
            } catch (ExecutionException failure) {
                // 产品代码不得暴露厂商消息、原因、请求体或端点。
                throw safeFailure(failure);
            }
        }

        /**
         * 根据当前输入构建并返回 {@code java.sql.SQLFeatureNotSupportedException}。
         */
        private SQLException safeFailure(Throwable failure) {
            Throwable cause = failure;
            for (int depth = 0; depth < 16 && cause.getCause() != null; depth++) {
                if (cause instanceof SQLException) {
                    break;
                }
                cause = cause.getCause();
            }
            String message = "Native operation did not return a usable result";
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && !state.matches("[A-Z0-9]{5}")) {
                    state = null;
                }
                if (sql instanceof java.sql.SQLFeatureNotSupportedException) {
                    return new java.sql.SQLFeatureNotSupportedException(message, state, sql.getErrorCode());
                }
                if (sql instanceof SQLTimeoutException) {
                    return new SQLTimeoutException(message, state, sql.getErrorCode());
                }
                return new SQLException(message, state, sql.getErrorCode());
            }
            return new SQLException(message);
        }

        /**
         * 标记当前作用域过期并触发终止或清理。
         */
        private void expire(FutureTask<?> task) {
            synchronized (this) {
                expired = true;
            }
            task.cancel(true);
            requestClose();
        }

        /**
         * 关闭数据库连接。
         */
        void closeConnection() throws SQLException {
            requestClose();
            long wait;
            boolean timedOut;
            synchronized (this) {
                wait = closeWaitMillis;
                timedOut = expired;
            }
            // 原始截止时间已过。Try-with-resources 不得再次等待。
            if (timedOut) {
                if (!isTerminated()) {
                    throw new SQLTimeoutException("Native connection termination is not yet confirmed");
                }
                return;
            }
            try {
                closed.get(wait, TimeUnit.MILLISECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new SQLTimeoutException("Native connection close was interrupted");
            } catch (TimeoutException | ExecutionException failure) {
                throw new SQLTimeoutException("Native connection close is not confirmed");
            }
            if (!isTerminated()) {
                throw new SQLTimeoutException("Native operation still owns the physical connection");
            }
        }

        /**
         * 不回退到无界线程；未确认的资源仍隔离并占用额度。
         */
        private void requestClose() {
            FutureTask<?> task;
            synchronized (this) {
                if (closing) {
                    return;
                }
                closing = true;
                task = current;
            }
            quarantine.run();
            if (task != null) {
                task.cancel(true);
            }
            try {
                closures.execute(() -> {
                    try {
                        withLoader(() -> {
                            connection.close();
                            return null;
                        });
                        boolean confirmed = (boolean) withLoader(connection::isClosed);
                        synchronized (Session.this) {
                            physicallyClosed = confirmed;
                        }
                        confirmTermination();
                        if (confirmed) {
                            closed.complete(null);
                        } else {
                            closed.completeExceptionally(new SQLException("Physical closure is unconfirmed"));
                        }
                    } catch (Exception | LinkageError failure) {
                        closed.completeExceptionally(new SQLException("Physical closure is unconfirmed"));
                    } finally {
                        confirmTermination();
                    }
                });
            } catch (RejectedExecutionException rejected) {
                // 不回退到无界线程；未确认的资源仍隔离并占用额度。
                closed.completeExceptionally(new SQLException("Native close capacity is unavailable"));
            }
        }

        /**
         * 判断终止。
         */
        boolean isTerminated() {
            synchronized (this) {
                return physicallyClosed && !active;
            }
        }

        /**
         * 检查当前运行资源是否正在关闭。
         */
        boolean isClosing() {
            synchronized (this) {
                return closing;
            }
        }

        /**
         * 中止当前连接的底层数据库操作。
         */
        void abortConnection() {
            synchronized (this) {
                expired = true;
            }
            requestClose();
        }

        /**
         * 确认底层运行资源已经真正终止。
         */
        private void confirmTermination() {
            if (isTerminated() && released.compareAndSet(false, true)) {
                terminated.run();
            }
        }

        /**
         * 在指定驱动类加载器上下文中调用当前操作。
         */
        private Object withLoader(Callable<?> operation) throws Exception {
            var thread = Thread.currentThread();
            var previous = thread.getContextClassLoader();
            try {
                thread.setContextClassLoader(loader);
                return operation.call();
            } finally {
                thread.setContextClassLoader(previous);
            }
        }
    }
}
