import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace BusinessConditionApi {
  export type Id = number | string;
  export type Status = 'ACTIVE' | 'DRAFT';
  export type ConfigType = 'BASIC' | 'COMPOSITE';
  export type LogicMode = 'SEGMENTED' | 'UNIFIED';
  export interface Save {
    id?: Id;
    expectedRevision?: Id;
    code: string;
    name: string;
    dataDomainId: Id;
    subjectDomainId?: Id;
    entityId?: Id;
    status?: Status;
    ownerUserId?: Id;
    description?: string;
    businessEntity?: string;
    dataFreshness?: string;
    sourceModelId: Id;
    configType: ConfigType;
    logicMode: LogicMode;
    expression: string;
  }
  export interface Model extends Save {
    id: Id;
    revision: Id;
    status: Status;
    createTime?: number;
    updateTime?: number;
    creator?: string;
    updater?: string;
    sourceModelName?: string;
    sourceModelCode?: string;
    ownerUserName?: string;
    ownerUserPhone?: string;
    entityName?: string;
    creatorName?: string;
    creatorPhone?: string;
    updaterName?: string;
    updaterPhone?: string;
    derivedIndicatorCount?: number;
  }
  export interface PageReq extends PageParam {
    keyword?: string;
    status?: Status;
    dataDomainId?: Id;
    subjectDomainId?: Id;
    sourceModelId?: Id;
    configType?: ConfigType;
    dataFreshness?: string;
  }
  export interface SourceField {
    fieldUid: string;
    fieldName: string;
    cnName?: string;
    dataType: string;
  }
}

const path = '/modeling/business-condition';
export const getBusinessConditionPage = (
  params: BusinessConditionApi.PageReq,
) =>
  requestClient.get<PageResult<BusinessConditionApi.Model>>(`${path}/page`, {
    params,
  });
export const getBusinessCondition = (id: BusinessConditionApi.Id) =>
  requestClient.get<BusinessConditionApi.Model>(`${path}/get`, {
    params: { id },
  });
export const createBusinessCondition = (data: BusinessConditionApi.Save) =>
  requestClient.post<BusinessConditionApi.Id>(`${path}/create`, data);
export const updateBusinessCondition = (data: BusinessConditionApi.Save) =>
  requestClient.put<boolean>(`${path}/update`, data);
export const deleteBusinessCondition = (
  id: BusinessConditionApi.Id,
  expectedRevision: BusinessConditionApi.Id,
) =>
  requestClient.delete<boolean>(`${path}/delete`, {
    params: { id, expectedRevision },
  });
export const updateBusinessConditionStatus = (data: {
  expectedRevision: BusinessConditionApi.Id;
  id: BusinessConditionApi.Id;
  status: BusinessConditionApi.Status;
}) => requestClient.put<boolean>(`${path}/update-status`, data);
export const getBusinessConditionSourceFields = (
  modelId: BusinessConditionApi.Id,
) =>
  requestClient.get<BusinessConditionApi.SourceField[]>(
    `${path}/source-fields`,
    { params: { modelId } },
  );
