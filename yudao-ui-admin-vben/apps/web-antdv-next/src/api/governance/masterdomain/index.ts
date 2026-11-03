import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace GovernanceMasterDomainApi {
  export type Id = number | string;
  export interface Domain {
    id?: Id;
    code: string;
    name: string;
    leaderUserId?: Id | null;
    leaderUserName?: string;
    leaderPhone?: string;
    remark?: string;
    creator?: string;
    creatorName?: string;
    updater?: string;
    updaterName?: string;
    updateTime?: number;
    /** 历史域图标与排序，当前管理页面不编辑。 */
    icon?: string;
    description?: string;
    sort?: number;
    status: number;
    modelCount?: Id;
    createTime?: number;
  }
  export interface PageReq extends PageParam {
    name?: string;
    code?: string;
    status?: number;
  }
  export interface SaveReq {
    id?: Id;
    name: string;
    code: string;
    leaderUserId: Id;
    leaderPhone?: string;
    description?: string;
    remark?: string;
    status?: number;
  }
}

export function getMasterDomainPage(params: GovernanceMasterDomainApi.PageReq) {
  return requestClient.get<PageResult<GovernanceMasterDomainApi.Domain>>(
    '/governance/master-domain/page',
    { params },
  );
}
export function getMasterDomain(id: GovernanceMasterDomainApi.Id) {
  return requestClient.get<GovernanceMasterDomainApi.Domain>(
    '/governance/master-domain/get',
    { params: { id } },
  );
}
export function getMasterDomainSimpleList() {
  return requestClient.get<GovernanceMasterDomainApi.Domain[]>(
    '/governance/master-domain/simple-list',
  );
}
export function createMasterDomain(data: GovernanceMasterDomainApi.SaveReq) {
  return requestClient.post<GovernanceMasterDomainApi.Id>(
    '/governance/master-domain/create',
    data,
  );
}
export function updateMasterDomain(data: GovernanceMasterDomainApi.SaveReq) {
  return requestClient.put<boolean>('/governance/master-domain/update', data);
}
export function updateMasterDomainStatus(
  id: GovernanceMasterDomainApi.Id,
  status: number,
) {
  return requestClient.put<boolean>('/governance/master-domain/update-status', {
    id,
    status,
  });
}
export function deleteMasterDomain(id: GovernanceMasterDomainApi.Id) {
  return requestClient.delete<boolean>('/governance/master-domain/delete', {
    params: { id },
  });
}
export function deleteMasterDomainList(ids: GovernanceMasterDomainApi.Id[]) {
  return requestClient.delete<boolean>(
    '/governance/master-domain/delete-list',
    { params: { ids: ids.join(',') } },
  );
}
