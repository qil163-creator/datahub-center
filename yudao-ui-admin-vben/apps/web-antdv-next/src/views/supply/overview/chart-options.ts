import type { EChartsOption } from '@vben/plugins/echarts';

import type { TrendPoint } from './data';

const CHART_BLUE = '#5ab1ef';

/** 近 N 天 API 调用趋势折线图 */
export function getTrendChartOptions(points: TrendPoint[]): EChartsOption {
  return {
    grid: {
      bottom: 8,
      containLabel: true,
      left: '1%',
      right: '2%',
      top: 16,
    },
    series: [
      {
        data: points.map((item) => item.count),
        itemStyle: { color: CHART_BLUE },
        lineStyle: { color: CHART_BLUE, width: 2 },
        name: '调用次数',
        showSymbol: true,
        symbol: 'circle',
        symbolSize: 8,
        type: 'line',
      },
    ],
    tooltip: {
      trigger: 'axis',
    },
    xAxis: {
      axisTick: { show: false },
      data: points.map((item) => item.date),
      type: 'category',
    },
    yAxis: {
      min: 0,
      minInterval: 1,
      name: '次数',
      splitNumber: 6,
      type: 'value',
    },
  };
}
