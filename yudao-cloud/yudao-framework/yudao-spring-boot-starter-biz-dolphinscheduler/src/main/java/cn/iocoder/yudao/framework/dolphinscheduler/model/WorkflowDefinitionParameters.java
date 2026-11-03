package cn.iocoder.yudao.framework.dolphinscheduler.model;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.request.CreateWorkflowDefinitionRequest;

import java.util.*;

/** 原生工作流图与执行策略参数编码，不包含业务实体或凭据。 */
public final class WorkflowDefinitionParameters {
    private WorkflowDefinitionParameters() { }

    public record Node(String id, String name, String taskType, Map<String, Object> parameters,
                       List<String> upstream, String priority) {
        public Node(String id, String name, String taskType, Map<String, Object> parameters,
                    List<String> upstream) {
            this(id, name, taskType, parameters, upstream, null);
        }
    }
    public record Execution(String priority, String workerGroup, Long environmentCode, int retryCount,
                            int retryInterval, int delayMinutes, String strategy) { }

    /** 编码真实节点和连线；控制根节点使用 DS 的零编码约定。 */
    public static CreateWorkflowDefinitionRequest build(String name, String description, List<Node> nodes,
                                                        List<Long> taskCodes, Execution options) {
        if (nodes.isEmpty() || nodes.size() > 200 || taskCodes.size() != nodes.size()
                || new HashSet<>(taskCodes).size() != taskCodes.size()
                || taskCodes.stream().anyMatch(code -> code == null || code <= 0)
                || !Set.of("PARALLEL", "SERIAL_WAIT", "SERIAL_DISCARD", "SERIAL_PRIORITY").contains(options.strategy())) {
            throw new IllegalArgumentException("工作流节点编码或执行策略无效");
        }
        Map<String, Long> mapping = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            if (mapping.put(nodes.get(i).id(), taskCodes.get(i)) != null) throw new IllegalArgumentException("工作流节点编号重复");
        }
        var definitions = new JSONArray(); var relations = new JSONArray();
        for (var node : nodes) {
            if (!mapping.keySet().containsAll(node.upstream())) throw new IllegalArgumentException("工作流引用不存在的节点");
            String priority = node.priority() == null ? options.priority() : node.priority();
            if (!Set.of("HIGHEST", "HIGH", "MEDIUM", "LOW", "LOWEST").contains(priority))
                throw new IllegalArgumentException("工作流节点优先级无效");
            definitions.add(new JSONObject().set("code", mapping.get(node.id())).set("name", node.name())
                    .set("description", description).set("flag", "YES").set("taskType", node.taskType())
                    .set("taskParams", node.parameters()).set("taskPriority", priority)
                    .set("workerGroup", options.workerGroup()).set("environmentCode", options.environmentCode())
                    .set("failRetryTimes", options.retryCount()).set("failRetryInterval", options.retryInterval())
                    .set("timeoutFlag", "CLOSE").set("timeout", 0).set("timeoutNotifyStrategy", "FAILED")
                    .set("delayTime", options.delayMinutes()).set("cpuQuota", -1).set("memoryMax", -1)
                    .set("isCache", "NO").set("taskExecuteType", "BATCH"));
            var parents = node.upstream().isEmpty() ? List.of("_root") : node.upstream();
            for (var parent : parents) relations.add(new JSONObject().set("name", "")
                    .set("preTaskCode", mapping.getOrDefault(parent, 0L)).set("preTaskVersion", 0)
                    .set("postTaskCode", mapping.get(node.id())).set("postTaskVersion", 0)
                    .set("conditionType", "NONE").set("conditionParams", new JSONObject()));
        }
        return new CreateWorkflowDefinitionRequest().setName(name).setDescription(description)
                .setGlobalParams("[]").setLocations("[]").setTimeout(0).setExecutionType(options.strategy())
                .setTaskDefinitionJson(definitions.toString()).setTaskRelationJson(relations.toString());
    }
}
