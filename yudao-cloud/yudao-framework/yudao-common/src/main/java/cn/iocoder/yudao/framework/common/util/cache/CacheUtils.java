package cn.iocoder.yudao.framework.common.util.cache;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Cache 工具类
 *
 * @author 芋道源码
 */
public class CacheUtils {

    /**
     * 异步刷新的 LoadingCache 最大缓存数量
     *
     * @see <a href="">本地缓存 CacheUtils 工具类建议</a>
     */
    private static final Integer CACHE_MAX_SIZE = 10000;

    private static final int RELOAD_WORKERS = 4;
    private static final int RELOAD_QUEUE_CAPACITY = 128;

    /** 全局缓存共用刷新预算，过载的刷新由 Guava 在后续读取时重试。 */
    private static final ThreadPoolExecutor RELOAD_EXECUTOR = new ThreadPoolExecutor(RELOAD_WORKERS, RELOAD_WORKERS, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(RELOAD_QUEUE_CAPACITY),
            Thread.ofPlatform().daemon().name("cache-reload-", 1).factory(), new ThreadPoolExecutor.AbortPolicy());

    /**
     * 构建异步刷新的 LoadingCache 对象
     *
     * 注意：如果你的缓存和 ThreadLocal 有关系，要么自己处理 ThreadLocal 的传递，要么使用 {@link #buildCache(Duration, CacheLoader)} 方法
     *
     * 或者简单理解：
     * 1、和“人”相关的，使用 {@link #buildCache(Duration, CacheLoader)} 方法
     * 2、和“全局”、“系统”相关的，使用当前缓存方法
     *
     * @param duration 过期时间
     * @param loader  CacheLoader 对象
     * @return LoadingCache 对象
     */
    public static <K, V> LoadingCache<K, V> buildAsyncReloadingCache(Duration duration, CacheLoader<K, V> loader) {
        return CacheBuilder.newBuilder()
                .maximumSize(CACHE_MAX_SIZE)
                // 只阻塞当前数据加载线程，其他线程返回旧值
                .refreshAfterWrite(duration)
                // 通过 asyncReloading 实现全异步加载，包括 refreshAfterWrite 被阻塞的加载线程
                .build(CacheLoader.asyncReloading(loader, RELOAD_EXECUTOR));
    }

    /**
     * 构建同步刷新的 LoadingCache 对象
     *
     * @param duration 过期时间
     * @param loader  CacheLoader 对象
     * @return LoadingCache 对象
     */
    public static <K, V> LoadingCache<K, V> buildCache(Duration duration, CacheLoader<K, V> loader) {
        return CacheBuilder.newBuilder()
                .maximumSize(CACHE_MAX_SIZE)
                // 只阻塞当前数据加载线程，其他线程返回旧值
                .refreshAfterWrite(duration)
                .build(loader);
    }

}
