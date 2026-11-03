<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { QualityAlertApi } from '#/api/governance/quality/alert';

import { onMounted, reactive } from 'vue';

import { Page } from '@vben/common-ui';

import { Card, Col, Row, Statistic, Tag } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  deleteAlert,
  getAlertPage,
  getAlertSummary,
  processAlert,
} from '#/api/governance/quality/alert';
import { $t } from '#/locales';

defineOptions({ name: 'GovernanceQualityAlert' });

const LEVEL_LABELS: Record<string, [string, string]> = {
  DANGER: ['危险', 'red'],
  SERIOUS: ['严重', 'orange'],
  GENERAL: ['一般', 'gold'],
};

const summary = reactive<QualityAlertApi.Summary>({});

async function loadSummary() {
  Object.assign(summary, await getAlertSummary());
}

function handleRefresh() {
  loadSummary();
  gridApi.query();
}

async function handleProcess(row: QualityAlertApi.Alert) {
  await processAlert(row.id!);
  handleRefresh();
}

async function handleDelete(row: QualityAlertApi.Alert) {
  await deleteAlert(row.id!);
  handleRefresh();
}

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: [
      {
        fieldName: 'alertTitle',
        label: '告警标题',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入告警标题' },
      },
      {
        fieldName: 'alertLevel',
        label: '告警等级',
        component: 'Select',
        componentProps: {
          allowClear: true,
          options: [
            { label: '危险', value: 'DANGER' },
            { label: '严重', value: 'SERIOUS' },
            { label: '一般', value: 'GENERAL' },
          ],
          placeholder: '请选择告警等级',
        },
      },
      {
        fieldName: 'alertStatus',
        label: '状态',
        component: 'Select',
        componentProps: {
          allowClear: true,
          options: [
            { label: '未处理', value: 'UNPROCESSED' },
            { label: '已处理', value: 'PROCESSED' },
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
      { field: 'alertTitle', title: '告警标题', minWidth: 200, align: 'left' },
      {
        field: 'taskType',
        title: '任务类型',
        width: 100,
        formatter: ({ cellValue }) => cellValue || '-',
      },
      {
        field: 'taskName',
        title: '任务名称',
        width: 150,
        formatter: ({ cellValue }) => cellValue || '-',
      },
      {
        field: 'alertLevel',
        title: '告警等级',
        width: 90,
        slots: { default: 'alertLevel' },
      },
      { field: 'issueCount', title: '问题记录数', width: 100 },
      {
        field: 'ruleName',
        title: '规则名称',
        minWidth: 160,
        formatter: ({ cellValue }) => cellValue || '-',
      },
      {
        field: 'alertStatus',
        title: '状态',
        width: 90,
        slots: { default: 'alertStatus' },
      },
      {
        field: 'alertTime',
        title: '告警时间',
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
          return await getAlertPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          });
        },
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<QualityAlertApi.Alert>,
});

onMounted(loadSummary);
</script>

<template>
  <Page auto-content-height>
    <div class="flex h-full min-h-0 flex-col gap-3">
      <Row :gutter="12">
        <Col :span="5">
          <Card size="small">
            <Statistic
              :value="summary.dangerCount ?? 0"
              :value-style="{ color: '#f5222d' }"
              title="危险"
            />
          </Card>
        </Col>
        <Col :span="5">
          <Card size="small">
            <Statistic
              :value="summary.seriousCount ?? 0"
              :value-style="{ color: '#fa8c16' }"
              title="严重"
            />
          </Card>
        </Col>
        <Col :span="5">
          <Card size="small">
            <Statistic
              :value="summary.generalCount ?? 0"
              :value-style="{ color: '#faad14' }"
              title="一般"
            />
          </Card>
        </Col>
        <Col :span="5">
          <Card size="small">
            <Statistic :value="summary.unprocessedCount ?? 0" title="未处理" />
          </Card>
        </Col>
        <Col :span="4">
          <Card size="small">
            <Statistic
              :value="summary.processedCount ?? 0"
              :value-style="{ color: '#52c41a' }"
              title="已处理"
            />
          </Card>
        </Col>
      </Row>

      <div class="min-h-0 flex-1 overflow-hidden">
        <Grid table-title="告警记录">
          <template #alertLevel="{ row }">
            <Tag :color="LEVEL_LABELS[row.alertLevel ?? '']?.[1]">
              {{ LEVEL_LABELS[row.alertLevel ?? '']?.[0] ?? '-' }}
            </Tag>
          </template>
          <template #alertStatus="{ row }">
            <Tag
              :color="row.alertStatus === 'PROCESSED' ? 'success' : 'warning'"
            >
              {{ row.alertStatus === 'PROCESSED' ? '已处理' : '未处理' }}
            </Tag>
          </template>
          <template #actions="{ row }">
            <TableAction
              :actions="[
                {
                  label: '处理',
                  type: 'link',
                  icon: 'lucide:wrench',
                  auth: ['governance:quality-alert:update'],
                  ifShow: row.alertStatus !== 'PROCESSED',
                  popConfirm: {
                    title: `确认处理告警「${row.alertTitle}」吗？`,
                    confirm: handleProcess.bind(null, row),
                  },
                },
                {
                  label: $t('common.delete'),
                  type: 'link',
                  danger: true,
                  icon: ACTION_ICON.DELETE,
                  auth: ['governance:quality-alert:delete'],
                  popConfirm: {
                    title: `确认删除告警「${row.alertTitle}」吗？`,
                    confirm: handleDelete.bind(null, row),
                  },
                },
              ]"
            />
          </template>
        </Grid>
      </div>
    </div>
  </Page>
</template>
