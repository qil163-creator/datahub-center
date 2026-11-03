package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 字段名映射节点（toolCode: column-map）
 *
 * 配置 regexColumnMapper: [{fromPattern, toValue}]，按正则重命名字段；toValue 为空表示移除该字段
 *
 * @author Li
 */
public class ColumnMapExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "column-map";

    /**
     * 初始化 {@code ColumnMapExecutor}及其父类状态。
     */
    public ColumnMapExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    private record Mapper(Pattern pattern, String toValue) {

    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        List<Mapper> mappers = parseMappers();
        Map<String, Object> row;
        while ((row = readNextRow()) != null) {
            Map<String, Object> mapped = new LinkedHashMap<>(row.size());
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                String name = entry.getKey();
                boolean removed = false;
                for (Mapper mapper : mappers) {
                    if (!mapper.pattern.matcher(name).find()) {
                        continue;
                    }
                    if (StrUtil.isBlank(mapper.toValue)) {
                        removed = true;
                    } else {
                        name = mapper.pattern.matcher(name).replaceAll(mapper.toValue);
                    }
                    break;
                }
                if (!removed) {
                    mapped.put(name, entry.getValue());
                }
            }
            pushRow(mapped);
        }
    }

    /**
     * 解析映射规则集合。
     */
    private List<Mapper> parseMappers() {
        List<Mapper> mappers = new ArrayList<>();
        JSONArray array = config().getJSONArray("regexColumnMapper");
        if (array == null) {
            return mappers;
        }
        for (Object item : array) {
            JSONObject json = (JSONObject) item;
            String fromPattern = json.getStr("fromPattern");
            if (StrUtil.isBlank(fromPattern)) {
                continue;
            }
            mappers.add(new Mapper(Pattern.compile(fromPattern), json.getStr("toValue")));
        }
        return mappers;
    }

}
