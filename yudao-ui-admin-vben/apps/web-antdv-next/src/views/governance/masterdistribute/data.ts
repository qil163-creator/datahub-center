import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { GovernanceMasterDistributeApi } from '#/api/governance/masterdistribute';

import { formatDateTime } from '@vben/utils';

/** 派发类型 */
export const DISTRIBUTE_TYPE_OPTIONS = [
  { label: '数据库', value: 1, color: 'blue' },
  { label: 'API 接口', value: 2, color: 'purple' },
  { label: '消息队列', value: 3, color: 'orange' },
];

/** 调度类型 */
export const TASK_TYPE_OPTIONS = [
  { label: '一次性', value: 1 },
  { label: '周期性', value: 2 },
];

/** 发布状态 */
export const STATUS_OPTIONS = [
  { label: '未发布', value: 0, color: 'default' },
  { label: '已发布', value: 1, color: 'success' },
];

/** 运行状态 */
export const RUN_STATUS_OPTIONS = [
  { label: '等待派发', value: 1, color: 'warning' },
  { label: '运行中', value: 2, color: 'processing' },
  { label: '成功', value: 3, color: 'success' },
  { label: '失败', value: 4, color: 'error' },
  { label: '结果未知，待核查', value: 5, color: 'warning' },
  { label: '已人工核查处置（非成功）', value: 6, color: 'default' },
];

/** 没有可信空闲状态时不提供重跑，后端仍负责最终门禁。 */
export function isRunBlocked(row: GovernanceMasterDistributeApi.Distribute) {
  return row.blockingRunStatus !== 0;
}

export function runBlockedReason(
  row: GovernanceMasterDistributeApi.Distribute,
) {
  switch (row.blockingRunStatus) {
    case 0: {
      return undefined;
    }
    case 1: {
      return '已有任务等待派发，请等待完成或在执行记录中停止。';
    }
    case 2: {
      return '任务正在执行，请等待完成或在执行记录中停止。';
    }
    case 5: {
      return '上次派发结果未知，请先在执行记录中核查处置，避免重复发送。';
    }
    default: {
      return '执行状态未确认，请刷新后重试。';
    }
  }
}

export function nextRunTimeLabel(
  row: GovernanceMasterDistributeApi.Distribute,
) {
  if (row.taskType === 1) return '不适用（一次性）';
  if (row.status === 0) return '未排期';
  return row.nextRunTime === null || row.nextRunTime === undefined
    ? '未记录'
    : formatDateTime(row.nextRunTime);
}

export function releaseUserLabel(
  row: GovernanceMasterDistributeApi.Distribute,
) {
  if (row.releaseUserName) return row.releaseUserName;
  if (row.releaseUserId !== null && row.releaseUserId !== undefined)
    return `用户 #${row.releaseUserId}`;
  return row.status === 1 ||
    (row.releaseTime !== null && row.releaseTime !== undefined)
    ? '未记录'
    : '暂无发布记录';
}

export function releaseTimeLabel(
  row: GovernanceMasterDistributeApi.Distribute,
) {
  if (row.releaseTime !== null && row.releaseTime !== undefined)
    return formatDateTime(row.releaseTime);
  return row.status === 1 ||
    (row.releaseUserId !== null && row.releaseUserId !== undefined)
    ? '未记录'
    : '暂无发布记录';
}

/** 触发方式 */
export const RUN_TYPE_OPTIONS = [
  { label: '自动调度', value: 1 },
  { label: '手动执行', value: 2 },
];

export function optionLabel(
  options: { label: string; value: number | string }[],
  value?: null | number | string,
) {
  return options.find((item) => item.value === value)?.label ?? '-';
}

export function optionColor(
  options: { color?: string; label: string; value: number | string }[],
  value?: null | number | string,
) {
  return options.find((item) => item.value === value)?.color ?? 'default';
}

/** 列表的搜索表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'name',
      label: '下游系统',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '请输入下游系统' },
    },
    {
      fieldName: 'latestRunStatus',
      label: '推送状态',
      component: 'Select',
      componentProps: {
        allowClear: true,
        placeholder: '请选择推送状态',
        options: RUN_STATUS_OPTIONS,
      },
    },
  ];
}

/** 派发任务列表字段 */
export function useGridColumns(): VxeTableGridOptions<GovernanceMasterDistributeApi.Distribute>['columns'] {
  return [
    { type: 'checkbox', width: 45 },
    { field: 'id', title: '编号', width: 90, sortable: true },
    {
      field: 'masterModelName',
      title: '实体模型信息',
      minWidth: 480,
      slots: { default: 'model' },
    },
    { field: 'name', title: '下游系统', minWidth: 180 },
    {
      field: 'config.api.url',
      title: '下游接收地址',
      minWidth: 320,
      slots: { default: 'destination' },
    },
    {
      field: 'latestRunStatus',
      title: '推送状态',
      width: 150,
      slots: { default: 'blockingRunStatus' },
    },
    {
      field: 'totalCount',
      title: '推送结果（总/成功/失败）',
      minWidth: 240,
      slots: { default: 'result' },
    },
    {
      field: 'status',
      title: '状态',
      width: 100,
      slots: { default: 'status' },
    },
    {
      field: 'lastPushTime',
      title: '最后推送时间',
      minWidth: 190,
      formatter: ({ cellValue }) =>
        cellValue === null || cellValue === undefined
          ? '-'
          : formatDateTime(cellValue),
    },
    {
      title: '操作',
      width: 240,
      fixed: 'right',
      showOverflow: false,
      className: 'masterdistribute-action-column',
      slots: { default: 'actions' },
    },
  ];
}
