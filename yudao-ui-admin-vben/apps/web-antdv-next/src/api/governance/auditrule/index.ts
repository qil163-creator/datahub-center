import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace GovernanceAuditRuleApi {
  export type Id = number | string;

  /** 稽查规则独立质量维度，与业务数据质量类目分开维护。 */
  export interface Dimension {
    id: Id;
    name: string;
    code: string;
    status: number;
    sort: number;
    remark?: null | string;
  }

  /** 稽查规则 */
  export interface AuditRule {
    id?: Id;
    name?: string;
    dimensionId?: Id;
    dimensionName?: string;
    ruleLevel?: number;
    code?: string;
    strategyKey?: string;
    engineSupported?: boolean;
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

  /** SQL 预览请求 */
  export interface PreviewReq {
    ruleId?: Id;
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

/** 查询稽查规则质量维度，不读取质量任务的业务类目。 */
export function getAuditRuleDimensionList() {
  return requestClient.get<GovernanceAuditRuleApi.Dimension[]>(
    '/governance/audit-rule/dimension-list',
  );
}

/** 稽查规则分页 */
export function getAuditRulePage(
  params: PageParam & {
    description?: string;
    dimensionId?: number | string;
    name?: string;
    status?: number;
  },
) {
  return requestClient.get<PageResult<GovernanceAuditRuleApi.AuditRule>>(
    '/governance/audit-rule/page',
    { params },
  );
}

/** 稽查规则详情 */
export function getAuditRule(id: GovernanceAuditRuleApi.Id) {
  return requestClient.get<GovernanceAuditRuleApi.AuditRule>(
    `/governance/audit-rule/get?id=${id}`,
  );
}

/** 新增稽查规则 */
export function createAuditRule(data: GovernanceAuditRuleApi.AuditRule) {
  return requestClient.post('/governance/audit-rule/create', data);
}

/** 修改稽查规则 */
export function updateAuditRule(data: GovernanceAuditRuleApi.AuditRule) {
  return requestClient.put('/governance/audit-rule/update', data);
}

/** 修改稽查规则状态 */
export function updateAuditRuleStatus(
  id: GovernanceAuditRuleApi.Id,
  status: number,
) {
  return requestClient.put(
    `/governance/audit-rule/update-status?id=${id}&status=${status}`,
  );
}

/** 删除稽查规则 */
export function deleteAuditRule(id: GovernanceAuditRuleApi.Id) {
  return requestClient.delete(`/governance/audit-rule/delete?id=${id}`);
}

/** 质量引擎稽查策略列表 */
export function getAuditStrategyList() {
  return requestClient.get<GovernanceAuditRuleApi.Strategy[]>(
    '/governance/audit-rule/strategy-list',
  );
}

/** 预览稽查 SQL */
export function previewAuditRuleSql(data: GovernanceAuditRuleApi.PreviewReq) {
  return requestClient.post<GovernanceAuditRuleApi.PreviewResp>(
    '/governance/audit-rule/preview-sql',
    data,
  );
}
