package cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery;

import java.util.List;
import java.util.Optional;

/** 持有短控制面事务；存储事务内禁止目标 I/O。 */
public interface TableRewriteStore {

    record PendingPage(List<TableRewriteKey> keys, String nextAfter) {

        /**
         * 初始化 {@code PendingPage}。
         */
        public PendingPage {
            keys = List.copyOf(keys);
        }
    }

    /**
     * 根据恢复资源键读取表重写的持久化状态。
     */
    Optional<TableRewriteRecord> read(TableRewriteKey key);

    /** 返回 PREPARED 前，原子持久化完整清单及每个加密页面。 */
    TableRewriteRecord prepare(TableRewriteKey key, String owner, TableRewriteMaterial material);

    /**
     * 返回当前迁移。
     */
    Optional<TableRewriteRecord> transition(TableRewriteKey key, String owner, long revision,
            TableRewriteRecord.Phase expected, TableRewriteRecord.Phase next,
            TableRewriteIdentity confirmedNewIdentity);

    /** 要求旧执行器已排空的可信证明；UNKNOWN 或有歧义的 INTENT 绝不自动认领。 */
    Optional<TableRewriteRecord> claimRecovery(TableRewriteKey key, long revision, String newOwner);

    /** 认证扫描到的所有记录头，包括 APPLIED。空页面仍可能含 nextAfter。 */
    PendingPage pending(String scope, String logicalResource, String afterOperation, int pageSize);
}
