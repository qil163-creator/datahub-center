package cn.iocoder.yudao.framework.dbswitch.data.config;

import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;

import java.util.HashSet;
import java.util.List;

/** 按字面值提供的物理表名；此契约不授予访问权限，也不展开模式表达式。 */
public final class SourceTableSelection {

    /**
     * 初始化 {@code SourceTableSelection}。
     */
    private SourceTableSelection() {
    }

    /**
     * 校验并取得精确名称。
     */
    public static String requireExactName(String name) {
        if (name == null || name.isEmpty() || name.length() > 1024 || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Exact source table names must be nonempty, bounded and contain no NUL");
        }
        return name;
    }

    /**
     * 校验并取得精确名称集合。
     */
    public static List<String> requireExactNames(List<String> names) {
        if (names == null || names.size() > 10_000) {
            throw new IllegalArgumentException("Exact source table selection is missing or too large");
        }
        var distinct = new HashSet<String>();
        for (String name : names) {
            requireExactName(name);
            if (!distinct.add(name)) {
                throw new IllegalArgumentException("Exact source table selection contains duplicates");
            }
        }
        return List.copyOf(names);
    }

    /** null 保留旧版 CSV 或模式 API；空的字面值列表继续表示原有 ALL 语义。 */
    public static List<String> exactNames(SourceDataSourceProperties source) {
        if (source.getSourceTableNames() == null) {
            return null;
        }
        if (!Integer.valueOf(1).equals(source.getSourceType())
                || !Integer.valueOf(0).equals(source.getIncludeOrExclude()) && !Integer.valueOf(1)
                .equals(source.getIncludeOrExclude())) {
            throw new IllegalArgumentException(
                    "Exact table selection requires a table source and an explicit selection mode");
        }
        String activeCsv = Integer.valueOf(1).equals(source.getIncludeOrExclude())
                ? source.getSourceIncludes() : source.getSourceExcludes();
        if (activeCsv != null && !activeCsv.isEmpty()) {
            throw new IllegalArgumentException("Exact table selection cannot be combined with active legacy CSV");
        }
        return requireExactNames(source.getSourceTableNames());
    }
}
