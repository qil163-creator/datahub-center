package cn.iocoder.yudao.framework.dbswitch.core.lineage;

/**
 * 分析过程中发现的问题。消息只包含标识符和原因，不包含 SQL 原文、字面量或连接信息。
 *
 * @param code           稳定的原因码，见 {@link Code}
 * @param message        中文说明
 * @param statementIndex 语句序号（从 1 开始）；脚本级问题为 null
 * @param degrading      是否使结果降级为部分解析
 */
public record LineageIssue(String code, String message, Integer statementIndex, boolean degrading) {

    /** 稳定原因码。新增码值只能追加，不得改变既有含义。 */
    public enum Code {
        SCRIPT_TOO_LARGE(true, "脚本超过可分析的长度上限"),
        STATEMENT_LIMIT(true, "语句数量超过上限，其余语句未分析"),
        PARSE_ERROR(true, "语句无法按当前数据库方言解析"),
        NOT_A_QUERY(true, "只接受单条查询语句"),
        UNSUPPORTED_STATEMENT(true, "存储过程、函数调用或代码块内部的血缘未解析"),
        UNSUPPORTED_TABLE_SOURCE(true, "存在无法识别的数据来源（如表函数），其字段来源未解析"),
        UNSUPPORTED_QUERY(true, "存在无法识别的查询结构，其字段来源未解析"),
        UNRESOLVED_COLUMN(true, "无法确定字段 %s 的来源"),
        AMBIGUOUS_COLUMN(true, "字段 %s 可能来自多张表，已按候选表全部记录并标记为不确定"),
        WILDCARD_UNEXPANDED(true, "%s.* 缺少表结构，无法展开为字段级血缘"),
        TARGET_COLUMNS_UNKNOWN(true, "目标表 %s 缺少表结构且语句未写列清单，只保留表级血缘"),
        COLUMN_COUNT_MISMATCH(true, "目标表 %s 的列数与查询输出列数不一致，只映射了可对齐的部分"),
        UNNAMED_OUTPUT(true, "查询输出存在未命名的表达式，无法映射到目标字段"),
        PARAMETERIZED_TABLE(true, "表名 %s 包含运行参数，已按原样记录"),
        CATALOG_UNAVAILABLE(true, "读取表结构失败，字段级血缘可能不完整"),
        TOO_COMPLEX(true, "语句结构过于复杂，已停止分析该语句"),
        ANALYSIS_ERROR(true, "语句分析失败"),
        TRUNCATED(true, "血缘边数量超过上限，已截断");

        private final boolean degrading;
        private final String template;

        /**
         * 初始化原因码。
         */
        Code(boolean degrading, String template) {
            this.degrading = degrading;
            this.template = template;
        }

        /**
         * 按模板生成问题。
         */
        LineageIssue of(Integer statementIndex, Object... args) {
            String message = args.length == 0 ? template : String.format(template, args);
            return new LineageIssue(name(), message, statementIndex, degrading);
        }
    }
}
