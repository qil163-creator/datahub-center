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
  export interface VersionSummary {
    initial?: boolean;
    attributeChanged?: number;
    relationAdded?: number;
    relationRemoved?: number;
  }
  export interface Version {
    id: Id;
    entityId: Id;
    versionNo: number;
    versionLabel: string;
    entityRevision?: Id;
    changeSummary?: VersionSummary;
    publishedBy?: Id;
    publishedByName?: string;
    /** 后端 LocalDateTime 默认输出毫秒时间戳 */
    publishedTime?: number | string;
  }
  export interface VersionRelation {
    relationType: string;
    entityId: Id;
    name?: string;
    code?: string;
  }
  export interface VersionDetail extends Version {
    snapshot?: Record<string, unknown> & { relations?: VersionRelation[] };
  }
  export interface AttributeChange {
    key: string;
    label: string;
    before?: string;
    after?: string;
  }
  export interface RelationChange {
    relationType: string;
    relationLabel: string;
    changeType: 'ADDED' | 'REMOVED';
    entityId?: Id;
    name?: string;
    code?: string;
  }
  export interface VersionCompare {
    fromLabel?: string;
    toLabel?: string;
    attributeChanges: AttributeChange[];
    relationChanges: RelationChange[];
  }
  export interface GraphNode {
    key: string;
    kind: 'ENTITY' | 'MODEL';
    refId: Id;
    name?: string;
    code?: string;
    entityType?: EntityType;
    subtype?: string;
    status?: string;
  }
  export interface GraphEdge {
    source: string;
    target: string;
    relationType: string;
    label: string;
  }
  export interface Graph {
    centerKey: string;
    nodes: GraphNode[];
    edges: GraphEdge[];
    modelsTruncated?: boolean;
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
/** 以实体为中心、一跳范围内的关系及用它创建的逻辑表。 */
export const getBusinessEntityGraph = (id: BusinessEntityApi.Id) =>
  requestClient.get<BusinessEntityApi.Graph>(
    '/modeling/business-entity/relation-graph',
    { params: { id } },
  );
/** 版本列表，最新在前；每次上线生成一个只读版本。 */
export const getBusinessEntityVersions = (entityId: BusinessEntityApi.Id) =>
  requestClient.get<BusinessEntityApi.Version[]>(
    '/modeling/business-entity/version/list',
    { params: { entityId } },
  );
export const getBusinessEntityVersion = (id: BusinessEntityApi.Id) =>
  requestClient.get<BusinessEntityApi.VersionDetail>(
    '/modeling/business-entity/version/get',
    { params: { id } },
  );
/** toVersionId 为空时与当前设计比较。 */
export const compareBusinessEntityVersions = (params: {
  entityId: BusinessEntityApi.Id;
  fromVersionId: BusinessEntityApi.Id;
  toVersionId?: BusinessEntityApi.Id;
}) =>
  requestClient.get<BusinessEntityApi.VersionCompare>(
    '/modeling/business-entity/version/compare',
    { params },
  );
