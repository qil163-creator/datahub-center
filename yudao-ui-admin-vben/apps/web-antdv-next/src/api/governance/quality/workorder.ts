import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace QualityWorkOrderApi {
  export type Id = number | string;
  export interface InlineQualityReport {
    reportId: Id;
    workOrderId?: Id;
    runId: string;
    nodeId: string;
    batchId: string;
    sourceType: string;
    planHash: string;
    identityHash?: string;
    identityFields?: string[];
    totalCount: Id;
    passCount: Id;
    warnCount: Id;
    rejectCount: Id;
    issueReferences: string[];
    createTime: number;
  }
  export type SubmissionType =
    | 'EXCEPTION'
    | 'EXPLAIN'
    | 'PLATFORM_REPAIR'
    | 'SOURCE_FIX';
  export type ConfirmAction =
    | 'ACCEPT_EXCEPTION'
    | 'ACCEPT_EXPLAIN'
    | 'RETURN'
    | 'VERIFY_PASS';

  /** 当前用户可见、窗口内创建的工单；计数为 int，人员 Long ID 无损传输。 */
  export interface Statistics {
    asOf: number;
    scope: 'authorized';
    window: number;
    countUnit: 'WORK_ORDER';
    sampleStatus: 'NO_SAMPLE' | 'SCORED';
    totalCount: number;
    ownerSummary: {
      count: number;
      ownerUserId?: Id | null;
      ownerUserName: string;
    }[];
    statusSummary: Record<string, number>;
    trend: { createdCount: number; date: string; processedCount: number }[];
    closeException: number;
    closeExplained: number;
    closeLegacy: number;
    closeVerifiedPass: number;
    coverage: string;
    currentOverdue: number;
  }

  export interface WorkOrder {
    id?: Id;
    sourceType?: string;
    etlReport?: InlineQualityReport;
    orderName?: string;
    orderCode?: string;
    taskId?: Id;
    taskName?: string;
    issueCount?: number;
    ownerUserId?: Id;
    ownerUserName?: string;
    creatorUserId?: Id;
    creatorUserName?: string;
    handlingDeptId?: Id;
    confirmerUserId?: Id;
    deadline?: number | string;
    orderStatus?: string;
    responsibilityStatus?: string;
    lockVersion?: number;
    currentSubmissionId?: Id;
    closeType?: string;
    closeReason?: string;
    confirmationPendingType?: string;
    lastError?: string;
    evidenceCompleteness?: string;
    everOverdue?: number;
    auditOpinion?: string;
    description?: string;
    issueIds?: Id[];
    allowedActions?: string[];
    createTime?: number | string;
    currentSubmission?: Submission;
    history?: Action[];
    recheckAttempt?: Recheck;
  }

  export interface UpdateRequest {
    id: Id;
    orderName: string;
    /** 打开编辑时读取的 lockVersion，提交时保留此值。 */
    expectedVersion: number;
    taskId?: Id;
    ownerUserId?: Id;
    handlingDeptId?: Id;
    confirmerUserId?: Id;
    deadline?: number | string;
    description?: string;
    problemType?: string;
    severity?: string;
    sourceType?: string;
    issueIds?: Id[];
  }

  export interface Submission {
    id?: Id;
    revision?: number;
    submissionType?: string;
    content?: string;
    evidenceRef?: string;
    repairOrderId?: Id;
    exceptionScope?: string;
    exceptionExpireAt?: number;
    status?: string;
    recheckStatus?: string;
    instanceId?: Id;
    comparableFlag?: number;
    submittedBy?: Id;
    submittedAt?: number | string;
    lastError?: string;
    rechecks?: Recheck[];
  }

  export interface Recheck {
    id: Id;
    attemptNo: number;
    instanceId?: Id;
    requestedBy?: Id;
    requestedAt?: number;
    status: string;
    comparableFlag?: number;
    evidenceJson?: string;
    lastError?: string;
    finishedAt?: number;
  }

  export interface Action {
    id?: Id;
    actionType?: string;
    fromStatus?: string;
    toStatus?: string;
    actorUserId?: Id;
    reason?: string;
    createTime?: number | string;
  }

  export interface Command {
    id: Id;
    expectedVersion: number;
    requestId: string;
    reason?: string;
    handlingDeptId?: Id;
    ownerUserId?: Id;
    confirmerUserId?: Id;
    submissionType?: SubmissionType;
    content?: string;
    evidenceRef?: string;
    repairOrderId?: Id;
    issueIds?: Id[];
    exceptionScope?: string;
    exceptionExpireAt?: number;
    submissionId?: Id;
    confirmAction?: ConfirmAction;
    newDeadline?: number | string;
    deadlineBasis?: string;
  }
}

function requestId() {
  return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`;
}

export function getWorkOrderPage(
  params: PageParam & {
    closeType?: string;
    orderName?: string;
    orderStatus?: string;
    ownerUserId?: QualityWorkOrderApi.Id;
    responsibilityStatus?: string;
    taskName?: string;
  },
) {
  return requestClient.get<PageResult<QualityWorkOrderApi.WorkOrder>>(
    '/governance/quality-work-order/page',
    { params },
  );
}

export function getWorkOrder(id: QualityWorkOrderApi.Id) {
  return requestClient.get<QualityWorkOrderApi.WorkOrder>(
    `/governance/quality-work-order/get`,
    { params: { id } },
  );
}

export function getMyTodoWorkOrderList() {
  return requestClient.get<QualityWorkOrderApi.WorkOrder[]>(
    '/governance/quality-work-order/my-todo',
  );
}

export function createWorkOrder(data: QualityWorkOrderApi.WorkOrder) {
  return requestClient.post('/governance/quality-work-order/create', data);
}

export function updateWorkOrder(
  data: QualityWorkOrderApi.UpdateRequest,
): Promise<boolean> {
  const { expectedVersion } = data;
  if (
    !Number.isInteger(expectedVersion) ||
    expectedVersion < 0 ||
    expectedVersion > 2_147_483_646
  ) {
    return Promise.reject(
      new Error('工单更新版本必须为 0 到 2147483646 的整数'),
    );
  }
  return requestClient.put<boolean>(
    '/governance/quality-work-order/update',
    data,
  );
}

export function deleteWorkOrder(id: QualityWorkOrderApi.Id) {
  return requestClient.delete(`/governance/quality-work-order/delete`, {
    params: { id },
  });
}

export function commandWorkOrder(
  path: string,
  data: QualityWorkOrderApi.Command,
) {
  return requestClient.post<QualityWorkOrderApi.WorkOrder>(
    `/governance/quality-work-order/${path}`,
    { ...data, requestId: data.requestId || requestId() },
  );
}

export function assignWorkOrder(data: QualityWorkOrderApi.Command) {
  return commandWorkOrder('assign', data);
}

export function claimWorkOrder(data: QualityWorkOrderApi.Command) {
  return commandWorkOrder('claim', data);
}

export function transferWorkOrderCommand(data: QualityWorkOrderApi.Command) {
  return commandWorkOrder('transfer', data);
}

export function submitWorkOrderResolution(data: QualityWorkOrderApi.Command) {
  return commandWorkOrder('submit-resolution', data);
}

export function recheckWorkOrder(data: QualityWorkOrderApi.Command) {
  return commandWorkOrder('recheck', data);
}

export function confirmWorkOrder(data: QualityWorkOrderApi.Command) {
  return commandWorkOrder('confirm', data);
}

export function withdrawWorkOrder(data: QualityWorkOrderApi.Command) {
  return commandWorkOrder('withdraw-submission', data);
}

export function extendWorkOrderDeadline(data: QualityWorkOrderApi.Command) {
  return commandWorkOrder('extend-deadline', data);
}

export function reopenWorkOrder(data: QualityWorkOrderApi.Command) {
  return commandWorkOrder('reopen', data);
}

export function getWorkOrderRawIssues(id: QualityWorkOrderApi.Id) {
  return requestClient.get<{
    issues: {
      evidenceTime?: number | string;
      issueCount?: number;
      issueId?: QualityWorkOrderApi.Id;
      liveValueNote?: string;
      locationSql?: string;
    }[];
  }>('/governance/quality-work-order/raw-issues', {
    params: { id },
  });
}

export function getWorkOrderStatistics(days?: number) {
  return requestClient.get<QualityWorkOrderApi.Statistics>(
    '/governance/quality-work-order/statistics',
    {
      params: days ? { days } : {},
    },
  );
}
