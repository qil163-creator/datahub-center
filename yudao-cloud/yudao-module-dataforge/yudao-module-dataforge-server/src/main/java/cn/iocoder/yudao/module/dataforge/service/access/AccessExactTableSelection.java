package cn.iocoder.yudao.module.dataforge.service.access;

import java.util.Arrays;
import java.util.List;

/**
 * 当前任务使用 CSV 表范围，不能表达逗号或组件 glob，也不能静默 trim/丢掉空项。
 * 此限制只属于旧业务 CSV；新任务的类型化名称集合由 DB Switch 的公开契约校验。
 * 不校验目标表名、列名、正则映射或 SQL，不把可编码集合当作授权结果。
 */
public final class AccessExactTableSelection {

    /**
     * 限制访问精确表选择工具的实例化，统一通过静态能力使用。
     */
    private AccessExactTableSelection() {
    }

    /**
     * 校验名称。
     *
     * @param name 名称
     * @return 校验名称文本
     */
    public static String requireName(String name) {
        if (name == null || name.isEmpty() || name.indexOf(',') >= 0 || name.indexOf('*') >= 0
                || name.indexOf('?') >= 0 || name.indexOf('\0') >= 0
                || boundaryWhitespace(name.codePointAt(0)) || boundaryWhitespace(name.codePointBefore(name.length()))) {
            throw invalid();
        }
        return name;
    }

    /**
     * 校验名称。
     *
     * @param names 名称
     * @return 校验名称结果集合
     */
    public static List<String> requireNames(List<String> names) {
        if (names == null) {
            throw invalid();
        }
        names.forEach(AccessExactTableSelection::requireName);
        return List.copyOf(names);
    }

    /** 空 CSV（含旧定义缺省 null）表示待发现的 ALL；非空 CSV 的每一个原始项都必须精确可编码。 */
    public static List<String> decodeCsv(String csv) {
        return csv == null || csv.isEmpty() ? List.of() : requireNames(Arrays.asList(csv.split(",", -1)));
    }

    /**
     * 编码访问精确表选择。
     *
     * @param names 名称
     * @return 编码访问精确表选择文本
     */
    public static String encode(List<String> names) {
        return String.join(",", requireNames(names));
    }

    /** 业务适配层：保留严格的旧版 CSV 规则，将新的字面量语义委托给 DB Switch。 */
    public static List<String> selectedNames(
            cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties source) {
        var exact = cn.iocoder.yudao.framework.dbswitch.data.config.SourceTableSelection.exactNames(source);
        return exact != null ? exact : decodeCsv(Integer.valueOf(1).equals(source.getIncludeOrExclude())
                ? source.getSourceIncludes() : source.getSourceExcludes());
    }

    /**
     * 业务适配层：保留严格的旧版 CSV 规则，将新的字面量语义委托给 DB Switch。
     *
     * @param source 来源数据
     * @return 业务适配层：保留严格的旧版 CSV 规则，将新的字面量语义委托给 DB Switch结果集合
     */
    public static List<String> selectedNames(
            cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfig.Source source) {
        if (source.getSourceTableNames() != null) {
            return cn.iocoder.yudao.framework.dbswitch.data.config.SourceTableSelection.requireExactNames(
                    source.getSourceTableNames());
        }
        return decodeCsv(Integer.valueOf(1)
                .equals(source.getIncludeOrExclude()) ? source.getSourceIncludes() : source.getSourceExcludes());
    }

    /**
     * 判断边界空白字符条件是否成立。
     *
     * @param codePoint 编码点
     * @return 处理是否成功
     */
    private static boolean boundaryWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    /**
     * 构造当前配置或输入不合法时使用的异常。
     *
     * @return 非法参数异常
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "源表名无法在当前任务 CSV 中精确表达：不允许空项、逗号、通配符、边界空白或 NUL");
    }
}
