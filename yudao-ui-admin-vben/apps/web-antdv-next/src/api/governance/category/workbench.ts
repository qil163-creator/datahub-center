import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace SafetyCategoryWorkbenchApi {
  export type Id = number | string;
  export type Time = null | number | string;
  export interface Directory {
    id: Id;
    revision?: Id;
    parentId?: Id | null;
    categoryName?: string;
    sort?: number;
    status?: number;
    remark?: string;
    categoryCode?: string;
    children?: Directory[];
  }
  export interface Level {
    id: Id;
    safetyLevel?: string;
    safetyName?: string;
    color?: string;
    status?: number;
    sensitiveLevel?: number;
  }
  export interface Option {
    value: string;
    label: string;
  }
  export interface Options {
    directories: Directory[];
    levels: Level[];
    priorities: Option[];
  }
  export interface Category {
    id: Id;
    revision?: Id;
    sourceRecordId?: string;
    categoryId?: Id;
    categoryName?: string;
    name?: string;
    shortName?: string;
    dataLevelId?: Id;
    safetyLevel?: string;
    levelName?: string;
    levelDisplayName?: string;
    levelColor?: string;
    priority?: string;
    ruleId?: Id | null;
    configured?: boolean;
    ruleName?: string;
    status?: number;
    sort?: number;
    description?: string;
    remark?: string;
    creatorName?: string;
    updaterName?: string;
    createTime?: Time;
    updateTime?: Time;
  }
  export interface PageReq extends PageParam {
    categoryIds?: Id[];
    name?: string;
    dataLevelId?: Id;
    status?: number;
    sortField?: 'createTime' | 'id';
    sortOrder?: 'asc' | 'desc';
  }
  export interface MutationItem {
    id: Id;
    revision: Id;
  }
  export interface Save {
    id?: Id;
    revision?: Id;
    categoryId: Id;
    name: string;
    shortName?: string;
    dataLevelId: Id;
    priority: string;
    status: number;
    description?: string;
  }
  export interface Mask {
    id: Id;
    revision: Id;
    ruleId?: Id;
    ruleVersionId?: Id;
    ruleRevisionToken?: string;
    ruleName: string;
    applicationScene: string[];
    maskType: '2';
    replaceRule: '1';
    replaceContent: string;
    intervalList: { endNum: null | number; startNum: null | number }[];
    status: number;
    description?: string;
  }
  export interface LevelSave {
    safetyName: string;
    safetyLevel: string;
    sensitiveLevel: number;
    status: number;
    description?: string;
  }
}

export function getCategoryWorkbenchPage(
  params: SafetyCategoryWorkbenchApi.PageReq,
) {
  return requestClient.get<PageResult<SafetyCategoryWorkbenchApi.Category>>(
    '/governance/safety-category/page',
    { params },
  );
}
export function getCategoryWorkbench(id: SafetyCategoryWorkbenchApi.Id) {
  return requestClient.get<SafetyCategoryWorkbenchApi.Category>(
    '/governance/safety-category/get',
    { params: { id } },
  );
}
export function getCategoryWorkbenchOptions() {
  return requestClient.get<SafetyCategoryWorkbenchApi.Options>(
    '/governance/safety-category/options',
  );
}
export function saveCategoryWorkbench(data: SafetyCategoryWorkbenchApi.Save) {
  return data.id === undefined
    ? requestClient.post<SafetyCategoryWorkbenchApi.Id>(
        '/governance/safety-category/create',
        data,
      )
    : requestClient.put('/governance/safety-category/update', data);
}
export function changeCategoryWorkbenchStatus(
  data: SafetyCategoryWorkbenchApi.MutationItem & { status: number },
) {
  return requestClient.put('/governance/safety-category/change-status', data);
}
export function deleteCategoryWorkbench(
  data: SafetyCategoryWorkbenchApi.MutationItem,
) {
  return requestClient.delete('/governance/safety-category/delete', {
    params: data,
  });
}
export function deleteCategoryWorkbenchList(
  items: SafetyCategoryWorkbenchApi.MutationItem[],
) {
  return requestClient.post('/governance/safety-category/delete-list', {
    items,
  });
}
export function setCategoryWorkbenchLevel(
  items: SafetyCategoryWorkbenchApi.MutationItem[],
  dataLevelId: SafetyCategoryWorkbenchApi.Id,
) {
  return requestClient.put('/governance/safety-category/batch-level', {
    items,
    dataLevelId,
  });
}
export function getCategoryWorkbenchMask(id: SafetyCategoryWorkbenchApi.Id) {
  return requestClient.get<null | SafetyCategoryWorkbenchApi.Mask>(
    '/governance/safety-category/mask',
    { params: { id } },
  );
}
export function saveCategoryWorkbenchMask(
  data: Omit<SafetyCategoryWorkbenchApi.Mask, 'ruleId'>,
) {
  return requestClient.put('/governance/safety-category/mask', data);
}
export function createCategoryWorkbenchLevel(
  data: SafetyCategoryWorkbenchApi.LevelSave,
) {
  return requestClient.post<SafetyCategoryWorkbenchApi.Id>(
    '/governance/safety-level/create',
    data,
  );
}
export function getCategoryWorkbenchDirectory(
  id: SafetyCategoryWorkbenchApi.Id,
) {
  return requestClient.get<SafetyCategoryWorkbenchApi.Directory>(
    '/governance/category-data/get',
    { params: { id } },
  );
}
export function saveCategoryWorkbenchDirectory(
  data: Omit<SafetyCategoryWorkbenchApi.Directory, 'id'> & {
    id?: SafetyCategoryWorkbenchApi.Id;
  },
) {
  return data.id === undefined
    ? requestClient.post('/governance/category-data/create', data)
    : requestClient.put('/governance/category-data/update', data);
}
export function deleteCategoryWorkbenchDirectory(
  id: SafetyCategoryWorkbenchApi.Id,
  revision?: SafetyCategoryWorkbenchApi.Id,
) {
  return requestClient.delete('/governance/category-data/delete', {
    params: { id, revision },
  });
}
