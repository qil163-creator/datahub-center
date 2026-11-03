import { requestClient } from '#/api/request';

export namespace FoundationCategoryDatametaApi {
  /** 标准数据元类目管理信息 */
  export interface CategoryDatameta {
    id: number; // 主键
    parentId?: number; // 父级ID
    categoryName?: string; // 类目名称
    categoryCode?: string; // 层级编码
    sort?: number; // 排序
    status?: number; // 状态
    remark: string; // 备注
    children?: CategoryDatameta[];
  }
}

/** 查询标准数据元类目管理列表 */
export function getCategoryDatametaList(params: any) {
  return requestClient.get<FoundationCategoryDatametaApi.CategoryDatameta[]>(
    '/governance/category-datameta/list',
    { params },
  );
}

/** 查询标准数据元类目管理详情 */
export function getCategoryDatameta(id: number | string) {
  return requestClient.get<FoundationCategoryDatametaApi.CategoryDatameta>(
    `/governance/category-datameta/get?id=${id}`,
  );
}

/** 新增标准数据元类目管理 */
export function createCategoryDatameta(
  data: FoundationCategoryDatametaApi.CategoryDatameta,
) {
  return requestClient.post('/governance/category-datameta/create', data);
}

/** 修改标准数据元类目管理 */
export function updateCategoryDatameta(
  data: FoundationCategoryDatametaApi.CategoryDatameta,
) {
  return requestClient.put('/governance/category-datameta/update', data);
}

/** 删除标准数据元类目管理 */
export function deleteCategoryDatameta(id: number) {
  return requestClient.delete(`/governance/category-datameta/delete?id=${id}`);
}

/** 导出标准数据元类目管理 */
export function exportCategoryDatameta(params: any) {
  return requestClient.download('/governance/category-datameta/export-excel', {
    params,
  });
}
