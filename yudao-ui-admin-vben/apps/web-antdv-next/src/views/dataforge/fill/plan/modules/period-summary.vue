<script lang="ts" setup>
import type { DataforgeFillApi } from '#/api/dataforge/fill';

import { computed, ref } from 'vue';

import { Modal, Table, Tag } from 'antdv-next';

import { getPlanPeriodSummary } from '#/api/dataforge/fill';

import { TASK_STATUS_TEXT } from '../../shared';
import { countUnreported, periodCell } from '../../summary';

defineOptions({ name: 'DataforgeFillPlanPeriodSummary' });

const open = ref(false);
const loading = ref(false);
const planName = ref('');
const summary = ref<DataforgeFillApi.PlanPeriodSummary>();
let generation = 0;

const columns = computed(() => [
  { dataIndex: 'orgName', key: 'orgName', title: '报送组织', width: 180, ellipsis: true, fixed: 'left' as const },
  { key: 'unreported', title: '未报期数', width: 90 },
  ...(summary.value?.periods ?? []).map((period) => ({
    key: `period:${period.taskId}`,
    taskId: period.taskId,
    title: `${period.periodKey}（${period.mergedCount}/${period.totalCount}）`,
    width: 150,
  })),
]);

/** 打开期次汇总；只采用最后一次打开的结果 */
async function openSummary(plan: { id: number | string; name?: string }) {
  const current = ++generation;
  planName.value = plan.name ?? '';
  summary.value = undefined;
  open.value = true;
  loading.value = true;
  try {
    const result = await getPlanPeriodSummary(plan.id);
    if (current === generation) summary.value = result;
  } finally {
    if (current === generation) loading.value = false;
  }
}

function periodTaskId(column: unknown) {
  return (column as { taskId?: number | string }).taskId ?? '';
}

defineExpose({ openSummary });
</script>

<template>
  <Modal v-model:open="open" :footer="null" :title="`期次汇总：${planName}`" :width="1120">
    <div class="flex flex-col gap-2 py-2">
      <p class="text-muted-foreground text-xs">
        最近 12 期各组织的报送状态；列标题括号内为“已合并/应填”组织数。橙色为未报，红色为未报且逾期。
      </p>
      <div class="flex flex-wrap gap-1">
        <Tag
          v-for="period in summary?.periods ?? []"
          :key="period.taskId"
          :color="TASK_STATUS_TEXT[period.status]?.color"
        >
          {{ period.periodKey }}：{{ TASK_STATUS_TEXT[period.status]?.text ?? period.status }}
        </Tag>
      </div>
      <Table
        :columns="columns"
        :data-source="summary?.orgs ?? []"
        :loading="loading"
        :pagination="false"
        :scroll="{ x: 'max-content', y: 520 }"
        row-key="reportingOrgId"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'unreported'">
            <Tag :color="countUnreported(record.statuses) > 0 ? 'warning' : 'success'">
              {{ countUnreported(record.statuses) }}
            </Tag>
          </template>
          <template v-else-if="String(column.key).startsWith('period:')">
            <Tag
              v-if="periodCell(record.statuses, record.overdue, periodTaskId(column))"
              :color="periodCell(record.statuses, record.overdue, periodTaskId(column))?.color"
            >
              {{ periodCell(record.statuses, record.overdue, periodTaskId(column))?.text }}
            </Tag>
            <span v-else class="text-muted-foreground">—</span>
          </template>
        </template>
      </Table>
    </div>
  </Modal>
</template>
