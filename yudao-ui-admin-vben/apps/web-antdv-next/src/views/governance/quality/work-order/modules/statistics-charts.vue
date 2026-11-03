<script setup lang="ts">
import type { EchartsUIType } from '@vben/plugins/echarts';

import type { QualityWorkOrderApi } from '#/api/governance/quality/workorder';

import { onMounted, ref } from 'vue';

import { EchartsUI, useEcharts } from '@vben/plugins/echarts';
import { formatDateTime } from '@vben/utils';

const props = defineProps<{ statistics: QualityWorkOrderApi.Statistics }>();
const trendChartRef = ref<EchartsUIType>();
const statusChartRef = ref<EchartsUIType>();
const ownerChartRef = ref<EchartsUIType>();
const { renderEcharts: trendChart } = useEcharts(trendChartRef);
const { renderEcharts: statusChart } = useEcharts(statusChartRef);
const { renderEcharts: ownerChart } = useEcharts(ownerChartRef);
const statusLabels: Record<string, string> = {
  PENDING: '待受理',
  IN_PROGRESS: '处理中',
  PENDING_RECHECK: '待复检',
  RECHECKING: '复检中',
  PENDING_CONFIRMATION: '待确认',
  PROCESSED: '已关闭',
  TRANSFERRED: '历史转办',
};
const ownerLabel = (
  owner: QualityWorkOrderApi.Statistics['ownerSummary'][number],
) =>
  owner.ownerUserId === null || owner.ownerUserId === undefined
    ? owner.ownerUserName
    : `${owner.ownerUserName}（${owner.ownerUserId}）`;

onMounted(() => {
  const stats = props.statistics;
  void trendChart({
    tooltip: { trigger: 'axis', renderMode: 'richText' },
    legend: { bottom: 0 },
    grid: { left: 40, right: 20, top: 30, bottom: 50 },
    xAxis: { type: 'category', data: stats.trend.map((item) => item.date) },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      {
        name: '创建工单数',
        type: 'line',
        data: stats.trend.map((item) => item.createdCount),
      },
      {
        name: '已关闭工单数',
        type: 'line',
        data: stats.trend.map((item) => item.processedCount),
      },
    ],
  });
  void statusChart({
    tooltip: { trigger: 'item', renderMode: 'richText' },
    legend: { bottom: 0 },
    series: [
      {
        type: 'pie',
        radius: ['42%', '68%'],
        center: ['50%', '44%'],
        data: Object.entries(stats.statusSummary).map(([status, count]) => ({
          name: statusLabels[status] ?? status,
          value: count,
        })),
        label: { formatter: '{b}: {c}' },
      },
    ],
  });
  void ownerChart({
    tooltip: { trigger: 'axis', renderMode: 'richText' },
    grid: { left: 40, right: 20, top: 30, bottom: 80 },
    xAxis: {
      type: 'category',
      data: stats.ownerSummary.map(ownerLabel),
      axisLabel: { rotate: 25 },
    },
    yAxis: { type: 'value', minInterval: 1 },
    dataZoom: [
      {
        type: 'slider',
        start: 0,
        end: Math.min(100, 1200 / stats.ownerSummary.length),
      },
    ],
    series: [
      {
        name: '工单数量',
        type: 'bar',
        barMaxWidth: 40,
        data: stats.ownerSummary.map((item) => item.count),
      },
    ],
  });
});
</script>

<template>
  <div>
    <p class="mb-3">
      可见工单 {{ statistics.totalCount }} 条 · 截至
      {{ formatDateTime(statistics.asOf) }}
    </p>
    <div class="mb-3 rounded border p-3">
      <div class="mb-2 font-semibold">工单处理统计分析</div>
      <EchartsUI ref="trendChartRef" class="h-[280px]" />
    </div>
    <div class="grid gap-3 lg:grid-cols-2">
      <div class="rounded border p-3">
        <div class="mb-2 font-semibold">工单状态占比分析</div>
        <EchartsUI ref="statusChartRef" class="h-[280px]" />
      </div>
      <div class="rounded border p-3">
        <div class="mb-2 font-semibold">责任人工单量分布</div>
        <EchartsUI ref="ownerChartRef" class="h-[280px]" />
        <details>
          <summary>查看责任人统计明细</summary>
          <ul aria-label="责任人统计明细">
            <li
              v-for="owner in statistics.ownerSummary"
              :key="
                owner.ownerUserId == null
                  ? 'unassigned'
                  : String(owner.ownerUserId)
              "
            >
              {{ ownerLabel(owner) }}：{{ owner.count }} 条
            </li>
          </ul>
        </details>
      </div>
    </div>
    <p class="mt-3 text-sm">{{ statistics.coverage }}</p>
  </div>
</template>
