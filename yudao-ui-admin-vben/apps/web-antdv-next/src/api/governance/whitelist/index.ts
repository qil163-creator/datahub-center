import type { Dayjs } from 'dayjs';

import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace SafetyWhitelistApi {
  /** 数据安全--脱敏白名单生效主体信息 */
  export interface WhitelistSubject {
    id: number; // 主体编号
    whitelistId?: number; // 所属白名单编号
    subjectId?: number; // 主体编号
  }

  /** 数据安全--脱敏白名单信息 */
  export interface Whitelist {
    id: number; // 白名单编号
    name?: string; // 白名单名称
    subjectType?: string; // 生效主体
    scene?: string; // 豁免场景
    objectType?: string; // 豁免范围
    objectId: number; // 豁免对象编号
    effectiveTime: Dayjs | string; // 生效开始时间
    expireTime: Dayjs | string; // 生效结束时间
    status?: number; // 状态
    approvalStatus?: string; // 审批状态 DRAFT/PENDING/APPROVED/REJECTED
    applyReason?: string; // 申请理由
    approver?: string; // 审批人
    description: string; // 描述
    remark: string; // 备注
    whitelistSubjects?: WhitelistSubject[];
  }

  /** 审批记录 */
  export interface WhitelistApproval {
    id: number;
    whitelistId: number;
    action?: string; // SUBMIT / APPROVE / REJECT
    opinion?: string;
    creator?: string;
    createTime?: string;
  }
}

/** 查询数据安全--脱敏白名单分页 */
export function getWhitelistPage(params: PageParam) {
  return requestClient.get<PageResult<SafetyWhitelistApi.Whitelist>>(
    '/governance/safety-whitelist/page',
    { params },
  );
}

/** 查询数据安全--脱敏白名单详情 */
export function getWhitelist(id: number) {
  return requestClient.get<SafetyWhitelistApi.Whitelist>(
    `/governance/safety-whitelist/get?id=${id}`,
  );
}

/** 新增数据安全--脱敏白名单 */
export function createWhitelist(data: SafetyWhitelistApi.Whitelist) {
  return requestClient.post('/governance/safety-whitelist/create', data);
}

/** 修改数据安全--脱敏白名单 */
export function updateWhitelist(data: SafetyWhitelistApi.Whitelist) {
  return requestClient.put('/governance/safety-whitelist/update', data);
}

/** 删除数据安全--脱敏白名单 */
export function deleteWhitelist(id: number) {
  return requestClient.delete(`/governance/safety-whitelist/delete?id=${id}`);
}

/** 批量删除数据安全--脱敏白名单 */
export function deleteWhitelistList(ids: number[]) {
  return requestClient.delete(
    `/governance/safety-whitelist/delete-list?ids=${ids.join(',')}`,
  );
}

/** 导出数据安全--脱敏白名单 */
export function exportWhitelist(params: any) {
  return requestClient.download('/governance/safety-whitelist/export-excel', {
    params,
  });
}

// ==================== 审批流 ====================

/** 提交审批 */
export function submitWhitelist(id: number) {
  return requestClient.put('/governance/safety-whitelist/submit', null, {
    params: { id },
  });
}

/** 审批通过 */
export function approveWhitelist(id: number, opinion?: string) {
  return requestClient.put('/governance/safety-whitelist/approve', null, {
    params: { id, opinion },
  });
}

/** 审批驳回 */
export function rejectWhitelist(id: number, opinion?: string) {
  return requestClient.put('/governance/safety-whitelist/reject', null, {
    params: { id, opinion },
  });
}

/** 查询审批记录列表 */
export function getWhitelistApprovalList(whitelistId: number) {
  return requestClient.get<SafetyWhitelistApi.WhitelistApproval[]>(
    `/governance/safety-whitelist/approval-list?whitelistId=${whitelistId}`,
  );
}

// ==================== 子表（数据安全--脱敏白名单生效主体） ====================

/** 获得数据安全--脱敏白名单生效主体列表 */
export function getWhitelistSubjectListByWhitelistId(whitelistId: number) {
  return requestClient.get<SafetyWhitelistApi.WhitelistSubject[]>(
    `/governance/safety-whitelist/whitelist-subject/list-by-whitelist-id?whitelistId=${whitelistId}`,
  );
}
