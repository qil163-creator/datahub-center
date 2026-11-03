<script lang="ts" setup>
import type { EchartsUIType } from '@vben/plugins/echarts';

import type { SupplyApiLogApi } from '#/api/supply/apilog';

import { onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { EchartsUI, useEcharts } from '@vben/plugins/echarts';

import { Button, Segmented } from 'antdv-next';

import { getApiLogStatistics } from '#/api/supply/apilog';

defineOptions({ name: 'SupplyDashboard' });

const days = ref<number>(7);
const loading = ref(false);
const stats = ref<SupplyApiLogApi.Statistics>({});

const trendChartRef = ref<EchartsUIType>();
const topChartRef = ref<EchartsUIType>();
const slowChartRef = ref<EchartsUIType>();
const { renderEcharts: renderTrend } = useEcharts(trendChartRef);
const { renderEcharts: renderTop } = useEcharts(topChartRef);
const { renderEcharts: renderSlow } = useEcharts(slowChartRef);

const metricCards = [
  {
    key: 'apiCount',
    label: '接口总数',
    icon: 'carbon:api-1',
    color: '#5b8ff9',
  },
  {
    key: 'todayCount',
    label: '今日请求量',
    icon: 'carbon:send-alt',
    color: '#5ad8a6',
  },
  {
    key: 'successRate',
    label: '成功率',
    icon: 'carbon:checkmark-outline',
    color: '#41c464',
    suffix: '%',
  },
  {
    key: 'avgDurationMs',
    label: '平均耗时',
    icon: 'carbon:time',
    color: '#f6bd16',
    suffix: ' ms',
  },
  {
    key: 'failCount',
    label: '失败次数',
    icon: 'carbon:close-outline',
    color: '#f4664a',
  },
  {
    key: 'totalCount',
    label: '总请求量',
    icon: 'carbon:wikis',
    color: '#945fb9',
  },
] as const;

function metricValue(key: string) {
  const value = (stats.value as Record<string, unknown>)[key];
  return value === undefined || value === null ? '-' : String(value);
}

async function loadData() {
  loading.value = true;
  try {
    stats.value = await getApiLogStatistics(days.value);
    renderCharts();
  } finally {
    loading.value = false;
  }
}

function renderCharts() {
  const trend = stats.value.trend ?? [];
  renderTrend({
    grid: { bottom: 30, left: 50, right: 20, top: 40 },
    legend: { data: ['成功', '失败'] },
    series: [
      {
        data: trend.map((item) => item.successCount ?? 0),
        itemStyle: { color: '#5ad8a6' },
        name: '成功',
        smooth: true,
        type: 'line',
      },
      {
        data: trend.map((item) => item.failCount ?? 0),
        itemStyle: { color: '#f4664a' },
        name: '失败',
        smooth: true,
        type: 'line',
      },
    ],
    tooltip: { trigger: 'axis' },
    xAxis: {
      boundaryGap: false,
      data: trend.map((item) => item.date ?? ''),
      type: 'category',
    },
    yAxis: { minInterval: 1, type: 'value' },
  });

  const topApis = (stats.value.topApis ?? []).toReversed();
  renderTop({
    grid: { bottom: 20, left: 10, right: 40, top: 10, containLabel: true },
    series: [
      {
        data: topApis.map((item) => item.value ?? 0),
        itemStyle: { borderRadius: [0, 4, 4, 0], color: '#5b8ff9' },
        label: { position: 'right', show: true },
        name: '调用量',
        type: 'bar',
      },
    ],
    tooltip: { trigger: 'axis' },
    xAxis: { minInterval: 1, type: 'value' },
    yAxis: {
      axisLabel: { width: 150, overflow: 'truncate' },
      data: topApis.map((item) => item.name ?? ''),
      type: 'category',
    },
  });

  const slowApis = (stats.value.slowApis ?? []).toReversed();
  renderSlow({
    grid: { bottom: 20, left: 10, right: 50, top: 10, containLabel: true },
    series: [
      {
        data: slowApis.map((item) => item.value ?? 0),
        itemStyle: { borderRadius: [0, 4, 4, 0], color: '#f6bd16' },
        label: { formatter: '{c} ms', position: 'right', show: true },
        name: '平均耗时',
        type: 'bar',
      },
    ],
    tooltip: { trigger: 'axis', valueFormatter: (value: any) => `${value} ms` },
    xAxis: { type: 'value' },
    yAxis: {
      axisLabel: { width: 150, overflow: 'truncate' },
      data: slowApis.map((item) => item.name ?? ''),
      type: 'category',
    },
  });
}

function handleDaysChange() {
  loadData();
}

onMounted(loadData);
</script>

<template>
  <Page auto-content-height>
    <div class="dashboard">
      <div class="dashboard__toolbar">
        <div class="dashboard__title">
          监控统计
          <span class="dashboard__subtitle">
            实时监控 API 调用量、响应时间、错误率等运营指标
          </span>
        </div>
        <div class="flex items-center gap-2">
          <Segmented
            v-model:value="days"
            :options="[
              { label: '近 7 天', value: 7 },
              { label: '近 15 天', value: 15 },
              { label: '近 30 天', value: 30 },
            ]"
            @change="handleDaysChange"
          />
          <Button :loading="loading" size="small" @click="loadData">
            <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
            刷新
          </Button>
        </div>
      </div>

      <!-- 指标卡 -->
      <div class="dashboard__metrics">
        <div v-for="card in metricCards" :key="card.key" class="metric-card">
          <div
            :style="{ background: `${card.color}1a`, color: card.color }"
            class="metric-card__icon"
          >
            <IconifyIcon :icon="card.icon" class="text-xl" />
          </div>
          <div class="metric-card__body">
            <div class="metric-card__value">
              {{ metricValue(card.key) }}<span v-if="'suffix' in card && card.suffix" class="metric-card__suffix">{{ card.suffix }}</span>
            </div>
            <div class="metric-card__label">
              {{ card.label }}
              <template v-if="card.key === 'apiCount'">
                （上线 {{ stats.onlineApiCount ?? '-' }}）
              </template>
            </div>
          </div>
        </div>
      </div>

      <!-- 调用趋势 -->
      <div class="dashboard__panel">
        <div class="dashboard__panel-title">接口调用量趋势</div>
        <EchartsUI ref="trendChartRef" height="300px" />
      </div>

      <!-- 排行 -->
      <div class="dashboard__grid">
        <div class="dashboard__panel">
          <div class="dashboard__panel-title">热门 API 排行（调用量 TOP10）</div>
          <EchartsUI ref="topChartRef" height="320px" />
        </div>
        <div class="dashboard__panel">
          <div class="dashboard__panel-title">耗时 API 排行（平均耗时 TOP10）</div>
          <EchartsUI ref="slowChartRef" height="320px" />
        </div>
      </div>
    </div>
  </Page>
</template>

<style scoped>
.dashboard {
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 100%;
  overflow-y: auto;
}

.dashboard__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background: hsl(var(--card));
  border-radius: 8px;
}

.dashboard__title {
  font-size: 16px;
  font-weight: 600;
}

.dashboard__subtitle {
  margin-left: 8px;
  font-size: 12px;
  font-weight: 400;
  color: hsl(var(--muted-foreground));
}

.dashboard__metrics {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 12px;
}

@media (max-width: 1280px) {
  .dashboard__metrics {
    grid-template-columns: repeat(3, 1fr);
  }
}

.metric-card {
  display: flex;
  gap: 12px;
  align-items: center;
  padding: 14px 16px;
  background: hsl(var(--card));
  border-radius: 8px;
}

.metric-card__icon {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  border-radius: 10px;
}

.metric-card__body {
  min-width: 0;
}

.metric-card__value {
  font-size: 20px;
  font-weight: 700;
  line-height: 1.2;
}

.metric-card__suffix {
  margin-left: 2px;
  font-size: 12px;
  font-weight: 400;
  color: hsl(var(--muted-foreground));
}

.metric-card__label {
  font-size: 12px;
  color: hsl(var(--muted-foreground));
  white-space: nowrap;
}

.dashboard__panel {
  padding: 14px 16px;
  background: hsl(var(--card));
  border-radius: 8px;
}

.dashboard__panel-title {
  padding-left: 8px;
  margin-bottom: 8px;
  font-size: 16px;
  font-weight: 600;
  border-left: 3px solid hsl(var(--primary));
}

.dashboard__grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
</style>
