import { requestClient } from '#/api/request';

export namespace FoundationCategoryDataQualityApi {
  /** 数据质量类目管理信息 */
  export interface CategoryDataQuality {
    id: number; // 主键
    parentId?: number; // 父级ID
    categoryName?: string; // 类目名称
    categoryCode?: string; // 层级编码
    sort?: number; // 排序
    status?: number; // 状态
    remark: string; // 备注
    children?: CategoryDataQuality[];
  }
}

/** 查询数据质量类目管理列表 */
export function getCategoryDataQualityList(params: any) {
  return requestClient.get<
    FoundationCategoryDataQualityApi.CategoryDataQuality[]
  >('/governance/category-data-quality/list', { params });
}

/** 查询数据质量类目管理详情 */
export function getCategoryDataQuality(id: number | string) {
  return requestClient.get<FoundationCategoryDataQualityApi.CategoryDataQuality>(
    `/governance/category-data-quality/get?id=${id}`,
  );
}

/** 新增数据质量类目管理 */
export function createCategoryDataQuality(
  data: FoundationCategoryDataQualityApi.CategoryDataQuality,
) {
  return requestClient.post('/governance/category-data-quality/create', data);
}

/** 修改数据质量类目管理 */
export function updateCategoryDataQuality(
  data: FoundationCategoryDataQualityApi.CategoryDataQuality,
) {
  return requestClient.put('/governance/category-data-quality/update', data);
}

/** 删除数据质量类目管理 */
export function deleteCategoryDataQuality(id: number) {
  return requestClient.delete(
    `/governance/category-data-quality/delete?id=${id}`,
  );
}

/** 导出数据质量类目管理 */
export function exportCategoryDataQuality(params: any) {
  return requestClient.download(
    '/governance/category-data-quality/export-excel',
    { params },
  );
}
