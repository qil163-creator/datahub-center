import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { GovernanceMetaCollectInstanceApi } from '#/api/governance/metacollectinstance';

import { getRangePickerDefaultProps } from '#/utils';

/** 实例状态 */
export const INSTANCE_STATUS_OPTIONS = [
  { label: '等待中', value: 0, color: 'default' },
  { label: '运行中', value: 1, color: 'processing' },
  { label: '成功', value: 2, color: 'success' },
  { label: '失败', value: 3, color: 'error' },
  { label: '部分成功', value: 4, color: 'warning' },
  { label: '已丢弃', value: 5, color: 'default' },
];

/** 触发方式 */
export const TRIGGER_TYPE_OPTIONS = [
  { label: '调度', value: 1 },
  { label: '手动', value: 2 },
  { label: '事件', value: 3 },
  { label: '导入', value: 4 },
  { label: '手工登记', value: 5 },
];

/** 日志级别颜色 */
export const LOG_LEVEL_COLORS: Record<string, string> = {
  ERROR: '#f5222d',
  INFO: 'inherit',
  WARN: '#faad14',
};

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

/** 耗时展示：毫秒 → 秒 */
export function formatDuration(durationMs?: null | number) {
  if (durationMs === undefined || durationMs === null) {
    return '-';
  }
  if (durationMs < 1000) {
    return `${durationMs}ms`;
  }
  return `${(durationMs / 1000).toFixed(1)}s`;
}

/** 列表的搜索表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'taskId',
      label: '任务编号',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '请输入任务编号' },
    },
    {
      fieldName: 'status',
      label: '采集状态',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: INSTANCE_STATUS_OPTIONS.map(({ label, value }) => ({
          label,
          value,
        })),
        placeholder: '请选择采集状态',
      },
    },
    {
      fieldName: 'triggerType',
      label: '触发方式',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: TRIGGER_TYPE_OPTIONS.map(({ label, value }) => ({
          label,
          value,
        })),
        placeholder: '请选择触发方式',
      },
    },
    {
      fieldName: 'createTime',
      label: '创建时间',
      component: 'RangePicker',
      componentProps: {
        ...getRangePickerDefaultProps(),
        allowClear: true,
      },
    },
  ];
}

/** 采集实例列表字段 */
export function useGridColumns(): VxeTableGridOptions<GovernanceMetaCollectInstanceApi.CollectInstance>['columns'] {
  return [
    { field: 'id', title: '编号', width: 70 },
    {
      field: 'taskName',
      title: '任务名称',
      minWidth: 170,
      slots: { default: 'taskName' },
    },
    {
      field: 'triggerType',
      title: '触发方式',
      width: 90,
      formatter: ({ cellValue }) =>
        optionLabel(TRIGGER_TYPE_OPTIONS, cellValue),
    },
    {
      field: 'status',
      title: '采集状态',
      width: 95,
      slots: { default: 'status' },
    },
    {
      field: 'stage',
      title: '阶段',
      width: 90,
      formatter: ({ cellValue }) => cellValue || '-',
    },
    { field: 'tableCount', title: '采集表数', width: 90 },
    {
      field: 'tableAdd',
      title: '变更数量',
      minWidth: 180,
      slots: { default: 'changes' },
    },
    {
      field: 'durationMs',
      title: '采集耗时',
      width: 90,
      formatter: ({ cellValue }) => formatDuration(cellValue),
    },
    {
      field: 'startTime',
      title: '开始时间',
      width: 180,
      formatter: 'formatDateTime',
    },
    {
      field: 'endTime',
      title: '结束时间',
      width: 180,
      formatter: 'formatDateTime',
    },
    {
      title: '操作',
      width: 140,
      fixed: 'right',
      showOverflow: false,
      className: 'metacollectinstance-action-column',
      slots: { default: 'actions' },
    },
  ];
}
