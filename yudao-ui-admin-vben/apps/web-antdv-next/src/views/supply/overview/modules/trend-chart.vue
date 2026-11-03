<script lang="ts" setup>
import type { EchartsUIType } from '@vben/plugins/echarts';

import type { TrendRange } from '../data';

import type { SupplyApiLogApi } from '#/api/supply/apilog';

import { computed, onMounted, ref, watch } from 'vue';

import { EchartsUI, useEcharts } from '@vben/plugins/echarts';

import { Card, RadioButton, RadioGroup } from 'antdv-next';

import { getTrendChartOptions } from '../chart-options';
import { sliceTrendList } from '../data';

defineOptions({ name: 'SupplyTrendChart' });

const props = defineProps<{
  trend: SupplyApiLogApi.Overview['trend'];
}>();

const days = ref<TrendRange>(30);
const chartRef = ref<EchartsUIType>();
const { renderEcharts } = useEcharts(chartRef);
const points = computed(() => sliceTrendList(props.trend, days.value));
const hasCalls = computed(() => points.value.some((point) => point.count > 0));

function render() {
  renderEcharts(getTrendChartOptions(points.value));
}

watch(() => props.trend, render, { deep: true });

onMounted(() => {
  render();
});
</script>

<template>
  <Card :title="`近${days}天API调用趋势`">
    <template #extra>
      <RadioGroup v-model:value="days" size="small" @change="render">
        <RadioButton :value="7">最近7天</RadioButton>
        <RadioButton :value="15">最近15天</RadioButton>
        <RadioButton :value="30">最近30天</RadioButton>
      </RadioGroup>
    </template>
    <div class="trend-chart">
      <EchartsUI ref="chartRef" height="360px" />
      <div v-if="!hasCalls" class="trend-chart__empty">该时段暂无调用</div>
    </div>
  </Card>
</template>

<style scoped>
.trend-chart {
  position: relative;
}

.trend-chart__empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: hsl(var(--muted-foreground));
  pointer-events: none;
}
</style>
