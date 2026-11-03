import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { GovernanceMetaCollectTaskApi } from '#/api/governance/metacollecttask';

import { metadataTime } from '../metadata-common/helpers';
export const SCOPE_TYPE_OPTIONS = [
  { label: '整个数据源', value: 1 },
  { label: '自定义库', value: 2 },
];
export const EXECUTE_STRATEGY_OPTIONS = [
  { label: '并行', value: 'PARALLEL' },
  { label: '串行等待', value: 'SERIAL_WAIT' },
  { label: '串行抛弃', value: 'SERIAL_DISCARD' },
  { label: '串行优先', value: 'SERIAL_PRIORITY' },
];
export function optionLabel(
  options: { label: string; value: number | string }[],
  value?: null | number | string,
) {
  return options.find((item) => item.value === value)?.label ?? '-';
}
export function useGridColumns(): VxeTableGridOptions<GovernanceMetaCollectTaskApi.CollectTask>['columns'] {
  return [
    { type: 'checkbox', width: 42 },
    { field: 'id', title: '编号', width: 70 },
    {
      field: 'name',
      title: '任务信息',
      minWidth: 360,
      align: 'left',
      showOverflow: false,
      slots: { default: 'taskInfo' },
    },
    {
      field: 'sourceSystemName',
      title: '采集来源',
      minWidth: 240,
      align: 'left',
      slots: { default: 'source' },
    },
    {
      field: 'enabled',
      title: '任务/调度状态',
      width: 145,
      align: 'left',
      slots: { default: 'state' },
    },
    {
      field: 'cron',
      title: '调度周期/最近运行时间',
      width: 230,
      align: 'left',
      slots: { default: 'schedule' },
    },
    {
      field: 'ownerName',
      title: '负责人',
      width: 130,
      align: 'left',
      slots: { default: 'owner' },
    },
    {
      field: 'creatorName',
      title: '创建人',
      width: 130,
      align: 'left',
      slots: { default: 'creator' },
    },
    {
      field: 'createTime',
      title: '创建时间',
      width: 170,
      formatter: ({ cellValue }) => metadataTime(cellValue, 'YYYY-MM-DD HH:mm'),
    },
    {
      title: '操作',
      width: 210,
      fixed: 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ];
}
