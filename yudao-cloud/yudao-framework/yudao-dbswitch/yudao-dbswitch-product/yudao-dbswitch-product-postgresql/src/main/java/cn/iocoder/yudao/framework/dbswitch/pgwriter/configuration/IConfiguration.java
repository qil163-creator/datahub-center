package cn.iocoder.yudao.framework.dbswitch.pgwriter.configuration;

/**
 * COPY 批量写入配置接口,定义二进制写入缓冲区大小等参数的获取方法。
 */
public interface IConfiguration {

    /**
     * 返回缓冲区大小。
     */
    int getBufferSize();
}
