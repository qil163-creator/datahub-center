import type { IndicatorModelApi } from '#/api/modeling/indicator-model';

export const QUERY_MAX_INDICATORS = 10;
export const QUERY_MAX_DIMENSIONS = 5;
export const QUERY_MAX_FILTERS = 10;
export const QUERY_DEFAULT_LIMIT = 200;
export const QUERY_MAX_LIMIT = 1000;
export const CHART_MAX_SERIES = 20;

export interface QueryFilterRow {
  fieldUid?: string;
  value?: string;
}

export interface QueryFormState {
  dimensions: string[];
  filters: QueryFilterRow[];
  indicatorIds: string[];
  limit?: number;
  /** RangePicker 使用 value-format="YYYY-MM-DD"，值已是日期字符串 */
  range?: [string, string];
  timeGrain?: IndicatorModelApi.Period;
}

/** 由表单组装请求；ID 保持字符串原样，不做数值转换。 */
export function buildQueryRequest(
  form: QueryFormState,
): IndicatorModelApi.QueryReq {
  const filters = form.filters
    .filter(
      (item) => item.fieldUid && item.value !== undefined && item.value !== '',
    )
    .map((item) => ({ fieldUid: item.fieldUid!, value: item.value! }));
  const limit =
    form.limit && Number.isInteger(form.limit)
      ? Math.min(Math.max(form.limit, 1), QUERY_MAX_LIMIT)
      : QUERY_DEFAULT_LIMIT;
  return {
    indicatorIds: [...new Set(form.indicatorIds.map(String))],
    dimensions: form.dimensions.length > 0 ? [...form.dimensions] : undefined,
    filters: filters.length > 0 ? filters : undefined,
    timeRange:
      form.range && form.range[0] && form.range[1]
        ? { start: form.range[0], end: form.range[1] }
        : undefined,
    timeGrain: form.timeGrain,
    limit,
  };
}

/** 表格稳定行键：维度与时间桶组合在结果内唯一。 */
export function rowKey(
  row: Record<string, null | string>,
  columns: IndicatorModelApi.QueryColumn[],
) {
  return JSON.stringify(
    columns
      .filter((column) => column.kind !== 'INDICATOR')
      .map((column) => row[column.key] ?? null),
  );
}

export interface ChartModel {
  categories: string[];
  series: { data: (null | string)[]; name: string }[];
  /** 系列数超过上限被省略 */
  omitted: boolean;
}

/**
 * 折线图模型：横轴为时间桶，每个指标 × 维度组合一条线。
 * 值保持后端十进制字符串交给图表做近似展示，不经过 Number/parseFloat。
 */
export function chartModel(
  result?: IndicatorModelApi.QueryResult,
): ChartModel | undefined {
  if (!result) return undefined;
  const time = result.columns.find((column) => column.kind === 'TIME');
  if (!time) return undefined;
  const dimensions = result.columns.filter(
    (column) => column.kind === 'DIMENSION',
  );
  const indicators = result.columns.filter(
    (column) => column.kind === 'INDICATOR',
  );
  const categories: string[] = [];
  const position = new Map<string, number>();
  for (const row of result.rows) {
    const bucket = row[time.key] ?? '(空)';
    if (!position.has(bucket)) {
      position.set(bucket, categories.length);
      categories.push(bucket);
    }
  }
  const series = new Map<string, (null | string)[]>();
  let omitted = false;
  for (const row of result.rows) {
    const group = dimensions
      .map((column) => row[column.key] ?? '(空)')
      .join(' / ');
    const index = position.get(row[time.key] ?? '(空)')!;
    for (const indicator of indicators) {
      const name = group ? `${indicator.title} · ${group}` : indicator.title;
      let data = series.get(name);
      if (!data) {
        if (series.size >= CHART_MAX_SERIES) {
          omitted = true;
          continue;
        }
        data = Array.from({ length: categories.length }, () => null);
        series.set(name, data);
      }
      data[index] = row[indicator.key] ?? null;
    }
  }
  return {
    categories,
    series: [...series.entries()].map(([name, data]) => ({ name, data })),
    omitted,
  };
}

/** 口径摘要文本。 */
export function caliberSummary(caliber: IndicatorModelApi.Caliber) {
  return caliber.leaves.map((leaf) => {
    const parts = [`${leaf.aggregation}(${leaf.valueField})`];
    if (leaf.timePeriod) parts.push(`周期 ${leaf.timePeriod}`);
    if (leaf.conditions.length > 0)
      parts.push(
        `限定 ${leaf.conditions.map((item) => `${item.name}: ${item.expression}`).join('；')}`,
      );
    const source = leaf.source;
    parts.push(
      `来源 ${source.datasourceName ?? source.datasourceId} · ${source.schemaName ? `${source.schemaName}.` : ''}${source.tableName}`,
    );
    return `${leaf.name} (${leaf.code})：${parts.join('，')}`;
  });
}
