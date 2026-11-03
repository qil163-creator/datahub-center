package cn.iocoder.yudao.framework.dbswitch.dbwriter.atomic;

import java.util.Objects;

/** 对单个已有批次使用独立键进行替换；绝不覆盖原始历史。 */
public record AtomicBatchReplacement(AtomicBatchTarget target, AtomicBatchKey key, String expectedCurrentPayloadHash,
                                     String operationKey, AtomicBatchRequest replacement) {

    /**
     * 校验构造参数并初始化 {@code AtomicBatchReplacement}。
     */
    public AtomicBatchReplacement {
        Objects.requireNonNull(target);
        Objects.requireNonNull(key);
        Objects.requireNonNull(replacement);
        new AtomicBatchKey(key.scope(), operationKey);
        if (expectedCurrentPayloadHash == null || !expectedCurrentPayloadHash.matches("[a-f0-9]{64}")
                || !target.equals(replacement.target()) || !key.equals(replacement.key())) {
            throw new IllegalArgumentException("Invalid replacement target, key or expected current hash");
        }
    }
}
