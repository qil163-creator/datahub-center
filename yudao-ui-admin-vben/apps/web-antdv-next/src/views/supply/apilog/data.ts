import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { SupplyApiLogApi } from '#/api/supply/apilog';

import { getRangePickerDefaultProps } from '#/utils';

import { formatSupplyDateTime } from '../utils/format';

/** 调用状态 */
export const LOG_STATUS_OPTIONS = [
  { label: '成功', value: 1, color: 'success' },
  { label: '失败', value: 0, color: 'error' },
];

export const REJECT_STAGE_OPTIONS = [
  { label: '认证', value: 'AUTH' },
  { label: '授权', value: 'GRANT' },
  { label: '发布合同', value: 'CONTRACT' },
  { label: 'IP限制', value: 'IP' },
  { label: '调用限流', value: 'RATE' },
  { label: '熔断', value: 'BREAKER' },
  { label: '执行', value: 'EXECUTE' },
];

/** 列表搜索表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'apiName',
      label: 'API名称',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入API服务名称',
      },
    },
    {
      fieldName: 'status',
      label: '状态',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: LOG_STATUS_OPTIONS.map(({ label, value }) => ({
          label,
          value,
        })),
        placeholder: '请选择状态',
      },
    },
    {
      fieldName: 'callTime',
      label: '调用时间',
      component: 'RangePicker',
      componentProps: {
        ...getRangePickerDefaultProps(),
        allowClear: true,
      },
    },
    {
      fieldName: 'rejectStage',
      label: '拒绝阶段',
      component: 'Select',
      componentProps: { allowClear: true, options: REJECT_STAGE_OPTIONS },
    },
    {
      fieldName: 'traceId',
      label: '追踪编号',
      component: 'Input',
      componentProps: { allowClear: true, maxlength: 64 },
    },
  ];
}

/** 列表字段 */
export function useGridColumns(): VxeTableGridOptions<SupplyApiLogApi.ApiLog>['columns'] {
  return [
    { field: 'id', title: '编号', width: 80 },
    {
      field: 'apiName',
      title: 'API服务名称',
      minWidth: 170,
      showOverflow: false,
    },
    {
      field: 'categoryId',
      title: 'API服务类目',
      width: 120,
      showOverflow: false,
      slots: { default: 'categoryId' },
    },
    { field: 'callerIp', title: '调用者IP', width: 130 },
    {
      field: 'rejectStage',
      title: '拒绝阶段',
      width: 100,
      formatter: ({ cellValue }) =>
        REJECT_STAGE_OPTIONS.find((item) => item.value === cellValue)?.label ??
        cellValue ??
        '-',
    },
    { field: 'traceId', title: '追踪编号', minWidth: 180 },
    { field: 'callPath', title: '调用接口地址', minWidth: 200 },
    { field: 'dataCount', title: '调用数据量', width: 100 },
    {
      field: 'durationMs',
      title: '调用耗时(秒)',
      width: 120,
      formatter: ({ cellValue }) => ((cellValue ?? 0) / 1000).toString(),
    },
    {
      field: 'status',
      title: '状态',
      width: 80,
      slots: { default: 'status' },
    },
    {
      field: 'callTime',
      title: '调用时间',
      width: 180,
      formatter: ({ cellValue }) => formatSupplyDateTime(cellValue) || '-',
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
