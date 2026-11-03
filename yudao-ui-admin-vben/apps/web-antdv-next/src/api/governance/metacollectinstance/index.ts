import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace GovernanceMetaCollectInstanceApi {
  export type Id = number | string;
  export interface CollectInstance {
    id?: Id;
    taskId?: Id;
    taskName?: string;
    description?: string;
    collectType?: 1 | 2;
    datasourceId?: Id;
    datasourceName?: string;
    dbType?: string;
    sourceSystemId?: Id;
    sourceSystemName?: string;
    ownerName?: string;
    ownerPhone?: string;
    creatorName?: string;
    creatorPhone?: string;
    triggerType?: number;
    status?: number;
    stage?: string;
    errorType?: string;
    collectCursor?: string;
    dbCount?: number;
    tableCount?: number;
    fileCount?: number;
    columnCount?: number;
    tableAdd?: number;
    tableUpdate?: number;
    tableDelete?: number;
    columnAdd?: number;
    columnUpdate?: number;
    columnDelete?: number;
    failDetail?: string;
    startTime?: number;
    endTime?: number;
    durationMs?: number;
    report?: string;
    createTime?: number;
  }
  export interface CollectLog {
    id?: Id;
    instanceId?: Id;
    logTime?: number;
    logLevel?: string;
    stage?: string;
    objectRef?: string;
    content?: string;
  }
}
export function getMetaCollectInstancePage(params: PageParam) {
  return requestClient.get<
    PageResult<GovernanceMetaCollectInstanceApi.CollectInstance>
  >('/governance/meta-collect-instance/page', { params });
}
export function getMetaCollectInstance(
  id: GovernanceMetaCollectInstanceApi.Id,
) {
  return requestClient.get<GovernanceMetaCollectInstanceApi.CollectInstance>(
    '/governance/meta-collect-instance/get',
    { params: { id } },
  );
}
export function getMetaCollectInstanceLogList(
  instanceId: GovernanceMetaCollectInstanceApi.Id,
) {
  return requestClient.get<GovernanceMetaCollectInstanceApi.CollectLog[]>(
    '/governance/meta-collect-instance/log-list',
    { params: { instanceId } },
  );
}
export function deleteMetaCollectInstanceList(
  ids: GovernanceMetaCollectInstanceApi.Id[],
) {
  return requestClient.delete('/governance/meta-collect-instance/delete-list', {
    params: { ids: ids.join(',') },
  });
}
export function downloadMetaCollectInstanceLog(
  instanceId: GovernanceMetaCollectInstanceApi.Id,
) {
  return requestClient.download(
    '/governance/meta-collect-instance/log-download',
    { params: { instanceId } },
  );
}
