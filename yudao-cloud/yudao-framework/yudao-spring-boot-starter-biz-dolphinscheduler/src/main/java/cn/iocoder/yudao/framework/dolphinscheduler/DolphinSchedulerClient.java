package cn.iocoder.yudao.framework.dolphinscheduler;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.api.*;
import cn.iocoder.yudao.framework.dolphinscheduler.config.DolphinSchedulerProperties;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;

/**
 * DolphinScheduler REST 客户端，按 OpenAPI 3.4.2 分组封装。
 */
public class DolphinSchedulerClient {

    private final DolphinSchedulerHttp http;

    private final AccessTokenApi accessTokenApi;

    private final AlertGroupApi alertGroupApi;

    private final AlertPluginInstanceApi alertPluginInstanceApi;

    private final AnalysisApi analysisApi;

    private final AuditLogApi auditLogApi;

    private final CloudApi cloudApi;

    private final ClusterApi clusterApi;

    private final DataSourceApi dataSourceApi;

    private final DynamicTaskTypeApi dynamicTaskTypeApi;

    private final EnvironmentApi environmentApi;

    private final ExecutorApi executorApi;

    private final FavouriteApi favouriteApi;

    private final K8sNamespaceApi k8sNamespaceApi;

    private final LineageApi lineageApi;

    private final LogApi logApi;

    private final LoginApi loginApi;

    private final MonitorApi monitorApi;

    private final ProjectApi projectApi;

    private final ProjectParameterApi projectParameterApi;

    private final ProjectPreferenceApi projectPreferenceApi;

    private final ProjectWorkerGroupApi projectWorkerGroupApi;

    private final QueueApi queueApi;

    private final ResourceApi resourceApi;

    private final ScheduleApi scheduleApi;

    private final TaskDefinitionApi taskDefinitionApi;

    private final TaskGroupApi taskGroupApi;

    private final TaskInstanceApi taskInstanceApi;

    private final TenantApi tenantApi;

    private final UiPluginApi uiPluginApi;

    private final UserApi userApi;

    private final WorkerGroupApi workerGroupApi;

    private final WorkflowDefinitionApi workflowDefinitionApi;

    private final WorkflowInstanceApi workflowInstanceApi;

    /**
     * 初始化 {@code DolphinSchedulerClient}。
     */
    public DolphinSchedulerClient(DolphinSchedulerProperties properties) {
        this.http = new DolphinSchedulerHttp(properties);
        this.accessTokenApi = new AccessTokenApi(http);
        this.alertGroupApi = new AlertGroupApi(http);
        this.alertPluginInstanceApi = new AlertPluginInstanceApi(http);
        this.analysisApi = new AnalysisApi(http);
        this.auditLogApi = new AuditLogApi(http);
        this.cloudApi = new CloudApi(http);
        this.clusterApi = new ClusterApi(http);
        this.dataSourceApi = new DataSourceApi(http);
        this.dynamicTaskTypeApi = new DynamicTaskTypeApi(http);
        this.environmentApi = new EnvironmentApi(http);
        this.executorApi = new ExecutorApi(http);
        this.favouriteApi = new FavouriteApi(http);
        this.k8sNamespaceApi = new K8sNamespaceApi(http);
        this.lineageApi = new LineageApi(http);
        this.logApi = new LogApi(http);
        this.loginApi = new LoginApi(http);
        this.monitorApi = new MonitorApi(http);
        this.projectApi = new ProjectApi(http);
        this.projectParameterApi = new ProjectParameterApi(http);
        this.projectPreferenceApi = new ProjectPreferenceApi(http);
        this.projectWorkerGroupApi = new ProjectWorkerGroupApi(http);
        this.queueApi = new QueueApi(http);
        this.resourceApi = new ResourceApi(http);
        this.scheduleApi = new ScheduleApi(http);
        this.taskDefinitionApi = new TaskDefinitionApi(http);
        this.taskGroupApi = new TaskGroupApi(http);
        this.taskInstanceApi = new TaskInstanceApi(http);
        this.tenantApi = new TenantApi(http);
        this.uiPluginApi = new UiPluginApi(http);
        this.userApi = new UserApi(http);
        this.workerGroupApi = new WorkerGroupApi(http);
        this.workflowDefinitionApi = new WorkflowDefinitionApi(http);
        this.workflowInstanceApi = new WorkflowInstanceApi(http);
    }

    /**
     * 返回HTTP。
     */
    public DolphinSchedulerHttp http() {
        return http;
    }

    /**
     * 访问令牌。
     */
    public AccessTokenApi accessToken() {
        return accessTokenApi;
    }

    /**
     * 告警组。
     */
    public AlertGroupApi alertGroup() {
        return alertGroupApi;
    }

    /**
     * 告警插件实例。
     */
    public AlertPluginInstanceApi alertPluginInstance() {
        return alertPluginInstanceApi;
    }

    /**
     * 统计分析。
     */
    public AnalysisApi analysis() {
        return analysisApi;
    }

    /**
     * 审计日志。
     */
    public AuditLogApi auditLog() {
        return auditLogApi;
    }

    /**
     * 云资源。
     */
    public CloudApi cloud() {
        return cloudApi;
    }

    /**
     * 集群。
     */
    public ClusterApi cluster() {
        return clusterApi;
    }

    /**
     * 数据源。
     */
    public DataSourceApi dataSource() {
        return dataSourceApi;
    }

    /**
     * 动态任务类型。
     */
    public DynamicTaskTypeApi dynamicTaskType() {
        return dynamicTaskTypeApi;
    }

    /**
     * 环境。
     */
    public EnvironmentApi environment() {
        return environmentApi;
    }

    /**
     * 执行器。
     */
    public ExecutorApi executor() {
        return executorApi;
    }

    /**
     * 收藏任务类型。
     */
    public FavouriteApi favourite() {
        return favouriteApi;
    }

    /**
     * K8s 命名空间。
     */
    public K8sNamespaceApi k8sNamespace() {
        return k8sNamespaceApi;
    }

    /**
     * 工作流血缘。
     */
    public LineageApi lineage() {
        return lineageApi;
    }

    /**
     * 任务日志。
     */
    public LogApi log() {
        return logApi;
    }

    /**
     * 登录。
     */
    public LoginApi login() {
        return loginApi;
    }

    /**
     * 使用配置项中的用户名密码登录，并缓存 sessionId。
     */
    public JSONObject loginWithConfig() {
        return loginApi.login();
    }

    /**
     * 监控。
     */
    public MonitorApi monitor() {
        return monitorApi;
    }

    /**
     * 项目。
     */
    public ProjectApi project() {
        return projectApi;
    }

    /**
     * 项目参数。
     */
    public ProjectParameterApi projectParameter() {
        return projectParameterApi;
    }

    /**
     * 项目偏好。
     */
    public ProjectPreferenceApi projectPreference() {
        return projectPreferenceApi;
    }

    /**
     * 项目 Worker 分组。
     */
    public ProjectWorkerGroupApi projectWorkerGroup() {
        return projectWorkerGroupApi;
    }

    /**
     * 队列。
     */
    public QueueApi queue() {
        return queueApi;
    }

    /**
     * 资源中心。
     */
    public ResourceApi resource() {
        return resourceApi;
    }

    /**
     * 调度。
     */
    public ScheduleApi schedule() {
        return scheduleApi;
    }

    /**
     * 任务定义。
     */
    public TaskDefinitionApi taskDefinition() {
        return taskDefinitionApi;
    }

    /**
     * 任务组。
     */
    public TaskGroupApi taskGroup() {
        return taskGroupApi;
    }

    /**
     * 任务实例。
     */
    public TaskInstanceApi taskInstance() {
        return taskInstanceApi;
    }

    /**
     * 租户。
     */
    public TenantApi tenant() {
        return tenantApi;
    }

    /**
     * UI 插件。
     */
    public UiPluginApi uiPlugin() {
        return uiPluginApi;
    }

    /**
     * 用户。
     */
    public UserApi user() {
        return userApi;
    }

    /**
     * Worker 分组。
     */
    public WorkerGroupApi workerGroup() {
        return workerGroupApi;
    }

    /**
     * 工作流定义。
     */
    public WorkflowDefinitionApi workflowDefinition() {
        return workflowDefinitionApi;
    }

    /**
     * 工作流实例。
     */
    public WorkflowInstanceApi workflowInstance() {
        return workflowInstanceApi;
    }

}
