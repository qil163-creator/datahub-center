package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;
import cn.iocoder.yudao.framework.dolphinscheduler.request.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 执行器接口，对应 OpenAPI tag：executor operation。
 */
public class ExecutorApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code ExecutorApi}。
     */
    public ExecutorApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * startTaskInstance
     * <p>运行任务实例</p>
     * <p>{@code POST /projects/{projectCode}/executors/task-instance/{code}/start}</p>
     * @param projectCode 必填，项目编码
     * @param code 必填，任务编码
     * @param version 必填，版本
     * @param warningGroupId 可选，告警组编号
     * @param workerGroup 可选，工作组
     * @param tenantCode 可选，操作系统租户编码
     * @param environmentCode 可选，环境编码
     * @param startParams 可选，启动参数
     * @param dryRun 可选，试运行
     * @param failureStrategy 必填，失败策略，取值：END / CONTINUE
     * @param execType 可选，命令类型，取值：START_PROCESS / START_CURRENT_TASK_PROCESS / RECOVER_TOLERANCE_FAULT_PROCESS / RECOVER_SUSPENDED_PROCESS / START_FAILURE_TASK_PROCESS / COMPLEMENT_DATA / SCHEDULER / REPEAT_RUNNING / PAUSE / STOP / RECOVER_SERIAL_WAIT / EXECUTE_TASK / DYNAMIC_GENERATION
     * @param warningType 必填，告警类型（发送策略），取值：NONE / SUCCESS / FAILURE / ALL
     * @param timeout 可选，超时时间
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject startStreamTaskInstance(Long projectCode, Long code, StartStreamTaskInstanceRequest request) {
        Map<String, Object> params = request == null ? new LinkedHashMap<>() : request.toMap();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        pathVars.put("code", code);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/executors/task-instance/{code}/start",
                pathVars);
        return http.postForm(path, params);
    }

    /**
     * startWorkflowInstance
     * <p>运行流程实例</p>
     * <p>{@code POST /projects/{projectCode}/executors/start-workflow-instance}</p>
     * @param projectCode 必填，项目编码
     * @param workflowDefinitionCode 必填，工作流定义编码
     * @param scheduleTime 必填，调度时间，空字符串表示当天
     * @param failureStrategy 必填，失败策略，取值：END / CONTINUE
     * @param startNodeList 可选，起始节点列表（节点名称）
     * @param taskDependType 可选，任务依赖类型，取值：TASK_ONLY / TASK_PRE / TASK_POST
     * @param execType 可选，命令类型，取值：START_PROCESS / START_CURRENT_TASK_PROCESS / RECOVER_TOLERANCE_FAULT_PROCESS / RECOVER_SUSPENDED_PROCESS / START_FAILURE_TASK_PROCESS / COMPLEMENT_DATA / SCHEDULER / REPEAT_RUNNING / PAUSE / STOP / RECOVER_SERIAL_WAIT / EXECUTE_TASK / DYNAMIC_GENERATION
     * @param warningType 必填，告警类型（发送策略），取值：NONE / SUCCESS / FAILURE / ALL
     * @param warningGroupId 可选，告警组编号
     * @param runMode 可选，运行模式，取值：RUN_MODE_SERIAL / RUN_MODE_PARALLEL
     * @param workflowInstancePriority 必填，工作流实例优先级，取值：HIGHEST / HIGH / MEDIUM / LOW / LOWEST
     * @param workerGroup 可选，工作组
     * @param tenantCode 可选，操作系统租户编码
     * @param environmentCode 可选，环境编码
     * @param startParams 可选，启动参数
     * @param expectedParallelismNumber 可选，用于设置补数任务线程的自定义并行度
     * @param dryRun 可选，试运行
     * @param complementDependentMode 可选，补数依赖模式，取值：OFF_MODE / ALL_DEPENDENT
     * @param allLevelDependent 可选，是否依赖所有层级
     * @param executionOrder 可选，执行顺序，取值：DESC_ORDER / ASC_ORDER
     * @param timeout 可选，超时时间
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject triggerWorkflowDefinition(Long projectCode, TriggerWorkflowDefinitionRequest request) {
        Map<String, Object> params = request == null ? new LinkedHashMap<>() : request.toMap();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/executors/start-workflow-instance",
                pathVars);
        return http.postForm(path, params);
    }

    /**
     * execute
     * <p>对流程实例执行操作</p>
     * <p>{@code POST /projects/{projectCode}/executors/execute}</p>
     * @param projectCode 必填，项目编码
     * @param workflowInstanceId 必填，流程实例编号
     * @param executeType 必填，执行类型，取值：NONE / REPEAT_RUNNING / RECOVER_SUSPENDED_PROCESS / START_FAILURE_TASK_PROCESS / STOP / PAUSE / EXECUTE_TASK
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject controlWorkflowInstance(Long projectCode, Integer workflowInstanceId, String executeType) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (workflowInstanceId != null) {
            params.put("workflowInstanceId", workflowInstanceId);
        }
        if (executeType != null) {
            params.put("executeType", executeType);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/executors/execute", pathVars);
        return http.postForm(path, params);
    }

    /**
     * execute-task
     * <p>对流程实例执行操作</p>
     * <p>{@code POST /projects/{projectCode}/executors/execute-task}</p>
     * @param projectCode 必填，项目编码
     * @param workflowInstanceId 必填，流程实例编号
     * @param startNodeList 必填，起始节点列表（节点名称）
     * @param taskDependType 必填，任务依赖类型，取值：TASK_ONLY / TASK_PRE / TASK_POST
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject executeTask(Long projectCode, Integer workflowInstanceId, String startNodeList,
            String taskDependType) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (workflowInstanceId != null) {
            params.put("workflowInstanceId", workflowInstanceId);
        }
        if (startNodeList != null) {
            params.put("startNodeList", startNodeList);
        }
        if (taskDependType != null) {
            params.put("taskDependType", taskDependType);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/executors/execute-task", pathVars);
        return http.postForm(path, params);
    }

    /**
     * batchStartWorkflowInstance
     * <p>批量运行流程实例（若找不到某个 processDefinitionCode，则返回失败信息并将状态设为失败；成功的任务仍会正常运行，不会停止）</p>
     * <p>{@code POST /projects/{projectCode}/executors/batch-start-workflow-instance}</p>
     * @param projectCode 必填，项目编码
     * @param workflowDefinitionCodes 必填，工作流定义编码列表
     * @param scheduleTime 必填，调度时间，空字符串表示当天
     * @param failureStrategy 必填，失败策略，取值：END / CONTINUE
     * @param startNodeList 可选，起始节点列表（节点名称）
     * @param taskDependType 可选，任务依赖类型，取值：TASK_ONLY / TASK_PRE / TASK_POST
     * @param execType 可选，命令类型，取值：START_PROCESS / START_CURRENT_TASK_PROCESS / RECOVER_TOLERANCE_FAULT_PROCESS / RECOVER_SUSPENDED_PROCESS / START_FAILURE_TASK_PROCESS / COMPLEMENT_DATA / SCHEDULER / REPEAT_RUNNING / PAUSE / STOP / RECOVER_SERIAL_WAIT / EXECUTE_TASK / DYNAMIC_GENERATION
     * @param warningType 必填，告警类型（发送策略），取值：NONE / SUCCESS / FAILURE / ALL
     * @param warningGroupId 必填，告警组编号
     * @param runMode 可选，运行模式，取值：RUN_MODE_SERIAL / RUN_MODE_PARALLEL
     * @param workflowInstancePriority 必填，工作流实例优先级，取值：HIGHEST / HIGH / MEDIUM / LOW / LOWEST
     * @param workerGroup 可选，工作组
     * @param tenantCode 可选，操作系统租户编码
     * @param environmentCode 可选，环境编码
     * @param startParams 可选，启动参数
     * @param expectedParallelismNumber 可选，用于设置补数任务线程的自定义并行度
     * @param dryRun 可选，试运行
     * @param complementDependentMode 可选，补数依赖模式，取值：OFF_MODE / ALL_DEPENDENT
     * @param allLevelDependent 可选，是否依赖所有层级
     * @param executionOrder 可选，执行顺序，取值：DESC_ORDER / ASC_ORDER
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject batchTriggerWorkflowDefinitions(Long projectCode,
            BatchTriggerWorkflowDefinitionsRequest request) {
        Map<String, Object> params = request == null ? new LinkedHashMap<>() : request.toMap();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/executors/batch-start-workflow-instance",
                pathVars);
        return http.postForm(path, params);
    }

    /**
     * batchExecute
     * <p>批量修改多个流程实例的状态（若找不到某个实例，则返回包含具体编号的错误；仅在部分实例的状态变更不符合预期时显示详细错误信息）</p>
     * <p>{@code POST /projects/{projectCode}/executors/batch-execute}</p>
     * @param projectCode 必填，项目编码
     * @param workflowInstanceIds 必填，工作流实例编号，多个编号以 "," 分隔
     * @param executeType 必填，执行类型，取值：NONE / REPEAT_RUNNING / RECOVER_SUSPENDED_PROCESS / START_FAILURE_TASK_PROCESS / STOP / PAUSE / EXECUTE_TASK
     * @return DolphinScheduler 统一 {@code Result}
     * @deprecated 项目编码是 64 位整数，使用 {@link #batchControlWorkflowInstance(Long, String, String)}
     */
    @Deprecated
    public JSONObject batchControlWorkflowInstance(Integer projectCode, String workflowInstanceIds,
            String executeType) {
        return batchControlWorkflowInstance(projectCode == null ? null : projectCode.longValue(),
                workflowInstanceIds, executeType);
    }

    /**
     * 批量修改流程实例状态；项目编码属于路由占位符，保留完整 64 位编码。
     * @param projectCode 必填，64 位项目编码
     * @param workflowInstanceIds 必填，逗号分隔的流程实例编号
     * @param executeType 必填，执行类型
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject batchControlWorkflowInstance(Long projectCode, String workflowInstanceIds, String executeType) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (workflowInstanceIds != null) {
            params.put("workflowInstanceIds", workflowInstanceIds);
        }
        if (executeType != null) {
            params.put("executeType", executeType);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("projectCode", projectCode);
        String path = DolphinSchedulerHttp.fillPath("/projects/{projectCode}/executors/batch-execute", pathVars);
        return http.postForm(path, params);
    }

}
