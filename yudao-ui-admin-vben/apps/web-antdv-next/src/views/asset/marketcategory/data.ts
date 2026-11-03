import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { AssetMarketCategoryApi } from '#/api/asset/marketcategory';

import { CommonStatusEnum, DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';

import { z } from '#/adapter/form';

import { marketCategoryParentOptions } from './category-tree';

export function useCategoryFormSchema(
  getParents: () => ReturnType<typeof marketCategoryParentOptions>,
): VbenFormSchema[] {
  return [
    {
      fieldName: 'name',
      label: '集市类目名称',
      component: 'Input',
      rules: z
        .string()
        .trim()
        .min(1, '请输入集市类目名称')
        .max(50, '类目名称不能超过50个字符'),
      componentProps: {
        placeholder: '请输入集市类目名称',
        maxLength: 50,
        allowClear: true,
      },
    },
    {
      fieldName: 'parentId',
      label: '上级类目',
      component: 'TreeSelect',
      rules: 'selectRequired',
      componentProps: () => ({
        treeData: getParents(),
        fieldNames: { label: 'name', value: 'id', children: 'children' },
        treeDefaultExpandAll: true,
        placeholder: '请选择上级类目',
        allowClear: false,
      }),
    },
    {
      fieldName: 'status',
      label: '状态',
      component: 'RadioGroup',
      defaultValue: CommonStatusEnum.ENABLE,
      rules: z.number().int().min(0).max(1),
      componentProps: () => ({
        options: getDictOptions(DICT_TYPE.COMMON_STATUS, 'number'),
      }),
    },
    {
      fieldName: 'sort',
      label: '排序',
      component: 'InputNumber',
      defaultValue: 0,
      rules: z.number().int('排序必须是整数').min(0, '排序不能小于0'),
      componentProps: {
        min: 0,
        max: 2_147_483_647,
        precision: 0,
        class: '!w-40',
      },
    },
    {
      fieldName: 'description',
      label: '描述',
      formItemClass: 'col-span-1 sm:col-span-2',
      component: 'TextArea',
      rules: z.string().max(256, '描述不能超过256个字符').nullish(),
      componentProps: {
        placeholder: '请输入描述',
        rows: 4,
        maxLength: 256,
        showCount: true,
      },
    },
  ];
}

export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'name',
      label: '集市类目名称',
      component: 'Input',
      componentProps: { placeholder: '请输入集市类目名称', allowClear: true },
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

export function useGridColumns(): VxeTableGridOptions<AssetMarketCategoryApi.MarketCategory>['columns'] {
  return [
    { type: 'checkbox', width: 40 },
    { type: 'seq', title: '编号', width: 70 },
    {
      field: 'name',
      title: '集市类目名称',
      minWidth: 190,
      treeNode: true,
      align: 'left',
    },
    {
      field: 'description',
      title: '描述',
      minWidth: 260,
      showOverflow: false,
      slots: { default: 'description' },
    },
    {
      field: 'status',
      title: '状态',
      width: 100,
      slots: { default: 'status' },
    },
    { field: 'sort', title: '排序', width: 84, sortable: true },
    {
      field: 'creatorName',
      title: '创建人',
      minWidth: 110,
      formatter: ({ row }) => row.creatorName || row.creator || '—',
    },
    {
      field: 'createTime',
      title: '创建时间',
      minWidth: 160,
      formatter: 'formatDateTime',
      sortable: true,
    },
    {
      title: '操作',
      width: 290,
      showOverflow: false,
      fixed: 'right',
      slots: { default: 'actions' },
    },
  ];
}
