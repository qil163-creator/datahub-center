package cn.iocoder.yudao.module.governance.framework.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 字段加密密钥中心：本地主密钥。
 * <p>
 * 主密钥只能来自部署环境（环境变量或配置中心），不得写入源码、SQL 或版本库配置。每个版本是 Base64 编码、
 * 至少 32 字节的随机值；新数据用 {@link #activeVersion} 加密，历史密文按密文里携带的版本解密。
 * 轮换时新增一个版本并切换 activeVersion，旧版本保留到历史密文清理完毕。未配置时字段加密失败关闭。
 */
@Data
@Component
@ConfigurationProperties(prefix = "yudao.governance.key-center")
public class SafetyKeyCenterProperties {

    /** 主密钥版本 → Base64 主密钥；默认为空（不可用）。 */
    private Map<String, String> masterKeys = Map.of();

    /** 新加密使用的主密钥版本。 */
    private String activeVersion;
}
