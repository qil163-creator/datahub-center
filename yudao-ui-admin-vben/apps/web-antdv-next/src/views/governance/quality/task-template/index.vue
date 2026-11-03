<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { QualityTaskTemplateApi } from '#/api/governance/quality/template';

import { ref } from 'vue';

import { Page } from '@vben/common-ui';

import { Alert, message, Switch, Tag } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  deleteTaskTemplate,
  getTaskTemplatePage,
  materializeTaskTemplate,
  updateTaskTemplateStatus,
} from '#/api/governance/quality/template';
import { $t } from '#/locales';

import TemplateForm from './modules/form.vue';

defineOptions({ name: 'GovernanceQualityTaskTemplate' });

const SCOPE_LABELS: Record<string, string> = {
  DATASOURCE: '整个数据源',
  TABLE_MATCH: '表名匹配',
  SPECIFY_TABLE: '指定表',
  LAYER: '按分层',
};

const formRef = ref<InstanceType<typeof TemplateForm>>();

function handleRefresh() {
  gridApi.query();
}

async function handleMaterialize(row: QualityTaskTemplateApi.Template) {
  const count = await materializeTaskTemplate(row.id!);
  message.success(`同步完成，本次新生成 ${count} 个任务`);
  handleRefresh();
}

async function handleStatusChange(row: QualityTaskTemplateApi.Template) {
  const newStatus = row.status === 0 ? 1 : 0;
  try {
    await updateTaskTemplateStatus(row.id!, newStatus);
    message.success(newStatus === 0 ? '启用成功' : '停用成功');
  } finally {
    handleRefresh();
  }
}

async function handleDelete(row: QualityTaskTemplateApi.Template) {
  await deleteTaskTemplate(row.id!);
  handleRefresh();
}

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: [
      {
        fieldName: 'templateName',
        label: '模板名称',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入模板名称' },
      },
    ],
  },
  gridOptions: {
    showOverflow: false,
    columns: [
      { field: 'id', title: '编号', width: 70 },
      {
        field: 'templateName',
        title: '模板名称',
        minWidth: 170,
        align: 'left',
      },
      { field: 'datasourceConnId', title: '数据源', width: 90 },
      {
        field: 'tableScopeType',
        title: '表范围',
        width: 110,
        formatter: ({ cellValue }) => SCOPE_LABELS[cellValue] ?? '-',
      },
      {
        field: 'ruleSourceType',
        title: '规则来源',
        width: 100,
        formatter: ({ cellValue }) =>
          cellValue === 'SPECIFY_RULE' ? '指定规则' : '规则集',
      },
      {
        field: 'scheduleType',
        title: '调度',
        width: 140,
        slots: { default: 'schedule' },
      },
      { field: 'generatedTaskCount', title: '已生成任务', width: 95 },
      {
        field: 'status',
        title: '状态',
        width: 90,
        slots: { default: 'status' },
      },
      {
        field: 'createTime',
        title: '创建时间',
        width: 165,
        formatter: 'formatDateTime',
      },
      {
        title: '操作',
        width: 210,
        fixed: 'right',
        showOverflow: false,
        slots: { default: 'actions' },
      },
    ],
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          return await getTaskTemplatePage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          });
        },
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<QualityTaskTemplateApi.Template>,
});
</script>

<template>
  <Page auto-content-height>
    <div class="flex h-full min-h-0 flex-col gap-3">
      <Alert
        message="海量表逐条建任务不现实：模板把「规则集 + 表范围 + 字段选择策略」写成一条配置，同步后物化成实体任务；生成的任务本身是手动调度，避免海量任务各自注册定时器并发打满引擎。"
        show-icon
        type="info"
      />
      <div class="min-h-0 flex-1 overflow-hidden">
        <Grid table-title="任务模板">
          <template #toolbar-tools>
            <TableAction
              :actions="[
                {
                  label: $t('common.create'),
                  type: 'primary',
                  icon: ACTION_ICON.ADD,
                  auth: ['governance:quality-task-template:create'],
                  onClick: () => formRef?.open(),
                },
              ]"
            />
          </template>
          <template #schedule="{ row }">
            <Tag v-if="row.scheduleType === 'TIMER'" color="blue">
              定时 {{ row.cronExpression }}
            </Tag>
            <Tag v-else>手动</Tag>
          </template>
          <template #status="{ row }">
            <Switch
              :checked="row.status === 0"
              checked-children="启用"
              un-checked-children="停用"
              @change="handleStatusChange(row)"
            />
          </template>
          <template #actions="{ row }">
            <TableAction
              :actions="[
                {
                  label: $t('common.edit'),
                  type: 'link',
                  icon: ACTION_ICON.EDIT,
                  auth: ['governance:quality-task-template:update'],
                  onClick: () => formRef?.open(row.id),
                },
              ]"
              :drop-down-actions="[
                {
                  label: '同步物化',
                  type: 'link',
                  icon: 'lucide:play',
                  auth: ['governance:quality-task-template:update'],
                  popConfirm: {
                    title: `将按模板「${row.templateName}」的表范围批量生成检测任务，确认执行？`,
                    confirm: handleMaterialize.bind(null, row),
                  },
                },
                {
                  label: $t('common.delete'),
                  type: 'link',
                  danger: true,
                  icon: ACTION_ICON.DELETE,
                  auth: ['governance:quality-task-template:delete'],
                  popConfirm: {
                    title: `确认删除任务模板「${row.templateName}」吗？`,
                    confirm: handleDelete.bind(null, row),
                  },
                },
              ]"
            />
          </template>
        </Grid>
      </div>
    </div>
    <TemplateForm ref="formRef" @saved="handleRefresh" />
  </Page>
</template>
