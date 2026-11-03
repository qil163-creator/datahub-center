import { requestClient } from '#/api/request';

export namespace AssetCategoryTagApi {
  /** 标签类目（树形） */
  export interface CategoryTag {
    id: number | string;
    parentId?: number | string;
    categoryName?: string;
    categoryCode?: string;
    sort?: number;
    status?: number;
    remark?: string;
    tagCount?: number;
    creator?: string;
    createTime?: number | string;
    children?: CategoryTag[];
  }
}

export function getCategoryTagList(params?: {
  categoryName?: string;
  status?: number;
}) {
  return requestClient.get<AssetCategoryTagApi.CategoryTag[]>(
    '/asset/category-tag/list',
    { params },
  );
}

export function getCategoryTag(id: number | string) {
  return requestClient.get<AssetCategoryTagApi.CategoryTag>(
    `/asset/category-tag/get?id=${id}`,
  );
}

export function createCategoryTag(data: AssetCategoryTagApi.CategoryTag) {
  return requestClient.post<number | string>(
    '/asset/category-tag/create',
    data,
  );
}

export function updateCategoryTag(data: AssetCategoryTagApi.CategoryTag) {
  return requestClient.put('/asset/category-tag/update', data);
}

export function deleteCategoryTag(id: number | string) {
  return requestClient.delete(`/asset/category-tag/delete?id=${id}`);
}
