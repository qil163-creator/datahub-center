<script lang="ts" setup>
import type { EChartsOption } from '@vben/plugins/echarts';

import type { RouteTarget } from '../../shared/route-resolver';

import type { DashboardApi } from '#/api/dashboard';
import type { SupplyApiLogApi } from '#/api/supply/apilog';

import { computed } from 'vue';

import { Button, Card } from 'antdv-next';

import {
  getApiTrendOptions,
  getWorkOrderTrendOptions,
} from '../../shared/chart-options';
import ChartPanel from '../../shared/chart-panel.vue';
import { DASHBOARD_TARGETS } from '../../shared/route-resolver';
import { formatCount, formatPercent } from '../../shared/utils';

/** 近 7 天趋势：API 调用（成功 / 失败）+ 质量工单（新增 / 处理） */
defineOptions({ name: 'WorkspaceTrendPanel' });

const props = defineProps<{
  /** undefined 表示数据服务统计不可用 */
  apiStats?: SupplyApiLogApi.Statistics;
  loading: boolean;
  /** undefined 表示质量工单统计不可用 */
  workOrderStats?: DashboardApi.WorkOrderStatistics;
}>();

const emit = defineEmits<{
  navigate: [target: RouteTarget];
}>();

/** 2026-11-05 → 11-05，横轴只看 7 天，去掉年份避免两端标签被裁切 */
function shortDate(date?: string) {
  return date && /^\d{4}-\d{2}-\d{2}/.test(date) ? date.slice(5, 10) : (date ?? '');
}

const apiOptions = computed<EChartsOption | undefined>(() => {
  const trend = props.apiStats?.trend;
  return trend && trend.length > 0
    ? getApiTrendOptions(
        trend.map((point) => ({ ...point, date: shortDate(point.date) })),
      )
    : undefined;
});

const workOrderOptions = computed<EChartsOption | undefined>(() => {
  const trend = props.workOrderStats?.trend;
  return trend && trend.length > 0
    ? getWorkOrderTrendOptions(
        trend.map((point) => ({ ...point, date: shortDate(point.date) })),
      )
    : undefined;
});

const apiSummary = computed(() => {
  const stats = props.apiStats;
  return stats
    ? `共 ${formatCount(stats.totalCount)} 次 · 成功率 ${formatPercent(stats.successRate)}`
    : '';
});

const workOrderSummary = computed(() => {
  const trend = props.workOrderStats?.trend;
  if (!trend) {
    return '';
  }
  const created = trend.reduce((sum, point) => sum + point.createdCount, 0);
  const processed = trend.reduce((sum, point) => sum + point.processedCount, 0);
  return `新增 ${formatCount(created)} 单 · 处理 ${formatCount(processed)} 单`;
});

const sections = computed(() => [
  {
    key: 'api',
    title: 'API 调用',
    summary: apiSummary.value,
    options: apiOptions.value,
    emptyText: '数据服务调用统计不可用',
    target: DASHBOARD_TARGETS.apiLog,
  },
  {
    key: 'workOrder',
    title: '质量工单',
    summary: workOrderSummary.value,
    options: workOrderOptions.value,
    emptyText: '质量工单统计不可用',
    target: DASHBOARD_TARGETS.qualityWorkOrder,
  },
]);
</script>

<template>
  <Card title="近 7 天趋势">
    <div class="grid grid-cols-1 gap-6 md:grid-cols-2">
      <section v-for="section in sections" :key="section.key" class="min-w-0">
        <div class="flex items-start justify-between gap-2">
          <div class="min-w-0">
            <div class="text-sm font-medium">{{ section.title }}</div>
            <div
              v-if="section.summary"
              class="text-muted-foreground mt-0.5 text-xs"
            >
              {{ section.summary }}
            </div>
          </div>
          <Button
            size="small"
            type="link"
            class="shrink-0 !px-0"
            @click="emit('navigate', section.target)"
          >
            明细
          </Button>
        </div>
        <ChartPanel
          :empty-text="section.emptyText"
          :loading="loading"
          :options="section.options"
          height="240px"
        />
      </section>
    </div>
  </Card>
</template>
