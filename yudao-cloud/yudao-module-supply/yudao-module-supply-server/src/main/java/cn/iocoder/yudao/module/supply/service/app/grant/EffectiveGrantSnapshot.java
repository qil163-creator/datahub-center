package cn.iocoder.yudao.module.supply.service.app.grant;

import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO.Binding;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** 单次查询采用的完整范围，返回前须按权威状态和数据库时间重验。 */
public record EffectiveGrantSnapshot(long appId, long apiId, long aggregateRevision,
                                     Long permissionUserId, Binding binding, String contractHash, String apiVersion,
                                     List<String> approvedColumns, Map<GrantSourceState.Identity, Long> sourceVersions,
                                     Instant evaluatedAt, Instant nextBoundary, boolean fieldScoped,
                                     Integer apiControlVersion,
                                     List<cn.iocoder.yudao.module.supply.api.app.dto.SupplyQuotaDTO.Source> quotaSources) {

    /**
     * 创建有效授权快照实例。
     *
     * @param appId 应用编号
     * @param apiId API 编号
     * @param aggregateRevision 聚合修订号
     * @param permissionUserId 权限主体用户编号
     * @param binding 绑定
     * @param contractHash 契约摘要
     * @param apiVersion API版本
     * @param approvedColumns 已批准列集合
     * @param sourceVersions 来源版本
     * @param evaluatedAt 已评估时间
     * @param nextBoundary 下次边界
     * @param fieldScoped 字段限定范围
     * @param apiControlVersion API控制版本
     */
    public EffectiveGrantSnapshot(long appId, long apiId, long aggregateRevision,
            Long permissionUserId, Binding binding, String contractHash, String apiVersion,
            List<String> approvedColumns, Map<GrantSourceState.Identity, Long> sourceVersions,
            Instant evaluatedAt, Instant nextBoundary, boolean fieldScoped, Integer apiControlVersion) {
        this(appId, apiId, aggregateRevision, permissionUserId, binding, contractHash, apiVersion,
                approvedColumns, sourceVersions, evaluatedAt, nextBoundary, fieldScoped, apiControlVersion, List.of());
    }

    /**
     * 创建有效授权快照实例。
     *
     * @param appId 应用编号
     * @param apiId API 编号
     * @param aggregateRevision 聚合修订号
     * @param permissionUserId 权限主体用户编号
     * @param binding 绑定
     * @param contractHash 契约摘要
     * @param apiVersion API版本
     * @param approvedColumns 已批准列集合
     * @param sourceVersions 来源版本
     * @param evaluatedAt 已评估时间
     * @param nextBoundary 下次边界
     * @param fieldScoped 字段限定范围
     * @param apiControlVersion API控制版本
     * @param quotaSources 额度来源
     */
    public EffectiveGrantSnapshot {
        approvedColumns = List.copyOf(new TreeSet<>(approvedColumns));
        sourceVersions = Map.copyOf(sourceVersions);
        quotaSources = List.copyOf(quotaSources);
    }

    /** 纯来源求值没有 API 控制事实；由同一读取事务取得的发布行版本补齐。 */
    public EffectiveGrantSnapshot withApiControlVersion(int version) {
        return new EffectiveGrantSnapshot(appId, apiId, aggregateRevision, permissionUserId,
                binding, contractHash, apiVersion, approvedColumns, sourceVersions,
                evaluatedAt, nextBoundary, fieldScoped, version, quotaSources);
    }
}
