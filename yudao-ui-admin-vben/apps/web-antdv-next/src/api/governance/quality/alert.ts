import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace QualityAlertApi {
  /** 告警记录 */
  export interface Alert {
    id?: number;
    alertTitle?: string;
    taskType?: string;
    taskId?: number;
    taskName?: string;
    alertLevel?: string;
    issueCount?: number;
    ruleName?: string;
    alertStatus?: string;
    alertTime?: string;
    createTime?: string;
  }

  /** 告警统计 */
  export interface Summary {
    dangerCount?: number;
    seriousCount?: number;
    generalCount?: number;
    unprocessedCount?: number;
    processedCount?: number;
  }
}

/** 告警记录分页 */
export function getAlertPage(
  params: PageParam & {
    alertLevel?: string;
    alertStatus?: string;
    alertTitle?: string;
    taskName?: string;
  },
) {
  return requestClient.get<PageResult<QualityAlertApi.Alert>>(
    '/governance/quality-alert/page',
    { params },
  );
}

/** 告警统计卡 */
export function getAlertSummary() {
  return requestClient.get<QualityAlertApi.Summary>(
    '/governance/quality-alert/summary',
  );
}

/** 处理告警 */
export function processAlert(id: number) {
  return requestClient.put(`/governance/quality-alert/process?id=${id}`);
}

/** 删除告警 */
export function deleteAlert(id: number) {
  return requestClient.delete(`/governance/quality-alert/delete?id=${id}`);
}
