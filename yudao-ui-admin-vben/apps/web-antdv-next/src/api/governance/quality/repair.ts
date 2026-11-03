import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace QualityRepairOrderApi {
  export type Id = number | string;
  /** 修复工单 */
  export interface RepairOrder {
    id?: Id;
    orderTitle?: string;
    repairType?: string;
    objectId?: Id;
    tableName?: string;
    checkField?: string;
    targetField?: string;
    repairConfig?: string;
    repairSql?: string;
    approvedSnapshot?: string;
    approveStatus?: string;
    execStatus?: string;
    verifyStatus?: string;
    repairDesc?: string;
    execResult?: string;
    execTime?: string;
    creator?: string;
    createTime?: string;
  }
}

/** 修复工单分页 */
export function getRepairOrderPage(
  params: PageParam & {
    approveStatus?: string;
    execStatus?: string;
    orderTitle?: string;
    repairType?: string;
    verifyStatus?: string;
  },
) {
  return requestClient.get<PageResult<QualityRepairOrderApi.RepairOrder>>(
    '/governance/quality-repair-order/page',
    { params },
  );
}

/** 修复工单详情 */
export function getRepairOrder(id: QualityRepairOrderApi.Id) {
  return requestClient.get<QualityRepairOrderApi.RepairOrder>(
    `/governance/quality-repair-order/get?id=${id}`,
  );
}

/** 新增修复工单 */
export function createRepairOrder(data: QualityRepairOrderApi.RepairOrder) {
  return requestClient.post('/governance/quality-repair-order/create', data);
}

/** 修改修复工单 */
export function updateRepairOrder(data: QualityRepairOrderApi.RepairOrder) {
  return requestClient.put('/governance/quality-repair-order/update', data);
}

/** 删除修复工单 */
export function deleteRepairOrder(id: QualityRepairOrderApi.Id) {
  return requestClient.delete(
    `/governance/quality-repair-order/delete?id=${id}`,
  );
}

/** 预览修复SQL */
export function previewRepairSql(data: QualityRepairOrderApi.RepairOrder) {
  return requestClient.post<string>(
    '/governance/quality-repair-order/preview-sql',
    data,
  );
}

/** 审批修复工单 */
export function approveRepairOrder(
  id: QualityRepairOrderApi.Id,
  approveStatus: string,
) {
  return requestClient.put(
    `/governance/quality-repair-order/approve?id=${id}&approveStatus=${approveStatus}`,
  );
}

/** 执行修复 */
export function executeRepairOrder(id: QualityRepairOrderApi.Id) {
  return requestClient.post(
    `/governance/quality-repair-order/execute?id=${id}`,
  );
}

/** 验证修复结果 */
export function verifyRepairOrder(
  id: QualityRepairOrderApi.Id,
  verifyStatus: string,
) {
  return requestClient.put(
    `/governance/quality-repair-order/verify?id=${id}&verifyStatus=${verifyStatus}`,
  );
}

export function resubmitRepairOrder(id: number | string) {
  return requestClient.post('/governance/quality-repair-order/resubmit', null, {
    params: { id },
  });
}
