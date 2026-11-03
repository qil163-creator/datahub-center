import { requestClient } from '#/api/request';

export namespace FoundationCategoryCleanRuleApi {
  /** 清洗规则 类目管理信息 */
  export interface CategoryCleanRule {
    id: number | string; // 主键
    parentId?: number | string; // 父级ID
    categoryName?: string; // 类目名称
    categoryCode?: string; // 层级编码
    sort?: number; // 排序
    status?: number; // 状态
    remark: string; // 备注
    creator?: string; // 创建人
    createTime?: number | string;
    children?: CategoryCleanRule[];
  }
}

/** 查询清洗规则 类目管理列表 */
export function getCategoryCleanRuleList(params: any) {
  return requestClient.get<FoundationCategoryCleanRuleApi.CategoryCleanRule[]>(
    '/governance/category-clean-rule/list',
    { params },
  );
}

/** 查询清洗规则 类目管理详情 */
export function getCategoryCleanRule(id: number | string) {
  return requestClient.get<FoundationCategoryCleanRuleApi.CategoryCleanRule>(
    `/governance/category-clean-rule/get?id=${id}`,
  );
}

/** 新增清洗规则 类目管理 */
export function createCategoryCleanRule(
  data: FoundationCategoryCleanRuleApi.CategoryCleanRule,
) {
  return requestClient.post('/governance/category-clean-rule/create', data);
}

/** 修改清洗规则 类目管理 */
export function updateCategoryCleanRule(
  data: FoundationCategoryCleanRuleApi.CategoryCleanRule,
) {
  return requestClient.put('/governance/category-clean-rule/update', data);
}

/** 删除清洗规则 类目管理 */
export function deleteCategoryCleanRule(id: number | string) {
  return requestClient.delete(
    `/governance/category-clean-rule/delete?id=${id}`,
  );
}

/** 导出清洗规则 类目管理 */
export function exportCategoryCleanRule(params: any) {
  return requestClient.download(
    '/governance/category-clean-rule/export-excel',
    { params },
  );
}
