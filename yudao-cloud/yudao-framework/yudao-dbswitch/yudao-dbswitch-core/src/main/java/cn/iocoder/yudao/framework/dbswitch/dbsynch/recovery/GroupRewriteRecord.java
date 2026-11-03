package cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery;

import java.util.Objects;

/** 持久状态。意图记录在目标确认不确定时仍保留，绝不自动过期。 */
public record GroupRewriteRecord(GroupRewriteKey key, long revision, String ownerToken,
                                 Phase phase, GroupRewriteMaterial material) {

    public enum Phase {PREPARED, DELETE_INTENT, RESTORE_INTENT, APPLIED}

    /**
     * 校验构造参数并初始化 {@code GroupRewriteRecord}。
     */
    public GroupRewriteRecord {
        Objects.requireNonNull(key);
        Objects.requireNonNull(phase);
        Objects.requireNonNull(material);
        owner(ownerToken);
        if (revision < 0 || !key.resource().equals(material.physicalFingerprint())) {
            throw new IllegalArgumentException("Recovery record identity is inconsistent");
        }
    }

    /**
     * 返回归属标识。
     */
    public static void owner(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{16,128}")) {
            throw new IllegalArgumentException("Invalid recovery owner token");
        }
    }

    /**
     * 判断当前能力或授权是否允许指定操作。
     */
    public static boolean permits(Phase current, Phase next) {
        return current == Phase.PREPARED && next == Phase.DELETE_INTENT
                || current == Phase.DELETE_INTENT && next == Phase.RESTORE_INTENT
                || current == Phase.RESTORE_INTENT && next == Phase.APPLIED;
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "GroupRewriteRecord[revision=" + revision + ",phase=" + phase + "]";
    }
}
