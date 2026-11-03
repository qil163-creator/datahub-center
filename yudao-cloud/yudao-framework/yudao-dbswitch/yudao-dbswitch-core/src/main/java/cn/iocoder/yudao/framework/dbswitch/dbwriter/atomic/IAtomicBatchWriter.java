package cn.iocoder.yudao.framework.dbswitch.dbwriter.atomic;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 显式启用的只追加、单目标批次协议。每次操作借用并持有一个新连接；
 * 不加入调用方事务。作用域授权由调用方负责。
 * 未知写入必须通过 findReceipt 对账；不允许自动回退或逐行重放。
 */
public interface IAtomicBatchWriter {

    /** 在已有已授权 schema 中显式执行运行时旁账 DDL，绝不改变目标表。 */
    void prepareLedger(String schema);

    /** 检查产品、目标存储、列、技术类型及非部分的批次与行唯一性。 */
    void preflight(AtomicBatchTarget target, List<String> fields);

    /**
     * 返回当前提交。
     */
    AtomicBatchReceipt commit(AtomicBatchRequest request);

    /** 锁定当前批次头，检查预期哈希，原子替换该批次并追加历史。 */
    AtomicBatchReceipt replace(AtomicBatchReplacement replacement);

    /** 其他写入器尚在执行时，缺失不证明回滚；错误绝不转换为缺失。 */
    Optional<AtomicBatchReceipt> findReceipt(AtomicBatchTarget target, AtomicBatchKey key);

    /** 当前修订；findReceipt 始终返回不可变原始提交。 */
    Optional<AtomicBatchReceipt> findCurrentReceipt(AtomicBatchTarget target, AtomicBatchKey key);

    /** 按稳定操作键对可能已提交的替换进行对账。 */
    Optional<AtomicBatchReceipt> findOperationReceipt(AtomicBatchTarget target, AtomicBatchKey key,
            String operationKey);

    /** 经回执检查的 SQL 和参数，供调用方现有行授权及脱敏查询路径使用。 */
    AtomicBatchReadPlan planReadBatch(AtomicBatchTarget target, AtomicBatchKey key,
            List<String> fields, long afterRow, int limit);

    /** 可选的固定状态白名单在数据库中、游标分页前应用。 */
    AtomicBatchReadPlan planReadBatch(AtomicBatchTarget target, AtomicBatchKey key,
            List<String> fields, long afterRow, int limit, java.util.Set<String> allowedStatuses);

    /** 固定批次相等条件及升序行游标；调用方必须授权每个所选列。 */
    List<Map<String, Object>> readBatch(AtomicBatchTarget target, AtomicBatchKey key,
            List<String> fields, long afterRow, int limit);

    /** 调用方在此调用前解析授权，之后应用脱敏。持锁期间不执行远程回调。
     * 在单个短数据库事务中，重新检查回执和存储物化，将已授权 SQL 限制到同一表及列，
     * 并强制保留原始批次、状态、游标、排序和上限边界。 */
    List<Map<String, Object>> readAuthorizedBatch(AtomicBatchReadPlan plan, String authorizedSql,
            String expectedPayloadHash);
}
