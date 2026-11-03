import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { SafetyIdentifyApi } from '#/api/governance/identify';

/** 打标状态 */
export const TAG_STATUS_OPTIONS = [
  { label: '待确认', value: 'PENDING', color: 'warning' },
  { label: '已确认', value: 'CONFIRMED', color: 'success' },
  { label: '已驳回', value: 'REJECTED', color: 'default' },
];

/** 打标来源 */
export const SOURCE_OPTIONS = [
  { label: '自动识别', value: 'AUTO', color: 'blue' },
  { label: '人工添加', value: 'MANUAL', color: 'purple' },
];

/** 扫描运行状态 */
export const SCAN_STATUS_OPTIONS = [
  { label: '运行中', value: 2, color: 'processing' },
  { label: '成功', value: 3, color: 'success' },
  { label: '失败', value: 4, color: 'error' },
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
      fieldName: 'tableName',
      label: '表名',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入表名',
      },
    },
    {
      fieldName: 'columnName',
      label: '字段名',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入字段名',
      },
    },
    {
      fieldName: 'tagStatus',
      label: '打标状态',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: TAG_STATUS_OPTIONS,
        placeholder: '请选择打标状态',
      },
    },
    {
      fieldName: 'source',
      label: '来源',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: SOURCE_OPTIONS,
        placeholder: '请选择来源',
      },
    },
  ];
}

/** 列表的字段 */
export function useGridColumns(): VxeTableGridOptions<SafetyIdentifyApi.FieldTag>['columns'] {
  return [
    { type: 'checkbox', width: 40 },
    {
      title: '字段',
      minWidth: 240,
      slots: { default: 'column' },
    },
    {
      field: 'categoryName',
      title: '数据分类',
      minWidth: 120,
    },
    {
      title: '数据分级',
      minWidth: 100,
      slots: { default: 'level' },
    },
    {
      field: 'source',
      title: '来源',
      minWidth: 100,
      slots: { default: 'source' },
    },
    {
      field: 'tagStatus',
      title: '打标状态',
      minWidth: 100,
      slots: { default: 'tagStatus' },
    },
    {
      field: 'hitDesc',
      title: '命中说明',
      minWidth: 220,
      showOverflow: 'tooltip',
    },
    {
      field: 'createTime',
      title: '识别时间',
      minWidth: 180,
      formatter: 'formatDateTime',
    },
    {
      title: '操作',
      width: 240,
      fixed: 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ];
}
