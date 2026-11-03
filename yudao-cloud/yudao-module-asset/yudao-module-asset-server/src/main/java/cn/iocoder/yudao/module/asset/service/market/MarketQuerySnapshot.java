package cn.iocoder.yudao.module.asset.service.market;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** 一次门户读取的不可变批准凭证；仅在服务内传递，不接受浏览器提交或用于持久化。 */
public record MarketQuerySnapshot(long userId, long applyId, long assetId, List<Grant> grants,
                                  MarketFieldScope fieldScope, Long databaseId, String schemaName, String tableName,
                                  LocalDateTime evaluatedAt) {

    /**
     * 使用给定用户编号、申请编号、资产编号初始化市场查询快照。
     */
    public MarketQuerySnapshot {
        grants = List.copyOf(grants);
    }

    /** 每条来源保留自己的版本、冻结证据及期限，不合成一个最早开始/最晚结束的区间。 */
    public record Grant(long applyId, long sourceRevision, int approvalRevision, String frozenEvidence,
                        Policy policy, LocalDateTime validFrom, LocalDateTime validTo,
                        cn.iocoder.yudao.module.supply.api.app.dto.SupplyQuotaDTO.Source quotaSource) {

        /**
         * 使用给定申请编号、来源修订号、审批修订号初始化市场查询快照。
         */
        public Grant(long applyId, long sourceRevision, int approvalRevision, String frozenEvidence,
                Policy policy, LocalDateTime validFrom, LocalDateTime validTo) {
            this(applyId, sourceRevision, approvalRevision, frozenEvidence, policy, validFrom, validTo, null);
        }
    }

    /** 当前个人治理模式下的兼容条件；不同主体/物理绑定另由可信上下文及冻结资产证据限定。 */
    public record Policy(Long appId, Long apiId, String contractHash, String apiVersion, String releaseId,
                         String purpose, String dutyMatter, String legalBasis) {

    }

    /** 忽略两次求值时间本身，但不忽略来源版本：暂停后恢复也不能释放旧结果。 */
    boolean sameGrant(MarketQuerySnapshot other) {
        return other != null && userId == other.userId
                && applyId == other.applyId && assetId == other.assetId
                && grants.equals(other.grants)
                && Objects.equals(databaseId, other.databaseId)
                && Objects.equals(schemaName, other.schemaName) && Objects.equals(tableName, other.tableName)
                && fieldScope.getColumns().equals(other.fieldScope.getColumns());
    }
}
