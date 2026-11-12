import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { SupplyDataApiApi } from '#/api/supply/dataapi';

import { getRangePickerDefaultProps } from '#/utils';

import { formatSupplyDateTime } from '../utils/format';
import { getUserNickname } from '../utils/user';

/** 请求方式 */
export const METHOD_OPTIONS = [
  { label: 'GET', value: 'GET' },
  { label: 'POST', value: 'POST' },
];

/** 返回格式 */
export const RESULT_FORMAT_OPTIONS = [
  { label: '分页', value: 'PAGE', color: 'processing' },
  { label: '列表', value: 'LIST', color: 'success' },
  { label: '详情', value: 'DETAIL', color: 'warning' },
];

/** 配置方式 */
export const CONFIG_TYPE_OPTIONS = [
  { label: '单表向导式', value: 'TABLE' },
  { label: 'SQL脚本式', value: 'SQL' },
  { label: '第三方转发', value: 'FORWARD' },
  { label: 'ETL 受控批次', value: 'ETL_BATCH' },
  { label: '发布指标', value: 'INDICATOR' },
];

/** 参数类型 */
export const PARAM_TYPE_OPTIONS = [
  { label: '字符串', value: 'STRING' },
  { label: '整数', value: 'INTEGER' },
  { label: '小数', value: 'DOUBLE' },
  { label: '日期', value: 'DATE' },
];

/** API 状态 */
export const API_STATUS_OPTIONS = [
  { label: '下线', value: 0, color: 'default' },
  { label: '上线', value: 1, color: 'success' },
];

/** 接口类型（非查询类仅 SQL 脚本式支持） */
export const API_TYPE_OPTIONS = [
  { label: '查询', value: 'QUERY', color: 'processing' },
  { label: '插入', value: 'INSERT', color: 'success' },
  { label: '更新', value: 'UPDATE', color: 'warning' },
  { label: '删除', value: 'DELETE', color: 'error' },
  { label: '多脚本(事务)', value: 'MULTI', color: 'purple' },
];

/** 认证方式 */
export const AUTH_TYPE_OPTIONS = [
  { label: '无认证', value: 'NONE', color: 'default' },
  { label: 'AppKey认证', value: 'APPKEY', color: 'processing' },
  { label: '签名认证(SM3)', value: 'SIGN', color: 'success' },
];

/** IP 规则类型 */
export const IP_RULE_TYPE_OPTIONS = [
  { label: '白名单', value: 'WHITE' },
  { label: '黑名单', value: 'BLACK' },
];

/** 返回字段数据模型 */
export const FIELD_MODEL_OPTIONS = [
  { label: '无', value: '' },
  { label: '字段隐藏', value: 'HIDE' },
  { label: '内容拼接', value: 'CONCAT' },
  { label: '内容替换', value: 'REPLACE' },
  { label: '内容截取', value: 'SUBSTRING' },
  { label: '内容分割', value: 'SPLIT' },
  { label: '时间格式化', value: 'DATE_FORMAT' },
];

/** 数据模型规则占位提示 */
export const FIELD_MODEL_RULE_PLACEHOLDER: Record<string, string> = {
  CONCAT: `如 \${value}_后缀`,
  DATE_FORMAT: '如 yyyy-MM-dd HH:mm:ss',
  HIDE: '无需规则',
  REPLACE: '如 旧值,新值',
  SPLIT: '如 @',
  SUBSTRING: '如 0,2',
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

/** 列表搜索表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'name',
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
        options: API_STATUS_OPTIONS.map(({ label, value }) => ({
          label,
          value,
        })),
        placeholder: '请选择状态',
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

/** 列表字段 */
export function useGridColumns(): VxeTableGridOptions<SupplyDataApiApi.DataApi>['columns'] {
  return [
    { field: 'id', title: '编号', width: 70 },
    {
      field: 'name',
      title: 'API名称/版本号/描述',
      minWidth: 340,
      align: 'left',
      showOverflow: false,
      slots: { default: 'name' },
    },
    {
      field: 'categoryId',
      title: '所属类目',
      width: 140,
      align: 'left',
      slots: { default: 'categoryId' },
    },
    {
      field: 'path',
      title: 'API路径/请求类型/返回格式',
      minWidth: 280,
      align: 'left',
      showOverflow: false,
      slots: { default: 'path' },
    },
    {
      field: 'authType',
      title: '认证方式',
      width: 120,
      slots: { default: 'authType' },
    },
    {
      field: 'status',
      title: '状态',
      width: 90,
      slots: { default: 'status' },
    },
    {
      field: 'creator',
      title: '创建人',
      width: 140,
      align: 'left',
      formatter: ({ cellValue }) => getUserNickname(cellValue),
    },
    {
      field: 'createTime',
      title: '创建时间',
      width: 180,
      formatter: ({ cellValue }) => formatSupplyDateTime(cellValue) || '-',
    },
    {
      title: '操作',
      width: 220,
      fixed: 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ];
}
