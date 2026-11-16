<script setup lang="ts">
import type { EchartsUIType } from '@vben/plugins/echarts';

import type { QualityAuditLogApi } from '#/api/governance/quality/auditlog';

import { onMounted, ref } from 'vue';

import { EchartsUI, useEcharts } from '@vben/plugins/echarts';
import { formatDateTime } from '@vben/utils';

const props = defineProps<{ points: QualityAuditLogApi.MetricTrendPoint[] }>();

const chartRef = ref<EchartsUIType>();
const { renderEcharts } = useEcharts(chartRef);

/** 仅用于绘图的近似数值；原始十进制字符串用于提示框展示 */
function approx(value?: null | string) {
  return value === null || value === undefined || value === ''
    ? null
    : Number(value);
}

/** 业务日期加检测时刻：同一业务日期多次检测时也能区分 */
function label(point: QualityAuditLogApi.MetricTrendPoint) {
  const checked = String(formatDateTime(point.checkTime) ?? '');
  return point.bizDate
    ? `${point.bizDate}\n${checked.slice(11, 16)}`
    : checked;
}

/** 纵轴上下各留出 10% 空间，避免预期区间线与坐标轴重合 */
function padded(range: { max: number; min: number }, side: 'max' | 'min') {
  const gap = Math.max((range.max - range.min) * 0.1, 1);
  return side === 'min'
    ? Math.max(0, Math.floor(range.min - gap))
    : Math.ceil(range.max + gap);
}

onMounted(() => {
  const points = props.points;
  void renderEcharts({
    tooltip: {
      trigger: 'axis',
      renderMode: 'richText',
      formatter: (params: unknown) => {
        const index = (params as { dataIndex: number }[])[0]?.dataIndex ?? 0;
        const point = points[index];
        if (!point) return '';
        const range =
          point.lower || point.upper
            ? `\n预期区间：${point.lower ?? '不限'} ～ ${point.upper ?? '不限'}`
            : '';
        const result =
          { FAIL: '未通过', PASS: '通过', SKIPPED: '仅记录' }[
            point.resultType ?? ''
          ] ?? point.resultType;
        return `${label(point)}\n行数：${point.value}${range}\n结果：${result}`;
      },
    },
    legend: { bottom: 0 },
    grid: { left: 60, right: 20, top: 20, bottom: 50 },
    xAxis: { type: 'category', data: points.map((point) => label(point)) },
    yAxis: {
      type: 'value',
      minInterval: 1,
      min: (range: { max: number; min: number }) => padded(range, 'min'),
      max: (range: { max: number; min: number }) => padded(range, 'max'),
    },
    series: [
      {
        name: '行数',
        type: 'line',
        data: points.map((point) => ({
          value: approx(point.value),
          itemStyle:
            point.resultType === 'FAIL' ? { color: '#f5222d' } : undefined,
          symbolSize: point.resultType === 'FAIL' ? 9 : 5,
        })),
      },
      {
        name: '预期上限',
        type: 'line',
        step: 'middle',
        lineStyle: { type: 'dashed' },
        symbol: 'none',
        data: points.map((point) => approx(point.upper)),
      },
      {
        name: '预期下限',
        type: 'line',
        step: 'middle',
        lineStyle: { type: 'dashed' },
        symbol: 'none',
        data: points.map((point) => approx(point.lower)),
      },
    ],
  });
});
</script>

<template>
  <EchartsUI ref="chartRef" class="h-[280px]" />
</template>
