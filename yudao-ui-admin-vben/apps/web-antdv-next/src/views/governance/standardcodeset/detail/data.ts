import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { GovernanceStandardCodeItemApi } from '#/api/governance/standardcodeitem';
import type { DescriptionItemSchema } from '#/components/description';

import { h } from 'vue';

import { CommonStatusEnum, DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';
import { formatDateTime } from '@vben/utils';

import { z } from '#/adapter/form';
import { DictTag } from '#/components/dict-tag';
import { formatCategoryName } from '#/views/governance/standardelement/data';

/** 详情页头部字段 */
export function useDetailSchema(): DescriptionItemSchema[] {
  return [
    {
      field: 'id',
      label: '编号',
    },
    {
      field: 'type',
      label: '字典类型',
    },
    {
      field: 'status',
      label: '状态',
      render: (val) =>
        h(DictTag, { type: DICT_TYPE.COMMON_STATUS, value: val }),
    },
    {
      field: 'remark',
      label: '描述',
      span: 3,
      render: (val) => val || '-',
    },
  ];
}

/** 详情页详细信息 */
export function useDetailExtraSchema(): DescriptionItemSchema[] {
  return [
    {
      field: 'creatorName',
      label: '创建人',
      render: (val) => val || '-',
    },
    {
      field: 'createTime',
      label: '创建时间',
      render: (val) => (val ? (formatDateTime(val) as string) : '-'),
    },
    {
      field: 'updaterName',
      label: '更新人',
      render: (val) => val || '-',
    },
    {
      field: 'updateTime',
      label: '更新时间',
      render: (val) => (val ? (formatDateTime(val) as string) : '-'),
    },
    {
      field: 'itemCount',
      label: '条目数量',
      render: (val) => (val === undefined || val === null ? '-' : val),
    },
  ];
}

/** 码值新增/修改表单 */
export function useItemFormSchema(): VbenFormSchema[] {
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
      fieldName: 'codeSetId',
      component: 'InputNumber',
      dependencies: {
        triggerFields: [''],
        show: () => false,
      },
    },
    {
      fieldName: 'dictType',
      component: 'Input',
      dependencies: {
        triggerFields: [''],
        show: () => false,
      },
    },
    {
      fieldName: 'parentId',
      label: '上级码值',
      component: 'Select',
      componentProps: {
        allowClear: true,
        placeholder: '请选择上级码值（可选）',
      },
    },
    {
      fieldName: 'validFrom',
      label: '生效时间',
      component: 'DatePicker',
      componentProps: {
        class: '!w-full',
        format: 'YYYY-MM-DD HH:mm:ss',
        placeholder: '为空表示立即生效',
        showTime: true,
        valueFormat: 'YYYY-MM-DD HH:mm:ss',
      },
    },
    {
      fieldName: 'validTo',
      label: '失效时间',
      component: 'DatePicker',
      componentProps: {
        class: '!w-full',
        format: 'YYYY-MM-DD HH:mm:ss',
        placeholder: '为空表示长期有效',
        showTime: true,
        valueFormat: 'YYYY-MM-DD HH:mm:ss',
      },
    },
    {
      fieldName: 'label',
      label: '码值标签',
      rules: 'required',
      component: 'Input',
      componentProps: {
        placeholder: '请输入码值标签',
      },
    },
    {
      fieldName: 'value',
      label: '码值键值',
      rules: 'required',
      component: 'Input',
      componentProps: {
        placeholder: '请输入码值键值',
      },
    },
    {
      fieldName: 'sort',
      label: '排序',
      rules: 'required',
      component: 'InputNumber',
      componentProps: {
        class: '!w-full',
        min: 0,
        placeholder: '请输入排序',
      },
      defaultValue: 0,
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
      label: '备注',
      component: 'TextArea',
      componentProps: {
        placeholder: '请输入备注',
        rows: 3,
        allowClear: true,
      },
    },
  ];
}

/** 码值列表字段 */
export function useItemGridColumns(
  onStatusChange?: (
    newStatus: number,
    row: GovernanceStandardCodeItemApi.StandardCodeItem,
  ) => PromiseLike<boolean | undefined>,
): VxeTableGridOptions<GovernanceStandardCodeItemApi.StandardCodeItem>['columns'] {
  return [
    { type: 'checkbox', width: 40 },
    {
      field: 'label',
      title: '码值标签',
      minWidth: 220,
      treeNode: true,
    },
    {
      field: 'value',
      title: '码值键值',
      minWidth: 120,
    },
    {
      field: 'levelNo',
      title: '层级',
      minWidth: 70,
    },
    {
      field: 'sort',
      title: '排序',
      minWidth: 80,
    },
    {
      field: 'validFrom',
      title: '生效时间',
      minWidth: 170,
      formatter: 'formatDateTime',
    },
    {
      field: 'validTo',
      title: '失效时间（不含）',
      minWidth: 170,
      formatter: ({ cellValue }) =>
        cellValue ? (formatDateTime(cellValue) as string) : '长期有效',
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
      field: 'remark',
      title: '备注',
      minWidth: 160,
    },
    {
      field: 'createTime',
      title: '创建时间',
      minWidth: 170,
      formatter: 'formatDateTime',
    },
    {
      title: '操作',
      width: 220,
      fixed: 'right',
      slots: { default: 'actions' },
    },
  ];
}

/** 关联信息：引用该标准码表的标准数据元 */
export function useRelationGridColumns(): VxeTableGridOptions['columns'] {
  return [
    {
      field: 'id',
      title: '编号',
      minWidth: 80,
    },
    {
      field: 'name',
      title: '中文名称',
      minWidth: 140,
    },
    {
      field: 'engName',
      title: '英文名称',
      minWidth: 140,
    },
    {
      field: 'categoryId',
      title: '数据元类目',
      minWidth: 140,
      formatter: ({ cellValue }) => formatCategoryName(cellValue),
    },
    {
      field: 'columnType',
      title: '字段类型',
      minWidth: 120,
    },
    {
      field: 'status',
      title: '状态',
      minWidth: 100,
      cellRender: {
        name: 'CellDict',
        props: { type: DICT_TYPE.COMMON_STATUS },
      },
    },
    {
      field: 'createTime',
      title: '创建时间',
      minWidth: 170,
      formatter: 'formatDateTime',
    },
  ];
}
