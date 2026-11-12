<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { QualityRuleApi } from '#/api/governance/quality/rule';

import { nextTick, onActivated, onMounted, ref } from 'vue';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';

import { message, Switch, Tag } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import {
  deleteQualityRule,
  getQualityRulePage,
  updateQualityRuleStatus,
} from '#/api/governance/quality/rule';
import { $t } from '#/locales';

import RuleForm from './modules/form.vue';
import PreviewModal from './modules/preview.vue';
import { takeRulePrefill } from './prefill';

defineOptions({ name: 'GovernanceQualityRule' });

const DIM_OPTIONS = [
  { label: '完整性', value: 'COMPLETENESS' },
  { label: '唯一性', value: 'UNIQUENESS' },
  { label: '规范性', value: 'NORMALIZATION' },
  { label: '准确性', value: 'ACCURACY' },
  { label: '及时性', value: 'TIMELINESS' },
  { label: '一致性', value: 'CONSISTENCY' },
];
const DIM_COLORS: Record<string, string> = {
  COMPLETENESS: 'green',
  UNIQUENESS: 'geekblue',
  NORMALIZATION: 'purple',
  ACCURACY: 'cyan',
  TIMELINESS: 'orange',
  CONSISTENCY: 'magenta',
};
const SEVERITY_OPTIONS = [
  { label: '严重', value: 'CRITICAL' },
  { label: '高', value: 'HIGH' },
  { label: '中', value: 'MID' },
  { label: '低', value: 'LOW' },
];
const SEVERITY_COLORS: Record<string, string> = {
  CRITICAL: 'red',
  HIGH: 'orange',
  MID: 'gold',
  LOW: 'default',
};
const RULE_TYPE_LABELS: Record<string, string> = {
  NOT_NULL: '非空校验',
  UNIQUE: '唯一校验',
  RANGE: '范围校验',
  REGEX: '正则格式校验',
  ENUM: '枚举值校验',
  LENGTH: '长度校验',
  PRECISION: '数值精度校验',
  DATE_FORMAT: '日期格式校验',
  TIME_ORDER: '时间顺序校验',
  GROUP_COMPLETENESS: '字段组完整性',
  COMPOSITE_UNIQUE: '组合唯一校验',
  CROSS_TABLE: '跨表一致性',
  FOREIGN_KEY: '外键校验',
  TIMELINESS: '及时性校验',
  PLACEHOLDER: '占位值识别',
  CUSTOM: '自定义SQL',
};

const ruleFormRef = ref<InstanceType<typeof RuleForm>>();
const previewModalRef = ref<InstanceType<typeof PreviewModal>>();

function handleRefresh() {
  gridApi.query();
}

async function handleStatusChange(row: QualityRuleApi.Rule) {
  const newStatus = row.status === 0 ? 1 : 0;
  try {
    await updateQualityRuleStatus(row.id!, newStatus);
    message.success(newStatus === 0 ? '启用成功' : '停用成功');
  } finally {
    handleRefresh();
  }
}

async function handleDelete(row: QualityRuleApi.Rule) {
  await deleteQualityRule(row.id!);
  handleRefresh();
}

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: [
      {
        fieldName: 'ruleName',
        label: '规则名称',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入规则名称' },
      },
      {
        fieldName: 'qualityDim',
        label: '质量维度',
        component: 'Select',
        componentProps: {
          allowClear: true,
          options: DIM_OPTIONS,
          placeholder: '请选择质量维度',
        },
      },
      {
        fieldName: 'severity',
        label: '严重程度',
        component: 'Select',
        componentProps: {
          allowClear: true,
          options: SEVERITY_OPTIONS,
          placeholder: '请选择严重程度',
        },
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
      { field: 'ruleName', title: '规则名称', minWidth: 170, align: 'left' },
      { field: 'ruleCode', title: '规则编码', width: 150 },
      {
        field: 'standardElementId',
        title: '来源',
        width: 90,
        slots: { default: 'origin' },
      },
      {
        field: 'qualityDim',
        title: '质量维度',
        width: 90,
        slots: { default: 'qualityDim' },
      },
      {
        field: 'ruleType',
        title: '规则类型',
        width: 130,
        formatter: ({ cellValue }) =>
          RULE_TYPE_LABELS[cellValue] ?? cellValue ?? '-',
      },
      {
        field: 'severity',
        title: '严重程度',
        width: 90,
        slots: { default: 'severity' },
      },
      {
        field: 'applyLayer',
        title: '适用分层',
        width: 100,
        formatter: ({ cellValue }) => cellValue || '-',
      },
      {
        field: 'strategyKey',
        title: '引擎策略',
        width: 110,
        slots: { default: 'strategy' },
      },
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
        width: 200,
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
          return await getQualityRulePage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          });
        },
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<QualityRuleApi.Rule>,
});

const { hasAccessByCodes } = useAccess();
/** 探查推荐“带入质量规则”：页面打开或从缓存激活时取走预填并打开新增表单。 */
function consumePrefill() {
  const prefill = takeRulePrefill();
  if (prefill && hasAccessByCodes(['governance:quality-rule:create'])) {
    void nextTick(() => ruleFormRef.value?.open(undefined, prefill));
  }
}
onMounted(consumePrefill);
onActivated(consumePrefill);
</script>

<template>
  <Page auto-content-height>
    <Grid table-title="质量规则">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: $t('common.create'),
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: ['governance:quality-rule:create'],
              onClick: () => ruleFormRef?.open(),
            },
          ]"
        />
      </template>
      <template #qualityDim="{ row }">
        <Tag :color="DIM_COLORS[row.qualityDim ?? ''] ?? 'default'">
          {{
            DIM_OPTIONS.find((item) => item.value === row.qualityDim)?.label ??
            '-'
          }}
        </Tag>
      </template>
      <template #severity="{ row }">
        <Tag :color="SEVERITY_COLORS[row.severity ?? ''] ?? 'default'">
          {{
            SEVERITY_OPTIONS.find((item) => item.value === row.severity)
              ?.label ?? '-'
          }}
        </Tag>
      </template>
      <template #origin="{ row }">
        <Tag :color="row.standardElementId ? 'blue' : 'default'">
          {{ row.standardElementId ? '标准物化' : '手工' }}
        </Tag>
      </template>
      <template #strategy="{ row }">
        <Tag :color="row.strategyKey ? 'success' : 'default'">
          {{ row.strategyKey ? '已接入引擎' : '未接入' }}
        </Tag>
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
              auth: ['governance:quality-rule:update'],
              onClick: () => ruleFormRef?.open(row.id),
            },
          ]"
          :drop-down-actions="[
            {
              label: 'SQL预览',
              type: 'link',
              icon: 'lucide:code',
              disabled: !row.strategyKey,
              onClick: () => previewModalRef?.open(row),
            },
            {
              label: $t('common.delete'),
              type: 'link',
              danger: true,
              icon: ACTION_ICON.DELETE,
              disabled: row.builtin === 1 || row.standardElementId != null,
              auth: ['governance:quality-rule:delete'],
              popConfirm: {
                title: `确认删除质量规则「${row.ruleName}」吗？`,
                confirm: handleDelete.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </Grid>
    <RuleForm ref="ruleFormRef" @saved="handleRefresh" />
    <PreviewModal ref="previewModalRef" />
  </Page>
</template>
