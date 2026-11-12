/** 指标接口配置（与后端 DataApiIndicatorConfig 一致）。发布时固定口径，调用方只能通过声明的参数传时间范围与维度过滤。 */
export interface IndicatorApiConfig {
  indicatorIds: (number | string)[];
  dimensions: string[];
  timeGrain?: string;
  limit?: number;
  startParam?: string;
  endParam?: string;
  filters: { fieldUid: string; param: string }[];
}

const PARAM = /^[A-Z_a-z]\w{0,63}$/;

export function emptyIndicatorConfig(): IndicatorApiConfig {
  return { indicatorIds: [], dimensions: [], filters: [], limit: 200 };
}

export function parseIndicatorConfig(json?: null | string): IndicatorApiConfig {
  if (!json) return emptyIndicatorConfig();
  try {
    const value = JSON.parse(json) as Partial<IndicatorApiConfig>;
    return {
      ...emptyIndicatorConfig(),
      ...value,
      indicatorIds: value.indicatorIds ?? [],
      dimensions: value.dimensions ?? [],
      filters: value.filters ?? [],
    };
  } catch {
    return emptyIndicatorConfig();
  }
}

/** 序列化：去掉空值，ID 保持原样（不做数值转换）。 */
export function serializeIndicatorConfig(config: IndicatorApiConfig): string {
  const value: Record<string, unknown> = {
    indicatorIds: config.indicatorIds,
    dimensions: config.dimensions,
  };
  if (config.timeGrain) value.timeGrain = config.timeGrain;
  if (config.limit) value.limit = config.limit;
  if (config.startParam && config.endParam) {
    value.startParam = config.startParam;
    value.endParam = config.endParam;
  }
  const filters = config.filters.filter((item) => item.param && item.fieldUid);
  if (filters.length > 0) value.filters = filters;
  return JSON.stringify(value);
}

/** 配置引用的请求参数名。 */
export function referencedParams(config: IndicatorApiConfig): string[] {
  const names: string[] = [];
  if (config.startParam && config.endParam)
    names.push(config.startParam, config.endParam);
  config.filters.forEach((item) => item.param && names.push(item.param));
  return names;
}

/** 前端预校验；后端仍会完整校验。 */
export function indicatorConfigError(
  config: IndicatorApiConfig,
  declaredParams: string[],
): string | undefined {
  if (config.indicatorIds.length === 0 || config.indicatorIds.length > 10) {
    return '请选择 1-10 个指标';
  }
  if (config.dimensions.length > 5) return '维度最多 5 个';
  if (config.limit !== undefined && (config.limit < 1 || config.limit > 1000)) {
    return '行数上限应为 1-1000';
  }
  if (Boolean(config.startParam) !== Boolean(config.endParam)) {
    return '开始与结束日期参数需同时配置';
  }
  const names = referencedParams(config);
  const invalid = names.find((name) => !PARAM.test(name));
  if (invalid) return `请求参数名不合法：${invalid}`;
  if (new Set(names).size !== names.length) return '请求参数名重复';
  const missing = names.find((name) => !declaredParams.includes(name));
  if (missing) return `请在“请求参数”中声明：${missing}`;
  return undefined;
}
