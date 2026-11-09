package cn.iocoder.yudao.module.supply.framework.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 数据服务开放 API 安全配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "yudao.supply.data-api")
public class SupplyDataApiProperties {

    /**
     * 允许 FORWARD 类型 API 转发到的外部主机名。为空时禁止发布和执行转发 API。
     */
    private Set<String> forwardAllowHosts = new LinkedHashSet<>();

    /**
     * 部门应用调用的对外基址，例如 https://api.example.gov/supply/gateway。
     * 未配置时文档不得从管理端 URL 猜测。
     */
    private String publicBaseUrl = "";

    /**
     * 结果缓存。M1 默认关闭：缺少治理策略版本和发布 revision 时不能证明撤权及时。
     * 恢复条件见 docs/research/2026-09-16-supply-m0-design.md。
     */
    private boolean resultCacheEnabled = false;

    /**
     * 应用密钥落库加密算法：SM4（默认，国密 SM4-GCM）或 AES（AES-256-GCM）。只影响新写入的密文；
     * 读取按密文前缀自动选择算法，切换后已有密文仍可解密。由 SupplyAppSecretCipher 读取。
     */
    private String secretCipher = "SM4";

    /**
     * 数据 API 查询默认超时秒数：接口未单独配置时使用。
     */
    private int defaultQueryTimeoutSeconds = 30;

    /**
     * 数据 API 查询超时上限秒数：接口配置超过该值时按上限执行。
     */
    private int maxQueryTimeoutSeconds = 300;

    /**
     * 单个应用同时执行中的开放 API 请求上限（全部接口、全部节点合计）；0 表示不限制。
     */
    private int appMaxConcurrency = 0;

}
