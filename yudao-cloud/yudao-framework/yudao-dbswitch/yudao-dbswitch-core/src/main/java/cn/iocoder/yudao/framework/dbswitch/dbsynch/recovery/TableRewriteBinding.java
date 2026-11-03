package cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery;

import java.util.Objects;

/** 可信运行时准入，绝不来自任务 JSON。所有权要求排除外部 DML/DDL，
 * 并在恢复前排空物理执行器；仅有租约时间戳不是此类依据。 */
public record TableRewriteBinding(String scope, String fingerprint, TableRewriteIdentity identity, String ownerToken,
                                  TableRewriteStore store, TableRewriteLimits limits, boolean exclusiveTargetOwnership,
                                  Runnable assertOwnership) {

    /**
     * 校验构造参数并初始化 {@code TableRewriteBinding}。
     */
    public TableRewriteBinding {
        GroupRewriteKey.fingerprint(scope);
        GroupRewriteKey.fingerprint(fingerprint);
        GroupRewriteRecord.owner(ownerToken);
        Objects.requireNonNull(identity);
        Objects.requireNonNull(store);
        Objects.requireNonNull(limits);
        Objects.requireNonNull(assertOwnership);
        if (!exclusiveTargetOwnership) {
            throw new IllegalArgumentException(
                    "Table rewriting requires explicit exclusive target DML and DDL ownership");
        }
    }

    /**
     * 检查所有权。
     */
    public void checkOwnership() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Table rewriting interrupted");
        }
        assertOwnership.run();
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "TableRewriteBinding[trusted]";
    }
}
