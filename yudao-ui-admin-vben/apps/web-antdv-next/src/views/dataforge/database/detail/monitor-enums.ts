import type { DatabaseMonitorApi } from '#/api/dataforge/database/monitor';

export const METRIC_OPTIONS = [
  { label: '连接状态', value: 'CONNECTION_STATUS' },
  { label: '响应时间', value: 'RESPONSE_TIME' },
] satisfies { label: string; value: DatabaseMonitorApi.Metric }[];

export const LEVEL_OPTIONS = [
  { label: '一般', value: 'GENERAL' },
  { label: '严重', value: 'SERIOUS' },
  { label: '紧急', value: 'URGENT' },
] satisfies { label: string; value: DatabaseMonitorApi.Level }[];

export const LEVEL_COLORS: Record<DatabaseMonitorApi.Level, string> = {
  GENERAL: 'purple',
  SERIOUS: 'gold',
  URGENT: 'red',
};

export const OPERATOR_OPTIONS = [
  { label: '大于 >', value: 'GT' },
  { label: '大于等于 ≥', value: 'GTE' },
  { label: '小于 <', value: 'LT' },
  { label: '小于等于 ≤', value: 'LTE' },
  { label: '等于 =', value: 'EQ' },
] satisfies { label: string; value: DatabaseMonitorApi.Operator }[];

const OPERATOR_SYMBOLS: Record<DatabaseMonitorApi.Operator, string> = {
  EQ: '=',
  GT: '>',
  GTE: '≥',
  LT: '<',
  LTE: '≤',
};

export const ALARM_STATUS: Record<
  DatabaseMonitorApi.AlarmStatus,
  { color: string; label: string }
> = {
  PENDING: { color: 'gold', label: '未处理' },
  HANDLED: { color: 'green', label: '已处理' },
  IGNORED: { color: 'default', label: '无需处理' },
};

export const LOG_STATUS: Record<
  DatabaseMonitorApi.LogStatus,
  { color: string; label: string }
> = {
  WAITING: { color: 'default', label: '待执行' },
  RUNNING: { color: 'processing', label: '进行中' },
  SUCCESS: { color: 'green', label: '成功' },
  FAILED: { color: 'red', label: '失败' },
  STOPPED: { color: 'default', label: '已停止' },
  UNKNOWN: { color: 'default', label: '未知' },
};

export function metricLabel(metric: DatabaseMonitorApi.Metric) {
  return METRIC_OPTIONS.find((item) => item.value === metric)?.label ?? metric;
}

export function levelLabel(level: DatabaseMonitorApi.Level) {
  return LEVEL_OPTIONS.find((item) => item.value === level)?.label ?? level;
}

export function metricValue(metric: DatabaseMonitorApi.Metric, value: string) {
  if (metric !== 'CONNECTION_STATUS') return `${value} ms`;
  if (value === '0') return '断开';
  return value === '1' ? '连接正常' : value;
}

export function triggerCondition(
  rule: Pick<DatabaseMonitorApi.Rule, 'metric' | 'operator' | 'threshold'>,
) {
  return rule.metric === 'CONNECTION_STATUS'
    ? '连接断开'
    : `${OPERATOR_SYMBOLS[rule.operator] ?? rule.operator} ${rule.threshold} ms`;
}
