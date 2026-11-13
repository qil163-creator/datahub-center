<script lang="ts" setup>
import type { EchartsUIType } from '@vben/plugins/echarts';

import type { IndicatorModelApi } from '#/api/modeling/indicator-model';

import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';

import { EchartsUI, useEcharts } from '@vben/plugins/echarts';

import { Alert, Card, Spin, Table } from 'antdv-next';

import { queryIndicatorData } from '#/api/modeling/indicator-model';
import { DataWatermark } from '#/components/data-watermark';
import {
  indexMaskedColumns,
  renderMaskedColumnTitle,
} from '#/components/masked-column';
import { createLatestWins } from '#/utils/resource-id';

import { rowKey } from '../query/state';
import { boardChart, statValue } from './board';

/** 看板组件：按查看者身份实时查询（结果经动态脱敏），不缓存结果。 */
defineOptions({ name: 'IndicatorBoardWidget' });

const props = defineProps<{ widget: IndicatorModelApi.BoardWidget }>();

const result = ref<IndicatorModelApi.QueryResult>();
const loading = ref(false);
const failed = ref(false);
const guard = createLatestWins();
const chartRef = ref<EchartsUIType>();
const { renderEcharts } = useEcharts(chartRef);

const masked = computed(() => indexMaskedColumns(result.value?.maskedColumns));
const columns = computed(() =>
  (result.value?.columns ?? []).map((column) => ({
    dataIndex: column.key,
    key: column.key,
    title: renderMaskedColumnTitle(column.title, masked.value.get(column.key)),
  })),
);
const stat = computed(() => statValue(result.value));
const chart = computed(() => boardChart(result.value));
const isChart = computed(
  () => props.widget.chartType === 'LINE' || props.widget.chartType === 'BAR',
);

async function load() {
  const ticket = guard.next();
  loading.value = true;
  failed.value = false;
  try {
    const value = await queryIndicatorData(props.widget.query);
    if (!ticket.isCurrent()) return;
    result.value = value;
    if (isChart.value) {
      await nextTick();
      renderChart();
    }
  } catch {
    if (ticket.isCurrent()) {
      result.value = undefined;
      failed.value = true;
    }
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}

function renderChart() {
  const model = chart.value;
  if (!model) return;
  void renderEcharts({
    tooltip: { trigger: 'axis' },
    legend: { type: 'scroll', top: 0 },
    grid: { left: 40, right: 16, top: 36, bottom: 24, containLabel: true },
    xAxis: { type: 'category', data: model.categories },
    yAxis: { type: 'value' },
    series: model.series.map((item) => ({
      name: item.name,
      type: props.widget.chartType === 'BAR' ? 'bar' : 'line',
      data: item.data,
    })),
  } as any);
}

watch(() => props.widget, load, { deep: true, immediate: true });
onBeforeUnmount(() => guard.invalidate());
</script>

<template>
  <Card :title="widget.title" size="small" class="h-full">
    <Spin :spinning="loading">
      <Alert
        v-if="failed"
        type="error"
        show-icon
        message="查询失败，请检查指标口径或权限"
      />
      <DataWatermark v-else>
        <div v-if="widget.chartType === 'STAT'" class="py-6 text-center">
          <div class="text-muted-foreground text-sm">{{ stat.title }}</div>
          <div class="mt-2 font-mono text-3xl">{{ stat.value }}</div>
        </div>
        <template v-else-if="isChart">
          <EchartsUI v-if="chart" ref="chartRef" height="260px" />
          <div v-else class="text-muted-foreground py-6 text-center text-sm">
            无时间维度，无法绘图
          </div>
        </template>
        <Table
          v-else
          :columns="columns"
          :data-source="result?.rows ?? []"
          :pagination="{ pageSize: 10 }"
          :row-key="
            (row: Record<string, null | string>) =>
              rowKey(row, result?.columns ?? [])
          "
          :scroll="{ x: 'max-content' }"
          size="small"
        />
      </DataWatermark>
    </Spin>
  </Card>
</template>
