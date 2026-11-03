import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace SafetyCategoryApi {
  /** 数据安全--数据分类信息信息 */
  export interface Category {
    id: number; // 主键
    categoryId: number; // 所属类目ID
    name: string; // 分类名称
    shortName: string; // 分类缩写
    dataLevelId: number; // 数据分级
    priority: string; // 优先级
    ruleId: number; // 脱敏配置
    remark: string; // 备注
    status?: number; // 状态
    sort?: number; // 显示顺序
    description: string; // 规则描述
    creator?: string; // 创建者
    createTime?: Date; // 创建时间
    updater?: string; // 修改者
    updateTime?: Date; // 修改时间
  }
}

/** 查询数据安全--数据分类信息分页 */
export function getCategoryPage(params: PageParam) {
  return requestClient.get<PageResult<SafetyCategoryApi.Category>>(
    '/governance/safety-category/page',
    { params },
  );
}

/** 查询数据安全--数据分类信息详情 */
export function getCategory(id: number) {
  return requestClient.get<SafetyCategoryApi.Category>(
    `/governance/safety-category/get?id=${id}`,
  );
}

/** 新增数据安全--数据分类信息 */
export function createCategory(data: SafetyCategoryApi.Category) {
  return requestClient.post('/governance/safety-category/create', data);
}

/** 修改数据安全--数据分类信息 */
export function updateCategory(data: SafetyCategoryApi.Category) {
  return requestClient.put('/governance/safety-category/update', data);
}

/** 删除数据安全--数据分类信息 */
export function deleteCategory(id: number) {
  return requestClient.delete(`/governance/safety-category/delete?id=${id}`);
}

/** 批量删除数据安全--数据分类信息 */
export function deleteCategoryList(ids: number[]) {
  return requestClient.delete(
    `/governance/safety-category/delete-list?ids=${ids.join(',')}`,
  );
}

/** 导出数据安全--数据分类信息 */
export function exportCategory(params: any) {
  return requestClient.download('/governance/safety-category/export-excel', {
    params,
  });
}
