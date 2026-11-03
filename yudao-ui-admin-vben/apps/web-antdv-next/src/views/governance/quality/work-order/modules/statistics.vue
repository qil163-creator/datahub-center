<script setup lang="ts">
import type { QualityWorkOrderApi } from '#/api/governance/quality/workorder';

import {
  computed,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  ref,
  watch,
} from 'vue';

import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Alert, Button, Empty, Select, Spin } from 'antdv-next';

import { getWorkOrderStatistics } from '#/api/governance/quality/workorder';

import { createWorkOrderRequestGuard } from '../use-work-order-request';
import StatisticsCharts from './statistics-charts.vue';

const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const permitted = computed(() =>
  hasAccessByCodes(['governance:quality-work-order:query']),
);
const identity = () =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    user.userInfo?.userId,
  ]);
const days = ref(30);
const statistics = ref<QualityWorkOrderApi.Statistics>();
const state = ref<'empty' | 'error' | 'forbidden' | 'loading' | 'ready'>(
  'loading',
);
const guard = createWorkOrderRequestGuard();
let active = true;

async function load() {
  const request = guard.begin();
  const startedIdentity = identity();
  statistics.value = undefined;
  if (!permitted.value) {
    state.value = 'forbidden';
    return;
  }
  if (!active) return;
  state.value = 'loading';
  const current = () =>
    active &&
    guard.isLatest(request) &&
    identity() === startedIdentity &&
    permitted.value;
  try {
    const result = await getWorkOrderStatistics(days.value);
    if (!current()) return;
    // 新旧节点混用或不完整响应必须显式报错，不能把缺失聚合当成“无人负责”。
    if (
      !Array.isArray(result.ownerSummary) ||
      !Array.isArray(result.trend) ||
      !result.statusSummary ||
      !['NO_SAMPLE', 'SCORED'].includes(result.sampleStatus)
    ) {
      throw new Error('Incomplete work-order statistics contract');
    }
    statistics.value = result;
    state.value = result.sampleStatus === 'NO_SAMPLE' ? 'empty' : 'ready';
  } catch (error) {
    if (!current()) return;
    const response = (
      error as { response?: { data?: { code?: number }; status?: number } }
    )?.response;
    state.value =
      response?.status === 403 || response?.data?.code === 403
        ? 'forbidden'
        : 'error';
  }
}

watch(
  () => [identity(), permitted.value, days.value],
  () => {
    void load();
  },
  { immediate: true, flush: 'sync' },
);
onDeactivated(() => {
  active = false;
  guard.begin();
  statistics.value = undefined;
  state.value = 'loading';
});
onActivated(() => {
  if (!active) {
    active = true;
    void load();
  }
});
onBeforeUnmount(() => {
  active = false;
  guard.dispose();
});
</script>

<template>
  <section class="rounded-lg bg-[hsl(var(--card))] p-4" aria-label="工单统计">
    <div class="mb-3 flex items-center justify-between gap-2">
      <span>仅统计当前用户可见、所选期间内创建的工单；责任人按当前归属</span>
      <Select
        v-model:value="days"
        aria-label="统计天数"
        :disabled="!permitted"
        :options="[
          { label: '近7天', value: 7 },
          { label: '近15天', value: 15 },
          { label: '近30天', value: 30 },
        ]"
        class="w-28"
      />
    </div>
    <div v-if="state === 'loading'" role="status"><Spin /> 正在加载统计</div>
    <Alert
      v-else-if="state === 'forbidden'"
      type="warning"
      title="无权查看工单统计"
      show-icon
    />
    <div v-else-if="state === 'error'" role="alert">
      <Alert
        type="error"
        title="统计加载失败，请重试；若持续失败，请检查服务版本和权限服务"
        show-icon
      />
      <Button class="mt-2" @click="load">
        <template #icon>
          <IconifyIcon icon="lucide:refresh-cw" />
        </template>
        重试
      </Button>
    </div>
    <Empty
      v-else-if="state === 'empty'"
      description="所选期间内没有当前用户可见的工单"
    />
    <StatisticsCharts v-else-if="statistics" :statistics="statistics" />
  </section>
</template>
