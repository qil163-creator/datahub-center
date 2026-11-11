import type { EChartsOption } from '@vben/plugins/echarts';

/**
 * 首页看板的 ECharts 配置构建函数。
 * 数据形状直接对应各统计接口的返回，避免在页面里再做一次转换。
 */

export const CHART_COLORS = {
  blue: '#2f7df6',
  sky: '#0ea5e9',
  teal: '#14b8a6',
  green: '#18a058',
  amber: '#f59e0b',
  red: '#d03050',
  violet: '#7c3aed',
  slate: '#64748b',
};

const PALETTE = [
  CHART_COLORS.blue,
  CHART_COLORS.teal,
  CHART_COLORS.amber,
  CHART_COLORS.violet,
  CHART_COLORS.sky,
  CHART_COLORS.green,
  CHART_COLORS.red,
  CHART_COLORS.slate,
];

export interface NameValue {
  name: string;
  value: number;
}

const TREND_GRID = {
  bottom: 8,
  containLabel: true,
  left: '1%',
  right: '2%',
  top: 40,
};

/** API 调用趋势：成功 / 失败 堆叠柱 */
export function getApiTrendOptions(
  trend: { date?: string; failCount?: number; successCount?: number }[],
): EChartsOption {
  return {
    grid: TREND_GRID,
    // 图例靠右，避免在窄卡片里压住左上角的纵轴名称
    legend: { right: 0, top: 4 },
    series: [
      {
        barMaxWidth: 28,
        data: trend.map((item) => item.successCount ?? 0),
        itemStyle: { color: CHART_COLORS.green },
        name: '调用成功',
        stack: 'total',
        type: 'bar',
      },
      {
        barMaxWidth: 28,
        data: trend.map((item) => item.failCount ?? 0),
        itemStyle: { color: CHART_COLORS.red },
        name: '调用失败',
        stack: 'total',
        type: 'bar',
      },
    ],
    tooltip: { trigger: 'axis' },
    xAxis: {
      axisTick: { show: false },
      data: trend.map((item) => item.date ?? ''),
      type: 'category',
    },
    yAxis: { min: 0, minInterval: 1, name: '次数', type: 'value' },
  };
}

/** 质量工单趋势：新增 / 处理 双折线 */
export function getWorkOrderTrendOptions(
  trend: { createdCount: number; date: string; processedCount: number }[],
): EChartsOption {
  return {
    grid: TREND_GRID,
    // 图例靠右，避免在窄卡片里压住左上角的纵轴名称
    legend: { right: 0, top: 4 },
    series: [
      {
        areaStyle: { opacity: 0.12 },
        data: trend.map((item) => item.createdCount),
        itemStyle: { color: CHART_COLORS.amber },
        name: '新增工单',
        smooth: true,
        type: 'line',
      },
      {
        areaStyle: { opacity: 0.12 },
        data: trend.map((item) => item.processedCount),
        itemStyle: { color: CHART_COLORS.green },
        name: '处理完成',
        smooth: true,
        type: 'line',
      },
    ],
    tooltip: { trigger: 'axis' },
    xAxis: {
      axisTick: { show: false },
      boundaryGap: false,
      data: trend.map((item) => item.date),
      type: 'category',
    },
    yAxis: { min: 0, minInterval: 1, name: '单', type: 'value' },
  };
}

interface MetaChangePoint {
  addCount?: number;
  day?: string;
  deleteCount?: number;
  modifyCount?: number;
}

/** 元数据变更趋势：新增 / 修改 / 删除 堆叠柱 */
export function getMetaChangeTrendOptions(
  trend: MetaChangePoint[],
): EChartsOption {
  function stackedBar(
    name: string,
    color: string,
    pick: (item: MetaChangePoint) => number | undefined,
  ) {
    return {
      barMaxWidth: 24,
      data: trend.map((item) => pick(item) ?? 0),
      itemStyle: { color },
      name,
      stack: 'change',
      type: 'bar' as const,
    };
  }
  return {
    grid: TREND_GRID,
    legend: { top: 4 },
    series: [
      stackedBar('新增', CHART_COLORS.green, (item) => item.addCount),
      stackedBar('修改', CHART_COLORS.blue, (item) => item.modifyCount),
      stackedBar('删除', CHART_COLORS.red, (item) => item.deleteCount),
    ],
    tooltip: { trigger: 'axis' },
    xAxis: {
      axisTick: { show: false },
      data: trend.map((item) => item.day ?? ''),
      type: 'category',
    },
    yAxis: { min: 0, minInterval: 1, name: '次', type: 'value' },
  };
}

/** 敏感数据访问趋势：访问总数 / 明文豁免 */
export function getAccessTrendOptions(
  trend: { date?: string; exempt?: number; total?: number }[],
): EChartsOption {
  return {
    grid: TREND_GRID,
    legend: { top: 4 },
    series: [
      {
        areaStyle: { opacity: 0.12 },
        data: trend.map((item) => item.total ?? 0),
        itemStyle: { color: CHART_COLORS.blue },
        name: '敏感数据访问',
        smooth: true,
        type: 'line',
      },
      {
        data: trend.map((item) => item.exempt ?? 0),
        itemStyle: { color: CHART_COLORS.red },
        name: '明文豁免',
        smooth: true,
        type: 'line',
      },
    ],
    tooltip: { trigger: 'axis' },
    xAxis: {
      axisTick: { show: false },
      boundaryGap: false,
      data: trend.map((item) => item.date ?? ''),
      type: 'category',
    },
    yAxis: { min: 0, minInterval: 1, name: '次', type: 'value' },
  };
}

/** 质量检核趋势：检核次数（柱，左轴）+ 通过率（线，右轴 0~100%）；无检核的日期通过率留空 */
export function getQualityTrendOptions(
  trend: { checkCount: number; date: string; passRate: null | number }[],
): EChartsOption {
  return {
    grid: TREND_GRID,
    legend: { top: 4 },
    series: [
      {
        barMaxWidth: 28,
        data: trend.map((item) => item.checkCount),
        itemStyle: { color: CHART_COLORS.blue },
        name: '检核次数',
        type: 'bar',
      },
      {
        connectNulls: false,
        data: trend.map((item) => item.passRate),
        itemStyle: { color: CHART_COLORS.green },
        name: '通过率',
        smooth: true,
        type: 'line',
        yAxisIndex: 1,
      },
    ],
    tooltip: {
      trigger: 'axis',
      valueFormatter: (value) =>
        value === null || value === undefined ? '-' : String(value),
    },
    xAxis: {
      axisTick: { show: false },
      data: trend.map((item) => item.date),
      type: 'category',
    },
    yAxis: [
      { min: 0, minInterval: 1, name: '次', type: 'value' },
      {
        axisLabel: { formatter: '{value}%' },
        max: 100,
        min: 0,
        name: '通过率',
        splitLine: { show: false },
        type: 'value',
      },
    ],
  };
}

/** 雷达图（质量六维通过率等，取值 0~100） */
export function getRadarOptions(
  indicators: { max?: number; name: string }[],
  values: number[],
  seriesName: string,
  color: string = CHART_COLORS.blue,
): EChartsOption {
  return {
    radar: {
      axisName: { color: '#8c8c8c', fontSize: 12 },
      center: ['50%', '52%'],
      indicator: indicators.map((item) => ({
        max: item.max ?? 100,
        name: item.name,
      })),
      radius: '68%',
      splitNumber: 4,
    },
    series: [
      {
        areaStyle: { opacity: 0.2 },
        data: [{ name: seriesName, value: values }],
        itemStyle: { color },
        lineStyle: { color, width: 2 },
        symbolSize: 5,
        type: 'radar',
      },
    ],
    tooltip: { trigger: 'item' },
  };
}

/** 环形占比图（资产类目 / 工单状态 / 安全分级等） */
export function getDonutOptions(
  items: NameValue[],
  seriesName: string,
  colors: string[] = PALETTE,
): EChartsOption {
  return {
    color: colors,
    legend: {
      bottom: 0,
      icon: 'circle',
      itemHeight: 8,
      itemWidth: 8,
      type: 'scroll',
    },
    series: [
      {
        avoidLabelOverlap: true,
        center: ['50%', '45%'],
        data: items,
        emphasis: {
          label: { fontWeight: 'bold', show: true },
        },
        itemStyle: { borderRadius: 6, borderWidth: 2 },
        label: { show: false },
        labelLine: { show: false },
        name: seriesName,
        radius: ['45%', '70%'],
        type: 'pie',
      },
    ],
    tooltip: { formatter: '{b}：{c}（{d}%）', trigger: 'item' },
  };
}
