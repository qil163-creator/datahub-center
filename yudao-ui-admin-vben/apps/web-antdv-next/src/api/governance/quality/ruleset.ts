import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace QualityRuleSetApi {
  /** 规则集 */
  export interface RuleSet {
    id?: number;
    setName?: string;
    setCode?: string;
    category?: string;
    assessDim?: string;
    cycle?: string;
    status?: number;
    description?: string;
    ruleCount?: number;
    createTime?: string;
  }

  /** 规则集明细 */
  export interface Item {
    id?: number;
    ruleId: number;
    ruleName?: string;
    ruleType?: string;
    qualityDim?: string;
    weight?: number;
  }
}

/** 规则集分页 */
export function getRuleSetPage(
  params: PageParam & {
    assessDim?: string;
    category?: string;
    setName?: string;
    status?: number;
  },
) {
  return requestClient.get<PageResult<QualityRuleSetApi.RuleSet>>(
    '/governance/quality-rule-set/page',
    { params },
  );
}

/** 规则集详情 */
export function getRuleSet(id: number) {
  return requestClient.get<QualityRuleSetApi.RuleSet>(
    `/governance/quality-rule-set/get?id=${id}`,
  );
}

/** 规则集精简列表（启用状态） */
export function getRuleSetSimpleList() {
  return requestClient.get<QualityRuleSetApi.RuleSet[]>(
    '/governance/quality-rule-set/simple-list',
  );
}

/** 新增规则集 */
export function createRuleSet(data: QualityRuleSetApi.RuleSet) {
  return requestClient.post('/governance/quality-rule-set/create', data);
}

/** 修改规则集 */
export function updateRuleSet(data: QualityRuleSetApi.RuleSet) {
  return requestClient.put('/governance/quality-rule-set/update', data);
}

/** 修改规则集状态 */
export function updateRuleSetStatus(id: number, status: number) {
  return requestClient.put(
    `/governance/quality-rule-set/update-status?id=${id}&status=${status}`,
  );
}

/** 删除规则集 */
export function deleteRuleSet(id: number) {
  return requestClient.delete(`/governance/quality-rule-set/delete?id=${id}`);
}

/** 规则集明细列表 */
export function getRuleSetItems(ruleSetId: number | string) {
  return requestClient.get<QualityRuleSetApi.Item[]>(
    `/governance/quality-rule-set/items?ruleSetId=${ruleSetId}`,
  );
}

/** 保存规则集明细（全量覆盖） */
export function saveRuleSetItems(data: {
  items: QualityRuleSetApi.Item[];
  ruleSetId: number;
}) {
  return requestClient.post('/governance/quality-rule-set/save-items', data);
}
