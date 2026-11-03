import { requestClient } from '#/api/request';

export namespace FoundationCategoryDataAssetApi {
  /** 数据资产类目管理信息 */
  export interface CategoryDataAsset {
    id: string | number; // 主键
    parentId?: string | number; // 父级ID
    categoryName?: string; // 类目名称
    categoryCode?: string; // 层级编码
    sort?: number; // 排序
    status?: number; // 状态
    remark?: string; // 备注
    children?: CategoryDataAsset[];
  }
}

/** 查询数据资产类目管理列表 */
export function getCategoryDataAssetList(params: any) {
  return requestClient.get<FoundationCategoryDataAssetApi.CategoryDataAsset[]>(
    '/asset/category-data-asset/list',
    { params },
  );
}

/** 查询数据资产类目管理详情 */
export function getCategoryDataAsset(id: string | number) {
  return requestClient.get<FoundationCategoryDataAssetApi.CategoryDataAsset>(
    `/asset/category-data-asset/get?id=${id}`,
  );
}

/** 新增数据资产类目管理 */
export function createCategoryDataAsset(
  data: FoundationCategoryDataAssetApi.CategoryDataAsset,
) {
  return requestClient.post('/asset/category-data-asset/create', data);
}

/** 修改数据资产类目管理 */
export function updateCategoryDataAsset(
  data: FoundationCategoryDataAssetApi.CategoryDataAsset,
) {
  return requestClient.put('/asset/category-data-asset/update', data);
}

/** 删除数据资产类目管理 */
export function deleteCategoryDataAsset(id: string | number) {
  return requestClient.delete(`/asset/category-data-asset/delete?id=${id}`);
}

/** 导出数据资产类目管理 */
export function exportCategoryDataAsset(params: any) {
  return requestClient.download('/asset/category-data-asset/export-excel', {
    params,
  });
}
