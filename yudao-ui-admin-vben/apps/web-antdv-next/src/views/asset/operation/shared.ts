import type { EChartsOption } from '@vben/plugins/echarts';

import type { AssetOperationApi } from '#/api/asset/operation';

export const VALUE_LEVELS: Record<
  AssetOperationApi.ValueLevel,
  { color: string; hint: string; label: string }
> = {
  HIGH: { color: 'success', label: '高价值', hint: '价值分 ≥ 70' },
  MEDIUM: { color: 'processing', label: '中价值', hint: '价值分 40-70' },
  LOW: { color: 'warning', label: '低价值', hint: '价值分 < 40' },
  IDLE: {
    color: 'default',
    label: '闲置',
    hint: '已发布、90 天无使用且无有效授权',
  },
};

export const SUGGESTIONS: Record<
  AssetOperationApi.Suggestion,
  { color: string; label: string }
> = {
  RETIRE_REVIEW: { color: 'red', label: '评估下线' },
  IMPROVE_QUALITY: { color: 'orange', label: '优先治理质量' },
  COMPLETE_METADATA: { color: 'gold', label: '补全编目信息' },
  PROMOTE: { color: 'blue', label: '加强推广' },
  KEEP: { color: 'green', label: '保持' },
};

export const ASSET_TYPES: Record<number, string> = {
  1: '库表',
  2: '接口',
  3: '文件',
};

export const ASSET_STATUSES: Record<number, string> = {
  0: '未发布',
  1: '已发布',
  2: '已下线',
};

/** 评分展示：保留一位小数，空值显示“未评估” */
export function formatScore(value?: null | number) {
  return value === null || value === undefined
    ? '未评估'
    : Number(value).toFixed(1);
}

/** 百分比展示 */
export function formatRate(value?: null | number) {
  return value === null || value === undefined
    ? '-'
    : `${Number(value).toFixed(1)}%`;
}

/** 闲置天数展示 */
export function formatIdle(days?: null | number) {
  if (days === null || days === undefined) return '-';
  if (days === 0) return '今天有使用';
  return `${days} 天`;
}

/** 分布饼图（按字典顺序给出标签，计数为 0 的项不展示） */
export function distributionPie<K extends string>(
  counts: Partial<Record<K, number>>,
  labels: Record<K, { label: string }>,
): EChartsOption {
  const data = (Object.keys(labels) as K[])
    .map((key) => ({ name: labels[key].label, value: counts[key] ?? 0 }))
    .filter((item) => item.value > 0);
  return {
    tooltip: { trigger: 'item', renderMode: 'richText' },
    legend: { bottom: 0 },
    series: [
      {
        type: 'pie',
        radius: ['42%', '68%'],
        center: ['50%', '44%'],
        data,
        label: { formatter: '{b}: {c}' },
      },
    ],
  };
}

/** 类目分布柱状图：资产数与其中闲置数 */
export function categoryBar(
  items: AssetOperationApi.CategoryItem[],
): EChartsOption {
  const top = items.slice(0, 12);
  return {
    tooltip: { trigger: 'axis', renderMode: 'richText' },
    legend: { bottom: 0 },
    grid: { left: 40, right: 20, top: 30, bottom: 70 },
    xAxis: {
      type: 'category',
      data: top.map((item) => item.categoryName),
      axisLabel: { rotate: 20 },
    },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      { name: '资产数', type: 'bar', data: top.map((item) => item.count) },
      { name: '闲置数', type: 'bar', data: top.map((item) => item.idleCount) },
    ],
  };
}

/** 单资产趋势：每日行为柱 + 热度/价值折线 */
export function trendOption(
  points: AssetOperationApi.TrendPoint[],
): EChartsOption {
  return {
    tooltip: { trigger: 'axis', renderMode: 'richText' },
    legend: { bottom: 0 },
    grid: { left: 40, right: 40, top: 30, bottom: 50 },
    xAxis: { type: 'category', data: points.map((item) => item.date.slice(5)) },
    yAxis: [
      { type: 'value', name: '次数', minInterval: 1 },
      { type: 'value', name: '分数', min: 0, max: 100 },
    ],
    series: [
      {
        name: '浏览',
        type: 'bar',
        stack: 'actions',
        data: points.map((item) => item.browseCount),
      },
      {
        name: '使用',
        type: 'bar',
        stack: 'actions',
        data: points.map((item) => item.usageCount),
      },
      {
        name: '申请',
        type: 'bar',
        stack: 'actions',
        data: points.map((item) => item.applyCount),
      },
      {
        name: '热度分',
        type: 'line',
        yAxisIndex: 1,
        connectNulls: true,
        data: points.map((item) => item.heatScore ?? null),
      },
      {
        name: '价值分',
        type: 'line',
        yAxisIndex: 1,
        connectNulls: true,
        data: points.map((item) => item.valueScore ?? null),
      },
    ],
  };
}
