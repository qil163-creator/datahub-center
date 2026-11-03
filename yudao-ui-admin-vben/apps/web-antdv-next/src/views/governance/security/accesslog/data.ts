import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { SafetySecurityApi } from '#/api/governance/security';

import { getRangePickerDefaultProps } from '#/utils';

/** 访问类型 */
export const ACCESS_TYPE_OPTIONS = [
  { label: '数据服务 API', value: 'API', color: 'blue' },
  { label: '静态脱敏任务', value: 'STATIC_TASK', color: 'purple' },
  { label: '数据查询', value: 'QUERY', color: 'cyan' },
  { label: '数据开发调试', value: 'DEV', color: 'geekblue' },
  { label: '数据探查', value: 'EXPLORE', color: 'gold' },
  { label: '数据接入同步', value: 'ACCESS', color: 'green' },
];

/** 脱敏结果 */
export const MASKED_FLAG_OPTIONS = [
  { label: '已脱敏', value: 1, color: 'success' },
  { label: '明文（白名单豁免）', value: 0, color: 'error' },
];

export function optionLabel(
  options: { label: string; value: number | string }[],
  value?: null | number | string,
) {
  return (
    options.find((item) => item.value === value)?.label ??
    (value === null || value === undefined || value === '' ? '-' : String(value))
  );
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
      fieldName: 'userName',
      label: '访问用户',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入访问用户',
      },
    },
    {
      fieldName: 'accessType',
      label: '访问类型',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: ACCESS_TYPE_OPTIONS,
        placeholder: '请选择访问类型',
      },
    },
    {
      fieldName: 'resourceName',
      label: '资源名称',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入 API/任务名称',
      },
    },
    {
      fieldName: 'tableName',
      label: '涉及表',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入表名',
      },
    },
    {
      fieldName: 'maskedFlag',
      label: '脱敏结果',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: MASKED_FLAG_OPTIONS,
        placeholder: '请选择脱敏结果',
      },
    },
    {
      fieldName: 'accessTime',
      label: '访问时间',
      component: 'RangePicker',
      componentProps: {
        ...getRangePickerDefaultProps(),
        allowClear: true,
      },
    },
  ];
}

/** 列表的字段 */
export function useGridColumns(): VxeTableGridOptions<SafetySecurityApi.AccessLog>['columns'] {
  return [
    {
      field: 'id',
      title: '编号',
      width: 80,
    },
    {
      field: 'userName',
      title: '访问用户',
      minWidth: 110,
    },
    {
      field: 'accessType',
      title: '访问类型',
      minWidth: 120,
      slots: { default: 'accessType' },
    },
    {
      field: 'resourceName',
      title: '资源名称',
      minWidth: 180,
      showOverflow: 'tooltip',
    },
    {
      field: 'tableName',
      title: '涉及表',
      minWidth: 140,
      showOverflow: 'tooltip',
    },
    {
      field: 'sensitiveColumns',
      title: '敏感字段',
      minWidth: 200,
      showOverflow: 'tooltip',
    },
    {
      field: 'columnCount',
      title: '字段数',
      width: 80,
    },
    {
      field: 'maskedFlag',
      title: '脱敏结果',
      minWidth: 150,
      slots: { default: 'maskedFlag' },
    },
    {
      field: 'callerIp',
      title: '来源 IP',
      minWidth: 130,
    },
    {
      field: 'accessTime',
      title: '访问时间',
      minWidth: 180,
      formatter: 'formatDateTime',
    },
    {
      title: '操作',
      width: 90,
      fixed: 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ];
}
