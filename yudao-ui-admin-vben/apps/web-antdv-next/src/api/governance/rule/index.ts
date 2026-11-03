import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace SafetyRuleApi {
  /** 替换明细（内容替换方式的匹配-替换码表） */
  export interface ReplaceItem {
    sequenceNo?: number; // 匹配顺序，从1开始，命中即停
    matchValue: string; // 需要匹配的字段内容
    replacementValue: string; // 匹配成功后的替换内容
  }

  /** 数据安全--脱敏规则（主表）；参数演进由版本表承载信息 */
  export interface Rule {
    id: number; // 脱敏规则编号
    ruleName?: string; // 规则名称
    methodCode?: string; // 脱敏方式
    sourceType?: string; // 规则来源
    currentVerId: number; // 当前生效的规则版本编号
    currentVersionNo?: number; // 当前版本号
    status?: number; // 状态
    sort?: number; // 显示顺序
    description: string; // 规则描述
    config?: Record<string, any>; // 脱敏参数（当前生效版本的配置）
    replaceItems?: ReplaceItem[]; // 替换明细（仅内容替换方式）
  }
}

/** 查询数据安全--脱敏规则（主表）；参数演进由版本表承载分页 */
export function getRulePage(params: PageParam) {
  return requestClient.get<PageResult<SafetyRuleApi.Rule>>(
    '/governance/safety-rule/page',
    { params },
  );
}

/** 查询数据安全--脱敏规则（主表）；参数演进由版本表承载详情 */
export function getRule(id: number) {
  return requestClient.get<SafetyRuleApi.Rule>(
    `/governance/safety-rule/get?id=${id}`,
  );
}

/** 新增数据安全--脱敏规则（主表）；参数演进由版本表承载 */
export function createRule(data: SafetyRuleApi.Rule) {
  return requestClient.post('/governance/safety-rule/create', data);
}

/** 修改数据安全--脱敏规则（主表）；参数演进由版本表承载 */
export function updateRule(data: SafetyRuleApi.Rule) {
  return requestClient.put('/governance/safety-rule/update', data);
}

/** 删除数据安全--脱敏规则（主表）；参数演进由版本表承载 */
export function deleteRule(id: number) {
  return requestClient.delete(`/governance/safety-rule/delete?id=${id}`);
}

/** 批量删除数据安全--脱敏规则（主表）；参数演进由版本表承载 */
export function deleteRuleList(ids: number[]) {
  return requestClient.delete(
    `/governance/safety-rule/delete-list?ids=${ids.join(',')}`,
  );
}

/** 导出数据安全--脱敏规则（主表）；参数演进由版本表承载 */
export function exportRule(params: any) {
  return requestClient.download('/governance/safety-rule/export-excel', {
    params,
  });
}
