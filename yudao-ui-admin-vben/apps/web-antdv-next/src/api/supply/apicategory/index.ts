import { requestClient } from '#/api/request';

export namespace SupplyApiCategoryApi {
  export type Id = number | string;

  /** 服务分类信息 */
  export interface ApiCategory {
    id?: Id;
    parentId?: Id; // 父级编号，0 表示顶级节点
    name?: string; // 数据服务类目名称
    status?: number; // 状态：0-启用 1-停用
    sort?: number; // 排序
    description?: string; // 描述
    remark?: string; // 备注
    creator?: string;
    createTime?: string;
    children?: ApiCategory[];
  }
}

/** 查询服务分类列表（树形数据平铺返回） */
export function getApiCategoryList(params: any) {
  return requestClient.get<SupplyApiCategoryApi.ApiCategory[]>(
    '/supply/api-category/list',
    { params },
  );
}

/** 查询服务分类详情 */
export function getApiCategory(id: SupplyApiCategoryApi.Id) {
  return requestClient.get<SupplyApiCategoryApi.ApiCategory>(
    '/supply/api-category/get',
    { params: { id } },
  );
}

/** 新增服务分类 */
export function createApiCategory(data: SupplyApiCategoryApi.ApiCategory) {
  return requestClient.post('/supply/api-category/create', data);
}

/** 修改服务分类 */
export function updateApiCategory(data: SupplyApiCategoryApi.ApiCategory) {
  return requestClient.put('/supply/api-category/update', data);
}

/** 修改服务分类状态 */
export function updateApiCategoryStatus(
  id: SupplyApiCategoryApi.Id,
  status: number,
) {
  return requestClient.put('/supply/api-category/update-status', {
    id,
    status,
  });
}

/** 删除服务分类 */
export function deleteApiCategory(id: SupplyApiCategoryApi.Id) {
  return requestClient.delete('/supply/api-category/delete', {
    params: { id },
  });
}
