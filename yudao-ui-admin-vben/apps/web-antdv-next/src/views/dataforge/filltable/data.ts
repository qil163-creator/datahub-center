import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { DataforgeFillTableApi } from '#/api/dataforge/filltable';

import { handleTree } from '@vben/utils';

import { z } from '#/adapter/form';
import { getDatabaseSimpleList } from '#/api/dataforge/database';
import { getDeptList } from '#/api/system/dept';
import { createHiddenIdField } from '#/views/dataforge/common/form-section';

/** 填报表发布状态 */
export const FILL_TABLE_STATUS_OPTIONS = [
  { label: '未发布', value: 0, color: 'default' },
  { label: '已发布', value: 1, color: 'success' },
];

/** 字段数据类型（dbswitch 逻辑类型） */
export const FIELD_TYPE_OPTIONS = [
  { label: '字符串', value: 2 },
  { label: '整数', value: 5 },
  { label: '长整型', value: 6 },
  { label: '小数', value: 1 },
  { label: '日期', value: 3 },
  { label: '日期时间', value: 9 },
  { label: '时间', value: 10 },
];

/** 录入类型 */
export const FILL_TYPE_OPTIONS = [
  { label: '手工录入', value: 1 },
  { label: '下拉选择', value: 2 },
];

/** 主键类型 */
export const PK_TYPE_OPTIONS = [
  { label: 'UUID 自动生成', value: 1 },
  { label: '自行录入', value: 2 },
];

/** 日期类字段的录入格式提示 */
export function fieldTypePlaceholder(fieldType?: number) {
  switch (fieldType) {
    case 1: {
      return '小数，如 3.14';
    }
    case 3: {
      return '格式：2026-01-01';
    }
    case 5:
    case 6: {
      return '整数';
    }
    case 9: {
      return '格式：2026-01-01 12:00:00';
    }
    case 10: {
      return '格式：12:00:00';
    }
    default: {
      return '请输入';
    }
  }
}

export function fillTableOptionLabel(
  options: { label: string; value: number | string }[],
  value?: null | number | string,
) {
  return options.find((item) => item.value === value)?.label ?? '-';
}

/** 新增/修改填报表 */
export function useFormSchema(): VbenFormSchema[] {
  return [
    createHiddenIdField(),
    {
      fieldName: 'tableEngName',
      label: '表英文名',
      component: 'Input',
      rules: z
        .string()
        .min(1, { message: '请填写表英文名' })
        .regex(/^[a-z][a-z0-9_]*$/, {
          message: '须以小写字母开头，只能包含小写字母、数字和下划线',
        }),
      componentProps: {
        allowClear: true,
        maxlength: 64,
        placeholder: '物理表名，如 t_population_report',
      },
    },
    {
      fieldName: 'tableCnName',
      label: '表中文名',
      rules: 'required',
      component: 'Input',
      componentProps: {
        allowClear: true,
        maxlength: 100,
        placeholder: '发布时作为表注释，如 人口信息填报表',
      },
    },
    {
      fieldName: 'databaseId',
      label: '目标数据源',
      rules: 'selectRequired',
      component: 'ApiSelect',
      componentProps: {
        api: async () => {
          const list = await getDatabaseSimpleList();
          return list.map((item) => ({
            label: `${item.name ?? item.id}${item.usageType === 2 ? '（中台库）' : ''}`,
            value: item.id,
          }));
        },
        labelField: 'label',
        optionFilterProp: 'label',
        placeholder: '发布后物理表将建在该数据源',
        showSearch: true,
        valueField: 'value',
      },
    },
    {
      fieldName: 'orgId',
      label: '所属单位',
      component: 'ApiTreeSelect',
      componentProps: {
        allowClear: true,
        api: async () => handleTree(await getDeptList()),
        childrenField: 'children',
        labelField: 'name',
        placeholder: '请选择所属单位',
        showSearch: true,
        treeDefaultExpandAll: true,
        treeNodeFilterProp: 'name',
        valueField: 'id',
      },
    },
    {
      fieldName: 'description',
      label: '描述',
      component: 'TextArea',
      formItemClass: 'col-span-2',
      componentProps: {
        placeholder: '这张表填什么数据、给谁填',
        rows: 2,
      },
    },
  ];
}

/** 列表的搜索表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'tableEngName',
      label: '表名',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '请输入表英文名' },
    },
    {
      fieldName: 'tableCnName',
      label: '表中文名',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '请输入表中文名' },
    },
    {
      fieldName: 'status',
      label: '发布状态',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: FILL_TABLE_STATUS_OPTIONS.map(({ label, value }) => ({
          label,
          value,
        })),
        placeholder: '请选择发布状态',
      },
    },
  ];
}

/** 填报表列表字段（管理页与录入查询页共用） */
export function useGridColumns(): VxeTableGridOptions<DataforgeFillTableApi.FillTable>['columns'] {
  return [
    { field: 'id', title: '编号', width: 70 },
    { field: 'tableEngName', title: '表名', minWidth: 150 },
    { field: 'tableCnName', title: '表中文名', minWidth: 130 },
    {
      field: 'databaseName',
      title: '目标数据源',
      minWidth: 120,
      formatter: ({ cellValue }) => cellValue || '-',
    },
    {
      field: 'status',
      title: '发布状态',
      width: 90,
      slots: { default: 'status' },
    },
    {
      field: 'releaseUserName',
      title: '发布人',
      width: 90,
      formatter: ({ cellValue }) => cellValue || '-',
    },
    {
      field: 'releaseTime',
      title: '发布时间',
      width: 140,
      formatter: 'formatDateTime',
    },
    {
      field: 'orgName',
      title: '所属单位',
      minWidth: 110,
      formatter: ({ cellValue }) => cellValue || '-',
    },
    {
      field: 'description',
      title: '描述',
      minWidth: 120,
      showOverflow: false,
      slots: { default: 'description' },
    },
    {
      title: '操作',
      width: 280,
      fixed: 'right',
      showOverflow: false,
      className: 'filltable-action-column',
      slots: { default: 'actions' },
    },
  ];
}

/** 规则级别 */
export const RULE_SEVERITY_OPTIONS = [
  { label: '错误（阻断提交）', value: 1 },
  { label: '警告（可提交）', value: 2 },
];

/** 数值与日期时间字段可设置取值范围 */
export function supportsRange(fieldType?: number) {
  return [1, 3, 5, 6, 9, 10].includes(fieldType ?? -1);
}

/** 范围输入框的占位提示 */
export function rangePlaceholder(fieldType?: number) {
  switch (fieldType) {
    case 3: {
      return '如 2026-01-01';
    }
    case 9: {
      return '如 2026-01-01 00:00:00';
    }
    case 10: {
      return '如 08:00:00';
    }
    default: {
      return '数值，留空不限';
    }
  }
}

/** 字段列表“规则”列的简要标签；只描述已配置的规则 */
export function describeColumnRules(column: {
  dictType?: string;
  fieldType?: number;
  maxValue?: string;
  minValue?: string;
  regexPattern?: string;
  ruleSeverity?: number;
  uniqueFlag?: number;
}): string[] {
  const tags: string[] = [];
  const min = column.minValue?.trim();
  const max = column.maxValue?.trim();
  if (supportsRange(column.fieldType) && (min || max)) {
    tags.push(`范围 ${min || '-∞'} ~ ${max || '+∞'}`);
  }
  if (column.fieldType === 2 && column.regexPattern?.trim()) {
    tags.push('格式');
  }
  if (column.uniqueFlag === 1) {
    tags.push('唯一');
  }
  if (column.dictType?.trim()) {
    tags.push(`字典 ${column.dictType.trim()}`);
  }
  if (column.ruleSeverity === 2 && tags.some((tag) => tag !== `字典 ${column.dictType?.trim()}`)) {
    tags.push('警告级');
  }
  return tags;
}
