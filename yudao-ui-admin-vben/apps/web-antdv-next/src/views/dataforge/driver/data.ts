import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { FoundationDatasourceDriverApi } from '#/api/dataforge/driver';
import type { DescriptionItemSchema } from '#/components/description';

import { h, markRaw } from 'vue';

import { CommonStatusEnum, DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';
import { formatDateTime } from '@vben/utils';

import { z } from '#/adapter/form';
import { ConnectionIcon } from '#/components/connection-icon';
import ConnectionIconUpload from '#/components/connection-icon/connection-icon-upload.vue';
import { DictTag } from '#/components/dict-tag';

/** 驱动来源：内置驱动 */
export const DRIVER_SOURCE_BUILTIN = 'BUILTIN';
/** 驱动来源：上传驱动 */
export const DRIVER_SOURCE_UPLOAD = 'UPLOAD';

/** 上传驱动时显示驱动文件相关字段 */
const uploadDriverDependencies = {
  triggerFields: ['driverSource'],
  show: (values: { driverSource?: string }) =>
    values.driverSource === DRIVER_SOURCE_UPLOAD,
};

/** 表单分组标题 */
function createSectionDivider(
  fieldName: string,
  title: string,
  extra?: Partial<VbenFormSchema>,
): VbenFormSchema {
  return {
    component: 'Divider',
    fieldName,
    hideLabel: true,
    formItemClass: 'col-span-2',
    componentProps: {
      titlePlacement: 'start',
      class: '!my-1',
    },
    renderComponentContent: () => ({
      default: () => [title],
    }),
    ...extra,
  };
}

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
    createSectionDivider('basicDivider', '基本信息'),
    {
      fieldName: 'driverCode',
      label: '驱动编码',
      rules: 'required',
      component: 'Input',
      formItemClass: 'col-span-1',
      componentProps: {
        placeholder: '请输入驱动编码',
      },
    },
    {
      fieldName: 'driverName',
      label: '驱动名称',
      rules: 'required',
      component: 'Input',
      formItemClass: 'col-span-1',
      componentProps: {
        placeholder: '请输入驱动名称',
      },
    },
    {
      fieldName: 'productType',
      label: '数据库类型',
      rules: 'required',
      component: 'Select',
      formItemClass: 'col-span-1',
      componentProps: {
        options: getDictOptions(DICT_TYPE.DATAHUB_PRODUCT_TYPE, 'string'),
        placeholder: '请选择数据库类型',
      },
    },
    {
      fieldName: 'driverSource',
      label: '驱动来源',
      rules: 'required',
      component: 'Select',
      formItemClass: 'col-span-1',
      componentProps: {
        options: getDictOptions(DICT_TYPE.DATAHUB_DRIVER_SOURCE, 'string'),
        placeholder: '请选择驱动来源',
      },
    },
    {
      fieldName: 'icon',
      label: '连接图标',
      component: markRaw(ConnectionIconUpload),
      formItemClass: 'col-span-1',
      componentProps: {
        maxNumber: 1,
      },
    },
    {
      fieldName: 'supportSchema',
      label: 'Schema 支持',
      rules: 'required',
      component: 'RadioGroup',
      formItemClass: 'col-span-1',
      componentProps: {
        options: [
          { label: '是', value: 1 },
          { label: '否', value: 0 },
        ],
        buttonStyle: 'solid',
        optionType: 'button',
      },
    },
    createSectionDivider('connectionDivider', '连接配置'),
    {
      fieldName: 'driverClassName',
      label: '驱动类名',
      rules: 'required',
      component: 'Input',
      formItemClass: 'col-span-2',
      componentProps: {
        placeholder: '请输入驱动类名',
      },
    },
    {
      fieldName: 'jdbcUrlTemplate',
      label: 'URL 模板',
      rules: 'required',
      component: 'Input',
      formItemClass: 'col-span-2',
      componentProps: {
        placeholder: '请输入 URL 模板',
      },
    },
    {
      fieldName: 'testSql',
      label: '连接测试 SQL',
      component: 'Input',
      formItemClass: 'col-span-2',
      componentProps: {
        placeholder: '请输入测试 SQL',
      },
    },
    createSectionDivider('jarDivider', '驱动文件', {
      dependencies: uploadDriverDependencies,
    }),
    {
      fieldName: 'jarFilePath',
      label: '驱动 JAR 文件',
      component: 'FileUpload',
      formItemClass: 'col-span-2',
      dependencies: {
        triggerFields: ['driverSource'],
        show: (values: { driverSource?: string }) =>
          values.driverSource === DRIVER_SOURCE_UPLOAD,
        rules: (values: { driverSource?: string }) =>
          values.driverSource === DRIVER_SOURCE_UPLOAD
            ? z.string().min(1, '请上传驱动Jar文件')
            : null,
      },
      componentProps: {
        maxNumber: 1,
        maxSize: 100,
        accept: ['jar'],
        helpText: '支持上传 .jar 格式的驱动文件，最大 100MB',
      },
    },
    {
      fieldName: 'jarFileName',
      component: 'Input',
      dependencies: {
        triggerFields: [''],
        show: () => false,
      },
    },
    {
      fieldName: 'jarFileSize',
      component: 'InputNumber',
      dependencies: {
        triggerFields: [''],
        show: () => false,
      },
    },
    {
      fieldName: 'jarFileMd5',
      component: 'Input',
      dependencies: {
        triggerFields: [''],
        show: () => false,
      },
    },
    createSectionDivider('otherDivider', '其他设置'),
    {
      fieldName: 'sort',
      label: '排序',
      rules: 'required',
      component: 'InputNumber',
      formItemClass: 'col-span-1',
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
      component: 'RadioGroup',
      formItemClass: 'col-span-1',
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
        rows: 3,
      },
    },
  ];
}

/** 格式化文件大小 */
function formatFileSize(size?: number) {
  if (size === undefined || size === null) {
    return '-';
  }
  if (size < 1024) {
    return `${size} B`;
  }
  if (size < 1024 * 1024) {
    return `${(size / 1024).toFixed(2)} KB`;
  }
  return `${(size / 1024 / 1024).toFixed(2)} MB`;
}

/** 详情页展示 */
export function useDetailSchema(): DescriptionItemSchema[] {
  return [
    {
      field: 'driverCode',
      label: '驱动编码',
      span: 1,
    },
    {
      field: 'driverName',
      label: '驱动名称',
      span: 1,
    },
    {
      field: 'productType',
      label: '数据库类型',
      span: 1,
      render: (val) =>
        h(DictTag, { type: DICT_TYPE.DATAHUB_PRODUCT_TYPE, value: val }),
    },
    {
      field: 'driverSource',
      label: '驱动来源',
      span: 1,
      render: (val) =>
        h(DictTag, { type: DICT_TYPE.DATAHUB_DRIVER_SOURCE, value: val }),
    },
    {
      field: 'icon',
      label: '连接图标',
      span: 2,
      render: (val) =>
        h(ConnectionIcon, { iconPath: val, class: 'size-16 rounded' }),
    },
    {
      field: 'supportSchema',
      label: 'Schema 支持',
      span: 1,
      render: (val) => (val === 1 ? '是' : '否'),
    },
    {
      field: 'driverClassName',
      label: '驱动类名',
      span: 2,
    },
    {
      field: 'jdbcUrlTemplate',
      label: 'URL 模板',
      span: 2,
    },
    {
      field: 'testSql',
      label: '连接测试 SQL',
      span: 2,
      render: (val) => val || '-',
    },
    {
      field: 'jarFileName',
      label: 'JAR 文件名',
      span: 1,
      render: (val, data) =>
        data?.driverSource === DRIVER_SOURCE_UPLOAD ? val || '-' : '-',
    },
    {
      field: 'jarFileSize',
      label: 'JAR 大小',
      span: 1,
      render: (val, data) =>
        data?.driverSource === DRIVER_SOURCE_UPLOAD ? formatFileSize(val) : '-',
    },
    {
      field: 'jarFilePath',
      label: 'JAR 路径',
      span: 2,
      render: (val, data) =>
        data?.driverSource === DRIVER_SOURCE_UPLOAD ? val || '-' : '-',
    },
    {
      field: 'jarFileMd5',
      label: 'JAR MD5',
      span: 2,
      render: (val, data) =>
        data?.driverSource === DRIVER_SOURCE_UPLOAD ? val || '-' : '-',
    },
    {
      field: 'sort',
      label: '排序',
      span: 1,
    },
    {
      field: 'status',
      label: '状态',
      span: 1,
      render: (val) =>
        h(DictTag, { type: DICT_TYPE.COMMON_STATUS, value: val }),
    },
    {
      field: 'remark',
      label: '备注',
      span: 2,
      render: (val) => val || '-',
    },
    {
      field: 'createTime',
      label: '创建时间',
      span: 1,
      render: (val) => (val ? formatDateTime(val) : '-'),
    },
    {
      field: 'updateTime',
      label: '更新时间',
      span: 1,
      render: (val) => (val ? formatDateTime(val) : '-'),
    },
  ];
}

/** 列表的搜索表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'driverName',
      label: '驱动名称',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入驱动名称',
      },
    },
    {
      fieldName: 'productType',
      label: '数据库类型',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: getDictOptions(DICT_TYPE.DATAHUB_PRODUCT_TYPE, 'string'),
        placeholder: '请选择数据库类型',
      },
    },
  ];
}

/** 列表的字段 */
export function useGridColumns(): VxeTableGridOptions<FoundationDatasourceDriverApi.DatasourceDriver>['columns'] {
  return [
    { type: 'checkbox', width: 40 },
    {
      field: 'id',
      title: '驱动编号',
      minWidth: 120,
    },
    {
      field: 'driverCode',
      title: '驱动编码',
      minWidth: 120,
    },
    {
      field: 'driverName',
      title: '驱动名称',
      minWidth: 120,
    },
    {
      field: 'productType',
      title: '数据库类型',
      minWidth: 120,
      cellRender: {
        name: 'CellDict',
        props: { type: DICT_TYPE.DATAHUB_PRODUCT_TYPE },
      },
    },
    {
      field: 'driverSource',
      title: '驱动来源',
      minWidth: 120,
      cellRender: {
        name: 'CellDict',
        props: { type: DICT_TYPE.DATAHUB_DRIVER_SOURCE },
      },
    },
    {
      field: 'driverClassName',
      title: '驱动类名',
      minWidth: 120,
    },
    {
      field: 'jdbcUrlTemplate',
      title: 'URL模板',
      minWidth: 120,
    },
    {
      field: 'icon',
      title: '连接图标',
      minWidth: 120,
      slots: { default: 'connectionIcon' },
    },
    {
      field: 'sort',
      title: '排序',
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
      field: 'remark',
      title: '备注',
      minWidth: 120,
    },
    {
      field: 'createTime',
      title: '创建时间',
      minWidth: 120,
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
