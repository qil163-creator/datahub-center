import type { ChartModel } from '../query/state';

import type { IndicatorModelApi } from '#/api/modeling/indicator-model';

import { chartModel } from '../query/state';

export const BOARD_MAX_WIDGETS = 12;

export const CHART_TYPE_OPTIONS = [
  { label: '折线图', value: 'LINE' },
  { label: '柱状图', value: 'BAR' },
  { label: '表格', value: 'TABLE' },
  { label: '指标卡', value: 'STAT' },
] as const;

/** 新组件标识：看板内唯一。 */
export function nextWidgetKey(
  existing: IndicatorModelApi.BoardWidget[],
): string {
  const used = new Set(existing.map((widget) => widget.key));
  let index = existing.length + 1;
  while (used.has(`w${index}`)) index++;
  return `w${index}`;
}

/** 组件表单校验（后端仍会完整校验）。 */
export function widgetError(
  widget: IndicatorModelApi.BoardWidget,
): string | undefined {
  if (!widget.title.trim()) return '请填写组件标题';
  if (widget.title.trim().length > 60) return '组件标题不能超过 60 个字符';
  if (
    widget.query.indicatorIds.length === 0 ||
    widget.query.indicatorIds.length > 10
  ) {
    return '请选择 1-10 个指标';
  }
  if ((widget.query.dimensions?.length ?? 0) > 5) return '维度最多 5 个';
  if (
    (widget.chartType === 'LINE' || widget.chartType === 'BAR') &&
    !widget.query.timeGrain
  ) {
    return '折线图和柱状图需要选择时间粒度';
  }
  return undefined;
}

/** 指标卡：第一行的第一个指标值（十进制字符串原样展示）。 */
export function statValue(result?: IndicatorModelApi.QueryResult): {
  title: string;
  value: string;
} {
  const column = result?.columns.find((item) => item.kind === 'INDICATOR');
  if (!result || !column) return { title: '-', value: '-' };
  const value = result.rows[0]?.[column.key];
  return { title: column.title, value: value ?? '-' };
}

/** 图表模型复用指标查询页的规则（时间桶为横轴）。 */
export function boardChart(
  result?: IndicatorModelApi.QueryResult,
): ChartModel | undefined {
  return chartModel(result);
}
