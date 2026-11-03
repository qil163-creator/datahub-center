import type { PageParam, PageResult } from '@vben/request';

import type { getStandardElementModelFields } from '#/api/governance/standardelement';

import { requestClient } from '#/api/request';

export namespace GovernanceStandardCodeSetApi {
  export type Id = number | string;
  export interface StandardCodeSet {
    id: Id;
    name?: string;
    type?: string;
    itemCount?: number;
    status?: number;
    categoryId?: Id;
    categoryName?: string;
    columnType?: string;
    dataLength?: number;
    dataScale?: number;
    standardType?: string;
    documentId?: Id;
    personCharge?: Id;
    personChargeName?: string;
    contactNumber?: string;
    description?: string;
    revision?: Id;
    remark?: string;
    creator?: string;
    creatorName?: string;
    createTime?: number | string;
    updater?: string;
    updaterName?: string;
    updateTime?: number | string;
    deletedTime?: number | string;
  }
  export interface Category {
    id: Id;
    parentId?: Id | null;
    categoryName?: string;
    sort?: number;
    children?: Category[];
  }
  export interface PageParams extends PageParam {
    documentId?: Id;
    categoryId?: Id;
    name?: string;
    type?: string;
    status?: number;
    sortField?: 'createTime' | 'id';
    sortOrder?: 'asc' | 'desc';
  }

  /** 码表/码值导入结果 */
  export interface ImportResult {
    createCodeSetTypes: string[]; // 新建的码表字典类型
    createItemKeys: string[]; // 新建的码值（字典类型=键值）
    updateItemKeys: string[]; // 更新的码值（字典类型=键值）
    failureReasons: Record<string, string>; // 失败原因
  }

}

/** 查询数据标准--标准码分页 */
export function getStandardCodeSetPage(
  params: GovernanceStandardCodeSetApi.PageParams,
) {
  return requestClient.get<
    PageResult<GovernanceStandardCodeSetApi.StandardCodeSet>
  >('/governance/standard-code-set/page', { params });
}

/** 查询数据标准--标准码详情 */
export function getStandardCodeSet(id: GovernanceStandardCodeSetApi.Id) {
  return requestClient.get<GovernanceStandardCodeSetApi.StandardCodeSet>(
    `/governance/standard-code-set/get?id=${id}`,
  );
}

/** 查询代码表经关联数据元绑定的当前模型字段。 */
export function getStandardCodeSetModelFields(id: GovernanceStandardCodeSetApi.Id) {
  return requestClient.get<Awaited<ReturnType<typeof getStandardElementModelFields>>>(
    '/governance/standard-code-set/model-fields',
    { params: { id } },
  );
}

/** 新增数据标准--标准码 */
export function createStandardCodeSet(
  data: GovernanceStandardCodeSetApi.StandardCodeSet,
) {
  return requestClient.post('/governance/standard-code-set/create', data);
}

/** 修改数据标准--标准码 */
export function updateStandardCodeSet(
  data: GovernanceStandardCodeSetApi.StandardCodeSet,
) {
  return requestClient.put('/governance/standard-code-set/update', data);
}

/** 删除数据标准--标准码 */
export function deleteStandardCodeSet(id: GovernanceStandardCodeSetApi.Id) {
  return requestClient.delete(`/governance/standard-code-set/delete?id=${id}`);
}

/** 批量删除数据标准--标准码 */
export function deleteStandardCodeSetList(
  ids: GovernanceStandardCodeSetApi.Id[],
) {
  return requestClient.delete(
    `/governance/standard-code-set/delete-list?ids=${ids.join(',')}`,
  );
}

/** 导出数据标准--标准码 */
export function exportStandardCodeSet(params: any) {
  return requestClient.download('/governance/standard-code-set/export-excel', {
    params,
  });
}

/** 下载码表/码值导入模板 */
export function downloadStandardCodeSetImportTemplate() {
  return requestClient.download(
    '/governance/standard-code-set/get-import-template',
  );
}

/** 导入码表/码值 */
export function importStandardCodeSet(file: File, updateSupport: boolean) {
  return requestClient.upload<GovernanceStandardCodeSetApi.ImportResult>(
    `/governance/standard-code-set/import?updateSupport=${updateSupport}`,
    { file },
  );
}

export function getStandardCodeSetCategories() {
  return requestClient.get<GovernanceStandardCodeSetApi.Category[]>(
    '/governance/standard-code-set/category-list',
  );
}

export function updateStandardCodeSetStatus(
  id: GovernanceStandardCodeSetApi.Id,
  status: number,
  revision: GovernanceStandardCodeSetApi.Id,
) {
  return requestClient.put<boolean>(
    '/governance/standard-code-set/update-status',
    undefined,
    { params: { id, status, revision } },
  );
}
