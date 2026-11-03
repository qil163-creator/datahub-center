package cn.iocoder.yudao.module.asset;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 项目的启动类
 *
 * @author 芋道源码
 */
@SpringBootApplication
public class AssetServerApplication {

    /**
     * 启动资产服务端应用后端应用。
     */
    public static void main(String[] args) {
        SpringApplication.run(AssetServerApplication.class, args);
    }

}
