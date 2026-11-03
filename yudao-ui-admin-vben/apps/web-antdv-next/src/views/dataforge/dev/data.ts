import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { DataforgeDevApi } from '#/api/dataforge/dev';

import { getRangePickerDefaultProps } from '#/utils';

/** DS 同步状态与期望调度开关分别展示，避免将同步等待误认为已生效。 */
export const DEV_SCHEDULE_SYNC_OPTIONS = [
  { label: '未配置', value: 'NOT_CONFIGURED', color: 'default' },
  { label: 'DS 同步中', value: 'PENDING', color: 'processing' },
  { label: 'DS 已同步', value: 'SYNCED', color: 'success' },
  { label: 'DS 同步失败', value: 'ERROR', color: 'error' },
];

/** 运行状态 */
export const DEV_RECORD_STATUS_OPTIONS = [
  { label: '等待依赖/资源', value: 'WAITING', color: 'default' },
  { label: '提交中', value: 'SUBMITTING', color: 'processing' },
  { label: '结果待核查', value: 'UNKNOWN', color: 'warning' },
  { label: '已请求停止', value: 'CANCEL_REQUESTED', color: 'warning' },
  { label: '运行中', value: 'RUNNING', color: 'processing' },
  { label: '成功', value: 'SUCCESS', color: 'success' },
  { label: '失败', value: 'FAILED', color: 'error' },
  { label: '已停止', value: 'STOPPED', color: 'default' },
];

/** 触发方式 */
export const DEV_TRIGGER_TYPE_OPTIONS = [
  { label: '手动', value: 'MANUAL' },
  { label: '调度', value: 'SCHEDULE' },
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

/** 运行时长：毫秒 → 120 ms / 1.2 s */
export function formatDuration(ms?: null | number) {
  if (ms === null || ms === undefined) {
    return '-';
  }
  if (ms < 1000) {
    return `${ms} ms`;
  }
  return `${(ms / 1000).toFixed(1)} s`;
}

/** 任务实例列表的搜索表单 */
export function useInstanceGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'taskName',
      label: '任务名称',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '请输入任务名称' },
    },
    {
      fieldName: 'status',
      label: '状态',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: DEV_RECORD_STATUS_OPTIONS.map(({ label, value }) => ({
          label,
          value,
        })),
        placeholder: '请选择状态',
      },
    },
    {
      fieldName: 'triggerType',
      label: '触发方式',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: DEV_TRIGGER_TYPE_OPTIONS,
        placeholder: '请选择触发方式',
      },
    },
    {
      fieldName: 'startTime',
      label: '开始时间',
      component: 'RangePicker',
      componentProps: {
        ...getRangePickerDefaultProps(),
        allowClear: true,
      },
    },
  ];
}

/** 任务实例列表字段 */
export function useInstanceGridColumns(): VxeTableGridOptions<DataforgeDevApi.Record>['columns'] {
  return [
    { field: 'id', title: '编号', width: 70 },
    { field: 'taskName', title: '任务名称', minWidth: 170, align: 'left' },
    {
      field: 'datasourceName',
      title: '数据源',
      width: 130,
      formatter: ({ cellValue }) => cellValue || '-',
    },
    {
      field: 'triggerType',
      title: '触发方式',
      width: 90,
      slots: { default: 'triggerType' },
    },
    {
      field: 'status',
      title: '状态',
      width: 90,
      slots: { default: 'status' },
    },
    {
      field: 'startTime',
      title: '开始时间',
      width: 180,
      formatter: 'formatDateTime',
    },
    {
      field: 'durationMs',
      title: '耗时',
      width: 90,
      formatter: ({ cellValue }) => formatDuration(cellValue),
    },
    {
      field: 'statementTotal',
      title: '语句',
      width: 90,
      slots: { default: 'statements' },
    },
    {
      field: 'businessDate',
      title: '业务日期',
      width: 120,
      formatter: 'formatDate',
    },
    { field: 'versionId', title: '运行版本', width: 110 },
    {
      field: 'qualityStatus',
      title: '质量验收',
      width: 145,
      slots: { default: 'qualityStatus' },
    },
    { field: 'affectedRows', title: '影响行数', width: 90 },
    {
      field: 'creator',
      title: '执行人',
      width: 90,
      formatter: ({ cellValue }) => cellValue || '-',
    },
    {
      title: '操作',
      width: 140,
      fixed: 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ];
}
