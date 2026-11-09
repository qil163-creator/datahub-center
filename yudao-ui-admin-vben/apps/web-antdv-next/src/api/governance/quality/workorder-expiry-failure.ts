import type { PageParam, PageResult } from '@vben/request';

import type { QualityWorkOrderApi } from './workorder';

import { requestClient } from '#/api/request';

type Id = QualityWorkOrderApi.Id;

/** 例外到期处理失败账本 */
export interface ExpiryFailure {
  id: Id;
  submissionId: Id;
  workOrderId: Id;
  workOrderName?: string;
  /** RETRYING 退避重试中 / EXHAUSTED 次数耗尽待人工 / RESOLVED 已关闭 */
  status: string;
  attemptCount: number;
  maxAttempts: number;
  lastError?: string;
  firstFailedAt?: number;
  lastFailedAt?: number;
  nextAttemptAt?: number;
  resolutionAction?: string;
  resolutionReason?: string;
  resolvedBy?: Id;
  resolvedAt?: number;
  version: number;
}

export interface ExpiryFailurePageReq extends PageParam {
  status?: string;
  workOrderId?: Id;
}

const base = '/governance/quality-work-order';

/** 例外到期处理失败账本分页 */
export function getExpiryFailurePage(params: ExpiryFailurePageReq) {
  return requestClient.get<PageResult<ExpiryFailure>>(
    `${base}/expiry-failure/page`,
    { params },
  );
}

/** 人工排查后授权例外到期处理重试 */
export function authorizeExpiryRetry(data: {
  expectedVersion: number;
  id: Id;
  reason: string;
}) {
  return requestClient.post<ExpiryFailure>(
    `${base}/expiry-failure/retry`,
    data,
  );
}
