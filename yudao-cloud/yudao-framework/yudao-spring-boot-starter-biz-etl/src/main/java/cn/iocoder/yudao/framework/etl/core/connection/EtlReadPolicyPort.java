package cn.iocoder.yudao.framework.etl.core.connection;

import java.util.List;
import java.util.Map;
import java.sql.Connection;
import java.sql.SQLException;

/** 调用方提供的读取授权策略。回调可抛出异常终止节点，禁止返回空 SQL 或 null 数据结果。 */
public interface EtlReadPolicyPort {

    enum ReadConnectionStage {OPENED, DRAINED}

    /**
     * 在读取第一行前和遍历结束后，观察执行已授权 SELECT 的连接。
     * 抛出异常会使节点失败；现有读取器仍负责关闭 ResultSet 和连接。
     * 校验必须有界，不得提交、回滚、关闭或改变连接状态，不得调用 RPC，
     * 也不得保留此借用连接。默认空实现保持现有策略实现的行为。
     */
    default void validateReadConnection(String nodeNo, Long databaseId, String authorizedSql,
            Connection connection, ReadConnectionStage stage) throws SQLException {
    }

    /**
     * 返回当前重写。
     */
    String rewrite(String nodeNo, Long databaseId, String sql);

    /** 每批不超过 500 行且受运行字节预算限制；只允许过滤、脱敏，不得新增数据行。 */
    List<Map<String, Object>> filter(String nodeNo, Long databaseId, String authorizedSql,
            List<Map<String, Object>> rows);

    /** 原始查询与授权查询分离，供调用方按最新行/字段策略复核，避免重复应用行条件。 */
    default List<Map<String, Object>> filter(String nodeNo, Long databaseId, String authorizedSql,
            List<Map<String, Object>> rows, String originalSql) {
        return filter(nodeNo, databaseId, authorizedSql, rows);
    }
}
