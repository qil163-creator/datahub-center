package cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery;

import java.util.Optional;
import java.util.List;

/** 持久控制面存储；实现必须在返回 PREPARED 前提交材料。 */
public interface GroupRewriteStore {

    /** 有界键集扫描。nextAfter 非 null 时，空键不代表扫描完成。 */
    record PendingPage(List<GroupRewriteKey> keys, String nextAfter) {

        /**
         * 初始化 {@code PendingPage}。
         */
        public PendingPage {
            keys = List.copyOf(keys);
        }
    }

    /**
     * 返回当前尚未处理完成的键集合。
     */
    PendingPage pendingKeys(String scope, String resource, String afterGroup, int pageSize);

    /**
     * 根据恢复资源键读取分组重写的持久化状态。
     */
    Optional<GroupRewriteRecord> read(GroupRewriteKey key);

    /** -1 表示创建新分组；非负 revision 仅替换 APPLIED 代际。 */
    GroupRewriteRecord prepare(GroupRewriteKey key, String ownerToken, GroupRewriteMaterial material,
            long completedRevision);

    /**
     * 返回当前迁移。
     */
    Optional<GroupRewriteRecord> transition(GroupRewriteKey key, String ownerToken, long revision,
            GroupRewriteRecord.Phase expected, GroupRewriteRecord.Phase next);

    /** 仅允许显式对账：调用方已证明旧执行器停止且外部写入已排空。 */
    Optional<GroupRewriteRecord> claimRecovery(GroupRewriteKey key, long revision, String newOwnerToken);
}
