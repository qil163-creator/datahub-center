import { requestClient } from '#/api/request';

export namespace ModelingBizCategoryApi {
  /** 业务分类信息 */
  export interface BizCategory {
    id?: number | string;
    parentId?: number | string; // 父级编号，0 表示顶级节点
    name?: string; // 业务分类名称
    code?: string; // 英文缩写
    leaderUserId?: number | string; // 负责人用户编号
    leaderPhone?: string; // 负责人电话
    status?: number; // 状态：0-启用 1-停用
    responsibilityScope?: string; // 责任范围说明，最多 2000 字，不作为授权依据
    description?: string; // 描述
    remark?: string; // 备注
    /** 关联数据域编号（保存必填） */
    dataDomainIds?: (number | string)[];
    /** 关联数据域摘要（列表/详情回显） */
    dataDomains?: { id?: number | string; name?: string; code?: string }[];
    creator?: string; // 创建者编号
    createTime?: number | string;
    updater?: string; // 更新者编号
    updateTime?: number | string;
    children?: BizCategory[];
  }
}

/** 查询业务分类列表（树形数据平铺返回） */
export function getBizCategoryList(params: any) {
  return requestClient.get<ModelingBizCategoryApi.BizCategory[]>(
    '/modeling/biz-category/list',
    { params },
  );
}

/** 查询业务分类详情 */
export function getBizCategory(id: number | string) {
  return requestClient.get<ModelingBizCategoryApi.BizCategory>(
    `/modeling/biz-category/get?id=${id}`,
  );
}

/** 新增业务分类 */
export function createBizCategory(data: ModelingBizCategoryApi.BizCategory) {
  return requestClient.post('/modeling/biz-category/create', data);
}

/** 修改业务分类 */
export function updateBizCategory(data: ModelingBizCategoryApi.BizCategory) {
  return requestClient.put('/modeling/biz-category/update', data);
}

/** 修改业务分类状态 */
export function updateBizCategoryStatus(id: number | string, status: number) {
  return requestClient.put('/modeling/biz-category/update-status', {
    id,
    status,
  });
}

/** 删除业务分类 */
export function deleteBizCategory(id: number | string) {
  return requestClient.delete(`/modeling/biz-category/delete?id=${id}`);
}
