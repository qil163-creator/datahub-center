import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace GovernanceMetaCollectTaskApi {
  export type Id = number | string;
  export type ExecuteStrategy =
    | 'PARALLEL'
    | 'SERIAL_DISCARD'
    | 'SERIAL_PRIORITY'
    | 'SERIAL_WAIT';
  export interface CollectTask {
    id?: Id;
    name?: string;
    collectType?: 1 | 2;
    executeStrategy?: ExecuteStrategy;
    filePath?: string;
    datasourceId?: Id;
    datasourceName?: string;
    sourceSystemId?: Id;
    sourceSystemName?: string;
    dbType?: string;
    host?: string;
    port?: number | string;
    username?: string;
    scopeType?: number;
    scopeConfig?: string;
    blacklist?: string;
    cron?: string;
    scheduler?: string;
    enabled?: number;
    scheduleEnabled?: number;
    runStatus?: number;
    ownerUserId?: Id;
    ownerName?: string;
    ownerPhone?: string;
    ownerDeptId?: Id;
    creatorName?: string;
    creatorPhone?: string;
    updaterName?: string;
    updateTime?: number;
    lastRunTime?: number;
    nextRunTime?: number;
    lastInstanceId?: Id;
    description?: string;
    remark?: string;
    createTime?: number;
  }
  export interface SourceConnection {
    id: Id;
    name: string;
    collectType: 1 | 2;
    dbType?: string;
    host?: string;
    port?: number | string;
    username?: string;
  }
  export interface SourceOptions {
    connections: SourceConnection[];
    systems: { id: Id; name: string }[];
  }
  export interface Scope {
    db: string;
    tables: string[];
  }
}
export function getMetaCollectTaskPage(params: PageParam) {
  return requestClient.get<
    PageResult<GovernanceMetaCollectTaskApi.CollectTask>
  >('/governance/meta-collect-task/page', { params });
}
export function getMetaCollectTask(id: GovernanceMetaCollectTaskApi.Id) {
  return requestClient.get<GovernanceMetaCollectTaskApi.CollectTask>(
    '/governance/meta-collect-task/get',
    { params: { id } },
  );
}
export function createMetaCollectTask(
  data: GovernanceMetaCollectTaskApi.CollectTask,
) {
  return requestClient.post('/governance/meta-collect-task/create', data);
}
export function updateMetaCollectTask(
  data: GovernanceMetaCollectTaskApi.CollectTask,
) {
  return requestClient.put('/governance/meta-collect-task/update', data);
}
export function deleteMetaCollectTask(id: GovernanceMetaCollectTaskApi.Id) {
  return requestClient.delete('/governance/meta-collect-task/delete', {
    params: { id },
  });
}
export function deleteMetaCollectTaskList(
  ids: GovernanceMetaCollectTaskApi.Id[],
) {
  return requestClient.delete('/governance/meta-collect-task/delete-list', {
    params: { ids: ids.join(',') },
  });
}
export function updateMetaCollectTaskEnabled(
  id: GovernanceMetaCollectTaskApi.Id,
  enabled: number,
) {
  return requestClient.put(
    '/governance/meta-collect-task/update-enabled',
    undefined,
    { params: { id, enabled } },
  );
}
export function updateMetaCollectTaskScheduleEnabled(
  id: GovernanceMetaCollectTaskApi.Id,
  scheduleEnabled: number,
) {
  return requestClient.put(
    '/governance/meta-collect-task/update-schedule-enabled',
    undefined,
    { params: { id, scheduleEnabled } },
  );
}
export function runMetaCollectTask(id: GovernanceMetaCollectTaskApi.Id) {
  return requestClient.post('/governance/meta-collect-task/run', undefined, {
    params: { id },
  });
}
export function getMetaCollectSourceOptions() {
  return requestClient.get<GovernanceMetaCollectTaskApi.SourceOptions>(
    '/governance/meta-collect-task/source-options',
  );
}
export function getMetaCollectScopeList(
  datasourceId: GovernanceMetaCollectTaskApi.Id,
) {
  return requestClient.get<GovernanceMetaCollectTaskApi.Scope[]>(
    '/governance/meta-collect-task/scope-list',
    { params: { datasourceId } },
  );
}
