import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { GovernanceStandardCodeSetApi } from '#/api/governance/standardcodeset';

import { CommonStatusEnum, DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';

import { z } from '#/adapter/form';

/** 新增/修改的表单 */
export function useFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'id',
      component: 'Input',
      dependencies: {
        triggerFields: [''],
        show: () => false,
      },
    },
    {
      fieldName: 'name',
      label: '字典名称',
      rules: 'required',
      component: 'Input',
      componentProps: {
        placeholder: '请输入字典名称',
      },
    },
    {
      fieldName: 'type',
      label: '字典类型',
      rules: 'required',
      component: 'Input',
      componentProps: {
        placeholder: '请输入字典类型',
      },
      dependencies: {
        triggerFields: ['id'],
        componentProps: (values) => ({
          disabled: !!values.id,
        }),
      },
    },
    {
      fieldName: 'status',
      label: '状态',
      component: 'RadioGroup',
      componentProps: {
        options: getDictOptions(DICT_TYPE.COMMON_STATUS, 'number'),
        buttonStyle: 'solid',
        optionType: 'button',
      },
      rules: z.number().default(CommonStatusEnum.ENABLE),
    },
    {
      fieldName: 'remark',
      label: '描述',
      component: 'TextArea',
      formItemClass: 'col-span-2',
      componentProps: {
        placeholder: '请输入描述',
        rows: 4,
        allowClear: true,
      },
    },
  ];
}

/** 列表的搜索表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'name',
      label: '字典名称',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入字典名称',
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
export function useGridColumns(
  onStatusChange?: (
    newStatus: number,
    row: GovernanceStandardCodeSetApi.StandardCodeSet,
  ) => PromiseLike<boolean | undefined>,
): VxeTableGridOptions<GovernanceStandardCodeSetApi.StandardCodeSet>['columns'] {
  return [
    { type: 'checkbox', width: 40 },
    {
      field: 'id',
      title: '主键',
      minWidth: 120,
    },
    {
      field: 'name',
      title: '字典名称',
      minWidth: 120,
    },
    {
      field: 'type',
      title: '字典类型',
      minWidth: 120,
    },
    {
      field: 'itemCount',
      title: '条目数量',
      minWidth: 100,
      align: 'center',
    },
    {
      field: 'remark',
      title: '描述',
      minWidth: 120,
    },
    {
      field: 'status',
      title: '状态',
      minWidth: 100,
      align: 'center',
      cellRender: {
        attrs: { beforeChange: onStatusChange },
        name: 'CellSwitch',
        props: {
          checkedValue: CommonStatusEnum.ENABLE,
          unCheckedValue: CommonStatusEnum.DISABLE,
        },
      },
    },
    {
      field: 'createTime',
      title: '创建时间',
      minWidth: 170,
      formatter: 'formatDateTime',
    },
    {
      title: '操作',
      width: 240,
      fixed: 'right',
      slots: { default: 'actions' },
    },
  ];
}
