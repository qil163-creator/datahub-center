<script lang="ts" setup>
import type { QualityWorkOrderApi } from '#/api/governance/quality/workorder';

import { computed, onDeactivated, ref } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useUserStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import { Alert, Button, Empty, Spin, Tag } from 'antdv-next';

import { getWorkOrderRawIssues } from '#/api/governance/quality/workorder';
import { useObjectRequest } from '#/hooks/use-object-request';

import { losslessId } from '../use-work-order-request';

type WorkOrderSummary = Pick<
  QualityWorkOrderApi.WorkOrder,
  'id' | 'orderCode' | 'orderName'
>;
type RawIssues = Awaited<ReturnType<typeof getWorkOrderRawIssues>>['issues'];

const { hasAccessByCodes } = useAccess();
const user = useUserStore();
const canRead = computed(() =>
  hasAccessByCodes(['governance:quality-work-order:raw-query']),
);

const order = ref<WorkOrderSummary>();
const issues = ref<RawIssues>([]);
const loading = ref(false);
const failed = ref(false);
const liveValueNotes = computed(() => [
  ...new Set(
    issues.value.flatMap(({ liveValueNote }) =>
      liveValueNote ? [liveValueNote] : [],
    ),
  ),
]);

const [Modal, modalApi] = useVbenModal({
  cancelText: '关闭',
  centered: true,
  class: 'w-[680px]',
  contentClass: 'px-4 py-4 sm:px-5',
  fullscreenButton: false,
  showConfirmButton: false,
  title: '原文明细',
  onOpenChange(isOpen) {
    if (!isOpen) reset();
  },
});
const requests = useObjectRequest(
  () => `${user.userInfo?.userId}/${canRead.value}`,
  close,
);

function reset() {
  requests.begin();
  order.value = undefined;
  issues.value = [];
  loading.value = false;
  failed.value = false;
}

function close() {
  reset();
  void modalApi.close();
}

async function load() {
  const id = losslessId(order.value?.id);
  if (!id || !canRead.value) return;

  const current = requests.begin();
  loading.value = true;
  failed.value = false;
  try {
    const result = await getWorkOrderRawIssues(id);
    if (current()) issues.value = result.issues ?? [];
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}

function open(row: WorkOrderSummary) {
  if (!losslessId(row.id) || !canRead.value) return;
  reset();
  order.value = { id: row.id, orderCode: row.orderCode, orderName: row.orderName };
  modalApi.open();
  void load();
}

onDeactivated(close);
defineExpose({ open });
</script>

<template>
  <Modal>
    <div v-if="order" class="space-y-4">
      <div class="flex items-start gap-3 rounded-lg border bg-muted/40 p-4">
        <div
          class="flex size-9 shrink-0 items-center justify-center rounded-md bg-primary/10 text-primary"
        >
          <IconifyIcon aria-hidden="true" class="size-5" icon="lucide:file-search" />
        </div>
        <div class="min-w-0">
          <p class="mb-1 break-all text-xs text-muted-foreground">
            工单 {{ order.orderCode || `#${order.id}` }}
          </p>
          <p class="m-0 break-all text-sm font-semibold leading-5">
            {{ order.orderName || '未命名工单' }}
          </p>
        </div>
      </div>

      <div v-if="loading" class="flex justify-center py-8" role="status">
        <Spin aria-label="正在加载原文明细" />
      </div>
      <Alert v-else-if="failed" title="原文明细加载失败" show-icon type="error">
        <template #action>
          <Button size="small" @click="load">重试</Button>
        </template>
      </Alert>
      <template v-else-if="issues.length">
        <Alert
          title="历史检出记录"
          :classes="{ description: 'datahub-popup-help' }"
          show-icon
          type="info"
        >
          <template #description>
            <p v-for="note in liveValueNotes" :key="note" class="m-0 break-all">
              {{ note }}
            </p>
            <p v-if="!liveValueNotes.length" class="m-0">
              查询实时回源值须单独授权。
            </p>
          </template>
        </Alert>

        <section aria-label="已授权明细">
          <div class="mb-2 flex flex-wrap items-center gap-2">
            <h3 class="m-0 text-sm font-semibold">已授权明细</h3>
            <Tag class="m-0">{{ issues.length }} 条</Tag>
          </div>
          <ul class="m-0 list-none divide-y overflow-hidden rounded-lg border p-0">
            <li v-for="(issue, index) in issues" :key="issue.issueId ?? index">
              <dl class="m-0 grid gap-3 px-4 py-3 sm:grid-cols-[1fr_1.5fr_1fr]">
                <div class="min-w-0">
                  <dt class="text-xs text-muted-foreground">问题编号</dt>
                  <dd class="m-0 mt-1 break-all text-sm font-medium tabular-nums">
                    {{ issue.issueId ?? '—' }}
                  </dd>
                </div>
                <div class="min-w-0">
                  <dt class="text-xs text-muted-foreground">检出时间</dt>
                  <dd class="m-0 mt-1 text-sm tabular-nums">
                    {{ formatDateTime(issue.evidenceTime) || '—' }}
                  </dd>
                </div>
                <div v-if="issue.issueCount != null" class="min-w-0">
                  <dt class="text-xs text-muted-foreground">问题条数</dt>
                  <dd class="m-0 mt-1 break-all text-sm tabular-nums">
                    {{ issue.issueCount }}
                  </dd>
                </div>
              </dl>
            </li>
          </ul>
        </section>
      </template>
      <Empty v-else description="暂无已授权明细" />
    </div>
  </Modal>
</template>
