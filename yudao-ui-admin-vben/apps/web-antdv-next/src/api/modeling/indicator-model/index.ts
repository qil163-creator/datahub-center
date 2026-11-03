import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace IndicatorModelApi {
  export type Id = number | string;
  export type Type = 'ATOMIC' | 'DERIVED';
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
