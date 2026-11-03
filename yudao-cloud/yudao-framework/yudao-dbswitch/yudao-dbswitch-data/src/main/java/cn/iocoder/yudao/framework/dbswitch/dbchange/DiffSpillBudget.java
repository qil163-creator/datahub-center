package cn.iocoder.yudao.framework.dbswitch.dbchange;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** 进程级资源记账，独立于 Spring 上下文和租户；删除失败时继续占用配额。 */
final class DiffSpillBudget {

    private static final Map<Path, Bucket> DIRECTORIES = new HashMap<>();

    private static final class Bucket {

        final long limit;

        long bytes;

        int leases;

        /**
         * 初始化 {@code Bucket}。
         */
        Bucket(long limit) {
            this.limit = limit;
        }
    }

    /**
     * 根据当前配置获取可用资源并登记其使用配额。
     */
    static synchronized Lease acquire(Path realDirectory, long limit) {
        Bucket bucket = DIRECTORIES.get(realDirectory);
        if (bucket == null) {
            bucket = new Bucket(limit);
            DIRECTORIES.put(realDirectory, bucket);
        }
        if (bucket.limit != limit) {
            throw new IllegalStateException("Active difference spools require one shared directory budget");
        }
        bucket.leases++;
        return new Lease(realDirectory, bucket);
    }

    static final class Lease implements AutoCloseable {

        private final Path directory;

        private final Bucket bucket;

        private long bytes;

        private boolean closed;

        /**
         * 初始化 {@code Lease}。
         */
        private Lease(Path directory, Bucket bucket) {
            this.directory = directory;
            this.bucket = bucket;
        }

        /**
         * 在共享预算锁内为当前租约预留磁盘字节，已关闭或超出共享限额时拒绝。
         */
        void reserve(long size) {
            synchronized (DiffSpillBudget.class) {
                if (closed || size < 0) {
                    throw new IllegalStateException("Difference spool budget lease is closed");
                }
                if (size > bucket.limit - bucket.bytes) {
                    throw new IllegalStateException("Difference spools exceed the shared disk budget");
                }
                bucket.bytes += size;
                bytes += size;
            }
        }

        /**
         * 记录当前受管资源已删除的状态。
         */
        void deleted(long size) {
            synchronized (DiffSpillBudget.class) {
                if (closed || size < 0 || size > bytes) {
                    throw new IllegalStateException("Difference spool budget ownership mismatch");
                }
                bucket.bytes -= size;
                bytes -= size;
            }
        }

        /**
         * 关闭 {@code DiffSpillBudget} 当前持有的资源。
         */
        @Override
        public void close() {
            synchronized (DiffSpillBudget.class) {
                if (closed) {
                    return;
                }
                // 清理结果不确定时不释放配额；管理方关闭读取器后可以重试删除。
                if (bytes != 0) {
                    throw new IllegalStateException("Undeleted difference spool bytes remain charged");
                }
                closed = true;
                if (--bucket.leases == 0 && bucket.bytes == 0) {
                    DIRECTORIES.remove(directory, bucket);
                }
            }
        }
    }
}
