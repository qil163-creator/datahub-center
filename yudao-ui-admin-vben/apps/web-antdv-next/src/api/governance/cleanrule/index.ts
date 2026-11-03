import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace GovernanceCleanRuleApi {
  /** 清洗规则 */
  export interface CleanRule {
    id?: number | string;
    name?: string;
    categoryId?: number | string;
    categoryName?: string;
    ruleLevel?: number;
    code?: string;
    strategyKey?: string;
    /** Persisted function parameters; columns are bound by the ETL node and are absent here. */
    config?: Record<string, unknown>;
    engineSupported?: boolean;
    /** 是否支持保持行数及字段集合的逐行清洗。 */
    rowTransformSupported?: boolean;
    /** 字段绑定能力由服务端注册函数提供，与逐行或集合执行方式分别建模。 */
    columnBinding?: 'ALL' | 'MULTIPLE' | 'SINGLE';
    description?: string;
    useCase?: string;
    example?: string;
    status?: number;
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

  /** 在线测试请求 */
  export interface TestReq {
    ruleId?: number | string;
    strategyKey?: string;
    configJson: string;
    rowsJson: string;
  }

  /** 在线测试结果 */
  export interface TestResp {
    strategyKey?: string;
    beforeCount?: number;
    afterCount?: number;
    rows?: Record<string, any>[];
  }
}

/** 清洗规则分页 */
export function getCleanRulePage(
  params: PageParam & {
    categoryId?: number | string;
    description?: string;
    name?: string;
    status?: number;
  },
) {
  return requestClient.get<PageResult<GovernanceCleanRuleApi.CleanRule>>(
    '/governance/clean-rule/page',
    { params },
  );
}

/** 清洗规则详情 */
export function getCleanRule(id: number | string) {
  return requestClient.get<GovernanceCleanRuleApi.CleanRule>(
    `/governance/clean-rule/get?id=${id}`,
  );
}

/** 新增清洗规则 */
export function createCleanRule(data: GovernanceCleanRuleApi.CleanRule) {
  return requestClient.post('/governance/clean-rule/create', data);
}

/** 修改清洗规则 */
export function updateCleanRule(data: GovernanceCleanRuleApi.CleanRule) {
  return requestClient.put('/governance/clean-rule/update', data);
}

/** 修改清洗规则状态 */
export function updateCleanRuleStatus(id: number | string, status: number) {
  return requestClient.put(
    `/governance/clean-rule/update-status?id=${id}&status=${status}`,
  );
}

/** 删除清洗规则 */
export function deleteCleanRule(id: number | string) {
  return requestClient.delete(`/governance/clean-rule/delete?id=${id}`);
}

/** 质量引擎清洗策略列表 */
export function getCleanStrategyList() {
  return requestClient.get<GovernanceCleanRuleApi.Strategy[]>(
    '/governance/clean-rule/strategy-list',
  );
}

/** 在线测试清洗规则 */
export function testCleanRule(data: GovernanceCleanRuleApi.TestReq) {
  return requestClient.post<GovernanceCleanRuleApi.TestResp>(
    '/governance/clean-rule/test',
    data,
  );
}
