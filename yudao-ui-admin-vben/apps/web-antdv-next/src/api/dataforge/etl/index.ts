import type { PageParam, PageResult } from '@vben/request';

import type { DataforgeDataApiApi } from '../dataapi';
import type {
  ResolveCommand,
  RunCommand,
  RunReceipt,
  StopCommand,
} from '../execution/command';

import type { QualityWorkOrderApi } from '#/api/governance/quality/workorder';

import { requestClient } from '#/api/request';

export namespace DataforgeEtlApi {
  export type Id = number | string;
  /** REST API 输入节点直接保存请求定义，不引用独立接口资源。 */
  export interface RestApiNodeConfig {
    apiConfig: DataforgeDataApiApi.DataApi;
    fetchSize: number;
  }
  export type QualityReport = QualityWorkOrderApi.InlineQualityReport;
  export interface TargetBatchReplayRequest {
    workOrderId: Id;
    submissionId: Id;
    requestId: string;
    snapshotMode: 'LATEST_PUBLISHED' | 'ORIGINAL';
    reason: string;
    confirmedTargetReplacement: true;
  }
  /** 工具箱组件 */
  export interface Tool {
    id: DataforgeEtlApi.Id;
    parentId?: DataforgeEtlApi.Id;
    name?: string;
    icon?: string;
    type?: number; // -1 目录 1 输入 2 转换 3 输出 4 脚本
    code?: string;
    builtIn?: number;
    enabled?: number;
    orderNo?: number;
    description?: string;
    createTime?: number;
    children?: Tool[];
  }

  /** 流程参数定义 */
  export interface ParamItem {
    name?: string;
    type?: 'BOOLEAN' | 'DECIMAL' | 'INTEGER' | 'STRING';
    required?: boolean;
    defaultValue?: string;
    min?: string;
    max?: string;
    minLength?: number;
    maxLength?: number;
    description?: string;
  }
  export interface RunParameterDefinition {
    parameters: ParamItem[];
    definitionRevision: number;
    published: boolean;
  }
  export interface EtlRunCommand extends RunCommand {
    expectedDefinitionRevision?: number;
    parameters?: Record<string, string>;
  }

  /** 生产流程 */
  export interface Pipeline {
    environmentId?: Id;
    revision?: number;
    publishedRevision?: number;
    publishedExecutionUserId?: DataforgeEtlApi.Id;
    qualityTaskId?: Id;
    id?: DataforgeEtlApi.Id;
    name?: string;
    taskType?: number; // 1-一次性 2-周期性
    cron?: string;
    nextRunTime?: number;
    status?: number; // 任务状态：0-未上线 1-已上线
    scheduleStatus?: number; // 调度状态：0-关闭 1-开启（仅周期任务）
    runStatus?: number; // 1-等待 2-运行中 3-成功 4-失败 5-停止
    startTime?: number;
    endTime?: number;
    releaseTime?: number;
    releaseUserName?: string;
    description?: string;
    orgId?: DataforgeEtlApi.Id;
    orgName?: string;
    createTime?: number;
    parameters?: ParamItem[];
    nodes?: Record<string, any>[];
    edges?: Record<string, any>[];
  }

  /** 执行记录 */
  export interface EtlRecord {
    protocolVersion?: number;
    rowVersion?: Id;
    stopRequested?: boolean;
    occupiesSlot?: boolean;
    reviewRequired?: boolean;
    executionStage?: string;
    effectState?: string;
    attemptCount?: number;
    scheduledTime?: number;
    nextAttemptAt?: number;
    nextReconcileAt?: number;
    updateTime?: number;
    qualityStatus?: string;
    qualityInstanceId?: Id;
    id?: DataforgeEtlApi.Id;
    pipelineId?: DataforgeEtlApi.Id;
    name?: string;
    runStatus?: number;
    executeType?: number; // 1-手动 2-自动调度
    startTime?: number;
    endTime?: number;
    log?: string;
    end?: boolean;
    createTime?: number;
    /** 运行观测快照，仅日志接口返回；历史运行为空 */
    runtimeMetrics?: null | RuntimeMetrics;
  }

  /** 运行观测：计数为采样时刻的近似值，等待时间为累计毫秒 */
  export interface RuntimeMetrics {
    elapsedMillis: number;
    finished: boolean;
    transferredRows: number | string;
    transferredBytes: number | string;
    bufferedBytes: number | string;
    nodes: RuntimeNode[];
    edges: RuntimeEdge[];
    series: RuntimePoint[];
  }

  export interface RuntimeNode {
    nodeNo: string;
    readCount: number | string;
    writeCount: number | string;
  }

  export interface RuntimeEdge {
    sourceNo: string;
    targetNo: string;
    queued: number;
    capacity: number;
    delivered: number | string;
    /** 上游因队列已满等待的累计毫秒，即背压 */
    blockedMillis: number | string;
    /** 下游因队列为空等待的累计毫秒 */
    starvedMillis: number | string;
  }

  export interface RuntimePoint {
    elapsedMillis: number;
    transferredRows: number | string;
    transferredBytes: number | string;
    bufferedBytes: number | string;
  }

  /** 节点执行记录 */
  export interface NodeRecord {
    designNodeNo?: string;
    callPath?: string;
    originalNodeNo?: string;
    id?: DataforgeEtlApi.Id;
    nodeNo?: string;
    nodeName?: string;
    toolCode?: string;
    runStatus?: number;
    startTime?: number;
    endTime?: number;
    log?: string;
    readCount?: number | string;
    writeCount?: number | string;
    portCounts?: null | Record<string, number | string>;
    fileOutputState?: null | string;
  }

  /** 可选事实；缺省或 null 表示未知，不根据类型补默认值。 */
  export interface FieldConstraints {
    nullable?: boolean | null;
    precision?: null | number;
    scale?: null | number;
    timezone?: null | string;
  }

  /** 字段契约 */
  export interface FieldMeta extends FieldConstraints {
    name?: string;
    dataType?: string;
    length?: null | number | string;
    sourceName?: null | string;
  }

  export interface SchemaResult {
    nodes?: Record<
      string,
      { inputFields?: FieldMeta[]; outputFields?: FieldMeta[] }
    >;
    errors?: string[];
    warnings?: string[];
  }

  export interface GraphRequest {
    environmentId?: Id;
    parameterDefinitions?: ParamItem[];
    parameters?: Record<string, string>;
    edges: Record<string, any>[];
    nodes: Record<string, any>[];
  }

  export interface ValidationIssue {
    nodeId?: string;
    field?: string;
    severity: 'ERROR' | 'WARNING';
    code: string;
    message: string;
  }

  export interface ValidationResult {
    valid: boolean;
    issues: ValidationIssue[];
  }

  export interface PreviewRequest extends GraphRequest {
    targetNodeId: string;
    limit: number;
    parameters?: Record<string, string>;
  }

  /** 目标节点某条输入边的独立有界样本，不能与输出按行号对应。 */
  export interface PreviewInputSample {
    sourceNodeNo: string;
    sourceNodeName: string;
    sourceOutputPort: string;
    inputPort: string;
    columns: FieldMeta[];
    rows: Record<string, unknown>[];
    /** 本次样本执行中流经此输入边的行数，不是全表行数。 */
    rowCount: Id;
    /** 此输入端口的展示配额截断，不等同于来源采样截断。 */
    truncated: boolean;
  }

  export interface PreviewResult {
    success: boolean;
    columns: FieldMeta[];
    rows: Record<string, unknown>[];
    inputRows: number;
    outputRows: number;
    issues: ValidationIssue[];
    truncated: boolean;
    /** 兼容旧服务端；缺省表示未提供输入样本，不表示输入为空。 */
    inputSamples?: null | PreviewInputSample[];
    comparisonMode?: 'INDEPENDENT_SAMPLES' | null;
    /** 实际流入目标节点的本次样本行数；区别于来源 inputRows。 */
    targetInputRows?: Id | null;
    /** 目标节点各输出端口的样本行数，如质量节点 pass/warn/reject、条件分流分支 */
    portCounts?: Record<string, number>;
    /** 与 rows 按下标对应的输出端口，只覆盖展示行 */
    rowPorts?: string[];
    previewId?: string;
    /** 与展示行对应，仅含规则元信息，不含规则原始消息。 */
    rowViolations?: {
      code?: null | string;
      field?: null | string;
      ruleCode?: null | string;
      severity: string;
    }[][];
    budget?: null | {
      maxBytes: number;
      maxResponseRows: number;
      maxRowBytes: number;
      maxRows: number;
      peakBufferedBytes: number;
      responseBytes: number;
      timeoutSeconds: number;
      usedBytes: number;
      usedRows: number;
    };
  }

  /** 行级检查执行批次，独立于写入后 SQL 验收实例。 */
  export interface QualityBatch {
    id: Id;
    recordId: Id;
    nodeNo: string;
    planHash?: string;
    inputCount: Id | null;
    passCount: Id | null;
    warnCount: Id | null;
    rejectCount: Id | null;
    violationCount: Id | null;
    registrationStatus: string;
    governanceReportId?: Id;
  }

  export interface QualityIssue {
    id: Id;
    batchId: Id;
    rowNumber: Id;
    qualityStatus: string;
    violationsJson: string;
    createTime?: number;
  }

  export interface QualityIssueDetail extends QualityIssue {
    rowJson: string;
    sourceVerified?: boolean;
  }

  export interface Version {
    id: Id;
    pipelineId: Id;
    revision: number;
    releaseGeneration?: Id | null;
    eventType: 'PUBLISH' | 'RESTORE';
    sourceVersionId?: Id | null;
    actorId: Id;
    reason?: string;
    contentHash: string;
    configJson?: string;
    createTime: number;
  }

  export interface VersionDiff {
    type:
      | 'EDGES_CHANGED'
      | 'ENVIRONMENT_CHANGED'
      | 'NODE_ADDED'
      | 'NODE_CHANGED'
      | 'NODE_REMOVED'
      | 'PARAMETERS_CHANGED'
      | 'QUALITY_TASK_CHANGED'
      | 'SCHEDULE_CHANGED';
    objectId?: string;
  }
}

/** ==================== 工具箱 ==================== */

export function getEtlToolList() {
  return requestClient.get<DataforgeEtlApi.Tool[]>('/dataforge/etl-tool/list');
}

/** ==================== 流程 ==================== */

export function getEtlPipelinePage(params: PageParam) {
  return requestClient.get<PageResult<DataforgeEtlApi.Pipeline>>(
    '/dataforge/etl-pipeline/page',
    { params },
  );
}

export function getEtlPipeline(id: DataforgeEtlApi.Id) {
  return requestClient.get<DataforgeEtlApi.Pipeline>(
    `/dataforge/etl-pipeline/get?id=${id}`,
  );
}

export function saveEtlPipeline(data: DataforgeEtlApi.Pipeline) {
  return requestClient.post<DataforgeEtlApi.Id>(
    '/dataforge/etl-pipeline/save',
    data,
  );
}

export function deleteEtlPipeline(id: DataforgeEtlApi.Id) {
  return requestClient.delete(`/dataforge/etl-pipeline/delete?id=${id}`);
}

export function releaseEtlPipeline(id: DataforgeEtlApi.Id, revision: number) {
  return requestClient.put(
    `/dataforge/etl-pipeline/release?id=${id}&revision=${revision}`,
  );
}

export function cancelEtlPipeline(id: DataforgeEtlApi.Id) {
  return requestClient.put(`/dataforge/etl-pipeline/cancel-release?id=${id}`);
}

export function updateEtlPipelineSchedule(
  id: DataforgeEtlApi.Id,
  enable: boolean,
) {
  return requestClient.put(
    `/dataforge/etl-pipeline/schedule?id=${id}&enable=${enable}`,
  );
}

export function copyEtlPipeline(id: DataforgeEtlApi.Id) {
  return requestClient.post<DataforgeEtlApi.Id>(
    `/dataforge/etl-pipeline/copy?id=${id}`,
  );
}

export function runEtlPipeline(id: DataforgeEtlApi.Id) {
  return requestClient.post<DataforgeEtlApi.Id>(
    `/dataforge/etl-pipeline/run?id=${id}`,
  );
}

/** 页面保留 command，网络失败后复用 requestId。 */
export function getEtlRunParameters(pipelineId: DataforgeEtlApi.Id) {
  return requestClient.get<DataforgeEtlApi.RunParameterDefinition>(
    '/dataforge/etl-pipeline/run-parameters',
    { params: { pipelineId } },
  );
}

export function runEtlCommand(command: DataforgeEtlApi.EtlRunCommand) {
  return requestClient.post<RunReceipt>('/dataforge/etl-pipeline/run-command', {
    pipelineId: command.taskId,
    requestId: command.requestId,
    expectedDefinitionRevision: command.expectedDefinitionRevision,
    parameters: command.parameters,
  });
}

export function stopEtlCommand(command: StopCommand) {
  return requestClient.post<RunReceipt>(
    '/dataforge/etl-pipeline/stop-command',
    command,
  );
}

export function resolveEtlCommand(command: ResolveCommand) {
  return requestClient.post<RunReceipt>(
    '/dataforge/etl-pipeline/resolve-command',
    command,
  );
}

export function stopEtlRecord(recordId: DataforgeEtlApi.Id) {
  return requestClient.post(
    `/dataforge/etl-pipeline/stop?recordId=${recordId}`,
  );
}

export function getEtlRecordLog(recordId: DataforgeEtlApi.Id) {
  return requestClient.get<DataforgeEtlApi.EtlRecord>(
    `/dataforge/etl-pipeline/record-log?recordId=${recordId}`,
  );
}

export function getEtlNodeRecords(recordId: DataforgeEtlApi.Id) {
  return requestClient.get<DataforgeEtlApi.NodeRecord[]>(
    `/dataforge/etl-pipeline/node-records?recordId=${recordId}`,
  );
}

export function resolveEtlSchema(data: DataforgeEtlApi.GraphRequest) {
  return requestClient.post<DataforgeEtlApi.SchemaResult>(
    '/dataforge/etl-pipeline/resolve-schema',
    data,
  );
}

export function validateEtlPipeline(data: DataforgeEtlApi.GraphRequest) {
  return requestClient.post<DataforgeEtlApi.ValidationResult>(
    '/dataforge/etl-pipeline/validate',
    data,
  );
}

/**
 * 有界、无写入预览；服务端白名单（EtlPreviewEngine.ALLOWED）仅放行生成来源、授权的 PostgreSQL 整表采样、
 * 受控字典/质量节点与纯内存转换节点，不执行 SQL、脚本、加解密或读写外部系统的节点。
 */
export function previewEtlPipeline(data: DataforgeEtlApi.PreviewRequest) {
  return requestClient.post<DataforgeEtlApi.PreviewResult>(
    '/dataforge/etl-pipeline/preview',
    data,
    { timeout: 35_000 },
  );
}

/** Java 脚本编译检查（返回错误列表，空为通过） */
export function compileEtlJava(code: string) {
  return requestClient.post<string[]>('/dataforge/etl-pipeline/compile-java', {
    code,
  });
}

/** ==================== 执行记录 ==================== */

export function getEtlRecordPage(params: PageParam) {
  return requestClient.get<PageResult<DataforgeEtlApi.EtlRecord>>(
    '/dataforge/etl-record/page',
    { params },
  );
}

/** SOURCE_FIX 后以授权回放重跑并受控替换原目标批次，服务端会绑定完整质检证据。 */
export function replayEtlTargetBatch(
  data: DataforgeEtlApi.TargetBatchReplayRequest,
) {
  return requestClient.post<RunReceipt>(
    '/dataforge/etl/target-batch/replay',
    data,
  );
}

export function deleteEtlRecord(id: DataforgeEtlApi.Id) {
  return requestClient.delete(`/dataforge/etl-record/delete?id=${id}`);
}

export function getEtlQualityBatches(recordId: DataforgeEtlApi.Id) {
  return requestClient.get<DataforgeEtlApi.QualityBatch[]>(
    '/dataforge/etl-pipeline/quality-batches',
    { params: { recordId } },
  );
}

export function getEtlQualityIssues(
  params: PageParam & { batchId: DataforgeEtlApi.Id },
) {
  return requestClient.get<PageResult<DataforgeEtlApi.QualityIssue>>(
    '/dataforge/etl-pipeline/quality-issues',
    { params },
  );
}

export function getEtlQualityIssue(id: DataforgeEtlApi.Id) {
  return requestClient.get<DataforgeEtlApi.QualityIssueDetail>(
    '/dataforge/etl-pipeline/quality-issue',
    { params: { id } },
  );
}

export function getEtlQualityReport(batchId: DataforgeEtlApi.Id) {
  return requestClient.get<DataforgeEtlApi.QualityReport>(
    '/dataforge/etl-pipeline/quality-report',
    { params: { batchId } },
  );
}

export function createEtlQualityWorkOrder(data: {
  batchId: DataforgeEtlApi.Id;
  deadline?: number;
  description?: string;
  orderName: string;
}) {
  return requestClient.post<DataforgeEtlApi.Id>(
    '/dataforge/etl-pipeline/quality-work-order',
    data,
  );
}

/** 只重新登记治理报告，不重跑 ETL 或重新写入目标。 */
export function retryEtlQualityRegistration(batchId: DataforgeEtlApi.Id) {
  return requestClient.post<boolean>(
    '/dataforge/etl-pipeline/retry-quality-registration',
    undefined,
    { params: { batchId } },
  );
}

export function getEtlVersionPage(
  params: PageParam & { pipelineId: DataforgeEtlApi.Id },
) {
  return requestClient.get<PageResult<DataforgeEtlApi.Version>>(
    '/dataforge/etl-pipeline/version/page',
    { params },
  );
}

export function getEtlVersion(id: DataforgeEtlApi.Id) {
  return requestClient.get<DataforgeEtlApi.Version>(
    '/dataforge/etl-pipeline/version/get',
    { params: { id } },
  );
}

export function diffEtlVersion(id: DataforgeEtlApi.Id) {
  return requestClient.get<DataforgeEtlApi.VersionDiff[]>(
    '/dataforge/etl-pipeline/version/diff',
    { params: { id } },
  );
}

export function restoreEtlVersion(data: {
  expectedRevision: number;
  reason: string;
  versionId: DataforgeEtlApi.Id;
}) {
  return requestClient.post<number>(
    '/dataforge/etl-pipeline/version/restore',
    data,
  );
}

export function resolveUnknownEtlRecord(data: ResolveCommand) {
  return requestClient.post('/dataforge/etl-pipeline/resolve-unknown', data);
}
