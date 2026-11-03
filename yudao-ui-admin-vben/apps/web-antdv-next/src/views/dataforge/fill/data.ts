import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { DataforgeFillApi } from '#/api/dataforge/fill';

import { markRaw } from 'vue';

import { handleTree } from '@vben/utils';

import { z } from '#/adapter/form';
import {
  getPlanCategoryList,
  getTableVersionListByTable,
} from '#/api/dataforge/fill';
import { getFillTablePage } from '#/api/dataforge/filltable';
import { getSimpleDeptList } from '#/api/system/dept';
import {
  createHiddenIdField,
  createSectionDivider,
} from '#/views/dataforge/common/form-section';

import OrgAssigneeList from './plan/modules/org-assignee-list.vue';
import {
  PERIOD_TYPE_OPTIONS,
  PLAN_STATUS_TEXT,
  TASK_STATUS_TEXT,
  WEEK_DAY_OPTIONS,
  WORK_ITEM_STATUS_OPTIONS,
} from './shared';

/** 采集计划状态下拉选项 */
export const PLAN_STATUS_OPTIONS = Object.entries(PLAN_STATUS_TEXT).map(
  ([value, item]) => ({ label: item.text, value: Number(value) }),
);

/** 采集任务状态下拉选项 */
export const FILL_TASK_STATUS_OPTIONS = Object.entries(TASK_STATUS_TEXT).map(
  ([value, item]) => ({ label: item.text, value: Number(value) }),
);

/** 合并批次状态 */
export const MERGE_STATUS_TEXT: Record<
  number,
  { color: string; text: string }
> = {
  0: { color: 'default', text: '待执行' },
  1: { color: 'processing', text: '执行中' },
  2: { color: 'success', text: '成功' },
  3: { color: 'error', text: '失败' },
};

export const MERGE_STATUS_OPTIONS = Object.entries(MERGE_STATUS_TEXT).map(
  ([value, item]) => ({ label: item.text, value: Number(value) }),
);

// ==================== 我的填报 ====================

/** 我的填报搜索（后端 /my 不分页，列表侧本地过滤） */
export function useMyGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'taskName',
      label: '计划/任务名',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '请输入计划或任务名称' },
    },
    {
      fieldName: 'status',
      label: '状态',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: WORK_ITEM_STATUS_OPTIONS,
        placeholder: '请选择状态',
      },
    },
  ];
}

/** 我的填报列表字段 */
export function useMyGridColumns(): VxeTableGridOptions<DataforgeFillApi.WorkItem>['columns'] {
  return [
    { field: 'id', title: '编号', width: 80 },
    { field: 'taskName', title: '任务名称', minWidth: 200, align: 'left' },
    {
      field: 'periodKey',
      title: '所属期',
      width: 110,
      formatter: ({ cellValue }) => cellValue || '-',
    },
    {
      field: 'orgName',
      title: '报送组织',
      width: 160,
      formatter: ({ cellValue }) => cellValue || '-',
    },
    {
      field: 'status',
      title: '状态',
      width: 170,
      showOverflow: false,
      slots: { default: 'status' },
    },
    { field: 'draftRowCount', title: '草稿行数', width: 90 },
    {
      field: 'deadlineTime',
      title: '截止时间',
      width: 165,
      formatter: 'formatDateTime',
    },
    {
      title: '操作',
      width: 110,
      fixed: 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ];
}

// ==================== 部门填报查看 ====================

/** 部门填报查看的搜索表单 */
export function useDeptViewGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'deptId',
      label: '部门',
      component: 'ApiTreeSelect',
      componentProps: {
        allowClear: true,
        api: async () => handleTree(await getSimpleDeptList()),
        childrenField: 'children',
        labelField: 'name',
        valueField: 'id',
        placeholder: '默认本部门',
        showSearch: true,
        treeDefaultExpandAll: true,
        treeNodeFilterProp: 'name',
      },
    },
  ];
}

/** 部门填报查看列表字段 */
export function useDeptViewGridColumns(): VxeTableGridOptions<DataforgeFillApi.WorkItem>['columns'] {
  return [
    { field: 'id', title: '编号', width: 80 },
    { field: 'taskName', title: '任务名称', minWidth: 180, align: 'left' },
    {
      field: 'periodKey',
      title: '所属期',
      width: 110,
      formatter: ({ cellValue }) => cellValue || '-',
    },
    {
      field: 'orgName',
      title: '报送组织',
      width: 160,
      formatter: ({ cellValue }) => cellValue || '-',
    },
    {
      field: 'status',
      title: '状态',
      width: 150,
      showOverflow: false,
      slots: { default: 'status' },
    },
    {
      field: 'assignees',
      title: '当前填报人',
      width: 200,
      showOverflow: false,
      slots: { default: 'assignees' },
    },
    { field: 'draftRowCount', title: '草稿行数', width: 90 },
    {
      field: 'lastModifyTime',
      title: '最后修改',
      width: 160,
      formatter: 'formatDateTime',
    },
    {
      field: 'deadlineTime',
      title: '截止时间',
      width: 160,
      formatter: 'formatDateTime',
    },
    {
      title: '操作',
      width: 100,
      fixed: 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ];
}

// ==================== 采集计划 ====================

export interface PlanFormValues {
  categoryId?: number | string;
  deadlineOffsetDays?: number;
  deadlineTime?: string;
  description?: string;
  generateTime?: string;
  id?: number | string;
  lockCore?: boolean;
  monthGenerateDay?: number;
  name?: string;
  onceDeadlineTime?: string;
  onceOpenTime?: string;
  orgs?: {
    assigneeUserIds: (number | string)[];
    reportingOrgId?: number | string;
  }[];
  overduePolicy?: number;
  periodOffset?: number;
  periodType?: number;
  prefillLastPeriod?: boolean;
  processDefinitionKey?: string;
  quarterGenerateDay?: number;
  remindHoursBeforeDeadline?: number;
  remindUrgeCooldownMinutes?: number;
  tableId?: number | string;
  tableVersionId?: number | string;
  weekGenerateDay?: number;
}

export function getDefaultPlanFormValues(): PlanFormValues {
  return {
    lockCore: false,
    orgs: [{ assigneeUserIds: [] }],
    overduePolicy: 1,
    periodType: 4,
    prefillLastPeriod: false,
    processDefinitionKey: 'dataforge_fill_approval',
  };
}

function toIsoDateTime(value?: string) {
  if (!value) {
    return undefined;
  }
  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/.test(value)) {
    return value.length === 16 ? `${value}:00` : value;
  }
  if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}(:\d{2})?$/.test(value)) {
    const iso = value.replace(' ', 'T');
    return iso.length === 16 ? `${iso}:00` : iso;
  }
  return value;
}

function toHm(value?: string) {
  if (!value) {
    return undefined;
  }
  const match = /^(\d{2}:\d{2})/.exec(value);
  return match?.[1];
}

function parseJsonObject(raw?: string): Record<string, unknown> {
  if (!raw) {
    return {};
  }
  try {
    return JSON.parse(raw) as Record<string, unknown>;
  } catch {
    return {};
  }
}

export function toPlanFormValues(plan: DataforgeFillApi.Plan): PlanFormValues {
  const remind = parseJsonObject(plan.remindConfig);
  const period = parseJsonObject(plan.periodConfig);
  const orgs =
    (plan.orgs ?? []).map((org) => ({
      assigneeUserIds: (org.assignees ?? []).map((item) => item.userId),
      reportingOrgId: org.reportingOrgId,
    })) ?? [];
  return {
    ...getDefaultPlanFormValues(),
    categoryId:
      plan.categoryId === undefined || plan.categoryId === null
        ? undefined
        : String(plan.categoryId),
    deadlineOffsetDays:
      typeof period.deadlineOffsetDays === 'number'
        ? period.deadlineOffsetDays
        : undefined,
    deadlineTime: toHm(
      typeof period.deadlineTime === 'string' ? period.deadlineTime : undefined,
    ),
    description: plan.description ?? '',
    generateTime: toHm(
      typeof period.generateTime === 'string' ? period.generateTime : undefined,
    ),
    id: plan.id,
    lockCore: plan.status === 1,
    monthGenerateDay:
      typeof period.monthGenerateDay === 'number'
        ? period.monthGenerateDay
        : undefined,
    name: plan.name,
    onceDeadlineTime: toIsoDateTime(
      typeof period.onceDeadlineTime === 'string'
        ? period.onceDeadlineTime
        : undefined,
    ),
    onceOpenTime: toIsoDateTime(
      typeof period.onceOpenTime === 'string' ? period.onceOpenTime : undefined,
    ),
    orgs: orgs.length > 0 ? orgs : [{ assigneeUserIds: [] }],
    overduePolicy: plan.overduePolicy ?? 1,
    periodOffset:
      typeof period.periodOffset === 'number' ? period.periodOffset : undefined,
    periodType: plan.periodType,
    prefillLastPeriod: plan.prefillLastPeriod === 1,
    processDefinitionKey: plan.processDefinitionKey,
    quarterGenerateDay:
      typeof period.quarterGenerateDay === 'number'
        ? period.quarterGenerateDay
        : undefined,
    remindHoursBeforeDeadline:
      typeof remind.hoursBeforeDeadline === 'number'
        ? remind.hoursBeforeDeadline
        : undefined,
    remindUrgeCooldownMinutes:
      typeof remind.urgeCooldownMinutes === 'number'
        ? remind.urgeCooldownMinutes
        : undefined,
    tableId: plan.tableId,
    tableVersionId: plan.tableVersionId,
    weekGenerateDay:
      typeof period.weekGenerateDay === 'number'
        ? period.weekGenerateDay
        : undefined,
  };
}

export function toPlanSaveReq(
  values: PlanFormValues,
): DataforgeFillApi.PlanSaveReq {
  const periodType = values.periodType ?? 4;
  const period: Record<string, unknown> = {};
  if (periodType === 1) {
    if (values.onceOpenTime) period.onceOpenTime = values.onceOpenTime;
    if (values.onceDeadlineTime)
      period.onceDeadlineTime = values.onceDeadlineTime;
  } else {
    if (values.periodOffset !== undefined)
      period.periodOffset = values.periodOffset;
    if (values.generateTime) period.generateTime = values.generateTime;
    if (periodType === 3 && values.weekGenerateDay !== undefined) {
      period.weekGenerateDay = values.weekGenerateDay;
    }
    if (periodType === 4 && values.monthGenerateDay !== undefined) {
      period.monthGenerateDay = values.monthGenerateDay;
    }
    if (periodType === 5 && values.quarterGenerateDay !== undefined) {
      period.quarterGenerateDay = values.quarterGenerateDay;
    }
    if (values.deadlineOffsetDays !== undefined) {
      period.deadlineOffsetDays = values.deadlineOffsetDays;
    }
    if (values.deadlineTime) period.deadlineTime = values.deadlineTime;
  }
  const remind: Record<string, unknown> = {};
  if (values.remindHoursBeforeDeadline !== undefined) {
    remind.hoursBeforeDeadline = values.remindHoursBeforeDeadline;
  }
  if (values.remindUrgeCooldownMinutes !== undefined) {
    remind.urgeCooldownMinutes = values.remindUrgeCooldownMinutes;
  }
  const orgs = (values.orgs ?? [])
    .filter((row) => row.reportingOrgId)
    .map((row, index) => ({
      assigneeUserIds: row.assigneeUserIds,
      reportingOrgId: row.reportingOrgId!,
      sort: index,
    }));
  return {
    categoryId: values.categoryId ?? null,
    description: values.description?.trim() || undefined,
    id: values.id,
    name: values.name?.trim() ?? '',
    orgs,
    overduePolicy: values.overduePolicy,
    periodConfig:
      Object.keys(period).length > 0 ? JSON.stringify(period) : undefined,
    periodType,
    prefillLastPeriod: values.prefillLastPeriod ? 1 : 0,
    processDefinitionKey: values.processDefinitionKey?.trim() ?? '',
    remindConfig:
      Object.keys(remind).length > 0 ? JSON.stringify(remind) : undefined,
    tableId: values.tableId!,
    tableVersionId: values.tableVersionId!,
  };
}

export function validatePlanOrgs(values: PlanFormValues): string | undefined {
  const validOrgs = (values.orgs ?? []).filter((row) => row.reportingOrgId);
  if (validOrgs.length === 0) {
    return '请至少选择一个目标组织';
  }
  const orgIds = new Set<string>();
  for (const row of validOrgs) {
    const key = String(row.reportingOrgId);
    if (orgIds.has(key)) {
      return '目标组织存在重复';
    }
    orgIds.add(key);
    if (row.assigneeUserIds.length === 0) {
      return '每个目标组织至少指定一名填报人';
    }
  }
  return undefined;
}

let versionRequestSeq = 0;

async function loadPublishedVersions(tableId?: number | string) {
  const seq = ++versionRequestSeq;
  if (!tableId) {
    return [];
  }
  const versions = await getTableVersionListByTable(tableId as number);
  if (seq !== versionRequestSeq) {
    return [];
  }
  return versions
    .filter((version) => version.status === 1)
    .map((version) => ({
      label: `V${version.versionNo}（${version.mergeStrategy ?? ''} · ${version.releaseTime ?? ''}）`,
      value: version.id,
    }));
}

/** 新增/修改采集计划 */
export function usePlanFormSchema(): VbenFormSchema[] {
  return [
    createHiddenIdField(),
    createHiddenIdField('lockCore'),
    createSectionDivider('basicDivider', '基本信息'),
    {
      fieldName: 'name',
      label: '计划名称',
      component: 'Input',
      rules: 'required',
      componentProps: {
        maxlength: 100,
        placeholder: '如：民生项目月报',
      },
    },
    {
      fieldName: 'categoryId',
      label: '计划分类',
      component: 'ApiTreeSelect',
      componentProps: {
        api: async () => {
          const categories = await getPlanCategoryList();
          return handleTree(
            categories.map((category) => ({
              ...category,
              id: String(category.id),
              parentId: String(category.parentId),
            })),
          );
        },
        allowClear: true,
        childrenField: 'children',
        labelField: 'name',
        placeholder: '未分类（可选择分类）',
        showSearch: true,
        treeDefaultExpandAll: true,
        treeNodeFilterProp: 'name',
        valueField: 'id',
      },
    },
    {
      fieldName: 'processDefinitionKey',
      label: 'BPM 流程定义 Key',
      component: 'Input',
      rules: 'required',
      componentProps: { placeholder: 'dataforge_fill_approval' },
    },
    {
      fieldName: 'description',
      label: '业务说明',
      component: 'TextArea',
      formItemClass: 'col-span-2',
      componentProps: { rows: 2, placeholder: '用途、口径说明' },
    },
    {
      fieldName: 'tableId',
      label: '填报表（模板）',
      component: 'ApiSelect',
      rules: 'selectRequired',
      componentProps: {
        api: async () => {
          const page = await getFillTablePage({ pageNo: 1, pageSize: 100 });
          return (page.list ?? []).map((table) => ({
            label: `${table.tableCnName}（${table.tableEngName}）`,
            value: table.id,
          }));
        },
        labelField: 'label',
        optionFilterProp: 'label',
        placeholder: '选择填报表',
        showSearch: true,
        valueField: 'value',
      },
      dependencies: {
        triggerFields: ['lockCore'],
        resolve: ({ values }) => ({ disabled: !!values.lockCore }),
      },
    },
    {
      fieldName: 'tableVersionId',
      label: '模板版本',
      component: 'Select',
      rules: 'selectRequired',
      dependencies: {
        triggerFields: ['tableId', 'lockCore'],
        resolve: async ({ values }) => ({
          disabled: !!values.lockCore,
          componentProps: {
            options: await loadPublishedVersions(values.tableId),
            placeholder: '选择已发布版本',
          },
        }),
      },
    },
    {
      fieldName: 'periodType',
      label: '周期类型',
      component: 'Select',
      rules: 'selectRequired',
      componentProps: { options: PERIOD_TYPE_OPTIONS },
      dependencies: {
        triggerFields: ['lockCore'],
        resolve: ({ values }) => ({ disabled: !!values.lockCore }),
      },
    },
    {
      fieldName: 'overduePolicy',
      label: '逾期策略',
      component: 'RadioGroup',
      defaultValue: 1,
      componentProps: {
        options: [
          { label: '软逾期（可继续填报，仅标识）', value: 1 },
          { label: '硬截止（截止后禁止编辑）', value: 2 },
        ],
      },
    },
    {
      fieldName: 'onceOpenTime',
      label: '开放时间',
      component: 'DatePicker',
      help: '缺省为发布时刻',
      componentProps: {
        class: 'w-full',
        format: 'YYYY-MM-DD HH:mm:ss',
        showTime: true,
        valueFormat: 'YYYY-MM-DDTHH:mm:ss',
      },
      dependencies: {
        triggerFields: ['periodType'],
        resolve: ({ values }) => ({ show: values.periodType === 1 }),
      },
    },
    {
      fieldName: 'onceDeadlineTime',
      label: '截止时间',
      component: 'DatePicker',
      help: '缺省为发布后 7 天 17:30',
      componentProps: {
        class: 'w-full',
        format: 'YYYY-MM-DD HH:mm:ss',
        showTime: true,
        valueFormat: 'YYYY-MM-DDTHH:mm:ss',
      },
      dependencies: {
        triggerFields: ['periodType'],
        resolve: ({ values }) => ({ show: values.periodType === 1 }),
      },
    },
    {
      fieldName: 'weekGenerateDay',
      label: '每周生成日',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: WEEK_DAY_OPTIONS,
        placeholder: '默认周一',
      },
      dependencies: {
        triggerFields: ['periodType'],
        resolve: ({ values }) => ({ show: values.periodType === 3 }),
      },
    },
    {
      fieldName: 'monthGenerateDay',
      label: '每月生成日',
      component: 'InputNumber',
      componentProps: {
        class: 'w-full',
        max: 28,
        min: 1,
        placeholder: '默认 1 号',
      },
      dependencies: {
        triggerFields: ['periodType'],
        resolve: ({ values }) => ({ show: values.periodType === 4 }),
      },
    },
    {
      fieldName: 'quarterGenerateDay',
      label: '季度首月生成日',
      component: 'InputNumber',
      componentProps: {
        class: 'w-full',
        max: 28,
        min: 1,
        placeholder: '默认 1 号',
      },
      dependencies: {
        triggerFields: ['periodType'],
        resolve: ({ values }) => ({ show: values.periodType === 5 }),
      },
    },
    {
      fieldName: 'generateTime',
      label: '生成时刻',
      component: 'TimePicker',
      componentProps: {
        class: 'w-full',
        format: 'HH:mm',
        placeholder: '默认 08:00',
        valueFormat: 'HH:mm',
      },
      dependencies: {
        triggerFields: ['periodType'],
        resolve: ({ values }) => ({ show: values.periodType !== 1 }),
      },
    },
    {
      fieldName: 'periodOffset',
      label: '所属期偏移',
      component: 'InputNumber',
      componentProps: {
        class: 'w-full',
        max: 0,
        min: -4,
        placeholder: '默认 -1（报上一期）',
      },
      dependencies: {
        triggerFields: ['periodType'],
        resolve: ({ values }) => ({ show: values.periodType !== 1 }),
      },
    },
    {
      fieldName: 'deadlineOffsetDays',
      label: '截止偏移天数',
      component: 'InputNumber',
      help: '截止 = 生成日 + N 天',
      componentProps: {
        class: 'w-full',
        max: 31,
        min: 0,
        placeholder: '默认 3（日报建议 0）',
      },
      dependencies: {
        triggerFields: ['periodType'],
        resolve: ({ values }) => ({ show: values.periodType !== 1 }),
      },
    },
    {
      fieldName: 'deadlineTime',
      label: '截止时刻',
      component: 'TimePicker',
      componentProps: {
        class: 'w-full',
        format: 'HH:mm',
        placeholder: '默认 17:30',
        valueFormat: 'HH:mm',
      },
      dependencies: {
        triggerFields: ['periodType'],
        resolve: ({ values }) => ({ show: values.periodType !== 1 }),
      },
    },
    {
      fieldName: 'prefillLastPeriod',
      label: '上期数据带入',
      component: 'Switch',
      dependencies: {
        triggerFields: ['periodType'],
        resolve: ({ values }) => ({ show: values.periodType !== 1 }),
      },
    },
    {
      fieldName: 'remindHoursBeforeDeadline',
      label: '截止前提醒（小时）',
      component: 'InputNumber',
      componentProps: {
        class: 'w-full',
        max: 168,
        min: 1,
        placeholder: '如 24，写入 remindConfig',
      },
    },
    {
      fieldName: 'remindUrgeCooldownMinutes',
      label: '催办频控（分钟）',
      component: 'InputNumber',
      componentProps: {
        class: 'w-full',
        max: 1440,
        min: 1,
        placeholder: '如 60，写入 remindConfig',
      },
    },
    {
      fieldName: 'orgs',
      label: '目标组织与填报人',
      component: markRaw(OrgAssigneeList),
      formItemClass: 'col-span-2',
      rules: z.array(z.any()).min(1, { message: '请至少选择一个目标组织' }),
    },
  ];
}

/** 采集计划列表的搜索表单 */
export function usePlanGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'name',
      label: '计划名称',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '请输入计划名称' },
    },
    {
      fieldName: 'status',
      label: '状态',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: PLAN_STATUS_OPTIONS,
        placeholder: '请选择状态',
      },
    },
    {
      fieldName: 'periodType',
      label: '周期',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: PERIOD_TYPE_OPTIONS,
        placeholder: '请选择周期',
      },
    },
  ];
}

/** 采集计划列表字段 */
export function usePlanGridColumns(): VxeTableGridOptions<DataforgeFillApi.Plan>['columns'] {
  return [
    { field: 'id', title: '编号', width: 80 },
    { field: 'name', title: '计划名称', minWidth: 180, align: 'left' },
    {
      field: 'tableName',
      title: '模板/版本',
      width: 220,
      align: 'left',
      slots: { default: 'template' },
    },
    {
      field: 'periodType',
      title: '周期',
      width: 90,
      slots: { default: 'periodType' },
    },
    {
      field: 'status',
      title: '状态',
      width: 90,
      slots: { default: 'status' },
    },
    {
      field: 'lastPeriodKey',
      title: '最近期次',
      width: 110,
      formatter: ({ cellValue }) => cellValue || '-',
    },
    {
      field: 'nextGenerateTime',
      title: '下次生成',
      width: 160,
      formatter: 'formatDateTime',
    },
    {
      field: 'creator',
      title: '发起者',
      width: 100,
      formatter: ({ cellValue }) => cellValue || '-',
    },
    {
      title: '操作',
      width: 280,
      fixed: 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ];
}

// ==================== 采集任务 ====================

/** 采集任务列表的搜索表单 */
export function useTaskGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'name',
      label: '任务名称',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '请输入任务名称' },
    },
    {
      fieldName: 'periodKey',
      label: '所属期',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '如 2026-08' },
    },
    {
      fieldName: 'status',
      label: '状态',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: FILL_TASK_STATUS_OPTIONS,
        placeholder: '请选择状态',
      },
    },
  ];
}

/** 采集任务列表字段 */
export function useTaskGridColumns(): VxeTableGridOptions<DataforgeFillApi.Task>['columns'] {
  return [
    { field: 'id', title: '编号', width: 80 },
    { field: 'name', title: '任务名称', minWidth: 180, align: 'left' },
    {
      field: 'periodKey',
      title: '所属期',
      width: 110,
      formatter: ({ cellValue }) => cellValue || '-',
    },
    {
      field: 'status',
      title: '状态',
      width: 90,
      slots: { default: 'status' },
    },
    {
      field: 'mergedOrgCount',
      title: '合并进度',
      width: 180,
      showOverflow: false,
      slots: { default: 'progress' },
    },
    {
      field: 'openTime',
      title: '开放时间',
      width: 165,
      formatter: 'formatDateTime',
    },
    {
      field: 'deadlineTime',
      title: '截止时间',
      width: 165,
      formatter: 'formatDateTime',
    },
    {
      title: '操作',
      width: 110,
      fixed: 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ];
}

// ==================== 合并记录 ====================

/** 合并记录列表的搜索表单 */
export function useMergeGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'targetTableName',
      label: '目标表',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '请输入目标表名' },
    },
    {
      fieldName: 'status',
      label: '状态',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: MERGE_STATUS_OPTIONS,
        placeholder: '请选择状态',
      },
    },
  ];
}

/** 合并记录列表字段 */
export function useMergeGridColumns(): VxeTableGridOptions<DataforgeFillApi.MergeBatch>['columns'] {
  return [
    { field: 'id', title: '编号', width: 80 },
    { field: 'submissionId', title: '提交快照', width: 90 },
    {
      field: 'targetTableName',
      title: '目标表',
      width: 180,
      align: 'left',
      formatter: ({ cellValue }) => cellValue || '-',
    },
    {
      field: 'mergeStrategy',
      title: '策略',
      width: 130,
      formatter: ({ cellValue }) => cellValue || '-',
    },
    {
      field: 'status',
      title: '状态',
      width: 90,
      slots: { default: 'status' },
    },
    {
      field: 'successCount',
      title: '写入/应写',
      width: 100,
      slots: { default: 'count' },
    },
    { field: 'retryCount', title: '重试', width: 70 },
    {
      field: 'errorMessage',
      title: '失败原因',
      minWidth: 200,
      align: 'left',
      slots: { default: 'errorMessage' },
    },
    {
      field: 'finishedAt',
      title: '完成时间',
      width: 165,
      formatter: 'formatDateTime',
    },
    {
      title: '操作',
      width: 150,
      fixed: 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ];
}
