package cn.iocoder.yudao.framework.dbswitch.pgwriter.configuration;

/**
 * COPY 批量写入配置默认实现,持有二进制写入缓冲区大小(默认 64KB)。
 */
public class Configuration implements IConfiguration {

    private final int bufferSize;

    /**
     * 使用给定参数初始化 {@code Configuration}。
     */
    public Configuration() {
        this(65536);
    }

    /**
     * 初始化 {@code Configuration}。
     */
    public Configuration(int bufferSize) {
        this.bufferSize = bufferSize;
    }

    /**
     * 返回缓冲区大小。
     */
    @Override
    public int getBufferSize() {
        return bufferSize;
    }
}
