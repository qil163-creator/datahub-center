import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';

import { markRaw } from 'vue';

import { DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';

import ConnectionIconUpload from '#/components/connection-icon/connection-icon-upload.vue';

/** 协议值与服务端连接器注册保持一致；标签优先使用已加载的系统字典。 */
export const CONNECTION_PROTOCOLS = [
  'JDBC',
  'HTTP',
  'FTP',
  'WEBHDFS',
  'SFTP',
  'S3',
  'REDIS',
  'KAFKA',
  'AMQP',
  'ROCKETMQ',
  'ODPS',
  'BOLT',
  'MILVUS',
] as const;

function protocolOptions() {
  const options = getDictOptions(DICT_TYPE.PROTOCOL_TYPE, 'string');
  const known = new Set(options.map((option) => option.value));
  return [
    ...options,
    ...CONNECTION_PROTOCOLS.filter((value) => !known.has(value)).map(
      (value) => ({ label: value, value }),
    ),
  ];
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
    {
      fieldName: 'typeCode',
      label: '数据源编码',
      rules: 'required',
      component: 'Input',
      componentProps: {
        placeholder: '请输入数据源编码',
      },
    },
    {
      fieldName: 'typeName',
      label: '数据源名称',
      rules: 'required',
      component: 'Input',
      componentProps: {
        placeholder: '请输入数据源名称',
      },
    },
    {
      fieldName: 'description',
      label: '描述',
      component: 'TextArea',
      formItemClass: 'col-span-2',
      componentProps: {
        maxlength: 512,
        rows: 2,
        showCount: true,
        placeholder: '请输入连接器描述',
      },
    },
    {
      fieldName: 'categoryCode',
      label: '连接分类',
      rules: 'required',
      component: 'Select',
      componentProps: () => ({
        options: getDictOptions(DICT_TYPE.CATEGORY_CODE, 'string'),
        placeholder: '请选择连接分类',
      }),
    },
    {
      fieldName: 'protocolType',
      label: '连接协议类型',
      rules: 'required',
      component: 'Select',
      componentProps: () => ({
        options: protocolOptions(),
        placeholder: '请选择连接协议类型',
      }),
    },
    {
      fieldName: 'iconPath',
      label: '连接图标路径',
      rules: 'required',
      component: markRaw(ConnectionIconUpload),
      dependencies: {
        triggerFields: ['typeCode'],
        resolve: ({ values }) => ({
          componentProps: { typeCode: values.typeCode },
        }),
      },
      formItemClass: 'col-span-2',
    },
    {
      fieldName: 'urlTemplates',
      label: '数据源URL',
      dependencies: {
        triggerFields: ['protocolType'],
        resolve: ({ values }) => ({
          rules: values.protocolType === 'JDBC' ? 'required' : null,
        }),
      },
      component: 'Input',
      formItemClass: 'col-span-2',
      componentProps: {
        placeholder: '请输入数据源URL',
      },
    },
    {
      fieldName: 'portTemplates',
      label: '默认端口',
      component: 'Input',
      formItemClass: 'col-span-2',
      componentProps: {
        placeholder: '请输入默认端口',
      },
    },
    {
      fieldName: 'validationQuery',
      label: '连接测试SQL',
      dependencies: {
        triggerFields: ['protocolType'],
        resolve: ({ values }) => ({
          rules: values.protocolType === 'JDBC' ? 'required' : null,
        }),
      },
      component: 'Input',
      formItemClass: 'col-span-2',
      componentProps: {
        placeholder: '请输入连接测试SQL',
      },
    },
    {
      fieldName: 'sort',
      label: '排序',
      rules: 'required',
      component: 'Input',
      componentProps: {
        placeholder: '请输入排序',
      },
    },
    {
      fieldName: 'status',
      label: '状态',
      rules: 'required',
      component: 'Select',
      componentProps: {
        options: getDictOptions(DICT_TYPE.COMMON_STATUS, 'number'),
        placeholder: '请选择状态',
      },
    },
    {
      fieldName: 'remark',
      label: '备注',
      component: 'TextArea',
      formItemClass: 'col-span-2',
      componentProps: {
        placeholder: '请输入备注',
      },
    },
  ];
}

/** 列表的搜索表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'typeCode',
      label: '数据源编码',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入数据源编码',
      },
    },
    {
      fieldName: 'typeName',
      label: '数据源名称',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入数据源名称',
      },
    },
    {
      fieldName: 'categoryCode',
      label: '连接分类',
      component: 'Select',
      componentProps: () => ({
        allowClear: true,
        options: getDictOptions(DICT_TYPE.CATEGORY_CODE, 'string'),
        placeholder: '请选择连接分类',
      }),
    },
    {
      fieldName: 'protocolType',
      label: '连接协议类型',
      component: 'Select',
      componentProps: () => ({
        allowClear: true,
        options: protocolOptions(),
        placeholder: '请选择连接协议类型',
      }),
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
export function useGridColumns(): VxeTableGridOptions<FoundationDatabaseTypeApi.DatabaseType>['columns'] {
  return [
    { type: 'checkbox', width: 40 },
    {
      field: 'id',
      title: '编号',
      minWidth: 80,
    },
    {
      field: 'typeCode',
      title: '数据源编码',
      minWidth: 120,
    },
    {
      field: 'typeName',
      title: '数据源名称',
      minWidth: 120,
    },
    {
      field: 'description',
      title: '描述',
      minWidth: 220,
    },
    {
      field: 'categoryCode',
      title: '连接分类',
      minWidth: 120,
      cellRender: {
        name: 'CellDict',
        props: { type: DICT_TYPE.CATEGORY_CODE },
      },
    },
    {
      field: 'protocolType',
      title: '连接协议类型',
      minWidth: 100,
      cellRender: {
        name: 'CellDict',
        props: { type: DICT_TYPE.PROTOCOL_TYPE },
      },
    },
    {
      field: 'iconPath',
      title: '连接图标',
      minWidth: 120,
      slots: { default: 'connectionIcon' },
    },
    {
      field: 'urlTemplates',
      title: '数据源URL',
      minWidth: 250,
    },
    {
      field: 'portTemplates',
      title: '默认端口',
      minWidth: 120,
    },
    {
      field: 'status',
      title: '状态',
      minWidth: 120,
      slots: { default: 'status' },
    },
    {
      field: 'remark',
      title: '备注',
      minWidth: 160,
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
