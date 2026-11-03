import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace QualityRuleApi {
  /** 质量规则 */
  export interface Rule {
    id?: number;
    categoryId?: number;
    ruleName?: string;
    ruleCode?: string;
    qualityDim?: string;
    checkLevel?: string;
    ruleType?: string;
    strategyKey?: string;
    paramSchema?: string;
    ruleContent?: string;
    severity?: string;
    applyLayer?: string;
    collectIssueDetail?: number;
    useCase?: string;
    example?: string;
    status?: number;
    builtin?: number;
    description?: string;
    remark?: string;
    standardElementId?: number | string;
    constraintHash?: string;
    creator?: string;
    creatorName?: string;
    createTime?: string;
  }

  /** 质量引擎策略 */
  export interface Strategy {
    strategyKey: string;
    strategyName: string;
    configExample?: string;
  }

  /** SQL 预览请求 */
  export interface PreviewReq {
    strategyKey?: string;
    productType: string;
    tableName: string;
    column?: string;
    columns?: string;
    whereClause?: string;
    configJson?: string;
  }

  /** SQL 预览结果 */
  export interface PreviewResp {
    strategyKey?: string;
    countSql?: string;
    errorDataSql?: string;
  }
}

/** 质量规则分页 */
export function getQualityRulePage(
  params: PageParam & {
    categoryId?: number;
    qualityDim?: string;
    ruleCode?: string;
    ruleName?: string;
    ruleType?: string;
    severity?: string;
    status?: number;
  },
) {
  return requestClient.get<PageResult<QualityRuleApi.Rule>>(
    '/governance/quality-rule/page',
    { params },
  );
}

/** 质量规则详情 */
export function getQualityRule(id: number) {
  return requestClient.get<QualityRuleApi.Rule>(
    `/governance/quality-rule/get?id=${id}`,
  );
}

/** 质量规则精简列表（启用状态） */
export function getQualityRuleSimpleList(params?: {
  standardElementId?: number | string;
}) {
  return requestClient.get<QualityRuleApi.Rule[]>(
    '/governance/quality-rule/simple-list',
    { params },
  );
}

/** 新增质量规则 */
export function createQualityRule(data: QualityRuleApi.Rule) {
  return requestClient.post('/governance/quality-rule/create', data);
}

/** 修改质量规则 */
export function updateQualityRule(data: QualityRuleApi.Rule) {
  return requestClient.put('/governance/quality-rule/update', data);
}

/** 修改质量规则状态 */
export function updateQualityRuleStatus(id: number, status: number) {
  return requestClient.put(
    `/governance/quality-rule/update-status?id=${id}&status=${status}`,
  );
}

/** 删除质量规则 */
export function deleteQualityRule(id: number) {
  return requestClient.delete(`/governance/quality-rule/delete?id=${id}`);
}

/** 质量引擎策略列表 */
export function getQualityStrategyList() {
  return requestClient.get<QualityRuleApi.Strategy[]>(
    '/governance/quality-rule/strategy-list',
  );
}

/** 预览规则 SQL */
export function previewQualityRuleSql(data: QualityRuleApi.PreviewReq) {
  return requestClient.post<QualityRuleApi.PreviewResp>(
    '/governance/quality-rule/preview-sql',
    data,
  );
}
