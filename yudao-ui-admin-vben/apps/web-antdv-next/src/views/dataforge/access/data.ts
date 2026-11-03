import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { DataforgeAccessApi } from '#/api/dataforge/access';

import { getRunState } from '#/views/dataforge/execution/run-status';

/** 同步引擎类型（同一页面用类型区分不同引擎的任务） */
export const ENGINE_TYPE_OPTIONS = [
  { label: '离线同步', value: 1, color: 'blue' },
  { label: '实时同步', value: 2, color: 'cyan' },
  { label: 'API接入', value: 3, color: 'purple' },
];

/** 任务类型（调度类型），实时同步仅 Seatunnel 引擎可选 */
export const TASK_TYPE_OPTIONS = [
  { label: '实时同步', value: 1, color: 'cyan' },
  { label: '一次性全量', value: 2, color: 'geekblue' },
  { label: '周期增量', value: 3, color: 'purple' },
];

/** Seatunnel 配置格式 */
export const CONFIG_FORMAT_OPTIONS = [
  { label: 'json', value: 'json' },
  { label: 'hocon', value: 'hocon' },
];

/** 发布状态 */
export const RELEASE_STATUS_OPTIONS = [
  { label: '未发布', value: 0, color: 'default' },
  { label: '已发布', value: 1, color: 'success' },
];

/** 运行状态 */
export const RUN_STATUS_OPTIONS = [1, 2, 3, 4, 5, 6].map((value) => {
  const { label, color } = getRunState('ACCESS', { runStatus: value });
  return { label, color, value };
});

/** 接入方式 */
export const ACCESS_MODE_OPTIONS = [
  { label: '入中台库', value: 1 },
  { label: '自定义接入', value: 2 },
];

/** 源端类型 */
export const SOURCE_TYPE_OPTIONS = [
  { label: '指定表', value: 1 },
  { label: '自定义 SQL', value: 2 },
];

/** 包含/排除所选表 */
export const INCLUDE_OR_EXCLUDE_OPTIONS = [
  { label: '包含表', value: 1 },
  { label: '排除表', value: 0 },
];

/** 增量变更类型 */
export const CHANGE_DATA_SYNC_TYPE_OPTIONS = [
  { label: '主键排序比对', value: 'PK_ORDER_COMPARE' },
  { label: '增量字段', value: 'INCREASE_COLUMN' },
];

/** 目标表名/字段名转换方式 */
export const NAME_CASE_OPTIONS: {
  label: string;
  value: DataforgeAccessApi.NameCase;
}[] = [
  { label: '不转换', value: 'NONE' },
  { label: '全大写', value: 'UPPER' },
  { label: '全小写', value: 'LOWER' },
  { label: '驼峰转下划线', value: 'SNAKE' },
  { label: '下划线转驼峰', value: 'CAMEL' },
];

/**
 * 旧任务只保存了同时作用于表名和字段名的大小写开关时，回显等效的转换方式；
 * 两个开关同时开启时执行结果为大写。
 */
export function savedNameCase(
  saved: DataforgeAccessApi.NameCase | null | undefined,
  lowercase?: boolean,
  uppercase?: boolean,
): DataforgeAccessApi.NameCase {
  if (saved) return saved;
  if (uppercase) return 'UPPER';
  return lowercase ? 'LOWER' : 'NONE';
}

/** 触发方式 */
export const RUN_TYPE_OPTIONS = [
  { label: '自动调度', value: 1, color: 'blue' },
  { label: '手动执行', value: 2, color: 'orange' },
];

export function optionLabel(
  options: { label: string; value: number | string }[],
  value?: null | number | string,
) {
  return options.find((item) => item.value === value)?.label ?? '-';
}

export function optionColor(
  options: { color?: string; label: string; value: number | string }[],
  value?: null | number | string,
) {
  return options.find((item) => item.value === value)?.color ?? 'default';
}

/** 列表的搜索表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'taskName',
      label: '任务名称',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入任务名称',
      },
    },
    {
      fieldName: 'engineType',
      label: '同步类型',
      component: 'Select',
      componentProps: {
        allowClear: true,
        // API 接入在独立的「接口采集」页面维护
        options: ENGINE_TYPE_OPTIONS.filter((item) => item.value !== 3).map(
          ({ label, value }) => ({ label, value }),
        ),
        placeholder: '请选择同步类型',
      },
    },
    {
      fieldName: 'status',
      label: '发布状态',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: RELEASE_STATUS_OPTIONS.map(({ label, value }) => ({
          label,
          value,
        })),
        placeholder: '请选择发布状态',
      },
    },
    {
      fieldName: 'runStatus',
      label: '运行状态',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: RUN_STATUS_OPTIONS.map(({ label, value }) => ({
          label,
          value,
        })),
        placeholder: '请选择运行状态',
      },
    },
  ];
}

/** 列表的字段 */
export function useGridColumns(): VxeTableGridOptions<DataforgeAccessApi.DataAccess>['columns'] {
  return [
    {
      field: 'id',
      title: '编号',
      width: 80,
    },
    {
      field: 'taskName',
      title: '任务名称',
      minWidth: 160,
    },
    {
      field: 'engineType',
      title: '同步类型',
      width: 90,
      slots: { default: 'engineType' },
    },
    {
      field: 'taskType',
      title: '调度类型',
      width: 100,
      slots: { default: 'taskType' },
    },
    {
      field: 'status',
      title: '发布状态',
      width: 90,
      slots: { default: 'status' },
    },
    {
      field: 'runStatus',
      title: '最新状态',
      width: 90,
      slots: { default: 'runStatus' },
    },
    {
      field: 'startTime',
      title: '最近开始时间',
      width: 140,
      formatter: 'formatDateTime',
    },
    {
      field: 'nextRunTime',
      title: '下次执行时间',
      width: 140,
      formatter: 'formatDateTime',
    },
    {
      field: 'releaseUserName',
      title: '发布人',
      width: 90,
      formatter: ({ cellValue }) => cellValue || '-',
    },
    {
      field: 'orgName',
      title: '所属单位',
      minWidth: 110,
      formatter: ({ cellValue }) => cellValue || '-',
    },
    {
      field: 'creatorName',
      title: '创建人',
      width: 90,
      formatter: ({ cellValue }) => cellValue || '-',
    },
    {
      field: 'createTime',
      title: '创建时间',
      width: 140,
      formatter: 'formatDateTime',
    },
    {
      title: '操作',
      width: 140,
      minWidth: 140,
      fixed: 'right',
      showOverflow: false,
      className: 'access-action-column',
      slots: { default: 'actions' },
    },
  ];
}
