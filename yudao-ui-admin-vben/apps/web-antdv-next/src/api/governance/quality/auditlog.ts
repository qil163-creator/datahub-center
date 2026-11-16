import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace QualityAuditLogApi {
  export type Id = number | string;
  /** 检测结果明细 */
  export interface AuditLog {
    id?: Id;
    instanceId?: Id;
    taskId?: Id;
    taskName?: string;
    ruleId?: Id;
    ruleName?: string;
    ruleType?: string;
    checkObjectId?: Id;
    tableName?: string;
    checkField?: string;
    groupName?: string;
    checkTime?: number | string;
    totalCount?: number;
    failCount?: number;
    passRate?: number;
    resultType?: string;
    indexValue?: string;
    costMs?: number;
    resultSql?: string;
    errorDetail?: string;
    /** 表级指标键：ROW_COUNT 表示表行数，此时 indexValue 为行数 */
    metricKey?: string;
  }

  /** 表级指标趋势点；数值为十进制字符串 */
  export interface MetricTrendPoint {
    auditLogId?: Id;
    checkTime?: number | string;
    bizDate?: string;
    value?: string;
    lower?: null | string;
    upper?: null | string;
    resultType?: string;
  }

  /** 质量报告统计 */
  export interface ReportSummary {
    totalCount?: number;
    passCount?: number;
    failCount?: number;
    avgPassRate?: number;
  }
}

/** 检测结果分页 */
export function getAuditLogPage(
  params: PageParam & {
    groupName?: string;
    instanceId?: QualityAuditLogApi.Id;
    resultType?: string;
    ruleName?: string;
    tableName?: string;
    taskId?: QualityAuditLogApi.Id;
  },
) {
  return requestClient.get<PageResult<QualityAuditLogApi.AuditLog>>(
    '/governance/quality-audit-log/page',
    { params },
  );
}

/** 表级指标趋势（如表行数波动检测的历史行数与预期区间） */
export function getMetricTrend(id: QualityAuditLogApi.Id, limit = 60) {
  return requestClient.get<QualityAuditLogApi.MetricTrendPoint[]>(
    '/governance/quality-audit-log/metric-trend',
    { params: { id, limit } },
  );
}

/** 检测结果详情 */
export function getAuditLog(id: QualityAuditLogApi.Id) {
  return requestClient.get<QualityAuditLogApi.AuditLog>(
    `/governance/quality-audit-log/get?id=${id}`,
  );
}

/** 质量报告统计 */
export function getReportSummary() {
  return requestClient.get<QualityAuditLogApi.ReportSummary>(
    '/governance/quality-audit-log/report-summary',
  );
}

/** 六维质量维度统计（雷达图） */
export function getDimensionSummary() {
  return requestClient.get<
    { checkCount: number; passRate: number; qualityDim: string }[]
  >('/governance/quality-audit-log/dimension-summary');
}

/** 质量趋势（近 N 天按日统计检测次数与平均通过率） */
export function getReportTrend(days?: number) {
  return requestClient.get<
    { checkCount: number; date: string; passRate: null | number }[]
  >('/governance/quality-audit-log/trend', {
    params: days ? { days } : {},
  });
}
