import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { GovernanceMetaObjectApi } from '#/api/governance/metaobject';

import { formatDateTime } from '@vben/utils';

import { metadataTime } from '../metadata-common/helpers';

/** 发布状态 */
export const PUBLISH_STATUS_OPTIONS = [
  { label: '未发布', value: 0, color: 'default' },
  { label: '已发布', value: 1, color: 'success' },
];

/** 对象状态 */
export const OBJECT_STATUS_OPTIONS = [
  { label: '正常', value: 1, color: 'success' },
  { label: '待确认删除', value: 2, color: 'warning' },
  { label: '已删除', value: 3, color: 'default' },
];

/** 对象来源 */
export const ORIGIN_OPTIONS = [
  { label: '采集', value: 1, color: 'blue' },
  { label: '手工', value: 2, color: 'purple' },
  { label: '导入', value: 3, color: 'cyan' },
];

/** 变更类型 */
export const CHANGE_TYPE_OPTIONS = [
  { label: '新增', value: 1, color: 'success' },
  { label: '修改', value: 2, color: 'processing' },
  { label: '删除', value: 3, color: 'error' },
];

/** 变更来源 */
export const CHANGE_SOURCE_OPTIONS = [
  { label: '采集', value: 1, color: 'blue' },
  { label: '手工', value: 2, color: 'purple' },
  { label: '导入', value: 3, color: 'cyan' },
  { label: '回滚', value: 4, color: 'orange' },
];

/** 变更对象类型 */
export const CHANGE_OBJECT_TYPE_OPTIONS = [
  { label: '库', value: 1 },
  { label: '表', value: 2 },
  { label: '字段', value: 3 },
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

/** 注释展示：平台注释优先于源库注释 */
export function displayComment(row: {
  customComment?: string;
  sourceComment?: string;
}) {
  return row.customComment || row.sourceComment || '-';
}

/** 时间展示：后端 LocalDateTime 序列化为毫秒时间戳，统一格式化为 YYYY-MM-DD HH:mm:ss */
export function formatTime(value?: null | number | string) {
  if (value === null || value === undefined || value === '') {
    return '-';
  }
  return (formatDateTime(value) as string) || '-';
}

/** 存储大小展示 */
export function formatSize(bytes?: null | number) {
  if (bytes == null || bytes <= 0) {
    return '-';
  }
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let value = bytes;
  let index = 0;
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024;
    index += 1;
  }
  return `${value.toFixed(value >= 100 ? 0 : 1)}${units[index]}`;
}

/** 列表的搜索表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'tableName',
      label: '表名称',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '请输入表名称' },
    },
    {
      fieldName: 'comment',
      label: '注释/业务名称',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '请输入注释关键字' },
    },
    {
      fieldName: 'publishStatus',
      label: '发布状态',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: PUBLISH_STATUS_OPTIONS.map(({ label, value }) => ({
          label,
          value,
        })),
        placeholder: '请选择发布状态',
      },
    },
  ];
}

/** 表元数据列表字段 */
export function useGridColumns(): VxeTableGridOptions<GovernanceMetaObjectApi.MetaTable>['columns'] {
  return [
    { type: 'checkbox', width: 40 },
    { field: 'id', title: '编号', width: 70 },
    { field: 'dbName', title: '所属库', width: 110 },
    {
      field: 'tableName',
      title: '表名称',
      minWidth: 170,
      slots: { default: 'tableName' },
    },
    {
      field: 'customComment',
      title: '表注释',
      minWidth: 160,
      formatter: ({ row }) => displayComment(row),
    },
    {
      field: 'businessName',
      title: '业务名称',
      minWidth: 130,
      formatter: ({ cellValue }) => cellValue || '-',
    },
    { field: 'columnCount', title: '字段数', width: 75 },
    {
      field: 'structVersion',
      title: '版本号',
      width: 130,
      slots: { default: 'version' },
    },
    {
      field: 'publishStatus',
      title: '发布状态',
      width: 90,
      slots: { default: 'publishStatus' },
    },
    {
      field: 'objectStatus',
      title: '对象状态',
      width: 100,
      slots: { default: 'objectStatus' },
    },
    {
      field: 'lastSeenTime',
      title: '最近确认时间',
      width: 180,
      formatter: ({ cellValue }) => metadataTime(cellValue, 'YYYY-MM-DD HH:mm'),
    },
    {
      title: '操作',
      width: 140,
      fixed: 'right',
      showOverflow: false,
      className: 'metaobject-action-column',
      slots: { default: 'actions' },
    },
  ];
}
