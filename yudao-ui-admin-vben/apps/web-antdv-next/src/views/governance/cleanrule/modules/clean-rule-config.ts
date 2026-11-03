export function formatCleanRuleConfig(config: unknown): string {
  if (!config || typeof config !== 'object' || Array.isArray(config))
    return '{}';
  return JSON.stringify(config, null, 2);
}

export function cleanRuleConfigFromExample(example?: string): string {
  if (!example) return '{}';
  try {
    const parsed: unknown = JSON.parse(example);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed))
      return '{}';
    const config = { ...(parsed as Record<string, unknown>) };
    delete config.columns;
    // The boundary function compares arbitrary-precision decimals; never round
    // its example values through JavaScript number serialization.
    for (const key of ['min', 'max']) {
      if (typeof config[key] === 'number') config[key] = String(config[key]);
    }
    return formatCleanRuleConfig(config);
  } catch {
    return '{}';
  }
}

export function parseCleanRuleConfig(text: string): Record<string, unknown> {
  const parsed: unknown = JSON.parse(text);
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new TypeError('清洗函数参数必须是 JSON 对象');
  }
  const config = parsed as Record<string, unknown>;
  if (Object.hasOwn(config, 'columns')) {
    throw new TypeError('字段绑定由 ETL 节点提供，不能保存在函数参数中');
  }
  if (['min', 'max'].some((key) => typeof config[key] === 'number')) {
    throw new TypeError('最小值和最大值必须使用 JSON 字符串，避免精度丢失');
  }
  return config;
}
