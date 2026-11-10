import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { DescriptionItemSchema } from '#/components/description';

import { h } from 'vue';

import { DICT_TYPE } from '@vben/constants';
import { formatDateTime } from '@vben/utils';

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
