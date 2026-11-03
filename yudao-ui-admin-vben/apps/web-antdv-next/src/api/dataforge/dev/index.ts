import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace DataforgeDevApi {
  export type Id = number | string;
  export type WorkloadMode = 'BATCH' | 'STREAMING';
  export type TaskType =
    | 'FLINK_JAR'
    | 'FLINK_SQL'
    | 'PROCEDURE'
    | 'PYFLINK'
    | 'PYSPARK'
    | 'SHELL'
    | 'SPARK_JAR'
    | 'SPARK_SQL'
    | 'SQL';

  /** 原生引擎配置；执行能力与部署档案以服务端校验为准。 */
  export interface ProgramConfig {
    schemaVersion?: number;
    workloadMode?: 'BATCH' | 'STREAMING';
    runtimeProfileCode?: string;
    engineVersion?: string;
    entryPoint?: string;
    artifactFileId?: Id | null;
    artifactSha256?: string;
    arguments?: string[];
    dependencyFileIds?: Id[] | null;
    pythonEnvironment?: string;
    parallelism?: number;
    jobManagerMemoryMb?: number;
    taskManagerMemoryMb?: number;
    taskManagerSlots?: number;
    taskManagerCount?: number;
    driverMemoryMb?: number;
    executorMemoryMb?: number;
    driverCores?: number;
    executorCores?: number;
    numExecutors?: number;
    queue?: string;
    checkpointIntervalMs?: number;
  }
  export interface RuntimeResource {
    fileId: Id;
    name: string;
    sha256: string;
  }
  export interface RuntimeArtifact extends RuntimeResource {
    programTypes: ('JAR' | 'PYTHON' | 'SQL')[];
    entryPoint?: string;
  }
  export interface RuntimeProfile {
    code: string;
    name: string;
    engine: 'FLINK' | 'SPARK';
    engineVersion: string;
    deployMode: string;
    queue?: string;
    allowedTaskTypes: TaskType[];
    artifacts: RuntimeArtifact[];
    dependencyResources: RuntimeResource[];
  }
  /** 开发目录 */
  export interface Dir {
    id?: DataforgeDevApi.Id;
    parentId?: DataforgeDevApi.Id;
    name?: string;
    sort?: number;
    children?: Dir[];
    status?: number;
    description?: string;
    remark?: string;
  }

  export interface LocalParam {
    prop: string;
    type:
      | 'BOOLEAN'
      | 'DATE'
      | 'DOUBLE'
      | 'INTEGER'
      | 'LONG'
      | 'TIME'
      | 'TIMESTAMP'
      | 'VARCHAR';
    value: string;
  }
  export interface TableReference {
    databaseId: Id;
    schemaName?: null | string;
    tableName: string;
  }
  export interface TableLineage {
    downstream: TableReference[];
    upstream: TableReference[];
  }
  export interface ExecutionConfig {
    workloadMode?: WorkloadMode;
    priority?: 'HIGH' | 'HIGHEST' | 'LOW' | 'LOWEST' | 'MEDIUM';
    workerGroup?: string;
    retryTimes?: number;
    retryIntervalMinutes?: number;
    delayMinutes?: number;
    sqlType?: 'AUTO' | 'NON_QUERY' | 'PROCEDURE' | 'QUERY';
    statementSeparator?: string;
    localParams?: LocalParam[];
    tableLineage?: null | TableLineage;
  }
  export interface Statistics {
    runningTasks: number;
    todayFailed: number;
    todayExecutions: number;
    todaySuccessRate: number;
    refreshedAt?: number;
  }
  export interface RecordStatistics {
    total: number;
    running: number;
    success: number;
    failed: number;
    refreshedAt?: number;
  }

  /** 开发任务 */
  export interface Task {
    /** 页面归属先取执行配置，再取程序模式；保存时不作为独立字段提交。 */
    workloadMode?: WorkloadMode;
    revision?: number;
    expectedRevision?: number;
    currentVersionId?: Id | null;
    dependencyTaskIds?: Id[];
    qualityTaskId?: Id;
    executionUserId?: Id;
    id?: DataforgeDevApi.Id;
    dirId?: DataforgeDevApi.Id;
    name?: string;
    taskType?: TaskType;
    programConfig?: null | ProgramConfig;
    engine?: 'FLINK' | 'SPARK';
    programType?: 'JAR' | 'PYTHON' | 'SQL';
    executionCapability?: 'LEGACY_SQL' | 'NATIVE' | 'NOT_READY' | 'STANDARD';
    executionCapabilityReason?: string;
    databaseId?: DataforgeDevApi.Id | null;
    datasourceName?: string;
    databaseName?: string;
    dbType?: string;
    content?: string;
    timeoutSeconds?: number;
    maxRows?: number;
    dsDatasourceId?: number;
    dsDatasourceType?: string;
    dsWorkflowCode?: DataforgeDevApi.Id;
    description?: string;
    status?: number;
    scheduleStatus?: number;
    cronExpression?: null | string;
    nextRunTime?: number;
    latestVersion?: number;
    versionRemark?: string;
    creator?: string;
    createTime?: number;
    updateTime?: number;
    ownerId?: Id;
    ownerName?: string;
    contactNumber?: string;
    scheduler?: 'DOLPHIN_SCHEDULER' | 'XXL_JOB';
    scheduleSyncStatus?:
      | 'ERROR'
      | 'NOT_CONFIGURED'
      | 'PENDING'
      | 'SYNCED'
      | null;
    scheduleSyncReason?: null | string;
    legacyCronExpression?: null | string;
    executionConfig?: ExecutionConfig;
    latestRecord?: Record;
  }

  /** 任务版本 */
  export interface TaskVersion {
    id: DataforgeDevApi.Id;
    taskId: DataforgeDevApi.Id;
    version: number;
    content?: string;
    remark?: string;
    creator?: string;
    createTime?: number;
  }

  /** 表字段元数据 */
  export interface TableColumn {
    name: string;
    comment?: string;
    dataType?: string;
  }

  export interface Dependency {
    taskId: Id;
    taskName: string;
    requiredVersionId: Id;
    businessDate?: string;
    recordId?: Id;
    actualVersionId?: Id;
    status?: string;
    qualityStatus?: string;
    satisfied: boolean;
    reason: string;
  }

  /** 任务执行实例 */
  export interface Record {
    taskType?: TaskType;
    nativeProgram?: boolean;
    dependencies?: Dependency[];
    waitReason?: string;
    businessDate?: string;
    versionId?: Id;
    executionUserId?: Id;
    qualityStatus?: string;
    qualityInstanceId?: Id;
    resolvedBy?: Id;
    resolvedAt?: number;
    resolutionReason?: string;
    lastError?: string;
    id: DataforgeDevApi.Id;
    taskId?: DataforgeDevApi.Id;
    taskName?: string;
    databaseId?: DataforgeDevApi.Id;
    datasourceName?: string;
    triggerType?: string;
    status?: string;
    startTime?: number;
    endTime?: number;
    durationMs?: number;
    statementTotal?: number;
    statementSuccess?: number;
    affectedRows?: number;
    dsInstanceId?: number;
    log?: string;
    creator?: string;
  }

  export interface TaskLogPage {
    message: string;
    readLines: number;
    nextSkipLineNum: number;
    mayHaveMore: boolean;
    truncated: boolean;
    available: boolean;
    source: 'DOLPHINSCHEDULER';
    statusMessage?: null | string;
  }
}

/** 任务类型选项 */
export const DEV_TASK_TYPE_OPTIONS: {
  label: string;
  value: DataforgeDevApi.TaskType;
}[] = [
  { label: 'SQL 任务', value: 'SQL' },
  { label: '存储过程', value: 'PROCEDURE' },
  { label: 'Shell 任务', value: 'SHELL' },
  { label: 'SparkSQL 任务', value: 'SPARK_SQL' },
  { label: 'FlinkSQL 任务', value: 'FLINK_SQL' },
  { label: 'Spark JAR 任务', value: 'SPARK_JAR' },
  { label: 'Flink JAR 任务', value: 'FLINK_JAR' },
  { label: 'PySpark 任务', value: 'PYSPARK' },
  { label: 'PyFlink 任务', value: 'PYFLINK' },
];

/** 集群任务包含原有 SQL 通道与原生程序。 */
export function isClusterTaskType(taskType?: string) {
  return getDevTaskEngine(taskType) !== undefined;
}

export function getDevTaskEngine(
  taskType?: string,
): 'FLINK' | 'SPARK' | undefined {
  if (['FLINK_JAR', 'FLINK_SQL', 'PYFLINK'].includes(taskType ?? ''))
    return 'FLINK';
  if (['PYSPARK', 'SPARK_JAR', 'SPARK_SQL'].includes(taskType ?? ''))
    return 'SPARK';
  return undefined;
}

export function getDevProgramType(taskType?: string): 'JAR' | 'PYTHON' | 'SQL' {
  if (['FLINK_JAR', 'SPARK_JAR'].includes(taskType ?? '')) return 'JAR';
  if (['PYFLINK', 'PYSPARK'].includes(taskType ?? '')) return 'PYTHON';
  return 'SQL';
}

export function isNativeProgramTask(
  task: Pick<DataforgeDevApi.Task, 'programConfig' | 'taskType'>,
) {
  return (
    isClusterTaskType(task.taskType) &&
    (getDevProgramType(task.taskType) !== 'SQL' ||
      (task.programConfig !== null && task.programConfig !== undefined))
  );
}

/** 服务端能力具有最终决定权；缺少能力字段时保守保留运行配置门禁。 */
export function isNativeProgramNotReady(
  task: Pick<
    DataforgeDevApi.Task,
    'executionCapability' | 'programConfig' | 'taskType'
  >,
) {
  return (
    task.executionCapability === 'NOT_READY' ||
    (task.executionCapability === undefined && isNativeProgramTask(task))
  );
}

/** 运行实例 / ETL 执行记录的成果质量状态文案（与后端 quality_status 取值一致） */
export const QUALITY_STATUS_LABELS: Record<string, string> = {
  FAILED: '质量不通过',
  NOT_CONFIGURED: '未配置质量验收',
  PASSED: '质量通过',
  PENDING: '等待质量验收',
  UNKNOWN: '质量结果待核查',
};

// ==================== 目录 ====================

export function getDevDirList() {
  return requestClient.get<DataforgeDevApi.Dir[]>('/dataforge/dev-dir/list');
}

export function createDevDir(data: DataforgeDevApi.Dir) {
  return requestClient.post('/dataforge/dev-dir/create', data);
}

export function updateDevDir(data: DataforgeDevApi.Dir) {
  return requestClient.put('/dataforge/dev-dir/update', data);
}

export function deleteDevDir(id: DataforgeDevApi.Id) {
  return requestClient.delete(`/dataforge/dev-dir/delete?id=${id}`);
}

// ==================== 任务 ====================

export function getDevRuntimeProfiles() {
  return requestClient.get<DataforgeDevApi.RuntimeProfile[]>(
    '/dataforge/dev-task/runtime-profiles',
  );
}

export function createDevTask(data: DataforgeDevApi.Task) {
  return requestClient.post<DataforgeDevApi.Id>(
    '/dataforge/dev-task/create',
    devTaskSavePayload(data),
  );
}

export function updateDevTask(data: DataforgeDevApi.Task) {
  return requestClient.put(
    '/dataforge/dev-task/update',
    devTaskSavePayload(data),
  );
}

/** 执行身份和 DS 引用由平台管理，编辑响应不能作为身份授权输入。 */
function devTaskSavePayload(data: DataforgeDevApi.Task) {
  const payload = { ...data };
  delete payload.workloadMode;
  delete payload.executionUserId;
  delete payload.dsDatasourceId;
  delete payload.dsDatasourceType;
  delete payload.dsWorkflowCode;
  return payload;
}

export function deleteDevTask(id: DataforgeDevApi.Id) {
  return requestClient.delete(`/dataforge/dev-task/delete?id=${id}`);
}

export function getDevTask(id: DataforgeDevApi.Id) {
  return requestClient.get<DataforgeDevApi.Task>(
    `/dataforge/dev-task/get?id=${id}`,
  );
}

export function getDevTaskPage(
  params: PageParam & {
    dbType?: string;
    dirId?: DataforgeDevApi.Id;
    executionStartTime?: string[];
    executionStatus?: string;
    name?: string;
    status?: number;
    workloadMode?: DataforgeDevApi.WorkloadMode;
  },
) {
  return requestClient.get<PageResult<DataforgeDevApi.Task>>(
    '/dataforge/dev-task/page',
    { params },
  );
}

export function getDevTaskListByDir(
  dirId?: DataforgeDevApi.Id,
  workloadMode?: DataforgeDevApi.WorkloadMode,
) {
  return requestClient.get<DataforgeDevApi.Task[]>(
    '/dataforge/dev-task/list-by-dir',
    { params: { dirId, ...(workloadMode ? { workloadMode } : {}) } },
  );
}

export function updateDevTaskStatus(id: DataforgeDevApi.Id, status: number) {
  return requestClient.put(
    `/dataforge/dev-task/update-status?id=${id}&status=${status}`,
  );
}

export function updateDevTaskSchedule(data: {
  cronExpression?: string;
  id: DataforgeDevApi.Id;
  scheduleStatus: number;
}) {
  return requestClient.put('/dataforge/dev-task/update-schedule', data);
}

export function runDevTask(data: {
  businessDate: string;
  id: DataforgeDevApi.Id;
  requestId: string;
}) {
  return requestClient.post<DataforgeDevApi.Id>(
    '/dataforge/dev-task/run',
    data,
  );
}

// ==================== 元数据 ====================

export function getDevTableColumns(
  databaseId: DataforgeDevApi.Id,
  tableName: string,
) {
  return requestClient.get<DataforgeDevApi.TableColumn[]>(
    '/dataforge/dev-task/column-list',
    { params: { databaseId, tableName } },
  );
}

// ==================== 版本 ====================

export function getDevTaskVersionList(taskId: DataforgeDevApi.Id) {
  return requestClient.get<DataforgeDevApi.TaskVersion[]>(
    `/dataforge/dev-task/version/list?taskId=${taskId}`,
  );
}

export function rollbackDevTaskVersion(
  versionId: DataforgeDevApi.Id,
  expectedRevision: number,
) {
  return requestClient.put(
    `/dataforge/dev-task/version/rollback?versionId=${versionId}&expectedRevision=${expectedRevision}`,
  );
}

// ==================== 实例 ====================

export function getDevRecordPage(
  params: PageParam & {
    startTime?: [string, string];
    status?: string;
    taskId?: DataforgeDevApi.Id;
    taskName?: string;
    triggerType?: string;
  },
) {
  return requestClient.get<PageResult<DataforgeDevApi.Record>>(
    '/dataforge/dev-record/page',
    { params },
  );
}

export function getDevRecord(id: DataforgeDevApi.Id) {
  return requestClient.get<DataforgeDevApi.Record>(
    `/dataforge/dev-record/get?id=${id}`,
  );
}

export function getDevTaskStatistics(
  workloadMode?: DataforgeDevApi.WorkloadMode,
) {
  return requestClient.get<DataforgeDevApi.Statistics>(
    '/dataforge/dev-task/statistics',
    { params: { workloadMode } },
  );
}
export function previewDevCron(cronExpression: string) {
  return requestClient.get<number[]>('/dataforge/dev-task/cron-preview', {
    params: { cronExpression },
    silentError: true,
  });
}
export function getDevRecordStatistics(taskId: DataforgeDevApi.Id) {
  return requestClient.get<DataforgeDevApi.RecordStatistics>(
    '/dataforge/dev-record/statistics',
    { params: { taskId } },
  );
}
export function downloadDevRecordLog(id: DataforgeDevApi.Id) {
  return requestClient.download('/dataforge/dev-record/task-log-download', {
    params: { id },
  });
}

export function getDevRecordTaskLog(params: {
  id: DataforgeDevApi.Id;
  limit: number;
  skipLineNum: number;
}) {
  return requestClient.get<DataforgeDevApi.TaskLogPage>(
    '/dataforge/dev-record/task-log',
    { params },
  );
}

export function stopDevRecord(id: DataforgeDevApi.Id) {
  return requestClient.put(`/dataforge/dev-record/stop?id=${id}`);
}

export function deleteDevRecord(id: DataforgeDevApi.Id) {
  return requestClient.delete(`/dataforge/dev-record/delete?id=${id}`);
}

export function resolveUnknownDevRecord(data: {
  confirmedTerminated: true;
  expectedStatus: 'UNKNOWN';
  id: DataforgeDevApi.Id;
  reason: string;
  status: 'FAILED' | 'STOPPED' | 'SUCCESS';
}) {
  return requestClient.post('/dataforge/dev-record/resolve-unknown', data);
}

export function resolveDevRecordQuality(data: {
  confirmedTerminated: true;
  expectedStatus: 'UNKNOWN';
  id: DataforgeDevApi.Id;
  reason: string;
}) {
  return requestClient.post('/dataforge/dev-record/resolve-quality', data);
}
