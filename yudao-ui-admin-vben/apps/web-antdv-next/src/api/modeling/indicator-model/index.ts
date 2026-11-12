import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace IndicatorModelApi {
  export type Id = number | string;
  export type Type = 'ATOMIC' | 'COMPOSITE' | 'DERIVED';
  export type Status = 'ACTIVE' | 'DRAFT';
  export type Aggregation =
    | 'AVG'
    | 'COUNT'
    | 'COUNT_DISTINCT'
    | 'MAX'
    | 'MIN'
    | 'SUM';
  export type Period = 'DAY' | 'MONTH' | 'QUARTER' | 'WEEK' | 'YEAR';
  export type ResultType =
    | 'BIGINT'
    | 'DATE'
    | 'DATETIME'
    | 'DECIMAL'
    | 'DOUBLE'
    | 'DOUBLE PRECISION'
    | 'FLOAT4'
    | 'FLOAT8'
    | 'FLOAT'
    | 'INT2'
    | 'INT4'
    | 'INT8'
    | 'INT'
    | 'INTEGER'
    | 'MONEY'
    | 'NUMBER'
    | 'NUMERIC'
    | 'REAL'
    | 'SMALLINT'
    | 'STRING'
    | 'TINYINT';
  export interface Save {
    id?: Id;
    expectedRevision?: Id;
    code: string;
    name: string;
    indicatorType: Type;
    dataDomainId: Id;
    subjectDomainId?: Id;
    entityId?: Id;
    status?: Status;
    ownerUserId?: Id;
    description?: string;
    definition?: string;
    unit?: string;
    dataFreshness?: string;
    resultDataType?: ResultType;
    sourceModelId?: Id;
    valueFieldUid?: string;
    timeFieldUid?: string;
    aggregation?: Aggregation;
    baseIndicatorId?: Id;
    timePeriod?: Period;
    businessCondition?: string;
    /** 复合指标公式，如 {gmv} / {order_cnt} * 100 */
    formula?: string;
  }
  export interface Model extends Save {
    id: Id;
    revision: Id;
    status: Status;
    sourceModelName?: string;
    sourceModelCode?: string;
    valueFieldName?: string;
    timeFieldName?: string;
    baseIndicatorName?: string;
    creator?: string;
    updater?: string;
    ownerUserName?: string;
    ownerUserPhone?: string;
    entityName?: string;
    creatorName?: string;
    creatorPhone?: string;
    updaterName?: string;
    updaterPhone?: string;
    derivedIndicatorCount?: number;
    compositeReferenceCount?: number;
    expression?: string;
    createTime?: number;
    updateTime?: number;
  }
  export interface PageReq extends PageParam {
    keyword?: string;
    indicatorType?: Type;
    status?: Status;
    dataDomainId?: Id;
    subjectDomainId?: Id;
    sourceModelId?: Id;
    baseIndicatorId?: Id;
  }
  export interface SourceField {
    fieldUid: string;
    fieldName: string;
    cnName?: string;
    dataType: string;
  }
  /** 启用指标轻量选项 */
  export interface Option {
    id: Id;
    code: string;
    name: string;
    indicatorType: Type;
    dataDomainId: Id;
    unit?: string;
    resultDataType?: ResultType;
    timeConfigured?: boolean;
    timePeriod?: Period;
    formula?: string;
  }
  export interface FormulaValidateReq {
    id?: Id;
    code?: string;
    dataDomainId: Id;
    formula: string;
  }
  export interface DependencyNode {
    id: Id;
    code: string;
    name: string;
    indicatorType: Type;
    status: Status;
    formula?: string;
    timePeriod?: Period;
    children: DependencyNode[];
  }
  export interface FormulaValidateResp {
    valid: boolean;
    message?: string;
    normalizedFormula?: string;
    references: string[];
    dependencies: DependencyNode[];
  }
  export interface QueryFilter {
    fieldUid: string;
    value: string;
  }
  export interface QueryReq {
    indicatorIds: Id[];
    dimensions?: string[];
    filters?: QueryFilter[];
    /** 含起止日期，格式 yyyy-MM-dd */
    timeRange?: { end: string; start: string };
    timeGrain?: Period;
    limit?: number;
  }
  export interface QueryMeta {
    dimensions: SourceField[];
    timeSupported: boolean;
    derivedPeriods: Period[];
  }
  export type QueryColumnKind = 'DIMENSION' | 'INDICATOR' | 'TIME';
  export interface QueryColumn {
    key: string;
    title: string;
    kind: QueryColumnKind;
    fieldUid?: string;
    indicatorId?: Id;
    indicatorCode?: string;
    unit?: string;
  }
  export interface QueryCondition {
    id: Id;
    code: string;
    name: string;
    expression: string;
  }
  export interface QuerySource {
    modelId: Id;
    materializedId: Id;
    datasourceId: Id;
    datasourceName?: string;
    schemaName?: string;
    tableName: string;
  }
  export interface LeafCaliber {
    indicatorId: Id;
    code: string;
    name: string;
    indicatorType: Type;
    aggregation: Aggregation;
    valueField: string;
    timeField?: string;
    timePeriod?: Period;
    conditions: QueryCondition[];
    source: QuerySource;
  }
  export interface Caliber {
    indicatorId: Id;
    code: string;
    name: string;
    indicatorType: Type;
    unit?: string;
    formula?: string;
    leaves: LeafCaliber[];
  }
  /** 指标值为后端输出的十进制字符串，前端不做数值换算。 */
  export interface QueryResult {
    columns: QueryColumn[];
    rows: Record<string, null | string>[];
    truncated: boolean;
    timeGrain?: Period;
    timeStart?: string;
    timeEnd?: string;
    calibers: Caliber[];
    /** 被动态脱敏的列（name 为行内列 key）；原值不会出现在 rows 中 */
    maskedColumns?: {
      categoryNames?: string[];
      name: string;
      reason?: string;
    }[];
  }
}

const path = '/modeling/indicator-model';
export const getIndicatorPage = (params: IndicatorModelApi.PageReq) =>
  requestClient.get<PageResult<IndicatorModelApi.Model>>(`${path}/page`, {
    params,
  });
export const getIndicator = (id: IndicatorModelApi.Id) =>
  requestClient.get<IndicatorModelApi.Model>(`${path}/get`, { params: { id } });
export const createIndicator = (data: IndicatorModelApi.Save) =>
  requestClient.post<IndicatorModelApi.Id>(`${path}/create`, data);
export const updateIndicator = (data: IndicatorModelApi.Save) =>
  requestClient.put<boolean>(`${path}/update`, data);
export const deleteIndicator = (
  id: IndicatorModelApi.Id,
  expectedRevision: IndicatorModelApi.Id,
) =>
  requestClient.delete<boolean>(`${path}/delete`, {
    params: { id, expectedRevision },
  });
export const updateIndicatorStatus = (data: {
  expectedRevision: IndicatorModelApi.Id;
  id: IndicatorModelApi.Id;
  status: IndicatorModelApi.Status;
}) => requestClient.put<boolean>(`${path}/update-status`, data);
export const getAtomicIndicatorOptions = () =>
  requestClient.get<IndicatorModelApi.Model[]>(`${path}/atomic-options`);
export const getIndicatorSourceFields = (modelId: IndicatorModelApi.Id) =>
  requestClient.get<IndicatorModelApi.SourceField[]>(`${path}/source-fields`, {
    params: { modelId },
  });
export const validateIndicatorFormula = (
  data: IndicatorModelApi.FormulaValidateReq,
) =>
  requestClient.post<IndicatorModelApi.FormulaValidateResp>(
    `${path}/validate-formula`,
    data,
  );
export const getActiveIndicatorOptions = (
  dataDomainId?: IndicatorModelApi.Id,
) =>
  requestClient.get<IndicatorModelApi.Option[]>(`${path}/active-options`, {
    params: { dataDomainId },
  });
export const getIndicatorQueryMeta = (indicatorIds: IndicatorModelApi.Id[]) =>
  requestClient.post<IndicatorModelApi.QueryMeta>(`${path}/query-meta`, {
    indicatorIds,
  });
export const queryIndicatorData = (data: IndicatorModelApi.QueryReq) =>
  requestClient.post<IndicatorModelApi.QueryResult>(`${path}/query`, data);
