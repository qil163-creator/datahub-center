package cn.iocoder.yudao.framework.etl.core.connection;

import java.util.List;
import java.util.Map;

/** 调用方提供已授权且冻结的行处理策略；框架不依赖策略的领域存储。 */
public interface EtlRowPolicyPort {

    record Violation(String ruleCode, String field, String severity, String code, String message) {

    }

    record Counts(long input, long passed, long warned, long rejected, long violations) {

    }


    /** 执行已授权且冻结的领域清洗规则。只有显式治理清洗节点会调用。 */
    default Map<String, Object> clean(String nodeNo, Map<String, Object> row) {
        throw new IllegalStateException("缺少冻结治理清洗计划");
    }

    /** 冻结计划是否需要完整输入集合；默认仍采用已有的逐行清洗契约。 */
    default boolean requiresCleanRowSet(String nodeNo) {
        return false;
    }

    /**
     * 对节点的完整有界输入按冻结顺序执行清洗，允许去重、删行，不能增行或改变字段集合。
     * 输入列表和行只读；调用方负责配置校验，框架负责缓存预算与节点取消。
     */
    default List<Map<String, Object>> cleanRows(String nodeNo, List<Map<String, Object>> rows) {
        throw new IllegalStateException("缺少冻结治理集合清洗计划");
    }

    /** 集合转换的生命周期与缓存限额；在保存每条派生行之前申请，不依赖领域规则类型。 */
    interface CleanRowSetControl {

        /**
         * 在规则计算前检查停止请求和资源限制。
         */
        void beforeRule(List<Map<String, Object>> rows);

        /**
         * 保留输出。
         */
        void retainOutput(Map<String, Object> row);

        /**
         * 返回检查点。
         */
        void checkpoint();
    }

    /** 旧端口仍可执行，新端口应在每条转换及每条派生行生成时应用限额。 */
    default List<Map<String, Object>> cleanRows(String nodeNo, List<Map<String, Object>> rows,
            CleanRowSetControl control) {
        control.beforeRule(rows);
        List<Map<String, Object>> result = cleanRows(nodeNo, rows);
        if (result != null) {
            result.forEach(control::retainOutput);
        }
        return result;
    }

    /**
     * 根据当前表达式或规则计算输入值的结果。
     */
    List<Violation> evaluate(String nodeNo, Map<String, Object> row);

    /** 在拒绝行离开节点前持久化证据；失败必须向上传播，不允许静默丢弃。 */
    void recordIssue(String nodeNo, long sequence, Map<String, Object> row, List<Violation> violations);

    /**
     * 记录问题。
     */
    default void recordIssue(String nodeNo, long sequence, Map<String, Object> row, List<Violation> violations,
            cn.iocoder.yudao.framework.etl.core.runtime.EtlRowOrigin origin) {
        recordIssue(nodeNo, sequence, row, violations);
    }

    /**
     * 记录当前操作完成并发布最终结果。
     */
    void complete(String nodeNo, Counts counts);
}
