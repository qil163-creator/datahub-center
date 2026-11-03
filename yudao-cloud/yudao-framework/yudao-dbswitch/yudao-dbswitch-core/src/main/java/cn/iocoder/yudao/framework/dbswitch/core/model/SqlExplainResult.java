package cn.iocoder.yudao.framework.dbswitch.core.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

/** 组件持有的语法检查结果。语法解析不会执行数据库 EXPLAIN。 */
@Data
@ToString(onlyExplicitlyIncluded = true)
public class SqlExplainResult {

    @ToString.Include
    private Integer index;

    @ToString.Include
    private String type;

    private String sql;

    private String parse;

    private String explain;

    private String error;

    @ToString.Include
    private boolean parseTrue;

    @ToString.Include
    private boolean explainTrue;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime explainTime;

    /**
     * 返回当前已解析。
     */
    public static SqlExplainResult parsed(int index, String type, String sql) {
        SqlExplainResult result = new SqlExplainResult();
        result.index = index;
        result.type = type;
        result.sql = sql;
        result.parseTrue = true;
        result.explainTime = LocalDateTime.now();
        return result;
    }

    /**
     * 标记当前操作失败并调用其失败处理逻辑。
     */
    public static SqlExplainResult fail(String sql, String error) {
        SqlExplainResult result = new SqlExplainResult();
        result.index = 1;
        result.sql = sql;
        result.error = error;
        result.explainTime = LocalDateTime.now();
        return result;
    }
}
