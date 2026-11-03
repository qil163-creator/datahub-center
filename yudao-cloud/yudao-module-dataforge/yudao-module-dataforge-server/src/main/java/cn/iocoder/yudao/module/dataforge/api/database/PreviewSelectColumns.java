package cn.iocoder.yudao.module.dataforge.api.database;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 固定表预览的列投影：请求列按忽略大小写映射到实表列名。
 */
public final class PreviewSelectColumns {

    /**
     * 限制预览SELECT列集合工具的实例化，统一通过静态能力使用。
     */
    private PreviewSelectColumns() {
    }

    /**
     * 获取预览SELECT列集合的首个缺失。
     *
     * @param requested 已请求
     * @param actualByLower 实际按小写
     * @return SELECT列集合的首个缺失预览文本
     */
    public static String firstMissing(List<String> requested, Map<String, String> actualByLower) {
        if (requested == null || actualByLower == null) {
            return null;
        }
        for (String name : requested) {
            if (StrUtil.isBlank(name)) {
                continue;
            }
            if (!actualByLower.containsKey(name.trim().toLowerCase(Locale.ROOT))) {
                return name.trim();
            }
        }
        return null;
    }

    /**
     * 解析预览SELECT列集合配置或身份。
     *
     * @param requested 已请求
     * @param actualByLower 实际按小写
     * @return 解析预览SELECT列集合配置或身份结果集合
     */
    public static List<String> resolve(List<String> requested, Map<String, String> actualByLower) {
        if (requested == null || requested.isEmpty() || actualByLower == null) {
            return List.of();
        }
        List<String> resolved = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String name : requested) {
            if (StrUtil.isBlank(name)) {
                continue;
            }
            String actual = actualByLower.get(name.trim().toLowerCase(Locale.ROOT));
            if (actual == null) {
                continue;
            }
            if (seen.add(actual.toLowerCase(Locale.ROOT))) {
                resolved.add(actual);
            }
        }
        return resolved;
    }

}
