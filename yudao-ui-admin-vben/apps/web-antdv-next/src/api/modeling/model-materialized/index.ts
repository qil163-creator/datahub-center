import type { PageParam, PageResult } from '@vben/request';

import type { ModelingModelApi } from '#/api/modeling/model';

import { requestClient } from '#/api/request';

export namespace ModelMaterializedApi {
  export interface Materialized {
    /** 物化记录 id；未发布为空 */
    id?: number | string;
    /** 同 id */
    materializedId?: number | string;
    /** 逻辑模型 id（列表主键） */
    modelId: number | string;
    datasourceId?: number | string;
    designRevision?: number | string;
    attemptId?: null | number | string;
    datasourceType?: string;
    datasourceName?: string;
    modelName?: string;
    modelAlias?: string;
    schemaName?: string;
    tableName?: string;
    publishMode?: string;
    /** UNPUBLISHED / SUCCESS / FAIL / RUNNING / UNKNOWN */
    publishStatus?: string;
    /** 同 publishStatus，兼容旧字段 */
    status?: string;
    ddlSql?: string;
    message?: string;
    description?: string;
    remark?: string;
    tableType?: string;
    layerId?: number | string;
    layerCode?: string;
    layerName?: string;
    bizCategoryId?: number | string;
    bizCategoryName?: string;
    dataDomainId?: number | string;
    dataDomainName?: string;
    subjectDomainId?: number | string;
    subjectDomainName?: string;
    hierarchyPath?: string;
    creator?: string;
    creatorName?: string;
    creatorPhone?: string;
    updater?: string;
    updaterName?: string;
    updaterPhone?: string;
    publisher?: string;
    publisherName?: string;
    publisherPhone?: string;
    publishTime?: number;
    publishStartTime?: number;
    publishDuration?: number;
    fields?: ModelingModelApi.ModelField[];
    tableCase?: number;
    tableNameNotation?: string;
    documentType?: string;
    documentId?: number | string;
    contactNumber?: string;
    createTime?: number;
    updateTime?: number;
  }

  export interface LogItem {
    id: number | string;
    materializedId?: number | string;
    modelId?: number | string;
    publishMode?: string;
    status?: string;
    ddlSql?: string;
    message?: string;
    operator?: string;
    creatorName?: string;
    modelName?: string;
    modelAlias?: string;
    datasourceId?: number | string;
    datasourceName?: string;
    datasourceType?: string;
    description?: string;
    startTime?: number;
    endTime?: number;
    createTime?: number;
  }

  export interface PageReq extends PageParam {
    modelId?: number | string;
    name?: string;
    tableType?: string;
    code?: string;
    tableName?: string;
    layerId?: number | string;
    layerIds?: Array<number | string>;
    bizCategoryId?: number | string;
    dataDomainId?: number | string;
    subjectDomainId?: number | string;
    datasourceId?: number | string;
    publishStatus?: string;
    /** @deprecated 使用 publishStatus */
    status?: string;
    publishMode?: string;
    sourceSystemId?: number | string;
    sortField?: 'id' | 'publishTime';
    sortOrder?: 'asc' | 'desc';
  }

  export interface PublishReq {
    modelId: number | string;
    datasourceId: number | string;
    publishMode: 'INCREMENT' | 'REBUILD' | string;
    description?: string;
    remark?: string;
  }

  export interface RepublishReq {
    id: number | string;
    publishMode: 'INCREMENT' | 'REBUILD' | string;
    description?: string;
    remark?: string;
  }

  export type RecoveryStatus = 'FAIL' | 'SUCCESS' | 'UNKNOWN';

  export interface Database {
    id: number | string;
    name?: string;
    databaseType?: number | string;
    databaseTypeName?: string;
    categoryCode?: string;
    productType?: string;
    iconPath?: string;
    databaseAddress?: string;
    schema?: string;
  }

  export interface CatNode {
    key: string;
    name: string;
    type: string;
    children?: CatNode[];
    layerId?: number | string;
    layerIds?: Array<number | string>;
    sourceSystemId?: number | string;
    dataDomainId?: number | string;
    subjectDomainId?: number | string;
    tableType?: string;
  }
}

export const getModelMaterializedPage = (
  params: ModelMaterializedApi.PageReq,
) =>
  requestClient.get<PageResult<ModelMaterializedApi.Materialized>>(
    '/modeling/model-materialized/page',
    { params },
  );

export const getModelMaterialized = (id: number | string) =>
  requestClient.get<ModelMaterializedApi.Materialized>(
    '/modeling/model-materialized/get',
    { params: { id } },
  );

export const getModelMaterializedByModel = (modelId: number | string) =>
  requestClient.get<ModelMaterializedApi.Materialized>(
    '/modeling/model-materialized/get',
    { params: { modelId } },
  );

export const getModelMaterializedDatabases = () =>
  requestClient.get<ModelMaterializedApi.Database[]>(
    '/modeling/model-materialized/database-list',
  );

export const getModelMaterializedCatTree = () =>
  requestClient.get<ModelMaterializedApi.CatNode[]>(
    '/modeling/model-materialized/cat-tree',
  );

export const getModelMaterializedLogPage = (
  params: PageParam & {
    materializedId?: number | string;
    modelId?: number | string;
    status?: string;
  },
) =>
  requestClient.get<PageResult<ModelMaterializedApi.LogItem>>(
    '/modeling/model-materialized/log-page',
    { params },
  );

export const publishModelMaterialized = (
  data: ModelMaterializedApi.PublishReq,
) =>
  requestClient.post<number | string>(
    '/modeling/model-materialized/publish',
    data,
  );

export const republishModelMaterialized = (
  data: ModelMaterializedApi.RepublishReq,
) =>
  requestClient.post<number | string>(
    '/modeling/model-materialized/republish',
    data,
  );

export const reconcileModelMaterialized = (id: number | string) =>
  requestClient.post<ModelMaterializedApi.RecoveryStatus>(
    '/modeling/model-materialized/reconcile',
    undefined,
    { params: { id } },
  );
