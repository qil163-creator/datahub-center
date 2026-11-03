import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';

import { formatDate } from '@vben/utils';

/** 字段数据类型（dbswitch 逻辑类型） */
export const FIELD_TYPE_OPTIONS = [
  { label: '字符串', value: 2 },
  { label: '整数', value: 5 },
  { label: '精确小数', value: 6 },
  { label: '浮点数（近似）', value: 1 },
  { label: '布尔', value: 4 },
  { label: '日期', value: 3 },
  { label: '日期时间', value: 9 },
  { label: '时间', value: 10 },
];

/** 模型发布状态 */
export const MODEL_STATUS_OPTIONS = [
  { label: '草稿', value: 0, color: 'warning' },
  { label: '已发布', value: 1, color: 'success' },
  { label: '已下线', value: 2, color: 'default' },
];

export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'name',
      label: '模型名称',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入模型名称或物理表名',
      },
    },
    {
      fieldName: 'status',
      label: '模型状态',
      component: 'Select',
      componentProps: {
        allowClear: true,
        placeholder: '请选择模型状态',
        options: MODEL_STATUS_OPTIONS,
      },
    },
  ];
}

export function useGridColumns(
  _compact = false,
): VxeTableGridOptions<GovernanceMasterDataApi.Model>['columns'] {
  return [
    { type: 'checkbox', width: 40 },
    { type: 'seq', title: '编号', width: 65 },
    {
      field: 'tableCn',
      title: '主数据模型信息',
      minWidth: 340,
      align: 'left',
      slots: { default: 'modelInfo' },
    },
    {
      field: 'domainName',
      title: '所属主数据域',
      minWidth: 150,
      align: 'left',
      slots: { default: 'domain' },
    },
    {
      field: 'fieldCount',
      title: '统计信息',
      minWidth: 150,
      align: 'left',
      slots: { default: 'statistics' },
    },
    {
      field: 'status',
      title: '状态',
      width: 90,
      slots: { default: 'status' },
    },
    {
      field: 'creatorName',
      title: '创建人',
      minWidth: 110,
      formatter: ({ row }) => row.creatorName || row.creator || '-',
    },
    {
      field: 'createTime',
      title: '创建时间',
      width: 165,
      formatter: ({ cellValue }) =>
        cellValue === undefined || cellValue === null
          ? '-'
          : formatDate(cellValue, 'YYYY-MM-DD HH:mm'),
    },
    {
      title: '操作',
      width: 260,
      fixed: 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ];
}

/** 长度可编辑的类型（字符串 / 小数） */
export function lengthEditable(fieldType?: number) {
  return fieldType === 2 || fieldType === 6;
}

/** 小数位可编辑的类型（小数） */
export function scaleEditable(fieldType?: number) {
  return fieldType === 6;
}

export function fieldTypeLabel(fieldType?: number) {
  return (
    FIELD_TYPE_OPTIONS.find((item) => item.value === fieldType)?.label ?? '-'
  );
}

/** 日期类字段的录入格式提示 */
export function fieldTypePlaceholder(fieldType?: number) {
  switch (fieldType) {
    case 1: {
      return '近似浮点数，如 3.14';
    }
    case 3: {
      return '格式：2026-01-01';
    }
    case 4: {
      return 'true / false';
    }
    case 5: {
      return '整数';
    }
    case 6: {
      return '精确小数，如 123456789012345678.123456（保持文本）';
    }
    case 9: {
      return '格式：2026-01-01 12:00:00';
    }
    case 10: {
      return '格式：12:00:00';
    }
    default: {
      return '请输入';
    }
  }
}
