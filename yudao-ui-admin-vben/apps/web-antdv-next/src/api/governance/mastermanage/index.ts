import type { PageParam, PageResult } from '@vben/request';

import type { GovernanceMasterDataApi } from '../masterdata';

import { requestClient } from '#/api/request';

export namespace GovernanceMasterManageApi {
  export type Id = GovernanceMasterDataApi.Id;
  export type Operation = 'CREATE' | 'DELETE' | 'UPDATE';
  export type ApprovalStatus =
    | 'APPLIED'
    | 'APPLYING'
    | 'APPROVED'
    | 'CANCELLED'
    | 'PENDING'
    | 'REJECTED'
    | 'UNKNOWN'
    | 'WAIT_START';
  export interface Segment {
    segmentType: 'ATTRIBUTE' | 'CONSTANT' | 'DATE' | 'SEQUENCE';
    segmentValue: string;
    sepChar?: string;
  }
  export interface CodeRule {
    id?: Id;
    modelId: Id;
    modelName?: string;
    code: string;
    name: string;
    fieldName: string;
    description?: string;
    status: number;
    segments: Segment[];
    example?: string;
    createTime?: number;
  }
  export interface RulePageReq extends PageParam {
    modelId?: Id;
    name?: string;
  }
  export interface Approval {
    id: Id;
    dataId?: string;
    modelId: Id;
    modelName?: string;
    operation: Operation;
    status: ApprovalStatus;
    requestId: string;
    applicantId?: Id;
    applicantName?: string;
    createTime?: number;
    reviewerId?: Id;
    reviewerName?: string;
    reviewTime?: number;
    reviewComment?: string;
    processInstanceId?: string;
    bpmProcessKey?: string;
    lastError?: string;
    rows: GovernanceMasterDataApi.Row[];
    beforeRows: GovernanceMasterDataApi.Row[];
  }
  export interface ApprovalPageReq extends PageParam {
    modelId?: Id;
    operation?: Operation;
    status?: ApprovalStatus;
  }
  export interface ChangeLog {
    id: Id;
    changeId: Id;
    modelId: Id;
    modelName?: string;
    dataId?: string;
    changeType: Operation;
    propertyName?: string;
    beforeValue?: string;
    afterValue?: string;
    changedBy?: Id;
    changedByName?: string;
    changedAt?: number;
  }
  export interface ChangePageReq extends PageParam {
    modelId?: Id;
    changeType?: Operation;
  }
  export interface SubmitReceipt {
    id: Id;
    status: ApprovalStatus;
    approvalRequired: boolean;
    message?: string;
  }
  export interface SubmitReq {
    modelId: Id;
    requestId: string;
    operation: Operation;
    rows: GovernanceMasterDataApi.Row[];
  }
}

export function getMasterCodeRulePage(
  params: GovernanceMasterManageApi.RulePageReq,
) {
  return requestClient.get<PageResult<GovernanceMasterManageApi.CodeRule>>(
    '/governance/master-code-rule/page',
    { params },
  );
}
export function getMasterCodeRule(id: GovernanceMasterManageApi.Id) {
  return requestClient.get<GovernanceMasterManageApi.CodeRule>(
    '/governance/master-code-rule/get',
    { params: { id } },
  );
}
export function createMasterCodeRule(data: GovernanceMasterManageApi.CodeRule) {
  return requestClient.post<GovernanceMasterManageApi.Id>(
    '/governance/master-code-rule/create',
    data,
  );
}
export function updateMasterCodeRule(data: GovernanceMasterManageApi.CodeRule) {
  return requestClient.put<boolean>(
    '/governance/master-code-rule/update',
    data,
  );
}
export function deleteMasterCodeRule(id: GovernanceMasterManageApi.Id) {
  return requestClient.delete<boolean>('/governance/master-code-rule/delete', {
    params: { id },
  });
}
export function deleteMasterCodeRuleList(ids: GovernanceMasterManageApi.Id[]) {
  return requestClient.delete<boolean>(
    '/governance/master-code-rule/delete-list',
    { params: { ids: ids.join(',') } },
  );
}
export function updateMasterCodeRuleStatus(
  id: GovernanceMasterManageApi.Id,
  status: number,
) {
  return requestClient.put<boolean>(
    '/governance/master-code-rule/update-status',
    { id, status },
  );
}
export function previewMasterCodeRule(
  data: GovernanceMasterManageApi.CodeRule,
) {
  return requestClient.post<string>(
    '/governance/master-code-rule/preview',
    data,
    { silentError: true },
  );
}
export function getMasterApprovalPage(
  params: GovernanceMasterManageApi.ApprovalPageReq,
) {
  return requestClient.get<PageResult<GovernanceMasterManageApi.Approval>>(
    '/governance/master-approval/page',
    { params },
  );
}
export function getMasterApproval(id: GovernanceMasterManageApi.Id) {
  return requestClient.get<GovernanceMasterManageApi.Approval>(
    '/governance/master-approval/get',
    { params: { id } },
  );
}
export function approveMasterData(data: {
  id: GovernanceMasterManageApi.Id;
  remark?: string;
}) {
  return requestClient.put<boolean>(
    '/governance/master-approval/approve',
    data,
  );
}
export function rejectMasterData(data: {
  id: GovernanceMasterManageApi.Id;
  remark?: string;
}) {
  return requestClient.put<boolean>('/governance/master-approval/reject', data);
}
export function reconcileMasterApproval(id: GovernanceMasterManageApi.Id) {
  return requestClient.put<boolean>(
    '/governance/master-approval/reconcile',
    undefined,
    { params: { id } },
  );
}
export function resolveMasterApproval(data: {
  evidenceRef: string;
  id: GovernanceMasterManageApi.Id;
  outcome: 'APPLIED' | 'NO_EFFECT';
  reason: string;
}) {
  return requestClient.put<boolean>(
    '/governance/master-approval/resolve',
    data,
  );
}
export function getMasterChangeLogPage(
  params: GovernanceMasterManageApi.ChangePageReq,
) {
  return requestClient.get<PageResult<GovernanceMasterManageApi.ChangeLog>>(
    '/governance/master-change-log/page',
    { params },
  );
}
export function submitMasterData(data: GovernanceMasterManageApi.SubmitReq) {
  return requestClient.post<GovernanceMasterManageApi.SubmitReceipt>(
    '/governance/master-data/submit',
    data,
  );
}
