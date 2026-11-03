package cn.iocoder.yudao.framework.dbswitch.core.model;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.TransactionCommitReceipt;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitRangeReceipt;

/**
 * 调用方独占实际读取连接上的只读快照。不得跨线程或在作用域内 commit/rollback/改连接状态。
 * close 复验同一事务和快照、回滚只读事务并还原连接；正常退出前不得发布读取结果。
 * 这不是同步提交/副本到达、源库代际或跨连接可见性的证明。
 */
public interface ReadSnapshot extends AutoCloseable {

    /** 仅保存版本、产品隔离协议和不可逆摘要，不保存数据库地址、业务值或原生事务清单。 */
    record Evidence(int schemaVersion, String protocol, String fingerprint) {

    }

    /**
     * 返回当前证据。
     */
    Evidence evidence();

    int MAX_COMMIT_OBSERVATIONS = 16;

    /** 顶层事务在本次快照的诊断，非表/字段/数据值、集群或恢复代际证明。 */
    record CommitObservation(TransactionCommitReceipt receipt, String status) {

    }

    record CommitVisibility(int schemaVersion, Evidence snapshot, List<CommitObservation> observations) {

        /**
         * 初始化 {@code CommitVisibility}。
         */
        public CommitVisibility {
            observations = List.copyOf(observations);
        }
    }

    /** 有界、同连接、同快照；只在作用域正常 close 后发布。旧实现明确不支持，不伪造可见。 */
    default CommitVisibility inspectCommits(List<TransactionCommitReceipt> receipts) throws SQLException {
        throw new SQLFeatureNotSupportedException("Commit visibility is not verified for this read snapshot");
    }

    int MAX_COMMIT_RANGES = 20;

    record RangeObservation(CommitRangeReceipt receipt, String status, String relationStatus) {

        /**
         * 使用给定参数初始化 {@code RangeObservation}。
         */
        public RangeObservation(CommitRangeReceipt receipt, String status) {
            this(receipt, status, "NOT_CHECKED");
        }
    }

    record RangeVisibility(int schemaVersion, Evidence snapshot, List<RangeObservation> observations) {

        /**
         * 初始化 {@code RangeVisibility}。
         */
        public RangeVisibility {
            observations = List.copyOf(observations);
        }
    }

    /** 核对可信写入摘要的整个事务闭区间；区间内无关的未完成事务也会导致保守拒绝。 */
    default RangeVisibility inspectCommitRanges(List<CommitRangeReceipt> ranges) throws SQLException {
        throw new SQLFeatureNotSupportedException("Commit range visibility is not verified for this read snapshot");
    }

    /** 额外绑定本次实际读取的关系；MATCH 也不表示值相等、恢复代际或业务准入。 */
    default RangeVisibility inspectCommitRanges(List<CommitRangeReceipt> ranges,
            String relationReference) throws SQLException {
        throw new SQLFeatureNotSupportedException(
                "Relation-bound commit visibility is not verified for this read snapshot");
    }

    /** 可选读取的保存点；close 总是回滚到保存点并释放，不结束或更换主快照。 */
    Checkpoint checkpoint() throws SQLException;

    interface Checkpoint extends AutoCloseable {

        /**
         * 结束当前快照检查点的使用作用域。
         */
        @Override
        void close() throws SQLException;
    }

    /**
     * 关闭 {@code ReadSnapshot} 当前持有的资源。
     */
    @Override
    void close() throws SQLException;
}
