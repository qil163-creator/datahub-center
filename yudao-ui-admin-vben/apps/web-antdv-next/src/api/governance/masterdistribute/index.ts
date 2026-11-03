import type { PageParam, PageResult } from '@vben/request';

import type { GovernanceMasterDataApi } from '../masterdata';

import { requestClient } from '#/api/request';

export namespace GovernanceMasterDistributeApi {
  export interface KeyValue {
    key?: string;
    value?: string;
  }

  export interface DbConfig {
    databaseId?: number | string;
    targetTableName?: string;
    targetDrop?: boolean;
    syncExist?: boolean;
    onlyCreate?: boolean;
    changeDataSync?: boolean;
    fetchSize?: number;
  }

  export interface ApiConfig {
    url?: string;
    headers?: KeyValue[];
    params?: KeyValue[];
    fetchSize?: number;
    method?: 'POST' | 'PUT';
    authType?: 'NONE' | 'TOKEN';
    token?: string;
    tokenConfigured?: boolean;
    timeoutSeconds?: number;
    pushScope?: 'ALL' | 'ENABLED';
    statusField?: string;
    enabledValue?: string;
    pushMode?: 'FULL' | 'INCREMENTAL';
    retryEnabled?: boolean;
    retryCount?: number;
    retryIntervalMinutes?: number;
    exhaustedAction?: 'MANUAL' | 'STOP';
    mappings?: FieldMapping[];
  }

  export interface FieldMapping {
    sourceField: string;
    targetField: string;
    enabled: boolean;
    transform: 'ORIGINAL' | 'STANDARD_DICT';
    defaultValue?: string;
  }

  export interface KafkaConfig {
    bootstrapServers?: string;
    topic?: string;
  }

  export interface RabbitConfig {
    host?: string;
    port?: number;
    username?: string;
    password?: string;
    virtualHost?: string;
    exchange?: string;
    routingKey?: string;
  }

  export interface MqConfig {
    mqType?: string; // kafka / rabbitmq
    kafka?: KafkaConfig;
    rabbit?: RabbitConfig;
    fetchSize?: number;
  }

  export interface DistributeConfig {
    db?: DbConfig;
    api?: ApiConfig;
    mq?: MqConfig;
  }

  /** 主数据派发任务 */
  export interface Distribute {
    id?: number | string;
    categoryId?: null | number | string;
    masterModelId?: number | string;
    masterModelName?: string;
    masterModelTableName?: string;
    name?: string;
    distributeType?: number; // 1-数据库 2-API 3-消息队列
    taskType?: number; // 1-一次性 2-周期性
    cron?: string;
    nextRunTime?: number;
    status?: number; // 0-未发布 1-已发布
    blockingRunStatus?: number; // 0-无 1-等待 2-运行中 5-结果未知
    description?: string;
    releaseTime?: number;
    releaseUserId?: number | string;
    releaseUserName?: string;
    orgId?: number | string;
    orgName?: string;
    createTime?: number;
    creatorName?: string;
    updaterName?: string;
    updateTime?: number;
    latestRunStatus?: number;
    lastPushTime?: number;
    totalCount?: number | string;
    successCount?: number | string;
    failureCount?: number | string;
    unknownCount?: number | string;
    config?: DistributeConfig;
  }

  /** 派发执行记录 */
  export interface DistributeLog {
    stopRequestedBy?: number | string;
    stopRequestedAt?: number;
    stopReason?: string;
    id?: number | string;
    distributeId?: number | string;
    masterModelId?: number | string;
    runType?: number; // 1-自动调度 2-手动执行
    runStatus?: number; // 1-等待派发 2-运行中 3-成功 4-失败 5-结果未知 6-已人工处置（非成功）
    resolutionRequestId?: string;
    resolutionOutcome?: 'COMPENSATED' | 'NO_EFFECT';
    resolutionReason?: string;
    stoppedEvidenceRef?: string;
    outputEvidenceRef?: string;
    resolvedBy?: number | string;
    resolvedAt?: number;
    startTime?: number;
    endTime?: number;
    errorInfo?: string;
    dataCount?: number | string;
    byteCount?: string;
    createTime?: number;
    totalCount?: number | string;
    successCount?: number | string;
    failureCount?: number | string;
    unknownCount?: number | string;
    pushScope?: 'ALL' | 'ENABLED';
    pushMode?: 'FULL' | 'INCREMENTAL';
    attempts?: number;
  }

  export interface PageReqVO extends PageParam {
    categoryId?: number | string;
    name?: string;
    distributeType?: number;
    status?: number;
    latestRunStatus?: number;
  }

  export interface LogPageReqVO extends PageParam {
    distributeId: number | string;
    runStatus?: number;
  }

  export interface StopCommand {
    recordId: number | string;
    reason: string;
  }

  export interface ResolveCommand {
    recordId: number | string;
    requestId: string;
    outcome: 'COMPENSATED' | 'NO_EFFECT';
    reason: string;
    confirmedStopped: true;
    outputReviewed: true;
    stoppedEvidenceRef: string;
    outputEvidenceRef: string;
  }
}

export function getMasterDistributePage(
  params: GovernanceMasterDistributeApi.PageReqVO,
) {
  return requestClient.get<
    PageResult<GovernanceMasterDistributeApi.Distribute>
  >('/governance/master-distribute/page', { params });
}

export function getMasterDistribute(id: number | string) {
  return requestClient.get<GovernanceMasterDistributeApi.Distribute>(
    `/governance/master-distribute/get?id=${id}`,
  );
}

export function createMasterDistribute(
  data: GovernanceMasterDistributeApi.Distribute,
) {
  return requestClient.post('/governance/master-distribute/create', data);
}

export function updateMasterDistribute(
  data: GovernanceMasterDistributeApi.Distribute,
) {
  return requestClient.put('/governance/master-distribute/update', data);
}

export function deleteMasterDistribute(id: number | string) {
  return requestClient.delete(`/governance/master-distribute/delete?id=${id}`);
}

export function releaseMasterDistribute(id: number | string) {
  return requestClient.put(`/governance/master-distribute/release?id=${id}`);
}

export function cancelMasterDistribute(id: number | string) {
  return requestClient.put(
    `/governance/master-distribute/cancel-release?id=${id}`,
  );
}

export function handRunMasterDistribute(id: number | string) {
  return requestClient.put(`/governance/master-distribute/hand-run?id=${id}`);
}

export function updateMasterDistributeStatus(
  id: number | string,
  status: number,
) {
  return requestClient.put<boolean>(
    '/governance/master-distribute/update-status',
    undefined,
    { params: { id, status } },
  );
}

export function retryMasterDistribute(id: number | string) {
  return requestClient.put<boolean>(
    '/governance/master-distribute/retry',
    undefined,
    { params: { id } },
  );
}

export function getPushModel(id: number | string) {
  return requestClient.get<GovernanceMasterDataApi.Model>(
    '/governance/master-distribute/model',
    { params: { id } },
  );
}

export function getPushModelList() {
  return requestClient.get<GovernanceMasterDataApi.Model[]>(
    '/governance/master-distribute/model-list',
  );
}

export function getMasterDistributeLogPage(
  params: GovernanceMasterDistributeApi.LogPageReqVO,
) {
  return requestClient.get<
    PageResult<GovernanceMasterDistributeApi.DistributeLog>
  >('/governance/master-distribute/log-page', { params });
}

export function getMasterDistributeLog(id: number | string) {
  return requestClient.get<GovernanceMasterDistributeApi.DistributeLog>(
    '/governance/master-distribute/log-get',
    { params: { id } },
  );
}

export function resolveMasterDistributeRun(
  data: GovernanceMasterDistributeApi.ResolveCommand,
) {
  return requestClient.put<GovernanceMasterDistributeApi.DistributeLog>(
    '/governance/master-distribute/resolve',
    data,
  );
}

export function stopMasterDistributeRun(
  data: GovernanceMasterDistributeApi.StopCommand,
) {
  return requestClient.put<GovernanceMasterDistributeApi.DistributeLog>(
    '/governance/master-distribute/stop',
    data,
  );
}
