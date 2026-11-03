package cn.iocoder.yudao.framework.etl.core.model;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.Getter;

import java.util.*;

/**
 * ETL 流程 DAG：由节点与连线构建索引，提供结构校验与拓扑排序
 *
 * 参考 srt-cloud 的 PipelineGraph，校验规则：
 * 节点编号唯一、连线引用存在、无环、输入节点无数据上游、
 * 转换/输出节点恰好一个数据上游（多输入合并暂未开放）、
 * 输出与控制脚本节点的出边只能是控制边；Java/JS 行脚本可接收一个数据输入。
 *
 * @author Li
 */
@Getter
public class EtlDag {

    private final List<EtlNodeDef> nodes;

    private final List<EtlEdgeDef> edges;

    private final Map<String, EtlNodeDef> nodeMap = new LinkedHashMap<>();

    /** 数据上游：目标节点 → 源节点列表 */
    private final Map<String, List<String>> dataUpstreams = new HashMap<>();

    /** 数据下游：源节点 → (输出端口 → 目标节点列表) */
    private final Map<String, Map<String, List<String>>> dataDownstreams = new HashMap<>();

    /** 控制上游：目标节点 → 源节点列表 */
    private final Map<String, List<String>> controlUpstreams = new HashMap<>();

    /** 全部上游（数据 + 控制） */
    private final Map<String, List<String>> allUpstreams = new HashMap<>();

    /**
     * 初始化 {@code EtlDag}。
     */
    private EtlDag(List<EtlNodeDef> nodes, List<EtlEdgeDef> edges) {
        this.nodes = nodes;
        this.edges = edges;
    }

    /**
     * 构建 DAG 并做结构校验
     *
     * @throws IllegalStateException 校验失败时抛出，消息为可读的中文错误
     */
    public static EtlDag build(List<EtlNodeDef> nodes, List<EtlEdgeDef> edges) {
        EtlDag dag = new EtlDag(nodes == null ? List.of() : nodes, edges == null ? List.of() : edges);
        dag.init();
        dag.validate();
        return dag;
    }

    /**
     * 宽松构建：只建立索引不做结构校验（字段契约推导时画布可能尚未连完整）
     */
    public static EtlDag buildLenient(List<EtlNodeDef> nodes, List<EtlEdgeDef> edges) {
        EtlDag dag = new EtlDag(nodes == null ? List.of() : nodes, edges == null ? List.of() : edges);
        dag.init();
        return dag;
    }

    /**
     * 校验节点和依赖关系，并初始化 ETL 有向无环图。
     */
    private void init() {
        if (CollUtil.isEmpty(nodes)) {
            throw new IllegalStateException("流程未包含任何节点");
        }
        for (EtlNodeDef node : nodes) {
            if (StrUtil.isBlank(node.getNo())) {
                throw new IllegalStateException("存在缺少编号的节点");
            }
            if (nodeMap.put(node.getNo(), node) != null) {
                throw new IllegalStateException(String.format("节点编号【%s】重复", node.getNo()));
            }
        }
        for (EtlEdgeDef edge : edges) {
            EtlNodeDef source = nodeMap.get(edge.getSourceNo());
            EtlNodeDef target = nodeMap.get(edge.getTargetNo());
            if (source == null || target == null) {
                throw new IllegalStateException("存在引用了不存在节点的连线");
            }
            allUpstreams.computeIfAbsent(edge.getTargetNo(), key -> new ArrayList<>()).add(edge.getSourceNo());
            if (edge.isControl() || isControlSemantics(source, target)) {
                controlUpstreams.computeIfAbsent(edge.getTargetNo(), key -> new ArrayList<>())
                        .add(edge.getSourceNo());
            } else {
                dataUpstreams.computeIfAbsent(edge.getTargetNo(), key -> new ArrayList<>())
                        .add(edge.getSourceNo());
                dataDownstreams.computeIfAbsent(edge.getSourceNo(), key -> new HashMap<>())
                        .computeIfAbsent(edge.resolveOutputPort(), key -> new ArrayList<>())
                        .add(edge.getTargetNo());
            }
        }
    }

    /**
     * 输出节点及控制脚本使用控制语义；Java/JS 的工具分类仍是脚本，但 execute(rows) 按行处理。
     */
    private boolean isControlSemantics(EtlNodeDef source, EtlNodeDef target) {
        return source.getToolType() != null && (source.getToolType() == EtlNodeDef.TOOL_TYPE_OUTPUT
                || (source.getToolType() == EtlNodeDef.TOOL_TYPE_SCRIPT && !source.isRowScript()))
                || (target.getToolType() != null && target.getToolType() == EtlNodeDef.TOOL_TYPE_SCRIPT && !target.isRowScript());
    }

    /**
     * 检查连线不重复、端口引用有效且流程不存在循环。
     */
    private void validate() {
        Set<String> pairs = new HashSet<>();
        for (EtlEdgeDef edge : edges) {
            if (!pairs.add(edge.getSourceNo() + "->" + edge.getTargetNo())) {
                throw new IllegalStateException("同一对节点之间不允许重复连线");
            }
        }
        // 显式输入槽按序排列；旧图未提供槽位时保持原有边顺序。
        for (EtlNodeDef node : nodes) {
            if (node.isMultiInput()) {
                Map<String, Integer> slots = new HashMap<>();
                for (EtlEdgeDef edge : edges) {
                    if (node.getNo().equals(edge.getTargetNo())
                            && !edge.isControl() && !isControlSemantics(nodeMap.get(edge.getSourceNo()), node)) {
                        String port = edge.getInputPort();
                        if (StrUtil.isBlank(port) || Set.of("in", "main").contains(port)) {
                            continue;
                        }
                        if (!port.matches("input-[1-9][0-9]{0,2}")) {
                            throw new IllegalStateException("多输入节点的输入槽必须是 input-1 至 input-999");
                        }
                        int slot = Integer.parseInt(edge.getInputPort().substring(6));
                        if (slots.containsValue(slot)) {
                            throw new IllegalStateException("多输入节点的输入槽重复");
                        }
                        slots.put(edge.getSourceNo(), slot);
                    }
                }
                if (!slots.isEmpty()) {
                    var upstream = dataUpstreams.getOrDefault(node.getNo(), List.of());
                    if (slots.size() != upstream.size()) {
                        throw new IllegalStateException("多输入节点需要为所有数据连线指定输入槽");
                    }
                    upstream.sort(Comparator.comparingInt(slots::get));
                }
            }
        }
        for (EtlNodeDef node : nodes) {
            int dataUpstreamCount = dataUpstreams.getOrDefault(node.getNo(), List.of()).size();
            Integer toolType = node.getToolType();
            if (toolType == null) {
                throw new IllegalStateException(String.format("节点【%s】缺少工具类型", node.getName()));
            }
            switch (toolType) {
                case EtlNodeDef.TOOL_TYPE_INPUT -> {
                    if (dataUpstreamCount > 0) {
                        throw new IllegalStateException(
                                String.format("输入节点【%s】不允许有数据上游", node.getName()));
                    }
                }
                case EtlNodeDef.TOOL_TYPE_TRANSFORM, EtlNodeDef.TOOL_TYPE_OUTPUT -> {
                    if (node.isMultiInput()) {
                        if (dataUpstreamCount < 2) {
                            throw new IllegalStateException(String.format(
                                    "合并节点【%s】至少需要两个数据上游（当前 %s 个）", node.getName(), dataUpstreamCount));
                        }
                    } else if (dataUpstreamCount != 1) {
                        throw new IllegalStateException(String.format(
                                "节点【%s】必须且只能有一个数据上游（当前 %s 个）", node.getName(), dataUpstreamCount));
                    }
                }
                default -> {
                    if (node.isRowScript()) {
                        if (dataUpstreamCount > 1) {
                            throw new IllegalStateException(
                                    String.format("行脚本节点【%s】最多允许一个数据上游（当前 %s 个）", node.getName(),
                                            dataUpstreamCount));
                        }
                        // 无输入的初始化或控制脚本保持与历史定义兼容。
                    } else if (dataUpstreamCount > 0) {
                        // 其他脚本工具只使用控制边。
                        throw new IllegalStateException(
                                String.format("脚本节点【%s】不能接收数据边", node.getName()));
                    }
                }
            }
        }
        topologicalSort();
    }

    /**
     * Kahn 拓扑排序，同时完成环检测
     *
     * @return 拓扑序节点编号列表
     */
    public List<String> topologicalSort() {
        Map<String, Integer> inDegree = new HashMap<>();
        nodes.forEach(node -> inDegree.put(node.getNo(), 0));
        allUpstreams.forEach((target, sources) -> inDegree.merge(target, sources.size(), Integer::sum));
        Deque<String> queue = new ArrayDeque<>();
        inDegree.forEach((no, degree) -> {
            if (degree == 0) {
                queue.add(no);
            }
        });
        Map<String, List<String>> allDownstreams = new HashMap<>();
        for (EtlEdgeDef edge : edges) {
            allDownstreams.computeIfAbsent(edge.getSourceNo(), key -> new ArrayList<>()).add(edge.getTargetNo());
        }
        List<String> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String no = queue.poll();
            sorted.add(no);
            for (String downstream : allDownstreams.getOrDefault(no, List.of())) {
                if (inDegree.merge(downstream, -1, Integer::sum) == 0) {
                    queue.add(downstream);
                }
            }
        }
        if (sorted.size() != nodes.size()) {
            throw new IllegalStateException("流程存在循环依赖，请检查连线");
        }
        return sorted;
    }

    /**
     * 返回节点。
     */
    public EtlNodeDef getNode(String no) {
        return nodeMap.get(no);
    }

    /**
     * 节点的唯一数据上游编号（无则返回 null）
     */
    public String getDataUpstream(String no) {
        List<String> upstreams = dataUpstreams.get(no);
        return CollUtil.isEmpty(upstreams) ? null : upstreams.get(0);
    }

    /**
     * 节点的全部数据上游编号列表（多输入节点用）
     */
    public List<String> getDataUpstreamList(String no) {
        return dataUpstreams.getOrDefault(no, List.of());
    }

    /**
     * 节点指定输出端口的数据下游编号列表
     */
    public List<String> getDataDownstreams(String no, String outputPort) {
        return dataDownstreams.getOrDefault(no, Map.of()).getOrDefault(outputPort, List.of());
    }

    /**
     * 节点全部数据下游编号列表（所有端口）
     */
    public List<String> getAllDataDownstreams(String no) {
        List<String> result = new ArrayList<>();
        dataDownstreams.getOrDefault(no, Map.of()).values().forEach(result::addAll);
        return result;
    }

    /**
     * 返回控制上游集合。
     */
    public List<String> getControlUpstreams(String no) {
        return controlUpstreams.getOrDefault(no, List.of());
    }

}
