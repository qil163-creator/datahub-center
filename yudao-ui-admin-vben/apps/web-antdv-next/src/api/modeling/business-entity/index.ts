import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace BusinessEntityApi {
  export type Id = number | string;
  export type EntityType = 'ACTIVITY' | 'OBJECT';
  export type EntitySubtype = 'EVENT' | 'HIERARCHICAL' | 'NORMAL' | 'PROCESS';
  export type Status = 0 | 1;
  export interface Summary {
    id: Id;
    name: string;
    code: string;
    subjectDomainId: Id;
    entityType: EntityType;
    entitySubtype: EntitySubtype;
    status: Status;
  }
  export interface SaveReq {
    dataDomainId: Id;
    subjectDomainId: Id;
    name: string;
    code: string;
    entityType: EntityType;
    entitySubtype: EntitySubtype;
    leaderUserId?: Id;
    description: string;
    inheritEntityId?: Id;
    associatedEntityIds: Id[];
    predecessorEntityIds: Id[];
    processEventIds: Id[];
  }
  export interface UpdateReq extends SaveReq {
    id: Id;
    expectedRevision: Id;
  }
  export interface Entity extends SaveReq {
    id: Id;
    children?: Entity[];
    dataDomainName?: string;
    subjectDomainName?: string;
    subjectDomainCode?: string;
    leaderName?: string;
    leaderPhone?: string;
    status: Status;
    revision: Id;
    creator?: string;
    creatorName?: string;
    creatorPhone?: string;
    updaterName?: string;
    createTime?: number | string;
    updateTime?: number | string;
    inheritedEntity?: Summary;
    associatedEntities?: Summary[];
    predecessorEntities?: Summary[];
    processEvents?: Summary[];
    logicalModelCount?: null | number;
  }
  export interface PageReq extends PageParam {
    sortOrder?: 'asc' | 'desc';
    dataDomainId?: Id;
    subjectDomainId?: Id;
    keyword?: string;
    entityType?: EntityType;
    entitySubtype?: EntitySubtype;
    entitySubtypes?: EntitySubtype[];
    status?: Status;
    leaderUserId?: Id;
  }
  export interface Options {
    entities: Summary[];
    unavailableProcessEventIds: Id[];
    unavailablePredecessorEntityIds: Id[];
  }
  export interface Target {
    id: Id;
    expectedRevision: Id;
  }
}

export const getBusinessEntityPage = (params: BusinessEntityApi.PageReq) =>
  requestClient.get<PageResult<BusinessEntityApi.Entity>>(
    '/modeling/business-entity/page',
    { params },
  );
export const getBusinessEntityTreePage = (params: BusinessEntityApi.PageReq) =>
  requestClient.get<PageResult<BusinessEntityApi.Entity>>(
    '/modeling/business-entity/tree-page',
    { params },
  );
export const getBusinessEntity = (id: BusinessEntityApi.Id) =>
  requestClient.get<BusinessEntityApi.Entity>('/modeling/business-entity/get', {
    params: { id },
  });
export const getBusinessEntityOptions = (
  dataDomainId: BusinessEntityApi.Id,
  excludeId?: BusinessEntityApi.Id,
) =>
  requestClient.get<BusinessEntityApi.Options>(
    '/modeling/business-entity/options',
    { params: { dataDomainId, excludeId } },
  );
export const createBusinessEntity = (data: BusinessEntityApi.SaveReq) =>
  requestClient.post<BusinessEntityApi.Id>(
    '/modeling/business-entity/create',
    data,
  );
export const updateBusinessEntity = (data: BusinessEntityApi.UpdateReq) =>
  requestClient.put<boolean>('/modeling/business-entity/update', data);
export const updateBusinessEntityStatus = (
  data: BusinessEntityApi.Target & { status: BusinessEntityApi.Status },
) =>
  requestClient.put<boolean>('/modeling/business-entity/update-status', data);
export const updateBusinessEntityStatusBatch = (
  targets: BusinessEntityApi.Target[],
  status: BusinessEntityApi.Status,
) =>
  requestClient.put<boolean>('/modeling/business-entity/update-status-batch', {
    targets,
    status,
  });
export const deleteBusinessEntity = (
  id: BusinessEntityApi.Id,
  expectedRevision: BusinessEntityApi.Id,
) =>
  requestClient.delete<boolean>('/modeling/business-entity/delete', {
    params: { id, expectedRevision },
  });
export const deleteBusinessEntityList = (targets: BusinessEntityApi.Target[]) =>
  requestClient.delete<boolean>('/modeling/business-entity/delete-list', {
    data: { targets },
  });
