import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { GovernanceLineageApi } from '#/api/governance/lineage';

import { labelOptions, SOURCE_TYPE_LABELS, STATUS_LABELS } from './graph-model';

/** 解析记录筛选。 */
export function useSourceFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'keyword',
      label: '来源名称',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '请输入任务或流程名称' },
    },
    {
      fieldName: 'sourceType',
      label: '来源类型',
      component: 'Select',
      componentProps: { allowClear: true, options: labelOptions(SOURCE_TYPE_LABELS), placeholder: '全部' },
    },
    {
      fieldName: 'status',
      label: '解析状态',
      component: 'Select',
      componentProps: { allowClear: true, options: labelOptions(STATUS_LABELS), placeholder: '全部' },
    },
  ];
}

/** 解析记录列。 */
export function useSourceColumns(): VxeTableGridOptions<GovernanceLineageApi.Source>['columns'] {
  return [
    { field: 'sourceType', title: '来源类型', minWidth: 110, slots: { default: 'sourceType' } },
    { field: 'sourceName', title: '来源名称', minWidth: 200 },
    { field: 'sourceId', title: '来源编号', minWidth: 110 },
    { field: 'status', title: '解析状态', minWidth: 110, slots: { default: 'status' } },
    { field: 'tableEdgeCount', title: '表级血缘', minWidth: 90 },
    { field: 'columnEdgeCount', title: '字段血缘', minWidth: 90 },
    { title: '解析问题', minWidth: 260, slots: { default: 'issues' } },
    { field: 'reportedTime', title: '上报时间', minWidth: 170, formatter: 'formatDateTime' },
  ];
}
