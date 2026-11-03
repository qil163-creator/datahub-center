import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { SafetyStaticTaskApi } from '#/api/governance/statictask';

import { DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';

/** 写入模式 */
export const WRITE_MODE_OPTIONS = [
  { label: '全量覆盖', value: 'OVERWRITE', color: 'orange' },
  { label: '追加', value: 'APPEND', color: 'blue' },
];

/** 运行状态 */
export const RUN_STATUS_OPTIONS = [
  { label: '运行中', value: 2, color: 'processing' },
  { label: '成功', value: 3, color: 'success' },
  { label: '失败', value: 4, color: 'error' },
];

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
      fieldName: 'taskName',
      label: '任务名称',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入任务名称',
      },
    },
    {
      fieldName: 'status',
      label: '状态',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: getDictOptions(DICT_TYPE.COMMON_STATUS, 'number'),
        placeholder: '请选择状态',
      },
    },
  ];
}

/** 列表的字段 */
export function useGridColumns(): VxeTableGridOptions<SafetyStaticTaskApi.Task>['columns'] {
  return [
    { type: 'checkbox', width: 40 },
    {
      field: 'id',
      title: '编号',
      minWidth: 80,
    },
    {
      field: 'taskName',
      title: '任务名称',
      minWidth: 160,
    },
    {
      title: '源表',
      minWidth: 200,
      slots: { default: 'source' },
    },
    {
      title: '目标表',
      minWidth: 200,
      slots: { default: 'target' },
    },
    {
      field: 'writeMode',
      title: '写入模式',
      minWidth: 100,
      slots: { default: 'writeMode' },
    },
    {
      title: '调度',
      minWidth: 180,
      slots: { default: 'schedule' },
    },
    {
      field: 'status',
      title: '状态',
      minWidth: 90,
      slots: { default: 'status' },
    },
    {
      field: 'createTime',
      title: '创建时间',
      minWidth: 180,
      formatter: 'formatDateTime',
    },
    {
      title: '操作',
      width: 300,
      fixed: 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ];
}
