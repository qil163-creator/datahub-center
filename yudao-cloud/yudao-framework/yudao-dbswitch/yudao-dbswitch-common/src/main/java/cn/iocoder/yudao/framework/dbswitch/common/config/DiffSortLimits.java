package cn.iocoder.yudao.framework.dbswitch.common.config;

/** 内存限额按每次比对计算；同一真实目录中的溢写限额在进程内共享。 */
public record DiffSortLimits(long memoryBytes, long maxSpillBytes, String directory, long maxSharedSpillBytes) {

    public static final long DEFAULT_SHARED_SPILL_BYTES = 16L * 1024 * 1024 * 1024;

    /**
     * 校验构造参数并初始化 {@code DiffSortLimits}。
     */
    public DiffSortLimits {
        if (memoryBytes < 1024 || maxSpillBytes < 1024 || maxSharedSpillBytes < 1024 || directory == null || directory.isBlank()) {
            throw new IllegalArgumentException("Invalid difference sort limits");
        }
        if (!java.nio.file.Path.of(directory).isAbsolute()) {
            throw new IllegalArgumentException("Difference sort directory must be an absolute deployment path");
        }
    }

    /**
     * 使用给定参数初始化 {@code DiffSortLimits}。
     */
    public DiffSortLimits(long memoryBytes, long maxSpillBytes, String directory) {
        this(memoryBytes, maxSpillBytes, directory, DEFAULT_SHARED_SPILL_BYTES);
    }

    /**
     * 创建当前组件的默认配置。
     */
    public static DiffSortLimits defaults() {
        return new DiffSortLimits(64L * 1024 * 1024, 8L * 1024 * 1024 * 1024,
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().toString());
    }
}
