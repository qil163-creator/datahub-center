import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace SensitiveListApi {
  export type Id = number | string;
  export interface Record {
    id: Id;
    revision: Id;
    sourceRecordId?: string;
    assetId: Id;
    assetName?: string;
    assetDescription?: string;
    tableName?: string;
    columnId: Id;
    columnName?: string;
    categoryId: Id;
    classificationName?: string;
    levelName?: string;
    levelColor?: string;
    ruleName?: string;
    description?: string;
    status: 0 | 1;
    creatorName?: string;
    updaterName?: string;
    sourceCreatorName?: string;
    sourceUpdaterName?: string;
    createTime?: number | string;
    updateTime?: number | string;
  }
  export interface SaveRequest {
    id?: Id;
    revision?: Id;
    assetId: Id;
    columnId: Id;
    categoryId: Id;
    description?: string;
    status: 0 | 1;
  }
  export interface PageRequest extends PageParam {
    keyword?: string;
    categoryKey?: string;
    status?: 0 | 1;
    sortField?: 'createTime' | 'id';
    sortOrder?: 'asc' | 'desc';
  }
  export interface Category {
    key: string;
    parentKey?: string;
    type: 'CATEGORY' | 'DIRECTORY';
    categoryId?: Id;
    name: string;
    selectable: boolean;
    status: 0 | 1;
    levelName?: string;
    levelColor?: string;
    ruleName?: string;
  }
  export interface AssetOption {
    id: Id;
    assetName?: string;
    tableName?: string;
    description?: string;
    assetType?: number;
  }
  export interface ColumnOption {
    id: Id;
    columnName: string;
    columnComment?: string;
    dataType?: string;
  }
}
const base = '/asset/sensitive-list';
export function getSensitiveListPage(params: SensitiveListApi.PageRequest) {
  return requestClient.get<PageResult<SensitiveListApi.Record>>(
    `${base}/page`,
    { params },
  );
}
export function getSensitiveList(id: SensitiveListApi.Id) {
  return requestClient.get<SensitiveListApi.Record>(`${base}/get`, {
    params: { id },
  });
}
export function createSensitiveList(data: SensitiveListApi.SaveRequest) {
  return requestClient.post<SensitiveListApi.Id>(`${base}/create`, data);
}
export function updateSensitiveList(data: SensitiveListApi.SaveRequest) {
  return requestClient.put<boolean>(`${base}/update`, data);
}
export function changeSensitiveListStatus(data: {
  id: SensitiveListApi.Id;
  revision: SensitiveListApi.Id;
  status: 0 | 1;
}) {
  return requestClient.put<boolean>(`${base}/change-status`, data);
}
export function deleteSensitiveList(
  id: SensitiveListApi.Id,
  revision: SensitiveListApi.Id,
) {
  return requestClient.delete<boolean>(`${base}/delete`, {
    params: { id, revision },
  });
}
export function deleteSensitiveLists(
  items: { id: SensitiveListApi.Id; revision: SensitiveListApi.Id }[],
) {
  return requestClient.post<{
    deletedIds: SensitiveListApi.Id[];
    skippedIds: SensitiveListApi.Id[];
  }>(`${base}/delete-list`, { items });
}
export function getSensitiveListCategories() {
  return requestClient.get<SensitiveListApi.Category[]>(`${base}/categories`);
}
export function getSensitiveAssetOptions(
  params: PageParam & { keyword?: string },
) {
  return requestClient.get<PageResult<SensitiveListApi.AssetOption>>(
    `${base}/asset-options`,
    { params },
  );
}
export function getSensitiveColumnOptions(assetId: SensitiveListApi.Id) {
  return requestClient.get<SensitiveListApi.ColumnOption[]>(
    `${base}/column-options`,
    { params: { assetId } },
  );
}
