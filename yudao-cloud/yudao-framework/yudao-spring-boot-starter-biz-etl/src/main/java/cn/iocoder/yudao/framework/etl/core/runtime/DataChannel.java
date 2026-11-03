package cn.iocoder.yudao.framework.etl.core.runtime;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 节点间内存数据通道：有界阻塞队列形成天然背压
 *
 * 同时计量投递的数据行、上游因队列已满而等待的累计时间（背压）和下游因队列为空而等待的累计时间，
 * 供运行观测采样；只在队列满或空的慢路径上计时，正常传输不额外取时钟。
 *
 * @author Li
 */
public class DataChannel {

    private static final int DEFAULT_CAPACITY = 1000;

    private final int capacity;

    private final LinkedBlockingQueue<DataPacket> queue;

    private final AtomicLong delivered = new AtomicLong();

    private final AtomicLong blockedNanos = new AtomicLong();

    private final AtomicLong starvedNanos = new AtomicLong();

    /**
     * 使用给定参数初始化 {@code DataChannel}。
     */
    public DataChannel() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * 校验构造参数并初始化 {@code DataChannel}。
     */
    public DataChannel(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("通道容量必须大于 0");
        }
        this.capacity = capacity;
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    /**
     * 尝试写入，超时返回 false（调用方循环重试并检查停止标记）
     */
    public boolean push(DataPacket packet, long timeoutMillis) throws InterruptedException {
        boolean accepted = queue.offer(packet);
        if (!accepted) {
            long started = System.nanoTime();
            try {
                accepted = queue.offer(packet, timeoutMillis, TimeUnit.MILLISECONDS);
            } finally {
                blockedNanos.addAndGet(System.nanoTime() - started);
            }
        }
        if (accepted && packet.getType() == DataPacket.Type.DATA) {
            delivered.incrementAndGet();
        }
        return accepted;
    }

    /**
     * 尝试读取，超时返回 null（调用方循环重试并检查停止标记）
     */
    public DataPacket poll(long timeoutMillis) throws InterruptedException {
        DataPacket packet = queue.poll();
        if (packet != null) {
            return packet;
        }
        long started = System.nanoTime();
        try {
            return queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        } finally {
            starvedNanos.addAndGet(System.nanoTime() - started);
        }
    }

    /** 当前排队的包数（含结束/异常控制包），为采样时刻的近似值。 */
    public int queued() {
        return queue.size();
    }

    /**
     * 返回容量。
     */
    public int capacity() {
        return capacity;
    }

    /** 已成功写入的数据行数，不含结束/异常控制包。 */
    public long delivered() {
        return delivered.get();
    }

    /**
     * 返回当前通道写入受阻的累计毫秒数。
     */
    public long blockedMillis() {
        return TimeUnit.NANOSECONDS.toMillis(blockedNanos.get());
    }

    /**
     * 返回当前通道等待上游输入的累计毫秒数。
     */
    public long starvedMillis() {
        return TimeUnit.NANOSECONDS.toMillis(starvedNanos.get());
    }

}
