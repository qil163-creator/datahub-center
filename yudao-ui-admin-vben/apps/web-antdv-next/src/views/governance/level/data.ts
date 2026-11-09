import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { SafetyLevelApi } from '#/api/governance/level';

import { h } from 'vue';

import { CommonStatusEnum, DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';

import { z } from '#/adapter/form';
import { SafetyLevelTag } from '#/components/safety-level-tag';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';

import { SafetyLevelColorPicker } from './components';

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
      fieldName: 'safetyName',
      label: '显示名称',
      rules: 'required',
      component: 'Input',
      componentProps: {
        placeholder: '请输入显示名称',
      },
    },
    {
      fieldName: 'safetyLevel',
      label: '安全等级',
      rules: 'required',
      component: 'Input',
      componentProps: {
        placeholder: '请输入安全等级，如 L1、L5',
      },
    },
    {
      fieldName: 'color',
      label: '标志颜色',
      component: SafetyLevelColorPicker,
      description: () =>
        h(
          'div',
          { class: 'mt-1.5' },
          '不选择时，L1~L4 使用默认配色，其他等级为灰色',
        ),
    },
    {
      fieldName: 'status',
      label: '状态',
      rules: z.number().default(CommonStatusEnum.ENABLE),
      component: 'RadioGroup',
      componentProps: {
        options: getDictOptions(DICT_TYPE.COMMON_STATUS, 'number'),
        buttonStyle: 'solid',
        optionType: 'button',
      },
    },
    {
      fieldName: 'sort',
      label: '显示顺序',
      rules: 'required',
      component: 'InputNumber',
      componentProps: {
        class: 'w-full',
        min: 0,
        placeholder: '请输入显示顺序',
      },
    },
    {
      fieldName: 'description',
      label: '等级描述',
      component: 'TextArea',
      formItemClass: 'col-span-2',
      componentProps: {
        placeholder: '请输入规则描述',
        rows: 4,
      },
    },
  ];
}

/** 列表的搜索表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'safetyName',
      label: '显示名称',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入显示名称',
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
export function useGridColumns(): VxeTableGridOptions<SafetyLevelApi.Level>['columns'] {
  return [
    { type: 'checkbox', width: 40 },
    // {
    //   field: 'id',
    //   title: '主键id',
    //   minWidth: 120,
    // },
    {
      field: 'safetyName',
      title: '显示名称',
      minWidth: 120,
    },
    {
      field: 'safetyLevel',
      title: '安全等级',
      minWidth: 120,
      slots: {
        default: ({ row }) =>
          h(SafetyLevelTag, {
            color: row.color,
            level: row.safetyLevel,
          }),
      },
    },
    {
      field: 'description',
      title: '等级描述',
      minWidth: 200,
      align: 'left',
      // 关闭单行省略，让内容可换行，最多显示 3 行，截断时悬停展示完整内容
      showOverflow: false,
      slots: {
        default: ({ row }) =>
          h(TextEllipsisCell, {
            center: true,
            content: row.description,
            line: 2,
          }),
      },
    },
    {
      field: 'sort',
      title: '显示顺序',
      minWidth: 120,
    },
    {
      field: 'status',
      title: '状态',
      minWidth: 120,
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
      width: 240,
      fixed: 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ];
}
