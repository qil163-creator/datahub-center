package cn.iocoder.yudao.module.supply.service.app.grant;

import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO.Scope;

import java.util.Objects;

/** 与持久化实体分离的不可变来源状态。 */
public record GrantSourceState(String sourceSystem, String sourceKey, long sourceRevision,
                               long appId, long apiId, Status status, Scope scope, String payloadHash) {

    /**
     * 创建授权来源状态实例。
     *
     * @param sourceSystem 来源系统
     * @param sourceKey 来源键
     * @param sourceRevision 来源修订号
     * @param appId 应用编号
     * @param apiId API 编号
     * @param status 目标状态
     * @param scope 范围
     * @param payloadHash 载荷摘要
     */
    public GrantSourceState {
        Objects.requireNonNull(sourceSystem, "sourceSystem");
        Objects.requireNonNull(sourceKey, "sourceKey");
        Objects.requireNonNull(status, "status");
        if (sourceSystem.isBlank() || sourceKey.isBlank() || sourceRevision <= 0 || appId <= 0 || apiId <= 0) {
            throw new IllegalArgumentException("Invalid source identity or revision");
        }
    }

    public enum Status {ACTIVE, SUSPENDED, REVOKED, REVIEW_REQUIRED}

    public record Identity(String sourceSystem, String sourceKey) {

    }

    /**
     * 获取授权来源状态的身份。
     *
     * @return 身份
     */
    public Identity identity() {
        return new Identity(sourceSystem, sourceKey);
    }
}
