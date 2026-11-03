import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { GovernanceStandardCodeItemApi } from '#/api/governance/standardcodeitem';
import type { DescriptionItemSchema } from '#/components/description';

import { h } from 'vue';

import { DICT_TYPE } from '@vben/constants';
import { formatDateTime } from '@vben/utils';

import { DictTag } from '#/components/dict-tag';

import { formatLifecycleStatus } from '../data';

/** 详情页基础信息 */
export function useDetailSchema(): DescriptionItemSchema[] {
  return [
    {
      field: 'id',
      label: '编号',
    },
    {
      field: 'code',
      label: '数据元编码',
      render: (val) => val || '-',
    },
    {
      field: 'engName',
      label: '英文名称',
      render: (val) => val || '-',
    },
    {
      field: 'columnType',
      label: '字段类型',
      render: (val) => val || '-',
    },
    {
      field: 'description',
      label: '描述',
      span: 3,
      render: (val) => val || '-',
    },
    {
      field: 'categoryName',
      label: '数据元类目',
      render: (val) => val || '-',
    },
    {
      field: 'status',
      label: '状态',
      render: (val) =>
        h(DictTag, { type: DICT_TYPE.COMMON_STATUS, value: val }),
    },
    {
      field: 'lifecycleStatus',
      label: '生命周期',
      render: (val) => formatLifecycleStatus(val as string),
    },
    {
      field: 'stdType',
      label: '标准类型',
      render: (val) =>
        val
          ? h(DictTag, { type: DICT_TYPE.STANDARD_STD_TYPE, value: val })
          : '-',
    },
    {
      field: 'stdCode',
      label: '标准编码',
      render: (val) => val || '-',
    },
    {
      field: 'documentName',
      label: '标准登记',
      span: 2,
      render: (val) => val || '-',
    },
  ];
}

/** 详情页其他信息 */
export function useDetailExtraSchema(): DescriptionItemSchema[] {
  return [
    {
      field: 'dataLength',
      label: '数据长度',
      render: (val) => (val === null || val === undefined ? '-' : String(val)),
    },
    {
      field: 'dataPrecision',
      label: '数据精度',
      render: (val) => (val === null || val === undefined ? '-' : String(val)),
    },
    {
      field: 'dataScale',
      label: '小数位数',
      render: (val) => (val === null || val === undefined ? '-' : String(val)),
    },
    {
      field: 'dataFormat',
      label: '数据格式',
      render: (val) => val || '-',
    },
    {
      field: 'measureUnit',
      label: '计量单位',
      render: (val) => val || '-',
    },
    {
      field: 'valueRange',
      label: '值域范围',
      render: (val) => val || '-',
    },
    {
      field: 'nullableFlag',
      label: '是否可空',
      render: (val) => (val === 1 ? '可空' : val === 0 ? '不可空' : '-'),
    },
    {
      field: 'defaultValue',
      label: '默认值',
      render: (val) => val || '-',
    },
    {
      field: 'personChargeName',
      label: '负责人',
      render: (val) => val || '-',
    },
    {
      field: 'contactNumber',
      label: '联系电话',
      render: (val) => val || '-',
    },
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
      field: 'remark',
      label: '备注',
      span: 2,
      render: (val) => val || '-',
    },
  ];
}

/** 关联码表下的码值列表（只读） */
export function useRelatedItemGridColumns(): VxeTableGridOptions<GovernanceStandardCodeItemApi.StandardCodeItem>['columns'] {
  return [
    {
      field: 'id',
      title: '编号',
      minWidth: 80,
    },
    {
      field: 'label',
      title: '码值标签',
      minWidth: 140,
    },
    {
      field: 'value',
      title: '码值键值',
      minWidth: 120,
    },
    {
      field: 'sort',
      title: '排序',
      minWidth: 80,
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
  ];
}
