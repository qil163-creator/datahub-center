package cn.iocoder.yudao.module.dataforge.service.etltemplate;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.model.EtlDag;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlGraphConverter;

import java.util.*;
import java.nio.charset.StandardCharsets;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.ETL_TEMPLATE_INVALID;

/** 团队共享的领域边界：仅结构化无凭据配置，删除临时状态和流程级配置，不执行模板。 */
public final class EtlTemplateGraph {

    /**
     * 限制ETL模板图工具的实例化，统一通过静态能力使用。
     */
    private EtlTemplateGraph() {
    }

    private static final Map<String, Set<String>> FIELDS = Map.ofEntries(
            entry("read-db", "databaseId tableName schemaName ifSql fetchSize"),
            entry("insert-db",
                    "databaseId tableName batchSize dataSyncType sourcePrimaryKeys targetTruncate writeMode"),
            entry("generate-records", "count fields"), entry("random-records", "count seed fields"),
            entry("column-map", "regexColumnMapper"), entry("add-constant", "fields"),
            entry("sort-records", "sorts"), entry("sort-merge", "sorts"),
            entry("data-merge", "mergeStrategy joinType joinConditions addSourceTag sourceTagField removeDuplicates"),
            entry("multi-join", "joinType joinConditions maxOutputRows"),
            entry("cartesian-product", "prefixFields maxOutputRows"),
            entry("flatten-row", "sourceField separator keepSource"),
            entry("field-split", "sourceField splitType delimiter wrapper keepSource outputs"),
            entry("value-map", "sourceField targetField unmatched defaultValue mappings"),
            entry("quality-check", "ruleSetId fieldBindings disposition statusField"));

    /**
     * 获取ETL模板图的条目。
     *
     * @param code 编码
     * @param fields 字段定义
     * @return ETL模板图的条目结果集合
     */
    private static Map.Entry<String, Set<String>> entry(String code, String fields) {
        return Map.entry(code, Set.of(fields.split(" ")));
    }

    public record Fragment(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {

    }

    /**
     * 获取ETL模板图的清洗。
     *
     * @param nodes 节点
     * @param edges 边
     * @return 片段
     */
    public static Fragment sanitize(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        try {
            if (nodes == null || nodes.isEmpty() || nodes.size() > 32 || edges == null || edges.size() > 128) {
                throw new IllegalArgumentException("模板需包含 1–32 个节点，最多 128 条连线");
            }
            if (JSONUtil.toJsonStr(nodes).getBytes(StandardCharsets.UTF_8).length + JSONUtil.toJsonStr(edges)
                    .getBytes(StandardCharsets.UTF_8).length > 262_144) {
                throw new IllegalArgumentException("模板配置超过 256 KB");
            }
            List<Map<String, Object>> safeNodes = new ArrayList<>();
            for (var raw : nodes) {
                var source = JSONUtil.parseObj(raw);
                var properties = source.getJSONObject("properties");
                if (properties == null) {
                    throw new IllegalArgumentException("缺少节点属性");
                }
                String id = source.getStr("id"), code = properties.getStr("toolCode");
                if (StrUtil.isBlank(id) || id.length() > 128 || !FIELDS.containsKey(code)) {
                    throw new IllegalArgumentException(
                            "节点编号无效或组件不支持团队共享；不支持脚本、外部文件、HTTP、MQ 或加解密配置");
                }
                var config = JSONUtil.parseObj(properties.get("nodeJson") == null ? "{}" : properties.get("nodeJson"));
                if (!FIELDS.get(code).containsAll(config.keySet())) {
                    throw new IllegalArgumentException("组件包含非共享字段：" + code);
                }
                if ("read-db".equals(code) && config.getInt("ifSql", 0) != 0) {
                    throw new IllegalArgumentException("数据库模板仅支持整表模式");
                }
                if ("cartesian-product".equals(code) && config.getBool("prefixFields", true)) {
                    throw new IllegalArgumentException("笛卡尔积以节点编号作为字段前缀时不可共享模板，请先关闭字段前缀");
                }
                if ("data-merge".equals(code) && config.getBool("addSourceTag", false)) {
                    throw new IllegalArgumentException("合并以节点编号作为来源标签时不可共享模板，请先关闭来源标签");
                }
                if (Set.of("read-db", "insert-db").contains(code) && (config.getLong(
                        "databaseId") == null || config.getLong("databaseId") <= 0 || StrUtil.isBlank(
                        config.getStr("tableName")))) {
                    throw new IllegalArgumentException("数据库模板需要明确的数据源编号和表名以复核共享授权");
                }
                Object safeConfig = structured(config, 0);
                Map<String, Object> props = new LinkedHashMap<>();
                props.put("name", StrUtil.maxLength(properties.getStr("name", code), 100));
                props.put("toolCode", code);
                props.put("toolType",
                        Set.of("read-db", "generate-records", "random-records").contains(code) ? 1 : "insert-db".equals(
                                code) ? 3 : 2);
                int failGoOn = properties.getInt("failGoOn", 0);
                if (failGoOn != 0 && failGoOn != 1) {
                    throw new IllegalArgumentException("失败策略无效");
                }
                props.put("nodeJson", safeConfig);
                props.put("failGoOn", failGoOn);
                props.put("overTimes", Math.max(0, Math.min(properties.getInt("overTimes", 0), 1440)));
                safeNodes.add(
                        new TreeMap<>(Map.of("id", id, "type", "EtlTask", "x", coordinate(source.getDouble("x", 0d)),
                                "y", coordinate(source.getDouble("y", 0d)), "properties", props)));
            }
            List<Map<String, Object>> safeEdges = new ArrayList<>();
            Set<String> pairs = new HashSet<>();
            for (var raw : edges) {
                var edge = JSONUtil.parseObj(raw);
                var props = edge.getJSONObject("properties");
                String source = edge.getStr("sourceNodeId"), target = edge.getStr("targetNodeId");
                if (StrUtil.isBlank(source) || StrUtil.isBlank(target) || !pairs.add(source + "\u0000" + target)) {
                    throw new IllegalArgumentException("连线引用无效或重复");
                }
                String output = edge.getStr("sourcePort", props == null ? "main" : props.getStr("outputPort", "main"));
                if ("out".equals(output)) {
                    output = "main";
                }
                String input = edge.getStr("targetPort", "in");
                if (!output.matches("[A-Za-z0-9_-]{1,64}") || !input.matches("[A-Za-z0-9_-]{1,64}")) {
                    throw new IllegalArgumentException("端口无效");
                }
                String edgeType = props == null ? "DATA" : props.getStr("edgeType", "DATA");
                if (!Set.of("DATA", "CONTROL").contains(edgeType)) {
                    throw new IllegalArgumentException("连线类型无效");
                }
                safeEdges.add(new TreeMap<>(
                        Map.of("id", "template-edge-" + safeEdges.size(), "type", "etl-edge", "sourceNodeId", source,
                                "targetNodeId", target, "sourcePort", output, "targetPort", input, "properties",
                                new TreeMap<>(Map.of("edgeType", edgeType, "outputPort", output)))));
            }
            // 模板允许未连接的片段，但拒绝悬空引用、重复节点和递归；完整可运行校验在应用后保存/发布执行。
            EtlDag.buildLenient(EtlGraphConverter.toNodeDefs(safeNodes), EtlGraphConverter.toEdgeDefs(safeEdges))
                    .topologicalSort();
            return new Fragment(List.copyOf(safeNodes), List.copyOf(safeEdges));
        } catch (RuntimeException ex) {
            throw exception(ETL_TEMPLATE_INVALID, StrUtil.maxLength(ex.getMessage(), 200));
        }
    }

    /**
     * 获取ETL模板图的坐标。
     *
     * @param value 待处理的值
     * @return ETL模板图
     */
    private static double coordinate(double value) {
        if (!Double.isFinite(value) || Math.abs(value) > 1_000_000) {
            throw new IllegalArgumentException("坐标超出范围");
        }
        return value;
    }

    /**
     * 获取ETL模板图的结构化。
     *
     * @param value 待处理的值
     * @param depth 深度
     * @return ETL模板图
     */
    private static Object structured(Object value, int depth) {
        if (depth > 8) {
            throw new IllegalArgumentException("配置嵌套超过限制");
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() > 128) {
                throw new IllegalArgumentException("配置字段过多");
            }
            Map<String, Object> result = new TreeMap<>();
            for (var item : map.entrySet()) {
                String key = String.valueOf(item.getKey());
                String lower = key.toLowerCase(Locale.ROOT);
                if (key.length() > 100 || lower.matches(
                        ".*(password|secret|credential|authorization|token|privatekey|accesskey|jdbcurl|headers|script|fileurl|filepath).*")) {
                    throw new IllegalArgumentException("模板包含凭据或外部执行配置字段");
                }
                result.put(key, structured(item.getValue(), depth + 1));
            }
            return result;
        }
        if (value instanceof Collection<?> list) {
            if (list.size() > 1000) {
                throw new IllegalArgumentException("配置数组过长");
            }
            return list.stream().map(item -> structured(item, depth + 1)).toList();
        }
        if (value == null || value instanceof Boolean || value instanceof Number) {
            return value;
        }
        if (value instanceof String text && text.length() <= 4000) {
            return text;
        }
        throw new IllegalArgumentException("配置值类型或长度无效");
    }
}
