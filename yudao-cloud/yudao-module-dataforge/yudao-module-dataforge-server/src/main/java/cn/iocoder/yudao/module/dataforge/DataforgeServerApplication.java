package cn.iocoder.yudao.module.dataforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 项目的启动类
 *
 * @author 芋道源码
 */
@SpringBootApplication
public class DataforgeServerApplication {

    /**
     * 启动当前业务服务应用。
     *
     * @param args 参数
     */
    public static void main(String[] args) {
        SpringApplication.run(DataforgeServerApplication.class, args);
    }

}
