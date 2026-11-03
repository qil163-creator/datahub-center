import { requestClient } from '#/api/request';

export namespace FoundationCategoryDataApi {
  export type Id = number | string;
  export type Time = null | number | string;
  /** 数据分类 类目管理信息 */
  export interface CategoryData {
    id: Id; // 主键
    revision?: Id;
    parentId?: Id; // 父级ID
    categoryName?: string; // 类目名称
    categoryCode?: string; // 层级编码
    sort?: number; // 排序
    status?: number; // 状态
    remark: string; // 备注
    sourceRecordId?: string;
    createTime?: Time;
    updateTime?: Time;
    children?: CategoryData[];
  }
  export type Save = Omit<
    CategoryData,
    'children' | 'createTime' | 'id' | 'updateTime'
  > & { id?: Id };
}

/** 查询数据分类 类目管理列表 */
export function getCategoryDataList(params: any) {
  return requestClient.get<FoundationCategoryDataApi.CategoryData[]>(
    '/governance/category-data/list',
    { params },
  );
}

/** 查询数据分类 类目管理详情 */
export function getCategoryData(id: number | string) {
  return requestClient.get<FoundationCategoryDataApi.CategoryData>(
    `/governance/category-data/get?id=${id}`,
  );
}

/** 新增数据分类 类目管理 */
export function createCategoryData(data: FoundationCategoryDataApi.Save) {
  return requestClient.post('/governance/category-data/create', data);
}

/** 修改数据分类 类目管理 */
export function updateCategoryData(
  data: FoundationCategoryDataApi.CategoryData,
) {
  return requestClient.put('/governance/category-data/update', data);
}

/** 删除数据分类 类目管理 */
export function deleteCategoryData(
  id: FoundationCategoryDataApi.Id,
  revision: FoundationCategoryDataApi.Id,
) {
  return requestClient.delete('/governance/category-data/delete', {
    params: { id, revision },
  });
}

/** 导出数据分类 类目管理 */
export function exportCategoryData(params: any) {
  return requestClient.download('/governance/category-data/export-excel', {
    params,
  });
}
