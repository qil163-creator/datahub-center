import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { SupplyAppApi } from '#/api/supply/app';

import { formatSupplyDateTime } from '../utils/format';
import { getUserNickname } from '../utils/user';

export { getUserNickname } from '../utils/user';

/** 应用类型 */
export const APP_TYPE_OPTIONS = [
  { label: 'Web', value: 'Web' },
  { label: 'App', value: 'App' },
  { label: '小程序', value: '小程序' },
];

/** 新增/修改的表单 */
export function useFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'id',
      component: 'Input',
      dependencies: {
        triggerFields: [''],
        resolve: () => ({ show: false }),
      },
    },
    {
      fieldName: 'name',
      label: '应用名称',
      rules: 'required',
      component: 'Input',
      componentProps: {
        placeholder: '请输入应用名称',
      },
    },
    {
      fieldName: 'appType',
      label: '应用类型',
      rules: 'selectRequired',
      component: 'Select',
      componentProps: {
        options: APP_TYPE_OPTIONS,
        placeholder: '请选择应用类型',
      },
    },
    {
      fieldName: 'icon',
      label: '应用图标',
      component: 'ImageUpload',
      componentProps: {
        maxNumber: 1,
      },
    },
    {
      fieldName: 'description',
      label: '描述',
      component: 'TextArea',
      componentProps: {
        placeholder: '请输入描述',
        rows: 3,
        maxlength: 500,
        showCount: true,
      },
      formItemClass: 'col-span-2',
    },
    {
      fieldName: 'remark',
      label: '备注',
      component: 'TextArea',
      componentProps: {
        placeholder: '请输入备注',
        rows: 3,
        maxlength: 500,
        showCount: true,
      },
      formItemClass: 'col-span-2',
    },
  ];
}

/** 列表的搜索表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'name',
      label: '应用名称',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入应用名称',
      },
    },
    {
      fieldName: 'appType',
      label: '应用类型',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: APP_TYPE_OPTIONS,
        placeholder: '请选择应用类型',
      },
    },
  ];
}

/** 列表的字段 */
export function useGridColumns(): VxeTableGridOptions<SupplyAppApi.App>['columns'] {
  return [
    {
      field: 'id',
      title: '编号',
      width: 70,
    },
    {
      field: 'name',
      title: '应用名称',
      minWidth: 160,
      showOverflow: false,
    },
    {
      field: 'description',
      title: '描述',
      minWidth: 220,
      align: 'left',
      showOverflow: false,
    },
    {
      field: 'appType',
      title: '应用类型',
      minWidth: 90,
    },
    {
      field: 'appKey',
      title: 'AppKey',
      minWidth: 300,
      showOverflow: false,
    },
    {
      field: 'icon',
      title: '应用图标',
      minWidth: 90,
      cellRender: { name: 'CellImage' },
    },
    {
      field: 'creator',
      title: '创建人',
      minWidth: 100,
      formatter: ({ cellValue }) => getUserNickname(cellValue),
    },
    {
      field: 'createTime',
      title: '创建时间',
      minWidth: 180,
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
