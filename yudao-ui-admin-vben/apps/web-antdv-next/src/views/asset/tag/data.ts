import type { AssetTagApi } from '#/api/asset/tag';

import { formatDate } from '@vben/utils';
export function tagAsset(name: string) {
  return `/assets/qdata-tag/${name}`;
}
export function tagNumber(tag: AssetTagApi.Tag) {
  return tag.sourceRecordId || String(tag.id ?? '-');
}
export function tagDate(value: number | string | undefined) {
  return value ? formatDate(value, 'YYYY-MM-DD HH:mm') : '-';
}
export const tagStatusOptions = [
  { label: '启用', value: 0 },
  { label: '禁用', value: 1 },
];
export const tagColumns = [
  {
    title: '编号',
    key: 'id',
    width: 70,
    sorter: true,
    align: 'center' as const,
  },
  { title: '标签名称', key: 'name', width: 210 },
  { title: '标签类目', key: 'categoryName', width: 140 },
  { title: '描述', key: 'description', width: 230 },
  {
    title: '资产数量',
    key: 'assetCount',
    width: 120,
    sorter: true,
    align: 'center' as const,
  },
  { title: '状态', key: 'status', width: 120, align: 'center' as const },
  { title: '创建人', key: 'creatorName', width: 120, align: 'center' as const },
  {
    title: '创建时间',
    key: 'createTime',
    width: 160,
    sorter: true,
    align: 'center' as const,
  },
  {
    title: '操作',
    key: 'actions',
    width: 240,
    fixed: 'right' as const,
    align: 'center' as const,
  },
];

export const tagFont =
  "'Helvetica Neue',Helvetica,'PingFang SC','Hiragino Sans GB','Microsoft YaHei',Arial,sans-serif";
