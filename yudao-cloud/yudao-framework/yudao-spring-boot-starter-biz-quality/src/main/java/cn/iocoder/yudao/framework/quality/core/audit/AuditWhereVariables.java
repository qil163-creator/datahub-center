package cn.iocoder.yudao.framework.quality.core.audit;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 质量规则 Where 条件中的业务日期变量。
 *
 * <p>支持 {@code ${bizdate}}（yyyy-MM-dd）、{@code ${bizdate_nodash}}（yyyyMMdd）与 {@code ${bizmonth}}（yyyy-MM）。
 * 变量被替换为带单引号的 SQL 字符串字面量，取值只含数字和短横线，不会改变条件结构；写成 {@code '${bizdate}'} 也可以，
 * 结果相同。变量只能单独出现或整体被单引号包住，嵌在更长的字符串里（例如 {@code 'x${bizdate}'}）或出现未知变量时拒绝。
 * 业务日期由调用方决定（例如调度时间的前一天），本类不读取时钟。</p>
 */
public final class AuditWhereVariables {

    private static final Pattern VARIABLE = Pattern.compile("('?)\\$\\{([A-Za-z_][A-Za-z0-9_]*)}('?)");

    private static final DateTimeFormatter DASHED = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter NO_DASH = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private AuditWhereVariables() {
    }

    /**
     * 条件中是否引用了变量。
     *
     * @param where Where 条件模板
     * @return 是否含变量
     */
    public static boolean hasVariables(String where) {
        return where != null && VARIABLE.matcher(where).find();
    }

    /**
     * 按业务日期替换变量。
     *
     * @param where Where 条件模板，可为空
     * @param bizDate 业务日期
     * @return 替换后的条件；原值为空或不含变量时原样返回
     * @throws IllegalArgumentException 未知变量或变量写法不合法
     */
    public static String render(String where, LocalDate bizDate) {
        if (where == null || where.indexOf("${") < 0) {
            return where;
        }
        Matcher matcher = VARIABLE.matcher(where);
        StringBuilder rendered = new StringBuilder(where.length() + 16);
        while (matcher.find()) {
            boolean leading = !matcher.group(1).isEmpty();
            boolean trailing = !matcher.group(3).isEmpty();
            if (leading != trailing) {
                throw new IllegalArgumentException("日期变量须单独使用或整体用单引号包住，例如 biz_date = ${bizdate}");
            }
            String value = switch (matcher.group(2)) {
                case "bizdate" -> DASHED.format(bizDate);
                case "bizdate_nodash" -> NO_DASH.format(bizDate);
                case "bizmonth" -> MONTH.format(bizDate);
                default -> throw new IllegalArgumentException("不支持的变量 ${" + matcher.group(2)
                        + "}，可用 ${bizdate}、${bizdate_nodash}、${bizmonth}");
            };
            matcher.appendReplacement(rendered, Matcher.quoteReplacement("'" + value + "'"));
        }
        matcher.appendTail(rendered);
        if (rendered.indexOf("${") >= 0) {
            throw new IllegalArgumentException("日期变量格式应为 ${bizdate}、${bizdate_nodash} 或 ${bizmonth}");
        }
        return rendered.toString();
    }

}
