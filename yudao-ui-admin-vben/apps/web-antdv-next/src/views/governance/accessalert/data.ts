/** 异常访问规则与状态文案（与后端 SafetyAccessAlertDO 一致）。 */
export const ALERT_RULE_OPTIONS = [
  { label: '敏感访问过多', value: 'VOLUME', unit: '次' },
  { label: '豁免明文访问过多', value: 'PLAINTEXT', unit: '次' },
  { label: '非工作时段访问', value: 'OFF_HOURS', unit: '次' },
  { label: '访问表过多', value: 'TABLE_SPREAD', unit: '张表' },
] as const;

export const ALERT_STATUS_OPTIONS = [
  { label: '待处理', value: 'OPEN', color: 'red' },
  { label: '已确认风险', value: 'CONFIRMED', color: 'orange' },
  { label: '已忽略', value: 'IGNORED', color: 'default' },
] as const;

export function ruleLabel(code: string): string {
  return ALERT_RULE_OPTIONS.find((item) => item.value === code)?.label ?? code;
}

export function ruleUnit(code: string): string {
  return ALERT_RULE_OPTIONS.find((item) => item.value === code)?.unit ?? '';
}

export function statusView(status: string): { color: string; label: string } {
  const found = ALERT_STATUS_OPTIONS.find((item) => item.value === status);
  return found
    ? { color: found.color, label: found.label }
    : { color: 'default', label: status };
}

/** 后端时间为毫秒时间戳或字符串；只用于展示。 */
export function formatTime(value?: number | string): string {
  if (value === undefined || value === null || value === '') return '-';
  const date = new Date(typeof value === 'number' ? value : String(value));
  if (Number.isNaN(date.getTime())) return String(value);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
