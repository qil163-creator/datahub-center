package cn.iocoder.yudao.module.dataforge.service.push;

import cn.hutool.core.util.StrUtil;

/**
 * 推送任务自定义 SQL 校验：只允许单条 SELECT
 *
 * @author Li
 */
public final class DataPushSqlValidator {

    /**
     * 限制数据推送SQL校验器工具的实例化，统一通过静态能力使用。
     */
    private DataPushSqlValidator() {
    }

    /**
     * @return 规范化后的 SQL；不合法时抛 {@link IllegalArgumentException}
     */
    public static String validateSelect(String sqlText) {
        if (StrUtil.isBlank(sqlText)) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        String sql = sqlText.trim();
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        String upper = normalizeForKeywordCheck(sql);
        if (upper.contains(";")) {
            throw new IllegalArgumentException("仅允许单条 SELECT，不能包含多个语句");
        }
        if (!upper.startsWith("SELECT ") && !upper.startsWith("WITH ")) {
            throw new IllegalArgumentException("仅允许 SELECT / WITH 查询");
        }
        if (upper.contains(" INTO ")
                || upper.matches(".*\\b(INSERT|UPDATE|DELETE|DROP|ALTER|TRUNCATE|CREATE|GRANT|REVOKE|"
                + "COPY|CALL|EXECUTE|PERFORM|VACUUM|ANALYZE)\\b.*")
                || upper.contains(" COMMENT ON ") || upper.contains(" LOCK TABLE ")
                || upper.matches(".*\\bDO\\s+\\$.*")
                || upper.contains(" SET ROLE ") || upper.contains(" SET SESSION ")) {
            throw new IllegalArgumentException("不允许包含写操作、DDL 或过程调用");
        }
        return sql;
    }

    /**
     * 去掉注释和字面量后再做关键字检查，避免 WHERE note LIKE '%DELETE%' 被误拒
     */
    static String normalizeForKeywordCheck(String sql) {
        String stripped = sql.replaceAll("(?s)/\\*.*?\\*/", " ");
        stripped = stripped.replaceAll("--[^\\n]*", " ");
        stripped = stripped.replaceAll("'(?:''|[^'])*'", "''");
        stripped = stripped.replaceAll("\"(?:\"\"|[^\"])*\"", "\"\"");
        stripped = stripped.replaceAll("`[^`]*`", "``");
        stripped = stripped.replaceAll("(?s)\\$[A-Za-z0-9_]*\\$.*?\\$[A-Za-z0-9_]*\\$", "''");
        return stripped.replaceAll("\\s+", " ").trim().toUpperCase();
    }

}
