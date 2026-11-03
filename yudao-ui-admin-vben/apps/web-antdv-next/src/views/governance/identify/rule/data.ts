import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { SafetyIdentifyApi } from '#/api/governance/identify';

import { DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';

/** 匹配方式 */
export const MATCH_TYPE_OPTIONS = [
  { label: '字段名正则', value: 'COLUMN_NAME', color: 'blue' },
  { label: '注释关键词', value: 'COLUMN_COMMENT', color: 'cyan' },
  { label: '数据内容正则', value: 'DATA_REGEX', color: 'purple' },
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
      fieldName: 'ruleName',
      label: '规则名称',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入规则名称',
      },
    },
    {
      fieldName: 'matchType',
      label: '匹配方式',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: MATCH_TYPE_OPTIONS,
        placeholder: '请选择匹配方式',
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
export function useGridColumns(): VxeTableGridOptions<SafetyIdentifyApi.IdentifyRule>['columns'] {
  return [
    {
      field: 'id',
      title: '编号',
      minWidth: 70,
    },
    {
      field: 'ruleName',
      title: '规则名称',
      minWidth: 160,
    },
    {
      field: 'matchType',
      title: '匹配方式',
      minWidth: 110,
      slots: { default: 'matchType' },
    },
    {
      field: 'pattern',
      title: '匹配模式',
      minWidth: 200,
      slots: { default: 'pattern' },
    },
    {
      field: 'categoryName',
      title: '数据分类',
      minWidth: 120,
    },
    {
      field: 'levelName',
      title: '数据分级',
      minWidth: 100,
    },
    {
      title: '采样阈值',
      minWidth: 110,
      slots: { default: 'threshold' },
    },
    {
      field: 'status',
      title: '状态',
      minWidth: 90,
      cellRender: {
        name: 'CellDict',
        props: { type: DICT_TYPE.COMMON_STATUS },
      },
    },
    {
      field: 'createTime',
      title: '创建时间',
      minWidth: 180,
      formatter: 'formatDateTime',
    },
    {
      title: '操作',
      width: 180,
      fixed: 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ];
}
