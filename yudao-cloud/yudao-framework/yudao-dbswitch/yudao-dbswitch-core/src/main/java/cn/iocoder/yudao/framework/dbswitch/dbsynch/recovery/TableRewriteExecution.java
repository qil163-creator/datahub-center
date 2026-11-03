package cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;

import java.util.Objects;

/** 对单个已持久准备的替换执行与产品无关的操作。绝不根据
 * 逻辑名称推断所有权，也不在 CREATE 确认丢失后发现或接受某一代际。产品调用
 * 严格发生在存储事务之外。恢复准入由可信运行时所有者负责。 */
public final class TableRewriteExecution {

    public interface Target {

        /** 精确的原始原生标识、schema、完整前映像及排他 DML/DDL。 */
        void verifyBefore(TableRewriteMaterial material) throws Exception;

        /** 仅在 DROP 已确认且固定命名空间内确认资源不存在后返回。 */
        void drop(TableRewriteMaterial material) throws Exception;

        /** 要求资源不存在；创建并获取实际原生标识后才返回。 */
        TableRewriteIdentity create(TableRewriteMaterial material) throws Exception;

        /** 检查此前已持久化的原生代际，绝不在此处接受替代代际。 */
        void verifyGeneration(TableRewriteMaterial material, TableRewriteIdentity identity) throws Exception;

        /** 在同一持有代际上重放页面，对此产品必须幂等。 */
        void restore(TableRewriteMaterial material, TableRewriteIdentity identity,
                TableRewriteMaterial.Page page) throws Exception;

        /** APPLIED 前完整回读目标，确保值精确且没有额外行。 */
        void verifyAfter(TableRewriteMaterial material, TableRewriteIdentity identity) throws Exception;
    }

    private final TableRewriteStore store;

    private final Runnable ownership;

    /**
     * 校验构造参数并初始化 {@code TableRewriteExecution}。
     */
    public TableRewriteExecution(TableRewriteStore store, Runnable ownership) {
        this.store = Objects.requireNonNull(store);
        this.ownership = Objects.requireNonNull(ownership);
    }

    /**
     * 执行已经完成参数绑定的预编译语句。
     */
    public TableRewriteRecord executePrepared(TableRewriteKey key, String owner, Target target) {
        var record = readOwned(key, owner);
        if (record.phase() != TableRewriteRecord.Phase.PREPARED || record.revision() != 0) {
            throw manual();
        }
        return run(record, Objects.requireNonNull(target));
    }

    /** 可信调用方必须先证明旧执行器及资源已排空，再调用 store.claimRecovery。
     * 重新读取认领记录中已认证的 revision/owner；伪造记录不能授予目标 I/O 权限。 */
    public TableRewriteRecord reconcileClaimed(TableRewriteRecord claim, Target target) {
        var record = readOwned(claim.key(), claim.ownerToken());
        if (record.revision() != claim.revision() || !record.material().hash().equals(claim.material().hash())
                || !record.permitsAutomaticReconciliation()) {
            throw manual();
        }
        return run(record, Objects.requireNonNull(target));
    }

    /**
     * 根据已持久化阶段执行表替换步骤，并记录确定或未知的操作结果。
     */
    private TableRewriteRecord run(TableRewriteRecord record, Target target) {
        try {
            if (record.phase() == TableRewriteRecord.Phase.PREPARED) {
                check();
                target.verifyBefore(record.material());
                record = advance(record, TableRewriteRecord.Phase.DROP_INTENT, null);
                try {
                    check();
                    target.drop(record.material());
                } catch (Exception failed) {
                    markUnknown(record, TableRewriteRecord.Phase.DROP_UNKNOWN);
                    throw new UnconfirmedWriteException();
                }
                record = advance(record, TableRewriteRecord.Phase.DROP_CONFIRMED, null);
            }
            if (record.phase() == TableRewriteRecord.Phase.DROP_CONFIRMED) {
                record = advance(record, TableRewriteRecord.Phase.CREATE_INTENT, null);
                TableRewriteIdentity created;
                try {
                    check();
                    created = Objects.requireNonNull(target.create(record.material()));
                } catch (Exception failed) {
                    markUnknown(record, TableRewriteRecord.Phase.CREATE_UNKNOWN);
                    throw new UnconfirmedWriteException();
                }
                // 若此 CAS 提交结果未知，CREATE_INTENT 同样禁止自动识别或重放。
                record = advance(record, TableRewriteRecord.Phase.CREATE_CONFIRMED, created);
            }
            if (record.phase() == TableRewriteRecord.Phase.CREATE_CONFIRMED) {
                check();
                target.verifyGeneration(record.material(), record.newIdentity());
                record = advance(record, TableRewriteRecord.Phase.RESTORE_INTENT, record.newIdentity());
            }
            if (record.phase() != TableRewriteRecord.Phase.RESTORE_INTENT) {
                throw manual();
            }
            for (var page : record.material().pages()) {
                if (page.image() == TableRewriteMaterial.Image.AFTER) {
                    check();
                    target.verifyGeneration(record.material(), record.newIdentity());
                    target.restore(record.material(), record.newIdentity(), page);
                }
            }
            check();
            target.verifyGeneration(record.material(), record.newIdentity());
            target.verifyAfter(record.material(), record.newIdentity());
            return advance(record, TableRewriteRecord.Phase.APPLIED, record.newIdentity());
        } catch (UnconfirmedWriteException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new UnconfirmedWriteException();
        }
    }

    /**
     * 读取独占。
     */
    private TableRewriteRecord readOwned(TableRewriteKey key, String owner) {
        check();
        GroupRewriteRecord.owner(owner);
        var record = store.read(key).orElseThrow(TableRewriteExecution::manual);
        if (!record.ownerToken().equals(owner)) {
            throw manual();
        }
        return record;
    }

    /**
     * 根据当前状态推进游标或操作阶段。
     */
    private TableRewriteRecord advance(TableRewriteRecord record, TableRewriteRecord.Phase next,
            TableRewriteIdentity identity) {
        check();
        return store.transition(record.key(), record.ownerToken(), record.revision(), record.phase(), next, identity)
                .orElseThrow(TableRewriteExecution::manual);
    }

    /**
     * 将当前操作标记为结果未知，等待后续核对。
     */
    private void markUnknown(TableRewriteRecord record, TableRewriteRecord.Phase phase) {
        try {
            advance(record, phase, null);
        } catch (Exception ignored) {/* 意图已持久化，同样阻止自动接纳 */}
    }

    /**
     * 检查当前状态是否满足当前操作的约定。
     */
    private void check() {
        if (Thread.currentThread().isInterrupted()) {
            throw manual();
        }
        ownership.run();
    }

    /**
     * 根据当前输入构建并返回 {@code IllegalStateException}。
     */
    private static IllegalStateException manual() {
        return new IllegalStateException(
                "Table rewrite requires authenticated ownership or manual generation evidence");
    }
}
