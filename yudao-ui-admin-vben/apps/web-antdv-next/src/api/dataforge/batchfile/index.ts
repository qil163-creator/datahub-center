import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace BatchFileApi {
  export type Id = number | string;
  export type FieldType =
    | 'bigint'
    | 'boolean'
    | 'date'
    | 'datetime'
    | 'decimal'
    | 'double'
    | 'int'
    | 'string';
  export type RunStatus =
    | 'FAILED'
    | 'PARTIAL_SUCCESS'
    | 'QUEUED'
    | 'RUNNING'
    | 'SUCCESS'
    | 'UNKNOWN';
  export type LastStatus = 'NOT_EXECUTED' | RunStatus;
  export interface Field {
    sourceIndex: number;
    name: string;
    type: FieldType;
  }
  export interface File {
    id: Id;
    name: string;
    type: string;
    source: 'LOCAL' | 'SYSTEM';
    connectionId?: Id;
    path?: string;
    size: Id;
    sha256: string;
    parseStatus: 'PARSED';
    rowCount: Id;
    fields: Field[];
    targetTable?: string;
    writeOrder?: number;
    lastStatus?: LastStatus;
    writtenRows?: Id;
  }
  export interface Target {
    name: string;
    fileIds: Id[];
  }
  export interface Save {
    id?: Id;
    revision?: Id;
    taskName: string;
    responsibleUserId: Id;
    description?: string;
    remark?: string;
    targetDatabaseId: Id;
    files: { fields: Field[]; id: Id }[];
    targets: Target[];
  }
  export interface Task {
    id: Id;
    revision: Id;
    taskName: string;
    responsibleUserId: Id;
    responsibleName: string;
    responsiblePhone: string;
    description: string;
    remark: string;
    targetDatabaseId: Id;
    targetDatabaseName: string;
    online: boolean;
    fileCount: number;
    targetCount: number;
    lastStatus: LastStatus;
    lastRunId?: Id;
    lastRunTime?: number;
    lastRunResolvedTime?: number;
    createTime: number;
    updateTime: number;
    creator: string;
  }
  export interface Detail extends Task {
    files: File[];
    targets: Target[];
    successFiles: number;
    failedFiles: number;
  }
  export interface Summary {
    taskCount: Id;
    successCount: Id;
    failedCount: Id;
    runCount: Id;
    statisticsTime: number;
  }
  export interface Options {
    databases: { id: Id; name: string; productId: string }[];
    users: { id: Id; mobile: string; nickname: string }[];
    limits: {
      maxColumns: number;
      maxFileBytes: number;
      maxFiles: number;
      maxRows: number;
      maxTotalBytes: number;
    };
    fieldTypes: FieldType[];
  }
  export interface PageReq extends PageParam {
    taskName?: string;
    lastStatus?: LastStatus;
    sortField?: 'fileCount' | 'id' | 'targetCount' | 'updateTime';
    sortOrder?: 'asc' | 'desc';
  }
  export interface RunPageReq extends PageParam {
    taskId: Id;
  }
  export interface RunItem {
    fileId: Id;
    fileName: string;
    targetTable: string;
    writeOrder: number;
    status: 'FAILED' | 'PENDING' | 'RUNNING' | 'SUCCESS' | 'UNKNOWN';
    writtenRows: Id;
    errorMessage?: string;
  }
  export interface Run {
    id: Id;
    taskId: Id;
    requestId: string;
    status: RunStatus;
    executionType: 'MANUAL';
    executionUserId: Id;
    startTime?: number;
    endTime?: number;
    fileCount: number;
    successCount: number;
    failedCount: number;
    writtenRows: Id;
    errorMessage?: string;
    createTime: number;
    resolvedTime?: number;
    resolvedBy?: Id;
    resolutionReason?: string;
    items?: RunItem[];
  }
  export interface Receipt {
    id: Id;
    status: RunStatus;
    replayed: boolean;
  }
}

const base = '/dataforge/batch-file';
export const getBatchFilePage = (params: BatchFileApi.PageReq) =>
  requestClient.get<PageResult<BatchFileApi.Task>>(`${base}/page`, { params });
export const getBatchFileSummary = () =>
  requestClient.get<BatchFileApi.Summary>(`${base}/summary`);
export const getBatchFile = (id: BatchFileApi.Id) =>
  requestClient.get<BatchFileApi.Detail>(`${base}/get`, { params: { id } });
export const getBatchFileOptions = () =>
  requestClient.get<BatchFileApi.Options>(`${base}/options`);
export function uploadBatchFile(file: File) {
  const form = new FormData();
  form.append('file', file);
  return requestClient.post<BatchFileApi.File>(`${base}/upload`, form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120_000,
  });
}
export const importSystemFile = (data: {
  connectionId: BatchFileApi.Id;
  path: string;
}) =>
  requestClient.post<BatchFileApi.File>(`${base}/system-file`, data, {
    timeout: 120_000,
  });
export const deleteBatchFileUpload = (id: BatchFileApi.Id) =>
  requestClient.delete<boolean>(`${base}/upload`, { params: { id } });
export const createBatchFile = (data: BatchFileApi.Save) =>
  requestClient.post<BatchFileApi.Id>(`${base}/create`, data);
export const updateBatchFile = (data: BatchFileApi.Save) =>
  requestClient.put<boolean>(`${base}/update`, data);
export const deleteBatchFile = (
  id: BatchFileApi.Id,
  revision: BatchFileApi.Id,
) =>
  requestClient.delete<boolean>(`${base}/delete`, { params: { id, revision } });
export const updateBatchFileStatus = (data: {
  id: BatchFileApi.Id;
  online: boolean;
  revision: BatchFileApi.Id;
}) => requestClient.put<boolean>(`${base}/status`, data);
export const executeBatchFile = (data: {
  id: BatchFileApi.Id;
  requestId: string;
}) => requestClient.post<BatchFileApi.Receipt>(`${base}/execute`, data);
export const getBatchFileRuns = (params: BatchFileApi.RunPageReq) =>
  requestClient.get<PageResult<BatchFileApi.Run>>(`${base}/run-page`, {
    params,
  });
export const getBatchFileRun = (id: BatchFileApi.Id) =>
  requestClient.get<BatchFileApi.Run>(`${base}/run`, { params: { id } });
export const resolveBatchFileRun = (data: {
  confirmedStopped: true;
  id: BatchFileApi.Id;
  outputReviewed: true;
  reason: string;
}) => requestClient.post<boolean>(`${base}/resolve`, data);
