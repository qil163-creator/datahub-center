import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace GovernanceMetaFileApi {
  export type Id = number | string;
  export interface MetaFile {
    id: Id;
    bizRevision: number;
    datasourceId?: Id;
    datasourceName?: string;
    sourceSystemId?: Id;
    sourceSystemName?: string;
    dbType?: string;
    host?: string;
    port?: number | string;
    username?: string;
    path?: string;
    parentPath?: string;
    name?: string;
    fileType?: string;
    size?: number | string;
    sourceModifiedTime?: number;
    firstSeenTime?: number;
    lastSeenTime?: number;
    objectStatus?: number;
    businessName?: string;
    description?: string;
    ownerUserId?: Id;
    ownerName?: string;
    ownerPhone?: string;
    techLeaderUserId?: Id;
    techLeaderName?: string;
    techLeaderPhone?: string;
    businessLeaderUserId?: Id;
    businessLeaderName?: string;
    businessLeaderPhone?: string;
    creatorName?: string;
    updaterName?: string;
    createTime?: number;
    updateTime?: number;
    taskId?: Id;
    instanceId?: Id;
  }
  export interface BizUpdate {
    id: Id;
    bizRevision: number;
    businessName?: string;
    description?: string;
    ownerUserId?: Id;
    techLeaderUserId?: Id;
    techLeaderPhone?: string;
    businessLeaderUserId?: Id;
    businessLeaderPhone?: string;
  }
  export interface TreeNode {
    id: string;
    name: string;
    type: 'connection' | 'directory' | 'system';
    dbType?: string;
    sourceSystemId?: Id | null;
    datasourceId?: Id;
    path?: string;
    children?: TreeNode[];
  }
}
export function getMetaFilePage(params: PageParam) {
  return requestClient.get<PageResult<GovernanceMetaFileApi.MetaFile>>(
    '/governance/meta-file/page',
    { params },
  );
}
export function getMetaFile(id: GovernanceMetaFileApi.Id) {
  return requestClient.get<GovernanceMetaFileApi.MetaFile>(
    '/governance/meta-file/get',
    { params: { id } },
  );
}
export function getMetaFileTree() {
  return requestClient.get<GovernanceMetaFileApi.TreeNode[]>(
    '/governance/meta-file/tree',
  );
}
export function updateMetaFileBiz(data: GovernanceMetaFileApi.BizUpdate) {
  return requestClient.put('/governance/meta-file/update-biz', data);
}
export function deleteMetaFileList(ids: GovernanceMetaFileApi.Id[]) {
  return requestClient.delete('/governance/meta-file/delete-list', {
    params: { ids: ids.join(',') },
  });
}
