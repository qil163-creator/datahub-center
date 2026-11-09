<script lang="ts" setup>
import type { SupplyApiLogApi } from '#/api/supply/apilog';

import {
  computed,
  onActivated,
  onDeactivated,
  onMounted,
  onUnmounted,
  ref,
  watch,
} from 'vue';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Alert, Button } from 'antdv-next';

import { getApiLogOverview } from '#/api/supply/apilog';

import { buildOverviewItems, buildRankList } from './data';
import MetricCards from './modules/metric-cards.vue';
import RankChart from './modules/rank-chart.vue';
import TrendChart from './modules/trend-chart.vue';

defineOptions({ name: 'SupplyOverview' });

const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const canQuery = computed(() => hasAccessByCodes(['supply:api-log:query']));
const overview = ref<SupplyApiLogApi.Overview>();
const loading = ref(false);
const failed = ref(false);
const scope = computed(() =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    access.accessCodes,
    user.userInfo?.userId,
    user.userRoles,
  ]),
);
const overviewItems = computed(() => buildOverviewItems(overview.value));
const trendList = computed(() => overview.value?.trend ?? []);
const todayRankList = computed(() => buildRankList(overview.value?.todayRank));
const monthRankList = computed(() => buildRankList(overview.value?.monthRank));

let requestId = 0;
let mounted = false;
let active = true;
let wasDeactivated = false;

async function loadOverview() {
  if (!access.accessToken || !canQuery.value) return;
  const id = ++requestId;
  loading.value = true;
  failed.value = false;
  try {
    const result = await getApiLogOverview();
    if (id !== requestId) return;
    overview.value = result;
  } catch {
    if (id === requestId) failed.value = true;
  } finally {
    if (id === requestId) loading.value = false;
  }
}

watch(
  scope,
  () => {
    requestId++;
    overview.value = undefined;
    failed.value = false;
    loading.value = false;
    if (mounted && active) void loadOverview();
  },
  { flush: 'sync' },
);

onMounted(() => {
  mounted = true;
  void loadOverview();
});
onDeactivated(() => {
  active = false;
  wasDeactivated = true;
  requestId++;
  loading.value = false;
});
onActivated(() => {
  active = true;
  if (wasDeactivated) void loadOverview();
  wasDeactivated = false;
});
onUnmounted(() => {
  mounted = false;
  requestId++;
});
</script>

<template>
  <Page content-class="p-5">
    <div class="flex flex-col gap-5">
      <Alert
        v-if="!canQuery"
        type="warning"
        show-icon
        message="暂无查看数据概览的权限"
      />
      <Alert v-else-if="failed" type="warning" show-icon>
        <template #message>
          {{
            overview
              ? '刷新失败，当前显示上次成功获取的数据。'
              : '数据加载失败，请重试。'
          }}
          <Button type="link" size="small" @click="loadOverview">重试</Button>
        </template>
      </Alert>
      <template v-if="overview">
        <MetricCards :items="overviewItems" />
        <TrendChart :trend="trendList" />
        <div class="grid grid-cols-1 gap-5 md:grid-cols-2">
          <RankChart title="今日API调用排行" :list="todayRankList" />
          <RankChart title="本月API调用排行" :list="monthRankList" />
        </div>
      </template>
      <div
        v-else-if="loading"
        role="status"
        class="text-muted-foreground rounded-lg bg-card p-8 text-center text-sm"
      >
        数据加载中…
      </div>
    </div>
  </Page>
</template>
