package cn.iocoder.yudao.framework.dbswitch.common.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SqlUtil
 *
 * 迁移自net.srt.framework.common.utils.SqlInjectionChecker
 *
 * @author zrx
 * @since 2021/7/14 21:57
 */
public class SqlInjectionChecker {

    /**
     * 是否含有sql注入，返回true表示含有
     *
     * @param obj 待检测的对象
     * @return true表示含有SQL注入风险，false表示安全
     */
    public static boolean containsSqlInjection(Object obj) {
        if (obj == null) {
            return false;
        }

        String input = obj.toString().trim();
        if (input.isEmpty()) {
            return false;
        }

        // SQL关键词检测（不区分大小写）
        String sqlKeywords = "(?i)\\b(and|or|exec|execute|insert|select|drop|grant|alter|delete|update|count|chr|mid|master|truncate|char|declare|union|join|having|where|order|group|by|asc|desc|limit|offset|into|values|set|create|table|database|schema|index|view|procedure|function|trigger|if|else|case|when|then|end|exists|in|not|like|between|is|null|true|false|xp_cmdshell|sp_executesql|openrowset|opendatasource|bulk|load_file|outfile|dumpfile|information_schema|sys|performance_schema|pg_catalog|sqlite_master)\\b";

        // 十六进制编码检测
        String hexPattern = "0x[0-9a-fA-F]+";

        // 联合查询检测
        String unionPattern = "(?i)union\\s+(all\\s+)?select";

        // 布尔盲注检测
        String booleanPattern = "(?i)(\\s+(and|or)\\s+)?(\\d+\\s*[=<>!]+\\s*\\d+|'[^']*'\\s*[=<>!]+\\s*'[^']*'|true|false)";

        // 时间盲注检测
        String timePattern = "(?i)(sleep|benchmark|waitfor|delay)\\s*\\(";

        // 错误注入检测
        String errorPattern = "(?i)(extractvalue|updatexml|exp|floor|rand)\\s*\\(";

        // 堆叠查询检测
        String stackedPattern = ";\\s*\\w+";

        // 组合所有模式
        String combinedPattern = String.format("(%s)|(%s)|(%s)|(%s)|(%s)|(%s)|(%s)",
                sqlKeywords, hexPattern, unionPattern,
                booleanPattern, timePattern, errorPattern, stackedPattern);

        Pattern pattern = Pattern.compile(combinedPattern);
        Matcher matcher = pattern.matcher(input);

        return matcher.find();
    }
}
