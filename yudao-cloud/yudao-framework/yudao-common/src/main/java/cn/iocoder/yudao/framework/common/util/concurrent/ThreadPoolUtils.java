package cn.iocoder.yudao.framework.common.util.concurrent;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;

/** 创建由调用方负责关闭的后台任务调度器。 */
public final class ThreadPoolUtils {

    private ThreadPoolUtils() {
    }

    /**
     * 按给定并发度运行命名守护线程，取消的定时任务立即从等待队列中移除。
     *
     * @param workers 调度线程数
     * @param name 线程名前缀
     * @return 由调用方管理生命周期的调度器
     */
    public static ScheduledThreadPoolExecutor newDaemonScheduler(int workers, String name) {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(workers,
                Thread.ofPlatform().daemon().name(name + "-", 1).factory(), new ThreadPoolExecutor.AbortPolicy());
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
