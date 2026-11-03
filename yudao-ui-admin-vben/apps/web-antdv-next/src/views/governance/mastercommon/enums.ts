import type { GovernanceMasterManageApi } from '#/api/governance/mastermanage';

export const OPERATION_OPTIONS: {
  label: string;
  value: GovernanceMasterManageApi.Operation;
}[] = [
  { label: '新增', value: 'CREATE' },
  { label: '编辑', value: 'UPDATE' },
  { label: '删除', value: 'DELETE' },
];
export const APPROVAL_STATUS_OPTIONS: {
  color: string;
  label: string;
  value: GovernanceMasterManageApi.ApprovalStatus;
}[] = [
  { label: '待审批', value: 'PENDING', color: 'processing' },
  { label: '流程待启动', value: 'WAIT_START', color: 'warning' },
  { label: '审批通过', value: 'APPROVED', color: 'success' },
  { label: '执行中', value: 'APPLYING', color: 'processing' },
  { label: '已生效', value: 'APPLIED', color: 'success' },
  { label: '已驳回', value: 'REJECTED', color: 'error' },
  { label: '已取消', value: 'CANCELLED', color: 'default' },
  { label: '结果待核查', value: 'UNKNOWN', color: 'warning' },
];
export function operationLabel(value: string) {
  return OPERATION_OPTIONS.find((row) => row.value === value)?.label ?? value;
}
export function approvalStatus(value: string) {
  return (
    APPROVAL_STATUS_OPTIONS.find((row) => row.value === value) ?? {
      label: value,
      color: 'default',
    }
  );
}
