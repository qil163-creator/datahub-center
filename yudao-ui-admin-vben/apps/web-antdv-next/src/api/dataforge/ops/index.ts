import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace OpsInstanceApi {
  export type Id = number | string;
  export type Kind = 'DEVELOPMENT' | 'FULL_SYNC' | 'INTEGRATION' | 'JOB';
  export type Origin = 'MANAGED' | 'PLATFORM' | 'SOURCE' | 'WIZARD';
  export type QueueState =
    | 'ACTIVE'
    | 'CANCELLED'
    | 'DISCARDED'
    | 'DISPATCHING'
    | 'DONE'
    | 'QUEUED'
    | 'REJECTED'
    | 'STOP_REQUESTED'
    | 'SUPERSEDED'
    | 'UNKNOWN';
  export type Status =
    | 'FAILURE'
    | 'IDLE'
    | 'RUNNING'
    | 'STOPPED'
    | 'SUCCESS'
    | 'UNKNOWN'
    | 'WAITING';
  export interface Identity {
    id: Id;
    kind: Kind;
    origin: Origin;
  }
  export interface Instance extends Identity {
    taskId: Id;
    categoryId?: Id;
    taskName: string;
    instanceName: string;
    taskType?: string;
    executionType: 'SCHEDULE' | 'START' | 'STOP';
    status: Status;
    queueState?: QueueState;
    queueReason?: string;
    queuedTime?: null | string;
    scheduledTime?: null | string;
    startTime?: null | string;
    endTime?: null | string;
    duration?: null | number;
    runCount?: number;
    ownerName?: string;
    creatorName?: string;
    createTime?: null | string;
    updateTime?: null | string;
    description?: string;
    flowJson?: string;
    children?: Instance[];
    /** 演示子任务引用父作业日志时，由展示层附加查询身份。 */
    logIdentity?: Identity;
  }
  export interface PageReq extends PageParam {
    kind: Kind;
    name?: string;
    categoryId?: Id;
    taskType?: string;
    status?: Status;
    startDate?: string;
    endDate?: string;
    sortField?: 'createTime' | 'endTime' | 'id' | 'startTime';
    sortOrder?: 'asc' | 'desc';
  }
  export interface Category {
    id: Id;
    parentId: Id;
    name: string;
    sort?: number;
  }
  export interface Statistics {
    running: number;
    todayFailed: number;
    todayExecuted: number;
    todaySuccessRate: number;
    statisticTime: string;
    importedSnapshot: boolean;
  }
  export interface Log {
    message: string;
    available: boolean;
    importedSnapshot: boolean;
  }
}

const base = '/dataforge/ops-instance';
export const getOpsInstancePage = (params: OpsInstanceApi.PageReq) =>
  requestClient.get<PageResult<OpsInstanceApi.Instance>>(`${base}/page`, {
    params,
  });
export const getOpsInstanceCategories = (kind: OpsInstanceApi.Kind) =>
  requestClient.get<OpsInstanceApi.Category[]>(`${base}/categories`, {
    params: { kind },
  });
export const getOpsInstanceStatistics = (
  params: Pick<OpsInstanceApi.PageReq, 'categoryId' | 'kind' | 'taskType'>,
) =>
  requestClient.get<OpsInstanceApi.Statistics>(`${base}/statistics`, {
    params,
  });
export const getOpsInstance = (params: OpsInstanceApi.Identity) =>
  requestClient.get<OpsInstanceApi.Instance>(`${base}/get`, { params });
export const getOpsInstanceLog = (params: OpsInstanceApi.Identity) =>
  requestClient.get<OpsInstanceApi.Log>(`${base}/log`, { params });
export const downloadOpsInstanceLog = (params: OpsInstanceApi.Identity) =>
  requestClient.download(`${base}/log-download`, { params });
