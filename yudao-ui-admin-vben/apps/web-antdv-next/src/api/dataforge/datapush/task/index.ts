import type { PageParam, PageResult } from '@vben/request';

import type { DataforgeDataPushConfigApi } from '#/api/dataforge/datapush/config';

import { requestClient } from '#/api/request';

export namespace DataforgeDataPushTaskApi {
  export interface Task {
    id?: number | string;
    pushConfigId?: number | string;
    categoryId?: null | number | string;
    pushConfigName?: string;
    channelType?: number;
    name?: string;
    sourceDatabaseId?: number | string;
    sourceDatabaseName?: string;
    pushType?: number;
    tableName?: string;
    sqlText?: string;
    objectName?: string;
    pushMode?: number;
    incrementColumn?: string;
    initStartVal?: string;
    batchSize?: number;
    taskType?: number;
    cron?: string;
    status?: number;
    nextRunTime?: number | string;
    releaseTime?: number | string;
    releaseUserId?: number | string;
    releaseUserName?: string;
    description?: string;
    orgId?: number | string;
    orgName?: string;
    running?: boolean;
    /** 0 空闲，1 等待，2 已进入发送，5 结果未知且禁止重跑。 */
    blockingRunStatus?: number;
    lastRunStatus?: number;
    lastStartTime?: number | string;
    lastEndTime?: number | string;
    lastDataCount?: number | string;
    lastErrorInfo?: string;
    watermarkEndVal?: string;
    createTime?: number | string;
  }

  export interface TaskPageReqVO extends PageParam {
    categoryId?: number | string;
    name?: string;
    pushType?: number;
    pushMode?: number;
    taskType?: number;
    status?: number;
    sourceDatabaseId?: number | string;
    orgId?: number | string;
  }

  export interface TaskSaveReqVO {
    categoryId?: null | number | string;
    id?: number | string;
    name?: string;
    pushConfigId?: number | string;
    pushConfig?: DataforgeDataPushConfigApi.Config;
    sourceDatabaseId?: number | string;
    orgId?: number | string;
    description?: string;
    objectName?: string;
    pushType?: number;
    tableName?: string;
    sqlText?: string;
    pushMode?: number;
    incrementColumn?: string;
    initStartVal?: string;
    batchSize?: number;
    taskType?: number;
    cron?: string;
  }

  export interface TaskLog {
    id?: number | string;
    pushTaskId?: number | string;
    runStatus?: number;
    runType?: number;
    startTime?: number | string;
    endTime?: number | string;
    dataCount?: number | string;
    realTimeLog?: string;
    errorInfo?: string;
    executeNo?: string;
    resolutionRequestId?: string;
    resolutionOutcome?: 'COMPENSATED' | 'NO_EFFECT';
    resolutionReason?: string;
    stoppedEvidenceRef?: string;
    outputEvidenceRef?: string;
    resolvedBy?: number | string;
    resolvedAt?: number;
  }

  export interface ResolveCommand {
    recordId: number | string;
    requestId: string;
    outcome: 'COMPENSATED' | 'NO_EFFECT';
    confirmedStopped: true;
    outputReviewed: true;
    reason: string;
    stoppedEvidenceRef: string;
    outputEvidenceRef: string;
  }

  export interface TaskLogPageReqVO extends PageParam {
    pushTaskId: number | string;
    runStatus?: number;
  }

  export interface Column {
    name: string;
    comment?: string;
    dataType?: string;
    incrementSupported?: boolean;
  }

  export interface Watermark {
    pushTaskId?: number | string;
    increment?: boolean;
    exists?: boolean;
    incrementColumn?: string;
    startVal?: string;
    endVal?: string;
    updateTime?: number | string;
    initStartVal?: string;
    resetUserId?: number | string;
    resetUserName?: string;
    resetTime?: number | string;
    resetOldEnd?: string;
  }

  export interface Preview {
    channelType?: number;
    objectName?: string;
    columns?: string[];
    rows?: Record<string, any>[];
    rowCount?: number;
    incrementStart?: string;
    incrementEnd?: string;
    emptyInterval?: boolean;
    payloadPreview?: string;
    hint?: string;
  }

  export interface PreviewReqVO {
    id?: number | string;
    pushConfigId?: number | string;
    pushConfig?: DataforgeDataPushConfigApi.Config;
    sourceDatabaseId?: number | string;
    pushType?: number;
    tableName?: string;
    sqlText?: string;
    objectName?: string;
    pushMode?: number;
    incrementColumn?: string;
    initStartVal?: string;
    batchSize?: number;
    limit?: number;
  }
}

export function getDataPushTaskPage(
  params: DataforgeDataPushTaskApi.TaskPageReqVO,
) {
  return requestClient.get<PageResult<DataforgeDataPushTaskApi.Task>>(
    '/dataforge/data-push-task/page',
    { params },
  );
}

export function getDataPushTask(id: number | string) {
  return requestClient.get<DataforgeDataPushTaskApi.Task>(
    '/dataforge/data-push-task/get',
    { params: { id } },
  );
}

export function createDataPushTask(
  data: DataforgeDataPushTaskApi.TaskSaveReqVO,
) {
  return requestClient.post<number | string>(
    '/dataforge/data-push-task/create',
    data,
  );
}

export function updateDataPushTask(
  data: DataforgeDataPushTaskApi.TaskSaveReqVO,
) {
  return requestClient.put<boolean>('/dataforge/data-push-task/update', data);
}

export function deleteDataPushTask(id: number | string) {
  return requestClient.delete<boolean>('/dataforge/data-push-task/delete', {
    params: { id },
  });
}

export function releaseDataPushTask(id: number | string) {
  return requestClient.put<boolean>('/dataforge/data-push-task/release', {
    params: { id },
  });
}

export function cancelDataPushTask(id: number | string) {
  return requestClient.put<boolean>(
    '/dataforge/data-push-task/cancel-release',
    { params: { id } },
  );
}

export function handRunDataPushTask(id: number | string) {
  return requestClient.put<boolean>('/dataforge/data-push-task/hand-run', {
    params: { id },
  });
}

export function stopDataPushTask(id: number | string) {
  return requestClient.put<boolean>('/dataforge/data-push-task/stop', {
    params: { id },
  });
}

export function getDataPushTaskLogPage(
  params: DataforgeDataPushTaskApi.TaskLogPageReqVO,
) {
  return requestClient.get<PageResult<DataforgeDataPushTaskApi.TaskLog>>(
    '/dataforge/data-push-task/log-page',
    { params },
  );
}

export function getDataPushTaskLog(id: number | string) {
  return requestClient.get<DataforgeDataPushTaskApi.TaskLog>(
    '/dataforge/data-push-task/log-get',
    { params: { id } },
  );
}

export function resolveDataPushRun(
  data: DataforgeDataPushTaskApi.ResolveCommand,
) {
  return requestClient.post<DataforgeDataPushTaskApi.TaskLog>(
    '/dataforge/data-push-task/resolve-command',
    data,
  );
}

export function getDataPushTableColumns(
  sourceDatabaseId: number | string,
  tableName: string,
) {
  return requestClient.get<DataforgeDataPushTaskApi.Column[]>(
    '/dataforge/data-push-task/table-columns',
    { params: { sourceDatabaseId, tableName } },
  );
}

export function parseDataPushSqlColumns(
  sourceDatabaseId: number | string,
  sqlText: string,
) {
  return requestClient.post<DataforgeDataPushTaskApi.Column[]>(
    '/dataforge/data-push-task/parse-sql-columns',
    { sourceDatabaseId, sqlText },
  );
}

export function getDataPushTaskWatermark(id: number | string) {
  return requestClient.get<DataforgeDataPushTaskApi.Watermark>(
    '/dataforge/data-push-task/watermark',
    { params: { id } },
  );
}

export function resetDataPushTaskWatermark(id: number | string) {
  return requestClient.put<boolean>(
    '/dataforge/data-push-task/reset-watermark',
    {
      params: { id },
    },
  );
}

export function previewDataPushTask(
  data: DataforgeDataPushTaskApi.PreviewReqVO,
) {
  return requestClient.post<DataforgeDataPushTaskApi.Preview>(
    '/dataforge/data-push-task/preview',
    data,
  );
}

export function copyDataPushTask(id: number | string) {
  return requestClient.post<number | string>(
    `/dataforge/data-push-task/copy?id=${id}`,
  );
}
