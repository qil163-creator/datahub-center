package cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery;

import java.util.Objects;

/** 已认证的持久状态。未确认代际的意图不授予重放权限。 */
public record TableRewriteRecord(TableRewriteKey key, long revision, String ownerToken, Phase phase,
                                 TableRewriteIdentity newIdentity, TableRewriteMaterial material) {

    public enum Phase {
        PREPARED, DROP_INTENT, DROP_CONFIRMED, CREATE_INTENT, CREATE_CONFIRMED,
        RESTORE_INTENT, APPLIED, DROP_UNKNOWN, CREATE_UNKNOWN
    }

    /**
     * 校验构造参数并初始化 {@code TableRewriteRecord}。
     */
    public TableRewriteRecord {
        Objects.requireNonNull(key);
        Objects.requireNonNull(phase);
        Objects.requireNonNull(material);
        GroupRewriteRecord.owner(ownerToken);
        if (revision < 0 || requiresNewIdentity(phase) != (newIdentity != null)
                || newIdentity != null && (!newIdentity.format().equals(material.oldIdentity().format())
                || newIdentity.equals(material.oldIdentity()))) {
            throw invalid();
        }
    }

    /**
     * 判断当前操作是否需要创建新的对象身份。
     */
    public static boolean requiresNewIdentity(Phase phase) {
        return phase == Phase.CREATE_CONFIRMED || phase == Phase.RESTORE_INTENT || phase == Phase.APPLIED;
    }

    /**
     * 判断当前能力或授权是否允许指定操作。
     */
    public static boolean permits(Phase current, Phase next) {
        return switch (current) {
            case PREPARED -> next == Phase.DROP_INTENT;
            case DROP_INTENT -> next == Phase.DROP_CONFIRMED || next == Phase.DROP_UNKNOWN;
            case DROP_CONFIRMED -> next == Phase.CREATE_INTENT;
            case CREATE_INTENT -> next == Phase.CREATE_CONFIRMED || next == Phase.CREATE_UNKNOWN;
            case CREATE_CONFIRMED -> next == Phase.RESTORE_INTENT;
            case RESTORE_INTENT -> next == Phase.APPLIED;
            case APPLIED, DROP_UNKNOWN, CREATE_UNKNOWN -> false;
        };
    }

    /**
     * 根据当前状态推进游标或操作阶段。
     */
    public TableRewriteRecord advance(Phase next, TableRewriteIdentity confirmedIdentity) {
        if (!permits(phase, next)) {
            throw invalid();
        }
        if (phase == Phase.CREATE_INTENT && next == Phase.CREATE_CONFIRMED) {
            if (confirmedIdentity == null) {
                throw invalid();
            }
        } else if (!Objects.equals(confirmedIdentity, newIdentity)) {
            throw invalid();
        }
        return new TableRewriteRecord(key, Math.incrementExact(revision), ownerToken, next, confirmedIdentity,
                material);
    }

    /** 执行目标 I/O 前，调用方仍须证明旧执行器已排空、所有权及实际原生代际。 */
    public boolean permitsAutomaticReconciliation() {
        return phase == Phase.PREPARED || phase == Phase.DROP_CONFIRMED || phase == Phase.CREATE_CONFIRMED || phase == Phase.RESTORE_INTENT;
    }

    /**
     * 根据当前输入构建并返回 {@code TableRewriteRecord}。
     */
    public TableRewriteRecord claimed(String owner) {
        GroupRewriteRecord.owner(owner);
        if (!permitsAutomaticReconciliation() || owner.equals(ownerToken)) {
            throw invalid();
        }
        return new TableRewriteRecord(key, Math.incrementExact(revision), owner, phase, newIdentity, material);
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid or unproven table recovery transition");
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "TableRewriteRecord[revision=" + revision + ",phase=" + phase + "]";
    }
}
