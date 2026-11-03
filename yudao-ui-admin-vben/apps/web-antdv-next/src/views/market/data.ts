import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';

import { ASSET_TYPE_OPTIONS } from '#/api/asset/dataasset';

import {
  APPLY_STATUS_OPTIONS,
  DELIVERY_STATUS_OPTIONS,
  optionColor,
  optionLabel,
  SHARE_ATTRIBUTE_OPTIONS,
} from './market-labels';
import { formatMarketTime } from './market-time';

export { formatMarketTime };
export {
  APPLY_STATUS_OPTIONS,
  DELIVERY_STATUS_OPTIONS,
  optionColor,
  optionLabel,
  SHARE_ATTRIBUTE_OPTIONS,
};
export { ASSET_TYPE_OPTIONS };

export const DEMAND_STATUS_OPTIONS = [
  { label: '草稿', value: 0, color: 'default' },
  { label: '已发布', value: 1, color: 'processing' },
  { label: '对接中', value: 2, color: 'cyan' },
  { label: '已匹配', value: 3, color: 'success' },
  { label: '已关闭', value: 4, color: 'default' },
  { label: '已撤销', value: 5, color: 'default' },
  { label: '已驳回', value: 6, color: 'error' },
  { label: '申请办理中', value: 7, color: 'processing' },
];

export const DEMAND_TYPE_OPTIONS = [
  { label: '新增数据', value: 1 },
  { label: '已有共享', value: 2 },
  { label: '质量提升', value: 3 },
  { label: '其他', value: 4 },
];

export const DELIVERY_OPTIONS = [
  { label: 'API', value: 1 },
  { label: '库表', value: 2 },
  { label: '文件', value: 3 },
  { label: '不限', value: 4 },
];

export const URGENCY_OPTIONS = [
  { label: '低', value: 1, color: 'default' },
  { label: '中', value: 2, color: 'processing' },
  { label: '高', value: 3, color: 'error' },
];

export const MATCH_STATUS_OPTIONS = [
  { label: '待确认', value: 0, color: 'processing' },
  { label: '已接受', value: 1, color: 'success' },
  { label: '已拒绝', value: 2, color: 'error' },
];

export const REPLY_TYPE_OPTIONS = [
  { label: '沟通', value: 1 },
  { label: '可供给', value: 2 },
  { label: '无法供给', value: 3 },
];

export const PROCESS_ACTION_OPTIONS = [
  { label: '提交', value: 'SUBMIT' },
  { label: '通过', value: 'APPROVE' },
  { label: '驳回', value: 'REJECT' },
  { label: '授权', value: 'AUTH' },
  { label: '取消授权', value: 'UNAUTH' },
  { label: '撤销', value: 'CANCEL' },
  { label: '收回', value: 'REVOKE' },
  { label: '过期', value: 'EXPIRE' },
  { label: '补正', value: 'SUPPLEMENT' },
  { label: '重提', value: 'RESUBMIT' },
  { label: '续期', value: 'RENEW' },
  { label: '暂停', value: 'PAUSE' },
  { label: '恢复', value: 'RESUME' },
];

export function useCheckFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'ifOwner',
      label: '审核范围',
      component: 'Select',
      defaultValue: 1,
      componentProps: {
        allowClear: false,
        options: [
          { label: '待我审核（我负责的资产）', value: 1 },
          { label: '全部申请', value: 0 },
        ],
      },
    },
    ...useApplyFormSchema(),
  ];
}

export function useApplyFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'keyword',
      label: '关键字',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '标题 / 资产' },
    },
    {
      fieldName: 'status',
      label: '状态',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: APPLY_STATUS_OPTIONS,
        placeholder: '全部状态',
      },
    },
    {
      fieldName: 'assetType',
      label: '资产类型',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: ASSET_TYPE_OPTIONS.filter((item) => !item.disabled),
        placeholder: '全部类型',
      },
    },
  ];
}

export function useApplyColumns(): VxeTableGridOptions['columns'] {
  return [
    { field: 'applyNo', title: '申请单号', minWidth: 150 },
    { field: 'title', title: '标题', minWidth: 180 },
    { field: 'assetName', title: '资产', minWidth: 160 },
    { field: 'applyUserName', title: '申请人', width: 100 },
    { field: 'defaultCheckerName', title: '默认审批人', width: 110 },
    { field: 'applyDeptName', title: '部门', width: 120 },
    {
      field: 'status',
      title: '状态',
      width: 100,
      slots: { default: 'status' },
    },
    { field: 'ifAuth', title: '授权', width: 80, slots: { default: 'auth' } },
    {
      field: 'techProvisionStatus',
      title: '开通',
      width: 90,
      slots: { default: 'delivery' },
    },
    {
      field: 'createTime',
      title: '申请时间',
      width: 170,
      formatter: ({ cellValue }) => formatMarketTime(cellValue),
    },
    {
      field: 'actions',
      title: '操作',
      width: 140,
      fixed: 'right',
      showOverflow: false,
      className: 'market-action-column',
      slots: { default: 'actions' },
    },
  ];
}

export function useDemandFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'keyword',
      label: '关键字',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '标题 / 说明' },
    },
    {
      fieldName: 'demandType',
      label: '需求类型',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: DEMAND_TYPE_OPTIONS,
      },
    },
    {
      fieldName: 'urgency',
      label: '紧急程度',
      component: 'Select',
      componentProps: { allowClear: true, options: URGENCY_OPTIONS },
    },
    {
      fieldName: 'status',
      label: '状态',
      component: 'Select',
      componentProps: { allowClear: true, options: DEMAND_STATUS_OPTIONS },
    },
  ];
}

export function useDemandColumns(): VxeTableGridOptions['columns'] {
  return [
    { field: 'demandNo', title: '需求单号', minWidth: 150 },
    { field: 'title', title: '标题', minWidth: 200 },
    {
      field: 'demandType',
      title: '类型',
      width: 110,
      slots: { default: 'demandType' },
    },
    {
      field: 'urgency',
      title: '紧急',
      width: 80,
      slots: { default: 'urgency' },
    },
    { field: 'applyUserName', title: '提出人', width: 100 },
    { field: 'applyDeptName', title: '部门', width: 120 },
    {
      field: 'status',
      title: '状态',
      width: 100,
      slots: { default: 'status' },
    },
    { field: 'matchCount', title: '匹配', width: 70 },
    {
      field: 'publishTime',
      title: '发布时间',
      width: 170,
      formatter: ({ cellValue }) => formatMarketTime(cellValue),
    },
    {
      field: 'actions',
      title: '操作',
      width: 160,
      fixed: 'right',
      showOverflow: false,
      className: 'market-action-column',
      slots: { default: 'actions' },
    },
  ];
}
