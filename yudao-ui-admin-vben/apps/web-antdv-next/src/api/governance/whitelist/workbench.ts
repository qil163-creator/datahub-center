import type { PageParam, PageResult } from '@vben/request';

import type { SafetyCategoryWorkbenchApi } from '#/api/governance/category/workbench';

import { requestClient } from '#/api/request';

export namespace SafetyWhitelistWorkbenchApi {
  export type Id = number | string;
  export type Time = null | number | string;
  export interface User {
    id: Id;
    nickname: string;
    deptName?: string;
    username?: string;
    mobile?: string;
    deptId?: Id;
  }
  export interface Row {
    id: Id;
    sourceRecordId?: string;
    revision: Id;
    name: string;
    categoryId: Id;
    categoryName?: string;
    categorySourceRecordId?: string;
    effectiveCategory: '1';
    userIds: Id[];
    users: User[];
    startTime: string;
    endTime: string;
    status: 0 | 1;
    description?: string;
    creatorName?: string;
    createTime?: Time;
    updaterName?: string;
    updateTime?: Time;
    authorizationBasis?: string;
  }
  export interface Save {
    id?: Id;
    revision?: Id;
    name: string;
    categoryId: Id;
    effectiveCategory: '1';
    userIds: Id[];
    startTime: string;
    endTime: string;
    status: 0 | 1;
    description?: string;
  }
  export interface Version {
    id: Id;
    revision: Id;
  }
  export interface PageReq extends PageParam {
    name?: string;
    categoryId?: Id;
    status?: number;
    sortField?: 'createTime' | 'id';
    sortOrder?: 'asc' | 'desc';
  }
  export interface Options {
    directories: SafetyCategoryWorkbenchApi.Directory[];
    categories: (SafetyCategoryWorkbenchApi.Category & {
      whitelistConfigured: boolean;
    })[];
  }
  export interface UserPageReq extends PageParam {
    username?: string;
    mobile?: string;
  }
}
const prefix = '/governance/safety-whitelist/workbench';
export const getWhitelistWorkbenchPage = (
  params: SafetyWhitelistWorkbenchApi.PageReq,
) =>
  requestClient.get<PageResult<SafetyWhitelistWorkbenchApi.Row>>(
    `${prefix}/page`,
    { params },
  );
export const getWhitelistWorkbench = (id: SafetyWhitelistWorkbenchApi.Id) =>
  requestClient.get<SafetyWhitelistWorkbenchApi.Row>(`${prefix}/get`, {
    params: { id },
  });
export const getWhitelistWorkbenchOptions = () =>
  requestClient.get<SafetyWhitelistWorkbenchApi.Options>(`${prefix}/options`);
export const getWhitelistWorkbenchUsers = (
  params: SafetyWhitelistWorkbenchApi.UserPageReq,
) =>
  requestClient.get<PageResult<SafetyWhitelistWorkbenchApi.User>>(
    `${prefix}/user-options`,
    { params },
  );
export const saveWhitelistWorkbench = (
  data: SafetyWhitelistWorkbenchApi.Save,
) =>
  data.id === undefined
    ? requestClient.post<SafetyWhitelistWorkbenchApi.Id>(
        `${prefix}/create`,
        data,
      )
    : requestClient.put(`${prefix}/update`, data);
export const changeWhitelistWorkbenchStatus = (
  data: SafetyWhitelistWorkbenchApi.Version & { status: 0 | 1 },
) => requestClient.put(`${prefix}/change-status`, data);
export const deleteWhitelistWorkbench = (
  data: SafetyWhitelistWorkbenchApi.Version,
) => requestClient.post(`${prefix}/delete`, data);
export const deleteWhitelistWorkbenchList = (
  items: SafetyWhitelistWorkbenchApi.Version[],
) => requestClient.post(`${prefix}/delete-list`, { items });
