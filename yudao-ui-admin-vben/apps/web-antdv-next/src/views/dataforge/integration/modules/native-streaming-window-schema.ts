export function validateStreamingWindow(value: unknown): string[] {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return ['请在高级配置中明确启用有限实时窗口'];
  const window = value as Record<string, unknown>;
  const keys = ['version', 'kind', 'timeMode', 'sizeMillis', 'maxRows', 'maxBytes', 'emit', 'latePolicy'];
  if (Object.keys(window).some((key) => !keys.includes(key)) || keys.some((key) => !(key in window))) return ['实时窗口合同字段缺失或未知'];
  if (window.version !== 1 || window.kind !== 'TUMBLING' || window.timeMode !== 'PROCESSING_TIME' || window.emit !== 'ON_CLOSE' || window.latePolicy !== 'PROCESS_ON_ARRIVAL') return ['当前仅支持按到达处理时间划分、窗口关闭后输出的滚动窗口'];
  const errors: string[] = [];
  for (const [key, label, minimum, maximum] of [
    ['sizeMillis', '窗口毫秒', 1000, 86_400_000],
    ['maxRows', '每窗每组最大行数', 1, 10_000],
    ['maxBytes', '每窗每组最大字节', 1024, 16_777_216],
  ] as const) {
    const value = window[key];
    if (typeof value !== 'number' || !Number.isInteger(value) || value < minimum || value > maximum) errors.push(`${label}须为${minimum}至${maximum}的整数`);
  }
  return errors;
}
