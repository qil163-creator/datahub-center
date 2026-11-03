package cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;

import java.util.Objects;

/** 可信内存内编排契约，绝不仅根据任务 JSON 或租约时长重建。
 * TDengine 无法强制执行目标隔离令牌。所有权要求排空外部 DML/DDL，且
 * 对账时须证明前执行器已无法再发出目标请求。 */
public record GroupRewriteBinding(String scope, String fingerprint, String physicalFingerprint, String ownerToken,
                                  GroupRewriteStore store,
                                  BatchLimits limits, boolean exclusiveTargetOwnership, ResumeMode resumeMode,
                                  Runnable assertOwnership) {

    public enum ResumeMode {NEW_OPERATION_ONLY, RECONCILE_AFTER_PRIOR_EXECUTOR_EXIT}

    /**
     * 校验构造参数并初始化 {@code GroupRewriteBinding}。
     */
    public GroupRewriteBinding {
        GroupRewriteKey.fingerprint(scope);
        GroupRewriteKey.fingerprint(fingerprint);
        GroupRewriteRecord.owner(ownerToken);
        GroupRewriteKey.fingerprint(physicalFingerprint);
        Objects.requireNonNull(store);
        Objects.requireNonNull(limits);
        Objects.requireNonNull(resumeMode);
        Objects.requireNonNull(assertOwnership);
        if (!exclusiveTargetOwnership || limits.maxBatchBytes() > GroupRewriteMaterial.MAX_BYTES) {
            throw new IllegalArgumentException(
                    "Group rewriting requires bounded recovery and explicit exclusive target ownership");
        }
    }

    /**
     * 检查所有权。
     */
    public void checkOwnership() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Group rewrite interrupted");
        }
        assertOwnership.run();
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "GroupRewriteBinding[mode=" + resumeMode + "]";
    }
}
