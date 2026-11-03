package cn.iocoder.yudao.module.supply.service.app.grant;

import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO.Scope;
import cn.iocoder.yudao.module.supply.api.app.SupplyGrantCommandCodec;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import static cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO.Action.*;
import static cn.iocoder.yudao.module.supply.service.app.grant.GrantSourceState.Status.*;

/** 来源状态机与兼容范围求值；不读取全局时钟或线程上下文。 */
public final class SupplyGrantPolicy {

    /**
     * 限制供应授权策略工具的实例化，统一通过静态能力使用。
     */
    private SupplyGrantPolicy() {
    }

    public enum Reason {
        STALE_REVISION, REVISION_CONFLICT, IDENTITY_MISMATCH, TERMINAL_SOURCE,
        UNSUPPORTED_POLICY, INCOMPATIBLE_SCOPE, NO_EFFECTIVE_SCOPE, AGGREGATE_DISABLED, REVIEW_REQUIRED, AMBIGUOUS_SOURCE
    }

    public static final class Rejected extends RuntimeException {

        private final Reason reason;

        /**
         * 创建已拒绝实例。
         *
         * @param reason 操作原因
         */
        public Rejected(Reason reason) {
            super(reason.name());
            this.reason = reason;
        }

        /**
         * 返回已拒绝的原因。
         *
         * @return 原因
         */
        public Reason reason() {
            return reason;
        }
    }

    /**
     * 核对来源身份、单调修订及内容摘要，按命令推进来源授权状态。
     *
     * @param previous 先前
     * @param command 待处理命令
     * @return 授权来源状态
     */
    public static GrantSourceState transition(GrantSourceState previous, SupplyGrantSourceCommandDTO command) {
        String payloadHash = SupplyGrantCommandCodec.hash(command);
        if (previous != null) {
            if (!previous.sourceSystem().equals(command.sourceSystem()) || !previous.sourceKey()
                    .equals(command.sourceKey())
                    || previous.appId() != command.appId() || previous.apiId() != command.apiId()) {
                throw new Rejected(Reason.IDENTITY_MISMATCH);
            }
            if (command.sourceRevision() < previous.sourceRevision()) {
                throw new Rejected(Reason.STALE_REVISION);
            }
            if (command.sourceRevision() == previous.sourceRevision()) {
                if (payloadHash.equals(previous.payloadHash())) {
                    return previous;
                }
                throw new Rejected(Reason.REVISION_CONFLICT);
            }
            if (previous.status() == REVOKED && command.action() != REVOKE) {
                throw new Rejected(Reason.TERMINAL_SOURCE);
            }
        }
        Scope scope = previous == null ? null : previous.scope();
        var status = switch (command.action()) {
            case GRANT -> ACTIVE;
            case SUSPEND -> SUSPENDED;
            case REVOKE -> REVOKED;
        };
        if (command.action() == GRANT) {
            validatePolicy(command.scope());
            if (scope != null && (!compatibilityKey(scope).equals(compatibilityKey(command.scope()))
                    || !Objects.equals(scope.quota(), command.scope().quota()))) {
                throw new Rejected(Reason.INCOMPATIBLE_SCOPE);
            }
            scope = command.scope();
        }
        return new GrantSourceState(command.sourceSystem(), command.sourceKey(), command.sourceRevision(),
                command.appId(), command.apiId(), status, scope, payloadHash);
    }

    /**
     * 计算批准范围的兼容性摘要，供多来源聚合校验使用。
     *
     * @param scope 范围
     * @return 计算批准范围的兼容性摘要，供多来源聚合校验使用文本
     */
    public static String compatibilityKey(Scope scope) {
        return SupplyGrantCommandCodec.compatibilityHash(scope);
    }

    /**
     * 根据数据库评估时间、来源状态及兼容范围计算有效授权快照和额度来源。
     *
     * @param appId 应用编号
     * @param apiId API 编号
     * @param aggregateRevision 聚合修订号
     * @param disabled 禁用状态
     * @param sources 来源
     * @param now 当前时间
     * @return 有效授权快照
     */
    public static EffectiveGrantSnapshot evaluate(long appId, long apiId, long aggregateRevision,
            boolean disabled, List<GrantSourceState> sources, Instant now) {
        Objects.requireNonNull(now, "database evaluation time");
        if (appId <= 0 || apiId <= 0 || aggregateRevision < 0) {
            throw new IllegalArgumentException("Invalid aggregate identity or revision");
        }
        if (disabled) {
            throw new Rejected(Reason.AGGREGATE_DISABLED);
        }
        String compatibility = null;
        Scope principal = null;
        Set<GrantSourceState.Identity> identities = new HashSet<>();
        var versions = new LinkedHashMap<GrantSourceState.Identity, Long>();
        var fields = new TreeSet<String>();
        var quotaSources = new java.util.ArrayList<cn.iocoder.yudao.module.supply.api.app.dto.SupplyQuotaDTO.Source>();
        Long nextBoundary = null;
        long nowMillis = now.toEpochMilli();
        for (var source : List.copyOf(sources)) {
            if (source.appId() != appId || source.apiId() != apiId) {
                throw new Rejected(Reason.IDENTITY_MISMATCH);
            }
            if (!identities.add(source.identity())) {
                throw new Rejected(Reason.AMBIGUOUS_SOURCE);
            }
            if (source.status() == REVOKED) {
                continue;
            }
            if (source.status() == REVIEW_REQUIRED || source.scope() == null) {
                throw new Rejected(Reason.REVIEW_REQUIRED);
            }
            var scope = source.scope();
            validatePolicy(scope);
            String key = compatibilityKey(scope);
            if (compatibility != null && !compatibility.equals(key)) {
                throw new Rejected(Reason.INCOMPATIBLE_SCOPE);
            }
            compatibility = key;
            principal = scope;
            versions.put(source.identity(), source.sourceRevision());
            if (source.status() != ACTIVE) {
                continue;
            }
            if (scope.validFromMillis() > nowMillis) {
                nextBoundary = earlier(nextBoundary, scope.validFromMillis());
            }
            if (!scope.forever() && scope.validToMillis() > nowMillis) {
                nextBoundary = earlier(nextBoundary, scope.validToMillis());
            }
            if (scope.validFromMillis() <= nowMillis && (scope.forever() || nowMillis < scope.validToMillis())) {
                fields.addAll(scope.approvedColumns());
                quotaSources.add(new cn.iocoder.yudao.module.supply.api.app.dto.SupplyQuotaDTO.Source(
                        source.sourceSystem(), source.sourceKey(), scope.quota(), scope.approvedColumns()));
            }
        }
        if (fields.isEmpty() || principal == null) {
            throw new Rejected(Reason.NO_EFFECTIVE_SCOPE);
        }
        return new EffectiveGrantSnapshot(appId, apiId, aggregateRevision,
                principal.permissionUserId(), principal.binding(), principal.approvedContractHash(),
                principal.apiVersion(),
                List.copyOf(fields), versions, now, nextBoundary == null ? null : Instant.ofEpochMilli(nextBoundary),
                true, null, quotaSources);
    }

    /**
     * 返回两个时间值中较早的有效时间。
     *
     * @param current 当前
     * @param candidate 候选
     * @return 较早数值
     */
    private static Long earlier(Long current, long candidate) {
        return current == null ? candidate : Math.min(current, candidate);
    }

    /**
     * 限制来源授权为已支持的个人或部门来源类型。
     *
     * @param scope 范围
     */
    private static void validatePolicy(Scope scope) {
        if (scope == null) {
            throw new Rejected(Reason.REVIEW_REQUIRED);
        }
        // ADMIN 沿用独立路径，不能混入市场来源；历史模式字段只保留编码和摘要兼容。
        if (!Set.of("PERSONAL", "DEPARTMENT").contains(scope.sourceType())) {
            throw new Rejected(Reason.UNSUPPORTED_POLICY);
        }
    }
}
