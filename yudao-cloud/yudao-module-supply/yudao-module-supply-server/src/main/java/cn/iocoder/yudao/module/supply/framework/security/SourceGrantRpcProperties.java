package cn.iocoder.yudao.module.supply.framework.security;

import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 来源级授权 v2 RPC 的服务身份门禁配置。
 *
 * 未配置任何对端时所有 v2 来源命令/查询被拒绝（默认关闭），不会退回到信任内部头。
 */
@Data
@Slf4j
@Component
@ConfigurationProperties(prefix = "yudao.supply.grant-source.rpc")
public class SourceGrantRpcProperties {

    /**
     * 允许调用 v2 来源 RPC 的服务：键为服务标识（即命令中的 sourceSystem，例如 asset），
     * 值为该服务客户端叶证书 DER 编码的 SHA-256 十六进制指纹；轮换期间可同时配置新旧两枚。
     */
    private Map<String, Set<String>> peers = new LinkedHashMap<>();

    /**
     * 校验来源授权RPC输入及状态。
     */
    @PostConstruct
    void validate() {
        if (peers == null || peers.isEmpty()) {
            log.warn("[validate][yudao.supply.grant-source.rpc.peers 未配置，来源级授权 v2 RPC 将拒绝所有调用]");
            peers = Map.of();
            return;
        }
        // 启动即失败：同一指纹属于多个服务或指纹格式错误时不允许按遍历顺序猜测 owner。
        peers = VerifiedServicePeerResolver.requireUnambiguous(peers);
    }

}
