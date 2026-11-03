import type { VbenFormSchema } from '#/adapter/form';
import type { StandardDocApi } from '#/api/governance/doc';
import type { AxiosProgressEvent, InfraFileApi } from '#/api/infra/file';

import { DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';

import { standardCategoryTree } from '../standardcommon/data';

interface FormSchemaOptions {
  categories: () => StandardDocApi.Category[];
  documents: () => StandardDocApi.SimpleDoc[];
  onFileDelete: () => void;
  uploadFile: (
    file: File,
    onUploadProgress?: AxiosProgressEvent,
  ) => Promise<InfraFileApi.RegisteredFile>;
}

/** 日期保持后端 LocalDate 的 YYYY-MM-DD 字符串，不经过时区转换。 */
export function useFormSchema(options: FormSchemaOptions): VbenFormSchema[] {
  return [
    ...['id', 'fileId', 'fileName'].map(
      (fieldName): VbenFormSchema => ({
        fieldName,
        component: 'Input',
        dependencies: { triggerFields: [], resolve: () => ({ show: false }) },
      }),
    ),
    {
      fieldName: 'categoryId',
      label: '标准类目',
      component: 'TreeSelect',
      rules: 'required',
      componentProps: () => ({
        allowClear: true,
        treeData: standardCategoryTree(options.categories())[0]?.children ?? [],
        fieldNames: { label: 'title', value: 'key', children: 'children' },
        showSearch: true,
        treeNodeFilterProp: 'title',
        placeholder: '请选择标准类目',
      }),
    },
    {
      fieldName: 'implementStatus',
      label: '标准状态',
      component: 'Select',
      rules: 'required',
      componentProps: () => ({
        options: getDictOptions(DICT_TYPE.STANDARD_IMPLEMENT_STATUS, 'string'),
        placeholder: '请选择标准状态',
      }),
    },
    {
      fieldName: 'stdCode',
      label: '标准号',
      component: 'Input',
      rules: 'required',
      componentProps: { placeholder: '请输入标准号' },
    },
    {
      fieldName: 'nameCn',
      label: '标准名称',
      component: 'Input',
      rules: 'required',
      componentProps: { placeholder: '请输入标准名称' },
    },
    {
      fieldName: 'stdType',
      label: '标准类型',
      component: 'Select',
      rules: 'required',
      componentProps: () => ({
        options: getDictOptions(DICT_TYPE.STANDARD_STD_TYPE, 'string'),
        placeholder: '请选择标准类型',
      }),
    },
    {
      fieldName: 'versionNo',
      label: '版本号',
      component: 'Input',
      componentProps: { placeholder: '请输入版本号', maxlength: 64 },
    },
    {
      fieldName: 'fileUrl',
      label: '标准文件',
      component: 'FileUpload',
      formItemClass: 'col-span-2',
      rules: 'required',
      componentProps: {
        accept: ['pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx'],
        api: options.uploadFile,
        directory: 'standard/doc',
        maxNumber: 1,
        maxSize: 50,
        helpText: '支持 PDF、Word、Excel、PPT，最大 50MB',
        showDescription: true,
        onDelete: options.onFileDelete,
      },
    },
    {
      fieldName: 'publishOrg',
      label: '发布机构名称',
      component: 'Input',
      componentProps: { placeholder: '请输入发布机构名称' },
    },
    ...[
      ['publishDate', '发布日期'],
      ['implementDate', '实施日期'],
      ['abolishDate', '废止日期'],
    ].map(
      ([fieldName, label]): VbenFormSchema => ({
        fieldName: fieldName!,
        label,
        component: 'DatePicker',
        componentProps: {
          format: 'YYYY-MM-DD',
          valueFormat: 'YYYY-MM-DD',
          placeholder: `请选择${label}`,
        },
      }),
    ),
    {
      fieldName: 'summary',
      label: '描述',
      component: 'TextArea',
      formItemClass: 'col-span-2',
      componentProps: {
        placeholder: '请输入标准摘要或适用范围',
        rows: 4,
        showCount: true,
      },
    },
    {
      fieldName: 'replaceDocId',
      label: '替代标准',
      component: 'Select',
      formItemClass: 'col-span-2',
      componentProps: () => ({
        allowClear: true,
        showSearch: true,
        optionFilterProp: 'label',
        placeholder: '请选择被本标准替代的旧标准',
        options: options.documents().map((row) => ({
          value: String(row.id),
          label: row.stdCode ? `${row.stdCode} ${row.nameCn}` : row.nameCn,
        })),
      }),
    },
    {
      fieldName: 'sort',
      label: '显示顺序',
      component: 'InputNumber',
      rules: 'required',
      componentProps: { min: 0, precision: 0 },
    },
    {
      fieldName: 'status',
      label: '启用状态',
      component: 'RadioGroup',
      rules: 'required',
      componentProps: () => ({
        options: getDictOptions(DICT_TYPE.COMMON_STATUS, 'number'),
      }),
    },
    {
      fieldName: 'remark',
      label: '备注',
      component: 'TextArea',
      formItemClass: 'col-span-2',
      componentProps: { rows: 2 },
    },
  ];
}

export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'stdCode',
      label: '标准号',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '请输入标准号' },
    },
    {
      fieldName: 'nameCn',
      label: '标准名称',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '请输入标准名称' },
    },
    {
      fieldName: 'stdType',
      label: '标准类型',
      component: 'Select',
      componentProps: () => ({
        allowClear: true,
        options: getDictOptions(DICT_TYPE.STANDARD_STD_TYPE, 'string'),
      }),
    },
    {
      fieldName: 'implementStatus',
      label: '标准状态',
      component: 'Select',
      componentProps: () => ({
        allowClear: true,
        options: getDictOptions(DICT_TYPE.STANDARD_IMPLEMENT_STATUS, 'string'),
      }),
    },
  ];
}

/** 路由编号必须保持无损十进制字符串。 */
export function parseDocId(value: unknown): string | undefined {
  return typeof value === 'string' &&
    /^[1-9]\d{0,18}$/.test(value) &&
    BigInt(value) <= 9_223_372_036_854_775_807n
    ? value
    : undefined;
}
