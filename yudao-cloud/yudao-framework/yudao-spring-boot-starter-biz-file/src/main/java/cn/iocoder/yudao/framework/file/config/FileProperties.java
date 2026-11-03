package cn.iocoder.yudao.framework.file.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 文件组件配置。连接凭据按调用传入，不保存在全局配置中。 */
@ConfigurationProperties(prefix = "yudao.file")
public class FileProperties {

    /** 是否自动装配文件存储及远程文件工厂；关闭时不影响调用方自行注册的组件。 */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
