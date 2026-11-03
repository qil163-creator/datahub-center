import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace GovernanceStandardCodeItemApi {
  export type Id = number | string;
  /** 数据标准--码值 */
  export interface StandardCodeItem {
    children?: StandardCodeItem[];
    id?: Id;
    codeSetId?: Id;
    label: string;
    value: string;
    dictType: string;
    parentId?: Id;
    levelNo?: number;
    validFrom?: string;
    validTo?: string;
    sort?: number;
    status?: number;
    remark?: string;
    createTime?: number | string;
    updateTime?: number | string;
    creatorName?: string;
    updaterName?: string;
    revision?: Id;
  }
}

export type StandardCodeItemTreeParams = {
  businessTime?: string;
  codeSetId: GovernanceStandardCodeItemApi.Id;
  includeHistory?: boolean;
};

/** 查询数据标准--码值分页 */
export function getStandardCodeItemPage(
  params: PageParam & {
    codeSetId?: GovernanceStandardCodeItemApi.Id;
    sortField?: 'createTime' | 'id';
    sortOrder?: 'asc' | 'desc';
  },
) {
  return requestClient.get<
    PageResult<GovernanceStandardCodeItemApi.StandardCodeItem>
  >('/governance/standard-code-item/page', { params });
}

/** 查询数据标准--码值详情 */
export function getStandardCodeItem(id: GovernanceStandardCodeItemApi.Id) {
  return requestClient.get<GovernanceStandardCodeItemApi.StandardCodeItem>(
    `/governance/standard-code-item/get?id=${id}`,
  );
}

/** 查询指定码表的层级码值。 */
export function getStandardCodeItemTree(params: StandardCodeItemTreeParams) {
  return requestClient.get<GovernanceStandardCodeItemApi.StandardCodeItem[]>(
    '/governance/standard-code-item/tree',
    { params },
  );
}

/** 新增数据标准--码值 */
export function createStandardCodeItem(
  data: GovernanceStandardCodeItemApi.StandardCodeItem,
) {
  return requestClient.post('/governance/standard-code-item/create', data);
}

/** 修改数据标准--码值 */
export function updateStandardCodeItem(
  data: GovernanceStandardCodeItemApi.StandardCodeItem,
) {
  return requestClient.put('/governance/standard-code-item/update', data);
}

/** 删除数据标准--码值 */
export function deleteStandardCodeItem(id: GovernanceStandardCodeItemApi.Id) {
  return requestClient.delete(`/governance/standard-code-item/delete?id=${id}`);
}

/** 批量删除数据标准--码值 */
export function deleteStandardCodeItemList(
  ids: GovernanceStandardCodeItemApi.Id[],
) {
  return requestClient.delete(
    `/governance/standard-code-item/delete-list?ids=${ids.join(',')}`,
  );
}
