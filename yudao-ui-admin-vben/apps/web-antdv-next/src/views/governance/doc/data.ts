import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { StandardDocApi } from '#/api/governance/doc';
import type { AxiosProgressEvent, InfraFileApi } from '#/api/infra/file';
import type { DescriptionItemSchema } from '#/components/description';

import { h } from 'vue';

import { CommonStatusEnum, DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';
import { formatDate, formatDateTime } from '@vben/utils';

import { getDocSimpleList } from '#/api/governance/doc';
import { DictTag } from '#/components/dict-tag';
import { getRangePickerDefaultProps } from '#/utils';

interface FormSchemaOptions {
  getCurrentDocId: () => StandardDocApi.Id | undefined;
  onFileDelete: () => void;
  uploadFile: (
    file: File,
    onUploadProgress?: AxiosProgressEvent,
  ) => Promise<InfraFileApi.FileUploadRespVO>;
}

/** 新增/修改的表单 */
export function useFormSchema({
  getCurrentDocId,
  onFileDelete,
  uploadFile,
}: FormSchemaOptions): VbenFormSchema[] {
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
      fieldName: 'stdCode',
      label: '标准编号',
      component: 'Input',
      componentProps: {
        placeholder: '请输入标准编号',
      },
    },
    {
      fieldName: 'nameCn',
      label: '标准名称',
      rules: 'required',
      component: 'Input',
      componentProps: {
        placeholder: '请输入标准名称',
      },
    },
    {
      fieldName: 'stdType',
      label: '标准分类',
      rules: 'required',
      component: 'Select',
      componentProps: {
        options: getDictOptions(DICT_TYPE.STANDARD_STD_TYPE, 'string'),
        placeholder: '请选择标准分类',
      },
    },
    {
      fieldName: 'publishOrg',
      label: '发布机构',
      component: 'Input',
      componentProps: {
        placeholder: '请输入发布机构',
      },
    },
    {
      fieldName: 'publishDate',
      label: '发布日期',
      component: 'DatePicker',
      componentProps: {
        format: 'YYYY-MM-DD',
        valueFormat: 'YYYY-MM-DD',
      },
    },
    {
      fieldName: 'implementDate',
      label: '实施日期',
      component: 'DatePicker',
      componentProps: {
        format: 'YYYY-MM-DD',
        valueFormat: 'YYYY-MM-DD',
      },
    },
    {
      fieldName: 'implementStatus',
      label: '实施状态',
      rules: 'required',
      component: 'Select',
      componentProps: {
        options: getDictOptions(DICT_TYPE.STANDARD_IMPLEMENT_STATUS, 'string'),
        placeholder: '请选择实施状态',
      },
    },
    {
      fieldName: 'sort',
      label: '显示顺序',
      rules: 'required',
      component: 'InputNumber',
      componentProps: {
        min: 0,
        placeholder: '请输入显示顺序',
      },
    },
    {
      fieldName: 'fileId',
      component: 'Input',
      dependencies: {
        triggerFields: [''],
        show: () => false,
      },
    },
    {
      fieldName: 'fileName',
      component: 'Input',
      dependencies: {
        triggerFields: [''],
        show: () => false,
      },
    },
    {
      fieldName: 'fileUrl',
      label: '标准原文',
      component: 'FileUpload',
      formItemClass: 'col-span-2',
      componentProps: {
        accept: ['pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx'],
        api: uploadFile,
        directory: 'standard/doc',
        helpText: '支持上传 PDF、Word、Excel、PPT 文件，最大 50MB',
        maxNumber: 1,
        maxSize: 50,
        onDelete: onFileDelete,
        showDescription: true,
      },
    },
    {
      fieldName: 'replaceDocId',
      label: '替代标准',
      component: 'ApiSelect',
      formItemClass: 'col-span-2',
      componentProps: {
        allowClear: true,
        api: () => getDocSimpleList(getCurrentDocId()),
        alwaysLoad: true,
        immediate: false,
        labelFn: (item) =>
          item.stdCode ? `${item.stdCode} ${item.nameCn}` : item.nameCn,
        optionFilterProp: 'label',
        placeholder: '请选择被本标准替代的旧标准',
        showSearch: true,
        valueField: 'id',
      },
      dependencies: {
        triggerFields: ['id'],
        componentProps: (values) => ({
          params: { excludeId: values.id },
        }),
      },
    },
    {
      fieldName: 'summary',
      label: '标准摘要/适用范围说明',
      component: 'TextArea',
      formItemClass: 'col-span-2',
      componentProps: {
        placeholder: '请输入标准摘要/适用范围说明',
        rows: 6,
      },
    },
    {
      fieldName: 'status',
      label: '状态',
      rules: 'required',
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
      component: 'TextArea',
      formItemClass: 'col-span-2',
      componentProps: {
        placeholder: '请输入备注',
        rows: 4,
      },
    },
  ];
}

/** 详情展示 */
export function useDetailSchema(): DescriptionItemSchema[] {
  return [
    {
      field: 'stdCode',
      label: '标准编号',
      span: 1,
      render: (val) => val || '-',
    },
    {
      field: 'nameCn',
      label: '标准名称',
      span: 1,
    },
    {
      field: 'stdType',
      label: '标准分类',
      span: 1,
      render: (val) =>
        h(DictTag, { type: DICT_TYPE.STANDARD_STD_TYPE, value: val }),
    },
    {
      field: 'publishOrg',
      label: '发布机构',
      span: 1,
      render: (val) => val || '-',
    },
    {
      field: 'publishDate',
      label: '发布日期',
      span: 1,
      render: (val) => (val ? formatDate(val, 'YYYY-MM-DD') : '-'),
    },
    {
      field: 'implementDate',
      label: '实施日期',
      span: 1,
      render: (val) => (val ? formatDate(val, 'YYYY-MM-DD') : '-'),
    },
    {
      field: 'implementStatus',
      label: '实施状态',
      span: 1,
      render: (val) =>
        h(DictTag, { type: DICT_TYPE.STANDARD_IMPLEMENT_STATUS, value: val }),
    },
    {
      field: 'sort',
      label: '显示顺序',
      span: 1,
    },
    {
      field: 'registryNo',
      label: '入库编号',
      span: 1,
      render: (val) => val || '-',
    },
    {
      field: 'registryTime',
      label: '入库时间',
      span: 1,
      render: (val) => (val ? formatDateTime(val) : '-'),
    },
    {
      field: 'fileName',
      label: '原文文件名',
      span: 2,
      render: (val) => val || '-',
    },
    {
      field: 'replaceDocLabel',
      label: '替代标准',
      span: 2,
      render: (val) => val || '-',
    },
    {
      field: 'summary',
      label: '标准摘要/适用范围说明',
      span: 2,
      render: (val) => val || '-',
    },
    {
      field: 'status',
      label: '状态',
      span: 1,
      render: (val) =>
        h(DictTag, { type: DICT_TYPE.COMMON_STATUS, value: val }),
    },
    {
      field: 'createTime',
      label: '创建时间',
      span: 1,
      render: (val) => (val ? formatDateTime(val) : '-'),
    },
    {
      field: 'remark',
      label: '备注',
      span: 2,
      render: (val) => val || '-',
    },
  ];
}

/** 列表的搜索表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'stdCode',
      label: '标准编号',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入标准编号',
      },
    },
    {
      fieldName: 'nameCn',
      label: '标准名称',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入标准名称',
      },
    },
    {
      fieldName: 'registryNo',
      label: '入库编号',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入入库编号',
      },
    },
    {
      fieldName: 'publishOrg',
      label: '发布机构',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入发布机构',
      },
    },
    {
      fieldName: 'stdType',
      label: '标准分类',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: getDictOptions(DICT_TYPE.STANDARD_STD_TYPE, 'string'),
        placeholder: '请选择标准分类',
      },
    },
    {
      fieldName: 'implementStatus',
      label: '实施状态',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: getDictOptions(DICT_TYPE.STANDARD_IMPLEMENT_STATUS, 'string'),
        placeholder: '请选择实施状态',
      },
    },
    {
      fieldName: 'registryTime',
      label: '入库时间',
      component: 'RangePicker',
      componentProps: {
        ...getRangePickerDefaultProps(),
        allowClear: true,
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

/** 根据原文文件名解析列表图标 */
export function getDocFileIconInfo(fileName?: null | string): {
  color: string;
  icon: string;
} {
  const ext = fileName?.split('.').pop()?.toLowerCase() || '';
  if (ext === 'pdf') {
    return { icon: 'ant-design:file-pdf-filled', color: '#ed5757' };
  }
  if (ext === 'doc' || ext === 'docx') {
    return { icon: 'ant-design:file-word-filled', color: '#2b7cd3' };
  }
  if (ext === 'xls' || ext === 'xlsx') {
    return { icon: 'ant-design:file-excel-filled', color: '#1f7244' };
  }
  if (ext === 'ppt' || ext === 'pptx') {
    return { icon: 'ant-design:file-ppt-filled', color: '#d24726' };
  }
  return { icon: 'ant-design:file-filled', color: '#909399' };
}

/** 列表的字段 */
export function useGridColumns(
  onStatusChange?: (
    newStatus: number,
    row: StandardDocApi.Doc,
  ) => PromiseLike<boolean | undefined>,
): VxeTableGridOptions<StandardDocApi.Doc>['columns'] {
  return [
    { type: 'checkbox', width: 40 },
    {
      field: 'stdCode',
      title: '标准编号',
      minWidth: 150,
    },
    {
      field: 'nameCn',
      title: '标准名称',
      minWidth: 220,
    },
    {
      field: 'fileName',
      title: '原文',
      width: 72,
      align: 'center',
      slots: { default: 'fileType' },
    },
    {
      field: 'stdType',
      title: '标准分类',
      minWidth: 110,
      cellRender: {
        name: 'CellDict',
        props: { type: DICT_TYPE.STANDARD_STD_TYPE },
      },
    },
    {
      field: 'publishOrg',
      title: '发布机构',
      minWidth: 160,
    },
    {
      field: 'publishDate',
      title: '发布日期',
      minWidth: 120,
      formatter: 'formatDate',
    },
    {
      field: 'implementDate',
      title: '实施日期',
      minWidth: 120,
      formatter: 'formatDate',
    },
    {
      field: 'implementStatus',
      title: '实施状态',
      minWidth: 110,
      cellRender: {
        name: 'CellDict',
        props: { type: DICT_TYPE.STANDARD_IMPLEMENT_STATUS },
      },
    },
    {
      field: 'registryNo',
      title: '入库编号',
      minWidth: 140,
    },
    {
      field: 'registryTime',
      title: '入库时间',
      minWidth: 170,
      formatter: 'formatDateTime',
    },
    {
      field: 'status',
      title: '状态',
      width: 100,
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
      title: '操作',
      width: 280,
      fixed: 'right',
      slots: { default: 'actions' },
    },
  ];
}
