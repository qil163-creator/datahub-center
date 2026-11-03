package cn.iocoder.yudao.framework.seatunnel;

import cn.iocoder.yudao.framework.seatunnel.config.SeatunnelProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * 初始化 SeaTunnel REST 服务地址。
 *
 * @author zrx
 */
public class BusinessInitializer implements ApplicationRunner {

    private final SeatunnelProperties properties;

    /**
     * 初始化 {@code BusinessInitializer}。
     */
    public BusinessInitializer(SeatunnelProperties properties) {
        this.properties = properties;
    }

    /**
     * 将应用配置的 SeaTunnel REST 地址设置到统一客户端入口。
     */
    @Override
    public void run(ApplicationArguments args) {
        SeatunnelRestApi.SERVER_URL = properties.getServerUrl();
    }
}
