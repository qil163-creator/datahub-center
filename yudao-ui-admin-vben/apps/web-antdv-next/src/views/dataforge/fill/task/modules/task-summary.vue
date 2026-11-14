<script lang="ts" setup>
import type { DataforgeFillApi } from '#/api/dataforge/fill';

import { computed, ref } from 'vue';

import { IconifyIcon } from '@vben/icons';
import { downloadFileFromBlobPart, formatDateTime } from '@vben/utils';

import { Button, Modal, Space, Table, Tag } from 'antdv-next';

import { exportTaskData, exportTaskUnreported, getTaskSummary } from '#/api/dataforge/fill';

import { WORK_ITEM_STATUS_TEXT } from '../../shared';

defineOptions({ name: 'DataforgeFillTaskSummary' });

const open = ref(false);
const loading = ref(false);
const exporting = ref<'' | 'data' | 'unreported'>('');
const summary = ref<DataforgeFillApi.TaskSummary>();
const taskName = ref('');
let currentTaskId: number | string | undefined;
let generation = 0;

const columns = computed(() => [
  { dataIndex: 'orgName', key: 'orgName', title: '报送组织', width: 180, ellipsis: true, fixed: 'left' as const },
  { key: 'status', title: '状态', width: 130 },
  { key: 'rowCount', title: '审批通过行数', width: 110 },
  { key: 'submitTime', title: '提交时间', width: 160 },
  ...(summary.value?.fields ?? []).map((field) => ({
    align: 'right' as const,
    key: `total:${field.fieldCode}`,
    title: `${field.name}合计`,
    width: 140,
  })),
]);

/** 打开任务汇总；只采用最后一次打开的结果 */
async function openSummary(task: { id: number | string; name?: string }) {
  const current = ++generation;
  currentTaskId = task.id;
  taskName.value = task.name ?? '';
  summary.value = undefined;
  open.value = true;
  loading.value = true;
  try {
    const result = await getTaskSummary(task.id);
    if (current === generation) summary.value = result;
  } finally {
    if (current === generation) loading.value = false;
  }
}

async function handleExport(kind: 'data' | 'unreported') {
  if (!currentTaskId || exporting.value) return;
  exporting.value = kind;
  try {
    const blob =
      kind === 'data' ? await exportTaskData(currentTaskId) : await exportTaskUnreported(currentTaskId);
    downloadFileFromBlobPart({
      fileName: `${taskName.value || '填报任务'}-${kind === 'data' ? '汇总数据' : '未报单位清单'}.xlsx`,
      source: blob,
    });
  } finally {
    exporting.value = '';
  }
}

function totalOf(record: DataforgeFillApi.TaskSummary['orgs'][number], key: unknown) {
  const code = String(key).slice('total:'.length);
  return record.approved ? (record.totals[code] ?? '0') : '-';
}

defineExpose({ handleExport, openSummary });
</script>

<template>
  <Modal
    v-model:open="open"
    :footer="null"
    :title="`结果汇总：${taskName}`"
    :width="1080"
  >
    <div class="flex flex-col gap-3 py-2">
      <div class="flex flex-wrap items-center justify-between gap-2">
        <Space wrap>
          <Tag color="success">审批通过 {{ summary?.approvedCount ?? 0 }} 个组织</Tag>
          <Tag :color="(summary?.unreportedCount ?? 0) > 0 ? 'warning' : 'default'">
            未报 {{ summary?.unreportedCount ?? 0 }} 个组织
          </Tag>
          <span class="text-muted-foreground text-xs">合计只统计审批通过的数据；中间单位含其下级汇入的数据，总计只按顶层计算</span>
        </Space>
        <Space>
          <Button
            v-access:code="'dataforge:fill-task:export'"
            :loading="exporting === 'unreported'"
            size="small"
            @click="handleExport('unreported')"
          >
            <template #icon><IconifyIcon icon="lucide:file-warning" /></template>
            导出未报单位
          </Button>
          <Button
            v-access:code="'dataforge:fill-task:export'"
            :loading="exporting === 'data'"
            size="small"
            type="primary"
            @click="handleExport('data')"
          >
            <template #icon><IconifyIcon icon="lucide:download" /></template>
            导出汇总数据
          </Button>
        </Space>
      </div>
      <Table
        :columns="columns"
        :data-source="summary?.orgs ?? []"
        :loading="loading"
        :pagination="false"
        :scroll="{ x: 'max-content', y: 480 }"
        row-key="workItemId"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'orgName'">
            <span :style="{ paddingLeft: `${(record.level ?? 0) * 16}px` }">
              {{ (record.level ?? 0) > 0 ? '└ ' : '' }}{{ record.orgName }}
            </span>
          </template>
          <template v-else-if="column.key === 'status'">
            <Tag :color="WORK_ITEM_STATUS_TEXT[record.status]?.color">
              {{ WORK_ITEM_STATUS_TEXT[record.status]?.text ?? record.status }}
            </Tag>
            <Tag v-if="record.overdue === 1" color="error">逾期</Tag>
          </template>
          <template v-else-if="column.key === 'rowCount'">
            {{ record.approved ? (record.rowCount ?? 0) : '-' }}
          </template>
          <template v-else-if="column.key === 'submitTime'">
            {{ formatDateTime(record.submitTime) || '-' }}
          </template>
          <template v-else-if="String(column.key).startsWith('total:')">
            {{ totalOf(record, column.key) }}
          </template>
        </template>
        <template v-if="(summary?.fields ?? []).length > 0" #summary>
          <Table.Summary fixed>
            <Table.Summary.Row>
              <Table.Summary.Cell :index="0" :col-span="4">合计（审批通过）</Table.Summary.Cell>
              <Table.Summary.Cell
                v-for="(field, index) in summary?.fields ?? []"
                :key="field.fieldCode"
                :index="index + 4"
                align="right"
              >
                {{ summary?.totals[field.fieldCode] ?? '0' }}
              </Table.Summary.Cell>
            </Table.Summary.Row>
          </Table.Summary>
        </template>
      </Table>
    </div>
  </Modal>
</template>
