package cn.iocoder.yudao.framework.dbswitch.core.util;


import cn.iocoder.yudao.framework.dbswitch.common.util.StringUtil;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 文本处理工具类,提供 SQL 语句按分隔符拆分、行内注释去除等预处理能力。
 * SqlUtil
 *
 */
public class SqlUtil {

    private static final String SEMICOLON = ";";

    /**
     * 返回语句集合。
     */
    public static String[] getStatements(String sql, String sqlSeparator) {
        if (StringUtil.isBlank(sql)) {
            return new String[0];
        }

        String[] splits = sql.replace(";\r\n", ";\n").split(sqlSeparator);
        String lastStatement = splits[splits.length - 1].trim();
        if (lastStatement.endsWith(SEMICOLON)) {
            splits[splits.length - 1] = lastStatement.substring(0, lastStatement.length() - 1);
        }

        return splits;
    }

    /**
     * 移除当前 SQL 或脚本中的注释。
     */
    public static String removeNote(String sql) {
        if (StringUtil.isNotBlank(sql)) {
            sql = sql.replaceAll("\u00A0", " ")
                    .replaceAll("[\r\n]+", "\n")
                    .replaceAll("--([^'\n]{0,}('[^'\n]{0,}'){0,1}[^'\n]{0,}){0,}", "").trim();
        }
        return sql;
    }

    /**
     * 替换当前模板中已声明的参数引用。
     */
    public static String replaceAllParam(String sql, String name, String value) {
        return sql.replaceAll("\\$\\{" + name + "}", value);
    }

}
