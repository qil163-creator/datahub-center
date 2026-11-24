package cn.iocoder.yudao.framework.operatelog.core.function;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.mzt.logapi.service.IParseFunction;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 把业务编号翻译为名称的 {@link IParseFunction}，操作日志模板写法：{@code {getLogicTableName{#id}}}。
 * <p>
 * 各业务模块按实体注册一份，传入「编号集合 → 编号与名称映射」的加载方法即可：
 * <ul>
 *     <li>支持单个编号、编号集合（批量删除时的 ids）以及它们的字符串形式；多个名称用「、」连接；</li>
 *     <li>在业务方法执行前取值，删除类操作也能拿到删除前的名称；</li>
 *     <li>查不到或读取失败时退回「编号X」，不影响业务结果。</li>
 * </ul>
 */
@Slf4j
public class EntityNameParseFunction implements IParseFunction {

    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern ID_TEXT = Pattern.compile("[\\[\\]\\d,\\s]+");

    private final String functionName;
    private final Function<Collection<Long>, Map<Long, String>> loader;

    public EntityNameParseFunction(String functionName, Function<Collection<Long>, Map<Long, String>> loader) {
        this.functionName = functionName;
        this.loader = loader;
    }

    /**
     * 按实体列表构建加载方法的便捷写法。
     */
    public static <T> Function<Collection<Long>, Map<Long, String>> loader(
            Function<Collection<Long>, ? extends Collection<T>> query,
            Function<T, ? extends Number> idGetter, Function<T, String> nameGetter) {
        return ids -> {
            Map<Long, String> result = new HashMap<>();
            Collection<T> rows = query.apply(ids);
            if (rows != null) {
                rows.forEach(row -> {
                    Number id = idGetter.apply(row);
                    if (id != null) {
                        result.put(id.longValue(), nameGetter.apply(row));
                    }
                });
            }
            return result;
        };
    }

    /**
     * 「记录 → 所属对象名称」的加载方法：先查记录拿到所属对象编号，再用所属对象的加载方法取名称。
     */
    public static <T> Function<Collection<Long>, Map<Long, String>> via(
            Function<Collection<Long>, ? extends Collection<T>> query,
            Function<T, ? extends Number> idGetter, Function<T, ? extends Number> ownerIdGetter,
            Function<Collection<Long>, Map<Long, String>> ownerLoader) {
        return ids -> {
            Map<Long, Long> owners = new HashMap<>();
            Collection<T> rows = query.apply(ids);
            if (rows != null) {
                rows.forEach(row -> {
                    Number id = idGetter.apply(row);
                    Number ownerId = ownerIdGetter.apply(row);
                    if (id != null && ownerId != null) {
                        owners.put(id.longValue(), ownerId.longValue());
                    }
                });
            }
            Map<Long, String> result = new HashMap<>();
            if (owners.isEmpty()) {
                return result;
            }
            Map<Long, String> ownerNames = ownerLoader.apply(new HashSet<>(owners.values()));
            owners.forEach((id, ownerId) -> result.put(id, ownerNames == null ? null : ownerNames.get(ownerId)));
            return result;
        };
    }

    @Override
    public boolean executeBefore() {
        return true;
    }

    @Override
    public String functionName() {
        return functionName;
    }

    @Override
    public String apply(Object value) {
        List<Long> ids = parseIds(value);
        // 编号 0 表示未关联（例如顶级节点的父级）
        if (!ids.isEmpty() && ids.stream().allMatch(id -> id == 0L)) {
            return "无";
        }
        ids = ids.stream().filter(id -> id != 0L).toList();
        if (ids.isEmpty()) {
            return StrUtil.isEmptyIfStr(value) || value instanceof Collection ? "未设置" : String.valueOf(value);
        }
        Map<Long, String> names = Collections.emptyMap();
        try {
            names = loader.apply(ids);
        } catch (RuntimeException ex) {
            // 审计名称读取失败时保留编号，不改变原有业务结果。
            log.warn("[apply][{} 读取名称失败，编号({})]", functionName, ids, ex);
        }
        Map<Long, String> finalNames = names == null ? Collections.emptyMap() : names;
        return ids.stream()
                .map(id -> StrUtil.blankToDefault(finalNames.get(id), "编号" + id))
                .collect(Collectors.joining("、"));
    }

    private static List<Long> parseIds(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        Set<Long> ids = new LinkedHashSet<>();
        if (value instanceof Number number) {
            ids.add(number.longValue());
        } else if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                ids.addAll(parseIds(item));
            }
        } else if (value.getClass().isArray() && value instanceof Object[] array) {
            for (Object item : array) {
                ids.addAll(parseIds(item));
            }
        } else if (ID_TEXT.matcher(String.valueOf(value)).matches()) {
            // 仅识别「12」「[1, 2]」这类纯编号文本，避免把普通文字里的数字当成编号
            Matcher matcher = NUMBER.matcher(String.valueOf(value));
            while (matcher.find()) {
                ids.add(Long.parseLong(matcher.group()));
            }
        }
        return CollUtil.isEmpty(ids) ? Collections.emptyList() : new ArrayList<>(ids);
    }

}
