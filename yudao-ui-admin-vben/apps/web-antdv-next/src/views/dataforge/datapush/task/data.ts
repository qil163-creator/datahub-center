import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { DataforgeDataPushTaskApi } from '#/api/dataforge/datapush/task';

import { markRaw } from 'vue';

import { handleTree } from '@vben/utils';

import { getDatabaseSimpleList } from '#/api/dataforge/database';
import { getPushCategoryTree } from '#/api/dataforge/datapush/category';
import { getDataPushConfigSimpleList } from '#/api/dataforge/datapush/config';
import { getDeptList } from '#/api/system/dept';
import { CronTab } from '#/components/cron-tab';

import {
  createHiddenIdField,
  createSectionDivider,
} from '../../common/form-section';
import { CHANNEL_TYPE_OPTIONS, optionColor, optionLabel } from '../config/data';
import { buildCategoryOptions } from './category-model';
import IncrementColumnSelect from './modules/increment-column-select.vue';
import SourceTableSelect from './modules/source-table-select.vue';
import { RUN_STATUS } from './run-state';

export { CHANNEL_TYPE_OPTIONS, optionColor, optionLabel };
export { RUN_STATUS } from './run-state';

export const PUSH_TYPE = {
  TABLE: 1,
  SQL: 2,
} as const;

export const PUSH_MODE = {
  FULL: 1,
  INCREMENT: 2,
} as const;

export const TASK_TYPE = {
  ONCE: 1,
  CRON: 2,
} as const;

export const RELEASE_STATUS = {
  UNRELEASED: 0,
  RELEASED: 1,
} as const;

export const RUN_TYPE = {
  MANUAL: 1,
  AUTO: 2,
} as const;

export const PUSH_TYPE_OPTIONS = [
  { label: '数据表', value: PUSH_TYPE.TABLE, color: 'processing' },
  { label: '自定义 SQL', value: PUSH_TYPE.SQL, color: 'default' },
];

export const PUSH_MODE_OPTIONS = [
  { label: '全量', value: PUSH_MODE.FULL, color: 'default' },
  { label: '增量', value: PUSH_MODE.INCREMENT, color: 'processing' },
];

export const TASK_TYPE_OPTIONS = [
  { label: '一次性', value: TASK_TYPE.ONCE, color: 'default' },
  { label: '周期性', value: TASK_TYPE.CRON, color: 'processing' },
];

export const STATUS_OPTIONS = [
  { label: '未发布', value: RELEASE_STATUS.UNRELEASED, color: 'default' },
  { label: '已发布', value: RELEASE_STATUS.RELEASED, color: 'success' },
];

export const RUN_STATUS_OPTIONS = [
  { label: '等待中', value: RUN_STATUS.WAITING, color: 'default' },
  { label: '运行中', value: RUN_STATUS.RUNNING, color: 'processing' },
  { label: '成功', value: RUN_STATUS.SUCCESS, color: 'success' },
  { label: '失败', value: RUN_STATUS.FAILED, color: 'error' },
  { label: '结果未知，待核查', value: RUN_STATUS.UNKNOWN, color: 'warning' },
  { label: '已人工核查处置', value: RUN_STATUS.RESOLVED, color: 'default' },
];

export const RUN_TYPE_OPTIONS = [
  { label: '手动执行', value: RUN_TYPE.MANUAL, color: 'orange' },
  { label: '自动调度', value: RUN_TYPE.AUTO, color: 'blue' },
];

export const BATCH_SIZE_OPTIONS = [50, 100, 200, 500, 1000].map((value) => ({
  label: String(value),
  value,
}));

export const DEFAULT_CRON = '0 0 2 * * ?';

export interface TaskFormValues {
  categoryId?: null | number | string;
  batchSize?: number;
  cron?: string;
  description?: string;
  id?: number | string;
  incrementColumn?: string;
  initStartVal?: string;
  name?: string;
  objectName?: string;
  orgId?: number | string;
  pushConfigId?: number | string;
  pushMode?: number;
  pushType?: number;
  sourceDatabaseId?: number | string;
  sqlText?: string;
  tableName?: string;
  taskType?: number;
}

export function getDefaultFormValues(): TaskFormValues {
  return {
    pushType: PUSH_TYPE.TABLE,
    pushMode: PUSH_MODE.FULL,
    taskType: TASK_TYPE.ONCE,
    batchSize: 100,
    cron: DEFAULT_CRON,
  };
}

export function toFormValues(
  task: DataforgeDataPushTaskApi.Task,
): TaskFormValues {
  return {
    ...getDefaultFormValues(),
    id: task.id,
    categoryId: task.categoryId === null || task.categoryId === undefined
      ? undefined
      : String(task.categoryId),
    name: task.name,
    pushConfigId: task.pushConfigId,
    sourceDatabaseId: task.sourceDatabaseId,
    orgId: task.orgId,
    description: task.description,
    pushType: task.pushType ?? PUSH_TYPE.TABLE,
    objectName: task.objectName,
    tableName: task.tableName,
    sqlText: task.sqlText,
    pushMode: task.pushMode ?? PUSH_MODE.FULL,
    incrementColumn: task.incrementColumn,
    initStartVal: task.initStartVal,
    batchSize: task.batchSize ?? 100,
    taskType: task.taskType ?? TASK_TYPE.ONCE,
    cron: task.cron ?? DEFAULT_CRON,
  };
}

function trimToUndefined(value?: string) {
  const trimmed = value?.trim();
  return trimmed || undefined;
}

export function toSaveReq(
  values: TaskFormValues,
): DataforgeDataPushTaskApi.Task {
  const pushType = values.pushType ?? PUSH_TYPE.TABLE;
  const pushMode = values.pushMode ?? PUSH_MODE.FULL;
  const taskType = values.taskType ?? TASK_TYPE.ONCE;
  return {
    id: values.id,
    categoryId: values.categoryId ?? null,
    name: trimToUndefined(values.name),
    pushConfigId: values.pushConfigId,
    sourceDatabaseId: values.sourceDatabaseId,
    orgId: values.orgId,
    description: trimToUndefined(values.description),
    objectName: trimToUndefined(values.objectName),
    pushType,
    tableName: pushType === PUSH_TYPE.TABLE ? values.tableName : undefined,
    sqlText:
      pushType === PUSH_TYPE.SQL ? trimToUndefined(values.sqlText) : undefined,
    pushMode,
    incrementColumn:
      pushMode === PUSH_MODE.INCREMENT ? values.incrementColumn : undefined,
    initStartVal:
      pushMode === PUSH_MODE.INCREMENT
        ? trimToUndefined(values.initStartVal)
        : undefined,
    batchSize: values.batchSize,
    taskType,
    cron:
      taskType === TASK_TYPE.CRON ? trimToUndefined(values.cron) : undefined,
  };
}

function formatDatabaseLabel(item: { name?: string; usageType?: number }) {
  return `${item.name ?? ''}${item.usageType === 2 ? '（中台库）' : ''}`;
}

export function useFormSchema(): VbenFormSchema[] {
  return [
    createHiddenIdField(),
    createSectionDivider('basicDivider', '基本信息'),
    {
      fieldName: 'name',
      label: '任务名称',
      rules: 'required',
      component: 'Input',
      componentProps: { placeholder: '如：人口表推送公安接口' },
    },
    {
      fieldName: 'categoryId',
      label: '数据推送类目',
      component: 'ApiTreeSelect',
      componentProps: {
        api: async () => buildCategoryOptions(await getPushCategoryTree()),
        labelField: 'label',
        valueField: 'value',
        childrenField: 'children',
        allowClear: true,
        placeholder: '请选择数据推送类目',
        showSearch: true,
        treeNodeFilterProp: 'label',
      },
    },
    {
      fieldName: 'pushConfigId',
      label: '推送配置',
      rules: 'selectRequired',
      component: 'ApiSelect',
      componentProps: {
        api: async () => {
          const list = await getDataPushConfigSimpleList();
          return (list ?? []).map((item) => ({
            label: `${item.name}（${optionLabel(CHANNEL_TYPE_OPTIONS, item.channelType)}）`,
            value: item.id,
          }));
        },
        labelField: 'label',
        valueField: 'value',
        placeholder: '选择已配置的目的地',
        showSearch: true,
        optionFilterProp: 'label',
      },
    },
    {
      fieldName: 'sourceDatabaseId',
      label: '源数据源',
      rules: 'selectRequired',
      component: 'ApiSelect',
      componentProps: {
        api: async () => {
          const list = await getDatabaseSimpleList();
          return (list ?? []).map((item) => ({
            label: formatDatabaseLabel(item),
            value: item.id,
          }));
        },
        labelField: 'label',
        valueField: 'value',
        placeholder: '任意已登记数据源',
        showSearch: true,
        optionFilterProp: 'label',
      },
    },
    {
      fieldName: 'orgId',
      label: '所属单位',
      component: 'ApiTreeSelect',
      componentProps: {
        api: async () => handleTree(await getDeptList()),
        labelField: 'name',
        valueField: 'id',
        childrenField: 'children',
        allowClear: true,
        placeholder: '请选择所属单位',
        showSearch: true,
        treeDefaultExpandAll: true,
        treeNodeFilterProp: 'name',
      },
    },
    {
      fieldName: 'description',
      label: '说明',
      component: 'TextArea',
      formItemClass: 'col-span-2',
      componentProps: { placeholder: '任务用途说明', rows: 2 },
    },
    createSectionDivider('sourceDivider', '数据来源'),
    {
      fieldName: 'pushType',
      label: '推送类型',
      rules: 'selectRequired',
      component: 'RadioGroup',
      componentProps: {
        optionType: 'button',
        options: PUSH_TYPE_OPTIONS.map(({ label, value }) => ({
          label,
          value,
        })),
      },
    },
    {
      fieldName: 'objectName',
      label: '对象名',
      component: 'Input',
      help: '接收方逻辑对象名，空则用表名或 CUSTOM_SQL',
      componentProps: {
        allowClear: true,
        placeholder: '接收方逻辑对象名',
      },
    },
    {
      fieldName: 'tableName',
      label: '源表',
      component: markRaw(SourceTableSelect),
      formItemClass: 'col-span-2',
      dependencies: {
        triggerFields: ['sourceDatabaseId', 'pushType'],
        resolve: ({ values }) => {
          const show = values.pushType === PUSH_TYPE.TABLE;
          return {
            show,
            rules: show ? 'selectRequired' : null,
            componentProps: {
              sourceDatabaseId: values.sourceDatabaseId,
            },
          };
        },
      },
    },
    {
      fieldName: 'sqlText',
      label: '自定义 SQL',
      component: 'TextArea',
      formItemClass: 'col-span-2',
      help: '仅允许单条 SELECT / WITH。一期推查询结果全部列。增量推送前请先解析列，再选择增量字段。',
      componentProps: {
        rows: 5,
        class: 'font-mono',
        placeholder:
          '仅允许单条 SELECT / WITH，例如：SELECT id, name FROM person',
      },
      dependencies: {
        triggerFields: ['pushType'],
        resolve: ({ values }) => {
          const show = values.pushType === PUSH_TYPE.SQL;
          return { show, rules: show ? 'required' : null };
        },
      },
    },
    createSectionDivider('modeDivider', '推送方式与调度'),
    {
      fieldName: 'pushMode',
      label: '推送方式',
      rules: 'selectRequired',
      component: 'RadioGroup',
      help: '全量每次推当前快照；增量按字段水位，成功才推进',
      componentProps: {
        optionType: 'button',
        options: PUSH_MODE_OPTIONS.map(({ label, value }) => ({
          label,
          value,
        })),
      },
    },
    {
      fieldName: 'batchSize',
      label: '每批行数',
      rules: 'selectRequired',
      component: 'Select',
      componentProps: { options: BATCH_SIZE_OPTIONS },
    },
    {
      fieldName: 'incrementColumn',
      label: '增量字段',
      component: markRaw(IncrementColumnSelect),
      formItemClass: 'col-span-2',
      help: '仅显示数值或时间类型字段，避免字符串水位漏数',
      dependencies: {
        triggerFields: [
          'pushMode',
          'pushType',
          'sourceDatabaseId',
          'tableName',
          'sqlText',
        ],
        resolve: ({ values }) => {
          const show = values.pushMode === PUSH_MODE.INCREMENT;
          return {
            show,
            rules: show ? 'selectRequired' : null,
            componentProps: {
              sourceDatabaseId: values.sourceDatabaseId,
              tableName: values.tableName,
              sqlText: values.sqlText,
              pushType: values.pushType,
            },
          };
        },
      },
    },
    {
      fieldName: 'initStartVal',
      label: '首次起始水位',
      component: 'Input',
      formItemClass: 'col-span-2',
      help: '没有历史水位时使用：区间为 (起始, 当前 MAX]。留空则首次从最小扫到 MAX（库通道会整表写入，大表请务必填写）',
      componentProps: {
        allowClear: true,
        placeholder: '可选。不含该值，例如 2024-01-01 或 100000',
      },
      dependencies: {
        triggerFields: ['pushMode'],
        resolve: ({ values }) => ({
          show: values.pushMode === PUSH_MODE.INCREMENT,
        }),
      },
    },
    {
      fieldName: 'taskType',
      label: '调度类型',
      rules: 'selectRequired',
      component: 'RadioGroup',
      help: '一次性任务发布后立即执行；周期任务按 Cron 表达式调度',
      componentProps: {
        optionType: 'button',
        options: TASK_TYPE_OPTIONS.map(({ label, value }) => ({
          label,
          value,
        })),
      },
    },
    {
      fieldName: 'cron',
      label: 'Cron 表达式',
      component: markRaw(CronTab),
      formItemClass: 'col-span-2',
      componentProps: {
        placeholder: '例如：0 0 2 * * ?（每天凌晨 2 点）',
      },
      dependencies: {
        triggerFields: ['taskType'],
        resolve: ({ values }) => {
          const show = values.taskType === TASK_TYPE.CRON;
          return { show, rules: show ? 'required' : null };
        },
      },
    },
  ];
}

export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'name',
      label: '任务名称',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '请输入任务名称' },
    },
    {
      fieldName: 'pushType',
      label: '推送类型',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: PUSH_TYPE_OPTIONS.map(({ label, value }) => ({
          label,
          value,
        })),
        placeholder: '请选择推送类型',
      },
    },
    {
      fieldName: 'pushMode',
      label: '推送方式',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: PUSH_MODE_OPTIONS.map(({ label, value }) => ({
          label,
          value,
        })),
        placeholder: '请选择推送方式',
      },
    },
    {
      fieldName: 'status',
      label: '发布状态',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: STATUS_OPTIONS.map(({ label, value }) => ({ label, value })),
        placeholder: '请选择发布状态',
      },
    },
  ];
}

export function useGridColumns(): VxeTableGridOptions<DataforgeDataPushTaskApi.Task>['columns'] {
  return [
    { field: 'id', title: '编号', width: 70 },
    {
      field: 'name',
      title: '任务名称',
      minWidth: 150,
      align: 'left',
      showOverflow: false,
      slots: { default: 'name' },
    },
    {
      field: 'pushConfigName',
      title: '接收端',
      minWidth: 140,
      formatter: ({ cellValue }) => cellValue || '-',
    },
    {
      field: 'channelType',
      title: '通道',
      width: 90,
      slots: { default: 'channelType' },
    },
    {
      field: 'sourceDatabaseName',
      title: '源数据源',
      minWidth: 130,
      formatter: ({ cellValue }) => cellValue || '-',
    },
    {
      field: 'pushType',
      title: '推送类型',
      width: 100,
      slots: { default: 'pushType' },
    },
    {
      field: 'pushMode',
      title: '推送方式',
      width: 90,
      slots: { default: 'pushMode' },
    },
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
      title: '状态',
      width: 85,
      slots: { default: 'status' },
    },
    {
      field: 'running',
      title: '运行',
      width: 165,
      slots: { default: 'running' },
    },
    {
      field: 'lastRunStatus',
      title: '最近执行',
      width: 90,
      slots: { default: 'lastRunStatus' },
    },
    {
      field: 'lastEndTime',
      title: '最近结束',
      width: 160,
      formatter: 'formatDateTime',
    },
    {
      field: 'lastDataCount',
      title: '最近行数',
      width: 90,
      formatter: ({ cellValue }) =>
        cellValue === undefined || cellValue === null ? '-' : cellValue,
    },
    {
      field: 'watermarkEndVal',
      title: '水位',
      minWidth: 120,
      formatter: ({ cellValue, row }) =>
        row.pushMode === PUSH_MODE.INCREMENT ? (cellValue ?? '尚未产生') : '-',
    },
    {
      field: 'nextRunTime',
      title: '下次执行时间',
      width: 160,
      formatter: 'formatDateTime',
    },
    {
      field: 'releaseUserName',
      title: '发布人',
      width: 90,
      formatter: ({ cellValue }) => cellValue || '-',
    },
    {
      title: '操作',
      width: 380,
      fixed: 'right',
      showOverflow: false,
      className: 'datapush-action-column',
      slots: { default: 'actions' },
    },
  ];
}
