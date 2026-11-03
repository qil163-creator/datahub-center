import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';
import type { GovernanceMasterManageApi } from '#/api/governance/mastermanage';

import { DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';

export interface ModelOption {
  label: string;
  value: GovernanceMasterManageApi.Id;
}

export const CODE_SEGMENT_TYPE_OPTIONS: {
  label: string;
  value: GovernanceMasterManageApi.Segment['segmentType'];
}[] = [
  { label: '固定值', value: 'CONSTANT' },
  { label: '日期', value: 'DATE' },
  { label: '流水号', value: 'SEQUENCE' },
  { label: '属性值', value: 'ATTRIBUTE' },
];

export function useGridFormSchema(
  getModels: () => ModelOption[],
): VbenFormSchema[] {
  return [
    {
      fieldName: 'modelId',
      label: '所属模型',
      component: 'Select',
      componentProps: () => ({
        options: getModels(),
        allowClear: true,
        showSearch: true,
        optionFilterProp: 'label',
        placeholder: '全部模型',
      }),
    },
    {
      fieldName: 'name',
      label: '规则名称',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '请输入规则名称' },
    },
  ];
}

export function useGridColumns(): VxeTableGridOptions<GovernanceMasterManageApi.CodeRule>['columns'] {
  return [
    { type: 'checkbox', width: 50 },
    { field: 'modelName', title: '所属模型', minWidth: 200 },
    { field: 'code', title: '规则编码', minWidth: 180 },
    { field: 'name', title: '规则名称', minWidth: 180 },
    { field: 'example', title: '编码示例', minWidth: 240 },
    {
      field: 'status',
      title: '状态',
      width: 110,
      cellRender: {
        name: 'CellDict',
        props: { type: DICT_TYPE.COMMON_STATUS },
      },
    },
    {
      title: '操作',
      width: 290,
      fixed: 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ];
}

export function useFormSchema(
  getModels: () => ModelOption[],
  getFields: () => GovernanceMasterDataApi.Column[],
): VbenFormSchema[] {
  return [
    {
      fieldName: 'modelId',
      label: '所属模型',
      component: 'Select',
      formItemClass: 'col-span-full',
      rules: 'selectRequired',
      componentProps: () => ({
        options: getModels(),
        showSearch: true,
        optionFilterProp: 'label',
        placeholder: '请选择所属模型',
      }),
    },
    {
      fieldName: 'code',
      label: '规则编码',
      component: 'Input',
      rules: 'required',
      componentProps: { maxlength: 64, placeholder: '请输入规则编码' },
    },
    {
      fieldName: 'name',
      label: '规则名称',
      component: 'Input',
      rules: 'required',
      componentProps: { maxlength: 100, placeholder: '请输入规则名称' },
    },
    {
      fieldName: 'description',
      label: '描述',
      component: 'TextArea',
      formItemClass: 'col-span-full',
      componentProps: {
        rows: 3,
        maxlength: 500,
        showCount: true,
        placeholder: '请输入描述',
      },
    },
    {
      fieldName: 'fieldName',
      label: '编码字段',
      component: 'Select',
      rules: 'selectRequired',
      componentProps: () => ({
        options: getFields()
          .filter((row) => row.fieldType === 2)
          .map((row) => ({
            label: `${row.commentStr || row.name}（${row.name}）`,
            value: row.name,
          })),
        placeholder: '选择写入编码的字符串字段',
      }),
    },
    {
      fieldName: 'status',
      label: '状态',
      component: 'RadioGroup',
      defaultValue: 1,
      componentProps: () => ({
        options: getDictOptions(DICT_TYPE.COMMON_STATUS, 'number'),
        buttonStyle: 'solid',
        optionType: 'button',
      }),
    },
  ];
}
