package cn.iocoder.yudao.framework.dbswitch.common.type;

/**
 * 变化量同步允许执行的操作组合（移植自 dbswitch 2.0.2 的 targetSyncOption 设计）
 * <p>
 * 用于限制增量同步时对目标端可执行的操作类别，例如主数据分发场景通常只允许"增/改"而禁止"删"。
 * 默认值 {@link #INSERT_UPDATE_DELETE} 与历史行为一致（三类操作全部执行）
 *
 * @author yudao
 */
public enum SyncOptionEnum {

    /** 仅执行插入 */
    INSERT(true, false, false),
    /** 仅执行更新 */
    UPDATE(false, true, false),
    /** 仅执行删除 */
    DELETE(false, false, true),
    /** 执行插入与更新（不删除目标端多余数据） */
    INSERT_UPDATE(true, true, false),
    /** 执行插入与删除 */
    INSERT_DELETE(true, false, true),
    /** 执行更新与删除 */
    UPDATE_DELETE(false, true, true),
    /** 插入、更新、删除全部执行（默认，与历史行为一致） */
    INSERT_UPDATE_DELETE(true, true, true),
    ;

    private final boolean callInsert;

    private final boolean callUpdate;

    private final boolean callDelete;

    /**
     * 初始化 {@code SyncOptionEnum}。
     */
    SyncOptionEnum(boolean callInsert, boolean callUpdate, boolean callDelete) {
        this.callInsert = callInsert;
        this.callUpdate = callUpdate;
        this.callDelete = callDelete;
    }

    /**
     * 返回调用插入。
     */
    public boolean callInsert() {
        return callInsert;
    }

    /**
     * 返回调用更新。
     */
    public boolean callUpdate() {
        return callUpdate;
    }

    /**
     * 返回调用删除。
     */
    public boolean callDelete() {
        return callDelete;
    }
}
