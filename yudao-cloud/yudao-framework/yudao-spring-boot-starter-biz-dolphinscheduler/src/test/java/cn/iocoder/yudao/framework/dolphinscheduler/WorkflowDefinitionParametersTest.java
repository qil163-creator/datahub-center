package cn.iocoder.yudao.framework.dolphinscheduler;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dolphinscheduler.model.WorkflowDefinitionParameters;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowDefinitionParametersTest {
    @Test void preservesDependenciesAndExecutionSettings() {
        var nodes = List.of(new WorkflowDefinitionParameters.Node("input", "输入", "FLINK", Map.of("rawScript", "select 1"), List.of()),
                new WorkflowDefinitionParameters.Node("output", "输出", "SPARK", Map.of("rawScript", "select 2"), List.of("input")));
        var result = WorkflowDefinitionParameters.build("workflow", "说明", nodes, List.of(9007199254740993L, 9007199254740994L), options());
        var relations = JSONUtil.parseArray(result.getTaskRelationJson());
        assertEquals(9007199254740993L, relations.getJSONObject(1).getLong("preTaskCode"));
        assertEquals(9007199254740994L, relations.getJSONObject(1).getLong("postTaskCode"));
        var task = JSONUtil.parseArray(result.getTaskDefinitionJson()).getJSONObject(0);
        assertEquals(3, task.getInt("failRetryTimes")); assertEquals(4, task.getInt("failRetryInterval"));
        assertEquals("worker", task.getStr("workerGroup")); assertEquals(5, task.getInt("delayTime"));
        assertEquals("SERIAL_WAIT", result.getExecutionType());
    }

    @Test void rejectsDanglingReferencesAndDuplicateCodes() {
        var node = new WorkflowDefinitionParameters.Node("a", "a", "FLINK", Map.of(), List.of("missing"));
        assertThrows(IllegalArgumentException.class, () -> WorkflowDefinitionParameters.build("w", "", List.of(node), List.of(1L), options()));
        assertThrows(IllegalArgumentException.class, () -> WorkflowDefinitionParameters.build("w", "", List.of(node, node), List.of(1L, 1L), options()));
    }

    private static WorkflowDefinitionParameters.Execution options() {
        return new WorkflowDefinitionParameters.Execution("HIGH", "worker", -1L, 3, 4, 5, "SERIAL_WAIT");
    }

    @Test void nodePriorityOverridesDefaultWithoutChangingOtherNodes() {
        var nodes = List.of(new WorkflowDefinitionParameters.Node("a", "a", "SUB_WORKFLOW", Map.of(), List.of(), "LOWEST"),
                new WorkflowDefinitionParameters.Node("b", "b", "SUB_WORKFLOW", Map.of(), List.of()));
        var result = WorkflowDefinitionParameters.build("w", "", nodes, List.of(1L, 2L), options());
        var tasks = JSONUtil.parseArray(result.getTaskDefinitionJson());
        assertEquals("LOWEST", tasks.getJSONObject(0).getStr("taskPriority"));
        assertEquals("HIGH", tasks.getJSONObject(1).getStr("taskPriority"));
        assertThrows(IllegalArgumentException.class, () -> WorkflowDefinitionParameters.build("w", "",
                List.of(new WorkflowDefinitionParameters.Node("a", "a", "SUB_WORKFLOW", Map.of(), List.of(), "INVALID")), List.of(1L), options()));
    }
}
