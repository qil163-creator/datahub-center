import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

/** 调度类型 */
export const TASK_TYPE_OPTIONS = [
  { label: '一次性', value: 1 },
  { label: '周期性', value: 2 },
];

/** 任务状态 */
export const STATUS_OPTIONS = [
  { label: '未上线', value: 0, color: 'default' },
  { label: '已上线', value: 1, color: 'success' },
];

/** 运行状态 */
export const RUN_STATUS_OPTIONS = [
  { label: '结果待核查', value: 6, color: 'warning' },
  { label: '等待中', value: 1, color: 'default' },
  { label: '运行中', value: 2, color: 'processing' },
  { label: '成功', value: 3, color: 'success' },
  { label: '失败', value: 4, color: 'error' },
  { label: '已停止', value: 5, color: 'warning' },
];

/** 执行方式 */
export const EXECUTE_TYPE_OPTIONS = [
  { label: '手动执行', value: 1 },
  { label: '自动调度', value: 2 },
];

/** 过滤操作符 */
export const FILTER_OPERATOR_OPTIONS = [
  { label: '等于', value: 'EQUALS' },
  { label: '不等于', value: 'NOT_EQUALS' },
  { label: '大于', value: 'GREATER_THAN' },
  { label: '大于等于', value: 'GREATER_EQUAL' },
  { label: '小于', value: 'LESS_THAN' },
  { label: '小于等于', value: 'LESS_EQUAL' },
  { label: '包含', value: 'CONTAINS' },
  { label: '不包含', value: 'NOT_CONTAINS' },
  { label: '开头是', value: 'STARTS_WITH' },
  { label: '结尾是', value: 'ENDS_WITH' },
  { label: '正则匹配', value: 'REGEX' },
  { label: '在列表内', value: 'IN' },
  { label: '不在列表内', value: 'NOT_IN' },
  { label: '区间', value: 'BETWEEN' },
  { label: '为空', value: 'IS_NULL' },
  { label: '不为空', value: 'IS_NOT_NULL' },
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

// ==================== 生产流程 ====================

/** 生产流程列表的搜索表单 */
export function usePipelineGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'name',
      label: '流程名称',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '请输入流程名称' },
    },
    {
      fieldName: 'status',
      label: '任务状态',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: STATUS_OPTIONS.map(({ label, value }) => ({ label, value })),
        placeholder: '请选择任务状态',
      },
    },
  ];
}

/** 生产流程列表字段 */
export function usePipelineGridColumns(): VxeTableGridOptions<DataforgeEtlApi.Pipeline>['columns'] {
  return [
    { field: 'id', title: '编号', width: 70 },
    { field: 'name', title: '流程名称', minWidth: 160, align: 'left' },
    { field: 'revision', title: '草稿修订', width: 90 },
    { field: 'publishedRevision', title: '发布版本', width: 90 },
    {
      field: 'taskType',
      title: '调度类型',
      width: 90,
      formatter: ({ cellValue }) => optionLabel(TASK_TYPE_OPTIONS, cellValue),
    },
    {
      field: 'cron',
      title: 'Cron',
      width: 120,
      formatter: ({ cellValue }) => cellValue || '-',
    },
    {
      field: 'status',
      title: '任务状态',
      width: 100,
      showOverflow: false,
      slots: { default: 'status' },
    },
    {
      field: 'scheduleStatus',
      title: '调度状态',
      width: 90,
      showOverflow: false,
      slots: { default: 'scheduleStatus' },
    },
    {
      field: 'runStatus',
      title: '最近运行',
      width: 90,
      slots: { default: 'runStatus' },
    },
    {
      field: 'startTime',
      title: '开始时间',
      width: 180,
      formatter: 'formatDateTime',
    },
    {
      field: 'endTime',
      title: '结束时间',
      width: 180,
      formatter: 'formatDateTime',
    },
    {
      field: 'releaseUserName',
      title: '上线人',
      width: 90,
      formatter: ({ cellValue }) => cellValue || '-',
    },
    {
      field: 'description',
      title: '描述',
      minWidth: 140,
      align: 'left',
    },
    {
      title: '操作',
      width: 200,
      fixed: 'right',
      showOverflow: false,
      className: 'etl-action-column',
      slots: { default: 'actions' },
    },
  ];
}

// ==================== 流程日志 ====================

/** 流程日志列表的搜索表单 */
export function useRecordGridFormSchema(
  defaultName?: string,
): VbenFormSchema[] {
  return [
    {
      fieldName: 'archived',
      label: '归档状态',
      component: 'Select',
      defaultValue: false,
      componentProps: {
        allowClear: false,
        options: [
          { label: '未归档', value: false },
          { label: '已归档', value: true },
        ],
      },
    },
    {
      fieldName: 'name',
      label: '流程名称',
      component: 'Input',
      defaultValue: defaultName,
      componentProps: { allowClear: true, placeholder: '请输入流程名称' },
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

/** 流程日志列表字段 */
export function useRecordGridColumns(): VxeTableGridOptions<DataforgeEtlApi.EtlRecord>['columns'] {
  return [
    { field: 'id', title: '编号', width: 80 },
    { field: 'name', title: '流程名称', minWidth: 160, align: 'left' },
    {
      field: 'qualityStatus',
      title: '质量验收',
      width: 130,
      slots: { default: 'qualityStatus' },
    },
    {
      field: 'runStatus',
      title: '运行状态',
      width: 100,
      slots: { default: 'runStatus' },
    },
    {
      field: 'executeType',
      title: '执行方式',
      width: 100,
      formatter: ({ cellValue }) =>
        optionLabel(EXECUTE_TYPE_OPTIONS, cellValue),
    },
    {
      field: 'startTime',
      title: '开始时间',
      width: 180,
      formatter: 'formatDateTime',
    },
    {
      field: 'endTime',
      title: '结束时间',
      width: 180,
      formatter: 'formatDateTime',
    },
    {
      field: 'createTime',
      title: '创建时间',
      width: 180,
      formatter: 'formatDateTime',
    },
    {
      title: '操作',
      width: 540,
      fixed: 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ];
}
