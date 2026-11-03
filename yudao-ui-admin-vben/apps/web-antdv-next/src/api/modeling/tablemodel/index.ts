import type { PageParam, PageResult } from '@vben/request';

import type { ModelingModelApi } from '../model';

import { requestClient } from '#/api/request';

export namespace TableModelApi {
  export type Id = number | string;
  export interface Model extends ModelingModelApi.LogicModel {
    id: Id;
    revision: Id;
    layoutRevision: Id;
    grain?: string;
    expectedRevision?: Id;
  }
  export interface PageReq extends PageParam {
    documentId?: Id;
    name?: string;
    code?: string;
    tableType?: string;
    layerId?: Id;
    dataDomainId?: Id;
    bizCategoryId?: Id;
    status?: string;
  }
  export interface Save {
    id?: Id;
    name?: string;
    code?: string;
    grain?: string;
    description?: string;
    expectedRevision?: Id;
    dataDomainId?: Id;
    layerId?: Id;
    subjectDomainId?: Id;
    bizCategoryId?: Id;
    tableType?: string;
    modelStyle?: string;
    createMode?: string;
    databaseId?: Id;
    tableName?: string;
    tableCase?: number;
    documentType?: string;
    documentId?: Id;
    contactUserId?: Id;
    contactNumber?: string;
    enabled?: number;
    remark?: string;
    fields?: ModelingModelApi.ModelField[];
    typeConfig?: ModelingModelApi.ModelTypeConfig | null;
    tableNameNotation?: string;
    sourceSystemId?: Id;
    datasourceId?: Id;
    dbType?: string;
    dbName?: string;
    schemaName?: string;
    entityId?: Id;
    objType?: string;
    businessType?: string;
    dataTimeliness?: string;
    statisticsStr?: string;
    grainEntityIds?: Id[];
  }
  export interface Draft extends Save {
    modelId: Id;
    expectedRevision: Id;
    fields: ModelingModelApi.ModelField[];
    typeConfig: ModelingModelApi.ModelTypeConfig | null;
  }
  export interface Design {
    modelId: Id;
    revision: Id;
    status: string;
    fields: ModelingModelApi.ModelField[];
    typeConfig?: ModelingModelApi.ModelTypeConfig | null;
  }
  export interface Relation extends ModelingModelApi.LogicRelation {
    revision?: Id;
    expectedRevision?: Id;
    sourceRevision?: Id;
    targetRevision?: Id;
    fieldPairs?: { sourceFieldUid: string; targetFieldUid: string }[];
  }
  export interface ErNode extends ModelingModelApi.LogicErNode {
    revision: Id;
    layoutRevision: Id;
  }
  export interface Diagram {
    layerId?: Id;
    layerName?: string;
    models?: ErNode[];
    relations?: Relation[];
  }
}

/** 仅提交可写设计头；部署目标、展示字段和状态由服务端维护。 */
export function modelSaveData(
  model: Partial<TableModelApi.Model>,
): TableModelApi.Save {
  return {
    id: model.id,
    name: model.name,
    code: model.code,
    grain: model.grain,
    description: model.description,
    expectedRevision: model.revision ?? model.expectedRevision,
    dataDomainId: model.dataDomainId,
    layerId: model.layerId,
    subjectDomainId: model.subjectDomainId,
    bizCategoryId: model.bizCategoryId,
    tableType: model.tableType,
    modelStyle: model.modelStyle,
    createMode: model.createMode,
    databaseId: model.databaseId,
    tableName: model.tableName,
    tableCase: model.tableCase,
    documentType: model.documentType,
    documentId: model.documentId,
    contactUserId: model.contactUserId,
    contactNumber: model.contactNumber,
    enabled: model.enabled,
    remark: model.remark,
    fields: model.fields,
    tableNameNotation: model.tableNameNotation,
    sourceSystemId: model.sourceSystemId,
    datasourceId: model.datasourceId,
    dbType: model.dbType,
    dbName: model.dbName,
    schemaName: model.schemaName,
    entityId: model.entityId,
    objType: model.objType,
    businessType: model.businessType,
    dataTimeliness: model.dataTimeliness,
    statisticsStr: model.statisticsStr,
    grainEntityIds: model.grainEntityIds,
  };
}

const path = '/modeling/table-model';
export const getTableModelPage = (
  params: TableModelApi.PageReq,
  signal?: AbortSignal,
) =>
  requestClient.get<PageResult<TableModelApi.Model>>(`${path}/page`, {
    params,
    signal,
  });
export const getTableModel = (id: TableModelApi.Id) =>
  requestClient.get<TableModelApi.Model>(`${path}/get`, { params: { id } });
export const createTableModel = (data: TableModelApi.Save) =>
  requestClient.post<TableModelApi.Id>(`${path}/create`, data);
export const updateTableModel = (data: TableModelApi.Save) =>
  requestClient.put<boolean>(`${path}/update`, data);
export const updateTableModelEnabled = (
  id: TableModelApi.Id,
  enabled: number,
  expectedRevision: TableModelApi.Id,
) =>
  requestClient.put<boolean>(`${path}/update-enabled`, null, {
    params: { id, enabled, expectedRevision },
  });
export const deleteTableModel = (
  id: TableModelApi.Id,
  expectedRevision: TableModelApi.Id,
) =>
  requestClient.delete<boolean>(`${path}/delete`, {
    params: { id, expectedRevision },
  });
export const getTableModelDesign = (modelId: TableModelApi.Id) =>
  requestClient.get<TableModelApi.Design>(`${path}/design-get`, {
    params: { modelId },
  });
export const saveTableModelDraft = (data: TableModelApi.Draft) =>
  requestClient.put<boolean>(`${path}/draft-save`, data);
export const publishTableModel = (
  modelId: TableModelApi.Id,
  expectedRevision: TableModelApi.Id,
) =>
  requestClient.put<boolean>(`${path}/publish`, null, {
    params: { modelId, expectedRevision },
  });
export const discardTableModel = (id: TableModelApi.Id) =>
  requestClient.put<boolean>(`${path}/discard`, null, { params: { id } });
export const getTableModelDiagram = (params: { layerId?: TableModelApi.Id }) =>
  requestClient.get<TableModelApi.Diagram>(`${path}/er-diagram`, { params });
export const saveTableModelRelation = (data: TableModelApi.Relation) =>
  requestClient.post<TableModelApi.Id>(`${path}/relation-save`, data);
export const deleteTableModelRelation = (
  id: TableModelApi.Id,
  expectedRevision: TableModelApi.Id,
) =>
  requestClient.delete<boolean>(`${path}/relation-delete`, {
    params: { id, expectedRevision },
  });
export const saveTableModelLayout = (data: {
  positions: {
    expectedLayoutRevision: TableModelApi.Id;
    modelId: TableModelApi.Id;
    posX: number;
    posY: number;
  }[];
}) => requestClient.put<boolean>(`${path}/er-layout-save`, data);

/** 预览与实际保存共用服务端汇总字段规则。 */
export const previewSummaryTableFields = (data: {
  dataDomainId: TableModelApi.Id;
  dataTimeliness?: string;
  grainEntityIds: TableModelApi.Id[];
  subjectDomainId: TableModelApi.Id;
}) =>
  requestClient.post<ModelingModelApi.ModelField[]>(
    `${path}/summary-preview`,
    data,
  );
