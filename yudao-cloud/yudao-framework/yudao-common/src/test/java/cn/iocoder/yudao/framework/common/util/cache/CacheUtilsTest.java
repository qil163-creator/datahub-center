package cn.iocoder.yudao.framework.common.util.cache;

import com.google.common.cache.CacheLoader;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheUtilsTest {

    @Test
    void readersRetainTheOldValueWhileTheRefreshWorkerIsBusy() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        var cache = CacheUtils.buildAsyncReloadingCache(Duration.ofDays(1), new CacheLoader<String, Integer>() {
            @Override
            public Integer load(String key) throws InterruptedException {
                int version = loads.incrementAndGet();
                if (version > 1) {
                    started.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("未收到刷新继续信号");
                    }
                }
                return version;
            }
        });
        try {
            assertEquals(1, cache.get("key"));
            cache.refresh("key");
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(1, cache.get("key"));
        } finally {
            release.countDown();
        }
    }
}
