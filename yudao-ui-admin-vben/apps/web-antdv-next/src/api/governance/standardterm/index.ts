import type { PageResult } from '@vben/request';

import type { GovernanceCategoryTermApi } from '#/api/governance/categoryterm';

import { requestClient } from '#/api/request';

export namespace GovernanceStandardTermApi {
  export interface PageParams {
    pageNo: number;
    pageSize: number;
    name?: string;
    engName?: string;
    categoryId?: number | string;
    termCategoryId?: number | string;
    ownerUserId?: number | string;
    status?: number;
  }

  /** 业务术语信息 */
  export interface StandardTerm {
    id?: number | string; // 主键
    name: string; // 术语名称
    engName?: string; // 英文名称
    alias?: string; // 别名/同义词
    definition: string; // 业务定义
    categoryId?: number | string; // 数据元类目
    termCategoryId?: null | number | string; // 术语类目
    relatedElementId?: number | string; // 关联标准数据元
    ownerUserId?: number | string; // 负责人
    stewardUserId?: number | string; // 数据管家
    status?: number; // 状态
    remark?: string; // 备注
    createTime?: number | string; // 创建时间
  }
}

/** 查询业务术语可用的术语类目 */
export function getStandardTermCategories() {
  return requestClient.get<GovernanceCategoryTermApi.Category[]>(
    '/governance/standard-term/category-list',
  );
}

/** 查询业务术语分页 */
export function getStandardTermPage(params: GovernanceStandardTermApi.PageParams) {
  return requestClient.get<PageResult<GovernanceStandardTermApi.StandardTerm>>(
    '/governance/standard-term/page',
    { params },
  );
}

/** 查询业务术语详情 */
export function getStandardTerm(id: number | string) {
  return requestClient.get<GovernanceStandardTermApi.StandardTerm>(
    `/governance/standard-term/get?id=${id}`,
  );
}

/** 新增业务术语 */
export function createStandardTerm(
  data: GovernanceStandardTermApi.StandardTerm,
) {
  return requestClient.post('/governance/standard-term/create', data);
}

/** 修改业务术语 */
export function updateStandardTerm(
  data: GovernanceStandardTermApi.StandardTerm,
) {
  return requestClient.put('/governance/standard-term/update', data);
}

/** 删除业务术语 */
export function deleteStandardTerm(id: number | string) {
  return requestClient.delete(`/governance/standard-term/delete?id=${id}`);
}

/** 批量删除业务术语 */
export function deleteStandardTermList(ids: (number | string)[]) {
  return requestClient.delete(
    `/governance/standard-term/delete-list?ids=${ids.join(',')}`,
  );
}
