import type { PageParam, PageResult } from '@vben/request';

import type { OpsInstanceApi } from '#/api/dataforge/ops';

import { requestClient } from '#/api/request';

export namespace JobApi {
  export type Id = number | string;
  export type Count = number | string;
  export type Strategy =
    | 'PARALLEL'
    | 'SERIAL_DISCARD'
    | 'SERIAL_PRIORITY'
    | 'SERIAL_WAIT';
  export interface Category {
    id: Id;
    parentId: Id;
    name: string;
    sort?: number;
  }
  export interface Owner {
    id: Id;
    name: string;
    contactNumber?: string;
  }
  export interface FlowNode {
    id: string;
    kind: 'DEVELOPMENT' | 'INTEGRATION';
    taskId: Id;
    name: string;
    x: number;
    y: number;
    priority?: 'HIGH' | 'HIGHEST' | 'LOW' | 'LOWEST' | 'MEDIUM';
    status?: number;
    description?: string;
    taskType?: string;
    dbType?: string;
  }
  export interface FlowEdge {
    source: string;
    target: string;
  }
  export interface Flow {
    nodes: FlowNode[];
    edges: FlowEdge[];
  }
  export interface TaskOption {
    id: Id;
    kind: FlowNode['kind'];
    name: string;
    categoryId?: Id;
    categoryName?: string;
    taskType?: string;
    dbType?: string;
    executable: boolean;
    status?: number;
    description?: string;
  }
  export interface SaveReq {
    id?: Id;
    revision?: number;
    categoryId: Id;
    name: string;
    ownerId?: Id;
    ownerPhone?: string;
    strategy: Strategy;
    cronExpression?: string;
    description?: string;
    flowJson?: string;
  }
  export type SaveJob = SaveReq;
  export interface Job extends SaveReq {
    id: Id;
    revision: number;
    categoryName?: string;
    ownerName?: string;
    status: number;
    scheduleStatus: number;
    nextRunTime?: null | string;
    configurationStatus?: string;
    activeRunCount?: Count;
    queuedRunCount?: Count;
    hasActiveRuns?: boolean;
    lastRunStatus?: OpsInstanceApi.Status;
    lastRunTime?: null | string;
    lastRunDuration?: null | number;
    createTime?: string;
    updateTime?: string;
    creatorName?: string;
    creatorPhone?: string;
    latestRecord?: OpsInstanceApi.Instance;
  }
  export interface PageReq extends PageParam {
    name?: string;
    categoryId?: Id;
    status?: number;
    ownerId?: Id;
    runStatus?: OpsInstanceApi.Status;
  }
  export interface CheckResult {
    valid: boolean;
    executable: boolean;
    errors: string[];
  }
}

const base = '/dataforge/job';
export const getJobPage = (params: JobApi.PageReq) =>
  requestClient.get<PageResult<JobApi.Job>>(`${base}/page`, { params });
export const getJob = (id: JobApi.Id) =>
  requestClient.get<JobApi.Job>(`${base}/get`, { params: { id } });
export const createJob = (data: JobApi.SaveReq) =>
  requestClient.post<JobApi.Id>(`${base}/create`, data);
export const updateJob = (data: JobApi.SaveReq) =>
  requestClient.put<boolean>(`${base}/update`, data);
export const cloneJob = (id: JobApi.Id) =>
  requestClient.post<JobApi.Id>(`${base}/clone`, undefined, { params: { id } });
export const deleteJob = (id: JobApi.Id, revision: number) =>
  requestClient.delete<boolean>(`${base}/delete`, { params: { id, revision } });
export const updateJobStatus = (data: {
  id: JobApi.Id;
  revision: number;
  status: number;
}) => requestClient.put<boolean>(`${base}/status`, data);
export const updateJobSchedule = (data: {
  cronExpression: string;
  id: JobApi.Id;
  revision: number;
  scheduleStatus: number;
}) => requestClient.put<boolean>(`${base}/schedule`, data);
export const previewJobCron = (cronExpression: string) =>
  requestClient.get<string[]>(`${base}/cron-preview`, {
    params: { cronExpression },
  });
export const getJobCategories = () =>
  requestClient.get<JobApi.Category[]>(`${base}/categories`);
export const createJobCategory = (data: Omit<JobApi.Category, 'id'>) =>
  requestClient.post<JobApi.Id>(`${base}/category/create`, data);
export const updateJobCategory = (data: JobApi.Category) =>
  requestClient.put<boolean>(`${base}/category/update`, data);
export const deleteJobCategory = (id: JobApi.Id) =>
  requestClient.delete<boolean>(`${base}/category/delete`, { params: { id } });
export const getJobOwners = () =>
  requestClient.get<JobApi.Owner[]>(`${base}/owners`);
export const getJobTaskOptions = () =>
  requestClient.get<JobApi.TaskOption[]>(`${base}/task-options`);
export const checkJob = (data: { flowJson: string }) =>
  requestClient.post<JobApi.CheckResult>(`${base}/check`, data);
export const runJob = (data: {
  id: JobApi.Id;
  requestId: string;
  revision: number;
}) => requestClient.post<JobApi.Id>(`${base}/run`, data);
export const stopJob = (id: JobApi.Id) =>
  requestClient.post<boolean>(`${base}/stop`, undefined, { params: { id } });
export const getJobRecords = (params: PageParam & { taskId: JobApi.Id }) =>
  requestClient.get<PageResult<OpsInstanceApi.Instance>>(`${base}/records`, {
    params,
  });
