import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace QualityOrgScoreApi {
  /** 机构评分 */
  export interface OrgScore {
    id?: number;
    ruleSetId?: number;
    ruleSetName?: string;
    periodStart?: string;
    periodEnd?: string;
    orgId?: number;
    orgName?: string;
    orgCode?: string;
    scoreRate?: number;
    passRuleCount?: number;
    totalRuleCount?: number;
    regionRank?: number;
    createTime?: string;
  }
}

/** 机构评分分页 */
export function getOrgScorePage(
  params: PageParam & {
    orgName?: string;
    periodEnd?: string;
    periodStart?: string;
    ruleSetId?: number;
  },
) {
  return requestClient.get<PageResult<QualityOrgScoreApi.OrgScore>>(
    '/governance/quality-org-score/page',
    { params },
  );
}

/** 计算评分 */
export function calculateOrgScore(
  ruleSetId: number,
  periodStart: string,
  periodEnd: string,
) {
  return requestClient.post<number>(
    `/governance/quality-org-score/calculate?ruleSetId=${ruleSetId}&periodStart=${periodStart}&periodEnd=${periodEnd}`,
  );
}
