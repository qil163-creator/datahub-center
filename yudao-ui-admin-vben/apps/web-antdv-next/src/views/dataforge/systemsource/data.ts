import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { FoundationSystemSourceApi } from '#/api/dataforge/systemsource';

import { CommonStatusEnum, DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';
import { handleTree } from '@vben/utils';

import { z } from '#/adapter/form';
import { getDeptList } from '#/api/system/dept';

type OwnerOption = { label: string; value: number };

/** 新增/修改的表单 */
export function useFormSchema(
  getOwnerOptions: (deptId?: number) => OwnerOption[] = () => [],
): VbenFormSchema[] {
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
      label: '系统名称',
      rules: 'required',
      formItemClass: 'col-span-1',
      component: 'Input',
      componentProps: {
        placeholder: '请输入系统名称',
      },
    },
    {
      fieldName: 'type',
      label: '系统类型',
      rules: 'required',
      formItemClass: 'col-span-1',
      component: 'Select',
      componentProps: {
        options: getDictOptions(DICT_TYPE.SYSTEM_SOURCE_TYPE, 'string'),
        placeholder: '请选择系统类型',
      },
    },
    {
      fieldName: 'description',
      label: '说明',
      formItemClass: 'col-span-2',
      component: 'TextArea',
      componentProps: {
        placeholder: '请输入说明',
        rows: 4,
        maxlength: 500,
        showCount: true,
      },
    },
    {
      fieldName: 'icon',
      label: '系统图标',
      formItemClass: 'col-span-1',
      component: 'ImageUpload',
      componentProps: {
        maxNumber: 1,
      },
    },
    {
      fieldName: 'url',
      label: '系统地址',
      formItemClass: 'col-span-1',
      component: 'Input',
      componentProps: {
        placeholder: '请输入系统地址',
      },
    },
    {
      fieldName: 'deptId',
      label: '所属部门',
      rules: 'required',
      formItemClass: 'col-span-1',
      component: 'ApiTreeSelect',
      componentProps: {
        api: async () => {
          const data = await getDeptList();
          return handleTree(data);
        },
        labelField: 'name',
        valueField: 'id',
        childrenField: 'children',
        placeholder: '请选择所属部门',
        treeDefaultExpandAll: true,
      },
    },
    {
      fieldName: 'deptName',
      component: 'Input',
      dependencies: {
        triggerFields: [''],
        show: () => false,
      },
    },
    {
      fieldName: 'ownerUserId',
      label: '负责人',
      rules: 'required',
      formItemClass: 'col-span-1',
      component: 'Select',
      componentProps: {
        allowClear: true,
        showSearch: true,
        optionFilterProp: 'label',
        disabled: true,
        options: [],
        placeholder: '请先选择所属部门',
      },
      dependencies: {
        triggerFields: ['deptId'],
        componentProps(values) {
          const deptId = values.deptId;
          return {
            allowClear: true,
            showSearch: true,
            optionFilterProp: 'label',
            disabled: !deptId,
            options: getOwnerOptions(deptId),
            placeholder: deptId ? '请选择负责人' : '请先选择所属部门',
          };
        },
      },
    },
    {
      fieldName: 'ownerUserName',
      component: 'Input',
      dependencies: {
        triggerFields: [''],
        show: () => false,
      },
    },
    {
      fieldName: 'ownerMobile',
      label: '联系方式',
      formItemClass: 'col-span-1',
      component: 'Input',
      componentProps: {
        disabled: true,
        placeholder: '请选择负责人后自动带出',
      },
    },
    {
      fieldName: 'sortOrder',
      label: '排序',
      rules: 'required',
      formItemClass: 'col-span-1',
      component: 'InputNumber',
      componentProps: {
        class: 'w-full',
        min: 0,
        placeholder: '请输入排序',
      },
    },
    {
      fieldName: 'status',
      label: '状态',
      rules: z.number().default(CommonStatusEnum.ENABLE),
      formItemClass: 'col-span-1',
      component: 'RadioGroup',
      componentProps: {
        options: getDictOptions(DICT_TYPE.COMMON_STATUS, 'number'),
        buttonStyle: 'solid',
        optionType: 'button',
      },
    },
    {
      fieldName: 'remark',
      label: '备注',
      formItemClass: 'col-span-2',
      component: 'TextArea',
      componentProps: {
        placeholder: '请输入备注',
        rows: 3,
      },
    },
  ];
}

/** 列表的搜索表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'name',
      label: '系统名称',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入系统名称',
      },
    },
    {
      fieldName: 'type',
      label: '系统类型',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: getDictOptions(DICT_TYPE.SYSTEM_SOURCE_TYPE, 'string'),
        placeholder: '请选择系统类型',
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
    row: FoundationSystemSourceApi.SystemSource,
  ) => PromiseLike<boolean | undefined>,
): VxeTableGridOptions<FoundationSystemSourceApi.SystemSource>['columns'] {
  return [
    { type: 'checkbox', width: 40 },
    {
      field: 'id',
      title: '编号',
      minWidth: 120,
    },
    {
      field: 'name',
      title: '系统名称',
      minWidth: 120,
    },
    {
      field: 'type',
      title: '系统类型',
      minWidth: 120,
      cellRender: {
        name: 'CellDict',
        props: { type: DICT_TYPE.SYSTEM_SOURCE_TYPE },
      },
    },
    {
      field: 'description',
      title: '说明',
      minWidth: 200,
      align: 'left',
      showOverflow: false,
      slots: { default: 'description' },
    },
    {
      field: 'icon',
      title: '系统图标',
      minWidth: 100,
      cellRender: {
        name: 'CellImage',
        props: { width: 40, height: 40 },
      },
    },
    {
      field: 'deptName',
      title: '所属部门',
      minWidth: 140,
      formatter: ({ cellValue }) => cellValue || '-',
    },
    {
      field: 'ownerUserName',
      title: '负责人',
      minWidth: 120,
      formatter: ({ cellValue }) => cellValue || '-',
    },
    {
      field: 'ownerMobile',
      title: '负责人联系方式',
      minWidth: 130,
      formatter: ({ cellValue }) => cellValue || '-',
    },
    {
      field: 'status',
      title: '状态',
      minWidth: 100,
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
      field: 'remark',
      title: '备注',
      minWidth: 150,
    },
    {
      field: 'createTime',
      title: '创建时间',
      minWidth: 180,
      formatter: 'formatDateTime',
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
