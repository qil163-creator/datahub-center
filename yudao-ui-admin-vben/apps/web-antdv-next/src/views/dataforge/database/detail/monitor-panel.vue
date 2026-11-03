<script lang="ts" setup>
import type { EChartsOption, EchartsUIType } from '@vben/plugins/echarts';

import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { DatabaseMonitorApi } from '#/api/dataforge/database/monitor';

import { computed, nextTick, onMounted, ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';
import { EchartsUI, useEcharts } from '@vben/plugins/echarts';
import { formatDateTime } from '@vben/utils';

import {
  Alert,
  Button,
  Descriptions,
  DescriptionsItem,
  RadioButton,
  RadioGroup,
  Tag,
} from 'antdv-next';

import {
  connectionStatusColor,
  connectionStatusLabel,
} from '../connection-status';

const props = defineProps<{
  collecting?: boolean;
  database: FoundationDatabaseApi.Database;
  loading?: boolean;
  overview?: DatabaseMonitorApi.Overview;
  trend?: DatabaseMonitorApi.Trend;
}>();
const emit = defineEmits<{ collect: []; rangeChange: [days: number] }>();

const rangeDays = ref(7);
watch(rangeDays, (days) => emit('rangeChange', days));
const ranges = [7, 15, 30];
const readChartRef = ref<EchartsUIType>();
const writeChartRef = ref<EchartsUIType>();
const responseChartRef = ref<EchartsUIType>();
const { renderEcharts: renderReadChart } = useEcharts(readChartRef);
const { renderEcharts: renderWriteChart } = useEcharts(writeChartRef);
const { renderEcharts: renderResponseChart } = useEcharts(responseChartRef);
type PerformanceMetricKey = 'readSpeed' | 'responseTimeMs' | 'writeSpeed';
const connectionStatus = computed(
  () => props.overview?.connectionStatus ?? props.database.connectionStatus,
);
const pendingMessage = computed(() => {
  if (props.collecting) return '正在采集数据…';
  if (props.loading) return '正在加载数据…';
  return '';
});
const performanceMetrics: {
  accent: string;
  color: string;
  icon: string;
  key: PerformanceMetricKey;
  name: string;
  surface: string;
  unit: string;
}[] = [
  {
    key: 'readSpeed',
    name: '读取速度',
    unit: '行/s',
    color: '#6287bf',
    icon: 'lucide:download',
    surface:
      'border-blue-200/70 bg-blue-50/40 dark:border-blue-900 dark:bg-blue-950/20',
    accent: 'text-blue-600 dark:text-blue-400',
  },
  {
    key: 'writeSpeed',
    name: '写入速度',
    unit: '行/s',
    color: '#86b967',
    icon: 'lucide:upload',
    surface:
      'border-green-200/70 bg-green-50/40 dark:border-green-900 dark:bg-green-950/20',
    accent: 'text-green-600 dark:text-green-400',
  },
  {
    key: 'responseTimeMs',
    name: '响应时间',
    unit: 'ms',
    color: '#c8a558',
    icon: 'lucide:timer',
    surface:
      'border-amber-200/70 bg-amber-50/40 dark:border-amber-900 dark:bg-amber-950/20',
    accent: 'text-amber-600 dark:text-amber-400',
  },
];
const capacityMetrics = [
  { key: 'used', name: '已用容量', icon: 'lucide:database' },
  { key: 'total', name: '总容量', icon: 'lucide:hard-drive' },
  { key: 'rate', name: '容量使用率', icon: 'lucide:gauge' },
];

function formatInteger(value: string) {
  return value.replaceAll(/\B(?=(\d{3})+(?!\d))/g, ',');
}

function formatRows(value?: string) {
  return value === undefined ? '—' : `${formatInteger(value)} 条`;
}

function hasTrend(key: PerformanceMetricKey) {
  return (
    props.trend?.days === rangeDays.value &&
    props.trend.points.some(
      (point) => point[key] !== null && point[key] !== undefined,
    )
  );
}

function setChartRef(key: PerformanceMetricKey, instance: unknown) {
  const chartRef = chartRefs[key];
  chartRef.value = (instance ?? undefined) as EchartsUIType;
}

const chartRefs = {
  readSpeed: readChartRef,
  writeSpeed: writeChartRef,
  responseTimeMs: responseChartRef,
};
const chartRenderers = {
  readSpeed: renderReadChart,
  writeSpeed: renderWriteChart,
  responseTimeMs: renderResponseChart,
};

async function renderTrends() {
  await nextTick();
  if (!props.trend || props.trend.days !== rangeDays.value) return;
  for (const metric of performanceMetrics) {
    if (!hasTrend(metric.key)) continue;
    const chartRef = chartRefs[metric.key];
    if (!chartRef.value) continue;
    const options: EChartsOption = {
      animation: false,
      grid: { top: 12, right: 8, bottom: 26, left: 42 },
      tooltip: {
        trigger: 'axis',
        valueFormatter: (value) =>
          value === null ? '暂无数据' : `${value} ${metric.unit}`,
      },
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: props.trend.points.map((point) =>
          point.date.slice(5).replace('-', '.'),
        ),
        axisTick: { show: false },
        axisLine: { lineStyle: { color: '#d4d4d8' } },
        axisLabel: { fontSize: 10, color: '#8c8c8c' },
      },
      yAxis: {
        type: 'value',
        axisLabel: { fontSize: 10, color: '#8c8c8c' },
        splitLine: {
          lineStyle: { type: 'dashed', color: 'rgba(148, 163, 184, 0.16)' },
        },
      },
      series: [
        {
          name: metric.name,
          type: 'line',
          data: props.trend.points.map((point) => point[metric.key]),
          connectNulls: false,
          symbol: 'circle',
          symbolSize: 5,
          lineStyle: { color: metric.color, width: 2 },
          itemStyle: { color: metric.color },
          areaStyle: { color: metric.color, opacity: 0.1 },
        },
      ],
    };
    const render = chartRenderers[metric.key];
    await render(options);
  }
}

watch(
  [() => props.trend, readChartRef, writeChartRef, responseChartRef, rangeDays],
  renderTrends,
  { flush: 'post' },
);
onMounted(renderTrends);
</script>

<template>
  <div class="space-y-7 py-1" :aria-busy="collecting || loading">
    <Alert
      v-if="
        overview?.collectionStatus && overview.collectionStatus !== 'SUCCESS'
      "
      :message="overview.collectionMessage || '本次采集未取得有效结果'"
      show-icon
      type="warning"
    />
    <section aria-labelledby="database-monitor-overview">
      <div class="mb-3 flex flex-wrap items-center justify-between gap-3">
        <h3
          id="database-monitor-overview"
          class="m-0 flex items-center gap-2 font-medium"
        >
          <span
            class="bg-primary h-4 w-1 rounded-full"
            aria-hidden="true"
          ></span>
          监控概览
          <span
            v-if="pendingMessage"
            role="status"
            class="text-muted-foreground text-xs font-normal"
          >
            {{ pendingMessage }}
          </span>
        </h3>
        <Button
          size="small"
          :loading="collecting"
          :disabled="collecting || loading || database.status !== 0"
          @click="emit('collect')"
        >
          <template #icon>
            <IconifyIcon icon="lucide:refresh-cw" class="size-3.5" />
          </template>
          立即采集
        </Button>
      </div>
      <Descriptions :column="{ xs: 1, sm: 2, lg: 3 }" bordered size="small">
        <DescriptionsItem :label="overview ? '最近连接采集' : '最近连接测试'">
          <div class="flex flex-wrap items-center gap-2">
            <Tag :color="connectionStatusColor(connectionStatus)">
              {{ connectionStatusLabel(connectionStatus) }}
            </Tag>
            <span
              v-if="
                overview?.lastCollectedTime ||
                (!overview && database.lastTestTime)
              "
              class="text-muted-foreground text-xs"
            >
              {{
                formatDateTime(
                  overview?.lastCollectedTime ?? database.lastTestTime,
                )
              }}
            </span>
          </div>
        </DescriptionsItem>
        <DescriptionsItem label="今日数据输出量">
          {{ formatRows(overview?.todayOutputRows) }}
        </DescriptionsItem>
        <DescriptionsItem label="今日数据输入量">
          {{ formatRows(overview?.todayInputRows) }}
        </DescriptionsItem>
        <DescriptionsItem label="当前告警数">
          {{ overview ? `${overview.pendingAlarmCount} 条` : '—' }}
        </DescriptionsItem>
        <DescriptionsItem label="平均响应耗时">
          {{
            overview?.averageResponseTimeMs == null
              ? '—'
              : `${overview.averageResponseTimeMs} ms`
          }}
        </DescriptionsItem>
        <DescriptionsItem label="传输总条数">
          {{ formatRows(overview?.totalTransferRows) }}
        </DescriptionsItem>
      </Descriptions>
      <p
        v-if="overview?.logCoverage"
        class="text-muted-foreground mb-0 mt-2 text-xs"
      >
        {{ overview.logCoverage }}
      </p>
    </section>

    <section aria-labelledby="database-performance-monitor">
      <div class="mb-3 flex flex-wrap items-center justify-between gap-3">
        <h3
          id="database-performance-monitor"
          class="m-0 flex items-center gap-2 font-medium"
        >
          <span
            class="bg-primary h-4 w-1 rounded-full"
            aria-hidden="true"
          ></span>
          性能监控信息
        </h3>
        <RadioGroup
          v-model:value="rangeDays"
          aria-label="性能监控时间范围"
          button-style="solid"
          size="small"
        >
          <RadioButton v-for="days in ranges" :key="days" :value="days">
            近{{ days }}天
          </RadioButton>
        </RadioGroup>
      </div>

      <div class="grid gap-4 lg:grid-cols-3">
        <article
          v-for="metric in performanceMetrics"
          :key="metric.name"
          :class="metric.surface"
          class="flex min-h-60 flex-col rounded border px-5 pb-4 pt-5"
        >
          <div class="flex items-center justify-between gap-2">
            <h4 class="m-0 text-sm font-medium">{{ metric.name }}</h4>
            <IconifyIcon
              :icon="metric.icon"
              :class="metric.accent"
              class="size-4"
            />
          </div>
          <div :class="metric.accent" class="mt-2 flex items-baseline gap-2">
            <span class="text-xl font-semibold">{{
              overview?.[metric.key] ?? '—'
            }}</span>
            <span class="text-xs">{{ metric.unit }}</span>
          </div>
          <EchartsUI
            v-if="hasTrend(metric.key)"
            :ref="(instance) => setChartRef(metric.key, instance)"
            height="156px"
            class="mt-3"
          />
          <div
            v-else
            class="text-muted-foreground flex flex-1 flex-col items-center justify-center gap-2 py-5 text-xs"
          >
            <IconifyIcon
              icon="lucide:chart-no-axes-combined"
              class="mb-1 size-7 opacity-40"
            />
            <span>{{ pendingMessage || '暂无采集数据' }}</span>
            <span class="text-xs opacity-75">近 {{ rangeDays }} 天</span>
          </div>
        </article>
      </div>
      <p class="text-muted-foreground mb-0 mt-2 text-xs">
        读取、写入速度为已完成同步任务的平均行吞吐；响应时间为数据库连接探测耗时。
      </p>
    </section>

    <section aria-labelledby="database-capacity-monitor">
      <h3
        id="database-capacity-monitor"
        class="mb-3 flex items-center gap-2 font-medium"
      >
        <span class="bg-primary h-4 w-1 rounded-full" aria-hidden="true"></span>
        容量监控信息
      </h3>
      <div class="grid gap-4 lg:grid-cols-3">
        <article
          v-for="metric in capacityMetrics"
          :key="metric.name"
          class="border-border flex items-center gap-4 rounded border p-5"
        >
          <div
            class="bg-muted text-muted-foreground flex size-11 shrink-0 items-center justify-center rounded-full"
          >
            <IconifyIcon :icon="metric.icon" class="size-5" />
          </div>
          <div class="flex-1">
            <h4 class="m-0 text-sm font-medium">{{ metric.name }}</h4>
            <div
              class="mt-2 flex flex-wrap items-baseline justify-between gap-2"
            >
              <span class="break-all text-xl font-semibold">{{
                metric.key === 'used' && overview?.capacityUsed != null
                  ? `${formatInteger(overview.capacityUsed)} B`
                  : '—'
              }}</span>
              <span
                v-if="metric.key !== 'used' || overview?.capacityUsed == null"
                class="text-muted-foreground text-xs"
                >{{
                  pendingMessage ||
                  (metric.key === 'used' ? '暂无采集数据' : '未提供容量上限')
                }}</span>
            </div>
          </div>
        </article>
      </div>
    </section>
  </div>
</template>
