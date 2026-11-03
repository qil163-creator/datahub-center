package cn.iocoder.yudao.framework.dbswitch.core.model;

import lombok.Data;
import lombok.ToString;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * JDBC 查询结果模型,封装 SQL 执行返回的列名、行数据、分页信息、耗时与成功状态,供 SQL 执行器返回给调用方。
 */
@Data
@ToString(onlyExplicitlyIncluded = true)
public class JdbcSelectResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<JdbcSelectResult> results;

    private Boolean ifQuery;

    private String sql;

    private Long time;

    private Boolean success;

    /** 仅表示 JDBC 边界结果；NOT_MANAGED 表示未报告本组件持有的显式事务。 */
    private TransactionOutcome transactionOutcome = TransactionOutcome.NOT_MANAGED;

    private String errorMsg;

    private Integer count;

    private List<String> columns;

    private List<Map<String, Object>> rowData;

    private List<Map<String, Object>> list;

    private Integer page;

    private Integer limit;

    private Long total;

    public enum TransactionOutcome {
        NOT_MANAGED, COMMITTED, ROLLED_BACK, UNKNOWN
    }
}
