package cn.iocoder.yudao.framework.dbswitch.pgwriter.bulkprocessor;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.bulkprocessor.handler.IBulkWriteHandler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 批量写入缓冲处理器,按批次大小或定时间隔攒批实体并触发批量落库,关闭时刷写剩余数据。
 */
public class BulkProcessor<TEntity> implements AutoCloseable {

    private final ScheduledThreadPoolExecutor scheduler;

    private final ScheduledFuture<?> scheduledFuture;

    private volatile boolean closed = false;

    private final IBulkWriteHandler<TEntity> handler;

    private final int bulkSize;

    private List<TEntity> batchedEntities;

    /**
     * 使用给定参数初始化 {@code BulkProcessor}。
     */
    public BulkProcessor(IBulkWriteHandler<TEntity> handler, int bulkSize) {
        this(handler, bulkSize, null);
    }

    /**
     * 初始化 {@code BulkProcessor}。
     */
    public BulkProcessor(IBulkWriteHandler<TEntity> handler, int bulkSize, Duration flushInterval) {

        this.handler = handler;
        this.bulkSize = bulkSize;

        // 从空的批次实体列表开始：
        this.batchedEntities = new ArrayList<>();

        if (flushInterval != null) {
            // 为基于时间的刷新间隔创建调度器：
            this.scheduler = new ScheduledThreadPoolExecutor(1);
            this.scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            this.scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
            this.scheduledFuture = this.scheduler
                    .scheduleWithFixedDelay(new Flush(), flushInterval.toMillis(), flushInterval.toMillis(),
                            TimeUnit.MILLISECONDS);
        } else {
            this.scheduler = null;
            this.scheduledFuture = null;
        }
    }

    /**
     * 将当前值加入集合或累计统计。
     */
    public synchronized BulkProcessor<TEntity> add(TEntity entity) {
        batchedEntities.add(entity);
        executeIfNeccessary();
        return this;
    }

    /**
     * 关闭 {@code BulkProcessor} 当前持有的资源。
     */
    @Override
    public void close() throws Exception {
        // 处理器已关闭时，不再继续执行：
        if (closed) {
            return;
        }
        closed = true;

        // 取消定时刷新间隔的 Future：
        Optional.ofNullable(this.scheduledFuture).ifPresent(future -> future.cancel(false));
        Optional.ofNullable(this.scheduler).ifPresent(ScheduledThreadPoolExecutor::shutdown);

        // 检查是否还有待写入的实体
        if (batchedEntities.size() > 0) {
            execute();
        }
    }

    /**
     * 在缓冲实体数量达到批次上限时触发写入。
     */
    private void executeIfNeccessary() {
        if (batchedEntities.size() >= bulkSize) {
            execute();
        }
    }

    // 当前需要在锁保护下执行

    /**
     * 交换当前缓存批次并调用写入处理器，允许后续继续收集实体。
     */
    private void execute() {
        // 分配新的列表：
        final List<TEntity> entities = batchedEntities;
        // 可以重新开始收集批次实体：
        batchedEntities = new ArrayList<>();
        // 将此前收集的批次实体写入 PostgreSQL：
        write(entities);
    }

    /**
     * 调用批次写入处理器，并将失败传播为当前处理异常。
     */
    private void write(List<TEntity> entities) {
        try {
            handler.write(entities);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    class Flush implements Runnable {

        /**
         * 在批次处理器同步锁内检查关闭状态并刷新已缓存实体。
         */
        @Override
        public void run() {
            synchronized (BulkProcessor.this) {
                if (closed) {
                    return;
                }
                if (batchedEntities.size() == 0) {
                    return;
                }
                execute();
            }

        }
    }
}
