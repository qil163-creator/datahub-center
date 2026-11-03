package cn.iocoder.yudao.framework.seatunnel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SeaTunnel REST 配置。
 */
@ConfigurationProperties(prefix = SeatunnelProperties.PREFIX)
public class SeatunnelProperties {

    public static final String PREFIX = "seatunnel";

    /**
     * SeaTunnel REST 服务地址。
     */
    private String serverUrl = "http://localhost:5801";

    /**
     * 返回服务端地址。
     */
    public String getServerUrl() {
        return serverUrl;
    }

    /**
     * 设置服务端地址。
     */
    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }
}
