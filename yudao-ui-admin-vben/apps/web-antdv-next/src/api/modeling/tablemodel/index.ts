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
  /** 版本变化摘要：初始版本只有 initial；其余为相对上一版本的数量。 */
  export interface ChangeSummary {
    initial?: boolean;
    added?: number;
    removed?: number;
    modified?: number;
    headerChanged?: number;
  }
  export interface Version {
    id: Id;
    modelId: Id;
    versionNo: number;
    versionLabel: string;
    modelRevision?: Id;
    fieldCount?: number;
    changeSummary?: ChangeSummary;
    publishedBy?: Id;
    publishedByName?: string;
    /** 后端 LocalDateTime 默认输出毫秒时间戳 */
    publishedTime?: number | string;
  }
  export interface VersionDetail extends Version {
    modelSnapshot?: Record<string, unknown>;
    fields?: ModelingModelApi.ModelField[];
    typeConfig?: unknown;
  }
  export type FieldChangeType = 'ADDED' | 'MODIFIED' | 'REMOVED' | 'UNCHANGED';
  export interface AttributeChange {
    key: string;
    label: string;
    before?: string;
    after?: string;
  }
  export interface FieldChange {
    changeType: FieldChangeType;
    fieldUid?: string;
    fieldName?: string;
    cnName?: string;
    before?: ModelingModelApi.ModelField;
    after?: ModelingModelApi.ModelField;
    changes: AttributeChange[];
  }
  export interface Diff {
    fromLabel?: string;
    toLabel?: string;
    headerChanges: AttributeChange[];
    fields: FieldChange[];
    summary: {
      added: number;
      headerChanged: number;
      modified: number;
      removed: number;
      unchanged: number;
    };
  }
  export interface SourceSyncPreview {
    modelId: Id;
    revision: Id;
    status?: string;
    datasourceId?: Id;
    sourceTable?: string;
    diff: Diff;
  }
  export interface SourceSyncResult {
    saved: boolean;
    published: boolean;
    publishMessage?: string;
  }
  export type SqlDialect = 'DORIS' | 'MYSQL' | 'ORACLE' | 'POSTGRESQL';
  export interface SqlParseResult {
    tableName?: string;
    tableComment?: string;
    fields: ModelingModelApi.ModelField[];
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

/** 版本列表，最新在前；每次发布生成一个只读版本。 */
export const getTableModelVersions = (modelId: TableModelApi.Id) =>
  requestClient.get<TableModelApi.Version[]>(`${path}/version/list`, {
    params: { modelId },
  });
export const getTableModelVersion = (id: TableModelApi.Id) =>
  requestClient.get<TableModelApi.VersionDetail>(`${path}/version/get`, {
    params: { id },
  });
/** toVersionId 为空时与当前设计比较。 */
export const compareTableModelVersions = (params: {
  fromVersionId: TableModelApi.Id;
  modelId: TableModelApi.Id;
  toVersionId?: TableModelApi.Id;
}) =>
  requestClient.get<TableModelApi.Diff>(`${path}/version/compare`, { params });
/** 贴源表：源表当前结构与当前设计的差异，不写入任何数据。 */
export const previewSourceSync = (modelId: TableModelApi.Id) =>
  requestClient.get<TableModelApi.SourceSyncPreview>(
    `${path}/source-sync/preview`,
    { params: { modelId } },
  );
/** 贴源表：按预览时的修订号写回结构，原已发布的模型重新发布。 */
export const applySourceSync = (data: {
  expectedRevision: TableModelApi.Id;
  modelId: TableModelApi.Id;
}) =>
  requestClient.put<TableModelApi.SourceSyncResult>(
    `${path}/source-sync/apply`,
    data,
  );
/** 解析建表语句为字段，只返回结果，不写入设计。 */
export const parseSqlFields = (data: {
  dialect: TableModelApi.SqlDialect;
  sql: string;
}) =>
  requestClient.post<TableModelApi.SqlParseResult>(
    `${path}/sql-parse-fields`,
    data,
  );
