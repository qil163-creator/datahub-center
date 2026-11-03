import type { PageParam, PageResult } from '@vben/request';

import type { QualityTaskInstanceApi } from './instance';

import { requestClient } from '#/api/request';

export namespace QualityReportApi {
  export type Id = number | string;
  export interface PageRequest extends PageParam {
    taskId?: Id;
    taskName?: string;
    instanceStatus?: string;
  }
  export interface Dimension {
    qualityDim: string;
    ruleCount: number;
    issueCount: Id;
    totalCount: Id;
    problemRatio: null | string;
    score: null | string;
    previousProblemRatio: null | string;
  }
  export interface Rule {
    id: Id;
    ruleId?: Id;
    evaluationId?: Id;
    ruleName: string;
    ruleType: string;
    ruleTypeName?: string;
    warningLevel?: string;
    errorExample?: string;
    repairAdvice?: string;
    checkTime?: number;
    qualityDim: string;
    checkObjectId?: Id;
    datasourceName?: string;
    tableName?: string;
    checkField?: string;
    fieldLabels: string[];
    totalCount: Id;
    failCount: Id;
    problemRatio: null | string;
    resultType: string;
    issueIds: Id[];
  }
  export interface TrendPoint {
    date: string;
    score: null | string;
  }
  export interface Detail {
    instance: QualityTaskInstanceApi.Instance;
    dimensions: Dimension[];
    trend: TrendPoint[];
    rules: Rule[];
  }
}

export function getQualityReportPage(params: QualityReportApi.PageRequest) {
  return requestClient.get<PageResult<QualityTaskInstanceApi.Instance>>(
    '/governance/quality-audit-log/report-page',
    { params },
  );
}

export function getQualityReport(id: QualityReportApi.Id, days: 7 | 15 | 30) {
  return requestClient.get<QualityReportApi.Detail>(
    '/governance/quality-audit-log/report',
    { params: { id, days } },
  );
}
