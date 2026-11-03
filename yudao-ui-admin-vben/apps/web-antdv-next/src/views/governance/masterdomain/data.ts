import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { GovernanceMasterDomainApi } from '#/api/governance/masterdomain';

import { CommonStatusEnum, DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';

export interface LeaderOption {
  label: string;
  value: GovernanceMasterDomainApi.Id;
  mobile?: string;
}

export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'name',
      label: '分域名称',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '请输入主数据域名称' },
    },
    {
      fieldName: 'code',
      label: '英文缩写',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '请输入英文缩写' },
    },
    {
      fieldName: 'status',
      label: '状态',
      component: 'Select',
      componentProps: () => ({
        allowClear: true,
        options: getDictOptions(DICT_TYPE.COMMON_STATUS, 'number'),
        placeholder: '请选择状态',
      }),
    },
  ];
}

export function useGridColumns(
  onStatusChange: (
    status: number,
    row: GovernanceMasterDomainApi.Domain,
  ) => Promise<boolean>,
  canUpdate: () => boolean,
): VxeTableGridOptions<GovernanceMasterDomainApi.Domain>['columns'] {
  return [
    { field: 'id', title: '编号', width: 80 },
    { field: 'name', title: '主数据域名称', minWidth: 150 },
    {
      field: 'description',
      title: '描述',
      minWidth: 200,
      align: 'left',
      showOverflow: false,
      slots: { default: 'description' },
    },
    { field: 'code', title: '英文缩写', minWidth: 100 },
    {
      field: 'status',
      title: '状态',
      minWidth: 90,
      cellRender: {
        name: 'CellSwitch',
        attrs: { beforeChange: onStatusChange },
        props: {
          checkedValue: CommonStatusEnum.ENABLE,
          unCheckedValue: CommonStatusEnum.DISABLE,
          get disabled() {
            return !canUpdate();
          },
        },
      },
    },
    {
      field: 'remark',
      title: '备注',
      minWidth: 240,
      align: 'left',
      showOverflow: false,
      slots: { default: 'remark' },
    },
    {
      field: 'leaderUserId',
      title: '负责人',
      minWidth: 100,
      formatter: ({ row }) =>
        row.leaderUserName || String(row.leaderUserId ?? '-'),
    },
    { field: 'leaderPhone', title: '负责人电话', minWidth: 120 },
    {
      field: 'creator',
      title: '创建人',
      minWidth: 100,
      formatter: ({ row }) => row.creatorName || row.creator || '-',
    },
    {
      field: 'createTime',
      title: '创建时间',
      minWidth: 140,
      formatter: 'formatDateTime',
    },
    {
      title: '操作',
      width: 200,
      showOverflow: false,
      fixed: 'right',
      slots: { default: 'actions' },
    },
  ];
}

export function useFormSchema(
  getLeaders: () => LeaderOption[],
  isLoading: () => boolean,
): VbenFormSchema[] {
  return [
    {
      fieldName: 'name',
      label: '主数据域名称',
      component: 'Input',
      rules: 'required',
      componentProps: { placeholder: '请输入主数据域名称', maxlength: 100 },
    },
    {
      fieldName: 'code',
      label: '英文缩写',
      component: 'Input',
      rules: 'required',
      componentProps: { placeholder: '请输入英文缩写', maxlength: 50 },
    },
    {
      fieldName: 'leaderUserId',
      label: '负责人',
      component: 'Select',
      rules: 'selectRequired',
      componentProps: () => ({
        options: getLeaders(),
        loading: isLoading(),
        disabled: isLoading(),
        placeholder: '请选择负责人',
        allowClear: true,
        showSearch: true,
        optionFilterProp: 'label',
      }),
    },
    {
      fieldName: 'leaderPhone',
      label: '负责人电话',
      component: 'Input',
      componentProps: {
        disabled: true,
        placeholder: '负责人联系电话，随负责人自动带出',
      },
    },
    {
      fieldName: 'status',
      label: '状态',
      component: 'RadioGroup',
      defaultValue: CommonStatusEnum.ENABLE,
      componentProps: () => ({
        options: getDictOptions(DICT_TYPE.COMMON_STATUS, 'number'),
        buttonStyle: 'solid',
        optionType: 'button',
      }),
    },
    {
      fieldName: 'description',
      label: '描述',
      component: 'TextArea',
      formItemClass: 'col-span-2',
      componentProps: {
        rows: 4,
        maxlength: 500,
        showCount: true,
        placeholder: '请输入描述',
      },
    },
    {
      fieldName: 'remark',
      label: '备注',
      component: 'TextArea',
      formItemClass: 'col-span-2',
      componentProps: {
        rows: 4,
        maxlength: 500,
        showCount: true,
        placeholder: '请输入备注',
      },
    },
  ];
}
