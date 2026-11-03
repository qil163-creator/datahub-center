import { requestClient } from '#/api/request';

export namespace AssetMarketCategoryApi {
  export type Id = number | string;

  export interface MarketCategory {
    id: Id;
    parentId: Id;
    name: string;
    description?: null | string;
    status: number;
    sort: number;
    creator?: string;
    creatorName?: string;
    createTime?: number;
    children?: MarketCategory[];
  }

  export interface CategoryQuery {
    name?: string;
    parentId?: Id;
    status?: number;
  }

  export type CategorySave = Omit<
    MarketCategory,
    'children' | 'createTime' | 'creator' | 'creatorName' | 'id'
  > & { id?: Id };
}

/** 查询管理用集市类目列表，由页面按 parentId 组织为树。 */
export function getMarketCategoryList(
  params: AssetMarketCategoryApi.CategoryQuery = {},
) {
  return requestClient.get<AssetMarketCategoryApi.MarketCategory[]>(
    '/asset/market-category/list',
    { params },
  );
}

/** 查询启用类目，供集市导航与发布挂载使用。 */
export function getMarketCategorySimpleList() {
  return requestClient.get<AssetMarketCategoryApi.MarketCategory[]>(
    '/asset/market-category/simple-list',
  );
}

export function getMarketCategory(id: AssetMarketCategoryApi.Id) {
  return requestClient.get<AssetMarketCategoryApi.MarketCategory>(
    '/asset/market-category/get',
    { params: { id } },
  );
}

export function createMarketCategory(
  data: AssetMarketCategoryApi.CategorySave,
) {
  return requestClient.post<AssetMarketCategoryApi.Id>(
    '/asset/market-category/create',
    data,
  );
}

export function updateMarketCategory(
  data: AssetMarketCategoryApi.CategorySave,
) {
  return requestClient.put<boolean>('/asset/market-category/update', data);
}

export function updateMarketCategoryStatus(
  id: AssetMarketCategoryApi.Id,
  status: number,
) {
  return requestClient.put<boolean>('/asset/market-category/update-status', {
    id,
    status,
  });
}

export function deleteMarketCategory(id: AssetMarketCategoryApi.Id) {
  return requestClient.delete<boolean>('/asset/market-category/delete', {
    params: { id },
  });
}

export function deleteMarketCategoryList(ids: AssetMarketCategoryApi.Id[]) {
  return requestClient.delete<boolean>('/asset/market-category/delete-list', {
    params: { ids: ids.join(',') },
  });
}
