import type { PageResult } from '@vben/request';

import type { QualityWorkOrderApi } from './workorder';

import { requestClient } from '#/api/request';

type Id = QualityWorkOrderApi.Id;
export interface WorkOrderNotifyReview {
  id: Id;
  actionType: string;
  outcome?: string;
  reason: string;
  operatorMessageId?: Id;
  decisionId?: Id;
  actorUserId: Id;
  resultingResolutionVersion: number;
  createTime: number;
}
export interface WorkOrderNotify {
  id: Id;
  workOrderId: Id;
  eventType: string;
  receiverUserId: Id;
  payloadSummary: string;
  sendStatus: string;
  retryCount: number;
  manualRetryCount: number;
  nextRetryTime?: number;
  lastError?: string;
  resolutionStatus?: string;
  resolutionVersion: number;
  resolutionReason?: string;
  operatorMessageId?: Id;
  resolvedBy?: Id;
  resolvedAt?: number;
  createTime: number;
  reviews?: WorkOrderNotifyReview[];
  reviewsTruncated?: boolean;
}
export interface NotifyReviewRequest {
  id: Id;
  expectedResolutionVersion: number;
  requestId: string;
  outcome: 'DELIVERED' | 'NOT_DELIVERED';
  reason: string;
  operatorMessageId?: Id;
}
export interface NotifyRetryRequest {
  id: Id;
  decisionId: Id;
  expectedResolutionVersion: number;
  requestId: string;
  reason: string;
}
const base = '/governance/quality-work-order';
export function getWorkOrderNotifyPage(params: {
  pageNo: number;
  pageSize: number;
  workOrderId: Id;
}) {
  return requestClient.get<PageResult<WorkOrderNotify>>(`${base}/notify-page`, {
    params,
  });
}
export function getWorkOrderNotifyDetail(id: Id) {
  return requestClient.get<WorkOrderNotify>(`${base}/notify-detail`, {
    params: { id },
  });
}
export function reviewWorkOrderNotify(data: NotifyReviewRequest) {
  return requestClient.post<WorkOrderNotify>(`${base}/notify-review`, data);
}
export function authorizeWorkOrderNotifyRetry(data: NotifyRetryRequest) {
  return requestClient.post<WorkOrderNotify>(
    `${base}/notify-authorize-retry`,
    data,
  );
}
