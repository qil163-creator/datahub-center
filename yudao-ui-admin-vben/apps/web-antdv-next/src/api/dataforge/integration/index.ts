import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace IntegrationApi {
  export type Id = number | string;
  export type Mode = 'OFFLINE' | 'REALTIME';
  export type Engine = 'DATAX' | 'FLINK' | 'SPARK';
  export type RunStatus = 'FAILURE' | 'IDLE' | 'RUNNING' | 'SUCCESS';
  export type Strategy =
    | 'PARALLEL'
    | 'SERIAL_DISCARD'
    | 'SERIAL_PRIORITY'
    | 'SERIAL_WAIT';
  export type Priority = 'HIGH' | 'HIGHEST' | 'LOW' | 'LOWEST' | 'MEDIUM';
  export interface Category {
    id: Id;
    parentId: Id;
    name: string;
    sort?: number;
    validFlag?: boolean;
    description?: string;
  }
  export interface Owner {
    id: Id;
    name: string;
    contactNumber?: string;
  }
  export interface EngineConfig {
    jvmInitialMemory?: number;
    jvmMaximumMemory?: number;
    channel?: number;
    byteSpeed?: number;
    recordSpeed?: number;
    errorLimit?: number;
    driverCores?: number;
    driverMemory?: string;
    executorCount?: number;
    executorMemory?: string;
    executorCores?: number;
    yarnQueue?: string;
    jobManagerMemory?: string;
    taskManagerMemory?: string;
    slots?: number;
    taskManagerCount?: number;
    parallelism?: number;
  }
  export interface Task {
    id: Id;
    revision: number;
    mode: Mode;
    categoryId: Id;
    categoryName?: string;
    name: string;
    ownerId: Id | null;
    ownerName?: string;
    contactNumber?: string;
    status: number;
    scheduleStatus: number;
    engine: Engine;
    engineConfig: EngineConfig;
    scheduler: 'DOLPHIN_SCHEDULER';
    cronExpression?: string;
    priority: Priority;
    retryCount: number;
    retryInterval: number;
    delayMinutes: number;
    executionStrategy: Strategy;
    workerGroup: string;
    description?: string;
    flowJson?: string;
    dsProjectCode?: Id;
    dsDefinitionCode?: Id;
    dsScheduleId?: Id;
    configurationStatus: 'COMPLETE' | 'UNCONFIGURED';
    executable: boolean;
    runtimeAvailable?: boolean;
    runtimeMessage?: string;
    activeRunning?: boolean;
    importedSnapshot: boolean;
    lastRunStatus: RunStatus;
    lastRunTime?: null | number;
    lastRunDuration?: null | number;
    creatorName?: string;
    createTime?: number;
    updateTime?: number;
  }
  export type SaveTask = Omit<
    Task,
    | 'activeRunning'
    | 'categoryName'
    | 'configurationStatus'
    | 'contactNumber'
    | 'createTime'
    | 'creatorName'
    | 'dsDefinitionCode'
    | 'dsProjectCode'
    | 'dsScheduleId'
    | 'executable'
    | 'flowJson'
    | 'id'
    | 'importedSnapshot'
    | 'lastRunDuration'
    | 'lastRunStatus'
    | 'lastRunTime'
    | 'ownerName'
    | 'revision'
    | 'runtimeAvailable'
    | 'runtimeMessage'
    | 'scheduleStatus'
    | 'updateTime'
  > & { id?: Id; revision?: number };
  export interface PageReq extends PageParam {
    mode: Mode;
    executedToday?: boolean;
    runDateStart?: string;
    runDateEnd?: string;
    name?: string;
    categoryId?: Id;
    status?: number;
    runStatus?: RunStatus;
    ownerId?: Id;
    sortField?: 'id';
    sortOrder?: 'asc' | 'desc';
  }
  export interface Statistics {
    runtimeAvailable?: boolean;
    runtimeMessage?: string;
    running: number;
    todayFailed: number;
    todayExecuted: number;
    todaySuccessRate: number;
    statisticTime?: number;
  }
  export interface Record {
    id: Id;
    taskId: Id;
    status: RunStatus;
    triggerType: 'IMPORTED' | 'MANUAL' | 'SCHEDULE';
    startTime?: number;
    endTime?: number;
    duration?: number;
    executorName?: string;
    message?: string;
    taskName?: string;
    instanceName?: string;
    executionType?: Strategy;
    currentNode?: string;
    commandType?: string;
  }

  export interface CategorySave {
    id?: Id;
    parentId: Id;
    name: string;
    sort: number;
    validFlag: boolean;
    description?: string;
  }
  export interface RecordStatistics {
    running: number;
    success: number;
    failed: number;
    statisticTime: number;
  }
  export interface SchemaField {
    name: string;
    dataType?: null | string;
    length?: null | number | string;
    precision?: null | number;
    scale?: null | number;
    nullable?: boolean | null;
    sourceName?: null | string;
  }
  export interface NodeSchema {
    inputFields?: SchemaField[];
    outputFields?: SchemaField[];
    outputPorts?: { [port: string]: SchemaField[] };
    complete?: boolean;
  }
  export interface SchemaResult {
    nodes?: { [nodeId: string]: NodeSchema };
    errors?: string[];
    warnings?: string[];
  }
}

const base = '/dataforge/integration';
export interface IntegrationFlowRequest {
  id: IntegrationApi.Id;
  revision: number;
  flowJson: string;
  draft: boolean;
}
export const saveIntegrationFlow = (data: IntegrationFlowRequest) =>
  requestClient.put<IntegrationApi.Task>(`${base}/flow`, data);
export const checkIntegrationFlow = (data: IntegrationFlowRequest) =>
  requestClient.post<{ errors: string[]; valid: boolean; }>(
    `${base}/check`,
    data,
  );
export const resolveIntegrationSchema = (data: Omit<IntegrationFlowRequest, 'draft'>) =>
  requestClient.post<IntegrationApi.SchemaResult>(`${base}/schema`, data, { silentError: true });
export const getIntegrationFileSchema = (data: {
  fileId: IntegrationApi.Id;
  format: 'CSV' | 'EXCEL' | 'JSONL';
  sheetName?: string;
  startColumn?: number;
  startRow?: number;
}) => requestClient.post<{ fields: IntegrationApi.SchemaField[]; partial?: boolean; warnings?: string[] }>(`${base}/file-schema`, data, { silentError: true });
export const getIntegrationPage = (params: IntegrationApi.PageReq) =>
  requestClient.get<PageResult<IntegrationApi.Task>>(`${base}/page`, {
    params,
  });
export const getIntegration = (id: IntegrationApi.Id) =>
  requestClient.get<IntegrationApi.Task>(`${base}/get`, { params: { id } });
export const getIntegrationCategories = () =>
  requestClient.get<IntegrationApi.Category[]>(`${base}/categories`);
export const getIntegrationOwners = () =>
  requestClient.get<IntegrationApi.Owner[]>(`${base}/owners`);
export const getIntegrationStatistics = (mode: IntegrationApi.Mode) =>
  requestClient.get<IntegrationApi.Statistics>(`${base}/statistics`, {
    params: { mode },
  });
export const createIntegration = (data: IntegrationApi.SaveTask) =>
  requestClient.post<IntegrationApi.Id>(`${base}/create`, data);
export const updateIntegration = (data: IntegrationApi.SaveTask) =>
  requestClient.put<boolean>(`${base}/update`, data);
export const deleteIntegration = (id: IntegrationApi.Id) =>
  requestClient.delete<boolean>(`${base}/delete`, { params: { id } });
export const cloneIntegration = (id: IntegrationApi.Id) =>
  requestClient.post<IntegrationApi.Id>(`${base}/clone`, undefined, {
    params: { id },
  });
export const setIntegrationStatus = (data: {
  id: IntegrationApi.Id;
  revision: number;
  status: number;
}) => requestClient.put<boolean>(`${base}/status`, data);
export const setIntegrationSchedule = (data: {
  cronExpression?: string;
  id: IntegrationApi.Id;
  revision: number;
  scheduleStatus: number;
}) => requestClient.put<boolean>(`${base}/schedule`, data);
export const runIntegration = (id: IntegrationApi.Id) =>
  requestClient.post<IntegrationApi.Id>(`${base}/run`, undefined, {
    params: { id },
  });
export const stopIntegration = (id: IntegrationApi.Id) =>
  requestClient.post<boolean>(`${base}/stop`, undefined, { params: { id } });
export const getIntegrationRecords = (params: {
  pageNo: number;
  pageSize: number;
  status?: IntegrationApi.RunStatus;
  taskId: IntegrationApi.Id;
}) =>
  requestClient.get<PageResult<IntegrationApi.Record>>(`${base}/records`, {
    params,
  });

export const getIntegrationRecordStatistics = (taskId: IntegrationApi.Id) =>
  requestClient.get<IntegrationApi.RecordStatistics>(
    `${base}/record-statistics`,
    { params: { taskId } },
  );
export const createIntegrationCategory = (data: IntegrationApi.CategorySave) =>
  requestClient.post<IntegrationApi.Id>(`${base}/category/create`, data);
export const updateIntegrationCategory = (data: IntegrationApi.CategorySave) =>
  requestClient.put<boolean>(`${base}/category/update`, data);
export const deleteIntegrationCategory = (id: IntegrationApi.Id) =>
  requestClient.delete<boolean>(`${base}/category/delete`, { params: { id } });
