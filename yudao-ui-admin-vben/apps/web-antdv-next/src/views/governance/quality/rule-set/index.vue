<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { QualityRuleSetApi } from '#/api/governance/quality/ruleset';

import { ref } from 'vue';

import { Page } from '@vben/common-ui';

import { message, Switch } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import {
  deleteRuleSet,
  getRuleSetPage,
  updateRuleSetStatus,
} from '#/api/governance/quality/ruleset';
import { $t } from '#/locales';

import RuleSetForm from './modules/form.vue';
import RuleSetItemDrawer from './modules/item-drawer.vue';

defineOptions({ name: 'GovernanceQualityRuleSet' });

const CYCLE_LABELS: Record<string, string> = {
  DAY: '日',
  WEEK: '周',
  MONTH: '月',
};

const formRef = ref<InstanceType<typeof RuleSetForm>>();
const itemDrawerRef = ref<InstanceType<typeof RuleSetItemDrawer>>();

function handleRefresh() {
  gridApi.query();
}

async function handleStatusChange(row: QualityRuleSetApi.RuleSet) {
  const newStatus = row.status === 0 ? 1 : 0;
  try {
    await updateRuleSetStatus(row.id!, newStatus);
    message.success(newStatus === 0 ? '启用成功' : '停用成功');
  } finally {
    handleRefresh();
  }
}

async function handleDelete(row: QualityRuleSetApi.RuleSet) {
  await deleteRuleSet(row.id!);
  handleRefresh();
}

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: [
      {
        fieldName: 'setName',
        label: '规则集名称',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入规则集名称' },
      },
      {
        fieldName: 'category',
        label: '分类',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入分类' },
      },
      {
        fieldName: 'status',
        label: '状态',
        component: 'Select',
        componentProps: {
          allowClear: true,
          options: [
            { label: '启用', value: 0 },
            { label: '停用', value: 1 },
          ],
          placeholder: '请选择状态',
        },
      },
    ],
  },
  gridOptions: {
    showOverflow: false,
    columns: [
      { field: 'id', title: '编号', width: 70 },
      { field: 'setName', title: '规则集名称', minWidth: 170, align: 'left' },
      { field: 'setCode', title: '规则集编码', width: 140 },
      {
        field: 'category',
        title: '分类',
        width: 130,
        formatter: ({ cellValue }) => cellValue || '-',
      },
      {
        field: 'assessDim',
        title: '评估维度',
        width: 100,
        formatter: ({ cellValue }) => cellValue || '-',
      },
      {
        field: 'cycle',
        title: '评估周期',
        width: 90,
        formatter: ({ cellValue }) => CYCLE_LABELS[cellValue] ?? '-',
      },
      { field: 'ruleCount', title: '规则数量', width: 90 },
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
        width: 180,
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
          return await getRuleSetPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          });
        },
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<QualityRuleSetApi.RuleSet>,
});
</script>

<template>
  <Page auto-content-height>
    <Grid table-title="规则集">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: $t('common.create'),
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: ['governance:quality-rule-set:create'],
              onClick: () => formRef?.open(),
            },
          ]"
        />
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
              auth: ['governance:quality-rule-set:update'],
              onClick: () => formRef?.open(row.id),
            },
          ]"
          :drop-down-actions="[
            {
              label: '规则管理',
              type: 'link',
              icon: 'lucide:list',
              auth: ['governance:quality-rule-set:update'],
              onClick: () => itemDrawerRef?.open(row),
            },
            {
              label: $t('common.delete'),
              type: 'link',
              danger: true,
              icon: ACTION_ICON.DELETE,
              auth: ['governance:quality-rule-set:delete'],
              popConfirm: {
                title: `确认删除规则集「${row.setName}」吗？`,
                confirm: handleDelete.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </Grid>
    <RuleSetForm ref="formRef" @saved="handleRefresh" />
    <RuleSetItemDrawer ref="itemDrawerRef" @saved="handleRefresh" />
  </Page>
</template>
