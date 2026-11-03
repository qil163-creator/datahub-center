package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnSequenceMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;

import java.util.Map;

/** 显式技术写入语义。排他所有权是部署契约，绝不等于授权。 */
public record IdentityTransferOptions(boolean preserveExplicitValues, boolean exclusiveTargetOwnership,
                                      Map<String, ColumnSequenceMetadata> sourceSequences,
                                      Map<String, cn.iocoder.yudao.framework.dbswitch.core.model.SqlServerIdentityMetadata> sourceIdentities) {

    /**
     * 使用给定参数初始化 {@code IdentityTransferOptions}。
     */
    public IdentityTransferOptions(boolean preserveExplicitValues, boolean exclusiveTargetOwnership,
            Map<String, ColumnSequenceMetadata> sourceSequences) {
        this(preserveExplicitValues, exclusiveTargetOwnership, sourceSequences, Map.of());
    }

    /**
     * 校验构造参数并初始化 {@code IdentityTransferOptions}。
     */
    public IdentityTransferOptions {
        sourceSequences = Map.copyOf(sourceSequences);
        sourceIdentities = Map.copyOf(sourceIdentities);
        if (preserveExplicitValues != exclusiveTargetOwnership
                || (!sourceSequences.isEmpty() || !sourceIdentities.isEmpty()) && !preserveExplicitValues
                || !sourceSequences.isEmpty() && !sourceIdentities.isEmpty()) {
            throw new IllegalArgumentException(
                    "Identity transfer requires explicit value preservation and exclusive target ownership");
        }
        for (var entry : sourceSequences.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue().cycle()
                    || entry.getValue().position().state() != MetadataValue.State.VALUE) {
                throw new IllegalArgumentException("Identity transfer requires observed non-cycling sequence state");
            }
        }
        if (sourceIdentities.size() > 1 || sourceIdentities.keySet().stream()
                .anyMatch(name -> name == null || name.isBlank())) {
            throw new IllegalArgumentException("SQL Server identity source columns are invalid");
        }
    }

    /**
     * 构造未启用当前功能的配置。
     */
    public static IdentityTransferOptions disabled() {
        return new IdentityTransferOptions(false, false, Map.of());
    }
}
